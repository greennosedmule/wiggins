package com.mulesipstea.wiggins

import android.app.Application
import com.mulesipstea.wiggins.settings.SettingsRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WigginsApp : Application() {
    val settings by lazy { SettingsRepository(this) }

    /** The hub connection and conversation, shared by every screen. */
    val assistant by lazy { Assistant(this) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Keeps reverse proxies and NAT from dropping an idle websocket.
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            // A proxy answering the upgrade with a redirect (to a sign-in page) is a
            // failure to report, not a page to follow.
            .followRedirects(false)
            .build()
    }
}
