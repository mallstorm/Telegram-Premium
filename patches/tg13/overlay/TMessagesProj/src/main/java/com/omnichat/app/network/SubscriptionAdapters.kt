package com.omnichat.app.network

import com.omnichat.app.providers.CapabilityAvailability
import com.omnichat.app.providers.ConnectionCapabilities

/** Legacy Subscription Proxy state kept source-compatible while the shared connection core migrates. */
enum class FeatureAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

private fun FeatureAvailability.toConnectionAvailability(): CapabilityAvailability = when (this) {
    FeatureAvailability.AVAILABLE -> CapabilityAvailability.AVAILABLE
    FeatureAvailability.UNAVAILABLE -> CapabilityAvailability.UNAVAILABLE
    FeatureAvailability.UNKNOWN -> CapabilityAvailability.UNKNOWN
}

/**
 * Common contract for the remaining built-in Subscription Proxy transports.
 *
 * Canonical OpenAI Codex and NVIDIA NIM connections deliberately do not implement this contract:
 * they are owned by the normal account connection layer. Historical proxy credentials for those
 * providers are read only by ProviderStateConsolidator during migration.
 */
data class SubscriptionCapabilities(
    val chat: FeatureAvailability = FeatureAvailability.AVAILABLE,
    val nativeTools: FeatureAvailability = FeatureAvailability.AVAILABLE,
    val imageGeneration: FeatureAvailability = FeatureAvailability.UNAVAILABLE,
    val videoGeneration: FeatureAvailability = FeatureAvailability.UNAVAILABLE,
    val musicGeneration: FeatureAvailability = FeatureAvailability.UNAVAILABLE
) {
    fun toConnectionCapabilities(): ConnectionCapabilities = ConnectionCapabilities(
        chat = chat.toConnectionAvailability(),
        nativeTools = nativeTools.toConnectionAvailability(),
        imageGeneration = imageGeneration.toConnectionAvailability(),
        videoGeneration = videoGeneration.toConnectionAvailability(),
        musicGeneration = musicGeneration.toConnectionAvailability()
    )
}

data class SubscriptionAccountState(
    val source: String,
    val connected: Boolean,
    val health: String,
    val identity: String,
    val plan: String,
    val endpoint: String,
    val models: List<String>,
    val modelOrigin: String,
    val lastVerifiedAtMs: Long,
    val lastError: String,
    val capabilities: SubscriptionCapabilities
)

interface SubscriptionAdapter {
    val source: String
    val protocol: String

    fun connected(): Boolean
    fun identity(): String
    fun detail(): String
    fun plan(): String = ""
    fun baseUrl(): String
    fun capabilities(): SubscriptionCapabilities = SubscriptionCapabilities()

    suspend fun accessToken(): String
    suspend fun accountScope(): String = ""
    suspend fun forceRefresh()
    suspend fun discoverModels(): List<String>
}

class ChatGptPlanSubscriptionAdapter(
    private val store: SubscriptionProxyStore,
    private val client: ChatGptPlanClient,
    private val experimental: Boolean
) : SubscriptionAdapter {
    override val source: String = if (experimental)
        SubscriptionProxyStore.ACCOUNT_CHATGPT_JSON
    else SubscriptionProxyStore.ACCOUNT_CHATGPT
    override val protocol = if (experimental) ApiGateway.CHATGPT_WEB else ApiGateway.RESPONSES

    private fun read(): ChatGptPlanClient.Credentials? =
        if (experimental) store.readChatGptJson() else store.readChatGpt()

    private fun save(credentials: ChatGptPlanClient.Credentials) {
        if (experimental) store.saveChatGptJson(credentials) else store.saveChatGpt(credentials)
    }

    private suspend fun current(): ChatGptPlanClient.Credentials {
        val credentials = read() ?: throw ApiFailure(
            if (experimental) "ChatGPT JSON Session не подключён"
            else "ChatGPT Subscription не подключён"
        )
        if (!credentials.expiresSoon()) return credentials
        if (experimental) {
            if (credentials.expired()) {
                throw ApiFailure("ChatGPT Web JSON session истекла — импортируйте свежую /api/auth/session")
            }
            return credentials
        }
        if (credentials.refreshToken.isNotBlank() && credentials.clientId.startsWith("oaiapp_")) {
            return client.refresh(credentials).also(::save)
        }
        if (credentials.expired()) {
            throw ApiFailure("ChatGPT session истекла — войдите снова")
        }
        return credentials
    }

    override fun connected(): Boolean = read()?.accessToken?.isNotBlank() == true
    override fun identity(): String = read()?.let { it.email.ifBlank { it.subject } }.orEmpty()
    override fun detail(): String = read()?.let {
        if (!experimental) {
            "Sign in with ChatGPT · Responses API · plan usage"
        } else {
            "Experimental · ChatGPT Web Session · chatgpt.com"
        }
    }.orEmpty()
    override fun plan(): String = read()?.plan.orEmpty()
    override fun baseUrl(): String =
        if (experimental) ChatGptPlanClient.WEB_BASE_URL else ChatGptPlanClient.API_BASE_URL

    override fun capabilities(): SubscriptionCapabilities {
        val visual = if (experimental) FeatureAvailability.UNAVAILABLE
        else FeatureAvailability.AVAILABLE
        return SubscriptionCapabilities(
            chat = FeatureAvailability.AVAILABLE,
            nativeTools = FeatureAvailability.AVAILABLE,
            imageGeneration = visual,
            videoGeneration = FeatureAvailability.UNAVAILABLE,
            musicGeneration = FeatureAvailability.UNAVAILABLE
        )
    }

    override suspend fun accessToken(): String = current().accessToken
    override suspend fun accountScope(): String = ""

    override suspend fun forceRefresh() {
        val credentials = read() ?: throw ApiFailure("ChatGPT account не подключён")
        if (!experimental &&
            credentials.refreshToken.isNotBlank() &&
            credentials.clientId.startsWith("oaiapp_")
        ) {
            save(client.refresh(credentials))
        } else {
            client.modelCatalog(current())
        }
    }

    override suspend fun discoverModels(): List<String> = client.modelCatalog(current())
}

class GrokSubscriptionAdapter(
    private val store: SubscriptionProxyStore,
    private val client: SubscriptionProxyGrokClient
) : SubscriptionAdapter {
    override val source = SubscriptionProxyStore.ACCOUNT_GROK
    override val protocol = ApiGateway.RESPONSES

    private suspend fun current(): SubscriptionProxyStore.GrokCredentials {
        val c = store.readGrok() ?: throw ApiFailure("Subscription Proxy: Grok не подключён")
        if (!c.expiresSoon()) return c
        return client.refresh(c).also(store::saveGrok)
    }

    override fun connected() = store.readGrok() != null
    override fun identity() = store.readGrok()?.email.orEmpty()
    override fun detail() = if (connected()) "OAuth Grok Build · Grok Imagine" else ""
    override fun plan() = store.readGrokUsage()?.plan.orEmpty()
    override fun baseUrl() = SubscriptionProxyGrokClient.GROK_BUILD_BASE
    override fun capabilities(): SubscriptionCapabilities {
        val restricted = store.readGrokUsage()?.plan
            ?.let(GrokSubscriptionSupport::isImagineRestricted)
        val media = when (restricted) {
            true -> FeatureAvailability.UNAVAILABLE
            false -> FeatureAvailability.AVAILABLE
            null -> FeatureAvailability.UNKNOWN
        }
        return SubscriptionCapabilities(
            chat = FeatureAvailability.AVAILABLE,
            nativeTools = FeatureAvailability.AVAILABLE,
            imageGeneration = media,
            videoGeneration = media
        )
    }
    override suspend fun accessToken() = current().accessToken
    override suspend fun forceRefresh() {
        val c = store.readGrok() ?: throw ApiFailure("Grok не подключён")
        store.saveGrok(client.refresh(c))
    }
    override suspend fun discoverModels() = client.modelCatalog(current())
}

class ClaudeSubscriptionAdapter(
    private val store: SubscriptionProxyStore,
    private val client: SubscriptionProxyClaudeClient
) : SubscriptionAdapter {
    override val source = SubscriptionProxyStore.ACCOUNT_CLAUDE
    override val protocol = ApiGateway.ANTHROPIC

    private suspend fun current(): SubscriptionProxyStore.ClaudeCredentials {
        val c = store.readClaude() ?: throw ApiFailure("Subscription Proxy: Claude не подключён")
        if (!c.expiresSoon()) return c
        return client.refresh(c).also(store::saveClaude)
    }

    override fun connected() = store.readClaude() != null
    override fun identity() = store.readClaude()?.email.orEmpty()
    override fun detail(): String {
        val c = store.readClaude() ?: return ""
        return c.organizationName.takeIf { it.isNotBlank() }?.let { "Claude Code · $it" }
            ?: "Claude Code OAuth"
    }
    override fun baseUrl() = SubscriptionProxyClaudeClient.API_BASE
    override suspend fun accessToken() = current().accessToken
    override suspend fun forceRefresh() {
        val c = store.readClaude() ?: throw ApiFailure("Claude не подключён")
        store.saveClaude(client.refresh(c))
    }
    override suspend fun discoverModels() = client.modelCatalog(current())
}

class GeminiSubscriptionAdapter(
    private val store: SubscriptionProxyStore,
    private val client: SubscriptionProxyGeminiClient
) : SubscriptionAdapter {
    override val source = SubscriptionProxyStore.ACCOUNT_GEMINI
    override val protocol = ApiGateway.GEMINI

    private suspend fun current(): SubscriptionProxyStore.GeminiCredentials {
        val c = store.readGemini() ?: throw ApiFailure("Subscription Proxy: Gemini не подключён")
        if (!c.expiresSoon()) return c
        return client.refresh(c).also(store::saveGemini)
    }

    override fun connected() = store.readGemini() != null
    override fun identity() = store.readGemini()?.email.orEmpty()
    override fun detail(): String {
        val c = store.readGemini() ?: return ""
        val endpoint = c.apiBaseUrl.substringAfter("https://").substringBefore('/')
        return c.projectId.takeIf { it.isNotBlank() }?.let {
            "Google Cloud Code · project $it" + if (endpoint.isNotBlank()) " · $endpoint" else ""
        } ?: "Google OAuth"
    }
    override fun baseUrl(): String =
        store.readGemini()?.apiBaseUrl?.takeIf { it.isNotBlank() } ?: SubscriptionProxyGeminiClient.DAILY_API

    override fun capabilities(): SubscriptionCapabilities {
        val models = store.models(source)
        return SubscriptionCapabilities(
            chat = FeatureAvailability.AVAILABLE,
            nativeTools = FeatureAvailability.AVAILABLE,
            imageGeneration = if (models.any(SubscriptionProxyGeminiSupport::isImageModel))
                FeatureAvailability.AVAILABLE else FeatureAvailability.UNKNOWN,
            // Veo IDs can appear in the catalog before the OAuth task transport exists.
            videoGeneration = FeatureAvailability.UNAVAILABLE,
            musicGeneration = if (models.any(SubscriptionProxyGeminiSupport::isMusicModel))
                FeatureAvailability.AVAILABLE else FeatureAvailability.UNKNOWN
        )
    }

    override suspend fun accessToken() = current().accessToken
    override suspend fun accountScope() = current().projectId
    override suspend fun forceRefresh() {
        val c = store.readGemini() ?: throw ApiFailure("Gemini не подключён")
        store.saveGemini(client.refresh(c))
    }
    override suspend fun discoverModels(): List<String> {
        val c = current()
        val catalog = client.modelCatalogWithEndpoint(c)
        if (c.apiBaseUrl != catalog.apiBaseUrl) store.saveGemini(c.copy(apiBaseUrl = catalog.apiBaseUrl))
        return catalog.models
    }
}
