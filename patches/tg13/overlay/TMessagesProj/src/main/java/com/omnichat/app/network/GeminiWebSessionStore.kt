package com.omnichat.app.network

import android.content.Context
import org.json.JSONObject

/** Non-secret status metadata for the encrypted consumer Gemini Web session. */
data class GeminiWebSessionMeta(
    val providerId: Long = 0L,
    val authUser: Int = 0,
    val accountLabel: String = "",
    val statusLabel: String = "",
    val tierLabel: String = "",
    val modelCount: Int = 0,
    val checkedAtMs: Long = 0L,
    val musicState: String = "unknown",
    val confidence: GeminiSessionConfidence = GeminiSessionConfidence.NONE,
    val capabilities: GeminiWebCapabilities? = null
) {
    val musicLabel: String get() {
        return when (capabilities?.musicGeneration) {
            GeminiCapabilityState.AVAILABLE -> "доступно"
            GeminiCapabilityState.UNAVAILABLE -> "недоступно для этой сессии"
            GeminiCapabilityState.TEMPORARILY_UNAVAILABLE -> "временно недоступно"
            GeminiCapabilityState.UNKNOWN, null -> when (musicState) {
                "available_pro" -> "доступно · полный трек через Pro"
                "available" -> "доступно · Pro не обнаружен"
                "unavailable" -> "недоступно для этой сессии"
                else -> "требуется проверка"
            }
        }
    }

    val capabilitySummary: String
        get() = capabilities?.compactLabel().orEmpty().ifBlank { "Chat · Files" }
}

object GeminiWebSessionStore {
    private const val PREFS = "gemini_web_session_meta"

    private fun key(providerId: Long) = "provider_$providerId"

    private fun readJson(context: Context, providerId: Long): JSONObject? {
        if (providerId <= 0L) return null
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(providerId), null)
            ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun writeJson(context: Context, providerId: Long, json: JSONObject) {
        if (providerId <= 0L) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(providerId), json.toString())
            .apply()
    }

    fun read(context: Context, providerId: Long): GeminiWebSessionMeta? {
        val json = readJson(context, providerId) ?: return null
        return runCatching {
            val capabilities = GeminiWebCapabilities.fromJson(json.optJSONObject("capabilities"))
            GeminiWebSessionMeta(
                providerId = providerId,
                authUser = json.optInt("authUser", 0).coerceIn(0, 9),
                accountLabel = json.optString("accountLabel"),
                statusLabel = json.optString("statusLabel"),
                tierLabel = json.optString("tierLabel"),
                modelCount = json.optInt("modelCount", 0).coerceAtLeast(0),
                checkedAtMs = json.optLong("checkedAtMs", 0L).coerceAtLeast(0L),
                musicState = json.optString("musicState", "unknown"),
                confidence = capabilities?.confidence ?: runCatching {
                    GeminiSessionConfidence.valueOf(
                        json.optString("confidence", GeminiSessionConfidence.NONE.name)
                    )
                }.getOrDefault(GeminiSessionConfidence.NONE),
                capabilities = capabilities
            )
        }.getOrNull()
    }

    fun save(
        context: Context,
        providerId: Long,
        authUser: Int,
        accountLabel: String,
        snapshot: GeminiWebClient.AccountSnapshot,
        checkedAtMs: Long = System.currentTimeMillis()
    ) {
        val previous = readJson(context, providerId)
        val json = JSONObject()
            .put("authUser", authUser.coerceIn(0, 9))
            .put("accountLabel", accountLabel.take(160))
            .put("statusLabel", snapshot.statusLabel.take(180))
            .put("tierLabel", snapshot.tierLabel.orEmpty().take(80))
            .put("modelCount", snapshot.models.count { it.available })
            .put("checkedAtMs", checkedAtMs)
            .put("musicState", musicState(snapshot))
            .put(
                "confidence",
                if (snapshot.statusCode == 1000) GeminiSessionConfidence.RPC_AUTHENTICATED.name
                else GeminiSessionConfidence.BROWSER_AUTHENTICATED.name
            )
        previous?.optJSONObject("capabilities")?.let { json.put("capabilities", it) }
        writeJson(context, providerId, json)
    }

    fun saveCapabilities(
        context: Context,
        providerId: Long,
        capabilities: GeminiWebCapabilities
    ) {
        val json = readJson(context, providerId) ?: JSONObject()
        json.put("capabilities", capabilities.toJson())
            .put("confidence", capabilities.confidence.name)
            .put("checkedAtMs", capabilities.lastCheckedAt)
            .put("modelCount", capabilities.availableModels.count { it != "gemini-auto" })
        capabilities.accountTier?.let { json.put("tierLabel", it.take(80)) }
        writeJson(context, providerId, json)
    }

    fun clear(context: Context, providerId: Long) {
        if (providerId <= 0L) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(providerId))
            .apply()
    }

    fun musicState(snapshot: GeminiWebClient.AccountSnapshot): String {
        val restricted = snapshot.statusCode?.let {
            it in setOf(1016, 1021, 1054, 1057, 1060)
        } == true
        if (restricted) return "unavailable"
        val available = snapshot.models.filter { it.available }
        if (snapshot.statusCode != null && snapshot.statusCode != 1000 && available.isEmpty()) {
            return "unknown"
        }
        if (available.isEmpty()) return "unknown"
        val hasPro = available.any {
            it.modelName.contains("pro", ignoreCase = true) ||
                it.displayName.contains("pro", ignoreCase = true)
        }
        return if (hasPro) "available_pro" else "available"
    }
}
