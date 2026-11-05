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

/* TelePremium: GeminiWebCapabilityScanner removed (depends on unfinished OmniChat APIs). */

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

/* TelePremium: GeminiWebAssetCollector removed (depends on unfinished OmniChat APIs). */

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

/* TelePremium: GeminiWebFeatureBridge removed (depends on unfinished OmniChat APIs). */
