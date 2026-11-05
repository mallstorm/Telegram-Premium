package com.omnichat.app.network

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Shared PKCE/state primitive used by browser-based account connections. */
object OAuthPkce {
    enum class StateEncoding { BASE64_URL, HEX }

    data class Material(
        val verifier: String,
        val challenge: String,
        val state: String
    )

    private val random = SecureRandom()

    fun create(
        verifierBytes: Int = 48,
        stateBytes: Int = 24,
        stateEncoding: StateEncoding = StateEncoding.BASE64_URL,
        verifierMaxChars: Int? = null
    ): Material {
        require(verifierBytes >= 32) { "PKCE verifier должен содержать не менее 32 случайных байт" }
        require(stateBytes >= 16) { "OAuth state должен содержать не менее 16 случайных байт" }
        val verifierRaw = randomBase64Url(verifierBytes)
        val verifier = verifierMaxChars?.let(verifierRaw::take) ?: verifierRaw
        val state = when (stateEncoding) {
            StateEncoding.BASE64_URL -> randomBase64Url(stateBytes)
            StateEncoding.HEX -> randomHex(stateBytes)
        }
        return Material(verifier, challenge(verifier), state)
    }

    fun challenge(verifier: String): String {
        require(verifier.isNotBlank()) { "PKCE verifier пуст" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun matchesState(expected: String, actual: String?): Boolean {
        if (expected.isBlank() || actual.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8)
        )
    }

    private fun randomBase64Url(bytes: Int): String = ByteArray(bytes)
        .also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun randomHex(bytes: Int): String = ByteArray(bytes)
        .also(random::nextBytes)
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
