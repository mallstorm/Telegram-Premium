package com.omnichat.app.providers

import java.util.Locale

enum class GeminiThinkingMode(val label: String, internal val rank: Int) {
    MINIMAL("Минимум", 0),
    LOW("Быстрый", 1),
    MEDIUM("Сбалансированный", 2),
    HIGH("Глубокий", 3),
    TIERED("Авто", 4),
    STANDARD("Стандарт", 5)
}

data class GeminiModelVariant(
    val id: String,
    val mode: GeminiThinkingMode
)

data class GeminiModelGroup(
    val familyId: String,
    val title: String,
    val subtitle: String,
    val badgeLabel: String?,
    val variants: List<GeminiModelVariant>
)

/**
 * Presentation-only normalization for Gemini model IDs.
 *
 * API IDs are never rewritten. Only terminal reasoning/router suffixes are grouped so
 * low/medium/high variants are shown as one model family in the UI.
 */
object GeminiModelPresentation {
    const val ARGON_ID = "gemini-4-argon"

    private val modeSuffixes = listOf(
        "-minimal" to GeminiThinkingMode.MINIMAL,
        "-low" to GeminiThinkingMode.LOW,
        "-medium" to GeminiThinkingMode.MEDIUM,
        "-high" to GeminiThinkingMode.HIGH,
        "-tiered" to GeminiThinkingMode.TIERED
    )

    fun isGemini(id: String): Boolean = id.trim().startsWith("gemini-", ignoreCase = true)

    fun isArgon(id: String): Boolean = familyId(id).equals(ARGON_ID, ignoreCase = true)

    fun variant(id: String): GeminiModelVariant {
        val normalized = id.trim()
        val lower = normalized.lowercase(Locale.ROOT)
        val mode = modeSuffixes.firstOrNull { (suffix, _) -> lower.endsWith(suffix) }?.second
            ?: GeminiThinkingMode.STANDARD
        return GeminiModelVariant(normalized, mode)
    }

    fun familyId(id: String): String {
        val normalized = id.trim()
        val lower = normalized.lowercase(Locale.ROOT)
        val suffix = modeSuffixes.firstOrNull { (candidate, _) -> lower.endsWith(candidate) }?.first
        return if (suffix == null) normalized else normalized.dropLast(suffix.length)
    }

    fun displayName(id: String): String {
        val base = familyId(id)
        val mode = variant(id).mode
        val title = title(base)
        return if (mode == GeminiThinkingMode.STANDARD) title else "$title · ${mode.label}"
    }

    fun groups(models: List<String>): List<GeminiModelGroup> {
        val grouped = linkedMapOf<String, MutableList<GeminiModelVariant>>()
        models.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(::isGemini)
            .distinct()
            .forEach { id ->
                val family = familyId(id)
                grouped.getOrPut(family) { mutableListOf() } += variant(id)
            }

        return grouped.map { (family, variants) ->
            GeminiModelGroup(
                familyId = family,
                title = title(family),
                subtitle = subtitle(family),
                badgeLabel = badge(family),
                variants = variants.sortedWith(compareBy<GeminiModelVariant> { it.mode.rank }.thenBy { it.id })
            )
        }
    }

    fun title(familyId: String): String {
        val raw = familyId.trim().removePrefix("gemini-").removePrefix("Gemini-")
        if (raw.isBlank()) return "Gemini"
        val words = raw.split('-').filter(String::isNotBlank)
        val rendered = words.joinToString(" ") { token ->
            when (token.lowercase(Locale.ROOT)) {
                "flash" -> "Flash"
                "lite" -> "Lite"
                "pro" -> "Pro"
                "ultra" -> "Ultra"
                "image" -> "Image"
                "preview" -> "Preview"
                "agent" -> "Agent"
                "exp" -> "Experimental"
                "experimental" -> "Experimental"
                "native" -> "Native"
                "audio" -> "Audio"
                "live" -> "Live"
                "argon" -> "Argon"
                else -> if (token.any(Char::isDigit)) token else token.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString()
                }
            }
        }
        return "Gemini $rendered"
    }

    fun subtitle(familyId: String): String {
        val id = familyId.lowercase(Locale.ROOT)
        return when {
            id == ARGON_ID -> "Флагманская reasoning-модель · контекст до 1M токенов"
            id.contains("image") -> "Чат и генерация изображений"
            id.contains("agent") -> "Агентный режим"
            id.contains("live") || id.contains("native-audio") -> "Модель для Live и аудио"
            id.contains("flash-lite") -> "Лёгкая и экономичная модель"
            Regex("(^|-)pro($|-)").containsMatchIn(id.removePrefix("gemini-")) -> "Для сложных задач и кода"
            id.contains("flash") -> "Быстрая универсальная модель"
            else -> "Модель Google Gemini"
        }
    }

    fun badge(familyId: String): String? {
        val id = familyId.lowercase(Locale.ROOT)
        return when {
            id == ARGON_ID -> "Флагман"
            id.contains("image") -> "Изображения"
            id.contains("agent") -> "Агент"
            id.contains("flash-lite") -> "Бюджетная"
            Regex("(^|-)pro($|-)").containsMatchIn(id.removePrefix("gemini-")) -> "Макс. качество"
            else -> null
        }
    }
}
