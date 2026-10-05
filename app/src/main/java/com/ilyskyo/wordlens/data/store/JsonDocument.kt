// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.store

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A single JSON document on disk, exposed as a [StateFlow], written atomically.
 *
 * Why a plain file and not Room: the deck is the app's only source of truth and it is meant to
 * be *auditable* — a user can pull `deck.json` off the device, read it, diff it, or paste it
 * into an issue. A binary database would be faster to query and impossible to inspect. For a
 * hobby vocabulary deck (thousands of rows, not millions) the file is the right trade.
 *
 * Failure behaviour is the important part, because this file holds everything the user has
 * ever learned:
 *
 * - writes go to a temp file and are renamed into place, so a kill mid-write cannot truncate
 *   the live document;
 * - a document that fails to parse is *moved aside*, never deleted and never silently reset,
 *   so the user can recover it by hand;
 * - reads that fail return the default, and the first successful write starts a fresh document
 *   at a new path rather than overwriting the evidence.
 */
class JsonDocument<T>(
    private val file: File,
    private val fallback: () -> T,
    private val serializer: KSerializer<T>,
    private val scope: CoroutineScope,
    private val json: Json = WordLensJson.instance,
    private val tag: String = file.name,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(fallback())

    /** Current contents. Emits [fallback] until [load] completes. */
    val state: StateFlow<T> = _state.asStateFlow()

    /** The same value as [state] without collecting — for widget callbacks and logs. */
    val current: T get() = _state.value

    var loaded: Boolean = false
        private set

    /**
     * Read the document from disk. Safe to call more than once; later calls are no-ops unless
     * [reload] is used.
     */
    suspend fun load(force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded && !force) return@withLock
            _state.value = readFromDisk()
            loaded = true
        }
    }

    /** Fire-and-forget load for `Application.onCreate`. */
    fun loadAsync() {
        scope.launch { load() }
    }

    /** Read-modify-write under the lock, then persist. */
    suspend fun update(block: (T) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = block(_state.value)
            _state.value = next
            writeToDisk(next)
            next
        }
    }

    /**
     * Synchronous read for callers that cannot suspend (AppWidgetProvider.onUpdate runs on the
     * main thread with a 30s budget, and blocking a couple of hundred KB of JSON is far cheaper
     * than spawning a coroutine that outlives the broadcast).
     */
    fun readBlocking(): T {
        if (loaded) return _state.value
        val parsed = readFromDisk()
        _state.value = parsed
        loaded = true
        return parsed
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun readFromDisk(): T {
        if (!file.exists()) return fallback()
        return try {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // Preserve the damaged bytes. Losing a learner's deck silently is unacceptable, and
            // so is refusing to start.
            val salvaged = moveAside()
            Log.e(
                tag,
                "Corrupt document at ${file.absolutePath} (${e::class.simpleName}: ${e.message}). " +
                    "Moved to $salvaged; starting a fresh document. The old file is intact.",
            )
            fallback()
        }
    }

    private fun moveAside(): String {
        val stamp = System.currentTimeMillis()
        val target = File(file.parentFile, "${file.name}.corrupt-$stamp")
        return if (file.renameTo(target)) target.absolutePath else file.absolutePath
    }

    private fun writeToDisk(value: T) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, value), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // renameTo can fail across some filesystems/AV interceptors; fall back to a
                // copy so the update is not lost.
                file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to persist ${file.absolutePath}", e)
        }
    }
}

/** One [Json] configuration for the whole app, so every document round-trips the same way. */
object WordLensJson {
    val instance: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true // tolerate documents written by a newer build
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }
}
