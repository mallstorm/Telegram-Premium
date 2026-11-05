package com.omnichat.app.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OpenAI ChatGPT/Codex subscription account client.
 *
 * The login flow mirrors the official openai/codex device-code flow:
 * auth.openai.com device authorization -> OAuth token exchange -> Codex backend.
 * No OpenAI API key is required.
 */
class OpenAiCodexAccountClient(
    private val authBaseUrl: String = AUTH_BASE_URL,
    private val codexBaseUrl: String = CODEX_BASE_URL
) {
    data class DeviceLogin(
        val deviceAuthId: String,
        val userCode: String,
        val verificationUrl: String,
        val pollIntervalSeconds: Long,
        val expiresAtMs: Long
    )

    data class Credentials(
        val idToken: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
        val accountId: String,
        val email: String = "",
        val plan: String = ""
    ) {
        fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs <= now + REFRESH_WINDOW_MS
    }

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun beginDeviceLogin(): DeviceLogin = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("client_id", CLIENT_ID)
        val response = executeJson(
            Request.Builder()
                .url("$authBaseUrl/api/accounts/deviceauth/usercode")
                .header("Accept", "application/json")
                .header("User-Agent", "OmniChat-Android/$CLIENT_VERSION")
                .post(payload.toString().toRequestBody(jsonType))
                .build(),
            "Не удалось начать вход OpenAI"
        )
        val deviceId = response.optString("device_auth_id")
        val userCode = response.optString("user_code").ifBlank { response.optString("usercode") }
        val interval = response.opt("interval")?.toString()?.toLongOrNull()?.coerceIn(1L, 30L) ?: 5L
        if (deviceId.isBlank() || userCode.isBlank()) {
            throw ApiFailure("OpenAI не вернул код входа")
        }
        DeviceLogin(
            deviceAuthId = deviceId,
            userCode = userCode,
            verificationUrl = "$authBaseUrl/codex/device",
            pollIntervalSeconds = interval,
            expiresAtMs = System.currentTimeMillis() + DEVICE_LOGIN_TTL_MS
        )
    }

    /**
     * One polling attempt. 403/404 means the user has not finished browser confirmation yet.
     * On success the authorization code is exchanged immediately for OAuth tokens.
     */
    suspend fun pollDeviceOnce(login: DeviceLogin): Credentials? = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() >= login.expiresAtMs) {
            throw ApiFailure("Код OpenAI истёк. Начните вход заново.")
        }
        val payload = JSONObject()
            .put("device_auth_id", login.deviceAuthId)
            .put("user_code", login.userCode)
        val request = Request.Builder()
            .url("$authBaseUrl/api/accounts/deviceauth/token")
            .header("Accept", "application/json")
            .header("User-Agent", "OmniChat-Android/$CLIENT_VERSION")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (response.code == 403 || response.code == 404) return@withContext null
            if (!response.isSuccessful) throw openAiError(response.code, raw, "OpenAI device login")
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw ApiFailure("OpenAI вернул некорректный ответ авторизации")
            }
            val authorizationCode = json.optString("authorization_code")
            val verifier = json.optString("code_verifier")
            val challenge = json.optString("code_challenge")
            if (authorizationCode.isBlank() || verifier.isBlank() || challenge.isBlank()) {
                throw ApiFailure("OpenAI не вернул полный PKCE-набор для завершения входа")
            }
            if (!pkceMatches(verifier, challenge)) {
                throw ApiFailure("OpenAI вернул несогласованные PKCE-данные. Начните вход заново.")
            }
            return@withContext exchangeAuthorizationCode(authorizationCode, verifier)
        }
    }

    suspend fun refresh(credentials: Credentials): Credentials = withContext(Dispatchers.IO) {
        require(credentials.refreshToken.isNotBlank()) { "OpenAI refresh token отсутствует" }
        // Codex OAuth token endpoint uses application/x-www-form-urlencoded for both
        // authorization-code exchange and refresh. Keep this aligned with openai/codex.
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("refresh_token", credentials.refreshToken)
            .build()
        val response = executeJson(
            Request.Builder()
                .url("$authBaseUrl/oauth/token")
                .header("Accept", "application/json")
                .header("User-Agent", "OmniChat-Android/$CLIENT_VERSION")
                .post(body)
                .build(),
            "Не удалось обновить OpenAI session"
        )
        credentialsFromTokenResponse(response, previous = credentials)
    }

    suspend fun modelCatalog(credentials: Credentials): List<String> = withContext(Dispatchers.IO) {
        val account = credentials.accountId.ifBlank { accountIdFromJwt(credentials.idToken) }
        val url = "$codexBaseUrl/models?client_version=$CLIENT_VERSION"
        val request = codexRequest(url, credentials.accessToken, account).get().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw openAiError(response.code, raw, "OpenAI Codex models")
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw ApiFailure("OpenAI Codex вернул некорректный каталог моделей")
            }
            val models = json.optJSONArray("models")
                ?: json.optJSONArray("data")
                ?: JSONArray()
            val ids = (0 until models.length()).mapNotNull { index ->
                when (val rawModel = models.opt(index)) {
                    is String -> rawModel.takeIf { it.isNotBlank() }
                    is JSONObject -> {
                        val visible = rawModel.optString("visibility")
                        val supported = if (rawModel.has("supported_in_api")) rawModel.optBoolean("supported_in_api") else true
                        if (!supported || visible.equals("hide", true) || visible.equals("none", true)) {
                            null
                        } else {
                            rawModel.optString("slug")
                                .ifBlank { rawModel.optString("model") }
                                .ifBlank { rawModel.optString("id") }
                                .ifBlank { rawModel.optString("name") }
                                .takeIf { it.isNotBlank() }
                        }
                    }
                    else -> null
                }
            }.distinct()
            if (ids.isEmpty()) throw ApiFailure("OpenAI Codex не вернул доступные модели")
            ids
        }
    }

    fun codexRequest(url: String, accessToken: String, accountId: String): Request.Builder {
        require(accessToken.isNotBlank()) { "OpenAI account не авторизован" }
        val parsed = url.toHttpUrlOrNull() ?: throw ApiFailure("Некорректный адрес OpenAI Codex")
        if (parsed.host != "chatgpt.com") throw ApiFailure("Для OpenAI Subscription разрешён только chatgpt.com")
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .apply {
                if (accountId.isNotBlank()) header("ChatGPT-Account-ID", accountId)
            }
            .header("originator", "omnichat")
            .header("session_id", UUID.randomUUID().toString())
            .header("User-Agent", "OmniChat-Android/$CLIENT_VERSION")
    }

    private fun pkceMatches(verifier: String, expectedChallenge: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val actual = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return actual == expectedChallenge
    }

    private fun exchangeAuthorizationCode(authorizationCode: String, verifier: String): Credentials {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", authorizationCode)
            .add("redirect_uri", "$authBaseUrl/deviceauth/callback")
            .add("code_verifier", verifier)
            .build()
        val response = executeJsonBlocking(
            Request.Builder()
                .url("$authBaseUrl/oauth/token")
                .post(body)
                .build(),
            "Не удалось завершить вход OpenAI"
        )
        return credentialsFromTokenResponse(response)
    }

    private fun credentialsFromTokenResponse(
        json: JSONObject,
        previous: Credentials? = null
    ): Credentials {
        val idToken = json.optString("id_token").ifBlank { previous?.idToken.orEmpty() }
        val accessToken = json.optString("access_token").ifBlank { previous?.accessToken.orEmpty() }
        val refreshToken = json.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() }
        if (accessToken.isBlank() || refreshToken.isBlank()) throw ApiFailure("OpenAI не вернул OAuth-токены")

        val accessClaims = jwtClaims(accessToken)
        val idClaims = jwtClaims(idToken)
        val nestedId = idClaims.optJSONObject(AUTH_CLAIM)
        val nestedAccess = accessClaims.optJSONObject(AUTH_CLAIM)
        val accountId = listOf(
            nestedId?.optString("chatgpt_account_id").orEmpty(),
            nestedAccess?.optString("chatgpt_account_id").orEmpty(),
            idClaims.optString("chatgpt_account_id"),
            accessClaims.optString("chatgpt_account_id"),
            firstOrganizationId(nestedId),
            firstOrganizationId(nestedAccess)
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val email = listOf(
            idClaims.optString("email"),
            accessClaims.optString("email"),
            nestedId?.optString("email").orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val plan = listOf(
            nestedId?.optString("chatgpt_plan_type").orEmpty(),
            nestedAccess?.optString("chatgpt_plan_type").orEmpty(),
            idClaims.optString("chatgpt_plan_type"),
            idClaims.optString("plan_type")
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val expSeconds = sequenceOf(accessClaims, idClaims)
            .mapNotNull { claims -> claims.opt("exp")?.toString()?.toLongOrNull() }
            .firstOrNull()
        val expiresAt = expSeconds?.times(1000L)
            ?: (System.currentTimeMillis() + DEFAULT_ACCESS_TTL_MS)

        return Credentials(
            idToken = idToken,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = expiresAt,
            accountId = accountId,
            email = email,
            plan = plan
        )
    }

    private fun firstOrganizationId(auth: JSONObject?): String {
        val organizations = auth?.optJSONArray("organizations") ?: return ""
        return organizations.optJSONObject(0)?.optString("id").orEmpty()
    }

    private fun accountIdFromJwt(jwt: String): String {
        val claims = jwtClaims(jwt)
        return claims.optJSONObject(AUTH_CLAIM)?.optString("chatgpt_account_id")
            ?.takeIf { it.isNotBlank() }
            ?: claims.optString("chatgpt_account_id")
    }

    private fun jwtClaims(jwt: String): JSONObject {
        val payload = jwt.split('.').getOrNull(1).orEmpty()
        if (payload.isBlank()) return JSONObject()
        return runCatching {
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decoded = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            JSONObject(String(decoded, Charsets.UTF_8))
        }.getOrElse { JSONObject() }
    }

    private suspend fun executeJson(request: Request, label: String): JSONObject =
        withContext(Dispatchers.IO) { executeJsonBlocking(request, label) }

    private fun executeJsonBlocking(request: Request, label: String): JSONObject {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw openAiError(response.code, raw, label)
            return runCatching { JSONObject(raw) }.getOrElse {
                throw ApiFailure("$label: сервер вернул некорректный JSON")
            }
        }
    }

    private fun openAiError(code: Int, raw: String, label: String): ApiFailure {
        val detail = runCatching {
            val json = JSONObject(raw)
            when (val error = json.opt("error")) {
                is JSONObject -> error.optString("message")
                    .ifBlank { error.optString("error_description") }
                    .ifBlank { error.optString("code") }
                is String -> error
                else -> json.optString("error_description").ifBlank { json.optString("message") }
            }
        }.getOrDefault("").take(260)

        val message = when {
            detail.isNotBlank() -> "$label: $detail (HTTP $code)"
            code == 401 -> "$label: сессия истекла или была отозвана"
            code == 403 -> "$label: аккаунту недоступен Codex"
            code == 429 -> "$label: достигнут лимит подписки"
            code in 500..599 -> "$label: сервис временно недоступен"
            else -> "$label: HTTP $code"
        }
        return ApiFailure(message, code)
    }

    companion object {
        const val PROVIDER_TYPE = "openai-codex"
        const val AUTH_BASE_URL = "https://auth.openai.com"
        const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val CLIENT_VERSION = "1.0.1"
        private const val AUTH_CLAIM = "https://api.openai.com/auth"
        private const val DEVICE_LOGIN_TTL_MS = 15 * 60 * 1000L
        private const val REFRESH_WINDOW_MS = 5 * 60 * 1000L
        private const val DEFAULT_ACCESS_TTL_MS = 60 * 60 * 1000L

        val DEFAULT_MODELS = listOf(
            "gpt-6-luna",
            "gpt-6-sol",
            "gpt-6-astra",
            "gpt-5.6-luna",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.5",
            "gpt-5.3-codex-spark"
        )
    }
}
