package com.siftalpha.studio

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Small process-wide crash recorder for real-device failures that CI cannot reproduce.
 * It never uploads crash data. Reports stay in the app's private SharedPreferences until
 * the user dismisses the local recovery screen.
 */
class StudioApplication : Application(), Application.ActivityLifecycleCallbacks {

    private var recoveryLaunchInProgress = false

    override fun onCreate() {
        super.onCreate()
        StudioLanguage.applyPersisted(this)
        installCrashRecorder()
        registerActivityLifecycleCallbacks(this)
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { saveCrash(thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun saveCrash(thread: Thread, throwable: Throwable) {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val report = buildString {
            appendLine("SIFTALPHA_DEVICE_CRASH_REPORT=1")
            appendLine("TIME_MS=${System.currentTimeMillis()}")
            appendLine("THREAD=${thread.name}")
            appendLine("TYPE=${throwable.javaClass.name}")
            appendLine("MESSAGE=${throwable.message.orEmpty()}")
            appendLine("--- stacktrace ---")
            append(writer.toString().take(MAX_REPORT_CHARS))
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_REPORT, report)
            .putBoolean(KEY_PENDING, true)
            .apply()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is CrashRecoveryActivity) return
        if (recoveryLaunchInProgress) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING, false)) return

        recoveryLaunchInProgress = true
        runCatching {
            activity.startActivity(Intent(activity, CrashRecoveryActivity::class.java))
        }.onFailure {
            recoveryLaunchInProgress = false
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activity is CrashRecoveryActivity) recoveryLaunchInProgress = false
    }

    companion object {
        private const val PREFS = "siftalpha_crash_report"
        private const val KEY_REPORT = "last_report"
        private const val KEY_PENDING = "pending"
        private const val MAX_REPORT_CHARS = 12_000

        fun readPendingReport(activity: Activity): String? =
            activity.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_REPORT, null)
                ?.takeIf {
                    activity.getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean(KEY_PENDING, false)
                }

        fun clearPendingReport(activity: Activity) {
            activity.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING, false)
                .apply()
        }
    }
}
