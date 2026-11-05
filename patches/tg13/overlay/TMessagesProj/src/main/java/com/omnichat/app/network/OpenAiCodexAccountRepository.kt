package com.omnichat.app.network

import android.content.Context
import com.omnichat.app.security.EncryptedPreferences
import com.omnichat.app.security.KeyVault
import org.json.JSONObject

/** Encrypted local storage + refresh for the user's ChatGPT/Codex subscription session. */
class OpenAiCodexAccountRepository(
    context: Context,
    keyVault: KeyVault,
    private val client: OpenAiCodexAccountClient
) {
    private val secrets = EncryptedPreferences(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        keyVault
    )

    fun read(): OpenAiCodexAccountClient.Credentials? = secrets.read(KEY_CREDENTIALS)
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { decode(it) }.getOrNull() }

    fun save(credentials: OpenAiCodexAccountClient.Credentials) {
        secrets.write(KEY_CREDENTIALS, encode(credentials))
    }

    fun clear() {
        secrets.clear()
    }

    suspend fun currentCredentials(): OpenAiCodexAccountClient.Credentials? {
        val credentials = read() ?: return null
        if (!credentials.expiresSoon()) return credentials
        return try {
            client.refresh(credentials).also(::save)
        } catch (error: Exception) {
            // A still-valid token may survive a transient refresh outage. Never reuse an expired token.
            if (credentials.expiresAtMs > System.currentTimeMillis() + 30_000L) credentials else throw error
        }
    }

    suspend fun models(): List<String> {
        val credentials = currentCredentials() ?: throw ApiFailure("Сначала войдите в OpenAI ChatGPT Subscription")
        return client.modelCatalog(credentials)
    }

    private fun encode(credentials: OpenAiCodexAccountClient.Credentials): String = JSONObject()
        .put("idToken", credentials.idToken)
        .put("accessToken", credentials.accessToken)
        .put("refreshToken", credentials.refreshToken)
        .put("expiresAtMs", credentials.expiresAtMs)
        .put("accountId", credentials.accountId)
        .put("email", credentials.email)
        .put("plan", credentials.plan)
        .toString()

    private fun decode(raw: String): OpenAiCodexAccountClient.Credentials {
        val json = JSONObject(raw)
        return OpenAiCodexAccountClient.Credentials(
            idToken = json.optString("idToken"),
            accessToken = json.getString("accessToken"),
            refreshToken = json.getString("refreshToken"),
            expiresAtMs = json.optLong("expiresAtMs", 0L),
            accountId = json.optString("accountId"),
            email = json.optString("email"),
            plan = json.optString("plan")
        )
    }

    companion object {
        private const val PREFS_NAME = "openai_codex_account"
        private const val KEY_CREDENTIALS = "credentials_encrypted"
    }
}
