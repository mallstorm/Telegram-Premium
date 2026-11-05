package com.omnichat.app.providers

import com.omnichat.app.network.ApiGateway
import com.omnichat.app.network.KiloGatewayRepository

enum class AccountAuthMode { API_KEY, OAUTH_CALLBACK, OAUTH_DEVICE, OAUTH_LOOPBACK }

data class AccountProviderSpec(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: String,
    val accessLabel: String,
    val description: String,
    val requiresAccountId: Boolean = false,
    val accountIdLabel: String = "",
    val fallbackModels: List<String> = emptyList(),
    val verifyOnConnect: Boolean = false,
    val accountUrl: String,
    val accountActionLabel: String = "Открыть кабинет",
    val authMode: AccountAuthMode = AccountAuthMode.API_KEY
)

/**
 * Account/API-key services exposed in the lightweight "Accounts and subscriptions" hub.
 * These are independent connections: a model routed through one service never silently borrows
 * another service's credentials.
 */
object AccountProviderCatalog {
    const val POLZA_ID = "polza"
    const val VSEGPT_ID = "vsegpt"
    const val REQUESTY_ID = "requesty"
    const val OPENCODE_ZEN_ID = "opencode-zen"
    const val OPENCODE_GO_ID = "opencode-go"
    const val NVIDIA_ID = "nvidia"
    const val GROQ_ID = "groq"
    const val MISTRAL_ID = "mistral"
    const val CLOUDFLARE_ID = "cloudflare"
    const val COHERE_ID = "cohere"
    const val KIMI_CODE_ID = "kimi-code"
    const val ZOO_GATEWAY_ID = "zoo-gateway"

    private val requestyFree = setOf(
        "nemotron-3.5-lightning-30b-a3b",
        "muse-glimmer-30b",
        "iling-3.0-tiny",
        "nemotron-3.5-content-safety",
        "laguna-m.1",
        "laguna-xs.2",
        "nemotron-3-nano-omni-30b-a3b-reasoning",
        "nemotron-3-ultra-550b-a55b",
        "leanstral-1-5",
        "gemma-4-31b-it",
        "nemotron-3-super-120b-a12b",
        "nemotron-3-nano-30b-a3b"
    )

    val specs: List<AccountProviderSpec> = listOf(
        AccountProviderSpec(
            POLZA_ID,
            "Polza.AI",
            "https://polza.ai/api/v1",
            ApiGateway.CHAT,
            "Оплата ₽ · 400+ моделей · PAYG",
            "Российский агрегатор моделей. Вход через браузер и PKCE создаёт API-ключ автоматически; каталог разговорных моделей загружается из аккаунта.",
            accountUrl = "https://polza.ai/",
            accountActionLabel = "Войти в Polza.AI",
            authMode = AccountAuthMode.OAUTH_LOOPBACK
        ),
        AccountProviderSpec(
            VSEGPT_ID,
            "VseGPT",
            "https://api.vsegpt.ru/v1",
            ApiGateway.CHAT,
            "199 ₽ Basic · 399 ₽ Pro · 799 ₽ OMF · 999 ₽ FAST · Team",
            "Все тарифы VseGPT используют один OpenAI-совместимый API: Базовый, Профессиональный, Open Models Fan, FAST и Team. OmniChat загружает только модели, доступные вашему ключу и тарифу.",
            accountUrl = "https://vsegpt.ru/User/API",
            accountActionLabel = "Открыть VseGPT · API-ключ"
        ),
        AccountProviderSpec(
            REQUESTY_ID,
            "Requesty",
            "https://router.requesty.ai/v1",
            ApiGateway.CHAT,
            "Free models + PAYG",
            "OpenAI-compatible gateway. Zero-cost models and paid routing use the same Requesty API key.",
            fallbackModels = requestyFree.toList(),
            accountUrl = "https://app.requesty.ai",
            accountActionLabel = "Войти в Requesty",
            authMode = AccountAuthMode.OAUTH_CALLBACK
        ),
        AccountProviderSpec(
            OPENCODE_ZEN_ID,
            "OpenCode Zen",
            "https://opencode.ai/zen/v1",
            ApiGateway.CHAT,
            "Free models + PAYG",
            "OpenCode Zen gateway. Models are loaded live; OmniChat switches Chat/Responses/Messages transport per selected model.",
            fallbackModels = listOf(
                "deepseek-v4-flash-free",
                "muse-spark-1.3-contributor-free",
                "muse-spark-1.2-contributor-free",
                "mimo-v2.6-flash-free",
                "space-bunny-free",
                "longcat-2.5-preview-free",
                "mimo-v2.5-free",
                "ling-3.0-flash-fin-free",
                "nemotron-3-ultra-free",
                "nemotron-3.5-lightning-free",
                "big-pickle"
            ),
            verifyOnConnect = true,
            accountUrl = "https://opencode.ai/auth",
            accountActionLabel = "Войти в OpenCode Zen / получить ключ"
        ),
        AccountProviderSpec(
            OPENCODE_GO_ID,
            "OpenCode Go",
            "https://opencode.ai/zen/go/v1",
            ApiGateway.CHAT,
            "Subscription",
            "OpenCode Go/Go Plus subscription key. Models are loaded live and requests carry OmniChat identity and a stable conversation session.",
            fallbackModels = listOf(
                "grok-4.7", "grok-4.6", "gpt-6-luna", "gpt-5.6-luna",
                "glm-5.3-flash", "glm-5.3", "glm-5.2",
                "kimi-k3", "kimi-k2.7-code", "kimi-k2.6", "longcat-2.0",
                "deepseek-v4.1-flash", "deepseek-v4-pro", "deepseek-v4-flash",
                "deepseek-v4-flash-vision-exp",
                "mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.5", "mimo-v2.5-pro",
                "minimax-m3", "minimax-m2.7",
                "muse-spark-1.3-contributor", "muse-spark-1.2-contributor",
                "qwen3.8-max", "qwen3.8-flash", "qwen3.7-plus",
                "hy4-preview", "hy3", "space-bunny-free", "longcat-2.5-preview-free"
            ),
            verifyOnConnect = true,
            accountUrl = "https://opencode.ai/auth",
            accountActionLabel = "Открыть OpenCode Go / получить ключ"
        ),
        AccountProviderSpec(
            NVIDIA_ID,
            "NVIDIA NIM",
            "https://integrate.api.nvidia.com/v1",
            ApiGateway.CHAT,
            "Free endpoints / credits",
            "NVIDIA hosted inference endpoints. Model availability and free quotas depend on the NVIDIA account.",
            accountUrl = "https://build.nvidia.com",
            accountActionLabel = "Войти в NVIDIA / получить API key"
        ),
        AccountProviderSpec(
            GROQ_ID,
            "GroqCloud",
            "https://api.groq.com/openai/v1",
            ApiGateway.CHAT,
            "Free plan",
            "Fast OpenAI-compatible inference. The existing Whisper key can also be used here if it is a Groq API key.",
            accountUrl = "https://console.groq.com/keys",
            accountActionLabel = "Открыть Groq API Keys"
        ),
        AccountProviderSpec(
            MISTRAL_ID,
            "Mistral AI",
            "https://api.mistral.ai/v1",
            ApiGateway.CHAT,
            "Free API mode + paid",
            "Official Mistral API. Available models are loaded from the account.",
            accountUrl = "https://console.mistral.ai/api-keys/",
            accountActionLabel = "Открыть Mistral API Keys"
        ),
        AccountProviderSpec(
            CLOUDFLARE_ID,
            "Cloudflare Workers AI",
            "",
            ApiGateway.CHAT,
            "Free Neurons + paid",
            "OpenAI-compatible Workers AI endpoint. Requires Cloudflare Account ID and API token.",
            requiresAccountId = true,
            accountIdLabel = "Cloudflare Account ID",
            fallbackModels = listOf(
                "@cf/zai-org/glm-4.7-flash",
                "@cf/google/gemma-4-26b-a4b-it",
                "@cf/nvidia/nemotron-3-120b-a12b"
            ),
            accountUrl = "https://dash.cloudflare.com/",
            accountActionLabel = "Открыть Cloudflare Workers AI"
        ),
        AccountProviderSpec(
            COHERE_ID,
            "Cohere",
            "https://api.cohere.ai/compatibility/v1",
            ApiGateway.CHAT,
            "Evaluation key + paid",
            "Cohere OpenAI Compatibility API. Chat/tool models are loaded from the account.",
            accountUrl = "https://dashboard.cohere.com/api-keys",
            accountActionLabel = "Открыть Cohere API Keys"
        ),
        AccountProviderSpec(
            KIMI_CODE_ID,
            "Kimi Code",
            "https://api.kimi.ai/coding/v1",
            ApiGateway.CHAT,
            "Subscription",
            "Kimi Code subscription through the official OAuth device flow. After browser approval OmniChat stores refresh credentials in Android Keystore and loads the model catalog automatically.",
            fallbackModels = listOf("kimi-for-coding"),
            accountUrl = "https://www.kimi.com/code",
            accountActionLabel = "Войти в Kimi Code",
            authMode = AccountAuthMode.OAUTH_DEVICE
        ),
        AccountProviderSpec(
            ZOO_GATEWAY_ID,
            "Zoo Gateway",
            "https://www.zoocode.dev/api/gateway/v1",
            ApiGateway.CHAT,
            "Gateway / promo models",
            "Zoo Gateway session login. Browser authorization returns a Zoo session token to OmniChat, then the model catalog is loaded automatically.",
            fallbackModels = listOf("anthropic/claude-sonnet-4"),
            accountUrl = "https://www.zoocode.dev/dashboard",
            accountActionLabel = "Войти в Zoo Code",
            authMode = AccountAuthMode.OAUTH_CALLBACK
        )
    )

    fun byId(id: String): AccountProviderSpec? = specs.firstOrNull { it.id == id }

    fun resolveBaseUrl(spec: AccountProviderSpec, accountId: String): String =
        if (spec.id == CLOUDFLARE_ID) {
            val clean = accountId.trim()
            require(clean.isNotBlank()) { "Укажите Cloudflare Account ID" }
            require(clean.matches(Regex("[A-Za-z0-9_-]{6,128}"))) { "Некорректный Cloudflare Account ID" }
            "https://api.cloudflare.com/client/v4/accounts/$clean/ai/v1"
        } else spec.baseUrl

    fun protocolForModel(providerType: String, model: String, defaultProtocol: String): String {
        val id = model.lowercase()
        return when (providerType) {
            OPENCODE_ZEN_ID -> when {
                id.startsWith("gpt-") || id.startsWith("grok-") || id.startsWith("muse-spark-") ->
                    ApiGateway.RESPONSES
                id.startsWith("claude-") ||
                    id.startsWith("qwen3.8-flash") ||
                    id.startsWith("qwen3.7-") ||
                    id.startsWith("qwen3.6-") ||
                    id.startsWith("qwen3.5-") -> ApiGateway.ANTHROPIC
                else -> ApiGateway.CHAT
            }
            OPENCODE_GO_ID -> when {
                id.startsWith("gpt-") || id.startsWith("grok-") || id.startsWith("muse-spark-") -> ApiGateway.RESPONSES
                id.startsWith("minimax-") ||
                    id.startsWith("qwen3.8-") ||
                    id.startsWith("qwen3.7-") ||
                    id.startsWith("qwen3.6-") -> ApiGateway.ANTHROPIC
                else -> ApiGateway.CHAT
            }
            else -> defaultProtocol
        }
    }

    fun supportsModel(providerType: String, model: String): Boolean {
        val id = model.lowercase()
        if (providerType == OPENCODE_ZEN_ID) {
            if (id.startsWith("gemini-") || id.startsWith("jev-")) return false
        }
        if (providerType == VSEGPT_ID) {
            // /models contains text plus media/utility endpoints. The chat picker must not
            // send TTS/STT/image/video IDs to /chat/completions.
            if (
                id.startsWith("tts-") || id.startsWith("stt-") || id.startsWith("tta-") ||
                id.startsWith("utils/") || id.startsWith("img2img-") ||
                id.startsWith("dall-e-") || id.startsWith("video-") ||
                id.contains("/tts-") || id.contains("/stt-") ||
                id.contains("stable-audio")
            ) return false
        }
        return true
    }

    fun isFreeModel(providerType: String, model: String): Boolean {
        val id = model.lowercase()
        return when (providerType) {
            ProviderCatalog.KILO_GATEWAY_ID ->
                id == "kilo-auto/free" || id.endsWith(":free") || id.endsWith("/free")
            ProviderCatalog.OPENROUTER_ID ->
                id == "openrouter/free" || id.endsWith(":free") || id.endsWith("/free")
            ProviderCatalog.CLINE_FREE_ID -> true
            REQUESTY_ID -> id.substringAfterLast('/') in requestyFree
            OPENCODE_ZEN_ID -> id.endsWith("-free")
            else -> false
        }
    }

    fun accessHint(providerType: String): String? = byId(providerType)?.accessLabel

    fun sourceLabel(providerType: String): String = when (providerType) {
        ProviderCatalog.KILO_GATEWAY_ID -> "Kilo Gateway"
        ProviderCatalog.OPENROUTER_ID -> "OpenRouter Account"
        ProviderCatalog.OPENAI_CODEX_ID -> "OpenAI ChatGPT/Codex Account"
        else -> byId(providerType)?.name.orEmpty()
    }
}
