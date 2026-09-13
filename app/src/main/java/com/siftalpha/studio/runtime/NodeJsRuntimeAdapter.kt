package com.siftalpha.studio.runtime

/**
 * Node.js preparation adapter used by polyglot projects whose primary process is still Python.
 *
 * alpha1 deliberately supports one high-confidence build shape: nested Vite applications. Node is
 * installed as a Runtime-managed toolchain, npm dependencies stay on the Ubuntu-local filesystem,
 * and only the resolved Vite build output is synchronized back into the imported project.
 */
class NodeJsRuntimeAdapter(
    private val host: RuntimeCommandHost,
) : RuntimeAdapter {
    override val kind: RuntimeKind = RuntimeKind.NODE_JS

    override val supportedActions: Set<RuntimeAction> = setOf(
        RuntimeAction.PREPARE,
        RuntimeAction.INSTALL_DEPENDENCIES,
        RuntimeAction.STATUS,
        RuntimeAction.CLEAN,
    )

    override val environmentRequirements: List<RuntimeEnvironmentRequirement> = listOf(
        RuntimeEnvironmentRequirement("node", "Node.js runtime"),
        RuntimeEnvironmentRequirement("npm", "npm package manager"),
    )

    fun prepareDetectedWebComponents(project: RuntimeProjectSpec): RuntimeCommand = RuntimeCommand(
        shellScript = host.wrapUbuntu(buildPrepare(project)),
        label = "${project.name} · 准备 Node.js 前端",
        description = "检测并准备项目中的 Node.js Web 构建组件。",
    )

    fun statusDetectedWebComponents(project: RuntimeProjectSpec): RuntimeCommand = RuntimeCommand(
        shellScript = host.wrapUbuntu(buildStatus(project)),
        label = "${project.name} · Node.js 状态",
        description = "检查 Node.js Web 构建组件是否仍然就绪。",
    )

    fun cleanDetectedWebComponents(project: RuntimeProjectSpec): RuntimeCommand = RuntimeCommand(
        shellScript = host.wrapUbuntu(buildClean(project)),
        label = "${project.name} · 清理 Node.js 环境",
        description = "清理 Runtime 本地的 Node.js 工作区和就绪标记，不删除项目源码。",
    )

    private fun buildPrepare(project: RuntimeProjectSpec): String {
        val id = host.runtimeId(project.folderName)
        val path = "/root/projects/${project.folderName}"
        val log = "/root/siftalpha/logs/prepare-$id.log"
        val ready = "/root/siftalpha/node-ready-$id.txt"
        val workRoot = "/root/siftalpha/node-workspaces/$id"
        return """
            set -e
            project=${sh(path)}
            log=${sh(log)}
            ready=${sh(ready)}
            work_root=${sh(workRoot)}
            toolchain_root='/root/siftalpha/toolchains'
            node_version='${MANAGED_NODE_VERSION}'
            node_base_url='${MANAGED_NODE_BASE_URL}'
            mkdir -p /root/siftalpha/logs "${'$'}work_root" "${'$'}toolchain_root"
            touch "${'$'}log"
            rm -f -- "${'$'}ready"

            ${componentHelpersShell()}
            ${nodeToolchainHelpersShell()}

            component_file="${'$'}work_root/.components"
            discover_vite_components >"${'$'}component_file"
            component_count="${'$'}(grep -c . "${'$'}component_file" 2>/dev/null || true)"
            if [ "${'$'}component_count" -eq 0 ]; then
              echo 'SIFTALPHA_NODE_ENV=NOT_REQUIRED'
              echo 'SIFTALPHA_NODE_COMPONENTS=0'
              exit 0
            fi

            echo "[SiftAlpha] Node.js Web 组件: ${'$'}component_count" >>"${'$'}log"
            if ! ensure_node_toolchain; then
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              exit 84
            fi
            activate_node_toolchain
            printf '[SiftAlpha] Node.js %s / npm %s\n' "${'$'}(node --version 2>/dev/null || true)" "${'$'}(npm --version 2>/dev/null || true)" >>"${'$'}log"

            repo_workspace="${'$'}work_root/repo"
            rm -rf -- "${'$'}repo_workspace"
            mkdir -p "${'$'}repo_workspace"
            echo '[SiftAlpha] 创建 Runtime 本地项目构建镜像...' >>"${'$'}log"
            if ! (
              set -o pipefail
              (
                cd "${'$'}project"
                tar --exclude-vcs-ignores \
                    --exclude='./.git' --exclude='*/.git' \
                    --exclude='./node_modules' --exclude='*/node_modules' \
                    --exclude='./dist' --exclude='*/dist' \
                    --exclude='./build' --exclude='*/build' -cf - .
              ) 2>>"${'$'}log" | (
                cd "${'$'}repo_workspace"
                tar -xf -
              ) 2>>"${'$'}log"
            ); then
              echo 'SIFTALPHA_NODE_DIAG=NODE_WORKSPACE_SNAPSHOT_FAILED'
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              tail -n 80 "${'$'}log" 2>/dev/null || true
              rm -rf -- "${'$'}repo_workspace"
              exit 89
            fi

            aggregate="${'$'}work_root/.fingerprints"
            : >"${'$'}aggregate"

            while IFS= read -r package_json; do
              [ -n "${'$'}package_json" ] || continue
              package_dir="${'$'}{package_json%/package.json}"
              if [ "${'$'}package_dir" = "${'$'}project" ]; then
                relative='.'
                workspace_dir="${'$'}repo_workspace"
              else
                relative="${'$'}{package_dir#"${'$'}project"/}"
                workspace_dir="${'$'}repo_workspace/${'$'}relative"
              fi
              component_key="${'$'}(printf '%s' "${'$'}relative" | sha256sum | awk '{print ${'$'}1}')"
              echo "[SiftAlpha] 准备 Node.js 组件: ${'$'}relative" >>"${'$'}log"

              source_output="${'$'}(resolve_vite_output_dir "${'$'}package_dir")" || {
                echo 'SIFTALPHA_NODE_DIAG=VITE_OUTPUT_UNRESOLVED'
                printf 'SIFTALPHA_NODE_COMPONENT=%s\n' "${'$'}relative"
                echo 'SIFTALPHA_NODE_ENV=NOT_READY'
                exit 86
              }
              workspace_output="${'$'}(resolve_vite_output_dir "${'$'}workspace_dir")" || {
                echo 'SIFTALPHA_NODE_DIAG=VITE_OUTPUT_UNRESOLVED'
                printf 'SIFTALPHA_NODE_COMPONENT=%s\n' "${'$'}relative"
                echo 'SIFTALPHA_NODE_ENV=NOT_READY'
                exit 86
              }
              case "${'$'}source_output" in
                "${'$'}project"/*) ;;
                *)
                  echo 'SIFTALPHA_NODE_DIAG=VITE_OUTPUT_OUTSIDE_PROJECT'
                  printf 'SIFTALPHA_NODE_COMPONENT=%s\n' "${'$'}relative"
                  echo 'SIFTALPHA_NODE_ENV=NOT_READY'
                  exit 86
                  ;;
              esac
              case "${'$'}workspace_output" in
                "${'$'}repo_workspace"/*) ;;
                *)
                  echo 'SIFTALPHA_NODE_DIAG=VITE_OUTPUT_OUTSIDE_WORKSPACE'
                  printf 'SIFTALPHA_NODE_COMPONENT=%s\n' "${'$'}relative"
                  echo 'SIFTALPHA_NODE_ENV=NOT_READY'
                  exit 86
                  ;;
              esac

              cd "${'$'}workspace_dir"
              npm_result=0
              if [ -f package-lock.json ]; then
                echo "[SiftAlpha] npm ci: ${'$'}relative" >>"${'$'}log"
                npm_config_engine_strict=true npm ci --no-audit --no-fund >>"${'$'}log" 2>&1 || npm_result=${'$'}?
              else
                echo "[SiftAlpha] npm install: ${'$'}relative" >>"${'$'}log"
                npm_config_engine_strict=true npm install --no-audit --no-fund >>"${'$'}log" 2>&1 || npm_result=${'$'}?
              fi
              if [ "${'$'}npm_result" -ne 0 ]; then
                if tail -n 120 "${'$'}log" | grep -Eqi 'EBADENGINE|Unsupported engine|not compatible with your version of node'; then
                  echo 'SIFTALPHA_NODE_DIAG=NODE_ENGINE_MISMATCH'
                else
                  echo 'SIFTALPHA_NODE_DIAG=NPM_INSTALL_FAILED'
                fi
                printf 'SIFTALPHA_NODE_COMPONENT=%s\n' "${'$'}relative"
                echo 'SIFTALPHA_NODE_ENV=NOT_READY'
                tail -n 120 "${'$'}log" 2>/dev/null || true
                exit 85
              fi

              if ! node -e "const p=require('./package.json'); process.exit(p.scripts && p.scripts.build ? 0 : 1)"; then
                echo 'SIFTALPHA_NODE_DIAG=NODE_BUILD_SCRIPT_MISSING'
                printf 'SIFTALPHA_NODE_COMPONENT=%s\n' "${'$'}relative"
                echo 'SIFTALPHA_NODE_ENV=NOT_READY'
                exit 86
              fi
              echo "[SiftAlpha] npm run build: ${'$'}relative" >>"${'$'}log"
              if ! npm run build >>"${'$'}log" 2>&1; then
                echo 'SIFTALPHA_NODE_DIAG=NODE_BUILD_FAILED'
                printf 'SIFTALPHA_NODE_COMPONENT=%s\n' "${'$'}relative"
                echo 'SIFTALPHA_NODE_ENV=NOT_READY'
                echo 'SIFTALPHA_NODE_LOG_TAIL_BEGIN'
                tail -n 120 "${'$'}log" 2>/dev/null || true
                echo 'SIFTALPHA_NODE_LOG_TAIL_END'
                exit 87
              fi
              if [ ! -f "${'$'}workspace_output/index.html" ]; then
                echo 'SIFTALPHA_NODE_DIAG=NODE_BUILD_OUTPUT_MISSING'
                printf 'SIFTALPHA_NODE_COMPONENT=%s\n' "${'$'}relative"
                echo 'SIFTALPHA_NODE_ENV=NOT_READY'
                tail -n 80 "${'$'}log" 2>/dev/null || true
                exit 88
              fi

              target_tmp="${'$'}source_output.siftalpha-tmp-${'$'}component_key"
              rm -rf -- "${'$'}target_tmp"
              mkdir -p "${'$'}target_tmp"
              cp -a "${'$'}workspace_output/." "${'$'}target_tmp/"
              rm -rf -- "${'$'}source_output"
              mv -- "${'$'}target_tmp" "${'$'}source_output"

              fingerprint="${'$'}(component_fingerprint "${'$'}package_dir")"
              output_relative="${'$'}{source_output#"${'$'}project"/}"
              printf '%s\t%s\t%s\n' "${'$'}relative" "${'$'}fingerprint" "${'$'}output_relative" >>"${'$'}aggregate"
            done <"${'$'}component_file"

            aggregate_hash="${'$'}(sha256sum "${'$'}aggregate" | awk '{print ${'$'}1}')"
            umask 077
            printf 'COUNT=%s\nHASH=%s\nNODE_VERSION=%s\n' "${'$'}component_count" "${'$'}aggregate_hash" "${'$'}node_version" >"${'$'}ready"
            echo 'SIFTALPHA_NODE_ENV=READY'
            printf 'SIFTALPHA_NODE_COMPONENTS=%s\n' "${'$'}component_count"
            printf 'SIFTALPHA_NODE_VERSION=%s\n' "${'$'}node_version"
            tail -n 50 "${'$'}log" 2>/dev/null || true
        """.trimIndent()
    }

    private fun buildStatus(project: RuntimeProjectSpec): String {
        val id = host.runtimeId(project.folderName)
        val path = "/root/projects/${project.folderName}"
        val ready = "/root/siftalpha/node-ready-$id.txt"
        val workRoot = "/root/siftalpha/node-workspaces/$id"
        return """
            project=${sh(path)}
            ready=${sh(ready)}
            work_root=${sh(workRoot)}
            toolchain_root='/root/siftalpha/toolchains'
            node_version='${MANAGED_NODE_VERSION}'
            node_base_url='${MANAGED_NODE_BASE_URL}'
            mkdir -p "${'$'}work_root" "${'$'}toolchain_root"
            ${componentHelpersShell()}
            ${nodeToolchainHelpersShell()}

            component_file="${'$'}work_root/.components-status"
            discover_vite_components >"${'$'}component_file"
            component_count="${'$'}(grep -c . "${'$'}component_file" 2>/dev/null || true)"
            if [ "${'$'}component_count" -eq 0 ]; then
              echo 'SIFTALPHA_NODE_ENV=NOT_REQUIRED'
              echo 'SIFTALPHA_NODE_COMPONENTS=0'
              exit 0
            fi
            if ! resolve_node_toolchain || [ ! -x "${'$'}node_dir/bin/node" ] || [ ! -x "${'$'}node_dir/bin/npm" ]; then
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_NODE_ENV_REASON=NODE_RUNTIME_MISSING'
              exit 0
            fi
            activate_node_toolchain
            if [ "${'$'}(node --version 2>/dev/null || true)" != "v${'$'}node_version" ]; then
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_NODE_ENV_REASON=NODE_RUNTIME_VERSION_CHANGED'
              exit 0
            fi
            if [ ! -f "${'$'}ready" ]; then
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_NODE_ENV_REASON=PREPARE_MARKER_MISSING'
              exit 0
            fi

            aggregate="${'$'}work_root/.fingerprints-status"
            : >"${'$'}aggregate"
            output_missing=0
            output_unresolved=0
            while IFS= read -r package_json; do
              [ -n "${'$'}package_json" ] || continue
              package_dir="${'$'}{package_json%/package.json}"
              if [ "${'$'}package_dir" = "${'$'}project" ]; then
                relative='.'
              else
                relative="${'$'}{package_dir#"${'$'}project"/}"
              fi
              source_output="${'$'}(resolve_vite_output_dir "${'$'}package_dir")" || {
                output_unresolved=1
                continue
              }
              case "${'$'}source_output" in
                "${'$'}project"/*) ;;
                *) output_unresolved=1; continue ;;
              esac
              [ -f "${'$'}source_output/index.html" ] || output_missing=1
              fingerprint="${'$'}(component_fingerprint "${'$'}package_dir")"
              output_relative="${'$'}{source_output#"${'$'}project"/}"
              printf '%s\t%s\t%s\n' "${'$'}relative" "${'$'}fingerprint" "${'$'}output_relative" >>"${'$'}aggregate"
            done <"${'$'}component_file"

            if [ "${'$'}output_unresolved" -ne 0 ]; then
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_NODE_ENV_REASON=VITE_OUTPUT_UNRESOLVED'
              exit 0
            fi
            if [ "${'$'}output_missing" -ne 0 ]; then
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_NODE_ENV_REASON=BUILD_OUTPUT_MISSING'
              exit 0
            fi
            aggregate_hash="${'$'}(sha256sum "${'$'}aggregate" | awk '{print ${'$'}1}')"
            saved_count="${'$'}(awk -F= '/^COUNT=/{print ${'$'}2; exit}' "${'$'}ready" 2>/dev/null || true)"
            saved_hash="${'$'}(awk -F= '/^HASH=/{print ${'$'}2; exit}' "${'$'}ready" 2>/dev/null || true)"
            saved_node="${'$'}(awk -F= '/^NODE_VERSION=/{print ${'$'}2; exit}' "${'$'}ready" 2>/dev/null || true)"
            if [ "${'$'}saved_count" = "${'$'}component_count" ] && \
               [ "${'$'}saved_hash" = "${'$'}aggregate_hash" ] && \
               [ "${'$'}saved_node" = "${'$'}node_version" ]; then
              echo 'SIFTALPHA_NODE_ENV=READY'
              printf 'SIFTALPHA_NODE_COMPONENTS=%s\n' "${'$'}component_count"
              printf 'SIFTALPHA_NODE_VERSION=%s\n' "${'$'}node_version"
            else
              echo 'SIFTALPHA_NODE_ENV=NOT_READY'
              echo 'SIFTALPHA_NODE_ENV_REASON=SOURCE_CHANGED'
            fi
        """.trimIndent()
    }

    private fun buildClean(project: RuntimeProjectSpec): String {
        val id = host.runtimeId(project.folderName)
        return """
            rm -rf -- ${sh("/root/siftalpha/node-workspaces/$id")}
            rm -f -- ${sh("/root/siftalpha/node-ready-$id.txt")}
            echo 'SIFTALPHA_NODE_ENV=CLEANED'
        """.trimIndent()
    }

    private fun componentHelpersShell(): String = """
        discover_vite_components() {
          find "${'$'}project" -maxdepth 7 -type f -name package.json \
            ! -path '*/node_modules/*' ! -path '*/.git/*' \
            ! -path '*/dist/*' ! -path '*/build/*' 2>/dev/null | \
          while IFS= read -r package_json; do
            package_dir="${'$'}{package_json%/package.json}"
            if [ -f "${'$'}package_dir/vite.config.ts" ] || \
               [ -f "${'$'}package_dir/vite.config.js" ] || \
               [ -f "${'$'}package_dir/vite.config.mts" ] || \
               [ -f "${'$'}package_dir/vite.config.mjs" ] || \
               [ -f "${'$'}package_dir/vite.config.cjs" ]; then
              printf '%s\n' "${'$'}package_json"
            fi
          done | LC_ALL=C sort
        }

        find_vite_config() {
          package_dir="${'$'}1"
          for config_name in vite.config.ts vite.config.js vite.config.mts vite.config.mjs vite.config.cjs; do
            if [ -f "${'$'}package_dir/${'$'}config_name" ]; then
              printf '%s\n' "${'$'}package_dir/${'$'}config_name"
              return 0
            fi
          done
          return 1
        }

        resolve_vite_output_dir() {
          package_dir="${'$'}1"
          config="${'$'}(find_vite_config "${'$'}package_dir")" || return 1
          out_rel="${'$'}(sed -nE "s#.*outDir:[[:space:]]*path\\.resolve\\(__dirname,[[:space:]]*['\"]([^'\"]+)['\"]\\).*#\\1#p" "${'$'}config" | head -n 1)"
          if [ -z "${'$'}out_rel" ]; then
            out_rel="${'$'}(sed -nE "s#.*outDir:[[:space:]]*['\"]([^'\"]+)['\"].*#\\1#p" "${'$'}config" | head -n 1)"
          fi
          [ -n "${'$'}out_rel" ] || out_rel='dist'
          case "${'$'}out_rel" in
            /*) return 1 ;;
          esac
          realpath -m "${'$'}package_dir/${'$'}out_rel"
        }

        component_fingerprint() {
          component_dir="${'$'}1"
          (
            cd "${'$'}component_dir" || exit 1
            find . -type f \
              ! -path './node_modules/*' ! -path './dist/*' \
              ! -path './build/*' ! -path './.git/*' -print0 2>/dev/null | \
              LC_ALL=C sort -z | xargs -0 sha256sum 2>/dev/null | sha256sum | awk '{print ${'$'}1}'
          )
        }
    """.trimIndent()

    private fun nodeToolchainHelpersShell(): String = """
        resolve_node_toolchain() {
          case "${'$'}(uname -m 2>/dev/null || true)" in
            aarch64|arm64) node_arch='arm64' ;;
            x86_64|amd64) node_arch='x64' ;;
            *) return 1 ;;
          esac
          node_archive="node-v${'$'}node_version-linux-${'$'}node_arch.tar.xz"
          node_dir="${'$'}toolchain_root/node-v${'$'}node_version-linux-${'$'}node_arch"
          return 0
        }

        activate_node_toolchain() {
          export PATH="${'$'}node_dir/bin:${'$'}PATH"
        }

        ensure_node_toolchain() {
          if ! resolve_node_toolchain; then
            echo 'SIFTALPHA_NODE_DIAG=NODE_ARCH_UNSUPPORTED'
            return 1
          fi
          if [ -x "${'$'}node_dir/bin/node" ] && [ -x "${'$'}node_dir/bin/npm" ] && \
             [ "${'$'}("${'$'}node_dir/bin/node" --version 2>/dev/null || true)" = "v${'$'}node_version" ]; then
            return 0
          fi

          echo "[SiftAlpha] 安装 Runtime 管理的 Node.js v${'$'}node_version (${ '$' }node_arch)..." >>"${'$'}log"
          need_download_tools=0
          command -v curl >/dev/null 2>&1 || need_download_tools=1
          command -v xz >/dev/null 2>&1 || need_download_tools=1
          if [ "${'$'}need_download_tools" -ne 0 ]; then
            export DEBIAN_FRONTEND=noninteractive
            if ! apt-get update >>"${'$'}log" 2>&1 || \
               ! apt-get install -y ca-certificates curl xz-utils >>"${'$'}log" 2>&1; then
              echo 'SIFTALPHA_NODE_DIAG=NODE_TOOLCHAIN_PREREQUISITE_FAILED'
              return 1
            fi
          fi

          download_dir="${'$'}work_root/.node-download"
          install_tmp="${'$'}toolchain_root/.node-install-${'$'}node_version-${'$'}node_arch"
          rm -rf -- "${'$'}download_dir" "${'$'}install_tmp"
          mkdir -p "${'$'}download_dir" "${'$'}install_tmp"
          if ! curl -fL --retry 3 --connect-timeout 20 \
              "${'$'}node_base_url/${'$'}node_archive" -o "${'$'}download_dir/${'$'}node_archive" >>"${'$'}log" 2>&1 || \
             ! curl -fL --retry 3 --connect-timeout 20 \
              "${'$'}node_base_url/SHASUMS256.txt" -o "${'$'}download_dir/SHASUMS256.txt" >>"${'$'}log" 2>&1; then
            echo 'SIFTALPHA_NODE_DIAG=NODE_TOOLCHAIN_DOWNLOAD_FAILED'
            rm -rf -- "${'$'}download_dir" "${'$'}install_tmp"
            return 1
          fi
          if ! (
            cd "${'$'}download_dir" &&
            grep -E "^[0-9a-fA-F]{64}  ${'$'}node_archive${'$'}" SHASUMS256.txt | sha256sum -c -
          ) >>"${'$'}log" 2>&1; then
            echo 'SIFTALPHA_NODE_DIAG=NODE_TOOLCHAIN_CHECKSUM_FAILED'
            rm -rf -- "${'$'}download_dir" "${'$'}install_tmp"
            return 1
          fi
          if ! tar -xJf "${'$'}download_dir/${'$'}node_archive" -C "${'$'}install_tmp" --strip-components=1 >>"${'$'}log" 2>&1 || \
             [ ! -x "${'$'}install_tmp/bin/node" ] || [ ! -x "${'$'}install_tmp/bin/npm" ]; then
            echo 'SIFTALPHA_NODE_DIAG=NODE_TOOLCHAIN_EXTRACT_FAILED'
            rm -rf -- "${'$'}download_dir" "${'$'}install_tmp"
            return 1
          fi
          rm -rf -- "${'$'}node_dir"
          mv -- "${'$'}install_tmp" "${'$'}node_dir"
          rm -rf -- "${'$'}download_dir"
          return 0
        }
    """.trimIndent()

    private fun sh(value: String): String = host.sh(value)

    companion object {
        const val MANAGED_NODE_VERSION = "24.21.0"
        const val MANAGED_NODE_BASE_URL = "https://nodejs.org/dist/v$MANAGED_NODE_VERSION"
    }
}
