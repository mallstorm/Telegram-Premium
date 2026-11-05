package com.omnichat.app.providers

import android.content.Context
import com.omnichat.app.network.ApiGateway
import com.omnichat.app.network.ClineAccountClient
import com.omnichat.app.network.OpenAiCodexAccountClient
import com.omnichat.app.network.KiloGatewayRepository
import org.json.JSONArray

/**
 * UI transport profiles imported from OmniBridge's provider registry.
 *
 * provider_catalog.json is still accepted as a legacy/import source, but account-managed providers
 * have exactly one runtime definition: AccountProviderCatalog. This prevents base URL, protocol,
 * auth and display metadata from becoming two competing sources of truth.
 */
data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: String,
    val category: String,
    val authType: String,
    val supportLevel: String,
    val selectable: Boolean,
    val warning: String = ""
) {
    val needsKey: Boolean get() = authType != "none"
    val needsEndpoint: Boolean get() = baseUrl.isBlank()
}

object ProviderCatalog {
    /** Canonical Cline connection. The two aliases are retained only for on-device migration. */
    const val CLINE_ID = "cline"
    const val CLINE_PASS_ID = "cline-pass"
    const val CLINE_FREE_ID = "cline-free"
    const val OPENAI_CODEX_ID = OpenAiCodexAccountClient.PROVIDER_TYPE
    const val OPENROUTER_ID = "openrouter"
    const val KILO_GATEWAY_ID = "kilo-gateway"
    const val CUSTOM_ID = "custom"
    const val GEMINI_WEB_ID = "gemini_web" // TelePremium: Gemini Web is not offered

    /** Generic API editor only; account-owned definitions are never projected into this list. */
    fun load(context: Context): List<ProviderProfile> =
        UnifiedConnectionCatalog.apiProfiles(loadAll(context))

    /**
     * Canonical runtime transport profiles.
     *
     * The JSON asset still carries some historical account-provider rows for backwards compatible
     * imports. They are deliberately removed here; AccountProviderCatalog owns those IDs at runtime.
     */
    fun loadAll(context: Context): List<ProviderProfile> = runtimeProfiles(
        parse(context.assets.open("provider_catalog.json").bufferedReader().use { it.readText() })
    )

    /** Pure projection used by runtime and tests; safe for migration/import callers to reuse. */
    fun runtimeProfiles(imported: List<ProviderProfile>): List<ProviderProfile> {
        val accountOwnedIds = ConnectionIdentity.accountManagedProviderIds
        return imported.filterNot { it.id in accountOwnedIds }
    }

    /**
     * Parse the legacy/import registry without changing its contents.
     * Call runtimeProfiles() before using the result as live application configuration.
     */
    fun parse(json: String): List<ProviderProfile> {
        val entries = JSONArray(json)
        val profiles = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val id = entry.getString("id")
            val protocols = entry.optJSONArray("protocols")
            val supportedWire = (0 until (protocols?.length() ?: 0))
                .mapNotNull { protocols?.optString(it) }
            val auth = entry.optString("auth_type", "bearer")
            val support = entry.optString("support_level")
            val rawUrl = entry.optString("base_url").trimEnd('/')
            val protocol = when (id) {
                "anthropic" -> ApiGateway.ANTHROPIC
                "gemini" -> ApiGateway.GEMINI
                "ollama" -> ApiGateway.OLLAMA
                "gigachat", "yandex" -> ApiGateway.CHAT
                "openai" -> ApiGateway.RESPONSES
                else -> if ("chat-completions" in supportedWire) ApiGateway.CHAT
                    else if ("responses" in supportedWire) ApiGateway.RESPONSES
                    else ApiGateway.CHAT
            }
            val url = when (id) {
                "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
                "ollama" -> "http://127.0.0.1:11434"
                "gigachat" -> "https://api.giga.chat/v1"
                "yandex" -> "https://ai.api.cloud.yandex.net/v1"
                else -> rawUrl
            }
            val authSupported = auth in setOf("bearer", "x-api-key", "none") ||
                (id == "gigachat" && auth == "gigachat-oauth") ||
                (id == "yandex" && auth == "yandex-api-key")
            val protocolSupported = id in setOf("anthropic", "gemini", "ollama") ||
                "chat-completions" in supportedWire || "responses" in supportedWire
            val selectable = authSupported && protocolSupported
            val warning = when {
                !authSupported -> "Требуется отдельный механизм авторизации ($auth); обычный API-ключ пока не поддерживается."
                !protocolSupported -> "Требуется отдельный API-адаптер; профиль доступен для справки."
                id == "gigachat" -> "Введите ключ авторизации и выберите область доступа. Приложение получит временный OAuth-токен автоматически."
                id == "yandex" -> "Укажите API-ключ и ID каталога AI Studio (OpenAI-Project)."
                url.isBlank() -> "Шаблон OmniBridge: адрес сервера необходимо указать вручную."
                id == "ollama" -> "127.0.0.1 на Android — это телефон, а не компьютер с Ollama. Укажите адрес своего сервера; HTTP доступен только для локального хоста устройства."
                else -> ""
            }
            ProviderProfile(
                id = id,
                name = if (id == CUSTOM_ID) "Произвольный OpenAI-compatible" else entry.getString("name"),
                baseUrl = url,
                protocol = protocol,
                category = entry.optString("category"),
                authType = auth,
                supportLevel = support,
                selectable = selectable,
                warning = warning
            )
        }
        require(profiles.map { it.id }.toSet().size == profiles.size) { "Повторяющиеся ID провайдеров" }

        // Cline Billing, ClinePass and Cline Free are access groups of one Cline account,
        // not three independent connections. Model classification decides the group.
        val cline = ProviderProfile(
            CLINE_ID,
            "Cline Account",
            ClineAccountClient.CLINE_OPENAI_BASE_URL,
            ApiGateway.CHAT,
            "Subscription",
            "bearer",
            "preset",
            true,
            "Один Cline account для Billing, ClinePass и Free. Группа доступа определяется выбранной моделью; токен хранится через Android Keystore."
        )
        val openAiCodex = ProviderProfile(
            OPENAI_CODEX_ID,
            "OpenAI ChatGPT Subscription",
            OpenAiCodexAccountClient.CODEX_BASE_URL,
            ApiGateway.RESPONSES,
            "Subscription",
            "account-oauth",
            "preset",
            true,
            "Вход через ChatGPT/Codex account выполняется в «Аккаунты и подписки». API-ключ OpenAI не нужен."
        )
        val openRouter = ProviderProfile(
            OPENROUTER_ID, "OpenRouter Account", "https://openrouter.ai/api/v1", ApiGateway.CHAT,
            "Gateway", "account-oauth", "native", true,
            "Отдельный аккаунт OpenRouter через OAuth PKCE. Не связан с Kilo Gateway; модели :free используют лимиты OpenRouter Account."
        )
        val kiloGateway = ProviderProfile(
            KILO_GATEWAY_ID, "Kilo Code", KiloGatewayRepository.BASE_URL, ApiGateway.CHAT,
            "Subscription", "account-oauth", "native", true,
            "Вход через официальный browser/device flow Kilo Code. Ручной API key в OmniChat не используется."
        )
        return listOf(cline, openAiCodex, openRouter, kiloGateway) +
            profiles.filterNot { it.id in setOf(OPENROUTER_ID, KILO_GATEWAY_ID) }
    }

    fun protocolLabel(protocol: String) = when (protocol) {
        ApiGateway.CHAT -> "OpenAI-compatible · Chat Completions"
        ApiGateway.RESPONSES -> "OpenAI · Responses"
        ApiGateway.ANTHROPIC -> "Anthropic · Messages"
        ApiGateway.GEMINI -> "Google Gemini · native API"
        ApiGateway.OLLAMA -> "Ollama · native API"
        else -> protocol
    }
}
