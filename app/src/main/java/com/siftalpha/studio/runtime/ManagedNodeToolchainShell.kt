package com.siftalpha.studio.runtime

/**
 * Managed Node.js toolchain contract for executable Runtime adapters.
 *
 * Callers define shell variables `toolchain_root`, `work_root`, `log`, `node_version` and
 * `node_base_url` before using these helpers. The install is verified against Node's official
 * SHASUMS256.txt and never falls back to a distro Node/npm package.
 */
object ManagedNodeToolchainShell {
    const val VERSION = NodeJsRuntimeAdapter.MANAGED_NODE_VERSION
    const val BASE_URL = NodeJsRuntimeAdapter.MANAGED_NODE_BASE_URL

    fun helpers(): String = """
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
}
