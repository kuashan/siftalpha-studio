package com.siftalpha.studio

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.siftalpha.studio.project.V04ProjectGateway
import com.siftalpha.studio.runtime.PrepareProgressProbe
import com.siftalpha.studio.runtime.ProjectRuntimeController
import com.siftalpha.studio.runtime.ProjectSecretStore
import com.siftalpha.studio.runtime.RuntimeResult
import com.siftalpha.studio.runtime.TermuxBackend
import com.siftalpha.studio.runtime.TermuxResultBus
import java.util.Locale

/**
 * UI-side read-only polling for long-running PREPARE commands.
 *
 * The primary PREPARE command remains authoritative. This controller only launches short probes
 * that tail the existing prepare log. It never installs packages or mutates runtime state.
 */
class PrepareLiveProgressController(
    private val context: Context,
    private val backend: TermuxBackend,
    private val gateway: V04ProjectGateway,
    private val runtime: ProjectRuntimeController,
    private val render: (folderName: String, text: String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val secretStore = ProjectSecretStore(context)
    private val activeMainExecutions = mutableMapOf<String, Int>()
    private val inFlightByFolder = mutableMapOf<String, Int>()
    private val probeFolderByExecution = mutableMapOf<Int, String>()
    private val scheduledByFolder = mutableMapOf<String, Runnable>()
    private var resumed = false

    fun resume() {
        resumed = true
        activeMainExecutions.keys.toList().forEach { schedule(it, RESUME_DELAY_MS) }
    }

    fun pause() {
        resumed = false
        scheduledByFolder.values.forEach(handler::removeCallbacks)
        scheduledByFolder.clear()
    }

    fun start(folderName: String, mainExecutionId: Int) {
        activeMainExecutions[folderName] = mainExecutionId
        if (resumed) schedule(folderName, INITIAL_DELAY_MS)
    }

    fun finish(folderName: String) {
        activeMainExecutions.remove(folderName)
        scheduledByFolder.remove(folderName)?.let(handler::removeCallbacks)
        // A probe already in flight is allowed to return, but consumeIfProbe() will discard its
        // display update because the primary PREPARE is no longer active.
        inFlightByFolder.remove(folderName)
    }

    /** Returns true when [result] belongs to an internal progress probe and was consumed here. */
    fun consumeIfProbe(result: RuntimeResult): Boolean {
        val folderName = probeFolderByExecution.remove(result.executionId) ?: return false
        TermuxResultBus.consume(result.executionId)
        inFlightByFolder.remove(folderName)

        if (activeMainExecutions.containsKey(folderName)) {
            val snapshot = PrepareProgressProbe.parse(redactProbeOutput(folderName, result.stdout))
            if (snapshot != null) render(folderName, format(folderName, snapshot))
            if (resumed) schedule(folderName, POLL_INTERVAL_MS)
        }
        return true
    }

    /**
     * Preparation logs are rendered in the project output panel. Redact configured values before
     * parsing so copied or displayed progress text cannot expose a protected credential. If the
     * protected store cannot be read, keep only protocol markers and omit the free-form log tail.
     */
    private fun redactProbeOutput(folderName: String, stdout: String): String =
        runCatching { secretStore.redactRuntimeText(folderName, stdout) }
            .getOrElse {
                stdout.lineSequence()
                    .map { it.trimEnd() }
                    .filter { it.startsWith("SIFTALPHA_PREPARE_") }
                    .joinToString("\n")
            }

    private fun schedule(folderName: String, delayMs: Long) {
        if (!resumed || !activeMainExecutions.containsKey(folderName)) return
        if (inFlightByFolder.containsKey(folderName)) return
        scheduledByFolder.remove(folderName)?.let(handler::removeCallbacks)
        val runnable = Runnable {
            scheduledByFolder.remove(folderName)
            sendProbe(folderName)
        }
        scheduledByFolder[folderName] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun sendProbe(folderName: String) {
        if (!resumed || !activeMainExecutions.containsKey(folderName)) return
        if (inFlightByFolder.containsKey(folderName)) return

        val project = runCatching {
            gateway.projects().firstOrNull { it.folderName == folderName }
        }.getOrNull()
        if (project == null) {
            schedule(folderName, POLL_INTERVAL_MS)
            return
        }

        try {
            val executionId = backend.execute(runtime.prepareProgress(project))
            probeFolderByExecution[executionId] = folderName
            inFlightByFolder[folderName] = executionId
            // Mirror the Runtime Center result-reconciliation rule: a very fast probe may have
            // completed before its executionId was registered above.
            TermuxResultBus.consume(executionId)?.let { consumeIfProbe(it) }
        } catch (_: Throwable) {
            schedule(folderName, POLL_INTERVAL_MS)
        }
    }

    private fun format(folderName: String, snapshot: PrepareProgressProbe.Snapshot): String {
        val mainExecutionId = activeMainExecutions[folderName]
        val stageLabel = when (snapshot.stage) {
            "STARTING" -> context.getString(R.string.runtime_prepare_live_stage_starting)
            "CREATE_OR_REUSE_VENV" -> context.getString(R.string.runtime_prepare_live_stage_venv)
            "INSTALL_REQUIREMENTS" -> context.getString(R.string.runtime_prepare_live_stage_requirements)
            "INSTALL_PYPROJECT" -> context.getString(R.string.runtime_prepare_live_stage_pyproject)
            "FINALIZING" -> context.getString(R.string.runtime_prepare_live_stage_finalizing)
            else -> context.getString(R.string.runtime_prepare_live_stage_unknown)
        }
        return buildString {
            appendLine(context.getString(R.string.runtime_prepare_live_title))
            appendLine(context.getString(R.string.runtime_prepare_live_project, folderName))
            if (mainExecutionId != null) {
                appendLine(context.getString(R.string.runtime_prepare_live_execution, mainExecutionId))
            }
            appendLine(context.getString(R.string.runtime_prepare_live_stage, stageLabel))
            appendLine(context.getString(R.string.runtime_prepare_live_source, snapshot.dependencySource))
            appendLine(context.getString(R.string.runtime_prepare_live_log_size, formatBytes(snapshot.logBytes)))
            appendLine()
            appendLine(context.getString(R.string.runtime_prepare_live_tail))
            if (snapshot.tail.isBlank()) {
                append(context.getString(R.string.runtime_prepare_live_waiting))
            } else {
                append(snapshot.tail)
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    companion object {
        private const val INITIAL_DELAY_MS = 700L
        private const val RESUME_DELAY_MS = 250L
        private const val POLL_INTERVAL_MS = 2000L
    }
}
