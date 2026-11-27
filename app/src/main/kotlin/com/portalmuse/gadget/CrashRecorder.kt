package com.portalmuse.gadget

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.portalmuse.core.CrashReport
import java.io.File
import java.io.IOException

private const val TAG = "PortalMuse"

/** Note les pannes avant que le processus meure, pour les dire à Muse à la prochaine connexion. */
object CrashRecorder {
    private fun file(context: Context) = File(context.filesDir, "error_report.json")
    private fun state(context: Context) = context.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(context: Context) {
        val state = state(context)
        // Une panne au lancement précédent prolonge la série ; un lancement sans panne la remet à zéro.
        val previous = if (state.getBoolean("crashed", false)) state.getInt("streak", 0) else 0
        state.edit().putBoolean("crashed", false).commit()
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            runCatching { record(context, error, previous + 1) }
            // Sans la boîte « l'application s'est arrêtée » d'Android, qui resterait sur le
            // kiosque : le processus s'arrête, le service repart et rouvre l'écran.
            Process.killProcess(Process.myPid())
        }
    }

    private fun record(context: Context, error: Throwable, streak: Int) {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        val uptime = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
        val report = CrashReport.of(error, context.packageName.substringBeforeLast('.'), streak, version, uptime)
        file(context).writeText(report.store())
        // Le type et la ligne seulement, comme dans le rapport.
        Log.e(TAG, "plantage : ${report.cause} ${report.detail.orEmpty()}")
        state(context).edit().putBoolean("crashed", true).putInt("streak", streak).commit()
    }

    /** La dernière panne pas encore dite à Muse. */
    fun pending(context: Context): CrashReport? = try {
        file(context).takeIf { it.exists() }?.let { CrashReport.restore(it.readText()) }
    } catch (e: IOException) {
        null
    }

    fun sent(context: Context) {
        file(context).delete()
        Log.i(TAG, "rapport de panne envoyé à Muse")
    }
}

/** Le processus de l'application : le relevé des pannes s'installe avant tout le reste. */
class PortalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        TimerCenter.init(this)
    }
}
