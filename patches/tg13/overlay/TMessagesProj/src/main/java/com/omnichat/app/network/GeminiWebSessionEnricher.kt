package com.omnichat.app.network

import android.content.Context
import com.omnichat.app.providers.ProviderCatalog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * Completes non-critical Gemini Web account/capability discovery after login has already succeeded.
 * Authentication is owned by the visible browser; enrichment can never roll back a usable session.
 */
object GeminiWebSessionEnricher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()

    fun schedule(
        context: Context,
        providerId: Long,
        credentials: GeminiWebClient.Credentials
    ) {
        if (providerId <= 0L || credentials.psid.isBlank()) return
        jobs.remove(providerId)?.cancel()
        val app = context.applicationContext
        val job = scope.launch {
            try {
                delay(900L)
                val client = GeminiWebClient(app)
                val snapshot = withTimeoutOrNull(75_000L) {
                    client.inspectAccount(credentials)
                }
                val liveCredentials = runCatching {
                    client.currentBrowserCredentials(credentials)
                }.getOrDefault(credentials)
                run {
                    val models = (listOf("gemini-auto") + snapshot?.models.orEmpty()
                        .filter { it.available }
                        .map { it.modelName })
                        .distinct()
                    // TelePremium: the connection lives in TelePremium's own AI store.
                    org.telegram.messenger.telepremium.ai.TPOmni.saveGeminiWeb(liveCredentials, models)
                    snapshot?.let {
                        GeminiWebSessionStore.save(
                            app,
                            providerId,
                            liveCredentials.authUser,
                            "",
                            it
                        )
                    }

                    // Capability discovery is a separate best-effort phase. DOM evidence can still
                    // confirm image/music/search actions even when account metadata RPCs are slow.
                    val capabilities = withTimeoutOrNull(45_000L) {
                        GeminiWebCapabilityScanner(client).scan(
                            credentials = liveCredentials,
                            mode = GeminiDiscoveryMode.FULL,
                            snapshotHint = snapshot
                        )
                    }
                    if (capabilities != null) {
                        GeminiWebSessionStore.saveCapabilities(app, providerId, capabilities)
                        GeminiWebDiagnostics.record(
                            app,
                            providerId,
                            "capability_scan",
                            capabilities.compactLabel()
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                GeminiWebDiagnostics.record(
                    app,
                    providerId,
                    "enrichment_error",
                    error.message ?: error.javaClass.simpleName
                )
            } finally {
                jobs.remove(providerId)
            }
        }
        jobs[providerId] = job
    }
}
