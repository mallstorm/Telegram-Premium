package com.omnichat.app.network

import okhttp3.Dns
import okhttp3.Interceptor

/**
 * TelePremium build of OmniChat's network layer: Telegram already routes the app through the
 * system/VPN resolver, so OmniChat's DoH/DoT registry is replaced by the system resolver.
 */
object NetworkDnsPolicy {
    fun dns(@Suppress("UNUSED_PARAMETER") publicOnly: Boolean = false): Dns = Dns.SYSTEM

    fun feedbackInterceptor(): Interceptor = Interceptor { chain -> chain.proceed(chain.request()) }
}
