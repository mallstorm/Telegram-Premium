package com.omnichat.app.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for Gemini consumer-web JSPB candidates.
 *
 * Gemini stores most rich-content features in candidate[12]. Low field numbers live at their
 * positional index while sparse/high fields live in the final JSONObject under their one-based
 * field number. Field values are returned exactly as encoded; callers own any feature-specific
 * wrapper traversal. This mirrors Gemini consumer get_field/get_rich_content_field semantics.
 */
object GeminiWebConsumerProtocol {
    enum class AssetKind { IMAGE, VIDEO, AUDIO }

    data class StructuredAsset(
        val url: String,
        val mime: String,
        val kind: AssetKind,
        val thumbnailUrl: String? = null,
        val generatedImageBlob: String = "",
        val generatedImagePlaceholder: String = ""
    )

    data class ResearchPlan(
        val title: String? = null,
        val steps: List<String> = emptyList(),
        val etaText: String? = null,
        val confirmPrompt: String? = null,
        val query: String? = null,
        val rawState: Int? = null,
        val responseText: String = ""
    )

    data class ResearchDocument(
        val id: String? = null,
        val title: String? = null,
        val content: String = "",
        val taskMarker: String? = null
    ) {
        val ready: Boolean get() = content.isNotBlank()
    }

    data class ParsedCandidate(
        val text: String,
        val assets: List<StructuredAsset>,
        val researchPlan: ResearchPlan?,
        val researchDocument: ResearchDocument?,
        val completionStatus: Int?,
        val candidateId: String
    )

    fun parseCandidate(candidate: JSONArray): ParsedCandidate {
        val text = stringAt(candidate, 1, 0).orEmpty()
        val assets = buildList {
            addAll(parseGeneratedImages(candidate))
            addAll(parseGeneratedVideo(candidate))
            addAll(parseGeneratedMedia(candidate))
        }.distinctBy { it.kind to it.url }

        return ParsedCandidate(
            text = text,
            assets = assets,
            researchPlan = parseResearchPlan(candidate, text),
            researchDocument = parseResearchDocument(candidate),
            completionStatus = numberAt(candidate, 8, 0)?.toInt(),
            candidateId = stringAt(candidate, 0).orEmpty()
        )
    }

    private fun parseGeneratedImages(candidate: JSONArray): List<StructuredAsset> {
        val rich = arrayAt(candidate, 12) ?: return emptyList()
        val field = richField(rich, FIELD_GENERATED_IMAGES) as? JSONArray ?: return emptyList()
        // Consumer GENERATED_IMAGES field is wrapped once; the image entries are field[0].
        val items = arrayAt(field, 0) ?: return emptyList()

        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONArray(index) ?: continue
                val url = stringAt(item, 0, 3, 3)?.takeIf(::isHttps) ?: continue
                add(
                    StructuredAsset(
                        url = url,
                        mime = mimeFromUrl(url).ifBlank { "image/jpeg" },
                        kind = AssetKind.IMAGE,
                        generatedImageBlob = stringAt(item, 0, 3, 5).orEmpty(),
                        generatedImagePlaceholder = stringAt(item, 1, 0).orEmpty().trim()
                    )
                )
            }
        }
    }

    private fun parseGeneratedVideo(candidate: JSONArray): List<StructuredAsset> {
        val rich = arrayAt(candidate, 12) ?: return emptyList()
        val field = richField(rich, FIELD_VIDEO) as? JSONArray ?: return emptyList()
        // Consumer VIDEO field: field[0][0][0] -> videoInfo; videoInfo[0][7] -> [thumb, video].
        val videoInfo = arrayAt(field, 0, 0, 0) ?: return emptyList()
        val urls = arrayAt(videoInfo, 0, 7) ?: return emptyList()
        if (urls.length() < 2) return emptyList()

        val thumbnail = urls.optString(0).takeIf(::isHttps)
        val url = urls.optString(1).takeIf(::isHttps) ?: return emptyList()
        return listOf(
            StructuredAsset(
                url = url,
                mime = mimeFromUrl(url).ifBlank { "video/mp4" },
                kind = AssetKind.VIDEO,
                thumbnailUrl = thumbnail
            )
        )
    }

    private fun parseGeneratedMedia(candidate: JSONArray): List<StructuredAsset> {
        val rich = arrayAt(candidate, 12) ?: return emptyList()
        // MEDIA is a sparse high-numbered field. get_field returns its payload directly.
        val media = richField(rich, FIELD_MEDIA) as? JSONArray ?: return emptyList()

        val mp3 = arrayAt(media, 0, 1, 7)
        val mp4 = arrayAt(media, 1, 1, 7)
        val result = mutableListOf<StructuredAsset>()

        val mp3Url = mp3?.optString(1)?.takeIf(::isHttps)
        if (mp3Url != null) {
            result += StructuredAsset(
                url = mp3Url,
                mime = mimeFromUrl(mp3Url).ifBlank { "audio/mpeg" },
                kind = AssetKind.AUDIO,
                thumbnailUrl = mp3.optString(0).takeIf(::isHttps)
            )
        }

        // Some consumer responses expose only the alternate MP4 container for generated music.
        val mp4Url = mp4?.optString(1)?.takeIf(::isHttps)
        if (mp4Url != null && mp3Url == null) {
            result += StructuredAsset(
                url = mp4Url,
                mime = "audio/mp4",
                kind = AssetKind.AUDIO,
                thumbnailUrl = mp4.optString(0).takeIf(::isHttps)
            )
        }
        return result
    }

    private fun parseResearchPlan(candidate: JSONArray, fallbackText: String): ResearchPlan? {
        val rich = arrayAt(candidate, 12) ?: return null
        val payload = sequenceOf(FIELD_RESEARCH_PLAN, FIELD_RESEARCH_PLAN_ALT)
            .mapNotNull { richField(rich, it) as? JSONArray }
            .firstOrNull() ?: return null

        val title = payload.optString(0).takeIf(String::isNotBlank)
        val stepsPayload = payload.optJSONArray(1)
        val steps = buildList {
            if (stepsPayload != null) {
                for (index in 0 until stepsPayload.length()) {
                    val step = stepsPayload.optJSONArray(index) ?: continue
                    val label = step.optString(1).takeIf(String::isNotBlank)
                    val body = step.optString(2).takeIf(String::isNotBlank)
                    when {
                        label != null && body != null -> add("$label: $body")
                        body != null -> add(body)
                        label != null -> add(label)
                    }
                }
            }
        }
        val query = stringAt(payload, 1, 0, 2)
        val eta = payload.optString(2).takeIf(String::isNotBlank)
        val confirm = stringAt(payload, 3, 0)
        val state = (richField(rich, FIELD_RESEARCH_PLAN_STATE) as? Number)?.toInt()

        if (title == null && steps.isEmpty() && query.isNullOrBlank() && eta == null && confirm.isNullOrBlank()) {
            return null
        }
        return ResearchPlan(
            title = title,
            steps = steps,
            etaText = eta,
            confirmPrompt = confirm?.takeIf(String::isNotBlank),
            query = query?.takeIf(String::isNotBlank),
            rawState = state,
            responseText = fallbackText
        )
    }

    private fun parseResearchDocument(candidate: JSONArray): ResearchDocument? {
        val document = arrayAt(candidate, 30, 0) ?: return null
        // [30] is shared by other cards; a research document always carries its task marker at [3].
        val marker = document.optString(3).takeIf(String::isNotBlank) ?: return null
        val id = document.optString(0).takeIf(String::isNotBlank)
        val title = document.optString(2).takeIf(String::isNotBlank)
        val content = document.optString(4).takeIf(String::isNotBlank)
            ?: stringAt(document, 17, 0).orEmpty()
        return ResearchDocument(id = id, title = title, content = content, taskMarker = marker)
    }

    /** Mirrors Gemini consumer get_field semantics exactly; it never unwraps array payloads. */
    internal fun richField(container: JSONArray, index: Int): Any? {
        val direct = if (index in 0 until container.length()) normalize(container.opt(index)) else null
        if (direct != null && direct !is JSONObject) return direct

        val sparse = if (container.length() > 0) container.optJSONObject(container.length() - 1) else null
        return normalize(sparse?.opt((index + 1).toString()))
    }

    private fun normalize(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONArray -> value.takeIf { it.length() > 0 }
        is JSONObject -> value.takeIf { it.length() > 0 }
        else -> value
    }

    private fun arrayAt(root: Any?, vararg path: Int): JSONArray? = nested(root, *path) as? JSONArray

    private fun stringAt(root: Any?, vararg path: Int): String? =
        (nested(root, *path) as? String)?.takeIf(String::isNotBlank)

    private fun numberAt(root: Any?, vararg path: Int): Number? = nested(root, *path) as? Number

    private fun nested(root: Any?, vararg path: Int): Any? {
        var current: Any? = root
        for (index in path) {
            current = when (current) {
                is JSONArray -> if (index in 0 until current.length()) normalize(current.opt(index)) else null
                else -> null
            }
            if (current == null) return null
        }
        return current
    }

    private fun isHttps(value: String): Boolean = value.startsWith("https://", ignoreCase = true)

    private fun mimeFromUrl(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".png") -> "image/png"
            clean.endsWith(".webp") -> "image/webp"
            clean.endsWith(".gif") -> "image/gif"
            clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "image/jpeg"
            clean.endsWith(".mp3") -> "audio/mpeg"
            clean.endsWith(".wav") -> "audio/wav"
            clean.endsWith(".ogg") -> "audio/ogg"
            clean.endsWith(".m4a") -> "audio/mp4"
            clean.endsWith(".mp4") -> "video/mp4"
            clean.endsWith(".webm") -> "video/webm"
            else -> ""
        }
    }

    private const val FIELD_GENERATED_IMAGES = 7
    private const val FIELD_RESEARCH_PLAN = 55
    private const val FIELD_RESEARCH_PLAN_ALT = 56
    private const val FIELD_VIDEO = 59
    private const val FIELD_RESEARCH_PLAN_STATE = 69
    private const val FIELD_MEDIA = 86
}
