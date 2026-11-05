package com.omnichat.app.security

/**
 * Dedicated credential namespace for Subscription Proxy.
 *
 * The alias intentionally differs from [KeyVault], preserving the original cryptographic
 * isolation while both vaults share one audited AES-GCM implementation.
 */
class SubscriptionProxyVault : SecretCipher {
    private val delegate = AndroidKeystoreAesGcmVault(
        alias = "omnichat_subscription_proxy_v1",
        invalidFormatMessage = "Неверный формат Subscription Proxy secret"
    )

    override fun encrypt(value: String): String = delegate.encrypt(value)

    override fun decrypt(value: String): String = delegate.decrypt(value)
}
