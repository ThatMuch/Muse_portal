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

/** Records crashes before the process dies, to tell Muse at the next connection. */
object CrashRecorder {
    private fun file(context: Context) = File(context.filesDir, "error_report.json")
    private fun state(context: Context) = context.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(context: Context) {
        val state = state(context)
        // A crash at the previous launch extends the streak; a launch without a crash resets it.
        val previous = if (state.getBoolean("crashed", false)) state.getInt("streak", 0) else 0
        state.edit().putBoolean("crashed", false).commit()
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            runCatching { record(context, error, previous + 1) }
            // Without Android's "app has stopped" dialog, which would stay on the kiosk: the process stops, the
            // service restarts and reopens the screen.
            Process.killProcess(Process.myPid())
        }
    }

    private fun record(context: Context, error: Throwable, streak: Int) {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        val uptime = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
        val report = CrashReport.of(error, context.packageName.substringBeforeLast('.'), streak, version, uptime)
        file(context).writeText(report.store())
        // The type and the line only, as in the report.
        Log.e(TAG, "crash: ${report.cause} ${report.detail.orEmpty()}")
        state(context).edit().putBoolean("crashed", true).putInt("streak", streak).commit()
    }

    /** The last crash not yet reported to Muse. */
    fun pending(context: Context): CrashReport? = try {
        file(context).takeIf { it.exists() }?.let { CrashReport.restore(it.readText()) }
    } catch (e: IOException) {
        null
    }

    fun sent(context: Context) {
        file(context).delete()
        Log.i(TAG, "crash report sent to Muse")
    }
}

/** The application process: crash recording is installed before anything else. */
class PortalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        TimerCenter.init(this)
    }
}
