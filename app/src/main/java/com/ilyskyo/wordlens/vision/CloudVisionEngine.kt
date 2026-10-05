// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Optional vision backend against a bring-your-own-key Anthropic-compatible endpoint.
 *
 * ## Why this is opt-in and never the default
 *
 * The on-device path exists so the app can claim photos do not leave the phone. A cloud
 * recogniser is genuinely better at naming things — it is the only realistic way to answer "what
 * is this dish" — but switching it on ends that claim, so:
 *
 * - it stays **off** until the user supplies their own key;
 * - the key is stored on the device only and sent nowhere except in the request they triggered;
 * - the photo is downscaled and JPEG-re-encoded before it leaves, which also drops every EXIF
 *   field, so the GPS coordinate of the user's home is not attached to an outbound image;
 * - nothing is sent automatically. Every call is a deliberate tap.
 *
 * ## Output handling
 *
 * The model is asked for strict JSON but is not trusted to produce it: the reply is parsed
 * defensively and anything unrecognisable degrades to plain-text lines. Models change under you;
 * a vocabulary app should not crash because one started adding a preamble.
 */
class CloudVisionEngine(
    private val apiKey: String,
    val model: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : RecognitionEngine {

    override val id = RecognitionEngineId.CLOUD

    override val requiresNetwork = true

    override fun unavailableReason(): String? = when {
        apiKey.isBlank() -> "No API key has been entered."
        model.isBlank() -> "No model has been selected."
        else -> null
    }

    /** True when these are the credentials this engine was built with. */
    fun matches(otherModel: String, otherKey: String): Boolean =
        model == otherModel && apiKey == otherKey

    override suspend fun label(bitmap: Bitmap): List<RawLabel> = withContext(Dispatchers.IO) {
        unavailableReason()?.let {
            Log.i(TAG, "skipped: $it")
            return@withContext emptyList()
        }

        val encoded = encodeImage(bitmap)
        if (encoded == null) {
            Log.w(TAG, "could not JPEG-encode the photo")
            return@withContext emptyList()
        }

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", encoded)
                                    }
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", PROMPT)
                                },
                            )
                        }
                    },
                )
            }
        }.toString()

        val response = post(body) ?: return@withContext emptyList()
        parse(response)
    }

    override fun close() = Unit

    // ── transport ────────────────────────────────────────────────────────────

    private fun post(body: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                setRequestProperty("accept-encoding", "gzip")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { readMaybeGzip(it, connection.contentEncoding) }.orEmpty()
            if (status !in 200..299) {
                // Surface the reason: a bare "failed" leaves the user with nothing to act on.
                Log.w(TAG, "HTTP $status: ${text.take(300)}")
                return null
            }
            text
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readMaybeGzip(stream: InputStream, encoding: String?): String {
        val source = if (encoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(stream)
        } else {
            stream
        }
        return source.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * JPEG-encode a downscaled copy.
     *
     * Downscaling does double duty: it keeps the request small, and re-encoding through
     * `Bitmap.compress` discards every EXIF field, so a photo taken at home does not carry its
     * own coordinates to a third party.
     */
    private fun encodeImage(bitmap: Bitmap): String? {
        val scaled = scaleDown(bitmap, MAX_EDGE)
        val buffer = ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)
        if (!ok) return null
        return Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= maxEdge) return source
        val ratio = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    private fun parse(raw: String): List<RawLabel> {
        val reply = extractReplyText(raw) ?: return parseLoose(raw)
        return parseStructured(reply) ?: parseLoose(reply)
    }

    /** The assistant's text out of a Messages-API envelope. */
    private fun extractReplyText(raw: String): String? = runCatching {
        val content = JSON.parseToJsonElement(raw).jsonObject["content"] as? JsonArray ?: return null
        content.firstNotNullOfOrNull { block ->
            (block as? JsonObject)?.primitiveText("text")
        }
    }.getOrNull()

    private fun parseStructured(text: String): List<RawLabel>? {
        val words = runCatching {
            val array = JSON.parseToJsonElement(text.stripFences()).jsonObject["words"] as? JsonArray
            array ?: return null
            array
        }.getOrNull() ?: return null

        val out = mutableListOf<RawLabel>()
        for (element in words) {
            val entry = element as? JsonObject ?: continue
            val headword = entry.primitiveText("headword") ?: entry.primitiveText("word") ?: continue
            if (headword.isBlank()) continue
            val confidence = entry.primitiveText("confidence")?.toFloatOrNull() ?: 0.8f
            val score = confidence.coerceIn(0f, 1f)

            out += RawLabel(headword, score)
            // Attach the translations as labels too, so a match can land on the entry from any
            // language the user happens to photograph text in.
            for (key in TRANSLATION_KEYS) {
                entry.primitiveText(key)?.let { out += RawLabel(it, score) }
            }
            entry.primitiveText("ipa")?.let { out += RawLabel(it, score) }
        }
        return out
            .distinctBy { it.text.lowercase() }
            .take(MAX_LABELS)
            .ifEmpty { null }
    }

    /** Anything the model returns that is not our JSON: read it as one word per line. */
    private fun parseLoose(text: String): List<RawLabel> = text.lineSequence()
        .map { it.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim() }
        .filter { it.isNotEmpty() && it.length <= 40 }
        .map { RawLabel(it, LOOSE_CONFIDENCE) }
        .distinctBy { it.text.lowercase() }
        .take(MAX_LABELS)
        .toList()

    private fun JsonObject.primitiveText(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    /** Models like to wrap JSON in a code fence despite being told not to. */
    private fun String.stripFences(): String = trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    companion object {
        private const val TAG = "CloudVision"

        private val JSON = Json { ignoreUnknownKeys = true }

        private val TRANSLATION_KEYS = listOf("zh", "zh-CN", "ja", "ko")

        const val DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"

        /** Vision models resize well below full resolution; this keeps requests cheap. */
        const val MAX_EDGE = 1400
        const val JPEG_QUALITY = 82
        const val MAX_TOKENS = 700
        const val MAX_LABELS = 12
        const val TIMEOUT_MS = 25_000

        /** Lower than on-device output, because unstructured free text is a weaker signal. */
        const val LOOSE_CONFIDENCE = 0.55f

        /**
         * Asks for exactly what a dictionary entry needs — headword, IPA, and the translations
         * in the four shipped languages — so a reply can be used directly instead of being
         * re-interpreted.
         */
        const val PROMPT = """
Name the things visible in this photo, for a vocabulary learner.

Reply with JSON only: no prose, no code fence.
{"words":[{"headword":"...","ipa":"...","zh":"...","ja":"...","ko":"...","confidence":0.0}]}

- headword: the most likely English name, lowercase and singular.
- ipa: IPA in slashes, or null when unsure. Never guess a transcription.
- zh / ja / ko: the usual word for it in Chinese, Japanese and Korean, or null when unsure.
- confidence: 0.0-1.0. Be strict. A wrong word here gets memorised for weeks.
- Concrete objects first. Add abstract or situational words only when the photo clearly depicts
  that situation (a kitchen may imply "recipe"; an office may imply "schedule"). Never invent a
  word you cannot see support for.
- At most 8 entries.
"""
    }
}
