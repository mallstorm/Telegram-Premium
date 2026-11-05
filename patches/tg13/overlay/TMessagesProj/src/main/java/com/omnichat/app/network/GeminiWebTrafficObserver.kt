package com.omnichat.app.network

import android.net.Uri
import java.util.ArrayDeque

/**
 * Process-local, non-secret protocol telemetry for the consumer Gemini WebView.
 * Query strings, cookies, headers and request bodies are never retained. Only RPC ids plus the
 * endpoint family are kept so protocol drift can be diagnosed without recording user content.
 */
object GeminiWebTrafficObserver {
    data class Event(
        val atMs: Long,
        val method: String,
        val host: String,
        val path: String,
        val rpcIds: List<String>
    )

    private const val MAX_EVENTS = 96
    private val lock = Any()
    private val events = ArrayDeque<Event>(MAX_EVENTS)

    fun observe(url: String?, method: String?) {
        val value = url?.takeIf { it.startsWith("https://") } ?: return
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
        val host = uri.host.orEmpty().lowercase()
        if (host != "gemini.google.com" && !host.endsWith(".google.com") &&
            !host.endsWith(".googleapis.com")
        ) return

        val rpcIds = runCatching { uri.getQueryParameter("rpcids") }
            .getOrNull()
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter { it.matches(Regex("[A-Za-z0-9_-]{2,32}")) }
            .take(12)

        val event = Event(
            atMs = System.currentTimeMillis(),
            method = method.orEmpty().uppercase().take(12),
            host = host.take(120),
            path = uri.path.orEmpty().take(220),
            rpcIds = rpcIds
        )
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(event)
        }
    }

    fun snapshot(): List<Event> = synchronized(lock) { events.toList() }

    fun recentRpcIds(): Set<String> = synchronized(lock) {
        events.asSequence().flatMap { it.rpcIds.asSequence() }.toSet()
    }

    fun clear() = synchronized(lock) { events.clear() }
}
