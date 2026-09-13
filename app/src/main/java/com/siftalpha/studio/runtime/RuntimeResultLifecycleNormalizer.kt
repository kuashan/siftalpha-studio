package com.siftalpha.studio.runtime

/**
 * Reconciles Termux command transport results with the managed workload state snapshot.
 *
 * A host/PRoot wrapper can remain alive briefly after the actual workload has already written
 * STATE=EXITED. In that race, the guest state is authoritative: stale RUNNING transport evidence must
 * not leak into the UI and a non-zero workload EXIT_CODE must become the command result exit code.
 */
internal object RuntimeResultLifecycleNormalizer {

    data class Reconciled(
        val stdout: String,
        val exitCode: Int,
        val runtimeState: RuntimeState,
    )

    fun reconcile(rawStdout: String, rawExitCode: Int): Reconciled {
        val runtimeState = RuntimeState.fromOutput(rawStdout)
        var stdout = rawStdout
        var exitCode = rawExitCode

        when (runtimeState) {
            RuntimeState.EXITED_SUCCESS -> {
                stdout = replaceStatus(stdout, "SIFTALPHA_STATUS=STOPPED")
                RuntimeState.extractExitCode(rawStdout)?.let { exitCode = it }
            }
            RuntimeState.EXITED_ERROR -> {
                stdout = replaceStatus(stdout, "SIFTALPHA_STATUS=EXITED_ERROR")
                RuntimeState.extractExitCode(rawStdout)
                    ?.takeIf { it != 0 }
                    ?.let { exitCode = it }
            }
            RuntimeState.STOPPED_BY_USER -> {
                // STOP is an acknowledged control action. Keep the transport exit code (normally 0)
                // even though the durable guest state intentionally records EXIT_CODE=143.
            }
            else -> Unit
        }

        if (runtimeState != RuntimeState.UNKNOWN) {
            stdout = removePrefixedLines(stdout, "SIFTALPHA_RUNTIME_STATE=")
            stdout = appendLine(stdout, "SIFTALPHA_RUNTIME_STATE=${runtimeState.name}")
        }

        return Reconciled(stdout, exitCode, runtimeState)
    }

    private fun replaceStatus(text: String, statusLine: String): String =
        appendLine(removePrefixedLines(text, "SIFTALPHA_STATUS="), statusLine)

    private fun removePrefixedLines(text: String, prefix: String): String =
        text.lineSequence()
            .filterNot { it.trim().startsWith(prefix) }
            .joinToString("\n")
            .trimEnd()

    private fun appendLine(text: String, line: String): String = buildString {
        if (text.isNotBlank()) {
            append(text.trimEnd())
            append('\n')
        }
        append(line)
        append('\n')
    }
}
