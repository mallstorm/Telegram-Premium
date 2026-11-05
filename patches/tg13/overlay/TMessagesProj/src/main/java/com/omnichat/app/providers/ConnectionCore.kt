package com.omnichat.app.providers

/**
 * Canonical semantic model for an OmniChat connection.
 *
 * Provider/vendor, authentication, transport and model catalog are deliberately separate concepts.
 * Existing repositories can migrate onto this core incrementally without changing credentials or
 * wire protocols in one risky step.
 */
enum class ConnectionChannel {
    DIRECT_API,
    ACCOUNT,
    SUBSCRIPTION_PROXY,
    WEB_SESSION,
    LOCAL
}

enum class ConnectionAuthKind {
    API_KEY,
    OAUTH_CALLBACK,
    OAUTH_DEVICE,
    OAUTH_LOOPBACK,
    ACCOUNT_OAUTH,
    BROWSER_SESSION,
    NONE
}

enum class CapabilityAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
    TEMPORARILY_UNAVAILABLE
}

enum class ClineAccessGroup { BILLING, PASS, FREE }

data class ConnectionCapabilities(
    val chat: CapabilityAvailability = CapabilityAvailability.UNKNOWN,
    val nativeTools: CapabilityAvailability = CapabilityAvailability.UNKNOWN,
    val imageGeneration: CapabilityAvailability = CapabilityAvailability.UNKNOWN,
    val videoGeneration: CapabilityAvailability = CapabilityAvailability.UNKNOWN,
    val musicGeneration: CapabilityAvailability = CapabilityAvailability.UNKNOWN,
    val webSearch: CapabilityAvailability = CapabilityAvailability.UNKNOWN,
    val deepResearch: CapabilityAvailability = CapabilityAvailability.UNKNOWN
) {
    val tools: CapabilityAvailability get() = nativeTools
}

data class ConnectionModelCatalogState(
    val models: List<String> = emptyList(),
    val origin: String = "cache",
    val verifiedAtMs: Long = 0L,
    val lastError: String = ""
) {
    val normalizedModels: List<String> get() = models.filter(String::isNotBlank).distinct()
    val isLive: Boolean get() = origin == "live" && verifiedAtMs > 0L
}

data class ConnectionDescriptor(
    val providerType: String,
    val vendorId: String,
    val channel: ConnectionChannel,
    val authKind: ConnectionAuthKind,
    val accountScope: String = "",
    val capabilities: ConnectionCapabilities = ConnectionCapabilities(),
    val catalog: ConnectionModelCatalogState = ConnectionModelCatalogState()
) {
    val connectionKey: String get() = ConnectionIdentity.connectionKey(providerType, accountScope)
}

/** Central provider identity policy, independent from display names and URLs. */
object ConnectionIdentity {
    const val SUBSCRIPTION_PROXY_PREFIX = "subscription-proxy-"

    private const val PROXY_CHATGPT = "subscription-proxy-chatgpt"
    private const val PROXY_CHATGPT_JSON = "subscription-proxy-chatgpt-json"
    private const val PROXY_CODEX = "subscription-proxy-codex"
    private const val PROXY_CLAUDE = "subscription-proxy-claude"
    private const val PROXY_GROK = "subscription-proxy-grok"
    private const val PROXY_GEMINI = "subscription-proxy-gemini"
    private const val PROXY_NVIDIA = "subscription-proxy-nvidia"
    private const val PROXY_EXTERNAL = "subscription-proxy-external"

    val accountManagedProviderIds: Set<String>
        get() = AccountProviderCatalog.specs.mapTo(linkedSetOf()) { it.id }

    fun canonicalVendorId(providerType: String): String = when (providerType) {
        ProviderCatalog.CLINE_ID,
        ProviderCatalog.CLINE_PASS_ID,
        ProviderCatalog.CLINE_FREE_ID -> "cline"

        ProviderCatalog.OPENAI_CODEX_ID,
        PROXY_CHATGPT,
        PROXY_CHATGPT_JSON,
        PROXY_CODEX -> "openai"

        "anthropic", PROXY_CLAUDE -> "anthropic"
        "xai", PROXY_GROK -> "xai"
        "gemini", ProviderCatalog.GEMINI_WEB_ID, PROXY_GEMINI -> "gemini"
        AccountProviderCatalog.NVIDIA_ID, PROXY_NVIDIA -> "nvidia"
        ProviderCatalog.OPENROUTER_ID -> "openrouter"
        ProviderCatalog.KILO_GATEWAY_ID -> "kilo"
        AccountProviderCatalog.KIMI_CODE_ID, "kimi" -> "kimi"
        else -> providerType.removePrefix(SUBSCRIPTION_PROXY_PREFIX)
    }

    /** Canonical ProviderEntity type used when an old bridge/alias row is migrated. */
    fun canonicalProviderType(providerType: String): String = when (providerType) {
        ProviderCatalog.CLINE_PASS_ID,
        ProviderCatalog.CLINE_FREE_ID -> ProviderCatalog.CLINE_ID
        PROXY_CODEX -> ProviderCatalog.OPENAI_CODEX_ID
        PROXY_NVIDIA -> AccountProviderCatalog.NVIDIA_ID
        else -> providerType
    }

    fun isClineProvider(providerType: String): Boolean = providerType in setOf(
        ProviderCatalog.CLINE_ID,
        ProviderCatalog.CLINE_PASS_ID,
        ProviderCatalog.CLINE_FREE_ID
    )

    fun clineAccessGroup(model: String): ClineAccessGroup {
        val id = model.lowercase()
        return when {
            id.startsWith("cline-free/") || id.endsWith(":free") ||
                id.contains("/free/") || id == "deepseek/deepseek-v4-flash" -> ClineAccessGroup.FREE
            id.startsWith("cline-pass/") -> ClineAccessGroup.PASS
            else -> ClineAccessGroup.BILLING
        }
    }

    fun channel(providerType: String): ConnectionChannel = when {
        providerType == ProviderCatalog.GEMINI_WEB_ID -> ConnectionChannel.WEB_SESSION
        providerType.startsWith(SUBSCRIPTION_PROXY_PREFIX) -> ConnectionChannel.SUBSCRIPTION_PROXY
        providerType == "ollama" -> ConnectionChannel.LOCAL
        providerType in accountManagedProviderIds ||
            isClineProvider(providerType) ||
            providerType in setOf(
                ProviderCatalog.OPENAI_CODEX_ID,
                ProviderCatalog.OPENROUTER_ID,
                ProviderCatalog.KILO_GATEWAY_ID
            ) -> ConnectionChannel.ACCOUNT
        else -> ConnectionChannel.DIRECT_API
    }

    fun authKind(providerType: String): ConnectionAuthKind {
        if (providerType == ProviderCatalog.GEMINI_WEB_ID) return ConnectionAuthKind.BROWSER_SESSION
        if (providerType.startsWith(SUBSCRIPTION_PROXY_PREFIX)) return ConnectionAuthKind.ACCOUNT_OAUTH
        if (isClineProvider(providerType) || providerType in setOf(
                ProviderCatalog.OPENAI_CODEX_ID,
                ProviderCatalog.OPENROUTER_ID,
                ProviderCatalog.KILO_GATEWAY_ID
            )
        ) return ConnectionAuthKind.ACCOUNT_OAUTH

        return when (AccountProviderCatalog.byId(providerType)?.authMode) {
            AccountAuthMode.API_KEY -> ConnectionAuthKind.API_KEY
            AccountAuthMode.OAUTH_CALLBACK -> ConnectionAuthKind.OAUTH_CALLBACK
            AccountAuthMode.OAUTH_DEVICE -> ConnectionAuthKind.OAUTH_DEVICE
            AccountAuthMode.OAUTH_LOOPBACK -> ConnectionAuthKind.OAUTH_LOOPBACK
            null -> if (providerType == "ollama") ConnectionAuthKind.NONE else ConnectionAuthKind.API_KEY
        }
    }

    fun redundancyGroup(providerType: String): String = when (providerType) {
        ProviderCatalog.CLINE_ID,
        ProviderCatalog.CLINE_PASS_ID,
        ProviderCatalog.CLINE_FREE_ID -> "cline-account"

        ProviderCatalog.OPENAI_CODEX_ID,
        PROXY_CODEX -> "openai-codex-account"

        AccountProviderCatalog.NVIDIA_ID,
        PROXY_NVIDIA -> "nvidia-api-key"

        else -> providerType
    }

    fun connectionKey(providerType: String, accountScope: String = ""): String {
        val scope = accountScope.trim()
        val core = "${canonicalVendorId(providerType)}:${redundancyGroup(providerType)}"
        return if (scope.isBlank()) core else "$core:$scope"
    }

    fun isSubscriptionProvider(providerType: String): Boolean = when {
        providerType == ProviderCatalog.CLINE_PASS_ID -> true
        providerType == ProviderCatalog.OPENAI_CODEX_ID -> true
        providerType == AccountProviderCatalog.OPENCODE_GO_ID -> true
        providerType == AccountProviderCatalog.KIMI_CODE_ID -> true
        providerType.startsWith(SUBSCRIPTION_PROXY_PREFIX) &&
            providerType != PROXY_NVIDIA && providerType != PROXY_EXTERNAL -> true
        else -> false
    }

    fun belongsInGenericApiSetup(providerType: String): Boolean =
        providerType !in accountManagedProviderIds
}
