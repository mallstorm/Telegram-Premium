package com.omnichat.app.network

/**
 * Central execution policy for consumer-web actions.
 *
 * Ordinary TEXT_CHAT deliberately uses the authenticated consumer composer. The user's device
 * smoke test proved that a browser-confirmed session can still leave the undocumented native
 * StreamGenerate transport silent, while the consumer UI itself is usable. Structured media,
 * files and research keep their protocol-specific native executors; their native path is guarded
 * by a JS-bridge preflight before any request is submitted.
 */
object GeminiWebActionRegistry {
    private val assistedConsumerFeatures = setOf(
        GeminiWebFeature.TEXT_CHAT
    )

    private val verifiedNativeFeatures = setOf(
        GeminiWebFeature.IMAGE_GENERATE,
        GeminiWebFeature.IMAGE_EDIT,
        GeminiWebFeature.FILE_ANALYSIS,
        GeminiWebFeature.MULTIMODAL_CHAT,
        GeminiWebFeature.MUSIC_GENERATE,
        GeminiWebFeature.VIDEO_GENERATE,
        GeminiWebFeature.WEB_SEARCH,
        GeminiWebFeature.DEEP_RESEARCH
    )

    fun executionMode(
        capabilities: GeminiWebCapabilities?,
        feature: GeminiWebFeature
    ): GeminiExecutionMode {
        if (feature in assistedConsumerFeatures) return GeminiExecutionMode.ASSISTED_WEBVIEW
        if (feature in verifiedNativeFeatures) return GeminiExecutionMode.NATIVE_BRIDGE

        return capabilities?.availableActions
            ?.firstOrNull { it.feature == feature }
            ?.executionMode
            ?: GeminiExecutionMode.UNSUPPORTED
    }

    fun action(
        capabilities: GeminiWebCapabilities?,
        feature: GeminiWebFeature
    ): GeminiWebAction? = capabilities?.availableActions?.firstOrNull { it.feature == feature }
}
