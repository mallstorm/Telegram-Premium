package com.omnichat.app.providers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical persistent model catalog keyed by a logical connection rather than a UI/provider row.
 *
 * Legacy aliases (Cline Billing/Pass/Free, Subscription Proxy Codex/NVIDIA) therefore resolve to
 * the same state. Credentials are intentionally not stored here.
 */
class ConnectionModelCatalogStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(providerType: String, accountScope: String = ""): ConnectionModelCatalogState {
        val raw = prefs.getString(storageKey(providerType, accountScope), null)
            ?: return ConnectionModelCatalogState()
        return runCatching {
            val json = JSONObject(raw)
            val array = json.optJSONArray("models") ?: JSONArray()
            ConnectionModelCatalogState(
                models = (0 until array.length()).mapNotNull { index ->
                    array.optString(index).trim().takeIf(String::isNotBlank)
                }.distinct(),
                origin = json.optString("origin", ORIGIN_CACHE).ifBlank { ORIGIN_CACHE },
                verifiedAtMs = json.optLong("verified_at_ms", 0L),
                lastError = json.optString("last_error", "")
            )
        }.getOrDefault(ConnectionModelCatalogState())
    }

    fun save(
        providerType: String,
        models: List<String>,
        origin: String = ORIGIN_LIVE,
        accountScope: String = ""
    ): ConnectionModelCatalogState {
        val clean = models.map(String::trim).filter(String::isNotBlank).distinct()
        val previous = read(providerType, accountScope)
        val state = ConnectionModelCatalogState(
            models = clean,
            origin = origin.ifBlank { ORIGIN_CACHE },
            verifiedAtMs = if (origin == ORIGIN_LIVE) System.currentTimeMillis() else previous.verifiedAtMs,
            lastError = ""
        )
        write(providerType, accountScope, state)
        return state
    }

    fun seedIfEmpty(
        providerType: String,
        models: List<String>,
        origin: String = ORIGIN_CACHE,
        accountScope: String = ""
    ): ConnectionModelCatalogState {
        val current = read(providerType, accountScope)
        if (current.models.isNotEmpty() || current.verifiedAtMs > 0L || current.lastError.isNotBlank()) {
            return current
        }
        return save(providerType, models, origin, accountScope)
    }

    fun markFailure(providerType: String, message: String, accountScope: String = "") {
        val current = read(providerType, accountScope)
        write(
            providerType,
            accountScope,
            current.copy(lastError = message.trim().take(500))
        )
    }

    fun clear(providerType: String, accountScope: String = "") {
        prefs.edit().remove(storageKey(providerType, accountScope)).apply()
    }

    private fun write(
        providerType: String,
        accountScope: String,
        state: ConnectionModelCatalogState
    ) {
        val json = JSONObject()
            .put("models", JSONArray(state.models))
            .put("origin", state.origin)
            .put("verified_at_ms", state.verifiedAtMs)
            .put("last_error", state.lastError)
        prefs.edit().putString(storageKey(providerType, accountScope), json.toString()).apply()
    }

    private fun storageKey(providerType: String, accountScope: String): String =
        "catalog:" + ConnectionIdentity.connectionKey(providerType, accountScope)

    companion object {
        const val ORIGIN_LIVE = "live"
        const val ORIGIN_CACHE = "cache"
        const val ORIGIN_FALLBACK = "fallback"
        private const val PREFS_NAME = "connection_model_catalog_v1"
    }
}
