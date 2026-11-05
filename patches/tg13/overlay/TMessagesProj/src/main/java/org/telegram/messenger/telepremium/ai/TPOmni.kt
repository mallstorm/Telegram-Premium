package org.telegram.messenger.telepremium.ai

import android.content.Context
import com.omnichat.app.data.MessageEntity
import com.omnichat.app.data.ProviderEntity
import com.omnichat.app.network.ApiFailure
import com.omnichat.app.network.ApiGateway
import com.omnichat.app.network.ChatGptPlanClient
import com.omnichat.app.network.ClineAccountClient
import com.omnichat.app.network.ClineAccountRepository
import com.omnichat.app.network.KiloGatewayRepository
import com.omnichat.app.network.KimiCodeOAuthRepository
import com.omnichat.app.network.OpenAiCodexAccountClient
import com.omnichat.app.network.OpenAiCodexAccountRepository
import com.omnichat.app.network.SubscriptionProxyClaudeClient
import com.omnichat.app.network.SubscriptionProxyGeminiClient
import com.omnichat.app.network.SubscriptionProxyGrokClient
import com.omnichat.app.network.SubscriptionProxyRepository
import com.omnichat.app.network.SubscriptionProxyStore
import com.omnichat.app.providers.AccountProviderCatalog
import com.omnichat.app.providers.AccountProviderSpec
import com.omnichat.app.providers.ConnectionModelCatalogStore
import com.omnichat.app.providers.ProviderCatalog
import com.omnichat.app.security.KeyVault
import com.omnichat.app.security.SubscriptionProxyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.telegram.messenger.ApplicationLoader

/**
 * Bridge between TelePremium (Java UI, TPAiStore connections) and OmniChat's connection layer
 * (com.omnichat.app.*, ported unchanged): subscription OAuth (ChatGPT plan, Codex, Grok,
 * Gemini/Antigravity), device sign-ins (Kilo Code, Cline, Kimi Code), account API keys
 * (NVIDIA, VseGPT, OpenCode, Cloudflare, Requesty, Zoo…), and CLIProxyAPI, and the
 * shared ApiGateway transport. All methods block — call them off the UI thread.
 */
object TPOmni {
    /** TPAiStore.Connection.authType for connections served by this bridge. */
    const val AUTH = "omni"

    const val KIND_CHATGPT = "chatgpt"
    const val KIND_CODEX = "codex"
    const val KIND_GROK = "grok"
    const val KIND_GEMINI = "gemini"
    const val KIND_KILO = "kilo"
    const val KIND_CLINE = "cline"
    const val KIND_KIMI = "kimi"

    private val context: Context get() = ApplicationLoader.applicationContext

    private val keyVault by lazy { KeyVault() }
    private val proxyVault by lazy { SubscriptionProxyVault() }
    private val catalogStore by lazy { ConnectionModelCatalogStore(context) }
    private val store by lazy { SubscriptionProxyStore(context, proxyVault, catalogStore) }
    private val grokClient by lazy { SubscriptionProxyGrokClient() }
    private val claudeClient by lazy { SubscriptionProxyClaudeClient() }
    private val geminiClient by lazy { SubscriptionProxyGeminiClient() }
    private val api by lazy { ApiGateway(context) }
    private val clineClient by lazy { ClineAccountClient() }
    private val clineRepository by lazy { ClineAccountRepository(context, keyVault, clineClient) }
    private val codexClient by lazy { OpenAiCodexAccountClient() }
    private val codexRepository by lazy { OpenAiCodexAccountRepository(context, keyVault, codexClient) }
    private val repository by lazy {
        SubscriptionProxyRepository(context, store, codexClient, grokClient, claudeClient, geminiClient)
    }
    private val kiloRepository by lazy { KiloGatewayRepository(context, keyVault) }
    private val kimiRepository by lazy { KimiCodeOAuthRepository(context, keyVault) }

    // ================================================================== connections

    private fun findByType(providerType: String): TPAiStore.Connection? =
        TPAiStore.list().firstOrNull { it.providerId == providerType }

    /** Creates or updates the single TelePremium connection of an OmniChat provider type. */
    private fun upsert(
        providerType: String,
        name: String,
        baseUrl: String,
        protocol: String,
        models: List<String>,
        scope: String = "",
        secret: String? = null
    ): TPAiStore.Connection {
        val existing = findByType(providerType)
        val c = existing ?: TPAiStore.Connection()
        c.providerId = providerType
        c.name = name
        c.baseUrl = baseUrl
        c.protocol = protocol
        c.authType = AUTH
        c.scope = scope
        val keep = existing?.model?.takeIf { it.isNotBlank() && (models.isEmpty() || it in models) }
        c.model = keep ?: models.firstOrNull().orEmpty()
        TPAiStore.save(c, secret)
        return c
    }

    // ================================================================== device sign-in

    class Device(
        @JvmField val kind: String,
        @JvmField val userCode: String,
        @JvmField val url: String,
        @JvmField val intervalSeconds: Long,
        @JvmField val expiresAtMs: Long,
        internal val handle: Any
    )

    @JvmStatic
    fun beginDevice(kind: String): Device = runBlocking(Dispatchers.IO) {
        when (kind) {
            KIND_CODEX -> codexClient.beginDeviceLogin().let {
                Device(kind, it.userCode, it.verificationUrl, it.pollIntervalSeconds, it.expiresAtMs, it)
            }
            KIND_GROK -> grokClient.beginDeviceLogin().let {
                Device(kind, it.userCode, it.verificationUriComplete.ifBlank { it.verificationUri }, it.pollIntervalSeconds, it.expiresAtMs, it)
            }
            KIND_CLINE -> clineClient.beginDeviceLogin().let {
                Device(kind, it.userCode, it.browserUrl, it.pollIntervalSeconds.toLong(),
                    System.currentTimeMillis() + it.expiresInSeconds * 1000L, it)
            }
            KIND_KILO -> kiloRepository.beginLogin().let {
                Device(kind, it.code, it.verificationUrl, 3, System.currentTimeMillis() + it.expiresIn * 1000L, it)
            }
            KIND_KIMI -> kimiRepository.beginLogin().let {
                Device(kind, it.userCode, it.verificationUriComplete.ifBlank { it.verificationUri }, it.intervalSeconds.toLong(), it.expiresAt, it)
            }
            else -> throw ApiFailure("Неизвестный способ входа: $kind")
        }
    }

    /**
     * Waits for the user to confirm the code in the browser and creates the TelePremium
     * connection. Returns a short status line. [cancelled] is polled between attempts.
     */
    @JvmStatic
    fun awaitDevice(device: Device, cancelled: java.util.concurrent.atomic.AtomicBoolean): String = runBlocking(Dispatchers.IO) {
        when (device.kind) {
            KIND_KILO -> {
                val account = kiloRepository.awaitLogin(device.handle as KiloGatewayRepository.DeviceLogin)
                val profile = ProviderCatalog.loadAll(context).first { it.id == ProviderCatalog.KILO_GATEWAY_ID }
                val models = runCatching {
                    api.models(profile.baseUrl, account.token, profile.protocol, profile.id, "")
                }.getOrDefault(emptyList()).ifEmpty { listOf(KiloGatewayRepository.AUTO_FREE) }
                upsert(profile.id, "Kilo Code", profile.baseUrl, profile.protocol, preferFirst(models, KiloGatewayRepository.AUTO_FREE))
                "Kilo Code подключён" + account.email.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            }
            KIND_KIMI -> {
                kimiRepository.waitForLogin(device.handle as KimiCodeOAuthRepository.DeviceLogin)
                val spec = AccountProviderCatalog.byId(AccountProviderCatalog.KIMI_CODE_ID)!!
                val token = kimiRepository.currentAccessToken()
                val models = accountModels(spec, token, "")
                upsert(spec.id, spec.name, spec.baseUrl, spec.protocol, models)
                "Kimi Code подключён · моделей: ${models.size}"
            }
            else -> {
                while (System.currentTimeMillis() < device.expiresAtMs) {
                    if (cancelled.get()) throw ApiFailure("Вход отменён")
                    delay(device.intervalSeconds.coerceAtLeast(2) * 1000L)
                    if (cancelled.get()) throw ApiFailure("Вход отменён")
                    when (device.kind) {
                        KIND_CODEX -> {
                            val saved = codexClient.pollDeviceOnce(device.handle as OpenAiCodexAccountClient.DeviceLogin) ?: continue
                            codexRepository.save(saved)
                            val models = runCatching { codexClient.modelCatalog(saved) }
                                .getOrDefault(OpenAiCodexAccountClient.DEFAULT_MODELS)
                            upsert(ProviderCatalog.OPENAI_CODEX_ID, "OpenAI ChatGPT Subscription · Codex",
                                OpenAiCodexAccountClient.CODEX_BASE_URL, ApiGateway.RESPONSES, models, saved.accountId)
                            return@runBlocking "OpenAI Codex подключён" + saved.email.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                        }
                        KIND_GROK -> {
                            val creds = repository.completeGrokPoll(device.handle as SubscriptionProxyGrokClient.DeviceLogin) ?: continue
                            val source = SubscriptionProxyStore.ACCOUNT_GROK
                            syncSource(source)
                            return@runBlocking "Grok подключён" + creds.email.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                        }
                        KIND_CLINE -> {
                            val tokens = clineClient.pollWorkOsOnce(device.handle as ClineAccountClient.DeviceLogin) ?: continue
                            val saved = clineClient.register(tokens)
                            clineRepository.save(saved)
                            val catalog = runCatching { clineClient.modelCatalog(saved) }.getOrNull()
                            upsert(ProviderCatalog.CLINE_ID, "Cline · Billing", ClineAccountClient.CLINE_OPENAI_BASE_URL, ApiGateway.CHAT,
                                catalog?.billing ?: ClineAccountClient.DEFAULT_BILLING_MODELS)
                            upsert(ProviderCatalog.CLINE_PASS_ID, "Cline · ClinePass", ClineAccountClient.CLINE_OPENAI_BASE_URL, ApiGateway.CHAT,
                                catalog?.pass ?: ClineAccountClient.DEFAULT_PASS_MODELS)
                            upsert(ProviderCatalog.CLINE_FREE_ID, "Cline · Free", ClineAccountClient.CLINE_OPENAI_BASE_URL, ApiGateway.CHAT,
                                catalog?.free ?: ClineAccountClient.DEFAULT_FREE_MODELS)
                            return@runBlocking "Cline подключён · Billing, ClinePass и Free"
                        }
                    }
                }
                throw ApiFailure("Код истёк. Запустите вход заново.")
            }
        }
    }

    // ================================================================== browser sign-in

    class Browser(@JvmField val kind: String, @JvmField val url: String, internal val handle: Any)

    @JvmStatic
    fun beginBrowser(kind: String): Browser = when (kind) {
        KIND_CHATGPT -> repository.beginChatGptLogin().let { Browser(kind, it.authUrl, it) }
        KIND_GEMINI -> geminiClient.beginLogin().let { Browser(kind, it.authUrl, it) }
        else -> throw ApiFailure("Неизвестный способ входа: $kind")
    }

    @JvmStatic
    fun awaitBrowser(browser: Browser): String = runBlocking(Dispatchers.IO) {
        when (browser.kind) {
            KIND_CHATGPT -> {
                val creds = repository.completeChatGptLogin(browser.handle as ChatGptPlanClient.Login)
                syncSource(SubscriptionProxyStore.ACCOUNT_CHATGPT)
                "ChatGPT Subscription подключена" + creds.email.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            }
            KIND_GEMINI -> {
                val creds = geminiClient.awaitCredentials(browser.handle as SubscriptionProxyGeminiClient.LoginSession)
                repository.saveGeminiLogin(creds)
                syncSource(SubscriptionProxyStore.ACCOUNT_GEMINI)
                "Google Gemini подключён" + creds.email.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            }
            else -> throw ApiFailure("Неизвестный способ входа")
        }
    }

    @JvmStatic
    fun cancelBrowser(browser: Browser?) {
        if (browser == null) return
        runCatching {
            when (browser.kind) {
                KIND_CHATGPT -> repository.cancelChatGptLogin(browser.handle as ChatGptPlanClient.Login)
                KIND_GEMINI -> geminiClient.cancel(browser.handle as SubscriptionProxyGeminiClient.LoginSession)
            }
        }
    }

    @JvmStatic
    fun importChatGptJson(raw: String): String = runBlocking(Dispatchers.IO) {
        repository.importChatGptJson(raw)
        syncSource(SubscriptionProxyStore.ACCOUNT_CHATGPT_JSON)
        "ChatGPT JSON-сессия импортирована"
    }

    /** CLIProxyAPI / any external OpenAI-compatible proxy of the Subscription Proxy hub. */
    @JvmStatic
    fun saveExternalProxy(baseUrl: String, bearer: String): String = runBlocking(Dispatchers.IO) {
        store.saveExternalConfig(true, baseUrl.trim(), bearer.trim())
        syncSource(SubscriptionProxyRepository.ACCOUNT_EXTERNAL)
        "CLIProxyAPI подключён"
    }

    private suspend fun syncSource(source: String): TPAiStore.Connection {
        val baseUrl = repository.baseUrlForSource(source)
        if (baseUrl.isBlank()) throw ApiFailure("Источник ещё не готов к использованию")
        val catalog = runCatching { repository.refreshModels(source) }
            .getOrElse { repository.cachedModels(source) }
            .filter(String::isNotBlank).distinct()
        if (catalog.isEmpty()) throw ApiFailure("Подписка не вернула доступных моделей")
        return upsert(
            repository.providerTypeForSource(source),
            repository.displayNameForSource(source),
            baseUrl,
            repository.protocolForSource(source),
            catalog,
            repository.accountScopeForSource(source)
        )
    }

    // ================================================================== account API keys

    /** Account services that are connected with a key, an OAuth code or a callback token. */
    @JvmStatic
    fun accountSpecs(): List<AccountProviderSpec> = AccountProviderCatalog.specs

    @JvmStatic
    fun connectAccount(providerType: String, apiKey: String, accountId: String): String = runBlocking(Dispatchers.IO) {
        val spec = AccountProviderCatalog.byId(providerType) ?: throw ApiFailure("Неизвестный сервис")
        val key = apiKey.trim()
        if (key.isBlank()) throw ApiFailure("Введите ключ ${spec.name}")
        var scope = if (spec.requiresAccountId) accountId.trim() else ""
        if (providerType == AccountProviderCatalog.CLOUDFLARE_ID && scope.isBlank()) {
            val accounts = api.cloudflareAccounts(key)
            scope = when (accounts.size) {
                0 -> throw ApiFailure("Cloudflare не вернул доступных аккаунтов для этого токена")
                1 -> accounts.first().id
                else -> throw ApiFailure("У токена несколько Cloudflare аккаунтов. Укажите Account ID.")
            }
        }
        val baseUrl = AccountProviderCatalog.resolveBaseUrl(spec, scope)
        val models = accountModels(spec.copy(baseUrl = baseUrl), key, scope)
        if (models.isEmpty()) throw ApiFailure("${spec.name} не вернул доступных разговорных моделей")
        upsert(spec.id, spec.name, baseUrl, spec.protocol, models, scope, key)
        "${spec.name} подключён · моделей: ${models.size}"
    }

    private suspend fun accountModels(spec: AccountProviderSpec, key: String, scope: String): List<String> {
        val remote = runCatching { api.models(spec.baseUrl, key, spec.protocol, spec.id, scope) }.getOrDefault(emptyList())
        return (remote + spec.fallbackModels)
            .filter { AccountProviderCatalog.supportsModel(spec.id, it) }
            .distinct()
    }

    private fun preferFirst(models: List<String>, preferred: String): List<String> =
        if (preferred in models) listOf(preferred) + models.filter { it != preferred } else models

    // ================================================================== disconnect

    @JvmStatic
    fun disconnect(providerType: String) {
        runCatching {
            repository.sourceFromProviderType(providerType)?.let { repository.clear(it) }
            when (providerType) {
                ProviderCatalog.OPENAI_CODEX_ID -> codexRepository.clear()
                ProviderCatalog.KILO_GATEWAY_ID -> kiloRepository.clear()
                AccountProviderCatalog.KIMI_CODE_ID -> kimiRepository.clear()
                ProviderCatalog.CLINE_ID, ProviderCatalog.CLINE_PASS_ID, ProviderCatalog.CLINE_FREE_ID -> {
                    if (TPAiStore.list().count { it.providerId.startsWith("cline") } <= 1) clineRepository.clear()
                }
            }
        }
    }

    // ================================================================== runtime

    private suspend fun storedKey(c: TPAiStore.Connection): String = TPAiStore.getSecret(c.id).trim()

    /** OmniChat's providerKey(): account repositories own their tokens. */
    private suspend fun keyFor(c: TPAiStore.Connection): String {
        val type = c.providerId
        return when {
            type == ProviderCatalog.OPENAI_CODEX_ID -> codexRepository.currentCredentials()?.accessToken.orEmpty()
            type == ProviderCatalog.KILO_GATEWAY_ID -> kiloRepository.readToken().ifBlank { storedKey(c) }
            type == AccountProviderCatalog.KIMI_CODE_ID ->
                if (kimiRepository.hasCredentials()) kimiRepository.currentAccessToken() else storedKey(c)
            type == ProviderCatalog.CLINE_ID || type == ProviderCatalog.CLINE_PASS_ID || type == ProviderCatalog.CLINE_FREE_ID ->
                clineRepository.currentCredentials()?.bearerToken
                    ?: storedKey(c).takeIf { it.isNotBlank() }?.let(ClineAccountClient::formatAccountToken).orEmpty()
            else -> storedKey(c)
        }
    }

    private fun entity(c: TPAiStore.Connection, system: String): ProviderEntity = ProviderEntity(
        id = (c.id.hashCode().toLong() and 0x7fffffffL) + 2L,
        name = c.title(),
        baseUrl = c.baseUrl.orEmpty(),
        encryptedApiKey = "",
        model = c.model.orEmpty(),
        protocol = c.protocol.orEmpty(),
        systemPrompt = system,
        providerType = c.providerId.orEmpty(),
        authScope = c.scope.orEmpty()
    )

    @JvmStatic
    fun models(c: TPAiStore.Connection): List<String> = runBlocking(Dispatchers.IO) {
        val type = c.providerId
        val source = repository.sourceFromProviderType(type)
        val list = when {
            source != null -> runCatching { repository.refreshModels(source) }.getOrElse { repository.cachedModels(source) }
            type == ProviderCatalog.OPENAI_CODEX_ID -> codexRepository.models()
            type == ProviderCatalog.CLINE_ID || type == ProviderCatalog.CLINE_PASS_ID || type == ProviderCatalog.CLINE_FREE_ID -> {
                val catalog = clineRepository.currentCredentials()?.let { clineClient.modelCatalog(it) }
                when (type) {
                    ProviderCatalog.CLINE_PASS_ID -> catalog?.pass ?: ClineAccountClient.DEFAULT_PASS_MODELS
                    ProviderCatalog.CLINE_FREE_ID -> catalog?.free ?: ClineAccountClient.DEFAULT_FREE_MODELS
                    else -> catalog?.billing ?: ClineAccountClient.DEFAULT_BILLING_MODELS
                }
            }
            AccountProviderCatalog.byId(type) != null -> accountModels(
                AccountProviderCatalog.byId(type)!!.copy(baseUrl = c.baseUrl.orEmpty()), keyFor(c), c.scope.orEmpty())
            else -> api.models(c.baseUrl.orEmpty(), keyFor(c), c.protocol.orEmpty(), type, c.scope.orEmpty())
        }
        list.filter(String::isNotBlank).distinct()
    }

    /** One-shot completion through OmniChat's transports (streams internally, returns final text). */
    @JvmStatic
    fun complete(c: TPAiStore.Connection, system: String?, user: String): String = runBlocking(Dispatchers.IO) {
        val sys = system.orEmpty()
        val history = listOf(MessageEntity(chatId = 1L, role = "user", content = user, model = c.model.orEmpty()))
        val type = c.providerId.orEmpty()
        val provider = entity(c, sys)
        val source = repository.sourceFromProviderType(type)
        if (source != null) {
            repository.executeAuthenticated(source) { auth ->
                api.stream(provider.copy(baseUrl = auth.baseUrl, authScope = auth.accountScope), auth.accessToken, history, { })
            }
        } else if (type == ProviderCatalog.OPENAI_CODEX_ID) {
            val account = codexRepository.currentCredentials() ?: throw ApiFailure("Сначала войдите в OpenAI Codex")
            api.stream(provider.copy(authScope = account.accountId), account.accessToken, history, { })
        } else {
            api.stream(provider, keyFor(c), history, { })
        }
    }
}
