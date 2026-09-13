package com.siftalpha.studio.runtime

/**
 * First-class executable Node.js Runtime.
 *
 * Source stays authoritative in the imported project while execution happens from a persistent
 * Runtime-local mirror so `node_modules` and runtime-created files do not pollute Android shared
 * storage. PREPARE refreshes only authoritative source paths; files created only by the running app
 * remain in the Runtime workspace until CLEAN.
 */
class ExecutableNodeJsRuntimeAdapter(
    private val host: RuntimeCommandHost,
) : ExecutableRuntimeAdapter {
    override val kind: RuntimeKind = RuntimeKind.NODE_JS

    override val supportedActions: Set<RuntimeAction> = setOf(
        RuntimeAction.PREPARE,
        RuntimeAction.INSTALL_DEPENDENCIES,
        RuntimeAction.START,
        RuntimeAction.STOP,
        RuntimeAction.STATUS,
        RuntimeAction.LOGS,
        RuntimeAction.CLEAN,
    )

    override val environmentRequirements: List<RuntimeEnvironmentRequirement> = listOf(
        RuntimeEnvironmentRequirement("managed-node", "SiftAlpha managed Node.js runtime"),
        RuntimeEnvironmentRequirement("managed-npm", "SiftAlpha managed npm package manager"),
    )

    override fun prepare(project: RuntimeProjectSpec): RuntimeCommand {
        val layout = resolveLayout(project) ?: return layoutError(project)
        val manager = packageManager(project, layout)
        if (manager !is NodePackageManagerPolicy.Result.Supported) {
            return packageManagerError(project, manager)
        }

        val id = host.runtimeId(project.folderName)
        val source = "/root/projects/${project.folderName}"
        val workRoot = "/root/siftalpha/node-exec-workspaces/$id"
        val workspaceRoot = "$workRoot/repo"
        val workspacePackage = if (layout.packageRoot == ".") workspaceRoot else "$workspaceRoot/${layout.packageRoot}"
        val ready = "/root/siftalpha/node-exec-ready-$id.txt"
        val log = "/root/siftalpha/logs/prepare-$id.log"
        val sourceManager = "$workRoot/source-manager.cjs"
        val declaredRun = project.declaredRun?.trim().orEmpty()
        val startMode = if (declaredRun.isNotBlank()) "DECLARED_RUN" else "PACKAGE_START"
        val installMode = if (manager.lockfile != null) "NPM_CI" else "NPM_INSTALL"

        val inner = """
            set -e
            project=${sh(source)}
            work_root=${sh(workRoot)}
            workspace_root=${sh(workspaceRoot)}
            workspace_package=${sh(workspacePackage)}
            ready=${sh(ready)}
            log=${sh(log)}
            source_manager=${sh(sourceManager)}
            package_root=${sh(layout.packageRoot)}
            toolchain_root='/root/siftalpha/toolchains'
            node_version='${ManagedNodeToolchainShell.VERSION}'
            node_base_url='${ManagedNodeToolchainShell.BASE_URL}'
            mkdir -p /root/siftalpha/logs "${'$'}work_root" "${'$'}toolchain_root"
            : >"${'$'}log"
            rm -f -- "${'$'}ready"

            if [ ! -f "${'$'}project/${layout.packageJsonPath}" ]; then
              echo 'SIFTALPHA_NODE_DIAG=NODE_PACKAGE_MANIFEST_MISSING'
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_ENV=NOT_READY'
              exit 82
            fi

            ${ManagedNodeToolchainShell.helpers()}
            if ! ensure_node_toolchain; then
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_ENV=NOT_READY'
              tail -n 100 "${'$'}log" 2>/dev/null || true
              exit 84
            fi
            activate_node_toolchain
            printf '[SiftAlpha] Node.js %s / npm %s\n' "${'$'}(node --version 2>/dev/null || true)" "${'$'}(npm --version 2>/dev/null || true)" >>"${'$'}log"

            cat >"${'$'}source_manager" <<'SIFTALPHA_NODE_SOURCE_MANAGER'
${sourceManagerJavaScript()}
SIFTALPHA_NODE_SOURCE_MANAGER
            chmod 600 "${'$'}source_manager"

            source_hash="${'$'}(node "${'$'}source_manager" fingerprint "${'$'}project")" || {
              echo 'SIFTALPHA_NODE_DIAG=NODE_SOURCE_FINGERPRINT_FAILED'
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_ENV=NOT_READY'
              exit 89
            }

            echo '[SiftAlpha] 同步 Node.js 项目到 Runtime 本地工作区...' >>"${'$'}log"
            if ! node "${'$'}source_manager" sync "${'$'}project" "${'$'}workspace_root" "${'$'}work_root/source-manifest.json" >>"${'$'}log" 2>&1; then
              echo 'SIFTALPHA_NODE_DIAG=NODE_WORKSPACE_SYNC_FAILED'
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_NODE_LOG_TAIL_BEGIN'
              tail -n 120 "${'$'}log" 2>/dev/null || true
              echo 'SIFTALPHA_NODE_LOG_TAIL_END'
              echo 'SIFTALPHA_ENV=NOT_READY'
              exit 89
            fi

            if [ ! -f "${'$'}workspace_package/package.json" ]; then
              echo 'SIFTALPHA_NODE_DIAG=NODE_WORKSPACE_PACKAGE_MISSING'
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_ENV=NOT_READY'
              exit 89
            fi

            if [ ${if (declaredRun.isNotBlank()) "0" else "1"} -eq 1 ]; then
              if ! node -e "const p=require(process.argv[1]); process.exit(p.scripts && typeof p.scripts.start === 'string' && p.scripts.start.trim() ? 0 : 1)" "${'$'}workspace_package/package.json"; then
                echo 'SIFTALPHA_NODE_DIAG=NODE_START_CONTRACT_MISSING'
                echo 'SIFTALPHA_NODE_ENV=NOT_READY'
                echo 'SIFTALPHA_ENV=NOT_READY'
                exit 90
              fi
            fi

            cd "${'$'}workspace_package"
            echo '[SiftAlpha] ${manager.installCommand}' >>"${'$'}log"
            npm_result=0
            npm_config_engine_strict=true ${manager.installCommand} >>"${'$'}log" 2>&1 || npm_result=${'$'}?
            if [ "${'$'}npm_result" -ne 0 ]; then
              if tail -n 160 "${'$'}log" | grep -Eqi 'EBADENGINE|Unsupported engine|not compatible with your version of node'; then
                echo 'SIFTALPHA_NODE_DIAG=NODE_ENGINE_MISMATCH'
              else
                echo 'SIFTALPHA_NODE_DIAG=NPM_INSTALL_FAILED'
              fi
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_NODE_LOG_TAIL_BEGIN'
              tail -n 120 "${'$'}log" 2>/dev/null || true
              echo 'SIFTALPHA_NODE_LOG_TAIL_END'
              echo 'SIFTALPHA_ENV=NOT_READY'
              exit 85
            fi

            umask 077
            {
              printf 'SOURCE_HASH=%s\n' "${'$'}source_hash"
              printf 'NODE_VERSION=%s\n' "${'$'}node_version"
              printf 'PACKAGE_ROOT=%s\n' "${'$'}package_root"
              printf 'PACKAGE_MANAGER=NPM\n'
              printf 'INSTALL_MODE=${installMode}\n'
              printf 'START_MODE=${startMode}\n'
            } >"${'$'}ready"

            echo 'SIFTALPHA_NODE_ENV=READY'
            echo 'SIFTALPHA_NODE_PRIMARY=1'
            printf 'SIFTALPHA_NODE_VERSION=%s\n' "${'$'}node_version"
            printf 'SIFTALPHA_NODE_PACKAGE_ROOT=%s\n' "${'$'}package_root"
            echo 'SIFTALPHA_NODE_PACKAGE_MANAGER=NPM'
            printf 'SIFTALPHA_NODE_START_MODE=${startMode}\n'
            echo 'SIFTALPHA_ENV=READY'
            tail -n 80 "${'$'}log" 2>/dev/null || true
        """.trimIndent()

        return RuntimeCommand(
            shellScript = host.wrapUbuntu(inner),
            label = "${project.name} · 准备 Node.js 环境",
            description = "准备 Runtime 管理的 Node.js/npm、Runtime 本地依赖与可执行工作区。",
        )
    }

    override fun start(project: RuntimeProjectSpec): RuntimeCommand {
        val layout = resolveLayout(project) ?: return layoutError(project)
        val manager = packageManager(project, layout)
        if (manager !is NodePackageManagerPolicy.Result.Supported) {
            return packageManagerError(project, manager)
        }

        val id = host.runtimeId(project.folderName)
        val source = "/root/projects/${project.folderName}"
        val workRoot = "/root/siftalpha/node-exec-workspaces/$id"
        val workspaceRoot = "$workRoot/repo"
        val workspacePackage = if (layout.packageRoot == ".") workspaceRoot else "$workspaceRoot/${layout.packageRoot}"
        val ready = "/root/siftalpha/node-exec-ready-$id.txt"
        val log = "/root/siftalpha/logs/prepare-$id.log"
        val sourceManager = "$workRoot/source-manager.cjs"
        val declaredRun = project.declaredRun?.trim().orEmpty()

        val preflight = """
            project=${sh(source)}
            work_root=${sh(workRoot)}
            workspace_root=${sh(workspaceRoot)}
            workspace_package=${sh(workspacePackage)}
            ready=${sh(ready)}
            log=${sh(log)}
            source_manager=${sh(sourceManager)}
            package_root=${sh(layout.packageRoot)}
            toolchain_root='/root/siftalpha/toolchains'
            node_version='${ManagedNodeToolchainShell.VERSION}'
            node_base_url='${ManagedNodeToolchainShell.BASE_URL}'
            ${ManagedNodeToolchainShell.helpers()}
            ${readinessCheckShell()}
            if [ "${'$'}node_exec_ready" -ne 1 ]; then
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              printf 'SIFTALPHA_NODE_ENV_REASON=%s\n' "${'$'}node_exec_reason"
              echo 'SIFTALPHA_ENV=NOT_READY'
              printf 'SIFTALPHA_ENV_REASON=NODE_%s\n' "${'$'}node_exec_reason"
              echo 'SIFTALPHA_ERROR=NODE_ENV_NOT_READY'
              exit 73
            fi
            echo 'SIFTALPHA_NODE_ENV=READY'
            printf 'SIFTALPHA_NODE_VERSION=%s\n' "${'$'}node_version"
            echo 'SIFTALPHA_ENV=READY'
        """.trimIndent()

        val environment = """
            toolchain_root='/root/siftalpha/toolchains'
            node_version='${ManagedNodeToolchainShell.VERSION}'
            case "${'$'}(uname -m 2>/dev/null || true)" in
              aarch64|arm64) node_arch='arm64' ;;
              x86_64|amd64) node_arch='x64' ;;
              *)
                echo 'SIFTALPHA_NODE_DIAG=NODE_ARCH_UNSUPPORTED'
                exit 84
                ;;
            esac
            node_dir="${'$'}toolchain_root/node-v${'$'}node_version-linux-${'$'}node_arch"
            export PATH="${'$'}node_dir/bin:${'$'}PATH"
        """.trimIndent()

        val workload = if (declaredRun.isNotBlank()) {
            """
                configured_run=${sh(declaredRun)}
                printf 'COMMAND=%s\n' "${'$'}configured_run"
                bash -c "${'$'}configured_run"
            """.trimIndent()
        } else {
            """
                echo 'COMMAND=npm start'
                npm start
            """.trimIndent()
        }

        return ManagedProcessRuntime.start(
            host = host,
            project = project,
            runtimeId = id,
            workdir = workspacePackage,
            preflightShell = preflight,
            environmentShell = environment,
            workloadShell = workload,
        )
    }

    override fun stop(project: RuntimeProjectSpec): RuntimeCommand =
        ManagedProcessRuntime.stop(host, project, host.runtimeId(project.folderName))

    override fun status(project: RuntimeProjectSpec): RuntimeCommand {
        val layout = resolveLayout(project) ?: return layoutError(project)
        val id = host.runtimeId(project.folderName)
        val source = "/root/projects/${project.folderName}"
        val workRoot = "/root/siftalpha/node-exec-workspaces/$id"
        val workspaceRoot = "$workRoot/repo"
        val workspacePackage = if (layout.packageRoot == ".") workspaceRoot else "$workspaceRoot/${layout.packageRoot}"
        val ready = "/root/siftalpha/node-exec-ready-$id.txt"
        val log = "/root/siftalpha/logs/prepare-$id.log"
        val sourceManager = "$workRoot/source-manager.cjs"
        val state = ManagedProcessRuntime.defaultGuestPaths(id).state

        val guestStatus = """
            project=${sh(source)}
            work_root=${sh(workRoot)}
            workspace_root=${sh(workspaceRoot)}
            workspace_package=${sh(workspacePackage)}
            ready=${sh(ready)}
            log=${sh(log)}
            source_manager=${sh(sourceManager)}
            package_root=${sh(layout.packageRoot)}
            state=${sh(state)}
            toolchain_root='/root/siftalpha/toolchains'
            node_version='${ManagedNodeToolchainShell.VERSION}'
            node_base_url='${ManagedNodeToolchainShell.BASE_URL}'
            ${ManagedNodeToolchainShell.helpers()}
            ${readinessCheckShell()}
            if [ "${'$'}node_exec_ready" -eq 1 ]; then
              echo 'SIFTALPHA_NODE_ENV=READY'
              echo 'SIFTALPHA_NODE_PRIMARY=1'
              printf 'SIFTALPHA_NODE_VERSION=%s\n' "${'$'}node_version"
              printf 'SIFTALPHA_NODE_PACKAGE_ROOT=%s\n' "${'$'}package_root"
              echo 'SIFTALPHA_ENV=READY'
            else
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              printf 'SIFTALPHA_NODE_ENV_REASON=%s\n' "${'$'}node_exec_reason"
              echo 'SIFTALPHA_ENV=NOT_READY'
              printf 'SIFTALPHA_ENV_REASON=NODE_%s\n' "${'$'}node_exec_reason"
            fi
            if [ -f "${'$'}state" ]; then
              cat "${'$'}state"
            fi
        """.trimIndent()

        return ManagedProcessRuntime.status(host, project, id, guestStatus)
    }

    override fun logs(project: RuntimeProjectSpec): RuntimeCommand =
        ManagedProcessRuntime.logs(host, project, host.runtimeId(project.folderName))

    override fun clean(project: RuntimeProjectSpec): RuntimeCommand {
        val id = host.runtimeId(project.folderName)
        val workRoot = "/root/siftalpha/node-exec-workspaces/$id"
        val ready = "/root/siftalpha/node-exec-ready-$id.txt"
        val prepareLog = "/root/siftalpha/logs/prepare-$id.log"
        val paths = ManagedProcessRuntime.defaultGuestPaths(id)
        val guestClean = """
            rm -rf -- ${sh(workRoot)}
            rm -f -- ${sh(ready)} ${sh(prepareLog)} ${sh(paths.runLog)} ${sh(paths.runner)} ${sh(paths.state)} ${sh(paths.secrets)}
            echo 'SIFTALPHA_NODE_ENV=CLEANED'
            echo 'SIFTALPHA_NODE_RUNTIME_DATA=CLEANED'
        """.trimIndent()
        return ManagedProcessRuntime.clean(host, project, id, guestClean)
    }

    private data class Layout(
        val packageJsonPath: String,
        val packageRoot: String,
    )

    private fun resolveLayout(project: RuntimeProjectSpec): Layout? {
        val packageJsons = project.relativePaths
            .asSequence()
            .map { it.replace('\\', '/').trim().trim('/') }
            .filter { it.isNotBlank() && it.substringAfterLast('/').equals("package.json", ignoreCase = true) }
            .filterNot { path -> path.split('/').any { it in IGNORED_DIRECTORIES } }
            .distinct()
            .sorted()
            .toList()
        val root = packageJsons.firstOrNull { '/' !in it }
        val selected = root ?: packageJsons.singleOrNull() ?: return null
        val packageRoot = selected.substringBeforeLast('/', missingDelimiterValue = ".").ifBlank { "." }
        return Layout(selected, packageRoot)
    }

    private fun packageManager(
        project: RuntimeProjectSpec,
        layout: Layout,
    ): NodePackageManagerPolicy.Result {
        val prefix = if (layout.packageRoot == ".") "" else "${layout.packageRoot}/"
        val packageFiles = project.relativePaths.mapNotNull { raw ->
            val path = raw.replace('\\', '/').trim().trim('/')
            if (!path.startsWith(prefix)) return@mapNotNull null
            val within = path.removePrefix(prefix)
            within.takeIf { '/' !in it }
        }
        return NodePackageManagerPolicy.resolve(packageFiles)
    }

    private fun layoutError(project: RuntimeProjectSpec): RuntimeCommand = errorCommand(
        project = project,
        code = 82,
        lines = listOf(
            "SIFTALPHA_NODE_DIAG=NODE_EXECUTABLE_ROOT_AMBIGUOUS",
            "SIFTALPHA_NODE_ENV=NOT_READY",
            "SIFTALPHA_ENV=NOT_READY",
            "SIFTALPHA_ERROR=NODE_EXECUTABLE_ROOT_REQUIRED",
        ),
    )

    private fun packageManagerError(
        project: RuntimeProjectSpec,
        result: NodePackageManagerPolicy.Result,
    ): RuntimeCommand {
        val lines = when (result) {
            is NodePackageManagerPolicy.Result.Unsupported -> listOf(
                "SIFTALPHA_NODE_DIAG=NODE_PACKAGE_MANAGER_UNSUPPORTED",
                "SIFTALPHA_NODE_PACKAGE_MANAGER=${result.manager.name}",
                "SIFTALPHA_NODE_PACKAGE_MANAGER_EVIDENCE=${result.evidence}",
                "SIFTALPHA_NODE_ENV=NOT_READY",
                "SIFTALPHA_ENV=NOT_READY",
            )
            is NodePackageManagerPolicy.Result.Ambiguous -> listOf(
                "SIFTALPHA_NODE_DIAG=NODE_PACKAGE_MANAGER_AMBIGUOUS",
                "SIFTALPHA_NODE_PACKAGE_MANAGER_EVIDENCE=${result.evidence.joinToString(",")}",
                "SIFTALPHA_NODE_ENV=NOT_READY",
                "SIFTALPHA_ENV=NOT_READY",
            )
            is NodePackageManagerPolicy.Result.Supported -> error("supported manager is not an error")
        }
        return errorCommand(project, 82, lines)
    }

    private fun errorCommand(
        project: RuntimeProjectSpec,
        code: Int,
        lines: List<String>,
    ): RuntimeCommand = RuntimeCommand(
        shellScript = buildString {
            appendLine("set +e")
            lines.forEach { appendLine("echo ${sh(it)}") }
            append("exit $code")
        },
        label = "${project.name} · Node.js Runtime",
        description = "Node.js 项目执行契约不完整或不受支持。",
    )

    /**
     * Requires project/work_root/workspace_package/ready/source_manager/toolchain vars and the managed
     * Node helper functions. Sets node_exec_ready=0/1 and node_exec_reason without mutating project source.
     */
    private fun readinessCheckShell(): String = """
        node_exec_ready=0
        node_exec_reason='NODE_RUNTIME_MISSING'
        if resolve_node_toolchain && [ -x "${'$'}node_dir/bin/node" ] && [ -x "${'$'}node_dir/bin/npm" ]; then
          activate_node_toolchain
          if [ "${'$'}(node --version 2>/dev/null || true)" != "v${'$'}node_version" ]; then
            node_exec_reason='NODE_RUNTIME_VERSION_CHANGED'
          elif [ ! -f "${'$'}ready" ]; then
            node_exec_reason='PREPARE_MARKER_MISSING'
          elif [ ! -f "${'$'}source_manager" ]; then
            node_exec_reason='SOURCE_MANAGER_MISSING'
          elif [ ! -f "${'$'}workspace_package/package.json" ]; then
            node_exec_reason='WORKSPACE_MISSING'
          else
            current_hash="${'$'}(node "${'$'}source_manager" fingerprint "${'$'}project" 2>/dev/null || true)"
            saved_hash="${'$'}(awk -F= '/^SOURCE_HASH=/{print substr(${ '$' }0,13); exit}' "${'$'}ready" 2>/dev/null || true)"
            saved_node="${'$'}(awk -F= '/^NODE_VERSION=/{print substr(${ '$' }0,14); exit}' "${'$'}ready" 2>/dev/null || true)"
            saved_root="${'$'}(awk -F= '/^PACKAGE_ROOT=/{print substr(${ '$' }0,14); exit}' "${'$'}ready" 2>/dev/null || true)"
            if [ -z "${'$'}current_hash" ]; then
              node_exec_reason='SOURCE_FINGERPRINT_FAILED'
            elif [ "${'$'}saved_node" != "${'$'}node_version" ]; then
              node_exec_reason='NODE_RUNTIME_VERSION_CHANGED'
            elif [ "${'$'}saved_root" != "${'$'}package_root" ]; then
              node_exec_reason='PACKAGE_ROOT_CHANGED'
            elif [ "${'$'}saved_hash" != "${'$'}current_hash" ]; then
              node_exec_reason='SOURCE_CHANGED'
            else
              node_exec_ready=1
              node_exec_reason='READY'
            fi
          fi
        fi
    """.trimIndent()

    /**
     * A small Node utility gives us filename-safe source fingerprints and an overlay sync that removes
     * only stale authoritative source paths. Runtime-created files that never came from project source
     * therefore survive PREPARE, while deleted source files cannot remain as stale executable code.
     */
    private fun sourceManagerJavaScript(): String = """
        'use strict';
        const fs = require('fs');
        const path = require('path');
        const crypto = require('crypto');

        const ignoredDirs = new Set(['.git', 'node_modules']);

        function inside(root, candidate) {
          const rel = path.relative(root, candidate);
          return rel === '' || (!rel.startsWith('..' + path.sep) && rel !== '..' && !path.isAbsolute(rel));
        }

        function walk(root) {
          const out = [];
          function visit(dir, relDir) {
            const entries = fs.readdirSync(dir, { withFileTypes: true })
              .sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
            for (const entry of entries) {
              if (entry.isDirectory() && ignoredDirs.has(entry.name)) continue;
              const rel = relDir ? relDir + '/' + entry.name : entry.name;
              const abs = path.join(dir, entry.name);
              const stat = fs.lstatSync(abs);
              if (stat.isDirectory()) {
                visit(abs, rel);
              } else if (stat.isFile()) {
                out.push({ rel, type: 'file', mode: stat.mode & 0o777 });
              } else if (stat.isSymbolicLink()) {
                const target = fs.readlinkSync(abs);
                if (path.isAbsolute(target)) throw new Error('absolute symlink is not allowed: ' + rel);
                const resolved = path.resolve(path.dirname(abs), target);
                if (!inside(root, resolved)) throw new Error('escaping symlink is not allowed: ' + rel);
                out.push({ rel, type: 'symlink', target });
              }
            }
          }
          visit(root, '');
          return out;
        }

        function fingerprint(root) {
          const hash = crypto.createHash('sha256');
          for (const item of walk(root)) {
            hash.update(item.type); hash.update('\0');
            hash.update(item.rel); hash.update('\0');
            if (item.type === 'file') {
              const content = fs.readFileSync(path.join(root, ...item.rel.split('/')));
              hash.update(crypto.createHash('sha256').update(content).digest('hex'));
            } else {
              hash.update(item.target);
            }
            hash.update('\0');
          }
          return hash.digest('hex');
        }

        function safeDest(root, rel) {
          const dest = path.resolve(root, ...rel.split('/'));
          if (!inside(root, dest)) throw new Error('unsafe destination: ' + rel);
          return dest;
        }

        function sync(source, dest, manifestPath) {
          fs.mkdirSync(dest, { recursive: true });
          const current = walk(source);
          let previous = [];
          try {
            previous = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
            if (!Array.isArray(previous)) previous = [];
          } catch (_) {}
          const currentSet = new Set(current.map(x => x.rel));

          for (const rel of previous) {
            if (typeof rel !== 'string' || currentSet.has(rel)) continue;
            const target = safeDest(dest, rel);
            try {
              const stat = fs.lstatSync(target);
              if (stat.isDirectory()) fs.rmSync(target, { recursive: true, force: true });
              else fs.unlinkSync(target);
            } catch (error) {
              if (error && error.code !== 'ENOENT') throw error;
            }
          }

          for (const item of current) {
            const sourcePath = path.join(source, ...item.rel.split('/'));
            const target = safeDest(dest, item.rel);
            fs.mkdirSync(path.dirname(target), { recursive: true });
            try { fs.rmSync(target, { recursive: true, force: true }); } catch (_) {}
            if (item.type === 'file') {
              fs.copyFileSync(sourcePath, target);
              try { fs.chmodSync(target, item.mode); } catch (_) {}
            } else {
              fs.symlinkSync(item.target, target);
            }
          }

          const tmp = manifestPath + '.tmp';
          fs.writeFileSync(tmp, JSON.stringify(current.map(x => x.rel)) + '\n', { mode: 0o600 });
          fs.renameSync(tmp, manifestPath);
        }

        const [command, a, b, c] = process.argv.slice(2);
        if (command === 'fingerprint') {
          process.stdout.write(fingerprint(path.resolve(a)) + '\n');
        } else if (command === 'sync') {
          sync(path.resolve(a), path.resolve(b), path.resolve(c));
        } else {
          throw new Error('unknown source-manager command');
        }
    """.trimIndent()

    private fun sh(value: String): String = host.sh(value)

    companion object {
        private val IGNORED_DIRECTORIES = setOf(
            ".git",
            "node_modules",
            "dist",
            "build",
            ".gradle",
            "target",
            ".venv",
            "venv",
            "__pycache__",
        )
    }
}
