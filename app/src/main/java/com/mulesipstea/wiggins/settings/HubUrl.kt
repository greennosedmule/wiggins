package com.mulesipstea.wiggins.settings

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validates a hub URL. `ws://` is accepted only in [AuthMode.NONE], where the
 * network is a LAN or VPN and HiveMind's payload encryption is the protection;
 * every other mode requires `wss://`.
 */
object HubUrl {
    sealed interface Result {
        data class Ok(val url: HttpUrl) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(raw: String, authMode: AuthMode): Result {
        val text = raw.trim()
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme != "ws" && scheme != "wss") return Result.Invalid("URL must start with ws:// or wss://")
        if (scheme == "ws" && authMode != AuthMode.NONE) return Result.Invalid("This auth mode requires wss://")
        // OkHttp models websocket URLs as http(s) and converts them back on connect.
        val httpUrl = (if (scheme == "wss") "https" else "http") + text.substring(scheme.length)
        val url = httpUrl.toHttpUrlOrNull() ?: return Result.Invalid("Not a valid URL")
        return Result.Ok(url)
    }
}
