package com.siftalpha.studio.runtime

/**
 * Converts a failed runtime result into a bounded, display-safe reason.
 *
 * Callers must pass output after project-secret redaction. This class never persists or logs
 * unbounded command output.
 */
object RuntimeFailureReason {
    private const val MAX_LENGTH = 240
    private val marker = Regex("SIFTALPHA_ERROR=([A-Z0-9_]+)")

    fun summarize(
        exitCode: Int,
        internalErrorMessage: String,
        stdout: String,
        stderr: String,
    ): String? {
        val combined = listOf(internalErrorMessage, stdout, stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (combined.isBlank() && exitCode == 0) return null

        marker.find(combined)?.groupValues?.getOrNull(1)?.let { return it }
        val detail = combined.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .lastOrNull()
            .orEmpty()
        return when {
            detail.isNotBlank() -> "exitCode=$exitCode: \${detail.takeLast(MAX_LENGTH - 12)}".takeLast(MAX_LENGTH)
            exitCode != 0 -> "exitCode=$exitCode"
            else -> "runtime command failed"
        }
    }
}
