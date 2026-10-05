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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

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
 * - 写入先落到 `*.tmp` 并 `fd.sync()`，再改名到位。改名不动时**不截断重写目标文件**，
 *   而是把旧文件挪成 `.bak` 再试一次；再不行就还原并报告失败（见 [RealDiskOps.write]）。
 * - 读失败分成两类，处理方式完全相反：**读不到**（IOException）时保持内存里的内容、
 *   并且拒绝落盘——一次瞬时抖动不该让下一次写入覆盖掉我们其实没读懂的数据；
 *   **解析不了**才把损坏文件改名留证（`.corrupt-<时间戳>`，改名不动就复制一份），从空文档启动。
 * - 磁盘上的 `schemaVersion` 比这个构建还新时，同样拒绝落盘：旧版本不该把新格式改写成自己认识的样子。
 * - 留证都失败时也不写回空文档。宁可这次编辑丢掉，也不要让「数据不见了」变成无法归因的事。
 */
class JsonDocument<T>(
    private val file: File,
    private val fallback: () -> T,
    private val serializer: KSerializer<T>,
    private val scope: CoroutineScope,
    private val json: Json = WordLensJson.instance,
    private val tag: String = file.name,
    private val disk: DiskOps = RealDiskOps,
    /** 这个构建认识的文档格式版本。磁盘上的比它还新时只读不写。 */
    private val currentSchema: Int = Int.MAX_VALUE,
    private val schemaOf: (T) -> Int = { currentSchema },
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(fallback())

    /** Current contents. Emits [fallback] until [load] completes. */
    val state: StateFlow<T> = _state.asStateFlow()

    /** The same value as [state] without collecting — for widget callbacks and logs. */
    val current: T get() = _state.value

    /**
     * 磁盘内容读回来过没有。
     *
     * 与 [persisting] 一起只在这个类的 `mutex.withLock` 里被写——所以这里没有、也不该有
     * 「同步读一份」的旁路入口（曾经有一个 `readBlocking()`，声称是给小组件用的，而小组件
     * 从头到尾没调用过它：它在主线程上绕过锁读文件、再写 `_state`/`loaded`/`persisting`，
     * 和冷启动那次 `load()` 是两次并发的磁盘读，输的那个会把文档打回旧内容）。
     * 要在不能挂起的回调里拿数据，就读 [current]——它取的是已经收敛好的那份状态。
     */
    var loaded: Boolean = false
        private set

    /**
     * false 表示**只在内存里改、不落盘**。
     *
     * 触发条件是「我们其实还没读懂磁盘上的内容」：读操作失败、或者磁盘上的
     * `schemaVersion` 比这个构建还新。这两种情况下把新内容写下去，覆盖的是我们没有读过的数据。
     */
    var persisting: Boolean = true
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

    /** 读失败之后重试一次。成功会恢复 [persisting]，返回是否已经可以落盘。 */
    suspend fun retryLoad(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            _state.value = readFromDisk()
            loaded = true
            persisting
        }
    }

    /** Read-modify-write under the lock, then persist. */
    suspend fun update(block: (T) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = block(_state.value)
            _state.value = next
            if (persisting) {
                writeToDisk(next)
            } else {
                Log.e(
                    tag,
                    "Not writing $tag: the document on disk was not read successfully " +
                        "(or is newer than this build). Editing in memory only, so a transient " +
                        "read failure cannot overwrite data we have not looked at.",
                )
            }
            next
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun readFromDisk(): T {
        if (!disk.exists(file)) {
            persisting = true
            return fallback()
        }
        val text = try {
            disk.readText(file)
        } catch (t: Throwable) {
            return refuseWrite(t, "could not be read")
        }
        val decoded = try {
            json.decodeFromString(serializer, text)
        } catch (t: Throwable) {
            return when (classifyReadFailure(t)) {
                ReadFailure.Corrupt -> salvage(t)
                else -> refuseWrite(t, "could not be read")
            }
        }
        val version = schemaOf(decoded)
        if (version > currentSchema) {
            Log.e(
                tag,
                "Document at ${file.absolutePath} has schemaVersion $version, newer than this " +
                    "build's $currentSchema. Showing it read-only: writing back would rewrite " +
                    "fields this build does not understand.",
            )
            persisting = false
            return decoded
        }
        persisting = true
        return decoded
    }

    /**
     * 解析失败：把损坏的字节留证，再从空文档启动。
     *
     * 留证失败（改名与复制都不动）时**不写回空文档**——那时宁可维持只读，也不能让
     * 「用户的数据不见了」变成一件无从追查的事。
     */
    private fun salvage(cause: Throwable): T {
        val salvaged = preserveCorrupt()
        if (salvaged == null) {
            persisting = false
            Log.e(
                tag,
                "Document at ${file.absolutePath} is unparseable (${cause::class.simpleName}: " +
                    "${cause.message}) and the bytes could not be preserved. Refusing to overwrite.",
                cause,
            )
            return _state.value
        }
        persisting = true
        Log.e(
            tag,
            "Corrupt document at ${file.absolutePath} (${cause::class.simpleName}: ${cause.message}). " +
                "Preserved at $salvaged; starting a fresh document.",
            cause,
        )
        return fallback()
    }

    private fun preserveCorrupt(): String? {
        val target = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        return when {
            disk.rename(file, target) -> target.absolutePath
            disk.copy(file, target) -> target.absolutePath
            else -> null
        }
    }

    private fun refuseWrite(cause: Throwable, why: String): T {
        persisting = false
        Log.e(
            tag,
            "Document at ${file.absolutePath} $why (${cause::class.simpleName}: ${cause.message}). " +
                "Keeping the in-memory document and refusing to persist until a read succeeds.",
            cause,
        )
        return _state.value
    }

    private fun writeToDisk(value: T) {
        val text = try {
            json.encodeToString(serializer, value)
        } catch (t: Throwable) {
            Log.e(tag, "Could not serialise $tag; nothing was written", t)
            return
        }
        when (val result = disk.write(file, text)) {
            WriteResult.Written -> Unit
            is WriteResult.Failed -> Log.e(tag, "Failed to persist ${file.absolutePath}", result.cause)
        }
    }
}

/** 读文档失败的种类——处理方式相反，所以必须分开判断。 */
enum class ReadFailure { Io, Corrupt, Unknown }

/**
 * 把读失败分类。**[ReadFailure.Unknown] 走「没读懂」那一侧**：不确定时不改名也不覆盖，
 * 猜错的代价只是这一次编辑没落盘，而另一种猜法的代价是用户的全部数据。
 *
 * 沿 cause 链判断而不是只看顶层类型：`java.nio` 与 Kotlin 的包装层常把真正的
 * [IOException] 塞进 [UncheckedIOException] 里，而 `UncheckedIOException` 继承的是
 * `RuntimeException` —— 只看顶层就会把一次存储抖动误判成「文件坏了」，然后改名、清空。
 */
fun classifyReadFailure(t: Throwable): ReadFailure {
    var current: Throwable? = t
    var hops = 0
    while (current != null && hops < MAX_CAUSE_HOPS) {
        when {
            current is SerializationException -> return ReadFailure.Corrupt
            current is IOException || current is SecurityException -> return ReadFailure.Io
        }
        current = current.cause?.takeIf { it !== current }
        hops++
    }
    return ReadFailure.Unknown
}

private const val MAX_CAUSE_HOPS = 8

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
