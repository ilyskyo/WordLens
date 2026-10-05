// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 * Reply parsing lives in [CloudReply] — it is the one place in this chain that reads input it
 * does not control, and it needs to be testable without a network or a device.
 */
class CloudVisionEngine(
    private val apiKey: String,
    val model: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : RecognitionEngine {

    override val id = RecognitionEngineId.CLOUD

    override val requiresNetwork = true

    /**
     * 不能出这条请求的理由，按严重程度排。
     *
     * 第一条是**密钥的去向**：endpoint 今天是编译期常量，所以这条看着像不会发生；写在这里
     * 是因为它一旦变成可设置的（这是这个后端最自然的下一步演进），错的那一半就是「用户的 API
     * Key 与照片以明文离开这台机器」，而那种事没有第二次机会。这里不抛也不重试——只让它
     * 变成「这个引擎现在不可用」，调用方本来就会回落到设备端。
     */
    override fun unavailableReason(): String? = when {
        !endpoint.startsWith("https://") -> "The vision endpoint is not HTTPS."
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
        CloudReply.labels(response)
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
                // 只截 300 字符是为了给日志一个上限；错误响应体里不会有密钥或图片内容。
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

    companion object {
        private const val TAG = "CloudVision"

        const val DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"

        /** Vision models resize well below full resolution; this keeps requests cheap. */
        const val MAX_EDGE = 1400
        const val JPEG_QUALITY = 82
        const val MAX_TOKENS = 700
        const val TIMEOUT_MS = 25_000

        /**
         * Asks for exactly what a dictionary entry needs — headword, IPA, and the translations
         * in the four shipped languages — so a reply can be used directly instead of being
         * re-interpreted.
         *
         * 释义与音标仍然要，但它们不进标签列表（`LexiconIndex` 的索引只认英语词头，见
         * [CloudReply]）。留着的理由很朴素：本地词典只有 12000 条，模型给的这两个字段是唯一
         * 一处「不查词典也在手边」的信息，而它们不占标签额度——上限按词掐，不按拍平后的条数。
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
