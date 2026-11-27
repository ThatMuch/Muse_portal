package com.portalmuse.gadget

import android.content.Context
import android.os.Build
import com.portalmuse.core.MuseApi
import com.portalmuse.core.SystemTrust
import okhttp3.OkHttpClient
import java.security.KeyStore
import javax.net.ssl.SSLContext

/** Connexions vers Muse : TLS avec le magasin système, privé des racines écartées. */
object MuseHttp {
    val client: OkHttpClient by lazy {
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        // Les certificats ajoutés par un utilisateur (alias « user: ») ne sont pas acceptés.
        val trust = SystemTrust.trustManager(system) { it.startsWith("system:") }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).build()
    }

    /** Le client s'annonce pour ce qu'il est, sans se faire passer pour le client Linux. */
    fun userAgent(context: Context): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return "portal-muse-gadget/$version (${Build.MODEL}; Android ${Build.VERSION.RELEASE})"
    }

    fun api(context: Context) = MuseApi(client, userAgent(context))
}
