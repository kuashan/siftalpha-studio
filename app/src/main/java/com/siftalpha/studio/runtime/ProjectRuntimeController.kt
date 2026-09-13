package com.siftalpha.studio.runtime

import com.siftalpha.studio.project.V04ProjectGateway

/**
 * Runtime-neutral project execution coordinator.
 *
 * v0.7 alpha5 removes the historical Python-primary assumption. Authoritative project evidence is
 * resolved into one executable primary Runtime; Python may still compose the accepted supplemental
 * Vite/Node build path, while a Node-primary project now owns its complete lifecycle directly.
 */
class ProjectRuntimeController(private val gateway: V04ProjectGateway) {

    enum class Action {
        PREPARE,
        START,
        STOP,
        STATUS,
        LOGS,
        CLEAN,
        CLONE_GITHUB,
    }

    data class GitHubCloneSpec(
        val cloneUrl: String,
        val sourceUrl: String,
        val branch: String,
        val projectName: String,
    )

    private data class ExecutionContext(
        val spec: RuntimeProjectSpec,
        val selection: ProjectRuntimeExecutionPlanner.Selection,
    )

    private val host: RuntimeCommandHost = TermuxProotRuntimeHost(gateway)
    private val pythonAdapter: ExecutableRuntimeAdapter = PythonRuntimeAdapter(host)
    private val nodeExecutableAdapter: ExecutableRuntimeAdapter = ExecutableNodeJsRuntimeAdapter(host)
    private val supplementalNodeAdapter = NodeJsRuntimeAdapter(host)

    fun runtimeSupported(): Boolean = host.runtimeSupported()

    fun runtimeUnsupportedReason(): String = host.runtimeUnsupportedReason()

    fun prepare(project: V04ProjectGateway.RuntimeProject): RuntimeCommand {
        val context = executionContext(project)
        val resolved = context.selection as? ProjectRuntimeExecutionPlanner.Selection.Resolved
            ?: return selectionError(project, context.selection)
        return when (resolved.primary) {
            RuntimeKind.PYTHON -> {
                val python = PythonDependencyDiagnostics.wrapPrepareFailure(
                    base = pythonAdapter.prepare(context.spec),
                    project = context.spec,
                    host = host,
                )
                val node = supplementalNodeAdapter.prepareDetectedWebComponents(context.spec)
                RuntimeEnvironmentComposer.prepare(
                    host = host,
                    projectName = context.spec.name,
                    primary = python,
                    node = node,
                )
            }
            RuntimeKind.NODE_JS -> nodeExecutableAdapter.prepare(context.spec)
            else -> executableUnavailable(project, resolved.primary)
        }
    }

    /**
     * PREPARE progress is intentionally cheap and read-only. Do not recursively rescan the repository
     * every two seconds merely to tail the shared prepare log.
     */
    fun prepareProgress(project: V04ProjectGateway.RuntimeProject): RuntimeCommand =
        PrepareProgressProbe.command(basicSpec(project), host)

    fun start(project: V04ProjectGateway.RuntimeProject): RuntimeCommand {
        val context = executionContext(project)
        val resolved = context.selection as? ProjectRuntimeExecutionPlanner.Selection.Resolved
            ?: return selectionError(project, context.selection)
        return when (resolved.primary) {
            RuntimeKind.PYTHON -> {
                val python = PythonDependencyDiagnostics.wrapStartFailure(
                    base = pythonAdapter.start(context.spec),
                    project = context.spec,
                    host = host,
                )
                RuntimeEnvironmentComposer.start(
                    host = host,
                    projectName = context.spec.name,
                    primary = python,
                    nodeStatus = supplementalNodeAdapter.statusDetectedWebComponents(context.spec),
                )
            }
            RuntimeKind.NODE_JS -> nodeExecutableAdapter.start(context.spec)
            else -> executableUnavailable(project, resolved.primary)
        }
    }

    /**
     * STOP is process ownership, not source detection. It must remain available after files were edited,
     * deleted or became ambiguous, so it never depends on a recursive project scan.
     */
    fun stop(project: V04ProjectGateway.RuntimeProject): RuntimeCommand {
        val spec = basicSpec(project)
        return ManagedProcessRuntime.stop(host, spec, host.runtimeId(spec.folderName))
    }

    fun status(project: V04ProjectGateway.RuntimeProject): RuntimeCommand {
        val context = executionContext(project)
        val resolved = context.selection as? ProjectRuntimeExecutionPlanner.Selection.Resolved
            ?: return unresolvedStatus(context)
        return when (resolved.primary) {
            RuntimeKind.PYTHON -> RuntimeEnvironmentComposer.status(
                host = host,
                projectName = context.spec.name,
                primary = pythonAdapter.status(context.spec),
                node = supplementalNodeAdapter.statusDetectedWebComponents(context.spec),
            )
            RuntimeKind.NODE_JS -> nodeExecutableAdapter.status(context.spec)
            else -> executableUnavailable(project, resolved.primary)
        }
    }

    fun logs(project: V04ProjectGateway.RuntimeProject): RuntimeCommand {
        val context = executionContext(project)
        val resolved = context.selection as? ProjectRuntimeExecutionPlanner.Selection.Resolved
        return when (resolved?.primary) {
            RuntimeKind.PYTHON -> PythonDependencyDiagnostics.appendRuntimeLogDiagnosis(
                base = pythonAdapter.logs(context.spec),
                project = context.spec,
                host = host,
            )
            RuntimeKind.NODE_JS -> nodeExecutableAdapter.logs(context.spec)
            else -> ManagedProcessRuntime.logs(host, context.spec, host.runtimeId(context.spec.folderName))
        }
    }

    fun clean(project: V04ProjectGateway.RuntimeProject): RuntimeCommand {
        val context = executionContext(project)
        val resolved = context.selection as? ProjectRuntimeExecutionPlanner.Selection.Resolved
            ?: return cleanUnresolved(project)
        return when (resolved.primary) {
            RuntimeKind.PYTHON -> RuntimeEnvironmentComposer.clean(
                host = host,
                projectName = context.spec.name,
                primary = pythonAdapter.clean(context.spec),
                node = supplementalNodeAdapter.cleanDetectedWebComponents(context.spec),
            )
            RuntimeKind.NODE_JS -> nodeExecutableAdapter.clean(context.spec)
            else -> executableUnavailable(project, resolved.primary)
        }
    }

    fun cloneGitHub(spec: GitHubCloneSpec): RuntimeCommand {
        val destination = "/root/projects/${spec.projectName}"
        val id = host.runtimeId(spec.projectName)
        val log = "/root/siftalpha/logs/clone-$id.log"
        val inner = """
            set -e
            mkdir -p /root/siftalpha/logs
            dest=${host.sh(destination)}
            log=${host.sh(log)}
            if [ -e "${'$'}dest" ]; then
              echo 'SIFTALPHA_ERROR=PROJECT_EXISTS'
              exit 61
            fi
            if ! command -v git >/dev/null 2>&1; then
              : >"${'$'}log"
              export DEBIAN_FRONTEND=noninteractive
              apt-get update >>"${'$'}log" 2>&1
              apt-get install -y git >>"${'$'}log" 2>&1
            fi
            : >"${'$'}log"
            if git clone --depth 1 --branch ${host.sh(spec.branch)} -- ${host.sh(spec.cloneUrl)} "${'$'}dest" >>"${'$'}log" 2>&1; then
              echo 'SIFTALPHA_CLONE=OK'
              tail -n 30 "${'$'}log" 2>/dev/null || true
            else
              code=${'$'}?
              rm -rf -- "${'$'}dest"
              echo 'SIFTALPHA_CLONE=FAILED'
              tail -n 40 "${'$'}log" 2>/dev/null || true
              exit "${'$'}code"
            fi
        """.trimIndent()
        return RuntimeCommand(
            shellScript = host.wrapUbuntu(inner),
            label = "GitHub 导入 · ${spec.projectName}",
            description = "从 GitHub 克隆项目到当前项目目录。",
        )
    }

    private fun executionContext(project: V04ProjectGateway.RuntimeProject): ExecutionContext {
        val facts = gateway.runtimeFacts(project.summary.documentId)
        val selection = ProjectRuntimeExecutionPlanner.select(
            relativePaths = facts.relativePaths,
            declaredType = facts.declaredType,
        )
        val resolvedPrimary = (selection as? ProjectRuntimeExecutionPlanner.Selection.Resolved)?.primary
        val resolvedEntry = facts.declaredEntry ?: when (resolvedPrimary) {
            RuntimeKind.NODE_JS -> project.summary.entry.takeUnless { it.endsWith(".py", ignoreCase = true) }.orEmpty()
            else -> project.summary.entry
        }
        val resolvedRun = facts.declaredRun ?: when (resolvedPrimary) {
            RuntimeKind.NODE_JS -> project.summary.run.takeUnless {
                val value = it.trim()
                value.startsWith("python ") || value.startsWith("python3 ")
            }.orEmpty()
            else -> project.summary.run
        }
        return ExecutionContext(
            spec = RuntimeProjectSpec(
                name = project.summary.name,
                folderName = project.folderName,
                entry = resolvedEntry,
                run = resolvedRun,
                declaredType = facts.declaredType,
                declaredEntry = facts.declaredEntry,
                declaredRun = facts.declaredRun,
                relativePaths = facts.relativePaths,
            ),
            selection = selection,
        )
    }

    private fun basicSpec(project: V04ProjectGateway.RuntimeProject): RuntimeProjectSpec = RuntimeProjectSpec(
        name = project.summary.name,
        folderName = project.folderName,
        entry = project.summary.entry,
        run = project.summary.run,
    )

    private fun selectionError(
        project: V04ProjectGateway.RuntimeProject,
        selection: ProjectRuntimeExecutionPlanner.Selection,
    ): RuntimeCommand {
        val lines = when (selection) {
            is ProjectRuntimeExecutionPlanner.Selection.Ambiguous -> listOf(
                "SIFTALPHA_ERROR=RUNTIME_SELECTION_AMBIGUOUS",
                "SIFTALPHA_RUNTIME_CANDIDATES=${selection.candidates.joinToString(",") { it.id }}",
                "SIFTALPHA_ENV=NOT_READY",
            )
            is ProjectRuntimeExecutionPlanner.Selection.Unsupported -> listOf(
                "SIFTALPHA_ERROR=RUNTIME_UNSUPPORTED_OR_UNKNOWN",
                "SIFTALPHA_ENV=NOT_READY",
            )
            is ProjectRuntimeExecutionPlanner.Selection.Resolved -> error("resolved selection is not an error")
        }
        return simpleError(project.summary.name, lines, 81)
    }

    private fun executableUnavailable(
        project: V04ProjectGateway.RuntimeProject,
        kind: RuntimeKind,
    ): RuntimeCommand = simpleError(
        project.summary.name,
        listOf(
            "SIFTALPHA_ERROR=RUNTIME_EXECUTION_UNSUPPORTED",
            "SIFTALPHA_RUNTIME_KIND=${kind.id}",
            "SIFTALPHA_ENV=NOT_READY",
        ),
        81,
    )

    private fun unresolvedStatus(context: ExecutionContext): RuntimeCommand {
        val id = host.runtimeId(context.spec.folderName)
        val state = ManagedProcessRuntime.defaultGuestPaths(id).state
        val reason = when (context.selection) {
            is ProjectRuntimeExecutionPlanner.Selection.Ambiguous -> "RUNTIME_SELECTION_AMBIGUOUS"
            is ProjectRuntimeExecutionPlanner.Selection.Unsupported -> "RUNTIME_UNSUPPORTED_OR_UNKNOWN"
            is ProjectRuntimeExecutionPlanner.Selection.Resolved -> error("resolved status")
        }
        val guestStatus = """
            echo 'SIFTALPHA_ENV=NOT_READY'
            echo 'SIFTALPHA_ENV_REASON=$reason'
            if [ -f ${host.sh(state)} ]; then cat ${host.sh(state)}; fi
        """.trimIndent()
        return ManagedProcessRuntime.status(host, context.spec, id, guestStatus)
    }

    /**
     * CLEAN is a recovery operation. When selection is ambiguous, clean all known project-specific
     * Runtime state while preserving source and shared toolchains instead of trapping the user in an
     * uncleanable state.
     */
    private fun cleanUnresolved(project: V04ProjectGateway.RuntimeProject): RuntimeCommand {
        val spec = basicSpec(project)
        val id = host.runtimeId(spec.folderName)
        val paths = ManagedProcessRuntime.defaultGuestPaths(id)
        val guestClean = """
            rm -rf -- ${host.sh("/root/venvs/$id")} \
                       ${host.sh("/root/siftalpha/node-workspaces/$id")} \
                       ${host.sh("/root/siftalpha/node-exec-workspaces/$id")}
            rm -f -- ${host.sh("/root/siftalpha/env-ready-$id.txt")} \
                      ${host.sh("/root/siftalpha/node-ready-$id.txt")} \
                      ${host.sh("/root/siftalpha/node-exec-ready-$id.txt")} \
                      ${host.sh("/root/siftalpha/logs/prepare-$id.log")} \
                      ${host.sh(paths.runLog)} ${host.sh(paths.runner)} ${host.sh(paths.state)} ${host.sh(paths.secrets)}
            echo 'SIFTALPHA_RUNTIME_RECOVERY_CLEAN=1'
        """.trimIndent()
        return ManagedProcessRuntime.clean(host, spec, id, guestClean)
    }

    private fun simpleError(
        projectName: String,
        lines: List<String>,
        exitCode: Int,
    ): RuntimeCommand = RuntimeCommand(
        shellScript = buildString {
            appendLine("set +e")
            lines.forEach { appendLine("echo ${host.sh(it)}") }
            append("exit $exitCode")
        },
        label = "$projectName · Runtime",
        description = "$projectName · 无法安全确定可执行 Runtime。",
    )
}
