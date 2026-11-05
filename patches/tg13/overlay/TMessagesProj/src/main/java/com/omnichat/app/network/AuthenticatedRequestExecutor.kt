package com.omnichat.app.network

data class SubscriptionTransportAuth(
    val accessToken: String,
    val baseUrl: String,
    val accountScope: String
)

/**
 * Executes one Subscription Proxy operation with exactly one OAuth recovery attempt.
 * Streaming callers can disable retry after any output/tool side effect has been observed.
 */
class AuthenticatedRequestExecutor(
    private val load: suspend (String) -> SubscriptionTransportAuth,
    private val refresh: suspend (String) -> Unit
) {
    suspend fun <T> execute(
        source: String,
        canRetry: () -> Boolean = { true },
        block: suspend (SubscriptionTransportAuth) -> T
    ): T {
        val first = load(source)
        return try {
            block(first)
        } catch (error: ApiFailure) {
            if (error.statusCode != 401 || !canRetry()) throw error
            refresh(source)
            block(load(source))
        }
    }
}
