package com.omnichat.app.network

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Cline account login, refresh, profile and billing API client. */
class ClineAccountClient(
    private val apiBaseUrl: String = CLINE_API_BASE_URL,
    private val workOsBaseUrl: String = WORKOS_API_BASE_URL,
    private val workOsClientId: String = WORKOS_CLIENT_ID,
    private val client: OkHttpClient = defaultClient()
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class DeviceLogin(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresInSeconds: Int,
        val pollIntervalSeconds: Int
    ) { val browserUrl: String get() = verificationUriComplete ?: verificationUri }

    data class WorkOsTokens(val accessToken: String, val refreshToken: String, val tokenType: String)

    data class Credentials(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
        val tokenType: String,
        val email: String,
        val name: String,
        val subject: String?,
        val clineUserId: String?
    ) {
        val bearerToken: String get() = formatAccountToken(accessToken)
        fun expiresSoon(nowMs: Long = System.currentTimeMillis(), bufferMs: Long = REFRESH_BUFFER_MS): Boolean =
            expiresAtMs <= nowMs + bufferMs
    }

    data class UserAccount(val id: String, val email: String, val name: String, val rawJson: String)

    data class PlanSummary(
        val displayName: String,
        val internalName: String = "",
        val description: String = ""
    )

    data class BalanceSummary(val microUsd: Long) {
        val usd: Double get() = microUsd / 1_000_000.0
    }

    data class AccountSnapshot(
        val user: UserAccount?,
        val plan: PlanSummary?,
        val balance: BalanceSummary?
    )
    data class ModelCatalog(
        val billing: List<String>,
        val pass: List<String>,
        val free: List<String>
    ) {
        val all: List<String> get() = (pass + free + billing).distinct()
    }

    suspend fun beginDeviceLogin(): DeviceLogin {
        val body = FormBody.Builder().add("client_id", workOsClientId).build()
        val json = postForm(workOsBaseUrl, "/user_management/authorize/device", body, null)
        return DeviceLogin(
            deviceCode = json.required("device_code"),
            userCode = json.required("user_code"),
            verificationUri = json.required("verification_uri"),
            verificationUriComplete = json.optString("verification_uri_complete").takeIf { it.isNotBlank() },
            expiresInSeconds = json.optInt("expires_in", 300).coerceAtLeast(1),
            pollIntervalSeconds = json.optInt("interval", 5).coerceAtLeast(1)
        )
    }

    suspend fun pollWorkOsOnce(login: DeviceLogin): WorkOsTokens? {
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("device_code", login.deviceCode)
            .add("client_id", workOsClientId)
            .build()
        val response = execute(
            Request.Builder().url(resolve(workOsBaseUrl, "/user_management/authenticate")).post(body).build(),
            allowErrorBody = true
        )
        if (response.code !in 200..299) {
            return when (response.json.optString("error")) {
                "authorization_pending", "slow_down" -> null
                "access_denied", "expired_token", "invalid_grant" -> throw ApiFailure(response.json.optString("error_description").ifBlank { "Авторизация Cline отклонена" })
                else -> throw ApiFailure(response.json.optString("error_description").ifBlank { "WorkOS не подтвердил вход Cline" })
            }
        }
        return WorkOsTokens(response.json.required("access_token"), response.json.required("refresh_token"), response.json.optString("token_type", "Bearer"))
    }

    suspend fun register(tokens: WorkOsTokens): Credentials = parseCredentials(
        postJson(apiBaseUrl, "/api/v1/auth/register", JSONObject()
            .put("accessToken", tokens.accessToken)
            .put("refreshToken", tokens.refreshToken), null)
    )

    suspend fun refresh(current: Credentials): Credentials = parseCredentials(
        postJson(apiBaseUrl, "/api/v1/auth/refresh", JSONObject()
            .put("refreshToken", current.refreshToken)
            .put("grantType", "refresh_token"), null),
        fallback = current
    )

    suspend fun validCredentials(current: Credentials): Credentials = if (current.expiresSoon()) refresh(current) else current

    suspend fun fetchMe(credentials: Credentials): UserAccount {
        val json = getJson(apiBaseUrl, "/api/v1/users/me", credentials.bearerToken)
        val data = json.optJSONObject("data") ?: json
        return UserAccount(
            id = data.optString("id").ifBlank { data.optString("clineUserId") },
            email = data.optString("email"),
            name = data.optString("name"),
            rawJson = data.toString()
        )
    }

    suspend fun fetchCurrentPlan(credentials: Credentials): PlanSummary? {
        val data = getJsonOrNull(apiBaseUrl, "/api/v1/users/me/plan", credentials.bearerToken)
            ?.optJSONObject("data") ?: return null
        val plan = data.optJSONObject("plan") ?: data
        val displayName = plan.optString("displayName")
            .ifBlank { plan.optString("name") }
            .ifBlank { data.optString("displayName") }
            .ifBlank { data.optString("name") }
        if (displayName.isBlank()) return null
        return PlanSummary(
            displayName = displayName,
            internalName = plan.optString("name"),
            description = plan.optString("desc").ifBlank { plan.optString("description") }
        )
    }

    suspend fun fetchBalance(credentials: Credentials, userId: String): BalanceSummary? {
        val payload = getJsonOrNull(
            apiBaseUrl,
            "/api/v1/users/${userId.urlSegment()}/balance",
            credentials.bearerToken
        ) ?: return null
        val data = payload.opt("data")
        val raw = when (data) {
            is JSONObject -> data.opt("balance")
            else -> data
        }
        val microUsd = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        } ?: return null
        return BalanceSummary(microUsd)
    }

    suspend fun fetchAccountSnapshot(credentials: Credentials): AccountSnapshot {
        val fresh = validCredentials(credentials)
        val user = runCatching { fetchMe(fresh) }.getOrNull()
        return AccountSnapshot(
            user = user,
            plan = runCatching { fetchCurrentPlan(fresh) }.getOrNull(),
            balance = user?.id?.takeIf { it.isNotBlank() }
                ?.let { runCatching { fetchBalance(fresh, it) }.getOrNull() }
        )
    }

    suspend fun models(credentials: Credentials): List<String> = modelCatalog(credentials).all

    suspend fun modelCatalog(credentials: Credentials): ModelCatalog {
        val fresh = validCredentials(credentials)
        val genericIds = runCatching {
            extractModelIds(getJson(CLINE_OPENAI_BASE_URL, "/models", fresh.bearerToken))
        }.getOrDefault(emptyList()).distinct()

        // Cline's generic /models catalog is broader than the models exposed by the
        // signed-in Cline surface. The live recommendation endpoint is the source of
        // truth for ClinePass and free rotation, including free IDs without :free.
        val recommended = getJsonOrNull(
            apiBaseUrl,
            "/api/v1/ai/cline/recommended-models",
            fresh.bearerToken
        )
        val livePass = extractModelIds(recommended?.opt("clinePass"))
        val liveFree = extractModelIds(recommended?.opt("free"))

        val pass = livePass
            .ifEmpty { genericIds.filter(::isPassModel) }
            .ifEmpty { DEFAULT_PASS_MODELS }
            .distinct()
        val free = liveFree
            .ifEmpty { genericIds.filter(::isFreeModel) }
            .ifEmpty { DEFAULT_FREE_MODELS }
            .distinct()

        val reserved = (pass + free).toSet()
        val billing = genericIds
            .filterNot { it in reserved || isPassModel(it) || isFreeModel(it) }
            .ifEmpty { DEFAULT_BILLING_MODELS }
            .distinct()

        return ModelCatalog(
            billing = billing.sortedWith { left, right -> modelSort(left, right) },
            pass = pass.sortedWith { left, right -> modelSort(left, right) },
            free = free.sortedWith { left, right -> modelSort(left, right) }
        )
    }

    private fun extractModelIds(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            when (val raw = value.opt(index)) {
                is JSONObject -> raw.optString("id")
                    .ifBlank { raw.optString("model") }
                    .ifBlank { raw.optString("name") }
                    .takeIf { it.isNotBlank() }
                is String -> raw.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        is JSONObject -> {
            val arrays = listOfNotNull(value.optJSONArray("data"), value.optJSONArray("models"))
            val fromArrays = arrays.flatMap { extractModelIds(it) }
            if (fromArrays.isNotEmpty()) fromArrays else value.keys().asSequence()
                .filter { key -> value.opt(key) is JSONObject || key.contains("/") || key.endsWith(":free") }
                .toList()
        }
        is String -> listOf(value).filter { it.isNotBlank() }
        else -> emptyList()
    }

    private fun isPassModel(id: String): Boolean = id.startsWith("cline-pass/")
    private fun isFreeModel(id: String): Boolean =
        id.startsWith("cline-free/") ||
            id.endsWith(":free") ||
            id.contains("/free/", ignoreCase = true) ||
            id == "deepseek/deepseek-v4-flash"

    private fun modelSort(left: String, right: String): Int {
        fun rank(id: String): Int = when {
            "mimo-v2.6-flash" in id -> 0
            "glm" in id.lowercase() -> 1
            "kimi" in id.lowercase() -> 2
            "deepseek" in id.lowercase() -> 3
            "qwen" in id.lowercase() -> 4
            else -> 9
        }
        return compareValuesBy(left, right, ::rank, { it.lowercase() })
    }

    private fun parseCredentials(payload: JSONObject, fallback: Credentials? = null): Credentials {
        val data = payload.optJSONObject("data") ?: payload
        val user = data.optJSONObject("userInfo") ?: JSONObject()
        val refreshToken = data.optString("refreshToken").ifBlank { fallback?.refreshToken.orEmpty() }
        if (refreshToken.isBlank()) throw ApiFailure("Cline не вернул refresh token")
        return Credentials(
            accessToken = data.required("accessToken"),
            refreshToken = refreshToken,
            expiresAtMs = data.optString("expiresAt").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() }
                ?: fallback?.expiresAtMs ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)),
            tokenType = data.optString("tokenType").ifBlank { fallback?.tokenType ?: "Bearer" },
            email = user.optString("email").ifBlank { fallback?.email.orEmpty() },
            name = user.optString("name").ifBlank { fallback?.name.orEmpty() },
            subject = user.optString("subject").ifBlank { fallback?.subject },
            clineUserId = user.optString("clineUserId").ifBlank { fallback?.clineUserId }
        )
    }

    private suspend fun getJson(base: String, path: String, bearer: String): JSONObject =
        execute(Request.Builder().url(resolve(base, path)).header("Authorization", "Bearer $bearer").get().build()).json

    private suspend fun getJsonOrNull(base: String, path: String, bearer: String): JSONObject? =
        runCatching { getJson(base, path, bearer) }.getOrNull()

    private suspend fun postForm(base: String, path: String, body: FormBody, bearer: String?): JSONObject =
        execute(Request.Builder().url(resolve(base, path)).apply { bearer?.let { header("Authorization", "Bearer $it") } }.post(body).build()).json

    private suspend fun postJson(base: String, path: String, body: JSONObject, bearer: String?): JSONObject =
        execute(Request.Builder().url(resolve(base, path)).header("Content-Type", "application/json").apply {
            bearer?.let { header("Authorization", "Bearer $it") }
        }.post(body.toString().toRequestBody(jsonType)).build()).json

    private fun resolve(base: String, path: String): HttpUrl {
        val root = base.trim().trimEnd('/').toHttpUrlOrNull() ?: throw ApiFailure("Некорректный адрес Cline API")
        if (root.scheme != "https" && root.host != "127.0.0.1" && root.host != "localhost") throw ApiFailure("Cline account требует HTTPS")
        val builder = root.newBuilder()
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach(builder::addPathSegment)
        return builder.build()
    }

    private suspend fun execute(request: Request, allowErrorBody: Boolean = false): JsonResponse = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = raw.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            JsonResponse(response.code, json, raw).also { if (!allowErrorBody) it.requireSuccess() }
        }
    }

    private data class JsonResponse(val code: Int, val json: JSONObject, val raw: String) {
        fun requireSuccess() {
            if (code in 200..299) return
            val message = json.optString("error").ifBlank { json.optString("error_description").ifBlank { raw.take(180).ifBlank { "HTTP $code" } } }
            throw ApiFailure("Cline account: $message")
        }
    }

    private fun JSONObject.required(name: String): String = optString(name).takeIf { it.isNotBlank() }
        ?: throw ApiFailure("Cline account: отсутствует поле $name")

    private fun String.urlSegment(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        const val CLINE_API_BASE_URL = "https://api.cline.bot"
        const val CLINE_OPENAI_BASE_URL = "https://api.cline.bot/api/v1"
        const val WORKOS_API_BASE_URL = "https://api.workos.com"
        const val WORKOS_CLIENT_ID = "client_01K3A541FN8TA3EPPHTD2325AR"
        const val WORKOS_TOKEN_PREFIX = "workos:"
        const val REFRESH_BUFFER_MS = 5 * 60 * 1000L

        val DEFAULT_PASS_MODELS = listOf(
            "cline-pass/mimo-v2.6-flash",
            "cline-pass/glm-5.3",
            "cline-pass/glm-5.3-flash",
            "cline-pass/kimi-k3",
            "cline-pass/deepseek-v4-pro",
            "cline-pass/deepseek-v4.1-flash",
            "cline-pass/mimo-v2.5",
            "cline-pass/mimo-v2.5-pro",
            "cline-pass/minimax-m3",
            "cline-pass/qwen3.8-max",
            "cline-pass/qwen3.7-max",
            "cline-pass/qwen3.7-plus"
        )

        val DEFAULT_FREE_MODELS = listOf(
            "deepseek/deepseek-v4-flash",
            "cline-free/deepseek-v4.1-flash",
            "cline-free/mimo-v2.6-flash",
            "cline-free/glm-5.3-flash",
            "cline-free/qwen3.8-flash"
        )

        val DEFAULT_BILLING_MODELS = listOf(
            "anthropic/claude-sonnet-4-5",
            "openai/gpt-5.5",
            "google/gemini-3.0-pro",
            "deepseek/deepseek-v4.1",
            "zai/glm-5.3",
            "moonshotai/kimi-k3",
            "qwen/qwen3.8-max"
        )

        fun formatAccountToken(token: String): String =
            token.trim().let { if (it.lowercase().startsWith(WORKOS_TOKEN_PREFIX)) it else WORKOS_TOKEN_PREFIX + it }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
