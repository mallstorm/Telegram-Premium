package com.omnichat.app.network

import android.content.Context
import android.os.Build
import com.omnichat.app.BuildConfig
import com.omnichat.app.security.EncryptedPreferences
import com.omnichat.app.security.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Kimi Code OAuth Device Authorization Grant.
 * Uses Kimi Code's published public client ID and keeps refresh credentials encrypted by Android Keystore.
 */
class KimiCodeOAuthRepository(
    context: Context,
    vault: KeyVault,
    private val authBaseUrl: String = "https://auth.kimi.com"
) {
    data class DeviceLogin(
        val userCode: String,
        val deviceCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresAt: Long,
        val intervalSeconds: Int
    )

    data class Credentials(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val tokenType: String
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secrets = EncryptedPreferences(prefs, vault)
    private val client = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val value = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, value).apply()
        return value
    }

    private fun request(url: String, body: FormBody): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("X-Msh-Platform", "kimi_cli")
        .header("X-Msh-Version", BuildConfig.VERSION_NAME)
        .header("X-Msh-Device-Name", Build.MODEL.orEmpty().take(120))
        .header("X-Msh-Device-Model", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120))
        .header("X-Msh-Os-Version", "Android ${Build.VERSION.RELEASE}".take(120))
        .header("X-Msh-Device-Id", deviceId())
        .post(body)
        .build()

    private fun readJson(request: Request): Pair<Int, JSONObject> =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            response.code to json
        }

    suspend fun beginLogin(): DeviceLogin = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .build()
        val (status, json) = readJson(
            request("${authBaseUrl.trimEnd('/')}/api/oauth/device_authorization", body)
        )
        if (status != 200) throw ApiFailure(
            json.optString("error_description").ifBlank {
                json.optString("error").ifBlank { "Kimi Code OAuth: HTTP $status" }
            }
        )
        val userCode = json.optString("user_code")
        val deviceCode = json.optString("device_code")
        val verificationUri = json.optString("verification_uri")
        val complete = json.optString("verification_uri_complete").ifBlank { verificationUri }
        val expiresIn = json.optLong("expires_in", 600L).coerceAtLeast(30L)
        val interval = json.optInt("interval", 5).coerceIn(2, 30)
        if (userCode.isBlank() || deviceCode.isBlank() || complete.isBlank()) {
            throw ApiFailure("Kimi Code OAuth вернул неполный ответ")
        }
        DeviceLogin(
            userCode = userCode,
            deviceCode = deviceCode,
            verificationUri = verificationUri,
            verificationUriComplete = complete,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
            intervalSeconds = interval
        )
    }

    suspend fun waitForLogin(login: DeviceLogin): Credentials {
        var interval = login.intervalSeconds.toLong()
        while (System.currentTimeMillis() < login.expiresAt) {
            delay(interval * 1000L)
            val result = withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("device_code", login.deviceCode)
                    .add("grant_type", DEVICE_GRANT)
                    .build()
                readJson(request("${authBaseUrl.trimEnd('/')}/api/oauth/token", body))
            }
            val status = result.first
            val json = result.second
            if (status == 200) {
                val credentials = credentialsFromToken(json, fallbackRefresh = "")
                save(credentials)
                return credentials
            }
            val error = json.optString("error")
            when (error) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = DeviceFlowPollBackoff.nextPenaltySeconds(error, interval)
                "expired_token" -> throw ApiFailure("Код входа Kimi Code истёк. Запустите вход ещё раз.")
                "access_denied" -> throw ApiFailure("Вход Kimi Code отклонён пользователем.")
                else -> if (status >= 400 && status != 428) {
                    throw ApiFailure(
                        json.optString("error_description").ifBlank {
                            error.ifBlank { "Kimi Code OAuth: HTTP $status" }
                        }
                    )
                }
            }
        }
        throw ApiFailure("Истекло время ожидания входа Kimi Code")
    }

    suspend fun currentAccessToken(): String = withContext(Dispatchers.IO) {
        val current = read() ?: return@withContext ""
        if (System.currentTimeMillis() < current.expiresAt - 60_000L) return@withContext current.accessToken
        runCatching { refresh(current).accessToken }.getOrElse { "" }
    }

    fun hasCredentials(): Boolean = read() != null

    fun clear() {
        secrets.remove(KEY_CREDENTIALS)
    }

    private fun read(): Credentials? = secrets.read(KEY_CREDENTIALS)
        .takeIf { it.isNotBlank() }
        ?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                Credentials(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.getString("refresh_token"),
                    expiresAt = json.getLong("expires_at"),
                    tokenType = json.optString("token_type", "Bearer")
                )
            }.getOrNull()
        }

    private fun save(credentials: Credentials) {
        val json = JSONObject()
            .put("access_token", credentials.accessToken)
            .put("refresh_token", credentials.refreshToken)
            .put("expires_at", credentials.expiresAt)
            .put("token_type", credentials.tokenType)
        secrets.write(KEY_CREDENTIALS, json.toString())
    }

    private fun credentialsFromToken(json: JSONObject, fallbackRefresh: String): Credentials {
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token").ifBlank { fallbackRefresh }
        val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        if (access.isBlank() || refresh.isBlank()) throw ApiFailure("Kimi Code OAuth не вернул токены")
        return Credentials(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
            tokenType = json.optString("token_type", "Bearer")
        )
    }

    private fun refresh(current: Credentials): Credentials {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", current.refreshToken)
            .build()
        val (status, json) = readJson(
            request("${authBaseUrl.trimEnd('/')}/api/oauth/token", body)
        )
        if (status != 200) {
            if (json.optString("error") == "invalid_grant") clear()
            throw ApiFailure(
                json.optString("error_description").ifBlank {
                    json.optString("error").ifBlank { "Не удалось обновить Kimi Code OAuth" }
                }
            )
        }
        val next = credentialsFromToken(json, current.refreshToken)
        save(next)
        return next
    }

    companion object {
        private const val PREFS_NAME = "kimi_code_oauth"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CREDENTIALS = "credentials"
        const val CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    }
}
