package com.omnichat.app.network

import android.content.Context
import android.net.Uri
import com.omnichat.app.security.EncryptedPreferences
import com.omnichat.app.security.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class OpenRouterAccountRepository(context: Context, vault: KeyVault) {
    data class Login(val url: String, val verifier: String, val state: String)
    data class AccountSnapshot(
        val label: String,
        val freeTier: Boolean,
        val usage: Double?,
        val limit: Double?,
        val limitRemaining: Double?,
        val expiresAt: String?
    )

    private val secrets = EncryptedPreferences(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        vault
    )
    private val http = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .build()
    private val oauthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loopbackServer: ServerSocket? = null

    private fun openLoopbackServer(): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
        soTimeout = LOOPBACK_TIMEOUT_MS
    }

    fun beginLogin(_callbackUrl: String): Login {
        closeLoopbackServer()

        val pkce = OAuthPkce.create(verifierBytes = 32, stateBytes = 24)
        val server = openLoopbackServer()
        loopbackServer = server
        val callbackUrl = "http://127.0.0.1:${server.localPort}/callback"
        val encodedCallback = java.net.URLEncoder.encode(callbackUrl, "UTF-8")
        val encodedState = java.net.URLEncoder.encode(pkce.state, "UTF-8")
        val url = "https://openrouter.ai/auth?callback_url=$encodedCallback" +
            "&code_challenge=${pkce.challenge}&code_challenge_method=S256" +
            "&state=$encodedState"

        secrets.write(KEY_PKCE_VERIFIER, pkce.verifier)
        secrets.write(KEY_PKCE_STATE, pkce.state)

        oauthScope.launch { relayLoopbackCallback(server) }
        return Login(url, pkce.verifier, pkce.state)
    }

    private suspend fun relayLoopbackCallback(server: ServerSocket) {
        try {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val requestLine = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine().orEmpty()
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                if (target.isBlank()) {
                    sendPage(socket, "OpenRouter OAuth", "Некорректный callback от браузера.", null)
                    return
                }

                val callbackUri = if (target.startsWith("http://") || target.startsWith("https://")) {
                    Uri.parse(target)
                } else {
                    Uri.parse("http://127.0.0.1$target")
                }

                val error = callbackUri.getQueryParameter("error")
                if (!error.isNullOrBlank()) {
                    clearPendingLoginIfCurrent(server)
                    sendPage(socket, "OpenRouter OAuth", "Авторизация отменена: $error", null)
                    return
                }

                val returnedState = callbackUri.getQueryParameter("state")
                if (!matchesPendingState(returnedState)) {
                    clearPendingLoginIfCurrent(server)
                    sendPage(socket, "OpenRouter OAuth", "Проверка state не пройдена. Запустите вход заново.", null)
                    return
                }

                val code = callbackUri.getQueryParameter("code")
                if (code.isNullOrBlank()) {
                    sendPage(socket, "OpenRouter OAuth", "OpenRouter не вернул код авторизации.", null)
                    return
                }

                val appUri = Uri.Builder()
                    .scheme("omnichat")
                    .authority("openrouter")
                    .path("/oauth")
                    .appendQueryParameter("code", code)
                    .appendQueryParameter("state", returnedState)
                    .build()
                    .toString()

                sendPage(
                    socket,
                    "OpenRouter подключается",
                    "Авторизация подтверждена. Возвращаемся в OmniChat…",
                    appUri
                )
            }
        } catch (_: SocketTimeoutException) {
            clearPendingLoginIfCurrent(server)
        } catch (_: Exception) {
            clearPendingLoginIfCurrent(server)
        } finally {
            if (loopbackServer === server) loopbackServer = null
            runCatching { server.close() }
        }
    }

    private fun clearPendingLoginIfCurrent(server: ServerSocket) {
        if (loopbackServer !== server) return
        loopbackServer = null
        runCatching { server.close() }
        secrets.remove(KEY_PKCE_VERIFIER, KEY_PKCE_STATE)
    }

    private fun sendPage(socket: Socket, title: String, message: String, appUri: String?) {
        val escapedTitle = htmlEscape(title)
        val escapedMessage = htmlEscape(message)
        val redirect = appUri?.let {
            val href = htmlEscape(it)
            """
            <script>window.location.replace('${it.replace("'", "%27")}');</script>
            <p><a href="$href">Вернуться в OmniChat</a></p>
            """.trimIndent()
        }.orEmpty()
        val body = """
            <!doctype html>
            <html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>$escapedTitle</title></head>
            <body style="font-family:sans-serif;padding:32px;line-height:1.5">
            <h2>$escapedTitle</h2><p>$escapedMessage</p>$redirect
            </body></html>
        """.trimIndent()
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().use { output ->
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
    }

    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun closeLoopbackServer() {
        val server = loopbackServer
        loopbackServer = null
        runCatching { server?.close() }
    }

    fun pendingVerifier(): String = secrets.read(KEY_PKCE_VERIFIER)

    fun pendingState(): String = secrets.read(KEY_PKCE_STATE)

    fun matchesPendingState(returnedState: String?): Boolean =
        OAuthPkce.matchesState(pendingState(), returnedState)

    fun clearPendingLogin() {
        closeLoopbackServer()
        secrets.remove(KEY_PKCE_VERIFIER, KEY_PKCE_STATE)
    }

    suspend fun exchange(code: String, verifier: String = pendingVerifier()): String = withContext(Dispatchers.IO) {
        require(verifier.isNotBlank()) { "OpenRouter OAuth: потерян PKCE verifier, повторите вход" }
        val body = JSONObject()
            .put("code", code)
            .put("code_verifier", verifier)
            .put("code_challenge_method", "S256")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/auth/keys")
            .post(body)
            .header("Content-Type", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiFailure("OpenRouter OAuth: HTTP ${response.code}: ${raw.take(400)}")
            val key = JSONObject(raw).optString("key").ifBlank { JSONObject(raw).optString("api_key") }
            if (key.isBlank()) throw ApiFailure("OpenRouter OAuth не вернул API key")
            secrets.write(KEY_API_KEY, key)
            secrets.remove(KEY_PKCE_VERIFIER, KEY_PKCE_STATE)
            key
        }
    }

    suspend fun accountSnapshot(key: String = readKey()): AccountSnapshot = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "OpenRouter account не подключён" }
        val request = Request.Builder().url("https://openrouter.ai/api/v1/key")
            .header("Authorization", "Bearer $key").get().build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiFailure("OpenRouter account: HTTP ${response.code}: ${raw.take(400)}")
            val data = JSONObject(raw).optJSONObject("data") ?: throw ApiFailure("OpenRouter account: неверный ответ /key")
            fun nullableDouble(name: String): Double? =
                if (!data.has(name) || data.isNull(name)) null else data.optDouble(name).takeUnless { it.isNaN() }
            AccountSnapshot(
                label = data.optString("label"),
                freeTier = data.optBoolean("is_free_tier", false),
                usage = nullableDouble("usage"),
                limit = nullableDouble("limit"),
                limitRemaining = nullableDouble("limit_remaining"),
                expiresAt = data.optString("expires_at").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    fun readKey(): String = secrets.read(KEY_API_KEY)

    fun clear() {
        closeLoopbackServer()
        secrets.clear()
    }

    companion object {
        const val CALLBACK = "omnichat://openrouter/oauth"
        private const val PREFS_NAME = "openrouter_account"
        private const val KEY_PKCE_VERIFIER = "pkce_verifier"
        private const val KEY_PKCE_STATE = "pkce_state"
        private const val KEY_API_KEY = "key"
        private const val LOOPBACK_TIMEOUT_MS = 180_000
    }
}
