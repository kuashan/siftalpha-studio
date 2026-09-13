package com.siftalpha.studio.runtime

/** Read-only probe for a long-running project environment preparation. */
object PrepareProgressProbe {
    fun command(project: RuntimeProjectSpec, host: RuntimeCommandHost): RuntimeCommand {
        val id = host.runtimeId(project.folderName)
        val projectPath = "/root/projects/${project.folderName}"
        val venv = "/root/venvs/$id"
        val pythonReady = "/root/siftalpha/env-ready-$id.txt"
        val nodeExecReady = "/root/siftalpha/node-exec-ready-$id.txt"
        val log = "/root/siftalpha/logs/prepare-$id.log"
        val inner = """
            set +e
            project=${host.sh(projectPath)}
            venv=${host.sh(venv)}
            python_ready=${host.sh(pythonReady)}
            node_exec_ready=${host.sh(nodeExecReady)}
            log=${host.sh(log)}

            source='none'
            runtime='unknown'
            node_root=0
            python_root=0
            [ -f "${'$'}project/package.json" ] && node_root=1
            if [ -s "${'$'}project/requirements.txt" ] || [ -f "${'$'}project/pyproject.toml" ] || \
               [ -f "${'$'}project/setup.py" ] || [ -f "${'$'}project/setup.cfg" ] || \
               [ -f "${'$'}project/Pipfile" ]; then
              python_root=1
            fi

            if [ "${'$'}node_root" -eq 1 ] && [ "${'$'}python_root" -eq 0 ]; then
              runtime='nodejs'
              if [ -f "${'$'}project/package-lock.json" ] || [ -f "${'$'}project/npm-shrinkwrap.json" ]; then
                source='package-lock.json'
              else
                source='package.json'
              fi
            else
              runtime='python'
              if [ -s "${'$'}project/requirements.txt" ]; then
                source='requirements.txt'
              elif [ -f "${'$'}project/pyproject.toml" ]; then
                source='pyproject.toml'
              fi
            fi

            bytes=0
            if [ -f "${'$'}log" ]; then
              bytes="${'$'}(wc -c <"${'$'}log" 2>/dev/null || echo 0)"
            fi

            stage='STARTING'
            if [ "${'$'}runtime" = 'nodejs' ]; then
              if [ -f "${'$'}node_exec_ready" ]; then
                stage='FINALIZING'
              elif grep -q '\[SiftAlpha\] npm ' "${'$'}log" 2>/dev/null; then
                stage='INSTALL_NODE_DEPENDENCIES'
              elif grep -q '同步 Node.js 项目到 Runtime 本地工作区' "${'$'}log" 2>/dev/null; then
                stage='SYNC_NODE_WORKSPACE'
              else
                stage='PREPARE_NODE_TOOLCHAIN'
              fi
            elif grep -q '\[SiftAlpha\] Node.js Web 组件:' "${'$'}log" 2>/dev/null; then
              stage='PREPARE_NODE_COMPONENTS'
            elif [ ! -x "${'$'}venv/bin/python" ]; then
              stage='CREATE_OR_REUSE_VENV'
            elif [ ! -f "${'$'}python_ready" ] && [ "${'$'}source" = 'requirements.txt' ]; then
              stage='INSTALL_REQUIREMENTS'
            elif [ ! -f "${'$'}python_ready" ] && [ "${'$'}source" = 'pyproject.toml' ]; then
              stage='INSTALL_PYPROJECT'
            else
              stage='FINALIZING'
            fi

            echo 'SIFTALPHA_PREPARE_PROGRESS=ACTIVE'
            printf 'SIFTALPHA_PREPARE_RUNTIME=%s\n' "${'$'}runtime"
            printf 'SIFTALPHA_PREPARE_STAGE=%s\n' "${'$'}stage"
            printf 'SIFTALPHA_PREPARE_DEPENDENCY_SOURCE=%s\n' "${'$'}source"
            printf 'SIFTALPHA_PREPARE_LOG_BYTES=%s\n' "${'$'}bytes"
            echo 'SIFTALPHA_PREPARE_TAIL_BEGIN'
            if [ -s "${'$'}log" ]; then
              tail -n 45 "${'$'}log" 2>/dev/null || true
            else
              echo 'SIFTALPHA_PREPARE_LOG=WAITING_FOR_OUTPUT'
            fi
            echo 'SIFTALPHA_PREPARE_TAIL_END'
        """.trimIndent()
        return RuntimeCommand(
            shellScript = host.wrapUbuntu(inner),
            label = "${project.name} · 准备进度",
            description = "只读查看正在进行的环境准备日志。",
        )
    }

    data class Snapshot(
        val stage: String,
        val dependencySource: String,
        val logBytes: Long,
        val tail: String,
        val runtime: String = "unknown",
    )

    fun parse(stdout: String): Snapshot? {
        if ("SIFTALPHA_PREPARE_PROGRESS=ACTIVE" !in stdout) return null
        var runtime = "unknown"
        var stage = "STARTING"
        var source = "none"
        var bytes = 0L
        val tailLines = mutableListOf<String>()
        var inTail = false
        stdout.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("SIFTALPHA_PREPARE_RUNTIME=") -> runtime = line.substringAfter('=')
                line.startsWith("SIFTALPHA_PREPARE_STAGE=") -> stage = line.substringAfter('=')
                line.startsWith("SIFTALPHA_PREPARE_DEPENDENCY_SOURCE=") -> source = line.substringAfter('=')
                line.startsWith("SIFTALPHA_PREPARE_LOG_BYTES=") -> bytes = line.substringAfter('=').toLongOrNull() ?: 0L
                line == "SIFTALPHA_PREPARE_TAIL_BEGIN" -> inTail = true
                line == "SIFTALPHA_PREPARE_TAIL_END" -> inTail = false
                inTail && line != "SIFTALPHA_PREPARE_LOG=WAITING_FOR_OUTPUT" -> tailLines += line
            }
        }
        return Snapshot(stage, source, bytes, tailLines.joinToString("\n").trim(), runtime)
    }
}
