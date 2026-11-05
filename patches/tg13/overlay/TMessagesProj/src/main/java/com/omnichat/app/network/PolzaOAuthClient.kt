package com.omnichat.app.network

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Polza.AI browser login used by the public Polza IDE:
 * Authorization Code + PKCE (S256) with a loopback callback.
 * The token exchange returns a normal Polza API key, so no long-lived OAuth token is required.
 */
class PolzaOAuthClient {
    data class LoginSession(
        val authUrl: String,
        internal val verifier: String,
        internal val state: String,
        internal val callbackUrl: String,
        internal val server: ServerSocket,
        internal val expiresAtMs: Long
    )

    private val client = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun beginLogin(): LoginSession {
        val pkce = OAuthPkce.create(
            verifierBytes = 48,
            stateBytes = 16,
            stateEncoding = OAuthPkce.StateEncoding.HEX,
            verifierMaxChars = 64
        )
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 1000
        }
        val callback = "http://127.0.0.1:${server.localPort}/callback"
        val url = AUTH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("callback_url", callback)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", pkce.state)
            .addQueryParameter("app_name", "OmniChat")
            .build()
            .toString()
        return LoginSession(
            authUrl = url,
            verifier = pkce.verifier,
            state = pkce.state,
            callbackUrl = callback,
            server = server,
            expiresAtMs = System.currentTimeMillis() + TIMEOUT_MS
        )
    }

    suspend fun awaitApiKey(session: LoginSession): String = withContext(Dispatchers.IO) {
        try {
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < session.expiresAtMs) {
                val socket = try {
                    session.server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                socket.use {
                    val callback = readCallback(it)
                    if (callback == null) {
                        writeHtml(it, 404, "Страница не найдена")
                        return@use
                    }
                    val error = callback.getQueryParameter("error")
                    if (!error.isNullOrBlank()) {
                        writeHtml(it, 200, "Доступ отклонён. Можно закрыть эту вкладку.")
                        throw ApiFailure("Вход Polza.AI отклонён пользователем")
                    }
                    val code = callback.getQueryParameter("code").orEmpty()
                    val returnedState = callback.getQueryParameter("state")
                    if (code.isBlank() || !OAuthPkce.matchesState(session.state, returnedState)) {
                        writeHtml(it, 200, "Некорректный ответ авторизации. Вернитесь в OmniChat.")
                        throw ApiFailure("Polza.AI вернул неверный state или пустой code")
                    }
                    val key = exchange(code, session)
                    writeHtml(it, 200, "Авторизация Polza.AI успешна. Возвращайтесь в OmniChat.")
                    return@withContext key
                }
            }
            throw ApiFailure("Истекло время ожидания входа Polza.AI")
        } finally {
            runCatching { session.server.close() }
        }
    }

    fun cancel(session: LoginSession?) {
        runCatching { session?.server?.close() }
    }

    private fun readCallback(socket: Socket): Uri? {
        socket.soTimeout = 3000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") return null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val target = parts[1]
        return Uri.parse("http://127.0.0.1$target")
            .takeIf { it.path == "/callback" }
    }

    private fun writeHtml(socket: Socket, status: Int, message: String) {
        val body = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="font-family:sans-serif;padding:32px"><h2>$message</h2></body></html>"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        writer.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n")
        writer.write("Content-Type: text/html; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(body)
        writer.flush()
    }

    private fun exchange(code: String, session: LoginSession): String {
        val payload = JSONObject()
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("code_verifier", session.verifier)
            .put("callback_url", session.callbackUrl)
        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiFailure("Polza.AI: ошибка обмена кода HTTP ${response.code}")
            }
            val key = runCatching { JSONObject(text).optString("key") }.getOrDefault("")
            if (key.isBlank()) throw ApiFailure("Polza.AI не вернул API-ключ после входа")
            return key
        }
    }

    companion object {
        const val AUTH_URL = "https://polza.ai/api/auth/authorize"
        const val TOKEN_URL = "https://polza.ai/api/auth/token"
        const val TIMEOUT_MS = 5 * 60 * 1000L
    }
}
