package com.omnichat.app.network

import android.content.Context
import com.omnichat.app.security.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class KiloLyriaParsed(
    val bytes: ByteArray,
    val mime: String,
    val text: String
)


internal fun kiloLyriaApiFailure(statusCode: Int, raw: String): ApiFailure {
    val error = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
    val title = error?.optString("title").orEmpty()
    val balance = error?.opt("balance")?.toString().orEmpty()
    if (statusCode == 402 || title.contains("Paid Model", ignoreCase = true)) {
        val balanceText = balance.takeIf(String::isNotBlank)?.let { " Баланс Kilo: $it." }.orEmpty()
        return ApiFailure(
            "Kilo Lyria требует credits.$balanceText Пополните баланс Kilo или выберите модель с подтверждённым FREE-доступом.",
            statusCode
        )
    }
    return ApiFailure("Kilo Lyria: HTTP $statusCode: ${raw.take(500)}", statusCode)
}

/** Parser for OpenAI/OpenRouter-compatible Lyria text+audio responses. */
internal object KiloLyriaResponseParser {
    fun parse(raw: String): KiloLyriaParsed {
        val audio = StringBuilder()
        val text = mutableListOf<String>()
        var mime = "audio/mpeg"

        fun appendContent(value: Any?) {
            when (value) {
                is String -> value.trim().takeIf(String::isNotBlank)?.let(text::add)
                is JSONArray -> for (i in 0 until value.length()) {
                    val item = value.optJSONObject(i) ?: continue
                    when (item.optString("type")) {
                        "text", "output_text" -> item.optString("text").trim()
                            .takeIf(String::isNotBlank)?.let(text::add)
                    }
                }
            }
        }

        fun consumeNode(node: JSONObject?) {
            if (node == null) return
            appendContent(node.opt("content"))
            node.optString("text").trim().takeIf(String::isNotBlank)?.let(text::add)
            val audioObject = node.optJSONObject("audio")
            audioObject?.optString("data")?.takeIf(String::isNotBlank)?.let(audio::append)
            val reportedMime = audioObject?.optString("mime_type").orEmpty()
            val format = audioObject?.optString("format").orEmpty()
            if (reportedMime.startsWith("audio/")) mime = reportedMime
            else if (format.equals("wav", ignoreCase = true)) mime = "audio/wav"
        }

        fun consumeEnvelope(json: JSONObject) {
            json.optJSONObject("audio")?.let { direct ->
                direct.optString("data").takeIf(String::isNotBlank)?.let(audio::append)
                direct.optString("mime_type").takeIf { it.startsWith("audio/") }?.let { mime = it }
            }
            appendContent(json.opt("content"))
            val choices = json.optJSONArray("choices") ?: return
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                consumeNode(choice.optJSONObject("delta"))
                consumeNode(choice.optJSONObject("message"))
            }
        }

        val trimmed = raw.trim()
        if (trimmed.startsWith("data:") || trimmed.contains("\ndata:")) {
            trimmed.lineSequence().forEach { line ->
                val value = line.trim()
                if (!value.startsWith("data:")) return@forEach
                val payload = value.removePrefix("data:").trim()
                if (payload.isBlank() || payload == "[DONE]") return@forEach
                runCatching { JSONObject(payload) }.getOrNull()?.let(::consumeEnvelope)
            }
        } else {
            runCatching { JSONObject(trimmed) }.getOrNull()?.let(::consumeEnvelope)
                ?: throw ApiFailure("Kilo Lyria вернула некорректный ответ")
        }

        val encoded = audio.toString().filterNot(Char::isWhitespace)
        if (encoded.isBlank()) throw ApiFailure("Kilo Lyria ответила без аудио")
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse {
            throw ApiFailure("Kilo Lyria вернула повреждённые аудиоданные")
        }
        return KiloLyriaParsed(
            bytes = bytes,
            mime = mime,
            text = text.map(String::trim).filter(String::isNotBlank).distinct().joinToString("\n\n")
        )
    }
}

/** Kilo Code account authentication using Kilo's official browser/device flow. */
class KiloGatewayRepository(context: Context, private val vault: KeyVault) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("kilo_gateway", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val musicHttp = http.newBuilder()
        .readTimeout(6, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(7, TimeUnit.MINUTES)
        .build()
    private val jsonType = "application/json".toMediaType()
    private val attachmentsRoot = File(appContext.filesDir, "attachments")

    data class DeviceLogin(val code: String, val verificationUrl: String, val expiresIn: Int)
    data class Account(val token: String, val email: String)
    data class LyriaResult(val bytes: ByteArray, val mime: String, val text: String, val model: String)

    fun readToken(): String = runCatching { vault.decrypt(prefs.getString("token", "").orEmpty()) }.getOrDefault("")
    fun accountEmail(): String = prefs.getString("email", "").orEmpty()
    fun clear() = prefs.edit().clear().apply()
    fun isAccountConnected(): Boolean = readToken().isNotBlank()

    suspend fun beginLogin(): DeviceLogin = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$API_BASE/api/device-auth/codes")
            .post("{}".toRequestBody(jsonType)).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiFailure("Kilo Code: HTTP ${r.code}: ${raw.take(300)}")
            val j = JSONObject(raw)
            DeviceLogin(j.getString("code"), j.getString("verificationUrl"), j.optInt("expiresIn", 600))
        }
    }

    suspend fun awaitLogin(login: DeviceLogin): Account = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + login.expiresIn.coerceAtLeast(30) * 1000L
        while (System.currentTimeMillis() < deadline) {
            val req = Request.Builder().url("$API_BASE/api/device-auth/codes/${login.code}")
                .get().header("Accept", "application/json").build()
            http.newCall(req).execute().use { r ->
                when (r.code) {
                    202 -> Unit
                    403 -> throw ApiFailure("Авторизация Kilo Code отклонена")
                    410 -> throw ApiFailure("Код авторизации Kilo Code истёк")
                    in 200..299 -> {
                        val j = JSONObject(r.body?.string().orEmpty())
                        val token = j.optString("token")
                        if (token.isBlank()) throw ApiFailure("Kilo Code не вернул токен аккаунта")
                        val email = j.optString("userEmail")
                        prefs.edit().putString("token", vault.encrypt(token)).putString("email", email).apply()
                        return@withContext Account(token, email)
                    }
                    else -> throw ApiFailure("Kilo Code: HTTP ${r.code}")
                }
            }
            delay(POLL_MS)
        }
        throw ApiFailure("Время ожидания входа Kilo Code истекло")
    }

    /**
     * Generates music through Kilo's OpenRouter-compatible account route. Lyria returns
     * text (lyrics/structure) plus audio; audio is accumulated from SSE delta.audio.data.
     */
    suspend fun generateLyriaMusic(
        model: String,
        prompt: String,
        referenceImages: List<LocalAttachment> = emptyList()
    ): LyriaResult = withContext(Dispatchers.IO) {
        require(isLyriaModel(model)) { "Kilo: выбранная модель не является Lyria" }
        require(prompt.isNotBlank()) { "Опишите музыку, которую нужно сгенерировать" }
        val token = readToken()
        require(token.isNotBlank()) { "Войдите в Kilo Code" }

        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", prompt.trim()))
        var inlineBytes = 0L
        referenceImages.asSequence()
            .filter { it.mime.startsWith("image/", ignoreCase = true) }
            .take(MAX_REFERENCE_IMAGES)
            .forEach { attachment ->
                val source = runCatching { File(attachment.path).canonicalFile }.getOrNull() ?: return@forEach
                val root = runCatching { attachmentsRoot.canonicalFile }.getOrNull() ?: return@forEach
                if (!source.isFile || !source.path.startsWith(root.path + File.separator)) return@forEach
                if (source.length() <= 0L || source.length() > MAX_REFERENCE_BYTES) return@forEach
                inlineBytes += source.length()
                if (inlineBytes > MAX_TOTAL_REFERENCE_BYTES) return@forEach
                val encoded = Base64.getEncoder().encodeToString(source.readBytes())
                parts.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:${attachment.mime};base64,$encoded")
                    )
                )
            }

        val content: Any = if (parts.length() == 1) prompt.trim() else parts
        val body = JSONObject()
            .put("model", canonicalLyriaModel(model))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("modalities", JSONArray().put("text").put("audio"))
            .put("audio", JSONObject().put("format", "mp3"))
            .put("stream", true)

        val endpoints = listOf(BASE_URL, GATEWAY_BASE_URL).distinct()
        var lastFailure: ApiFailure? = null
        for ((index, base) in endpoints.withIndex()) {
            val request = Request.Builder()
                .url("${base.trimEnd('/')}/chat/completions")
                .post(body.toString().toRequestBody(jsonType))
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream, application/json")
                .header("User-Agent", "OmniChat-Android")
                .header("X-Title", "OmniChat")
                .build()
            musicHttp.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val failure = kiloLyriaApiFailure(response.code, raw)
                    lastFailure = failure
                    if (index < endpoints.lastIndex && response.code in setOf(404, 405)) return@use
                    throw failure
                }
                val parsed = KiloLyriaResponseParser.parse(raw)
                return@withContext LyriaResult(parsed.bytes, parsed.mime, parsed.text, canonicalLyriaModel(model))
            }
        }
        throw lastFailure ?: ApiFailure("Kilo Lyria: генерация не выполнена")
    }

    companion object {
        const val API_BASE = "https://api.kilo.ai"
        /** Kilo Code device-session route already used by OmniChat. */
        const val BASE_URL = "https://api.kilo.ai/api/openrouter"
        /** Current public Kilo Gateway base; used as a forward-compatible fallback. */
        const val GATEWAY_BASE_URL = "https://api.kilo.ai/api/gateway"
        const val AUTO_FREE = "kilo-auto/free"
        const val AUTO_EFFICIENT = "kilo-auto/efficient"
        const val AUTO_FRONTIER = "kilo-auto/frontier"
        const val AUTO_SMALL = "kilo-auto/small"
        const val LYRIA_CLIP = "google/lyria-3-clip-preview"
        const val LYRIA_PRO = "google/lyria-3-pro-preview"
        val LYRIA_MODELS = setOf(LYRIA_CLIP, LYRIA_PRO)
        private const val POLL_MS = 3000L
        private const val MAX_REFERENCE_IMAGES = 4
        private const val MAX_REFERENCE_BYTES = 6L * 1024L * 1024L
        private const val MAX_TOTAL_REFERENCE_BYTES = 12L * 1024L * 1024L

        fun canonicalLyriaModel(model: String): String = when (model.trim().lowercase()) {
            LYRIA_CLIP, "lyria-3-clip-preview" -> LYRIA_CLIP
            LYRIA_PRO, "lyria-3-pro-preview" -> LYRIA_PRO
            else -> model.trim()
        }

        fun isLyriaModel(model: String): Boolean = canonicalLyriaModel(model) in LYRIA_MODELS
    }
}
