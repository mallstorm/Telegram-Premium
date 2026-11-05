package com.omnichat.app.network

import android.content.Context
import com.omnichat.app.data.MessageEntity
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

enum class GeminiSessionConfidence {
    NONE,
    COOKIE_CAPTURED,
    BROWSER_AUTHENTICATED,
    RPC_AUTHENTICATED,
    FULLY_DISCOVERED
}

enum class GeminiCapabilityState {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
    TEMPORARILY_UNAVAILABLE
}

enum class GeminiWebFeature {
    TEXT_CHAT,
    IMAGE_GENERATE,
    IMAGE_EDIT,
    FILE_ANALYSIS,
    MULTIMODAL_CHAT,
    MUSIC_GENERATE,
    WEB_SEARCH,
    DEEP_RESEARCH
}

enum class GeminiExecutionMode {
    NATIVE_BRIDGE,
    ASSISTED_WEBVIEW,
    UNSUPPORTED
}

enum class GeminiDiscoveryMode { LIGHT, FULL, ON_DEMAND }

data class GeminiWebAction(
    val id: String,
    val label: String,
    val feature: GeminiWebFeature,
    val executionMode: GeminiExecutionMode
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("feature", feature.name)
        .put("executionMode", executionMode.name)

    companion object {
        fun fromJson(value: JSONObject): GeminiWebAction? = runCatching {
            GeminiWebAction(
                id = value.getString("id"),
                label = value.optString("label"),
                feature = GeminiWebFeature.valueOf(value.getString("feature")),
                executionMode = GeminiExecutionMode.valueOf(
                    value.optString("executionMode", GeminiExecutionMode.NATIVE_BRIDGE.name)
                )
            )
        }.getOrNull()
    }
}

data class GeminiWebCapabilities(
    val textChat: GeminiCapabilityState = GeminiCapabilityState.AVAILABLE,
    val imageGeneration: GeminiCapabilityState = GeminiCapabilityState.UNKNOWN,
    val imageEditing: GeminiCapabilityState = GeminiCapabilityState.UNKNOWN,
    val imageUpload: GeminiCapabilityState = GeminiCapabilityState.AVAILABLE,
    val fileUpload: GeminiCapabilityState = GeminiCapabilityState.AVAILABLE,
    val pdfUpload: GeminiCapabilityState = GeminiCapabilityState.AVAILABLE,
    val musicGeneration: GeminiCapabilityState = GeminiCapabilityState.UNKNOWN,
    val audioOutput: GeminiCapabilityState = GeminiCapabilityState.UNKNOWN,
    val videoInput: GeminiCapabilityState = GeminiCapabilityState.UNKNOWN,
    val deepResearch: GeminiCapabilityState = GeminiCapabilityState.UNKNOWN,
    val webSearch: GeminiCapabilityState = GeminiCapabilityState.UNKNOWN,
    val availableModels: List<String> = listOf("gemini-auto"),
    val availableActions: List<GeminiWebAction> = emptyList(),
    val accountTier: String? = null,
    val confidence: GeminiSessionConfidence = GeminiSessionConfidence.BROWSER_AUTHENTICATED,
    val routeToken: String = "",
    val lastCheckedAt: Long = System.currentTimeMillis()
) {
    fun state(feature: GeminiWebFeature): GeminiCapabilityState = when (feature) {
        GeminiWebFeature.TEXT_CHAT -> textChat
        GeminiWebFeature.IMAGE_GENERATE -> imageGeneration
        GeminiWebFeature.IMAGE_EDIT -> imageEditing
        GeminiWebFeature.FILE_ANALYSIS -> fileUpload
        GeminiWebFeature.MULTIMODAL_CHAT -> imageUpload
        GeminiWebFeature.MUSIC_GENERATE -> musicGeneration
        GeminiWebFeature.WEB_SEARCH -> webSearch
        GeminiWebFeature.DEEP_RESEARCH -> deepResearch
    }

    fun toJson(): JSONObject = JSONObject()
        .put("textChat", textChat.name)
        .put("imageGeneration", imageGeneration.name)
        .put("imageEditing", imageEditing.name)
        .put("imageUpload", imageUpload.name)
        .put("fileUpload", fileUpload.name)
        .put("pdfUpload", pdfUpload.name)
        .put("musicGeneration", musicGeneration.name)
        .put("audioOutput", audioOutput.name)
        .put("videoInput", videoInput.name)
        .put("deepResearch", deepResearch.name)
        .put("webSearch", webSearch.name)
        .put("availableModels", JSONArray(availableModels))
        .put("availableActions", JSONArray().also { array -> availableActions.forEach { array.put(it.toJson()) } })
        .put("accountTier", accountTier.orEmpty())
        .put("confidence", confidence.name)
        .put("routeToken", routeToken)
        .put("lastCheckedAt", lastCheckedAt)

    fun compactLabel(): String = buildList {
        add("Chat")
        if (imageGeneration == GeminiCapabilityState.AVAILABLE) add("Images")
        if (imageEditing == GeminiCapabilityState.AVAILABLE) add("Edit")
        if (fileUpload == GeminiCapabilityState.AVAILABLE) add("Files")
        if (musicGeneration == GeminiCapabilityState.AVAILABLE) add("Music")
        if (webSearch == GeminiCapabilityState.AVAILABLE) add("Search")
        if (deepResearch == GeminiCapabilityState.AVAILABLE) add("Research")
    }.joinToString(" · ")

    companion object {
        private fun state(value: String, fallback: GeminiCapabilityState) =
            runCatching { GeminiCapabilityState.valueOf(value) }.getOrDefault(fallback)

        fun fromJson(value: JSONObject?): GeminiWebCapabilities? {
            value ?: return null
            return runCatching {
                val models = value.optJSONArray("availableModels") ?: JSONArray()
                val actions = value.optJSONArray("availableActions") ?: JSONArray()
                GeminiWebCapabilities(
                    textChat = state(value.optString("textChat"), GeminiCapabilityState.AVAILABLE),
                    imageGeneration = state(value.optString("imageGeneration"), GeminiCapabilityState.UNKNOWN),
                    imageEditing = state(value.optString("imageEditing"), GeminiCapabilityState.UNKNOWN),
                    imageUpload = state(value.optString("imageUpload"), GeminiCapabilityState.AVAILABLE),
                    fileUpload = state(value.optString("fileUpload"), GeminiCapabilityState.AVAILABLE),
                    pdfUpload = state(value.optString("pdfUpload"), GeminiCapabilityState.AVAILABLE),
                    musicGeneration = state(value.optString("musicGeneration"), GeminiCapabilityState.UNKNOWN),
                    audioOutput = state(value.optString("audioOutput"), GeminiCapabilityState.UNKNOWN),
                    videoInput = state(value.optString("videoInput"), GeminiCapabilityState.UNKNOWN),
                    deepResearch = state(value.optString("deepResearch"), GeminiCapabilityState.UNKNOWN),
                    webSearch = state(value.optString("webSearch"), GeminiCapabilityState.UNKNOWN),
                    availableModels = (0 until models.length()).mapNotNull { index ->
                        models.optString(index).takeIf(String::isNotBlank)
                    }.ifEmpty { listOf("gemini-auto") },
                    availableActions = (0 until actions.length()).mapNotNull { index ->
                        actions.optJSONObject(index)?.let(GeminiWebAction::fromJson)
                    },
                    accountTier = value.optString("accountTier").takeIf(String::isNotBlank),
                    confidence = runCatching {
                        GeminiSessionConfidence.valueOf(
                            value.optString("confidence", GeminiSessionConfidence.BROWSER_AUTHENTICATED.name)
                        )
                    }.getOrDefault(GeminiSessionConfidence.BROWSER_AUTHENTICATED),
                    routeToken = value.optString("routeToken"),
                    lastCheckedAt = value.optLong("lastCheckedAt", 0L)
                )
            }.getOrNull()
        }
    }
}

/** DOM signals discovered from the authenticated consumer UI. No cookies or prompt text are stored. */
data class GeminiWebUiSignals(
    val composerReady: Boolean,
    val uploadAction: Boolean,
    val labels: List<String>
)

class GeminiWebCapabilityScanner(
    private val client: GeminiWebClient
) {
    suspend fun scan(
        credentials: GeminiWebClient.Credentials,
        mode: GeminiDiscoveryMode = GeminiDiscoveryMode.FULL,
        snapshotHint: GeminiWebClient.AccountSnapshot? = null
    ): GeminiWebCapabilities {
        val snapshot = snapshotHint ?: withTimeoutOrNull(if (mode == GeminiDiscoveryMode.LIGHT) 18_000L else 65_000L) {
            client.inspectAccount(credentials)
        }
        val ui = withTimeoutOrNull(if (mode == GeminiDiscoveryMode.LIGHT) 10_000L else 25_000L) {
            client.discoverUiSignals(credentials)
        }
        val route = runCatching { WebViewXrayProxy.ensureCurrentRoute() }.getOrDefault("")
        val labels = ui?.labels.orEmpty().joinToString(" ").lowercase(Locale.ROOT)

        fun containsAny(vararg markers: String): Boolean = markers.any(labels::contains)
        fun discoveredState(found: Boolean): GeminiCapabilityState =
            if (found) GeminiCapabilityState.AVAILABLE else GeminiCapabilityState.UNKNOWN

        val imageFound = containsAny(
            "image", "imagen", "изображ", "картин", "рисунок", "generate image", "создать изображ"
        )
        val musicFound = containsAny("music", "lyria", "музык", "song", "audio generation")
        val researchFound = containsAny("deep research", "глубокое исслед", "research")
        val searchFound = containsAny("search", "поиск в интернете", "google search")
        val uploadFound = ui?.uploadAction == true || containsAny("upload", "attach", "прикреп", "загруз")
        val models = (listOf("gemini-auto") + snapshot?.models.orEmpty()
            .filter { it.available }
            .map { it.modelName })
            .distinct()
        val restricted = snapshot?.statusCode in setOf(1016, 1021, 1054, 1057, 1060)
        val confidence = when {
            restricted -> GeminiSessionConfidence.COOKIE_CAPTURED
            snapshot?.statusCode == 1000 && models.size > 1 -> GeminiSessionConfidence.FULLY_DISCOVERED
            snapshot != null -> GeminiSessionConfidence.RPC_AUTHENTICATED
            ui?.composerReady == true -> GeminiSessionConfidence.BROWSER_AUTHENTICATED
            else -> GeminiSessionConfidence.COOKIE_CAPTURED
        }

        val actions = buildList {
            add(GeminiWebAction("chat", "Chat", GeminiWebFeature.TEXT_CHAT, GeminiExecutionMode.NATIVE_BRIDGE))
            if (imageFound) {
                add(GeminiWebAction("image_generate", "Images", GeminiWebFeature.IMAGE_GENERATE, GeminiExecutionMode.NATIVE_BRIDGE))
                add(GeminiWebAction("image_edit", "Image edit", GeminiWebFeature.IMAGE_EDIT, GeminiExecutionMode.NATIVE_BRIDGE))
            }
            if (musicFound) add(
                GeminiWebAction("music_generate", "Music", GeminiWebFeature.MUSIC_GENERATE, GeminiExecutionMode.NATIVE_BRIDGE)
            )
            if (searchFound) add(GeminiWebAction("web_search", "Search", GeminiWebFeature.WEB_SEARCH, GeminiExecutionMode.NATIVE_BRIDGE))
            if (researchFound) add(GeminiWebAction("deep_research", "Research", GeminiWebFeature.DEEP_RESEARCH, GeminiExecutionMode.ASSISTED_WEBVIEW))
        }

        return GeminiWebCapabilities(
            textChat = if (restricted) GeminiCapabilityState.UNAVAILABLE else GeminiCapabilityState.AVAILABLE,
            imageGeneration = if (restricted) GeminiCapabilityState.UNAVAILABLE else discoveredState(imageFound),
            imageEditing = if (restricted) GeminiCapabilityState.UNAVAILABLE else discoveredState(imageFound && uploadFound),
            imageUpload = if (restricted) GeminiCapabilityState.UNAVAILABLE else GeminiCapabilityState.AVAILABLE,
            fileUpload = if (restricted) GeminiCapabilityState.UNAVAILABLE else GeminiCapabilityState.AVAILABLE,
            pdfUpload = if (restricted) GeminiCapabilityState.UNAVAILABLE else GeminiCapabilityState.AVAILABLE,
            musicGeneration = if (restricted) GeminiCapabilityState.UNAVAILABLE else discoveredState(musicFound),
            audioOutput = if (restricted) GeminiCapabilityState.UNAVAILABLE else discoveredState(musicFound),
            videoInput = if (restricted) GeminiCapabilityState.UNAVAILABLE else GeminiCapabilityState.UNKNOWN,
            deepResearch = if (restricted) GeminiCapabilityState.UNAVAILABLE else discoveredState(researchFound),
            webSearch = if (restricted) GeminiCapabilityState.UNAVAILABLE else discoveredState(searchFound),
            availableModels = models,
            availableActions = actions,
            accountTier = snapshot?.tierLabel,
            confidence = confidence,
            routeToken = route,
            lastCheckedAt = System.currentTimeMillis()
        )
    }
}

class GeminiWebFeatureRouter {
    fun route(
        text: String,
        attachments: List<LocalAttachment>,
        capabilities: GeminiWebCapabilities? = null
    ): GeminiWebFeature {
        val normalized = text.lowercase(Locale.ROOT).replace('ё', 'е')
        val hasImage = attachments.any { it.mime.startsWith("image/", ignoreCase = true) }
        val hasFile = attachments.isNotEmpty()

        val music = listOf(
            "сгенерируй музыку", "создай музыку", "создай песню", "напиши песню",
            "сделай трек", "музыкальную композицию", "generate music", "create music",
            "make a song", "generate a song"
        ).any(normalized::contains)
        if (music && capabilities?.musicGeneration != GeminiCapabilityState.UNAVAILABLE) {
            return GeminiWebFeature.MUSIC_GENERATE
        }

        val imageEdit = hasImage && listOf(
            "измени", "замени", "убери", "добавь", "сделай", "переделай", "edit", "replace", "remove", "change"
        ).any(normalized::contains)
        if (imageEdit) return GeminiWebFeature.IMAGE_EDIT

        val imageGeneration = listOf(
            "сгенерируй изображ", "создай изображ", "нарисуй", "сделай картин", "сгенерируй картин",
            "generate image", "create image", "draw ", "make an image", "make a picture"
        ).any(normalized::contains)
        if (imageGeneration && capabilities?.imageGeneration != GeminiCapabilityState.UNAVAILABLE) {
            return GeminiWebFeature.IMAGE_GENERATE
        }

        if (hasImage) return GeminiWebFeature.MULTIMODAL_CHAT
        if (hasFile) return GeminiWebFeature.FILE_ANALYSIS

        if (listOf("deep research", "глубокое исследование", "глубокий поиск").any(normalized::contains) &&
            capabilities?.deepResearch != GeminiCapabilityState.UNAVAILABLE
        ) return GeminiWebFeature.DEEP_RESEARCH

        if (listOf("найди в интернете", "поищи в интернете", "web search", "search the web").any(normalized::contains) &&
            capabilities?.webSearch != GeminiCapabilityState.UNAVAILABLE
        ) return GeminiWebFeature.WEB_SEARCH

        return GeminiWebFeature.TEXT_CHAT
    }
}

data class GeminiWebFeatureResult(
    val text: String,
    val state: GeminiWebClient.ConversationState,
    val attachments: List<LocalAttachment>,
    val feature: GeminiWebFeature,
    val executionMode: GeminiExecutionMode = GeminiExecutionMode.NATIVE_BRIDGE
)

/**
 * Downloads generated consumer-web assets into OmniChat's private attachment storage.
 * The default JVM ProxySelector remains active, so InAppVlessRouter routes these HTTP calls
 * through the same loopback Xray route used by the rest of OmniChat when Xray is enabled.
 */
class GeminiWebAssetCollector(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun collect(
        refs: List<GeminiWebClient.GeneratedAssetRef>,
        credentials: GeminiWebClient.Credentials,
        effectivePrompt: String
    ): List<LocalAttachment> = withContext(Dispatchers.IO) {
        refs.distinctBy { it.url }.mapIndexedNotNull { index, ref ->
            runCatching { download(ref, credentials, effectivePrompt, index) }.getOrNull()
        }
    }

    private fun download(
        ref: GeminiWebClient.GeneratedAssetRef,
        credentials: GeminiWebClient.Credentials,
        effectivePrompt: String,
        index: Int
    ): LocalAttachment? {
        val url = ref.url.trim()
        if (!url.startsWith("https://")) return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Mobile Safari/537.36")
            .header("Referer", "https://gemini.google.com/")
            .header("Cookie", cookieHeader(credentials))
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Gemini asset HTTP ${response.code}")
            val body = response.body ?: return null
            val mime = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.startsWith("image/") || it.startsWith("audio/") || it.startsWith("video/") }
                ?: ref.mime.takeIf { it.startsWith("image/") || it.startsWith("audio/") || it.startsWith("video/") }
                ?: return null
            val maxBytes = when {
                mime.startsWith("image/") -> 64L * 1024L * 1024L
                mime.startsWith("audio/") -> 160L * 1024L * 1024L
                else -> 256L * 1024L * 1024L
            }
            val declared = body.contentLength()
            if (declared > maxBytes) throw IOException("Gemini asset слишком большой")
            val bytes = body.bytes()
            if (bytes.isEmpty() || bytes.size.toLong() > maxBytes) return null
            val ext = extension(mime)
            val dir = File(context.filesDir, "attachments/gemini_web").also { it.mkdirs() }
            val file = File(dir, "gemini-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}-$index.$ext")
            file.writeBytes(bytes)
            return LocalAttachment.generated(
                name = file.name,
                mime = mime,
                path = file.absolutePath,
                size = file.length(),
                displayTitle = when {
                    mime.startsWith("image/") -> "Gemini image ${index + 1}"
                    mime.startsWith("audio/") -> "Gemini audio ${index + 1}"
                    else -> "Gemini media ${index + 1}"
                },
                effectivePrompt = effectivePrompt,
                variantIndex = index
            )
        }
    }

    private fun cookieHeader(credentials: GeminiWebClient.Credentials): String = buildList {
        if (credentials.psid.isNotBlank()) add("__Secure-1PSID=${credentials.psid}")
        if (credentials.psidts.isNotBlank()) add("__Secure-1PSIDTS=${credentials.psidts}")
        if (credentials.sapisid.isNotBlank()) add("SAPISID=${credentials.sapisid}")
    }.joinToString("; ")

    private fun extension(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        "audio/mp4", "audio/m4a" -> "m4a"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        else -> if (mime.startsWith("image/")) "jpg" else "bin"
    }
}

object GeminiWebDiagnostics {
    private const val PREFS = "gemini_web_diagnostics"

    fun record(context: Context, providerId: Long, event: String, detail: String = "") {
        if (providerId <= 0L) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "provider_$providerId"
        val current = prefs.getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        val next = JSONArray()
        val start = (current.length() - 39).coerceAtLeast(0)
        for (i in start until current.length()) next.put(current.optJSONObject(i))
        next.put(
            JSONObject()
                .put("time", System.currentTimeMillis())
                .put("event", event.take(80))
                .put("detail", detail.take(240))
        )
        prefs.edit().putString(key, next.toString()).apply()
    }
}

class GeminiWebFeatureBridge(
    private val context: Context,
    private val client: GeminiWebClient,
    private val router: GeminiWebFeatureRouter = GeminiWebFeatureRouter(),
    private val collector: GeminiWebAssetCollector = GeminiWebAssetCollector(context)
) {
    suspend fun execute(
        providerId: Long,
        credentials: GeminiWebClient.Credentials,
        model: String,
        systemPrompt: String,
        history: List<MessageEntity>,
        attachments: List<LocalAttachment>,
        conversation: GeminiWebClient.ConversationState?,
        onText: suspend (String) -> Unit
    ): GeminiWebFeatureResult {
        val capabilities = GeminiWebSessionStore.read(context, providerId)?.capabilities
        val latestText = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        var feature = router.route(latestText, attachments, capabilities)
        var activeCapabilities = capabilities

        val state = activeCapabilities?.state(feature)
        if (state == null || state == GeminiCapabilityState.UNKNOWN || state == GeminiCapabilityState.TEMPORARILY_UNAVAILABLE) {
            val scanned = withTimeoutOrNull(28_000L) {
                GeminiWebCapabilityScanner(client).scan(credentials, GeminiDiscoveryMode.ON_DEMAND)
            }
            if (scanned != null) {
                activeCapabilities = scanned
                GeminiWebSessionStore.saveCapabilities(context, providerId, scanned)
                feature = router.route(latestText, attachments, scanned)
            }
        }

        if (activeCapabilities?.state(feature) == GeminiCapabilityState.UNAVAILABLE) {
            feature = GeminiWebFeature.TEXT_CHAT
        }

        GeminiWebDiagnostics.record(context, providerId, "feature_start", feature.name)
        val generated = client.generate(
            credentials = credentials,
            model = model,
            systemPrompt = featureInstruction(systemPrompt, feature),
            history = history,
            attachments = attachments,
            conversation = conversation,
            onText = onText
        )
        val liveCredentials = runCatching { client.currentBrowserCredentials(credentials) }.getOrDefault(credentials)
        val collected = collector.collect(generated.assets, liveCredentials, latestText)
        GeminiWebDiagnostics.record(
            context,
            providerId,
            "feature_complete",
            "${feature.name}; assets=${collected.size}; refs=${generated.assets.size}"
        )
        return GeminiWebFeatureResult(
            text = generated.text.ifBlank {
                when {
                    collected.any { it.mime.startsWith("image/") } -> "Изображение создано."
                    collected.any { it.mime.startsWith("audio/") } -> "Музыка создана."
                    collected.isNotEmpty() -> "Готово."
                    else -> "Gemini завершил операцию."
                }
            },
            state = generated.state,
            attachments = collected,
            feature = feature
        )
    }

    private fun featureInstruction(base: String, feature: GeminiWebFeature): String {
        val extra = when (feature) {
            GeminiWebFeature.IMAGE_GENERATE ->
                "Используй доступный в Gemini инструмент генерации изображений. Верни созданное изображение, а не только текстовое описание."
            GeminiWebFeature.IMAGE_EDIT ->
                "Отредактируй приложенное изображение средствами Gemini согласно последней инструкции. Верни новый визуальный результат."
            GeminiWebFeature.MUSIC_GENERATE ->
                "Если в текущем Gemini аккаунте доступна генерация музыки/аудио, создай аудиорезультат по последней инструкции и верни media asset."
            GeminiWebFeature.WEB_SEARCH ->
                "Используй доступный consumer-web поиск Gemini, если он включён для этого аккаунта."
            GeminiWebFeature.DEEP_RESEARCH ->
                "Используй доступный режим Deep Research Gemini, если он доступен этой сессии."
            else -> ""
        }
        return listOf(base, extra).filter(String::isNotBlank).joinToString("\n\n")
    }
}
