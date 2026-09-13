package com.siftalpha.studio.runtime

import com.siftalpha.studio.project.V04ProjectGateway

/**
 * Multi-Runtime storage inspection and maintenance.
 *
 * Project source lives in Android shared storage and is never deleted here. Runtime-owned data is
 * classified by component so Python, Node.js and future executable Runtimes can share one storage
 * manager without pretending every project owns a Python venv.
 */
class RuntimeStorageController(private val gateway: V04ProjectGateway) {

    data class ComponentUsage(
        val componentId: String,
        val sizeKb: Long,
    )

    data class ProjectUsage(
        val folderName: String,
        val runtimeId: String,
        val sizeKb: Long,
        val components: List<ComponentUsage> = emptyList(),
    )

    data class OrphanUsage(
        val runtimeId: String,
        val sizeKb: Long,
        val components: List<ComponentUsage> = emptyList(),
    )

    data class Snapshot(
        val ubuntuInstalled: Boolean,
        val ubuntuTotalKb: Long,
        val ubuntuBaseKb: Long,
        val projectRuntimeKb: Long,
        val sharedRuntimeDataKb: Long,
        val toolchainsKb: Long,
        val downloadCacheKb: Long,
        val pipCacheKb: Long,
        val npmCacheKb: Long,
        val aptCacheKb: Long,
        val projects: List<ProjectUsage>,
        val orphans: List<OrphanUsage>,
    ) {
        /** Compatibility aliases for callers/tests from the Python-only storage model. */
        val projectEnvironmentsKb: Long get() = projectRuntimeKb
        val runtimeDataKb: Long get() = sharedRuntimeDataKb + toolchainsKb
    }

    internal data class ProjectDirectoryComponent(
        val id: String,
        val guestRoot: String,
    )

    private val host: RuntimeCommandHost = TermuxProotRuntimeHost(gateway)

    fun runtimeSupported(): Boolean = host.runtimeSupported()

    fun snapshot(projects: List<V04ProjectGateway.RuntimeProject>): RuntimeCommand {
        val known = projects.map { it.folderName to host.runtimeId(it.folderName) }
        val projectLines = known.joinToString("\n") { (folder, id) ->
            "report_project ${host.sh(folder)} ${host.sh(id)}"
        }
        val knownIds = known.joinToString("|") { it.second }
        val componentSpecs = PROJECT_DIRECTORY_COMPONENTS.joinToString(" ") { "${it.id}:${it.guestRoot}" }
        val script = """
            set -e
            if ! command -v proot-distro >/dev/null 2>&1; then
              echo 'SIFTALPHA_STORAGE_ERROR=PROOT_DISTRO_MISSING'
              exit 71
            fi
            ${rootfsLocatorScript()}

            size_kb() {
              target="${'$'}1"
              if [ -e "${'$'}target" ]; then
                du -sk "${'$'}target" 2>/dev/null | awk '{print ${'$'}1 + 0}'
              else
                echo 0
              fi
            }

            download_cache_kb=0
            if [ -d "${'$'}pd_runtime/cache" ]; then
              download_cache_kb=${'$'}((download_cache_kb + ${'$'}(size_kb "${'$'}pd_runtime/cache")))
            fi
            if [ -d "${'$'}pd_runtime/dlcache" ]; then
              download_cache_kb=${'$'}((download_cache_kb + ${'$'}(size_kb "${'$'}pd_runtime/dlcache")))
            fi
            printf 'SIFTALPHA_STORAGE_CACHE_KB=%s\n' "${'$'}download_cache_kb"

            if [ -z "${'$'}rootfs" ]; then
              echo 'SIFTALPHA_STORAGE_UBUNTU=NOT_INSTALLED'
              exit 0
            fi

            component_specs=${host.sh(componentSpecs)}
            siftalpha_root="${'$'}rootfs/root/siftalpha"
            toolchains="${'$'}siftalpha_root/toolchains"
            pip_cache="${'$'}rootfs/root/.cache/pip"
            npm_cache="${'$'}rootfs/root/.npm/_cacache"
            apt_cache="${'$'}rootfs/var/cache/apt/archives"

            project_meta_kb() {
              rid="${'$'}1"
              amount=0
              for target in \
                "${'$'}siftalpha_root/logs/run-${'$'}rid.log" \
                "${'$'}siftalpha_root/logs/prepare-${'$'}rid.log" \
                "${'$'}siftalpha_root/logs/clone-${'$'}rid.log" \
                "${'$'}siftalpha_root/state-${'$'}rid.txt" \
                "${'$'}siftalpha_root/run-${'$'}rid.sh" \
                "${'$'}siftalpha_root/secrets-${'$'}rid.env" \
                "${'$'}siftalpha_root/env-ready-${'$'}rid.txt" \
                "${'$'}siftalpha_root/node-ready-${'$'}rid.txt" \
                "${'$'}siftalpha_root/node-exec-ready-${'$'}rid.txt"; do
                [ -e "${'$'}target" ] || continue
                amount=${'$'}((amount + ${'$'}(size_kb "${'$'}target")))
              done
              echo "${'$'}amount"
            }

            report_components() {
              owner_kind="${'$'}1"
              folder="${'$'}2"
              rid="${'$'}3"
              total=0
              for spec in ${'$'}component_specs; do
                component_id="${'$'}{spec%%:*}"
                component_root="${'$'}{spec#*:}"
                amount="${'$'}(size_kb "${'$'}rootfs${'$'}component_root/${'$'}rid")"
                total=${'$'}((total + amount))
                if [ "${'$'}amount" -gt 0 ]; then
                  if [ "${'$'}owner_kind" = 'PROJECT' ]; then
                    printf 'SIFTALPHA_STORAGE_PROJECT_COMPONENT=%s|%s|%s|%s\n' "${'$'}folder" "${'$'}rid" "${'$'}component_id" "${'$'}amount"
                  else
                    printf 'SIFTALPHA_STORAGE_ORPHAN_COMPONENT=%s|%s|%s\n' "${'$'}rid" "${'$'}component_id" "${'$'}amount"
                  fi
                fi
              done
              meta="${'$'}(project_meta_kb "${'$'}rid")"
              total=${'$'}((total + meta))
              if [ "${'$'}meta" -gt 0 ]; then
                if [ "${'$'}owner_kind" = 'PROJECT' ]; then
                  printf 'SIFTALPHA_STORAGE_PROJECT_COMPONENT=%s|%s|%s|%s\n' "${'$'}folder" "${'$'}rid" '${COMPONENT_RUNTIME_STATE}' "${'$'}meta"
                else
                  printf 'SIFTALPHA_STORAGE_ORPHAN_COMPONENT=%s|%s|%s\n' "${'$'}rid" '${COMPONENT_RUNTIME_STATE}' "${'$'}meta"
                fi
              fi
              printf '%s\n' "${'$'}total"
            }

            report_project() {
              folder="${'$'}1"
              rid="${'$'}2"
              component_output="${'$'}(report_components PROJECT "${'$'}folder" "${'$'}rid")"
              total="${'$'}(printf '%s\n' "${'$'}component_output" | tail -n 1)"
              printf '%s\n' "${'$'}component_output" | sed '${'$'}d'
              printf 'SIFTALPHA_STORAGE_PROJECT=%s|%s|%s\n' "${'$'}folder" "${'$'}rid" "${'$'}{total:-0}"
            }

            report_orphan() {
              rid="${'$'}1"
              component_output="${'$'}(report_components ORPHAN '' "${'$'}rid")"
              total="${'$'}(printf '%s\n' "${'$'}component_output" | tail -n 1)"
              printf '%s\n' "${'$'}component_output" | sed '${'$'}d'
              printf 'SIFTALPHA_STORAGE_ORPHAN=%s|%s\n' "${'$'}rid" "${'$'}{total:-0}"
            }

            total_kb="${'$'}(size_kb "${'$'}rootfs")"
            siftalpha_kb="${'$'}(size_kb "${'$'}siftalpha_root")"
            toolchains_kb="${'$'}(size_kb "${'$'}toolchains")"
            pip_cache_kb="${'$'}(size_kb "${'$'}pip_cache")"
            npm_cache_kb="${'$'}(size_kb "${'$'}npm_cache")"
            apt_cache_kb="${'$'}(size_kb "${'$'}apt_cache")"

            project_dirs_kb=0
            siftalpha_project_dirs_kb=0
            outside_siftalpha_project_dirs_kb=0
            python_venv_kb=0
            for spec in ${'$'}component_specs; do
              component_id="${'$'}{spec%%:*}"
              component_root="${'$'}{spec#*:}"
              amount="${'$'}(size_kb "${'$'}rootfs${'$'}component_root")"
              project_dirs_kb=${'$'}((project_dirs_kb + amount))
              [ "${'$'}component_id" = '${COMPONENT_PYTHON_VENV}' ] && python_venv_kb="${'$'}amount"
              case "${'$'}component_root" in
                /root/siftalpha/*) siftalpha_project_dirs_kb=${'$'}((siftalpha_project_dirs_kb + amount)) ;;
                *) outside_siftalpha_project_dirs_kb=${'$'}((outside_siftalpha_project_dirs_kb + amount)) ;;
              esac
            done

            metadata_total_kb=0
            for target in \
              "${'$'}siftalpha_root"/logs/run-*.log \
              "${'$'}siftalpha_root"/logs/prepare-*.log \
              "${'$'}siftalpha_root"/logs/clone-*.log \
              "${'$'}siftalpha_root"/state-*.txt \
              "${'$'}siftalpha_root"/run-*.sh \
              "${'$'}siftalpha_root"/secrets-*.env \
              "${'$'}siftalpha_root"/env-ready-*.txt \
              "${'$'}siftalpha_root"/node-ready-*.txt \
              "${'$'}siftalpha_root"/node-exec-ready-*.txt; do
              [ -e "${'$'}target" ] || continue
              metadata_total_kb=${'$'}((metadata_total_kb + ${'$'}(size_kb "${'$'}target")))
            done

            project_runtime_kb=${'$'}((project_dirs_kb + metadata_total_kb))
            shared_runtime_kb=${'$'}((siftalpha_kb - siftalpha_project_dirs_kb - metadata_total_kb - toolchains_kb))
            [ "${'$'}shared_runtime_kb" -lt 0 ] && shared_runtime_kb=0
            base_kb=${'$'}((total_kb - outside_siftalpha_project_dirs_kb - siftalpha_kb - pip_cache_kb - npm_cache_kb - apt_cache_kb))
            [ "${'$'}base_kb" -lt 0 ] && base_kb=0

            echo 'SIFTALPHA_STORAGE_UBUNTU=INSTALLED'
            printf 'SIFTALPHA_STORAGE_UBUNTU_TOTAL_KB=%s\n' "${'$'}total_kb"
            printf 'SIFTALPHA_STORAGE_UBUNTU_BASE_KB=%s\n' "${'$'}base_kb"
            printf 'SIFTALPHA_STORAGE_PROJECT_RUNTIME_KB=%s\n' "${'$'}project_runtime_kb"
            printf 'SIFTALPHA_STORAGE_SHARED_RUNTIME_KB=%s\n' "${'$'}shared_runtime_kb"
            printf 'SIFTALPHA_STORAGE_TOOLCHAINS_KB=%s\n' "${'$'}toolchains_kb"
            printf 'SIFTALPHA_STORAGE_PIP_CACHE_KB=%s\n' "${'$'}pip_cache_kb"
            printf 'SIFTALPHA_STORAGE_NPM_CACHE_KB=%s\n' "${'$'}npm_cache_kb"
            printf 'SIFTALPHA_STORAGE_APT_CACHE_KB=%s\n' "${'$'}apt_cache_kb"
            # Compatibility output for older parsers.
            printf 'SIFTALPHA_STORAGE_VENVS_KB=%s\n' "${'$'}python_venv_kb"
            printf 'SIFTALPHA_STORAGE_RUNTIME_DATA_KB=%s\n' "${'$'}siftalpha_kb"

            $projectLines

            known_ids=${host.sh(knownIds)}
            {
              for spec in ${'$'}component_specs; do
                component_root="${'$'}{spec#*:}"
                root="${'$'}rootfs${'$'}component_root"
                [ -d "${'$'}root" ] || continue
                for item in "${'$'}root"/*; do
                  [ -d "${'$'}item" ] || continue
                  basename "${'$'}item"
                done
              done
              for target in \
                "${'$'}siftalpha_root"/state-*.txt \
                "${'$'}siftalpha_root"/run-*.sh \
                "${'$'}siftalpha_root"/secrets-*.env \
                "${'$'}siftalpha_root"/env-ready-*.txt \
                "${'$'}siftalpha_root"/node-ready-*.txt \
                "${'$'}siftalpha_root"/node-exec-ready-*.txt; do
                [ -e "${'$'}target" ] || continue
                name="${'$'}(basename "${'$'}target")"
                case "${'$'}name" in
                  state-*.txt) rid="${'$'}{name#state-}"; rid="${'$'}{rid%.txt}" ;;
                  run-*.sh) rid="${'$'}{name#run-}"; rid="${'$'}{rid%.sh}" ;;
                  secrets-*.env) rid="${'$'}{name#secrets-}"; rid="${'$'}{rid%.env}" ;;
                  env-ready-*.txt) rid="${'$'}{name#env-ready-}"; rid="${'$'}{rid%.txt}" ;;
                  node-ready-*.txt) rid="${'$'}{name#node-ready-}"; rid="${'$'}{rid%.txt}" ;;
                  node-exec-ready-*.txt) rid="${'$'}{name#node-exec-ready-}"; rid="${'$'}{rid%.txt}" ;;
                  *) continue ;;
                esac
                printf '%s\n' "${'$'}rid"
              done
              for target in \
                "${'$'}siftalpha_root"/logs/run-*.log \
                "${'$'}siftalpha_root"/logs/prepare-*.log \
                "${'$'}siftalpha_root"/logs/clone-*.log; do
                [ -e "${'$'}target" ] || continue
                name="${'$'}(basename "${'$'}target")"
                case "${'$'}name" in
                  run-*.log) rid="${'$'}{name#run-}"; rid="${'$'}{rid%.log}" ;;
                  prepare-*.log) rid="${'$'}{name#prepare-}"; rid="${'$'}{rid%.log}" ;;
                  clone-*.log) rid="${'$'}{name#clone-}"; rid="${'$'}{rid%.log}" ;;
                  *) continue ;;
                esac
                printf '%s\n' "${'$'}rid"
              done
            } | sort -u | while IFS= read -r rid; do
              case "${'$'}rid" in ''|*[!A-Za-z0-9._-]*) continue ;; esac
              case "|${'$'}known_ids|" in
                *"|${'$'}rid|"*) ;;
                *) report_orphan "${'$'}rid" ;;
              esac
            done
        """.trimIndent()
        return RuntimeCommand(
            shellScript = script,
            label = "Runtime storage scan",
            description = "Inspect multi-Runtime project data, shared toolchains and caches without touching project source.",
        )
    }

    fun clearDownloadCache(): RuntimeCommand {
        val script = """
            set -e
            if ! command -v proot-distro >/dev/null 2>&1; then
              echo 'SIFTALPHA_STORAGE_ERROR=PROOT_DISTRO_MISSING'
              exit 71
            fi
            pd_runtime="${'$'}{TERMUX__PREFIX:-${'$'}PREFIX}/var/lib/proot-distro"
            if proot-distro clear-cache --help >/dev/null 2>&1; then
              proot-distro clear-cache
            elif [ -d "${'$'}pd_runtime/dlcache" ]; then
              rm -rf -- "${'$'}pd_runtime/dlcache"/*
            fi
            echo 'SIFTALPHA_STORAGE_CACHE_CLEARED=1'
        """.trimIndent()
        return RuntimeCommand(script, "Clear runtime download cache", "Clear reusable PRoot-Distro download cache.")
    }

    fun clearPipCache(): RuntimeCommand {
        val script = """
            set -e
            ${requireProotAndRootfsShell()}
            proot-distro login ubuntu -- bash -lc 'if command -v python3 >/dev/null 2>&1 && python3 -m pip --version >/dev/null 2>&1; then python3 -m pip cache purge >/dev/null 2>&1 || rm -rf -- /root/.cache/pip; else rm -rf -- /root/.cache/pip; fi'
            echo 'SIFTALPHA_STORAGE_PIP_CACHE_CLEARED=1'
        """.trimIndent()
        return RuntimeCommand(script, "Clear pip cache", "Clear reusable pip cache without removing installed project dependencies.")
    }

    fun clearNpmCache(): RuntimeCommand {
        val script = """
            set -e
            ${requireProotAndRootfsShell()}
            rm -rf -- "${'$'}rootfs/root/.npm/_cacache"
            echo 'SIFTALPHA_STORAGE_NPM_CACHE_CLEARED=1'
        """.trimIndent()
        return RuntimeCommand(script, "Clear npm cache", "Clear reusable npm package cache without removing project node_modules trees.")
    }

    fun clearAptCache(): RuntimeCommand {
        val script = """
            set -e
            ${requireProotAndRootfsShell()}
            proot-distro login ubuntu -- bash -lc 'if command -v apt-get >/dev/null 2>&1; then apt-get clean; else rm -rf -- /var/cache/apt/archives/*.deb /var/cache/apt/archives/partial/* 2>/dev/null || true; fi'
            echo 'SIFTALPHA_STORAGE_APT_CACHE_CLEARED=1'
        """.trimIndent()
        return RuntimeCommand(script, "Clear apt cache", "Clear downloaded apt package archives without uninstalling packages.")
    }

    fun clearToolchains(): RuntimeCommand {
        val script = """
            set -e
            ${activeRuntimeGuardShell("RUNTIME_IN_USE")}
            ${requireProotAndRootfsShell()}
            rm -rf -- "${'$'}rootfs/root/siftalpha/toolchains"
            echo 'SIFTALPHA_STORAGE_TOOLCHAINS_CLEARED=1'
        """.trimIndent()
        return RuntimeCommand(
            script,
            "Clear managed toolchains",
            "Remove shared managed toolchains only when no tracked project Runtime is active.",
        )
    }

    fun cleanOrphan(runtimeId: String): RuntimeCommand {
        val safeId = validatedOrphanIds(listOf(runtimeId)).single()
        return orphanCleanupCommand(listOf(safeId), cleanAll = false)
    }

    fun cleanAllOrphans(runtimeIds: List<String>): RuntimeCommand =
        orphanCleanupCommand(validatedOrphanIds(runtimeIds), cleanAll = true)

    private fun orphanCleanupCommand(runtimeIds: List<String>, cleanAll: Boolean): RuntimeCommand {
        val shellIds = runtimeIds.joinToString(" ") { host.sh(it) }
        val componentSpecs = PROJECT_DIRECTORY_COMPONENTS.joinToString(" ") { "${it.id}:${it.guestRoot}" }
        val script = """
            set -e
            ${rootfsLocatorScript()}
            if [ -z "${'$'}rootfs" ]; then
              echo 'SIFTALPHA_STORAGE_UBUNTU=NOT_INSTALLED'
              exit 0
            fi
            runtime_dir="${'$'}HOME/.siftalpha/runtime"
            component_specs=${host.sh(componentSpecs)}
            for rid in $shellIds; do
              pid="${'$'}(cat "${'$'}runtime_dir/${'$'}rid.pid" 2>/dev/null || true)"
              pgid="${'$'}(cat "${'$'}runtime_dir/${'$'}rid.pgid" 2>/dev/null || true)"
              if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then
                echo 'SIFTALPHA_STORAGE_ERROR=ORPHAN_RUNTIME_IN_USE'
                printf 'SIFTALPHA_RUNTIME_ID=%s\n' "${'$'}rid"
                exit 78
              fi
              if [ -n "${'$'}pgid" ] && kill -0 -- "-${'$'}pgid" 2>/dev/null; then
                echo 'SIFTALPHA_STORAGE_ERROR=ORPHAN_RUNTIME_IN_USE'
                printf 'SIFTALPHA_RUNTIME_ID=%s\n' "${'$'}rid"
                exit 78
              fi
            done

            cleaned=0
            for rid in $shellIds; do
              removed=0
              for spec in ${'$'}component_specs; do
                component_root="${'$'}{spec#*:}"
                target="${'$'}rootfs${'$'}component_root/${'$'}rid"
                if [ -e "${'$'}target" ]; then
                  rm -rf -- "${'$'}target"
                  removed=1
                fi
              done
              siftalpha_root="${'$'}rootfs/root/siftalpha"
              for target in \
                "${'$'}siftalpha_root/logs/run-${'$'}rid.log" \
                "${'$'}siftalpha_root/logs/prepare-${'$'}rid.log" \
                "${'$'}siftalpha_root/logs/clone-${'$'}rid.log" \
                "${'$'}siftalpha_root/state-${'$'}rid.txt" \
                "${'$'}siftalpha_root/run-${'$'}rid.sh" \
                "${'$'}siftalpha_root/secrets-${'$'}rid.env" \
                "${'$'}siftalpha_root/env-ready-${'$'}rid.txt" \
                "${'$'}siftalpha_root/node-ready-${'$'}rid.txt" \
                "${'$'}siftalpha_root/node-exec-ready-${'$'}rid.txt"; do
                if [ -e "${'$'}target" ]; then
                  rm -rf -- "${'$'}target"
                  removed=1
                fi
              done
              rm -f -- "${'$'}runtime_dir/${'$'}rid.pid" "${'$'}runtime_dir/${'$'}rid.pgid" \
                "${'$'}runtime_dir/${'$'}rid.launch.log" "${'$'}runtime_dir/${'$'}rid.secrets.in"
              [ "${'$'}removed" -eq 1 ] && cleaned=${'$'}((cleaned + 1))
            done
            printf 'SIFTALPHA_STORAGE_ORPHANS_CLEANED=%s\n' "${'$'}cleaned"
        """.trimIndent()
        return RuntimeCommand(
            shellScript = script,
            label = if (cleanAll) "Clean all orphan Runtime data" else "Clean orphan Runtime data",
            description = "Remove orphaned multi-Runtime project data after confirming no tracked process is alive.",
        )
    }

    fun uninstallUbuntu(): RuntimeCommand {
        val script = """
            set -e
            ${activeRuntimeGuardShell("RUNTIME_IN_USE")}
            if ! command -v proot-distro >/dev/null 2>&1; then
              echo 'SIFTALPHA_STORAGE_ERROR=PROOT_DISTRO_MISSING'
              exit 71
            fi
            ${rootfsLocatorScript()}
            if [ -z "${'$'}rootfs" ]; then
              echo 'SIFTALPHA_STORAGE_UBUNTU=NOT_INSTALLED'
              exit 0
            fi
            proot-distro remove ubuntu
            rm -rf -- "${'$'}HOME/.siftalpha/runtime"
            echo 'SIFTALPHA_STORAGE_UBUNTU=REMOVED'
        """.trimIndent()
        return RuntimeCommand(
            script,
            "Remove Ubuntu runtime",
            "Remove the shared Ubuntu Runtime while preserving project source in Android shared storage.",
        )
    }

    private fun requireProotAndRootfsShell(): String = """
        if ! command -v proot-distro >/dev/null 2>&1; then
          echo 'SIFTALPHA_STORAGE_ERROR=PROOT_DISTRO_MISSING'
          exit 71
        fi
        ${rootfsLocatorScript()}
        if [ -z "${'$'}rootfs" ]; then
          echo 'SIFTALPHA_STORAGE_UBUNTU=NOT_INSTALLED'
          exit 0
        fi
    """.trimIndent()

    private fun activeRuntimeGuardShell(errorCode: String): String = """
        runtime_dir="${'$'}HOME/.siftalpha/runtime"
        if [ -d "${'$'}runtime_dir" ]; then
          for pid_file in "${'$'}runtime_dir"/*.pid; do
            [ -f "${'$'}pid_file" ] || continue
            rid="${'$'}(basename "${'$'}pid_file" .pid)"
            pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
            pgid="${'$'}(cat "${'$'}runtime_dir/${'$'}rid.pgid" 2>/dev/null || true)"
            if { [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; } || \
               { [ -n "${'$'}pgid" ] && kill -0 -- "-${'$'}pgid" 2>/dev/null; }; then
              echo 'SIFTALPHA_STORAGE_ERROR=$errorCode'
              printf 'SIFTALPHA_RUNTIME_ID=%s\n' "${'$'}rid"
              exit 78
            fi
          done
        fi
    """.trimIndent()

    private fun rootfsLocatorScript(): String = """
        pd_runtime="${'$'}{TERMUX__PREFIX:-${'$'}PREFIX}/var/lib/proot-distro"
        rootfs=''
        if [ -d "${'$'}pd_runtime/containers/ubuntu/rootfs" ]; then
          rootfs="${'$'}pd_runtime/containers/ubuntu/rootfs"
        elif [ -d "${'$'}pd_runtime/installed-rootfs/ubuntu" ]; then
          rootfs="${'$'}pd_runtime/installed-rootfs/ubuntu"
        fi
    """.trimIndent()

    companion object {
        const val COMPONENT_PYTHON_VENV = "PYTHON_VENV"
        const val COMPONENT_NODE_PRIMARY = "NODE_PRIMARY_WORKSPACE"
        const val COMPONENT_NODE_SUPPLEMENTAL = "NODE_SUPPLEMENTAL_WORKSPACE"
        const val COMPONENT_RUNTIME_STATE = "RUNTIME_STATE"

        internal val PROJECT_DIRECTORY_COMPONENTS = listOf(
            ProjectDirectoryComponent(COMPONENT_PYTHON_VENV, "/root/venvs"),
            ProjectDirectoryComponent(COMPONENT_NODE_PRIMARY, "/root/siftalpha/node-exec-workspaces"),
            ProjectDirectoryComponent(COMPONENT_NODE_SUPPLEMENTAL, "/root/siftalpha/node-workspaces"),
        )

        private val SAFE_RUNTIME_ID = Regex("^[A-Za-z0-9._-]+$")
        private val SAFE_COMPONENT_ID = Regex("^[A-Z0-9_]+$")

        internal fun validatedOrphanIds(runtimeIds: List<String>): List<String> {
            val ids = runtimeIds.distinct()
            require(ids.isNotEmpty()) { "No orphan runtime ids" }
            require(ids.all { SAFE_RUNTIME_ID.matches(it) }) { "Unsafe runtime id" }
            return ids
        }

        fun parseSnapshot(stdout: String): Snapshot {
            var installed = false
            var total = 0L
            var base = 0L
            var projectRuntime = 0L
            var sharedRuntime = 0L
            var toolchains = 0L
            var legacyVenvs = 0L
            var legacyRuntimeData = 0L
            var hasProjectRuntime = false
            var hasSharedRuntime = false
            var downloadCache = 0L
            var pipCache = 0L
            var npmCache = 0L
            var aptCache = 0L
            val projectRows = mutableMapOf<Pair<String, String>, Long>()
            val projectComponents = mutableMapOf<Pair<String, String>, MutableList<ComponentUsage>>()
            val orphanRows = mutableMapOf<String, Long>()
            val orphanComponents = mutableMapOf<String, MutableList<ComponentUsage>>()

            stdout.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line == "SIFTALPHA_STORAGE_UBUNTU=INSTALLED" -> installed = true
                    line == "SIFTALPHA_STORAGE_UBUNTU=NOT_INSTALLED" -> installed = false
                    line.startsWith("SIFTALPHA_STORAGE_UBUNTU_TOTAL_KB=") ->
                        total = positiveLong(line.substringAfter('='))
                    line.startsWith("SIFTALPHA_STORAGE_UBUNTU_BASE_KB=") ->
                        base = positiveLong(line.substringAfter('='))
                    line.startsWith("SIFTALPHA_STORAGE_PROJECT_RUNTIME_KB=") -> {
                        projectRuntime = positiveLong(line.substringAfter('='))
                        hasProjectRuntime = true
                    }
                    line.startsWith("SIFTALPHA_STORAGE_SHARED_RUNTIME_KB=") -> {
                        sharedRuntime = positiveLong(line.substringAfter('='))
                        hasSharedRuntime = true
                    }
                    line.startsWith("SIFTALPHA_STORAGE_TOOLCHAINS_KB=") ->
                        toolchains = positiveLong(line.substringAfter('='))
                    line.startsWith("SIFTALPHA_STORAGE_VENVS_KB=") ->
                        legacyVenvs = positiveLong(line.substringAfter('='))
                    line.startsWith("SIFTALPHA_STORAGE_RUNTIME_DATA_KB=") ->
                        legacyRuntimeData = positiveLong(line.substringAfter('='))
                    line.startsWith("SIFTALPHA_STORAGE_CACHE_KB=") ->
                        downloadCache = positiveLong(line.substringAfter('='))
                    line.startsWith("SIFTALPHA_STORAGE_PIP_CACHE_KB=") ->
                        pipCache = positiveLong(line.substringAfter('='))
                    line.startsWith("SIFTALPHA_STORAGE_NPM_CACHE_KB=") ->
                        npmCache = positiveLong(line.substringAfter('='))
                    line.startsWith("SIFTALPHA_STORAGE_APT_CACHE_KB=") ->
                        aptCache = positiveLong(line.substringAfter('='))
                    line.startsWith("SIFTALPHA_STORAGE_PROJECT_COMPONENT=") -> {
                        val parts = line.substringAfter('=').split('|')
                        if (
                            parts.size == 4 &&
                            SAFE_RUNTIME_ID.matches(parts[1]) &&
                            SAFE_COMPONENT_ID.matches(parts[2])
                        ) {
                            val key = parts[0] to parts[1]
                            projectComponents.getOrPut(key) { mutableListOf() } +=
                                ComponentUsage(parts[2], positiveLong(parts[3]))
                        }
                    }
                    line.startsWith("SIFTALPHA_STORAGE_PROJECT=") -> {
                        val parts = line.substringAfter('=').split('|')
                        if (parts.size == 3 && SAFE_RUNTIME_ID.matches(parts[1])) {
                            projectRows[parts[0] to parts[1]] = positiveLong(parts[2])
                        }
                    }
                    line.startsWith("SIFTALPHA_STORAGE_ORPHAN_COMPONENT=") -> {
                        val parts = line.substringAfter('=').split('|')
                        if (
                            parts.size == 3 &&
                            SAFE_RUNTIME_ID.matches(parts[0]) &&
                            SAFE_COMPONENT_ID.matches(parts[1])
                        ) {
                            orphanComponents.getOrPut(parts[0]) { mutableListOf() } +=
                                ComponentUsage(parts[1], positiveLong(parts[2]))
                        }
                    }
                    line.startsWith("SIFTALPHA_STORAGE_ORPHAN=") -> {
                        val parts = line.substringAfter('=').split('|')
                        if (parts.size == 2 && SAFE_RUNTIME_ID.matches(parts[0])) {
                            orphanRows[parts[0]] = positiveLong(parts[1])
                        }
                    }
                }
            }

            if (!hasProjectRuntime) projectRuntime = legacyVenvs
            if (!hasSharedRuntime) sharedRuntime = legacyRuntimeData

            val projects = projectRows.map { (key, size) ->
                ProjectUsage(
                    folderName = key.first,
                    runtimeId = key.second,
                    sizeKb = size,
                    components = projectComponents[key].orEmpty().sortedByDescending { it.sizeKb },
                )
            }.sortedByDescending { it.sizeKb }
            val orphans = orphanRows.map { (runtimeId, size) ->
                OrphanUsage(
                    runtimeId = runtimeId,
                    sizeKb = size,
                    components = orphanComponents[runtimeId].orEmpty().sortedByDescending { it.sizeKb },
                )
            }.sortedByDescending { it.sizeKb }

            return Snapshot(
                ubuntuInstalled = installed,
                ubuntuTotalKb = total,
                ubuntuBaseKb = base,
                projectRuntimeKb = projectRuntime,
                sharedRuntimeDataKb = sharedRuntime,
                toolchainsKb = toolchains,
                downloadCacheKb = downloadCache,
                pipCacheKb = pipCache,
                npmCacheKb = npmCache,
                aptCacheKb = aptCache,
                projects = projects,
                orphans = orphans,
            )
        }

        private fun positiveLong(value: String): Long = (value.toLongOrNull() ?: 0L).coerceAtLeast(0L)
    }
}
