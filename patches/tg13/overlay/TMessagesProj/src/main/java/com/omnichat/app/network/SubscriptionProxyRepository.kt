package com.omnichat.app.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Runtime facade for the isolated Subscription Proxy contour.
 *
 * Subscription Proxy owns only transports that do not already have a canonical OmniChat account
 * connection. OpenAI Codex and NVIDIA NIM are intentionally excluded: their historical proxy state
 * is migration input only and ProviderStateConsolidator moves it into the canonical connection.
 * External CLIProxyAPI compatibility remains isolated behind its own endpoint/bearer.
 */
class SubscriptionProxyRepository(
    context: Context,
    private val store: SubscriptionProxyStore,
    @Suppress("UNUSED_PARAMETER") codexClient: OpenAiCodexAccountClient,
    private val grokClient: SubscriptionProxyGrokClient,
    private val claudeClient: SubscriptionProxyClaudeClient,
    private val geminiClient: SubscriptionProxyGeminiClient,
    @Suppress("UNUSED_PARAMETER") nvidiaClient: NvidiaNimClient = NvidiaNimClient(),
    private val chatGptClient: ChatGptPlanClient = ChatGptPlanClient()
) {
    data class ModelRef(
        val source: String,
        val model: String,
        val label: String
    )

    data class AccountSnapshot(
        val source: String,
        val connected: Boolean,
        val identity: String,
        val detail: String,
        val models: List<String>,
        val plan: String = "",
        val usedPercent: Double? = null,
        val remainingPercent: Double? = null,
        val periodLabel: String = "",
        val resetsAtMs: Long? = null,
        val health: String = "disconnected",
        val modelOrigin: String = SubscriptionProxyStore.ORIGIN_CACHE,
        val lastVerifiedAtMs: Long = 0L,
        val lastError: String = ""
    )

    private val adapters: Map<String, SubscriptionAdapter> = listOf(
        ChatGptPlanSubscriptionAdapter(store, chatGptClient, experimental = false),
        ChatGptPlanSubscriptionAdapter(store, chatGptClient, experimental = true),
        // provider-state-consolidation: Codex uses the canonical account repository
        GrokSubscriptionAdapter(store, grokClient),
        ClaudeSubscriptionAdapter(store, claudeClient),
        GeminiSubscriptionAdapter(store, geminiClient)
        // provider-state-consolidation: NVIDIA uses the canonical account connection
    ).associateBy { it.source }

    private val grokRefreshMutex = Mutex()
    private val claudeRefreshMutex = Mutex()
    private val geminiRefreshMutex = Mutex()

    private val authExecutor = AuthenticatedRequestExecutor(
        load = { source ->
            SubscriptionTransportAuth(
                accessToken = accessTokenForSource(source),
                baseUrl = baseUrlForSource(source),
                accountScope = accountScopeForSource(source)
            )
        },
        refresh = { source -> forceRefreshForSource(source) }
    )

    private val client = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Legacy compatibility only. New Codex code must use OpenAiCodexAccountRepository. */
    @Deprecated("Codex is owned by OpenAiCodexAccountRepository")
    fun codexCredentials(): OpenAiCodexAccountClient.Credentials? = null

    fun chatGptCredentials(): ChatGptPlanClient.Credentials? = store.readChatGpt()
    fun chatGptJsonCredentials(): ChatGptPlanClient.Credentials? = store.readChatGptJson()

    fun beginChatGptLogin(): ChatGptPlanClient.Login =
        chatGptClient.beginLogin(store.chatGptHostId(), store.readChatGpt())

    fun cancelChatGptLogin(login: ChatGptPlanClient.Login) = chatGptClient.cancel(login)

    suspend fun completeChatGptLogin(login: ChatGptPlanClient.Login): ChatGptPlanClient.Credentials {
        val credentials = chatGptClient.awaitCredentials(login)
        store.saveChatGpt(credentials)
        runCatching { refreshModels(SubscriptionProxyStore.ACCOUNT_CHATGPT) }
        return credentials
    }

    suspend fun importChatGptJson(raw: String): ChatGptPlanClient.Credentials {
        val credentials = chatGptClient.importSessionJson(raw)
        if (credentials.expired()) throw ApiFailure("Импортированная ChatGPT session уже истекла")
        val models = chatGptClient.modelCatalog(credentials)
        if (models.isEmpty()) throw ApiFailure("ChatGPT JSON session не вернула доступные модели")
        store.saveChatGptJson(credentials)
        store.saveModels(
            SubscriptionProxyStore.ACCOUNT_CHATGPT_JSON,
            models,
            SubscriptionProxyStore.ORIGIN_LIVE
        )
        return credentials
    }

    fun grokCredentials(): SubscriptionProxyStore.GrokCredentials? = store.readGrok()
    fun claudeCredentials(): SubscriptionProxyStore.ClaudeCredentials? = store.readClaude()
    fun geminiCredentials(): SubscriptionProxyStore.GeminiCredentials? = store.readGemini()

    /** Legacy compatibility only. Canonical Codex refresh is performed by its account repository. */
    @Deprecated("Codex is owned by OpenAiCodexAccountRepository")
    suspend fun currentCodex(): OpenAiCodexAccountClient.Credentials? = null

    suspend fun currentGrok(): SubscriptionProxyStore.GrokCredentials? {
        val current = store.readGrok() ?: return null
        if (!current.expiresSoon()) return current
        return grokRefreshMutex.withLock {
            val latest = store.readGrok() ?: return@withLock null
            if (!latest.expiresSoon()) latest else grokClient.refresh(latest).also(store::saveGrok)
        }
    }

    suspend fun currentClaude(): SubscriptionProxyStore.ClaudeCredentials? {
        val current = store.readClaude() ?: return null
        if (!current.expiresSoon()) return current
        return claudeRefreshMutex.withLock {
            val latest = store.readClaude() ?: return@withLock null
            if (!latest.expiresSoon()) latest else claudeClient.refresh(latest).also(store::saveClaude)
        }
    }

    suspend fun saveClaudeLogin(credentials: SubscriptionProxyStore.ClaudeCredentials) {
        store.saveClaude(credentials)
        runCatching { refreshModels(SubscriptionProxyStore.ACCOUNT_CLAUDE) }
    }

    suspend fun currentGemini(): SubscriptionProxyStore.GeminiCredentials? {
        val current = store.readGemini() ?: return null
        if (!current.expiresSoon()) return current
        return geminiRefreshMutex.withLock {
            val latest = store.readGemini() ?: return@withLock null
            if (!latest.expiresSoon()) latest else geminiClient.refresh(latest).also(store::saveGemini)
        }
    }

    suspend fun refreshGeminiNow(): SubscriptionProxyStore.GeminiCredentials? {
        return geminiRefreshMutex.withLock {
            val current = store.readGemini() ?: return@withLock null
            geminiClient.refresh(current).also(store::saveGemini)
        }
    }

    suspend fun saveGeminiLogin(credentials: SubscriptionProxyStore.GeminiCredentials) {
        store.saveGemini(credentials)
        runCatching { refreshModels(SubscriptionProxyStore.ACCOUNT_GEMINI) }
    }

    @Deprecated("NVIDIA NIM is owned by the canonical account connection")
    suspend fun connectNvidia(@Suppress("UNUSED_PARAMETER") apiKey: String): List<String> {
        throw ApiFailure("NVIDIA NIM теперь подключается один раз через «Аккаунты и подписки»")
    }

    @Deprecated("Codex is owned by OpenAiCodexAccountRepository")
    suspend fun completeCodexPoll(
        @Suppress("UNUSED_PARAMETER") login: OpenAiCodexAccountClient.DeviceLogin
    ): OpenAiCodexAccountClient.Credentials? {
        throw ApiFailure("OpenAI Codex теперь подключается один раз через «Аккаунты и подписки»")
    }

    suspend fun completeGrokPoll(
        login: SubscriptionProxyGrokClient.DeviceLogin
    ): SubscriptionProxyStore.GrokCredentials? {
        val credentials = grokClient.pollDeviceOnce(login) ?: return null
        store.saveGrok(credentials)
        runCatching { refreshModels(SubscriptionProxyStore.ACCOUNT_GROK) }
        runCatching { refreshGrokUsage() }
        return credentials
    }

    suspend fun refreshGrokUsage(): GrokSubscriptionUsage {
        val credentials = currentGrok()
            ?: throw ApiFailure("Subscription Proxy: войдите в xAI/Grok")
        return grokClient.subscriptionUsage(credentials).also(store::saveGrokUsage)
    }

    suspend fun refreshModels(source: String): List<String> {
        requireRuntimeSource(source)
        return try {
            val models = if (source == ACCOUNT_EXTERNAL) {
                externalModels()
            } else {
                val adapter = adapters[source]
                    ?: throw ApiFailure("Subscription Proxy: неизвестный источник $source")
                if (!adapter.connected()) throw ApiFailure("Subscription Proxy: источник $source не подключён")
                adapter.discoverModels()
            }.filter(String::isNotBlank).distinct()

            if (models.isEmpty()) throw ApiFailure("Subscription Proxy: источник $source не вернул доступные модели")
            store.saveModels(source, models, SubscriptionProxyStore.ORIGIN_LIVE)
            models
        } catch (error: Exception) {
            store.markCatalogFailure(source, error.message ?: "Не удалось обновить каталог")
            throw error
        }
    }

    suspend fun refreshConnectedModels(): List<ModelRef> {
        val out = mutableListOf<ModelRef>()
        val labels = mapOf(
            SubscriptionProxyStore.ACCOUNT_CHATGPT to "ChatGPT Subscription",
            SubscriptionProxyStore.ACCOUNT_CHATGPT_JSON to "ChatGPT JSON · Experimental",
            SubscriptionProxyStore.ACCOUNT_GROK to "xAI Grok",
            SubscriptionProxyStore.ACCOUNT_CLAUDE to "Anthropic Claude",
            SubscriptionProxyStore.ACCOUNT_GEMINI to "Google Gemini"
        )
        adapters.values.filter { it.connected() }.forEach { adapter ->
            runCatching { refreshModels(adapter.source) }
                .getOrElse { store.models(adapter.source) }
                .forEach { model -> out += ModelRef(adapter.source, model, labels[adapter.source].orEmpty()) }
        }
        if (adapters[SubscriptionProxyStore.ACCOUNT_GROK]?.connected() == true) {
            runCatching { refreshGrokUsage() }
        }
        if (store.externalConfig().enabled) {
            runCatching { refreshModels(ACCOUNT_EXTERNAL) }
                .getOrElse { store.models(ACCOUNT_EXTERNAL) }
                .forEach { out += ModelRef(ACCOUNT_EXTERNAL, it, "CLIProxyAPI") }
        }
        return out.distinctBy { it.source to it.model }
    }

    fun cachedModels(source: String): List<String> = store.models(source)

    suspend fun forceRefreshForSource(source: String) {
        requireRuntimeSource(source)
        adapters[source]?.forceRefresh()
            ?: if (source != ACCOUNT_EXTERNAL) throw ApiFailure("Subscription Proxy: неизвестный источник $source") else Unit
    }

    suspend fun <T> executeAuthenticated(
        source: String,
        canRetry: () -> Boolean = { true },
        block: suspend (SubscriptionTransportAuth) -> T
    ): T {
        requireRuntimeSource(source)
        return authExecutor.execute(source, canRetry, block)
    }

    suspend fun accessTokenForSource(source: String): String {
        requireRuntimeSource(source)
        return if (source == ACCOUNT_EXTERNAL) store.externalConfig().bearer
        else adapters[source]?.accessToken()
            ?: throw ApiFailure("Subscription Proxy: источник $source не поддерживается")
    }

    suspend fun accountScopeForSource(source: String): String {
        requireRuntimeSource(source)
        return if (source == ACCOUNT_EXTERNAL) "" else adapters[source]?.accountScope().orEmpty()
    }

    fun baseUrlForSource(source: String): String {
        requireRuntimeSource(source)
        return if (source == ACCOUNT_EXTERNAL) store.externalConfig().baseUrl
        else adapters[source]?.baseUrl().orEmpty()
    }

    fun protocolForSource(source: String): String {
        requireRuntimeSource(source)
        return if (source == ACCOUNT_EXTERNAL) ApiGateway.CHAT
        else adapters[source]?.protocol ?: ApiGateway.CHAT
    }

    fun providerTypeForSource(source: String): String = when (source) {
        SubscriptionProxyStore.ACCOUNT_CODEX -> com.omnichat.app.providers.ProviderCatalog.OPENAI_CODEX_ID
        SubscriptionProxyStore.ACCOUNT_NVIDIA -> com.omnichat.app.providers.AccountProviderCatalog.NVIDIA_ID
        else -> PROVIDER_PREFIX + source
    }

    fun sourceFromProviderType(providerType: String): String? =
        providerType.takeIf { it.startsWith(PROVIDER_PREFIX) }?.removePrefix(PROVIDER_PREFIX)

    fun displayNameForSource(source: String): String = when (source) {
        SubscriptionProxyStore.ACCOUNT_CHATGPT -> "ChatGPT Subscription"
        SubscriptionProxyStore.ACCOUNT_CHATGPT_JSON -> "ChatGPT JSON Session · Experimental"
        SubscriptionProxyStore.ACCOUNT_CODEX -> "OpenAI Codex · canonical account"
        SubscriptionProxyStore.ACCOUNT_GROK -> "Subscription Proxy · xAI Grok"
        SubscriptionProxyStore.ACCOUNT_CLAUDE -> "Subscription Proxy · Anthropic Claude"
        SubscriptionProxyStore.ACCOUNT_GEMINI -> "Subscription Proxy · Google Gemini"
        SubscriptionProxyStore.ACCOUNT_NVIDIA -> "NVIDIA NIM · canonical account"
        ACCOUNT_EXTERNAL -> "Subscription Proxy · CLIProxyAPI"
        else -> "Subscription Proxy · $source"
    }

    private fun healthFor(source: String, connected: Boolean): String {
        if (!connected) return "disconnected"
        val catalog = store.catalogState(source)
        return when {
            catalog.lastError.isNotBlank() && catalog.models.isNotEmpty() -> "degraded"
            catalog.lastError.isNotBlank() -> "unavailable"
            catalog.verifiedAtMs > 0L -> "healthy"
            else -> "unverified"
        }
    }

    fun snapshot(source: String): AccountSnapshot {
        if (isCanonicalOnlySource(source)) {
            return AccountSnapshot(
                source = source,
                connected = false,
                identity = "",
                detail = "Управляется единым подключением в «Аккаунты и подписки»",
                models = emptyList()
            )
        }
        if (source == ACCOUNT_EXTERNAL) {
            val config = store.externalConfig()
            val catalog = store.catalogState(source)
            return AccountSnapshot(
                source = source,
                connected = config.enabled,
                identity = config.baseUrl,
                detail = "Совместимость с отдельным CLIProxyAPI endpoint",
                models = catalog.models,
                health = healthFor(source, config.enabled),
                modelOrigin = catalog.origin,
                lastVerifiedAtMs = catalog.verifiedAtMs,
                lastError = catalog.lastError
            )
        }

        val adapter = adapters[source] ?: return AccountSnapshot(source, false, "", "", emptyList())
        val catalog = store.catalogState(source)
        val usage = if (source == SubscriptionProxyStore.ACCOUNT_GROK) store.readGrokUsage() else null
        return AccountSnapshot(
            source = source,
            connected = adapter.connected(),
            identity = adapter.identity(),
            detail = adapter.detail(),
            models = catalog.models,
            plan = usage?.plan ?: adapter.plan(),
            usedPercent = usage?.usedPercent,
            remainingPercent = usage?.remainingPercent,
            periodLabel = usage?.periodLabel.orEmpty(),
            resetsAtMs = usage?.resetsAtMs,
            health = healthFor(source, adapter.connected()),
            modelOrigin = catalog.origin,
            lastVerifiedAtMs = catalog.verifiedAtMs,
            lastError = catalog.lastError
        )
    }

    fun accountState(source: String): SubscriptionAccountState {
        val snapshot = snapshot(source)
        val capabilities = when {
            isCanonicalOnlySource(source) -> SubscriptionCapabilities(
                chat = FeatureAvailability.UNAVAILABLE,
                nativeTools = FeatureAvailability.UNAVAILABLE
            )
            source == ACCOUNT_EXTERNAL -> SubscriptionCapabilities(
                chat = FeatureAvailability.AVAILABLE,
                nativeTools = FeatureAvailability.UNKNOWN,
                imageGeneration = FeatureAvailability.UNKNOWN,
                videoGeneration = FeatureAvailability.UNKNOWN
            )
            else -> adapters[source]?.capabilities() ?: SubscriptionCapabilities(
                chat = FeatureAvailability.UNAVAILABLE,
                nativeTools = FeatureAvailability.UNAVAILABLE
            )
        }
        return SubscriptionAccountState(
            source = source,
            connected = snapshot.connected,
            health = snapshot.health,
            identity = snapshot.identity,
            plan = snapshot.plan,
            endpoint = if (isCanonicalOnlySource(source)) "" else baseUrlForSource(source),
            models = snapshot.models,
            modelOrigin = snapshot.modelOrigin,
            lastVerifiedAtMs = snapshot.lastVerifiedAtMs,
            lastError = snapshot.lastError,
            capabilities = capabilities
        )
    }

    suspend fun verifyAccount(source: String): SubscriptionAccountState {
        if (isCanonicalOnlySource(source)) return accountState(source)
        if (source != ACCOUNT_EXTERNAL) {
            val adapter = adapters[source]
                ?: throw ApiFailure("Subscription Proxy: неизвестный источник $source")
            if (!adapter.connected()) return accountState(source)
        } else if (!store.externalConfig().enabled) {
            return accountState(source)
        }

        runCatching { refreshModels(source) }
        if (source == SubscriptionProxyStore.ACCOUNT_GROK) {
            runCatching { refreshGrokUsage() }
        }
        return accountState(source)
    }

    fun clear(source: String) {
        when (source) {
            SubscriptionProxyStore.ACCOUNT_CHATGPT -> store.clearChatGpt()
            SubscriptionProxyStore.ACCOUNT_CHATGPT_JSON -> store.clearChatGptJson()
            // Migration cleanup only; these sources are not runtime adapters anymore.
            SubscriptionProxyStore.ACCOUNT_CODEX -> store.clearCodex()
            SubscriptionProxyStore.ACCOUNT_GROK -> store.clearGrok()
            SubscriptionProxyStore.ACCOUNT_CLAUDE -> store.clearClaude()
            SubscriptionProxyStore.ACCOUNT_GEMINI -> store.clearGemini()
            SubscriptionProxyStore.ACCOUNT_NVIDIA -> store.clearNvidia()
        }
    }

    private fun isCanonicalOnlySource(source: String): Boolean =
        source == SubscriptionProxyStore.ACCOUNT_CODEX || source == SubscriptionProxyStore.ACCOUNT_NVIDIA

    private fun requireRuntimeSource(source: String) {
        if (isCanonicalOnlySource(source)) {
            throw ApiFailure("Этот источник управляется единым подключением в «Аккаунты и подписки»")
        }
    }

    private suspend fun externalModels(): List<String> = withContext(Dispatchers.IO) {
        val config = store.externalConfig()
        require(config.enabled) { "Внешний CLIProxyAPI выключен" }
        val base = config.baseUrl.trim().trimEnd('/')
        val url = "$base/models".toHttpUrlOrNull()
            ?: throw ApiFailure("Некорректный CLIProxyAPI URL")
        NetworkEndpointPolicy.requireApiUrl(url, allowLocalHttp = true)
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .apply {
                if (config.bearer.isNotBlank()) header("Authorization", "Bearer ${config.bearer}")
            }
            .get().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiFailure("CLIProxyAPI /models: HTTP ${response.code}")
            }
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw ApiFailure("CLIProxyAPI /models вернул некорректный JSON")
            }
            val data = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
            (0 until data.length()).mapNotNull { i ->
                when (val item = data.opt(i)) {
                    is String -> item.takeIf { it.isNotBlank() }
                    is JSONObject -> item.optString("id")
                        .ifBlank { item.optString("name") }
                        .takeIf { it.isNotBlank() }
                    else -> null
                }
            }.distinct().sorted()
        }
    }

    companion object {
        const val PROVIDER_PREFIX = "subscription-proxy-"
        const val ACCOUNT_EXTERNAL = "external"
    }
}
