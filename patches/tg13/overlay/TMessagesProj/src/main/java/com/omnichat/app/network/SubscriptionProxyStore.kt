package com.omnichat.app.network

import android.content.Context
import com.omnichat.app.providers.ConnectionModelCatalogStore
import com.omnichat.app.security.SubscriptionProxyVault
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage boundary for live Subscription Proxy sources.
 *
 * Runtime credentials here belong only to genuine proxy-only transports (ChatGPT plan/session,
 * Grok, Claude, Gemini and external CLIProxyAPI). Historical Codex/NVIDIA keys are retained only so
 * ProviderStateConsolidator can migrate existing installs without losing credentials.
 */
class SubscriptionProxyStore(
    context: Context,
    private val vault: SubscriptionProxyVault,
    private val connectionCatalog: ConnectionModelCatalogStore = ConnectionModelCatalogStore(context)
) {
    data class GrokCredentials(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val tokenType: String,
        val expiresAtMs: Long,
        val email: String,
        val subject: String,
        val tokenEndpoint: String
    ) {
        fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs <= now + 5 * 60 * 1000L
    }

    data class ClaudeCredentials(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
        val email: String,
        val accountId: String,
        val organizationId: String,
        val organizationName: String
    ) {
        fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs <= now + 5 * 60 * 1000L
    }

    data class GeminiCredentials(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresAtMs: Long,
        val email: String,
        val projectId: String,
        val apiBaseUrl: String = ""
    ) {
        fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs <= now + 5 * 60 * 1000L
    }

    data class ExternalProxyConfig(
        val enabled: Boolean,
        val baseUrl: String,
        val bearer: String
    )

    data class CatalogState(
        val models: List<String>,
        val origin: String,
        val verifiedAtMs: Long,
        val lastError: String
    )

    data class NvidiaInferenceState(
        val available: Boolean?,
        val verifiedAtMs: Long,
        val message: String
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Migration-only writer kept for downgrade/source compatibility. */
    @Deprecated("Codex runtime credentials belong to OpenAiCodexAccountRepository")
    fun saveCodex(credentials: OpenAiCodexAccountClient.Credentials) {
        val json = JSONObject()
            .put("id_token", credentials.idToken)
            .put("access_token", credentials.accessToken)
            .put("refresh_token", credentials.refreshToken)
            .put("expires_at_ms", credentials.expiresAtMs)
            .put("account_id", credentials.accountId)
            .put("email", credentials.email)
            .put("plan", credentials.plan)
        putSecret(CODEX_SECRET, json.toString())
    }

    /** Migration-only reader used by ProviderStateConsolidator. */
    @Deprecated("Codex runtime credentials belong to OpenAiCodexAccountRepository")
    fun readCodex(): OpenAiCodexAccountClient.Credentials? = secret(CODEX_SECRET)
        .takeIf { it.isNotBlank() }
        ?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                OpenAiCodexAccountClient.Credentials(
                    idToken = json.optString("id_token"),
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token"),
                    expiresAtMs = json.optLong("expires_at_ms"),
                    accountId = json.optString("account_id"),
                    email = json.optString("email"),
                    plan = json.optString("plan")
                )
            }.getOrNull()
        }

    /** Migration-only cleanup after canonical Codex state is verified. */
    @Deprecated("Codex runtime credentials belong to OpenAiCodexAccountRepository")
    fun clearCodex() {
        prefs.edit().remove(CODEX_SECRET).remove(CODEX_MODELS)
            .remove(catalogOriginKey(ACCOUNT_CODEX)).remove(catalogVerifiedKey(ACCOUNT_CODEX))
            .remove(catalogErrorKey(ACCOUNT_CODEX)).apply()
    }

    fun chatGptHostId(): String {
        val existing = prefs.getString(CHATGPT_HOST_ID, "").orEmpty()
        if (existing.startsWith("urn:")) return existing
        val created = "urn:uuid:" + java.util.UUID.randomUUID()
        prefs.edit().putString(CHATGPT_HOST_ID, created).apply()
        return created
    }

    fun saveChatGpt(credentials: ChatGptPlanClient.Credentials) =
        putSecret(CHATGPT_SECRET, credentials.toJson())

    fun readChatGpt(): ChatGptPlanClient.Credentials? = secret(CHATGPT_SECRET)
        .takeIf { it.isNotBlank() }
        ?.let { ChatGptPlanClient.Credentials.fromJson(it) }

    fun clearChatGpt() {
        prefs.edit().remove(CHATGPT_SECRET).remove(CHATGPT_MODELS)
            .remove(catalogOriginKey(ACCOUNT_CHATGPT)).remove(catalogVerifiedKey(ACCOUNT_CHATGPT))
            .remove(catalogErrorKey(ACCOUNT_CHATGPT)).apply()
    }

    fun saveChatGptJson(credentials: ChatGptPlanClient.Credentials) =
        putSecret(CHATGPT_JSON_SECRET, credentials.toJson())

    fun readChatGptJson(): ChatGptPlanClient.Credentials? = secret(CHATGPT_JSON_SECRET)
        .takeIf { it.isNotBlank() }
        ?.let { ChatGptPlanClient.Credentials.fromJson(it) }

    fun clearChatGptJson() {
        prefs.edit().remove(CHATGPT_JSON_SECRET).remove(CHATGPT_JSON_MODELS)
            .remove(catalogOriginKey(ACCOUNT_CHATGPT_JSON)).remove(catalogVerifiedKey(ACCOUNT_CHATGPT_JSON))
            .remove(catalogErrorKey(ACCOUNT_CHATGPT_JSON)).apply()
    }

    fun saveGrok(credentials: GrokCredentials) {
        val json = JSONObject()
            .put("access_token", credentials.accessToken)
            .put("refresh_token", credentials.refreshToken)
            .put("id_token", credentials.idToken)
            .put("token_type", credentials.tokenType)
            .put("expires_at_ms", credentials.expiresAtMs)
            .put("email", credentials.email)
            .put("subject", credentials.subject)
            .put("token_endpoint", credentials.tokenEndpoint)
        putSecret(GROK_SECRET, json.toString())
    }

    fun readGrok(): GrokCredentials? = secret(GROK_SECRET)
        .takeIf { it.isNotBlank() }
        ?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                GrokCredentials(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token"),
                    idToken = json.optString("id_token"),
                    tokenType = json.optString("token_type", "Bearer"),
                    expiresAtMs = json.optLong("expires_at_ms"),
                    email = json.optString("email"),
                    subject = json.optString("subject"),
                    tokenEndpoint = json.optString("token_endpoint")
                )
            }.getOrNull()
        }

    fun saveGrokUsage(usage: GrokSubscriptionUsage) {
        prefs.edit().putString(GROK_USAGE, JSONObject()
            .put("plan", usage.plan)
            .put("used_percent", usage.usedPercent)
            .put("remaining_percent", usage.remainingPercent)
            .put("period_label", usage.periodLabel)
            .put("resets_at_ms", usage.resetsAtMs)
            .put("updated_at_ms", usage.updatedAtMs)
            .toString()).apply()
    }

    fun readGrokUsage(): GrokSubscriptionUsage? =
        prefs.getString(GROK_USAGE, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                GrokSubscriptionUsage(
                    plan = json.optString("plan", "Grok Build"),
                    usedPercent = json.opt("used_percent")?.let { if (it == JSONObject.NULL) null else json.optDouble("used_percent") },
                    remainingPercent = json.opt("remaining_percent")?.let { if (it == JSONObject.NULL) null else json.optDouble("remaining_percent") },
                    periodLabel = json.optString("period_label", "кредитный"),
                    resetsAtMs = json.opt("resets_at_ms")?.let { if (it == JSONObject.NULL) null else json.optLong("resets_at_ms") },
                    updatedAtMs = json.optLong("updated_at_ms")
                )
            }.getOrNull()
        }

    fun clearGrok() {
        prefs.edit().remove(GROK_SECRET).remove(GROK_MODELS).remove(GROK_USAGE)
            .remove(catalogOriginKey(ACCOUNT_GROK)).remove(catalogVerifiedKey(ACCOUNT_GROK))
            .remove(catalogErrorKey(ACCOUNT_GROK)).apply()
    }

    fun saveClaude(credentials: ClaudeCredentials) {
        val json = JSONObject()
            .put("access_token", credentials.accessToken)
            .put("refresh_token", credentials.refreshToken)
            .put("expires_at_ms", credentials.expiresAtMs)
            .put("email", credentials.email)
            .put("account_id", credentials.accountId)
            .put("organization_id", credentials.organizationId)
            .put("organization_name", credentials.organizationName)
        putSecret(CLAUDE_SECRET, json.toString())
    }

    fun readClaude(): ClaudeCredentials? = secret(CLAUDE_SECRET)
        .takeIf { it.isNotBlank() }
        ?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                ClaudeCredentials(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token"),
                    expiresAtMs = json.optLong("expires_at_ms"),
                    email = json.optString("email"),
                    accountId = json.optString("account_id"),
                    organizationId = json.optString("organization_id"),
                    organizationName = json.optString("organization_name")
                )
            }.getOrNull()
        }

    fun clearClaude() = prefs.edit().remove(CLAUDE_SECRET).remove(CLAUDE_MODELS)
        .remove(catalogOriginKey(ACCOUNT_CLAUDE)).remove(catalogVerifiedKey(ACCOUNT_CLAUDE))
        .remove(catalogErrorKey(ACCOUNT_CLAUDE)).apply()

    fun saveGemini(credentials: GeminiCredentials) {
        val json = JSONObject()
            .put("access_token", credentials.accessToken)
            .put("refresh_token", credentials.refreshToken)
            .put("token_type", credentials.tokenType)
            .put("expires_at_ms", credentials.expiresAtMs)
            .put("email", credentials.email)
            .put("project_id", credentials.projectId)
            .put("api_base_url", credentials.apiBaseUrl)
        putSecret(GEMINI_SECRET, json.toString())
    }

    fun readGemini(): GeminiCredentials? = secret(GEMINI_SECRET)
        .takeIf { it.isNotBlank() }
        ?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                GeminiCredentials(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token"),
                    tokenType = json.optString("token_type", "Bearer"),
                    expiresAtMs = json.optLong("expires_at_ms"),
                    email = json.optString("email"),
                    projectId = json.optString("project_id"),
                    apiBaseUrl = json.optString("api_base_url")
                )
            }.getOrNull()
        }

    fun clearGemini() = prefs.edit().remove(GEMINI_SECRET).remove(GEMINI_MODELS)
        .remove(catalogOriginKey(ACCOUNT_GEMINI)).remove(catalogVerifiedKey(ACCOUNT_GEMINI))
        .remove(catalogErrorKey(ACCOUNT_GEMINI)).apply()

    /** Migration-only writer kept for old installs/tests. */
    @Deprecated("NVIDIA runtime credentials belong to the canonical account connection")
    fun saveNvidiaApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "Введите NVIDIA API key" }
        putSecret(NVIDIA_API_KEY_SECRET, apiKey.trim())
    }

    /** Migration-only reader used by ProviderStateConsolidator. */
    @Deprecated("NVIDIA runtime credentials belong to the canonical account connection")
    fun readNvidiaApiKey(): String = secret(NVIDIA_API_KEY_SECRET)

    /** Migration-only cleanup after canonical NVIDIA state is verified. */
    @Deprecated("NVIDIA runtime credentials belong to the canonical account connection")
    fun clearNvidia() = prefs.edit().remove(NVIDIA_API_KEY_SECRET).remove(NVIDIA_MODELS)
        .remove(catalogOriginKey(ACCOUNT_NVIDIA)).remove(catalogVerifiedKey(ACCOUNT_NVIDIA))
        .remove(catalogErrorKey(ACCOUNT_NVIDIA))
        .remove(NVIDIA_INFERENCE_AVAILABLE).remove(NVIDIA_INFERENCE_VERIFIED)
        .remove(NVIDIA_INFERENCE_MESSAGE).apply()

    @Deprecated("NVIDIA inference health belongs to the canonical account connection")
    fun saveNvidiaInference(probe: NvidiaNimClient.InferenceProbe) {
        prefs.edit()
            .putBoolean(NVIDIA_INFERENCE_AVAILABLE, probe.available)
            .putLong(NVIDIA_INFERENCE_VERIFIED, probe.verifiedAtMs)
            .putString(NVIDIA_INFERENCE_MESSAGE, probe.message.take(700))
            .apply()
    }

    @Deprecated("NVIDIA inference health belongs to the canonical account connection")
    fun nvidiaInferenceState(): NvidiaInferenceState = NvidiaInferenceState(
        available = if (prefs.contains(NVIDIA_INFERENCE_AVAILABLE))
            prefs.getBoolean(NVIDIA_INFERENCE_AVAILABLE, false) else null,
        verifiedAtMs = prefs.getLong(NVIDIA_INFERENCE_VERIFIED, 0L),
        message = prefs.getString(NVIDIA_INFERENCE_MESSAGE, "").orEmpty()
    )

    fun saveModels(account: String, models: List<String>, origin: String = ORIGIN_LIVE) {
        val now = System.currentTimeMillis()
        val clean = models.map(String::trim).filter(String::isNotBlank).distinct()
        connectionCatalog.save(connectionProviderType(account), clean, origin)
        // Compatibility mirror for upgrades/downgrades; no runtime reader depends on it anymore.
        prefs.edit()
            .putString(modelsKey(account), JSONArray(clean).toString())
            .putString(catalogOriginKey(account), origin)
            .putLong(catalogVerifiedKey(account), if (origin == ORIGIN_LIVE) now else legacyCatalogState(account).verifiedAtMs)
            .remove(catalogErrorKey(account))
            .apply()
    }

    fun markCatalogFailure(account: String, message: String) {
        connectionCatalog.markFailure(connectionProviderType(account), message)
        prefs.edit().putString(catalogErrorKey(account), message.take(500)).apply()
    }

    fun catalogState(account: String): CatalogState {
        var state = connectionCatalog.read(connectionProviderType(account))
        if (state.models.isEmpty() && state.verifiedAtMs == 0L && state.lastError.isBlank()) {
            val legacy = legacyCatalogState(account)
            if (legacy.models.isNotEmpty() || legacy.verifiedAtMs > 0L || legacy.lastError.isNotBlank()) {
                state = connectionCatalog.seedIfEmpty(
                    connectionProviderType(account), legacy.models, legacy.origin
                ).copy(lastError = legacy.lastError)
                if (legacy.lastError.isNotBlank()) {
                    connectionCatalog.markFailure(connectionProviderType(account), legacy.lastError)
                    state = connectionCatalog.read(connectionProviderType(account))
                }
            }
        }
        return CatalogState(state.models, state.origin, state.verifiedAtMs, state.lastError)
    }

    fun models(account: String): List<String> = catalogState(account).models

    private fun legacyCatalogState(account: String): CatalogState {
        val array = runCatching { JSONArray(prefs.getString(modelsKey(account), "[]")) }.getOrNull()
        val models = if (array == null) emptyList() else (0 until array.length()).mapNotNull { i ->
            array.optString(i).trim().takeIf { it.isNotBlank() }
        }.distinct()
        return CatalogState(
            models = models,
            origin = prefs.getString(catalogOriginKey(account), ORIGIN_CACHE).orEmpty().ifBlank { ORIGIN_CACHE },
            verifiedAtMs = prefs.getLong(catalogVerifiedKey(account), 0L),
            lastError = prefs.getString(catalogErrorKey(account), "").orEmpty()
        )
    }

    private fun connectionProviderType(account: String): String = "subscription-proxy-$account"

    fun externalConfig(): ExternalProxyConfig = ExternalProxyConfig(
        enabled = prefs.getBoolean(EXTERNAL_ENABLED, false),
        baseUrl = prefs.getString(EXTERNAL_BASE, DEFAULT_EXTERNAL_BASE).orEmpty(),
        bearer = secret(EXTERNAL_BEARER)
    )

    fun saveExternalConfig(enabled: Boolean, baseUrl: String, bearer: String) {
        val editor = prefs.edit()
            .putBoolean(EXTERNAL_ENABLED, enabled)
            .putString(EXTERNAL_BASE, baseUrl.trim().trimEnd('/'))
        if (bearer.isNotBlank()) editor.putString(EXTERNAL_BEARER, vault.encrypt(bearer.trim()))
        else editor.remove(EXTERNAL_BEARER)
        editor.apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun catalogOriginKey(account: String) = "catalog_origin_" + account.replace(Regex("[^a-z0-9_-]"), "_")
    private fun catalogVerifiedKey(account: String) = "catalog_verified_" + account.replace(Regex("[^a-z0-9_-]"), "_")
    private fun catalogErrorKey(account: String) = "catalog_error_" + account.replace(Regex("[^a-z0-9_-]"), "_")

    private fun modelsKey(account: String): String = when (account) {
        ACCOUNT_CODEX -> CODEX_MODELS
        ACCOUNT_CHATGPT -> CHATGPT_MODELS
        ACCOUNT_CHATGPT_JSON -> CHATGPT_JSON_MODELS
        ACCOUNT_GROK -> GROK_MODELS
        ACCOUNT_CLAUDE -> CLAUDE_MODELS
        ACCOUNT_GEMINI -> GEMINI_MODELS
        ACCOUNT_NVIDIA -> NVIDIA_MODELS
        else -> "models_" + account.replace(Regex("[^a-z0-9_-]"), "_")
    }

    private fun putSecret(key: String, raw: String) {
        prefs.edit().putString(key, vault.encrypt(raw)).apply()
    }

    private fun secret(key: String): String = runCatching {
        vault.decrypt(prefs.getString(key, "").orEmpty())
    }.getOrDefault("")

    companion object {
        const val PROVIDER_TYPE = "subscription-proxy"

        /** Legacy migration source only; not a live Subscription Proxy adapter. */
        const val ACCOUNT_CODEX = "codex"
        const val ACCOUNT_CHATGPT = "chatgpt"
        const val ACCOUNT_CHATGPT_JSON = "chatgpt-json"
        const val ACCOUNT_CLAUDE = "claude"
        const val ACCOUNT_GROK = "grok"
        const val ACCOUNT_GEMINI = "gemini"
        /** Legacy migration source only; not a live Subscription Proxy adapter. */
        const val ACCOUNT_NVIDIA = "nvidia"

        const val DEFAULT_EXTERNAL_BASE = "http://127.0.0.1:8787/v1"
        const val ORIGIN_LIVE = "live"
        const val ORIGIN_CACHE = "cache"
        const val ORIGIN_FALLBACK = "fallback"

        private const val PREFS = "subscription_proxy"
        private const val CODEX_SECRET = "codex_secret"
        private const val CHATGPT_SECRET = "chatgpt_plan_secret"
        private const val CHATGPT_JSON_SECRET = "chatgpt_json_secret"
        private const val CHATGPT_HOST_ID = "chatgpt_host_id"
        private const val CLAUDE_SECRET = "claude_secret"
        private const val GROK_SECRET = "grok_secret"
        private const val GEMINI_SECRET = "gemini_secret"
        private const val CODEX_MODELS = "codex_models"
        private const val CHATGPT_MODELS = "chatgpt_models"
        private const val CHATGPT_JSON_MODELS = "chatgpt_json_models"
        private const val CLAUDE_MODELS = "claude_models"
        private const val GROK_MODELS = "grok_models"
        private const val GROK_USAGE = "grok_usage"
        private const val GEMINI_MODELS = "gemini_models"
        private const val NVIDIA_API_KEY_SECRET = "nvidia_api_key_secret"
        private const val NVIDIA_MODELS = "nvidia_models"
        private const val NVIDIA_INFERENCE_AVAILABLE = "nvidia_inference_available"
        private const val NVIDIA_INFERENCE_VERIFIED = "nvidia_inference_verified"
        private const val NVIDIA_INFERENCE_MESSAGE = "nvidia_inference_message"
        private const val EXTERNAL_ENABLED = "external_enabled"
        private const val EXTERNAL_BASE = "external_base"
        private const val EXTERNAL_BEARER = "external_bearer"
    }
}
