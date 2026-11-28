package com.portalmuse.gadget

import android.content.Context
import android.os.Build
import com.portalmuse.core.MuseApi
import com.portalmuse.core.SystemTrust
import okhttp3.OkHttpClient
import java.security.KeyStore
import javax.net.ssl.SSLContext

/** Connections to Muse: TLS with the system store, minus the distrusted roots. */
object MuseHttp {
    val client: OkHttpClient by lazy {
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        // Certificates added by a user ("user:" aliases) are not accepted.
        val trust = SystemTrust.trustManager(system) { it.startsWith("system:") }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).build()
    }

    /** The client announces itself for what it is, without posing as the Linux client. */
    fun userAgent(context: Context): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return "portal-muse-gadget/$version (${Build.MODEL}; Android ${Build.VERSION.RELEASE})"
    }

    fun api(context: Context) = MuseApi(client, userAgent(context))
}
