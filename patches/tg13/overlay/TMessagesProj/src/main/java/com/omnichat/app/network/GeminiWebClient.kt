package com.omnichat.app.network

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.omnichat.app.data.MessageEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Experimental Gemini Web transport.
 *
 * StreamGenerate runs inside Android System WebView (Chromium) because Gemini's consumer web
 * endpoints are browser-gated. Binary uploads use the same authenticated browser session and the
 * content-push upload endpoint, but are sent by OkHttp so large local files never cross the
 * evaluateJavascript/Binder boundary.
 *
 * Google credentials are never requested. Only explicitly supplied session cookies are stored,
 * encrypted by the application's KeyVault.
 */
class GeminiWebClient(private val context: Context) {
    data class Credentials(
        val psid: String,
        val psidts: String = "",
        val sapisid: String = "",
        val authUser: Int = 0
    ) {
        fun toSecret(): String = JSONObject()
            .put("psid", clean(psid, "__Secure-1PSID"))
            .put("psidts", clean(psidts, "__Secure-1PSIDTS"))
            .put("sapisid", clean(sapisid, "SAPISID"))
            .put("authUser", authUser.coerceIn(0, 9))
            .toString()

        companion object {
            fun fromSecret(raw: String): Credentials {
                if (raw.isBlank()) return Credentials("")
                return runCatching {
                    val json = JSONObject(raw)
                    Credentials(
                        clean(json.optString("psid"), "__Secure-1PSID"),
                        clean(json.optString("psidts"), "__Secure-1PSIDTS"),
                        clean(json.optString("sapisid"), "SAPISID"),
                        json.optInt("authUser", 0).coerceIn(0, 9)
                    )
                }.getOrElse { Credentials(clean(raw, "__Secure-1PSID")) }
            }

            private fun clean(value: String, name: String): String =
                value.trim()
                    .removePrefix("$name=")
                    .substringBefore(';')
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim()
        }
    }

    /**
     * Opaque Gemini web continuation metadata. The first 3 slots are cid/rid/rcid.
     * anchorMessageId belongs to OmniChat and marks the last local message already represented
     * by this server-side Gemini conversation.
     */
    data class ConversationState(
        val metadataJson: String = DEFAULT_METADATA_JSON,
        val anchorMessageId: Long = 0L
    ) {
        fun metadata(): JSONArray {
            val parsed = runCatching { JSONArray(metadataJson) }.getOrNull()
            val out = JSONArray()
            for (i in 0 until 10) {
                val value = if (parsed != null && i < parsed.length()) parsed.opt(i) else null
                out.put(if (value == null) JSONObject.NULL else value)
            }
            if (out.opt(0) == JSONObject.NULL) out.put(0, "")
            if (out.opt(1) == JSONObject.NULL) out.put(1, "")
            if (out.opt(2) == JSONObject.NULL) out.put(2, "")
            if (out.opt(9) == JSONObject.NULL) out.put(9, "")
            return out
        }

        val cid: String get() = metadata().optString(0)
        val rid: String get() = metadata().optString(1)
        val rcid: String get() = metadata().optString(2)

        fun withAnchor(messageId: Long) = copy(anchorMessageId = messageId.coerceAtLeast(0L))

        fun toJson(): JSONObject = JSONObject()
            .put("metadata", metadata())
            .put("anchorMessageId", anchorMessageId)

        companion object {
            fun fromJson(value: JSONObject?): ConversationState? {
                value ?: return null
                val metadata = value.optJSONArray("metadata") ?: return null
                return ConversationState(
                    metadataJson = metadata.toString(),
                    anchorMessageId = value.optLong("anchorMessageId", 0L).coerceAtLeast(0L)
                )
            }
        }
    }

    data class GenerateResult(
        val text: String,
        val state: ConversationState
    )

    data class WebModel(
        val modelId: String,
        val modelName: String,
        val displayName: String,
        val description: String,
        val capacity: Int,
        val capacityField: Int,
        val modelNumber: Int,
        val available: Boolean = true
    )

    data class UsageLimit(
        val label: String,
        val usagePercent: Double? = null,
        val remaining: Double? = null,
        val total: Double? = null,
        val resetAtSeconds: Long? = null
    )

    data class RemoteChat(
        val cid: String,
        val title: String,
        val pinned: Boolean,
        val timestampSeconds: Double
    )

    data class AccountSnapshot(
        val statusCode: Int?,
        val statusLabel: String,
        val tierLabel: String?,
        val models: List<WebModel>,
        val limits: List<UsageLimit>,
        val chats: List<RemoteChat>
    )

    data class SessionStatus(
        val authenticated: Boolean,
        val buildLabel: String,
        val sessionIdPresent: Boolean
    )

    private data class Bootstrap(
        val token: String,
        val buildLabel: String,
        val sessionId: String,
        val pushId: String
    )

    private data class UploadedFile(val ref: String, val name: String)

    private sealed interface JsEvent {
        data class Chunk(val text: String) : JsEvent
        data class State(val metadataJson: String) : JsEvent
        data class Rpc(val payloadJson: String) : JsEvent
        data class Done(val text: String) : JsEvent
        data class Error(val message: String) : JsEvent
    }

    private val appContext = context.applicationContext
    private val webMutex = Mutex()
    private val events = ConcurrentHashMap<String, Channel<JsEvent>>()
    private val accountModels = ConcurrentHashMap<String, List<WebModel>>()
    private val clientSessionId = UUID.randomUUID().toString().uppercase()

    private val uploadHttp = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pageWaiter: CompletableDeferred<String>? = null

    private inner class Bridge {
        @JavascriptInterface
        fun emit(requestId: String, type: String, payload: String) {
            val channel = events[requestId] ?: return
            when (type) {
                "chunk" -> channel.trySend(JsEvent.Chunk(payload))
                "state" -> channel.trySend(JsEvent.State(payload))
                "rpc" -> channel.trySend(JsEvent.Rpc(payload))
                "done" -> channel.trySend(JsEvent.Done(payload))
                "error" -> channel.trySend(JsEvent.Error(payload))
            }
        }
    }

    suspend fun probe(credentials: Credentials): SessionStatus = webMutex.withLock {
        require(credentials.psid.isNotBlank()) { "Укажите __Secure-1PSID" }
        val boot = bootstrap(credentials)
        SessionStatus(true, boot.buildLabel, boot.sessionId.isNotBlank())
    }

    suspend fun inspectAccount(credentials: Credentials): AccountSnapshot = webMutex.withLock {
        require(credentials.psid.isNotBlank()) { "Укажите __Secure-1PSID" }
        val boot = bootstrap(credentials)
        val liveCredentials = currentBrowserCredentials(credentials)
        val view = ensureWebView()

        val userBodies = executeRpc(view, liveCredentials, boot, RPC_USER_STATUS, "[]")
        val userBody = userBodies.firstOrNull()?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: throw ApiFailure("Gemini Web не вернул статус аккаунта")
        val statusCode = userBody.opt(14).let { if (it is Number) it.toInt() else null }
        val models = parseModels(userBody, statusCode == 1016)
        if (models.isNotEmpty()) accountModels[accountCacheKey(liveCredentials)] = models

        val usage = runCatching {
            executeRpc(view, liveCredentials, boot, RPC_USAGE_INFO, "[]", "/usage")
                .firstOrNull()?.let { JSONArray(it) }
        }.getOrNull()
        val (tierLabel, usageLimits) = parseUsageInfo(usage)

        val quotaLimits = buildList {
            runCatching {
                executeRpc(view, liveCredentials, boot, RPC_QUOTA, FLASH_QUOTA_PAYLOAD)
            }.getOrNull()?.forEach { body ->
                runCatching { JSONArray(body) }.getOrNull()?.let { addAll(parseQuota(it, "Flash")) }
            }
            runCatching {
                executeRpc(view, liveCredentials, boot, RPC_QUOTA, ADVANCED_QUOTA_PAYLOAD)
            }.getOrNull()?.forEach { body ->
                runCatching { JSONArray(body) }.getOrNull()?.let { addAll(parseQuota(it, "Pro")) }
            }
        }

        val chats = runCatching {
            loadRecentChats(view, liveCredentials, boot)
        }.getOrDefault(emptyList())

        AccountSnapshot(
            statusCode = statusCode,
            statusLabel = accountStatusLabel(statusCode),
            tierLabel = tierLabel,
            models = models,
            limits = (usageLimits + quotaLimits).distinctBy { it.label },
            chats = chats
        )
    }

    suspend fun remoteConversationState(
        credentials: Credentials,
        cid: String
    ): ConversationState = webMutex.withLock {
        require(cid.isNotBlank()) { "Пустой Gemini chat ID" }
        val boot = bootstrap(credentials)
        val liveCredentials = currentBrowserCredentials(credentials)
        val view = ensureWebView()
        val payload = JSONArray()
            .put(cid)
            .put(5)
            .put(JSONObject.NULL)
            .put(1)
            .put(JSONArray().put(1))
            .put(JSONArray().put(4))
            .put(JSONObject.NULL)
            .put(1)
            .toString()
        val body = executeRpc(view, liveCredentials, boot, RPC_CHAT_TURNS, payload)
            .firstOrNull()?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: throw ApiFailure("Не удалось прочитать выбранный Gemini-чат")
        val turns = body.optJSONArray(0)
            ?: throw ApiFailure("Gemini-чат не содержит доступной истории")

        var rid = ""
        var rcid = ""
        for (i in 0 until turns.length()) {
            val turn = turns.optJSONArray(i) ?: continue
            val candidateRid = turn.optJSONArray(0)?.optString(1).orEmpty()
            val candidates = turn.optJSONArray(3)?.optJSONArray(0)
            if (candidates != null) {
                for (j in 0 until candidates.length()) {
                    val candidate = candidates.optJSONArray(j) ?: continue
                    val candidateRcid = candidate.optString(0)
                    if (candidateRcid.isNotBlank()) {
                        rid = candidateRid
                        rcid = candidateRcid
                        break
                    }
                }
            }
            if (rid.isNotBlank() && rcid.isNotBlank()) break
        }
        if (rid.isBlank() || rcid.isBlank())
            throw ApiFailure("В выбранном Gemini-чате пока нет завершённого ответа модели")

        val metadata = JSONArray(DEFAULT_METADATA_JSON)
        metadata.put(0, cid)
        metadata.put(1, rid)
        metadata.put(2, rcid)
        ConversationState(metadataJson = metadata.toString())
    }

    /**
     * Google can rotate __Secure-1PSIDTS after a browser request.
     * Callers persist the refreshed value back into Android Keystore.
     */
    suspend fun currentBrowserCredentials(fallback: Credentials): Credentials =
        withContext(Dispatchers.Main.immediate) {
            val raw = CookieManager.getInstance().getCookie(GOOGLE_ORIGIN).orEmpty()
            val values = raw.split(';').mapNotNull { item ->
                val part = item.trim()
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
            }.toMap()
            Credentials(
                psid = values["__Secure-1PSID"].orEmpty().ifBlank { fallback.psid },
                psidts = values["__Secure-1PSIDTS"].orEmpty().ifBlank { fallback.psidts },
                sapisid = values["SAPISID"].orEmpty().ifBlank { fallback.sapisid },
                authUser = fallback.authUser
            )
        }

    private fun accountCacheKey(credentials: Credentials): String {
        val raw = "${credentials.authUser}|${credentials.psid}"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    private fun accountStatusLabel(code: Int?): String = when (code) {
        1000 -> "Доступ разрешён"
        1014 -> "Временное ограничение доступа"
        1016 -> "Сессия не авторизована"
        1021 -> "Аккаунт отклонён"
        1033 -> "Ограничены некоторые возможности"
        1040, 1042 -> "Требуется принять условия Google"
        1054 -> "Доступ заблокирован настройками семьи"
        1057 -> "Требуется одобрение семьи"
        1060 -> "Gemini недоступен для текущего региона"
        null -> "Статус неизвестен"
        else -> "Статус Google: $code"
    }

    private fun parseModels(body: JSONArray, unauthenticated: Boolean): List<WebModel> {
        val modelData = body.optJSONArray(15) ?: return emptyList()
        val tierFlags = body.optJSONArray(16)?.intValues().orEmpty()
        val capabilityFlags = body.optJSONArray(17)?.intValues().orEmpty()
        val (capacity, capacityField) = when {
            21 in tierFlags -> 1 to 13
            22 in tierFlags -> 2 to 13
            115 in capabilityFlags -> 4 to 12
            16 in tierFlags || 106 in capabilityFlags -> 3 to 12
            8 in tierFlags || 19 in capabilityFlags -> 2 to 12
            else -> 1 to 12
        }
        return buildList {
            for (i in 0 until modelData.length()) {
                val item = modelData.optJSONArray(i) ?: continue
                val id = item.optString(0)
                if (id.isBlank()) continue
                val category = item.optString(1).ifBlank { item.optString(10) }
                val display = item.optString(11).ifBlank {
                    item.optString(19).ifBlank { category.ifBlank { id } }
                }
                val description = item.optString(12).ifBlank { item.optString(2) }
                val number = (item.opt(17) as? Number)?.toInt()
                    ?: (item.opt(9) as? Number)?.toInt()
                    ?: 1
                val slug = category.ifBlank { display }.trim().lowercase()
                    .replace(Regex("[^a-z0-9._-]+"), "-")
                    .trim('-')
                add(
                    WebModel(
                        modelId = id,
                        modelName = if (slug.startsWith("gemini-")) slug else "gemini-$slug",
                        displayName = display,
                        description = description,
                        capacity = capacity,
                        capacityField = capacityField,
                        modelNumber = number,
                        available = !unauthenticated || i == 0
                    )
                )
            }
        }.distinctBy { it.modelId }
    }

    private fun parseUsageInfo(body: JSONArray?): Pair<String?, List<UsageLimit>> {
        body ?: return null to emptyList()
        val tier = when ((body.opt(0) as? Number)?.toInt()) {
            1 -> "FREE"
            2 -> "PRO"
            3, 6 -> "ULTRA"
            4 -> "PLUS"
            else -> null
        }
        val items = body.optJSONArray(1) ?: return tier to emptyList()
        val limits = buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONArray(i) ?: continue
                val remaining = (item.opt(0) as? Number)?.toDouble()
                val usageLevel = (item.opt(1) as? Number)?.toDouble()
                val type = (item.opt(2) as? Number)?.toInt()
                val resetAt = item.optJSONArray(3)?.optJSONArray(0)?.opt(0)
                    .let { (it as? Number)?.toLong() }
                val label = when (type) {
                    1 -> "Окно 5 часов"
                    2 -> "Недельный лимит"
                    3 -> "AI credits"
                    else -> "Лимит $type"
                }
                add(
                    UsageLimit(
                        label = label,
                        usagePercent = usageLevel?.let { if (type == 3) null else it * 100.0 },
                        remaining = remaining,
                        resetAtSeconds = resetAt
                    )
                )
            }
        }
        return tier to limits
    }

    private fun parseQuota(body: JSONArray, category: String): List<UsageLimit> {
        val items = body.optJSONArray(0) ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONArray(i) ?: continue
                val action = item.optJSONArray(0)?.opt(1).let { (it as? Number)?.toInt() }
                val usage = (item.opt(2) as? Number)?.toDouble()
                val reset = item.optJSONArray(3)?.opt(0).let { (it as? Number)?.toLong() }
                val total = (item.opt(4) as? Number)?.toDouble()
                val remaining = (item.opt(5) as? Number)?.toDouble()
                val label = when (action) {
                    4 -> "Gemini Pro"
                    11 -> "Gemini Flash"
                    15 -> "Gemini Flash Thinking"
                    else -> "Gemini $category"
                }
                add(UsageLimit(label, usage, remaining, total, reset))
            }
        }
    }

    private suspend fun loadRecentChats(
        view: WebView,
        credentials: Credentials,
        boot: Bootstrap
    ): List<RemoteChat> {
        val result = linkedMapOf<String, RemoteChat>()
        val payloads = listOf(
            JSONArray().put(20).put(JSONObject.NULL).put(JSONArray().put(1).put(JSONObject.NULL).put(1)).toString(),
            JSONArray().put(20).put(JSONObject.NULL).put(JSONArray().put(0).put(JSONObject.NULL).put(1)).toString()
        )
        for (payload in payloads) {
            val bodies = executeRpc(view, credentials, boot, RPC_LIST_CHATS, payload)
            for (raw in bodies) {
                val body = runCatching { JSONArray(raw) }.getOrNull() ?: continue
                val chats = body.optJSONArray(2) ?: continue
                for (i in 0 until chats.length()) {
                    val chat = chats.optJSONArray(i) ?: continue
                    val cid = chat.optString(0)
                    if (cid.isBlank()) continue
                    val ts = chat.optJSONArray(5)
                    val seconds = (ts?.opt(0) as? Number)?.toDouble() ?: 0.0
                    val nanos = (ts?.opt(1) as? Number)?.toDouble() ?: 0.0
                    result[cid] = RemoteChat(
                        cid = cid,
                        title = chat.optString(1).ifBlank { "Gemini chat" },
                        pinned = chat.optBoolean(2, false),
                        timestampSeconds = seconds + nanos / 1_000_000_000.0
                    )
                }
            }
        }
        return result.values.sortedWith(
            compareByDescending<RemoteChat> { it.pinned }
                .thenByDescending { it.timestampSeconds }
        )
    }

    private fun JSONArray.intValues(): Set<Int> = buildSet {
        for (i in 0 until length()) (opt(i) as? Number)?.toInt()?.let(::add)
    }

    private suspend fun executeRpc(
        view: WebView,
        credentials: Credentials,
        boot: Bootstrap,
        rpcId: String,
        payload: String,
        sourcePath: String = "/app"
    ): List<String> {
        val requestId = UUID.randomUUID().toString()
        val channel = Channel<JsEvent>(Channel.UNLIMITED)
        events[requestId] = channel
        val script = rpcScript(
            requestId = requestId,
            rpcId = rpcId,
            payload = payload,
            token = boot.token,
            buildLabel = boot.buildLabel,
            sessionId = boot.sessionId,
            authUser = credentials.authUser,
            authHeader = sapisidHash(credentials).orEmpty(),
            sourcePath = sourcePath,
            pathPrefix = if (credentials.authUser > 0) "/u/${credentials.authUser}" else ""
        )
        try {
            evaluate(view, script)
            return withTimeout(45_000L) {
                while (true) {
                    when (val event = channel.receive()) {
                        is JsEvent.Rpc -> {
                            val array = JSONArray(event.payloadJson)
                            return@withTimeout (0 until array.length()).mapNotNull {
                                array.optString(it).takeIf(String::isNotBlank)
                            }
                        }
                        is JsEvent.Error -> throw ApiFailure(event.message)
                        else -> Unit
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                emptyList()
            }
        } finally {
            events.remove(requestId)?.close()
        }
    }

    suspend fun generate(
        credentials: Credentials,
        model: String,
        systemPrompt: String,
        history: List<MessageEntity>,
        attachments: List<LocalAttachment> = emptyList(),
        conversation: ConversationState? = null,
        onText: suspend (String) -> Unit
    ): GenerateResult = webMutex.withLock {
        require(credentials.psid.isNotBlank()) { "Сессия Gemini Web не настроена" }
        val prompt = buildPrompt(systemPrompt, history)
        require(prompt.isNotBlank()) { "Пустой запрос Gemini Web" }
        require(prompt.length <= 90_000) { "Контекст Gemini Web слишком большой" }

        val boot = bootstrap(credentials)
        val browserCredentials = currentBrowserCredentials(credentials)
        val uploaded = uploadAttachments(browserCredentials, boot, attachments)

        val view = ensureWebView()
        val config = modelConfig(model)
        val discoveredModel = resolveWebModel(view, browserCredentials, boot, model)
        if (!model.equals("gemini-auto", ignoreCase = true) &&
            discoveredModel == null && model !in MODELS) {
            throw ApiFailure("Модель Gemini «$model» больше не доступна этому аккаунту. Обновите список моделей.")
        }
        val extendedThinking = model.contains("thinking", ignoreCase = true)
        val requestId = UUID.randomUUID().toString()
        val channel = Channel<JsEvent>(Channel.UNLIMITED)
        events[requestId] = channel

        val authHeader = sapisidHash(browserCredentials).orEmpty()
        val prefix = if (browserCredentials.authUser > 0) "/u/${browserCredentials.authUser}" else ""
        val initialState = conversation ?: ConversationState()
        val script = generateScript(
            requestId = requestId,
            prompt = prompt,
            token = boot.token,
            buildLabel = boot.buildLabel,
            sessionId = boot.sessionId,
            authUser = browserCredentials.authUser,
            authHeader = authHeader,
            pathPrefix = prefix,
            mode = discoveredModel?.modelNumber ?: config.first,
            thinkMode = if (discoveredModel != null) 0 else config.second,
            modelHeader = discoveredModel?.let { modelHeaderValue(it, extendedThinking) }.orEmpty(),
            extendedThinking = extendedThinking,
            metadata = initialState.metadata(),
            files = uploaded
        )

        try {
            evaluate(view, script)
            var finalText = ""
            var metadataJson = initialState.metadata().toString()
            withTimeout(180_000L) {
                while (true) {
                    when (val event = channel.receive()) {
                        is JsEvent.Chunk -> {
                            if (event.text.length >= finalText.length) {
                                finalText = event.text
                                onText(finalText)
                            }
                        }
                        is JsEvent.State -> {
                            if (runCatching { JSONArray(event.metadataJson) }.isSuccess)
                                metadataJson = event.metadataJson
                        }
                        is JsEvent.Rpc -> Unit
                        is JsEvent.Done -> {
                            if (event.text.isNotBlank()) {
                                finalText = event.text
                                onText(finalText)
                            }
                            return@withTimeout
                        }
                        is JsEvent.Error -> throw ApiFailure(event.message)
                    }
                }
            }
            if (finalText.isBlank())
                throw ApiFailure("Gemini Web не вернул текст. Веб-протокол мог измениться.")
            GenerateResult(
                text = finalText,
                state = ConversationState(
                    metadataJson = metadataJson,
                    anchorMessageId = initialState.anchorMessageId
                )
            )
        } finally {
            events.remove(requestId)?.close()
        }
    }

    private suspend fun uploadAttachments(
        credentials: Credentials,
        boot: Bootstrap,
        attachments: List<LocalAttachment>
    ): List<UploadedFile> {
        if (attachments.isEmpty()) return emptyList()
        val supported = attachments.distinctBy { it.path }.map { attachment ->
            val mime = attachment.mime.lowercase()
            if (!(mime.startsWith("image/") || mime == "application/pdf")) {
                throw ApiFailure(
                    "Gemini Web: в этом проходе поддерживаются изображения и PDF. " +
                        "Файл «${attachment.name}» имеет тип ${attachment.mime}."
                )
            }
            val file = File(attachment.path)
            if (!file.isFile) throw ApiFailure("Gemini Web: файл «${attachment.name}» больше недоступен")
            if (file.length() <= 0L || file.length() > MAX_UPLOAD_BYTES)
                throw ApiFailure("Gemini Web: размер каждого вложения должен быть от 1 байта до 8 МБ")
            Triple(attachment, file, mime)
        }

        return withContext(Dispatchers.IO) {
            supported.map { (attachment, file, mime) ->
                val safeName = attachment.name
                    .replace(Regex("[\\r\\n\\t]"), "_")
                    .take(120)
                    .ifBlank { "attachment" }
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        safeName,
                        file.asRequestBody(mime.toMediaTypeOrNull())
                    )
                    .build()
                val request = Request.Builder()
                    .url(UPLOAD_URL)
                    .header("Origin", GOOGLE_ORIGIN)
                    .header("Referer", "$GOOGLE_ORIGIN/")
                    .header("X-Tenant-Id", "bard-storage")
                    .header("Push-ID", boot.pushId.ifBlank { DEFAULT_PUSH_ID })
                    .header("X-Goog-AuthUser", credentials.authUser.toString())
                    .header("Cookie", cookieHeader(credentials))
                    .apply { sapisidHash(credentials)?.let { header("Authorization", it) } }
                    .post(body)
                    .build()

                uploadHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful)
                        throw ApiFailure("Gemini Web: загрузка «$safeName» завершилась HTTP ${response.code}")
                    val ref = response.body?.string().orEmpty().trim()
                    if (!ref.startsWith("/") || ref.length > 2048)
                        throw ApiFailure("Gemini Web: сервер вернул некорректный идентификатор файла")
                    UploadedFile(ref, safeName)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main.immediate) {
        webView ?: WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            val browser = this
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(browser, true)
            }
            addJavascriptInterface(Bridge(), BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageWaiter?.takeIf { !it.isCompleted }?.complete(url.orEmpty())
                }
            }
        }.also { webView = it }
    }

    private suspend fun bootstrap(credentials: Credentials): Bootstrap {
        val view = ensureWebView()
        installCookies(view, credentials)
        val prefix = if (credentials.authUser > 0) "/u/${credentials.authUser}" else ""
        val target = "$GOOGLE_ORIGIN$prefix/app"
        val waiter = CompletableDeferred<String>()
        pageWaiter = waiter
        withContext(Dispatchers.Main.immediate) { view.loadUrl(target) }

        val loadedUrl = try {
            withTimeout(30_000L) { waiter.await() }
        } finally {
            pageWaiter = null
        }

        if (loadedUrl.contains("accounts.google.com", ignoreCase = true))
            throw ApiFailure("Gemini Web перенаправил на вход: сессия истекла")

        repeat(12) {
            val raw = evaluate(view, BOOTSTRAP_SCRIPT)
            val payload = decodeJavascriptString(raw)
            val json = runCatching { JSONObject(payload) }.getOrNull()
            val href = json?.optString("href").orEmpty()
            val token = json?.optString("token").orEmpty()
            val bl = json?.optString("bl").orEmpty()
            val sid = json?.optString("sid").orEmpty()
            val pushId = json?.optString("pushId").orEmpty().ifBlank { DEFAULT_PUSH_ID }
            if (href.contains("accounts.google.com", ignoreCase = true))
                throw ApiFailure("Gemini Web требует повторного входа в Google")
            if (token.isNotBlank() && bl.isNotBlank())
                return Bootstrap(token, bl, sid, pushId)
            delay(450L)
        }
        throw ApiFailure(
            "Google не подтвердил клиентскую сессию Gemini. Проверьте __Secure-1PSID / __Secure-1PSIDTS."
        )
    }

    private suspend fun installCookies(view: WebView, credentials: Credentials) =
        withContext(Dispatchers.Main.immediate) {
            val clean = Credentials.fromSecret(credentials.toSecret())
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setAcceptThirdPartyCookies(view, true)

            fun set(name: String, value: String) {
                if (value.isBlank()) {
                    manager.setCookie(
                        GOOGLE_ORIGIN,
                        "$name=; Max-Age=0; Domain=.google.com; Path=/; Secure"
                    )
                } else {
                    manager.setCookie(
                        GOOGLE_ORIGIN,
                        "$name=$value; Domain=.google.com; Path=/; Secure; HttpOnly; SameSite=None"
                    )
                }
            }

            set("__Secure-1PSID", clean.psid)
            set("__Secure-1PSIDTS", clean.psidts)
            set("SAPISID", clean.sapisid)
            manager.flush()
        }

    private suspend fun evaluate(view: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            view.post {
                if (!continuation.isActive) return@post
                view.evaluateJavascript(script) { raw ->
                    if (continuation.isActive) continuation.resume(raw.orEmpty())
                }
            }
        }

    private fun decodeJavascriptString(raw: String): String {
        if (raw.isBlank() || raw == "null" || raw == "undefined") return ""
        return runCatching { JSONArray("[$raw]").optString(0) }
            .getOrElse { raw.trim('"') }
    }

    private fun buildPrompt(systemPrompt: String, history: List<MessageEntity>): String = buildString {
        if (systemPrompt.isNotBlank())
            append("[System instruction]\n").append(systemPrompt.trim()).append("\n\n")
        history.forEach { message ->
            val content = message.content.trim()
            if (content.isBlank() && message.attachmentsJson == "[]") return@forEach
            append(if (message.role == "assistant") "[Assistant]\n" else "[User]\n")
            if (content.isNotBlank()) append(content)
            val attachments = LocalAttachment.fromJson(message.attachmentsJson)
            if (attachments.isNotEmpty()) {
                if (content.isNotBlank()) append("\n")
                append("[Вложения: ")
                append(attachments.joinToString { it.name })
                append("]")
            }
            append("\n\n")
        }
    }.trim()

    private fun modelConfig(model: String): Pair<Int, Int> = when (model.lowercase()) {
        "gemini-auto" -> 1 to 0
        "gemini-3.7-flash" -> 1 to 4
        "gemini-3.6-flash", "gemini-3.5-flash" -> 1 to 4
        "gemini-3.5-flash-thinking" -> 2 to 0
        "gemini-3.1-pro" -> 3 to 4
        "gemini-3.5-flash-thinking-lite" -> 5 to 0
        "gemini-flash-lite" -> 6 to 4
        else -> 4 to 4
    }

    private suspend fun resolveWebModel(
        view: WebView,
        credentials: Credentials,
        boot: Bootstrap,
        requested: String
    ): WebModel? {
        val name = requested.trim()
        if (name.isBlank() || name.equals("gemini-auto", ignoreCase = true)) return null
        val cacheKey = accountCacheKey(credentials)
        var models = accountModels[cacheKey].orEmpty()
        if (models.isEmpty()) {
            val body = executeRpc(view, credentials, boot, RPC_USER_STATUS, "[]")
                .firstOrNull()?.let { runCatching { JSONArray(it) }.getOrNull() }
            if (body != null) {
                val status = body.opt(14).let { if (it is Number) it.toInt() else null }
                models = parseModels(body, status == 1016)
                if (models.isNotEmpty()) accountModels[cacheKey] = models
            }
        }
        val target = name.lowercase()
        models.firstOrNull {
            it.available && (
                it.modelName.equals(name, true) ||
                    it.displayName.equals(name, true) ||
                    it.modelId.equals(name, true)
            )
        }?.let { return it }
        val normalized = target.removePrefix("gemini-").replace("_", "-")
        return models.firstOrNull {
            it.available && it.modelName.lowercase().removePrefix("gemini-").replace("_", "-") == normalized
        }
    }

    private fun modelHeaderValue(model: WebModel, extendedThinking: Boolean): String {
        val header = JSONArray()
        header.put(1)
        header.put(JSONObject.NULL)
        header.put(JSONObject.NULL)
        header.put(JSONObject.NULL)
        header.put(model.modelId)
        header.put(JSONObject.NULL)
        header.put(JSONObject.NULL)
        header.put(0)
        header.put(JSONArray().put(4).put(5).put(6).put(8))
        header.put(JSONObject.NULL)
        header.put(JSONObject.NULL)
        if (model.capacityField == 13) {
            header.put(JSONObject.NULL)
            header.put(model.capacity)
        } else {
            header.put(model.capacity)
        }
        header.put(JSONObject.NULL)
        header.put(JSONObject.NULL)
        header.put(model.modelNumber)
        header.put(if (extendedThinking) 2 else 1)
        header.put(clientSessionId)
        return header.toString()
    }

    private fun cookieHeader(credentials: Credentials): String {
        val clean = Credentials.fromSecret(credentials.toSecret())
        return buildList {
            add("__Secure-1PSID=${clean.psid}")
            clean.psidts.takeIf { it.isNotBlank() }?.let { add("__Secure-1PSIDTS=$it") }
            clean.sapisid.takeIf { it.isNotBlank() }?.let { add("SAPISID=$it") }
        }.joinToString("; ")
    }

    private fun sapisidHash(credentials: Credentials): String? {
        val sapisid = Credentials.fromSecret(credentials.toSecret()).sapisid
            .takeIf { it.isNotBlank() } ?: return null
        val seconds = System.currentTimeMillis() / 1000L
        val value = "$seconds $sapisid $GOOGLE_ORIGIN"
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${seconds}_$hash"
    }

    private fun rpcScript(
        requestId: String,
        rpcId: String,
        payload: String,
        token: String,
        buildLabel: String,
        sessionId: String,
        authUser: Int,
        authHeader: String,
        sourcePath: String,
        pathPrefix: String
    ): String {
        val qId = JSONObject.quote(requestId)
        val qRpc = JSONObject.quote(rpcId)
        val qPayload = JSONObject.quote(payload)
        val qToken = JSONObject.quote(token)
        val qBl = JSONObject.quote(buildLabel)
        val qSid = JSONObject.quote(sessionId)
        val qAuth = JSONObject.quote(authHeader)
        val qSource = JSONObject.quote(sourcePath)
        val qPrefix = JSONObject.quote(pathPrefix)
        val qBridge = JSONObject.quote(BRIDGE_NAME)
        val qClientSession = JSONObject.quote(clientSessionId)
        return """
            (async function() {
              const requestId = $qId;
              const bridge = window[$qBridge];
              const fail = (m) => {
                try { bridge.emit(requestId, "error", String(m || "Gemini RPC error")); } catch (_) {}
              };
              try {
                const rpcId = $qRpc;
                const rpcPayload = $qPayload;
                const params = new URLSearchParams();
                params.set("rpcids", rpcId);
                params.set("hl", "ru");
                params.set("_reqid", String(Date.now() % 1000000));
                params.set("rt", "c");
                params.set("source-path", $qSource);
                const bl = $qBl;
                const sid = $qSid;
                if (bl) params.set("bl", bl);
                if (sid) params.set("f.sid", sid);

                const body = new URLSearchParams();
                body.set("at", $qToken);
                body.set("f.req", JSON.stringify([[[rpcId, rpcPayload, null, "generic"]]]));

                const genericModelHeader = [
                  1,null,null,null,null,null,null,null,[4,5,6,8],
                  null,null,null,null,null,null,null,$qClientSession
                ];
                const headers = {
                  "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                  "X-Same-Domain": "1",
                  "X-Goog-AuthUser": String($authUser),
                  "x-goog-ext-525001261-jspb": JSON.stringify(genericModelHeader),
                  "x-goog-ext-73010989-jspb": "[0]"
                };
                const auth = $qAuth;
                if (auth) headers["Authorization"] = auth;

                const prefix = $qPrefix;
                const url = prefix + "/_/BardChatUi/data/batchexecute?" + params.toString();
                const response = await fetch(url, {
                  method: "POST",
                  credentials: "include",
                  headers,
                  body: body.toString()
                });
                if (!response.ok) {
                  fail("Gemini RPC " + rpcId + " HTTP " + response.status);
                  return;
                }
                const text = await response.text();
                let buffer = text;
                if (buffer.startsWith(")]}'")) buffer = buffer.slice(4).replace(/^\s+/, "");
                const frames = [];
                while (buffer.length) {
                  buffer = buffer.replace(/^\s+/, "");
                  const marker = /^(\d+)\n/.exec(buffer);
                  if (!marker) break;
                  const expected = Number(marker[1]);
                  const start = marker[1].length;
                  if (!Number.isFinite(expected) || buffer.length - start < expected) break;
                  const chunk = buffer.slice(start, start + expected);
                  buffer = buffer.slice(start + expected);
                  try {
                    const parsed = JSON.parse(chunk);
                    if (Array.isArray(parsed)) frames.push(...parsed);
                    else frames.push(parsed);
                  } catch (_) {}
                }
                if (!frames.length) {
                  try {
                    const clean = text.replace(/^\)\]\}'/, "").trim();
                    const parsed = JSON.parse(clean);
                    if (Array.isArray(parsed)) frames.push(...parsed);
                    else frames.push(parsed);
                  } catch (_) {}
                }

                const bodies = [];
                const walk = (node) => {
                  if (!Array.isArray(node)) return;
                  if (node.length >= 3 && node[1] === rpcId && typeof node[2] === "string")
                    bodies.push(node[2]);
                  for (const child of node) if (Array.isArray(child)) walk(child);
                };
                frames.forEach(walk);
                if (!bodies.length) {
                  fail("Gemini RPC " + rpcId + " не вернул ожидаемый ответ");
                  return;
                }
                bridge.emit(requestId, "rpc", JSON.stringify(bodies));
              } catch (e) {
                fail(e && e.message ? e.message : String(e));
              }
            })();
        """.trimIndent()
    }

    private fun generateScript(
        requestId: String,
        prompt: String,
        token: String,
        buildLabel: String,
        sessionId: String,
        authUser: Int,
        authHeader: String,
        pathPrefix: String,
        mode: Int,
        thinkMode: Int,
        modelHeader: String,
        extendedThinking: Boolean,
        metadata: JSONArray,
        files: List<UploadedFile>
    ): String {
        val qId = JSONObject.quote(requestId)
        val qPrompt = JSONObject.quote(prompt)
        val qToken = JSONObject.quote(token)
        val qBl = JSONObject.quote(buildLabel)
        val qSid = JSONObject.quote(sessionId)
        val qAuth = JSONObject.quote(authHeader)
        val qPrefix = JSONObject.quote(pathPrefix)
        val qBridge = JSONObject.quote(BRIDGE_NAME)
        val qModelHeader = JSONObject.quote(modelHeader)
        val metadataJson = metadata.toString()
        val filesJson = JSONArray().also { array ->
            files.forEach { file ->
                array.put(
                    JSONArray()
                        .put(JSONArray().put(file.ref))
                        .put(file.name)
                )
            }
        }.toString()

        return """
            (async function() {
              const requestId = $qId;
              const bridge = window[$qBridge];
              const fail = (message) => {
                try { bridge.emit(requestId, "error", String(message || "Gemini Web error")); } catch (_) {}
              };
              try {
                const requestMetadata = $metadataJson;
                while (requestMetadata.length < 10) requestMetadata.push(null);
                const uploadedFiles = $filesJson;

                const inner = Array(81).fill(null);
                inner[0] = [$qPrompt, 0, null, uploadedFiles.length ? uploadedFiles : null, null, null, 0];
                inner[1] = ["ru"];
                inner[2] = requestMetadata;
                inner[6] = [1];
                inner[7] = 1;
                inner[10] = 1;
                inner[11] = 0;
                inner[17] = [[$thinkMode]];
                inner[18] = 0;
                inner[27] = 1;
                inner[30] = [4];
                inner[41] = [1];
                inner[53] = 0;
                inner[59] = (crypto && crypto.randomUUID) ? crypto.randomUUID().toUpperCase() :
                  (Date.now().toString(16) + Math.random().toString(16).slice(2)).toUpperCase();
                inner[61] = [];
                inner[68] = 1;
                inner[79] = $mode;
                inner[80] = ${if (extendedThinking) 2 else 1};

                const params = new URLSearchParams();
                params.set("f.req", JSON.stringify([null, JSON.stringify(inner)]));
                params.set("at", $qToken);

                const prefix = $qPrefix;
                let url = prefix + "/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate";
                url += "?bl=" + encodeURIComponent($qBl);
                const sid = $qSid;
                if (sid) url += "&f.sid=" + encodeURIComponent(sid);
                url += "&hl=ru&_reqid=" + (Date.now() % 1000000) + "&rt=c";

                const headers = {
                  "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                  "X-Same-Domain": "1",
                  "X-Goog-AuthUser": String($authUser),
                  "x-goog-ext-525005358-jspb": JSON.stringify([inner[59], 1])
                };
                const modelHeader = $qModelHeader;
                if (modelHeader) {
                  headers["x-goog-ext-525001261-jspb"] = modelHeader;
                  headers["x-goog-ext-73010989-jspb"] = "[0]";
                  headers["x-goog-ext-73010990-jspb"] = "[0,0,0]";
                }
                const auth = $qAuth;
                if (auth) headers["Authorization"] = auth;

                const response = await fetch(url, {
                  method: "POST",
                  credentials: "include",
                  headers,
                  body: params.toString()
                });

                if (!response.ok) {
                  fail("Gemini Web HTTP " + response.status +
                    ". Сессия отклонена или внутренний протокол изменился.");
                  return;
                }

                let frameBuffer = "";
                let prefixChecked = false;
                let last = "";
                let rawWindow = "";
                const state = requestMetadata.slice(0, 10);

                const mergeMetadata = (incoming) => {
                  if (!Array.isArray(incoming)) return;
                  while (state.length < 10) state.push(null);
                  for (let i = 0; i < Math.min(10, incoming.length); i++) {
                    if (incoming[i] !== null && incoming[i] !== undefined)
                      state[i] = incoming[i];
                  }
                };

                const inspectDecoded = (decoded) => {
                  if (!Array.isArray(decoded)) return;
                  if (Array.isArray(decoded[1])) mergeMetadata(decoded[1]);
                  if (typeof decoded[25] === "string" && decoded[25])
                    state[9] = decoded[25];

                  const candidates = decoded[4];
                  if (!Array.isArray(candidates)) return;
                  let best = "";
                  for (const candidate of candidates) {
                    if (!Array.isArray(candidate)) continue;
                    if (typeof candidate[0] === "string" && candidate[0])
                      state[2] = candidate[0];
                    const texts = candidate[1];
                    if (!Array.isArray(texts)) continue;
                    for (const value of texts) {
                      if (typeof value === "string" && value.length > best.length)
                        best = value;
                    }
                  }
                  if (best && best.length >= last.length && best !== last) {
                    last = best;
                    bridge.emit(requestId, "chunk", last);
                  }
                };

                const walkFrames = (node) => {
                  if (!Array.isArray(node)) return;
                  if (node.length >= 3 && node[0] === "wrb.fr" && typeof node[2] === "string") {
                    try { inspectDecoded(JSON.parse(node[2])); } catch (_) {}
                    return;
                  }
                  for (const child of node) if (Array.isArray(child)) walkFrames(child);
                };

                // Google currently frames StreamGenerate as:
                //   <UTF-16 length>\n<JSON envelope>
                // JavaScript string.length already counts UTF-16 code units, which matches
                // the web protocol's length marker semantics.
                const feedFrames = (chunk) => {
                  frameBuffer += chunk || "";
                  if (!prefixChecked) {
                    if (frameBuffer.length < 4 && ")]}'".startsWith(frameBuffer)) return;
                    if (frameBuffer.startsWith(")]}'"))
                      frameBuffer = frameBuffer.slice(4).replace(/^\s+/, "");
                    prefixChecked = true;
                  }
                  while (true) {
                    frameBuffer = frameBuffer.replace(/^\s+/, "");
                    const marker = /^(\d+)\n/.exec(frameBuffer);
                    if (!marker) break;
                    const expectedUnits = Number(marker[1]);
                    const payloadStart = marker[1].length;
                    if (!Number.isFinite(expectedUnits) ||
                        frameBuffer.length - payloadStart < expectedUnits) break;
                    const payload = frameBuffer.slice(payloadStart, payloadStart + expectedUnits);
                    frameBuffer = frameBuffer.slice(payloadStart + expectedUnits);
                    try {
                      const parsed = JSON.parse(payload);
                      if (Array.isArray(parsed)) {
                        for (const part of parsed) walkFrames(part);
                      } else {
                        walkFrames(parsed);
                      }
                    } catch (_) {}
                  }
                };

                const fallbackParse = (text) => {
                  if (!text) return;
                  let clean = text.replace(/^\)\]\}'/, "").trim();
                  try {
                    const parsed = JSON.parse(clean);
                    if (Array.isArray(parsed)) {
                      for (const part of parsed) walkFrames(part);
                    } else {
                      walkFrames(parsed);
                    }
                    return;
                  } catch (_) {}
                  for (const line of clean.split("\n")) {
                    const candidate = line.trim();
                    if (!candidate || /^\d+$/.test(candidate) ||
                        candidate.indexOf('"wrb.fr"') < 0) continue;
                    try { walkFrames(JSON.parse(candidate)); } catch (_) {}
                  }
                };

                if (response.body && response.body.getReader) {
                  const reader = response.body.getReader();
                  const decoder = new TextDecoder("utf-8");
                  while (true) {
                    const item = await reader.read();
                    if (item.done) break;
                    const chunk = decoder.decode(item.value, {stream:true});
                    if (rawWindow.length < 24000)
                      rawWindow += chunk.slice(0, 24000 - rawWindow.length);
                    feedFrames(chunk);
                  }
                  feedFrames(decoder.decode());
                } else {
                  const text = await response.text();
                  rawWindow = text.slice(0, 24000);
                  feedFrames(text);
                }
                if (!last) fallbackParse(rawWindow);

                bridge.emit(requestId, "state", JSON.stringify(state));
                if (!last) {
                  const match = /BardErrorInfo[^0-9]{0,40}(\d+)/.exec(rawWindow);
                  fail(match ? ("Gemini Web отклонил запрос (код " + match[1] + ")") :
                    "Gemini Web не вернул текст. Веб-протокол мог измениться.");
                  return;
                }
                bridge.emit(requestId, "done", last);
              } catch (error) {
                fail(error && error.message ? error.message : String(error));
              }
            })();
        """.trimIndent()
    }

    companion object {
        const val PROVIDER_TYPE = "gemini_web"
        const val PROTOCOL = "gemini-web"

        val MODELS = listOf(
            "gemini-auto",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash-thinking",
            "gemini-3.1-pro",
            "gemini-3.5-flash-thinking-lite",
            "gemini-flash-lite"
        )

        private const val BRIDGE_NAME = "OmniGeminiBridge"
        private const val GOOGLE_ORIGIN = "https://gemini.google.com"
        private const val UPLOAD_URL = "https://content-push.googleapis.com/upload"
        private const val RPC_USER_STATUS = "otAQ7b"
        private const val RPC_QUOTA = "qpEbW"
        private const val RPC_USAGE_INFO = "jSf9Qc"
        private const val RPC_LIST_CHATS = "MaZiqc"
        private const val RPC_CHAT_TURNS = "hNvQHb"
        private const val FLASH_QUOTA_PAYLOAD = "[[[1,11],[2,11],[6,11]]]"
        private const val ADVANCED_QUOTA_PAYLOAD = "[[[1,4],[6,6],[1,15]]]"
        private const val DEFAULT_PUSH_ID = "feeds/mcudyrk2a4khkz"
        private const val MAX_UPLOAD_BYTES = 8_000_000L
        private const val DEFAULT_METADATA_JSON =
            "[\"\",\"\",\"\",null,null,null,null,null,null,\"\"]"

        private val BOOTSTRAP_SCRIPT = """
            (function() {
              const html = (document.documentElement && document.documentElement.innerHTML || "")
                .replace(/&quot;/g, '"');
              const take = (key) => {
                try {
                  const direct = new RegExp('"' + key + '":"([^"]+)"').exec(html);
                  if (direct && direct[1]) return direct[1];
                  const escaped = new RegExp('\\\\?"' + key + '\\\\?":\\\\?"([^"\\\\]+)').exec(html);
                  return escaped && escaped[1] ? escaped[1] : "";
                } catch (_) { return ""; }
              };
              return JSON.stringify({
                href: String(location.href || ""),
                token: take("SNlM0e"),
                bl: take("cfb2h"),
                sid: take("FdrFJe"),
                pushId: take("qKIAYe")
              });
            })();
        """.trimIndent()
    }
}
