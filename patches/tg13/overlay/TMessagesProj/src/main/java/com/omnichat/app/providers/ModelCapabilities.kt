package com.omnichat.app.providers

import com.omnichat.app.network.KiloGatewayRepository
import com.omnichat.app.network.NvidiaNimClient
import com.omnichat.app.network.SubscriptionProxyGeminiSupport

enum class ModelPrimaryCapability {
    CHAT,
    VISION_CHAT,
    IMAGE_GENERATION,
    MUSIC_GENERATION,
    VIDEO_GENERATION,
    EMBEDDING,
    RERANK,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    SAFETY,
    UNKNOWN
}

enum class ModelAccessKind { FREE, SUBSCRIPTION, PAID, UNKNOWN }

/** Provider-aware capability and access classification for the unified model picker. */
object ModelCapabilities {
    private const val NVIDIA_PROXY_PROVIDER = "subscription-proxy-nvidia"
    private const val GEMINI_PROXY_PROVIDER = "subscription-proxy-gemini"

    fun canChat(providerType: String, model: String): Boolean {
        if (model.isBlank()) return false
        if (providerType == GEMINI_PROXY_PROVIDER &&
            SubscriptionProxyGeminiSupport.isConversationalImageModel(model)
        ) return true
        if (isImageGeneration(providerType, model) ||
            isMusicGeneration(providerType, model) ||
            isVideoGeneration(providerType, model)
        ) return false
        if (!AccountProviderCatalog.supportsModel(providerType, model)) return false
        if (isUtilityModel(model) || isSafetyModel(model)) return false
        if (providerType == "yandex") return model.startsWith("gpt://", ignoreCase = true)
            && !model.contains("embedding", ignoreCase = true)
            && !model.contains("speech-realtime", ignoreCase = true)
            && !model.endsWith("/deprecated", ignoreCase = true)
        if (providerType != "gigachat") return true
        return model.startsWith("GigaChat", ignoreCase = true) &&
            !model.contains("embedding", ignoreCase = true)
    }

    fun isSafetyModel(model: String): Boolean {
        val id = model.lowercase()
        return id.contains("content-safety") ||
            id.contains("safeguard") ||
            id.contains("moderation") ||
            id.contains("guardrail") ||
            id.contains("llama-guard") ||
            id.endsWith("-safety") ||
            id.contains("/safety")
    }

    private fun isEmbeddingModel(model: String): Boolean {
        val id = model.lowercase()
        return id.contains("embedding") || id.startsWith("embed-") || id.contains("/embed-")
    }

    private fun isRerankModel(model: String): Boolean = model.lowercase().contains("rerank")

    private fun isSpeechToTextModel(model: String): Boolean {
        val id = model.lowercase()
        return id.contains("whisper") || id.contains("transcrib") ||
            id.contains("speech-to-text") || id.startsWith("stt-") || id.contains("/stt-")
    }

    private fun isTextToSpeechModel(model: String): Boolean {
        val id = model.lowercase()
        return id.contains("text-to-speech") || id.startsWith("tts-") ||
            id.contains("/tts-") || id.contains("/tts") || id.endsWith("-tts")
    }

    private fun isUtilityModel(model: String): Boolean =
        isEmbeddingModel(model) || isRerankModel(model) ||
            isSpeechToTextModel(model) || isTextToSpeechModel(model)

    private fun looksLikeImageGenerator(id: String): Boolean =
        id.startsWith("gpt-image-") || id.contains("/gpt-image-") ||
            id.contains("dall-e") || id.contains("flux") || id.contains("seedream") ||
            id.contains("nano-banana") || id.contains("imagen-") ||
            id.contains("/imagen-") || id.contains("image-generation")

    private fun looksLikeVideoGenerator(id: String): Boolean =
        id.contains("veo") || id.contains("seedance") || id.contains("kling") ||
            id.contains("hailuo") || id.contains("wan-video") || id.contains("ltx-video") ||
            id.contains("sora")

    fun isImageGeneration(providerType: String, model: String): Boolean {
        val id = model.lowercase()
        if (providerType == "yandex")
            return id.startsWith("art://") || id == "aliceai-image-art-3.0"
        if (providerType == GEMINI_PROXY_PROVIDER)
            return SubscriptionProxyGeminiSupport.isImageModel(model) &&
                !SubscriptionProxyGeminiSupport.isMusicModel(model) &&
                !SubscriptionProxyGeminiSupport.isVeoModel(model)
        if (providerType == ProviderCatalog.OPENROUTER_ID ||
            providerType == ProviderCatalog.KILO_GATEWAY_ID
        ) return looksLikeImageGenerator(id)
        return id.startsWith("gpt-image-")
    }

    /** Semantic image capability and executable transport are deliberately separate. */
    fun canExecuteImageGeneration(providerType: String, model: String): Boolean {
        if (!isImageGeneration(providerType, model)) return false
        return providerType == "yandex" || providerType == GEMINI_PROXY_PROVIDER
    }

    /** ApiGateway.generateImage is YandexART-only. */
    fun usesYandexImageTransport(providerType: String, model: String): Boolean =
        providerType == "yandex" && isImageGeneration(providerType, model)

    fun isMusicGeneration(providerType: String, model: String): Boolean {
        if (providerType == ProviderCatalog.KILO_GATEWAY_ID)
            return KiloGatewayRepository.isLyriaModel(model)
        return providerType == GEMINI_PROXY_PROVIDER &&
            SubscriptionProxyGeminiSupport.isMusicModel(model)
    }

    fun isVideoGeneration(providerType: String, model: String): Boolean {
        val id = model.lowercase()
        if (providerType == GEMINI_PROXY_PROVIDER)
            return SubscriptionProxyGeminiSupport.isVeoModel(model)
        return providerType in setOf(ProviderCatalog.OPENROUTER_ID, ProviderCatalog.KILO_GATEWAY_ID) &&
            looksLikeVideoGenerator(id)
    }

    /**
     * Select only capabilities for which OmniChat has an execution path. Known video-generator
     * IDs remain classified as VIDEO_GENERATION but are not selectable until their transport exists.
     * Kilo currently has a dedicated music transport, not generic image/video media transports.
     */
    fun canSelect(providerType: String, model: String): Boolean {
        if (canChat(providerType, model)) return true
        if (isMusicGeneration(providerType, model))
            return providerType == ProviderCatalog.KILO_GATEWAY_ID || providerType == GEMINI_PROXY_PROVIDER
        if (isImageGeneration(providerType, model))
            return canExecuteImageGeneration(providerType, model)
        return false
    }

    fun isFreeModel(providerType: String, model: String): Boolean = when {
        // Legacy alias semantics remain intact until ProviderStateConsolidator rewrites the row.
        providerType == ProviderCatalog.CLINE_FREE_ID -> true
        providerType == NVIDIA_PROXY_PROVIDER || providerType == AccountProviderCatalog.NVIDIA_ID ->
            NvidiaNimClient.isKnownFreeEndpoint(model)
        else -> AccountProviderCatalog.isFreeModel(providerType, model)
    }

    fun accessKind(providerType: String, model: String): ModelAccessKind = when {
        isFreeModel(providerType, model) -> ModelAccessKind.FREE
        // Keep the historical alias correct during the migration window.
        providerType == ProviderCatalog.CLINE_PASS_ID -> ModelAccessKind.SUBSCRIPTION
        providerType == ProviderCatalog.CLINE_ID &&
            ConnectionIdentity.clineAccessGroup(model) == ClineAccessGroup.PASS -> ModelAccessKind.SUBSCRIPTION
        ConnectionIdentity.isClineProvider(providerType) -> ModelAccessKind.PAID
        ConnectionIdentity.isSubscriptionProvider(providerType) -> ModelAccessKind.SUBSCRIPTION
        providerType in setOf(AccountProviderCatalog.POLZA_ID, AccountProviderCatalog.VSEGPT_ID) ->
            ModelAccessKind.PAID
        else -> ModelAccessKind.UNKNOWN
    }

    fun accessBadge(providerType: String, model: String, providerName: String): String? {
        if (providerType == ProviderCatalog.KILO_GATEWAY_ID && KiloGatewayRepository.isLyriaModel(model))
            return "Kilo credits · FREE не подтверждён"
        return when (accessKind(providerType, model)) {
            ModelAccessKind.FREE -> when {
                providerType == NVIDIA_PROXY_PROVIDER || providerType == AccountProviderCatalog.NVIDIA_ID ->
                    "FREE endpoint · доступ зависит от NVIDIA аккаунта"
                providerType == ProviderCatalog.CLINE_FREE_ID -> "FREE · лимит Cline аккаунта"
                else -> "FREE · бесплатно у $providerName"
            }
            ModelAccessKind.SUBSCRIPTION -> "В подписке · $providerName"
            ModelAccessKind.PAID -> "Платная · $providerName"
            ModelAccessKind.UNKNOWN -> null
        }
    }

    fun sourceLabel(providerType: String): String =
        if (providerType == NVIDIA_PROXY_PROVIDER) "NVIDIA NIM"
        else AccountProviderCatalog.sourceLabel(providerType)

    fun accessHint(providerType: String): String? = AccountProviderCatalog.accessHint(providerType)

    /** Human-facing names; original model IDs must be preserved unchanged for API requests. */
    fun displayName(providerType: String, id: String): String {
        if (providerType == ProviderCatalog.KILO_GATEWAY_ID) {
            return when (KiloGatewayRepository.canonicalLyriaModel(id)) {
                KiloGatewayRepository.LYRIA_CLIP -> "Google: Lyria 3 Clip Preview"
                KiloGatewayRepository.LYRIA_PRO -> "Google: Lyria 3 Pro Preview"
                else -> id
            }
        }
        if (providerType == "yandex" && isImageGeneration(providerType, id)) return "Alice AI ART 3.0"
        if (GeminiModelPresentation.isGemini(id)) return GeminiModelPresentation.displayName(id)
        if (providerType != "yandex" || !id.contains("://")) return id
        val tail = id.substringAfter("://").substringAfter('/', "").substringBefore('@')
        val name = tail.substringBeforeLast('/', tail).substringAfterLast('/')
        val revision = tail.substringAfterLast('/', "")
        return if (revision.isNotBlank() && revision != name) "$name · $revision" else name
    }

    /** null means unknown, not unsupported. */
    fun knownVisionSupport(providerType: String, model: String): Boolean? {
        val normalized = model.removePrefix("models/").lowercase()
        if (normalized == GeminiModelPresentation.ARGON_ID) return true
        if (providerType != "yandex") return null
        val family = model.substringAfter("://", model).substringAfter('/')
            .substringBefore('/').lowercase()
        return when {
            family.startsWith("qwen3.6-35b-a3b") -> true
            family.startsWith("deepseek-v4-flash") -> false
            family.startsWith("text-embeddings") || family.startsWith("speech-realtime") -> false
            else -> null
        }
    }

    fun primaryCapability(providerType: String, model: String): ModelPrimaryCapability = when {
        isMusicGeneration(providerType, model) -> ModelPrimaryCapability.MUSIC_GENERATION
        isVideoGeneration(providerType, model) -> ModelPrimaryCapability.VIDEO_GENERATION
        isImageGeneration(providerType, model) -> ModelPrimaryCapability.IMAGE_GENERATION
        isEmbeddingModel(model) -> ModelPrimaryCapability.EMBEDDING
        isRerankModel(model) -> ModelPrimaryCapability.RERANK
        isSpeechToTextModel(model) -> ModelPrimaryCapability.SPEECH_TO_TEXT
        isTextToSpeechModel(model) -> ModelPrimaryCapability.TEXT_TO_SPEECH
        isSafetyModel(model) -> ModelPrimaryCapability.SAFETY
        canChat(providerType, model) && knownVisionSupport(providerType, model) == true ->
            ModelPrimaryCapability.VISION_CHAT
        canChat(providerType, model) -> ModelPrimaryCapability.CHAT
        else -> ModelPrimaryCapability.UNKNOWN
    }

    fun capabilityLabel(providerType: String, model: String): String {
        if (providerType == GEMINI_PROXY_PROVIDER &&
            SubscriptionProxyGeminiSupport.isConversationalImageModel(model)
        ) return "Чат + изображения"
        return when (primaryCapability(providerType, model)) {
            ModelPrimaryCapability.CHAT -> "Чат"
            ModelPrimaryCapability.VISION_CHAT -> "Анализ изображений"
            ModelPrimaryCapability.IMAGE_GENERATION -> "Генерация изображений"
            ModelPrimaryCapability.MUSIC_GENERATION -> "Генерация музыки"
            ModelPrimaryCapability.VIDEO_GENERATION -> "Генерация видео"
            ModelPrimaryCapability.EMBEDDING -> "Эмбеддинги"
            ModelPrimaryCapability.RERANK -> "Реранкинг"
            ModelPrimaryCapability.SPEECH_TO_TEXT -> "Распознавание речи"
            ModelPrimaryCapability.TEXT_TO_SPEECH -> "Озвучка"
            ModelPrimaryCapability.SAFETY -> "Safety / moderation"
            ModelPrimaryCapability.UNKNOWN -> "Возможности не подтверждены"
        }
    }

    fun visionHint(providerType: String, model: String): String = "· ${capabilityLabel(providerType, model)}"

    fun selectableModels(providerType: String, models: List<String>): List<String> {
        val withImage = if (providerType == "yandex") models + "aliceai-image-art-3.0" else models
        return withImage.filter { canSelect(providerType, it) }.distinct()
    }

    fun chatModels(providerType: String, models: List<String>): List<String> =
        models.filter { canChat(providerType, it) }.distinct()

    fun defaultChatModel(providerType: String, models: List<String>): String? {
        val chat = chatModels(providerType, models)
        return chat.firstOrNull { it.equals("GigaChat-2", ignoreCase = true) } ?: chat.firstOrNull()
    }
}
