package com.omnichat.app.network

import com.omnichat.app.data.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Conservative Gemini Web recovery. It only retries errors that happen before StreamGenerate
 * can reasonably have been submitted (session/bootstrap/login preparation). Media generation and
 * research confirmation are never blindly replayed after an ambiguous transport failure because
 * that could consume quota twice or start duplicate server-side work.
 */
suspend fun GeminiWebClient.generateWithRecovery(
    credentials: GeminiWebClient.Credentials,
    model: String,
    systemPrompt: String,
    history: List<MessageEntity>,
    attachments: List<LocalAttachment> = emptyList(),
    conversation: GeminiWebClient.ConversationState? = null,
    feature: GeminiWebFeature = GeminiWebFeature.TEXT_CHAT,
    directPrompt: String? = null,
    onText: suspend (String) -> Unit
): GeminiWebClient.GenerateResult {
    var liveCredentials = credentials
    var firstFailure: Throwable? = null

    repeat(2) { attempt ->
        try {
            return generate(
                credentials = liveCredentials,
                model = model,
                systemPrompt = systemPrompt,
                history = history,
                attachments = attachments,
                conversation = conversation,
                feature = feature,
                directPrompt = directPrompt,
                onText = onText
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (attempt > 0 || !error.isSafePreSubmitGeminiFailure()) throw error
            firstFailure = error

            // Refresh from Chromium's live cookie jar. This captures 1PSIDTS rotations made after
            // login without overwriting them with the older encrypted snapshot.
            liveCredentials = runCatching {
                currentBrowserCredentials(liveCredentials)
            }.getOrDefault(liveCredentials)

            // Re-bootstrap through the same Xray-routed WebView. A successful probe means retrying
            // the still-unsubmitted user request is safe.
            runCatching { probe(liveCredentials) }.getOrElse { probeFailure ->
                throw firstFailure ?: probeFailure
            }
            delay(250L)
        }
    }
    throw firstFailure ?: ApiFailure("Gemini Web recovery failed")
}

private fun Throwable.isSafePreSubmitGeminiFailure(): Boolean {
    val value = (message ?: "").lowercase()
    return listOf(
        "сессия истекла",
        "перенаправил на вход",
        "не удалось открыть gemini web",
        "bootstrap",
        "system webview не поддерживает proxy override",
        "не удалось направить webview через xray"
    ).any(value::contains)
}
