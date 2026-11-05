package com.omnichat.app.network

import android.content.Context
import com.omnichat.app.security.EncryptedPreferences
import com.omnichat.app.security.KeyVault
import org.json.JSONObject

/** Secure local storage and refresh helper for Cline account credentials. */
class ClineAccountRepository(
    context: Context,
    keyVault: KeyVault,
    private val client: ClineAccountClient
) {
    private val secrets = EncryptedPreferences(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        keyVault
    )

    fun read(): ClineAccountClient.Credentials? = secrets.read(KEY_CREDENTIALS)
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { decode(it) }.getOrNull() }

    fun save(credentials: ClineAccountClient.Credentials) {
        secrets.write(KEY_CREDENTIALS, encode(credentials))
    }

    fun clear() {
        secrets.clear()
    }

    suspend fun currentCredentials(): ClineAccountClient.Credentials? {
        val credentials = read() ?: return null
        if (!credentials.expiresSoon()) return credentials
        return runCatching { client.refresh(credentials) }
            .onSuccess { save(it) }
            .getOrElse { credentials }
    }

    suspend fun accountSnapshot(): ClineAccountClient.AccountSnapshot? {
        val credentials = currentCredentials() ?: return null
        return runCatching { client.fetchAccountSnapshot(credentials) }.getOrNull()
    }

    private fun encode(credentials: ClineAccountClient.Credentials): String = JSONObject()
        .put("accessToken", credentials.accessToken)
        .put("refreshToken", credentials.refreshToken)
        .put("expiresAtMs", credentials.expiresAtMs)
        .put("tokenType", credentials.tokenType)
        .put("email", credentials.email)
        .put("name", credentials.name)
        .put("subject", credentials.subject)
        .put("clineUserId", credentials.clineUserId)
        .toString()

    private fun decode(raw: String): ClineAccountClient.Credentials {
        val json = JSONObject(raw)
        return ClineAccountClient.Credentials(
            accessToken = json.getString("accessToken"),
            refreshToken = json.getString("refreshToken"),
            expiresAtMs = json.optLong("expiresAtMs", 0L),
            tokenType = json.optString("tokenType", "Bearer"),
            email = json.optString("email"),
            name = json.optString("name"),
            subject = json.optString("subject").takeIf { it.isNotBlank() },
            clineUserId = json.optString("clineUserId").takeIf { it.isNotBlank() }
        )
    }

    companion object {
        private const val PREFS_NAME = "cline_account"
        private const val KEY_CREDENTIALS = "credentials_encrypted"
    }
}
