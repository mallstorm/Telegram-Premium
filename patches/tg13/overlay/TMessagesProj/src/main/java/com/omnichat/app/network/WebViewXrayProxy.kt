package com.omnichat.app.network

/** TelePremium: no in-app Xray route; WebView uses the system/VPN network. */
object WebViewXrayProxy {
    fun ensureCurrentRoute(): String = ""
}
