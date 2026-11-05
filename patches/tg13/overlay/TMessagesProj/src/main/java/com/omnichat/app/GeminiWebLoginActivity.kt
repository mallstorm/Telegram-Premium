package com.omnichat.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.omnichat.app.network.GeminiWebClient
import com.omnichat.app.network.GeminiWebSessionStore
import com.omnichat.app.providers.ProviderCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray

/**
 * Lightweight one-shot sign-in for the consumer Gemini web session.
 * Uses only Android System WebView; no Gecko/Chromium engine is bundled in the APK.
 * Raw cookies never leave the process and are persisted only through KeyVault.
 */
class GeminiWebLoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var validationJob: Job? = null
    private var lastCandidate = ""
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        buildUi()
        configureWebView()
        status.text = "Войдите в Google. После открытия Gemini сессия сохранится автоматически."
        webView.loadUrl(GEMINI_URL)
    }

    override fun onDestroy() {
        validationJob?.cancel()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(10), dp(10))
        }
        header.addView(
            TextView(this).apply {
                text = "Gemini Web · вход через Google"
                textSize = 18f
                setTextColor(Color.rgb(35, 39, 43))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(Button(this).apply {
            text = "Закрыть"
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        info.addView(progress, LinearLayout.LayoutParams(dp(22), dp(22)))
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(dp(10), 0, 0, 0)
        }
        info.addView(
            status,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        webView = WebView(this)
        root.addView(header)
        root.addView(info)
        root.addView(
            webView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (completed) return
                status.text = when {
                    url?.contains("accounts.google.com", true) == true ->
                        "Войдите в Google и подтвердите аккаунт…"
                    url?.contains("gemini.google.com", true) == true -> "Открываю Gemini…"
                    else -> "Загрузка…"
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val current = url.orEmpty()
                if (!completed && isGeminiApp(current)) scheduleCapture(current)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && !completed) {
                    status.text = "Страница входа: ${error?.description ?: "ошибка загрузки"}"
                    progress.visibility = View.GONE
                }
            }
        }
    }

    private fun scheduleCapture(url: String) {
        if (url == lastCandidate && validationJob?.isActive == true) return
        lastCandidate = url
        validationJob?.cancel()
        validationJob = scope.launch {
            delay(700L)
            captureAndValidate(url)
        }
    }

    private suspend fun captureAndValidate(url: String) {
        if (completed) return
        val authUser = inferAuthUser(url)
        progress.visibility = View.VISIBLE
        status.text = "Gemini открыт · ожидаю готовность сессии…"

        var credentials: GeminiWebClient.Credentials? = null
        repeat(16) { attempt ->
            if (completed || credentials != null) return@repeat
            val candidate = readCredentials(authUser)
            if (candidate.psid.isNotBlank()) {
                credentials = candidate
            } else {
                status.text = "Получаю сессию Google… ${attempt + 1}/16"
                delay(450L)
            }
        }
        val captured = credentials
        if (captured == null) {
            status.text = "Gemini открыт, но сессионные cookies не появились. Обновите страницу или войдите снова."
            progress.visibility = View.GONE
            return
        }

        status.text = "Сессия найдена · проверяю аккаунт и модели…"
        val client = GeminiWebClient(applicationContext)
        var verified: GeminiWebClient.AccountSnapshot? = null
        var lastError: Throwable? = null

        try {
            verified = withTimeout(18_000L) {
                client.inspectAccount(captured)
            }
        } catch (t: Throwable) {
            if (t is CancellationException && t !is TimeoutCancellationException) throw t
            lastError = t
        }

        // Full account inspection also probes usage, quotas and recent chats. Those optional
        // web RPCs must never prevent login when the authenticated Gemini page is already open.
        if (verified == null) {
            status.text = "Подробная проверка задержалась · подключаю Gemini в auto-режиме…"
            val sessionAlive = try {
                withTimeout(10_000L) {
                    client.probe(captured)
                }
                true
            } catch (t: Throwable) {
                if (t is CancellationException && t !is TimeoutCancellationException) throw t
                lastError = t
                false
            }

            if (sessionAlive) {
                verified = GeminiWebClient.AccountSnapshot(
                    statusCode = null,
                    statusLabel = "Сессия активна · подробная проверка отложена",
                    tierLabel = null,
                    models = emptyList(),
                    limits = emptyList(),
                    chats = emptyList()
                )
            }
        }

        val snapshot = verified
        if (snapshot == null) {
            val reason = when (lastError) {
                is TimeoutCancellationException -> "таймаут проверки"
                else -> lastError?.message ?: "ошибка"
            }
            status.text = "Сессия найдена, но проверка не прошла: $reason"
            progress.visibility = View.GONE
            return
        }

        val liveCredentials = runCatching {
            client.currentBrowserCredentials(captured)
        }.getOrDefault(captured)
        val providerId = persist(liveCredentials, snapshot)
        GeminiWebSessionStore.save(
            applicationContext,
            providerId,
            liveCredentials.authUser,
            "",
            snapshot
        )
        CookieManager.getInstance().flush()

        completed = true
        progress.visibility = View.GONE
        status.text = if (snapshot.models.isEmpty()) {
            "Gemini Web подключён · auto-режим активен · возвращаюсь в OmniChat…"
        } else {
            "Gemini Web подключён · возвращаюсь в OmniChat…"
        }
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_PROVIDER_ID, providerId)
                .putExtra(EXTRA_AUTH_USER, liveCredentials.authUser)
                .putExtra(EXTRA_STATUS, snapshot.statusLabel)
                .putExtra(EXTRA_TIER, snapshot.tierLabel.orEmpty())
                .putExtra(EXTRA_MODEL_COUNT, snapshot.models.count { it.available })
                .putExtra(EXTRA_MUSIC_STATE, GeminiWebSessionStore.musicState(snapshot))
        )
        delay(450L)
        finish()
    }

    private suspend fun persist(
        credentials: GeminiWebClient.Credentials,
        snapshot: GeminiWebClient.AccountSnapshot
    ): Long {
        val models = (listOf("gemini-auto") + snapshot.models
            .filter { it.available }
            .map { it.modelName })
            .distinct()
        // TelePremium: stored as a TelePremium AI connection instead of OmniChat's Room table.
        return org.telegram.messenger.telepremium.ai.TPOmni.saveGeminiWeb(credentials, models)
    }

    private fun readCredentials(authUser: Int): GeminiWebClient.Credentials {
        val raw = CookieManager.getInstance().getCookie(GOOGLE_ORIGIN).orEmpty()
        val values = raw.split(';').mapNotNull { item ->
            val part = item.trim()
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
        }.toMap()
        return GeminiWebClient.Credentials(
            psid = values["__Secure-1PSID"].orEmpty(),
            psidts = values["__Secure-1PSIDTS"].orEmpty(),
            sapisid = values["SAPISID"].orEmpty(),
            authUser = authUser.coerceIn(0, 9)
        )
    }

    private fun inferAuthUser(url: String): Int {
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
        return Regex("""/u/([0-9])(?:/|$)""")
            .find(path)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 9)
            ?: 0
    }

    private fun isGeminiApp(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.host.equals("gemini.google.com", true) &&
            (uri.path.orEmpty().contains("/app") || uri.path.orEmpty() == "/")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_AUTH_USER = "auth_user"
        const val EXTRA_STATUS = "status"
        const val EXTRA_TIER = "tier"
        const val EXTRA_MODEL_COUNT = "model_count"
        const val EXTRA_MUSIC_STATE = "music_state"
        private const val GOOGLE_ORIGIN = "https://gemini.google.com"
        private const val GEMINI_URL = "https://gemini.google.com/app"
    }
}
