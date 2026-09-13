package com.siftalpha.studio.runtime

import android.app.IntentService
import android.content.Intent
import android.content.pm.PackageManager

@Suppress("DEPRECATION")
class TermuxResultService : IntentService("SiftAlphaTermuxResultService") {

    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) return

        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val bundle = intent.getBundleExtra(TermuxContract.EXTRA_RESULT_BUNDLE) ?: return
        val rawStdout = bundle.getString(TermuxContract.RESULT_STDOUT, "")
        val rawExitCode = bundle.getInt(TermuxContract.RESULT_EXIT_CODE, Int.MIN_VALUE)
        val reconciled = RuntimeResultLifecycleNormalizer.reconcile(rawStdout, rawExitCode)
        var stdout = reconciled.stdout

        if ("=== SiftAlpha Runtime Probe ===" in rawStdout) {
            val diagnostic = RuntimeDiagnostic.fromProbe(
                output = rawStdout,
                runCommandPermission = checkSelfPermission(TermuxContract.RUN_COMMAND_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED,
            )
            stdout = appendLine(stdout, "=== SiftAlpha Runtime Diagnostic ===")
            stdout = appendLine(stdout, diagnostic.summary().trimEnd())
        }

        val result = RuntimeResult(
            executionId = executionId,
            stdout = stdout,
            stderr = bundle.getString(TermuxContract.RESULT_STDERR, ""),
            exitCode = reconciled.exitCode,
            internalErrorCode = bundle.getInt(TermuxContract.RESULT_ERR, Int.MIN_VALUE),
            internalErrorMessage = bundle.getString(TermuxContract.RESULT_ERRMSG, ""),
        )

        TermuxResultBus.publish(result)
    }

    private fun appendLine(text: String, line: String): String = buildString {
        if (text.isNotBlank()) {
            append(text.trimEnd())
            append('\n')
        }
        append(line)
        append('\n')
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "siftalpha_execution_id"
    }
}
