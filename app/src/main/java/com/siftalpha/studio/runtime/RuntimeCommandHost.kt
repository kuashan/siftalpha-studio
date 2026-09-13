package com.siftalpha.studio.runtime

import com.siftalpha.studio.project.V04ProjectGateway

/**
 * Host-specific shell facilities used by executable runtime adapters.
 *
 * Language adapters own language/package-manager behavior. The host owns how commands are wrapped
 * for the current Android execution environment. This separation allows a future built-in
 * SiftAlpha Runtime Host to replace Termux/PRoot without redesigning every language adapter.
 */
interface RuntimeCommandHost {
    fun runtimeSupported(): Boolean
    fun runtimeUnsupportedReason(): String
    fun sharedRoot(): String
    fun runtimeId(folderName: String): String
    fun sh(value: String): String
    fun wrapUbuntu(inner: String): String
    fun hostPreamble(): String
    fun hostProcessHelpers(): String
}

/**
 * Installs a tiny Termux-side proot-distro wrapper when Termux exposes a readable resolver file.
 *
 * Android does not guarantee a conventional host /etc/resolv.conf. Termux keeps its own resolver
 * configuration under $PREFIX/etc/resolv.conf; binding that exact file into the PRoot guest keeps
 * DNS aligned with the host without hard-coding public DNS servers. If the file is unavailable,
 * the bridge is not installed and proot-distro keeps its normal behaviour.
 */
internal object TermuxProotDnsBridge {
    fun setupShell(): String = """
        real_proot="${'$'}(command -v proot-distro)"
        termux_resolv=''
        if [ -n "${'$'}{PREFIX:-}" ] && [ -r "${'$'}PREFIX/etc/resolv.conf" ]; then
          termux_resolv="${'$'}PREFIX/etc/resolv.conf"
        fi
        if [ -n "${'$'}termux_resolv" ]; then
          proot_wrapper_dir="${'$'}runtime_dir/bin"
          proot_wrapper="${'$'}proot_wrapper_dir/proot-distro"
          mkdir -p "${'$'}proot_wrapper_dir"
          cat >"${'$'}proot_wrapper" <<'SIFTALPHA_PROOT_WRAPPER'
#!/data/data/com.termux/files/usr/bin/bash
if [ "${'$'}{1:-}" = 'login' ] && [ -n "${'$'}{SIFTALPHA_TERMUX_RESOLV:-}" ] && [ -r "${'$'}SIFTALPHA_TERMUX_RESOLV" ]; then
  shift
  exec "${'$'}SIFTALPHA_REAL_PROOT_DISTRO" login --bind "${'$'}SIFTALPHA_TERMUX_RESOLV:/etc/resolv.conf" "${'$'}@"
fi
exec "${'$'}SIFTALPHA_REAL_PROOT_DISTRO" "${'$'}@"
SIFTALPHA_PROOT_WRAPPER
          chmod 700 "${'$'}proot_wrapper"
          export SIFTALPHA_REAL_PROOT_DISTRO="${'$'}real_proot"
          export SIFTALPHA_TERMUX_RESOLV="${'$'}termux_resolv"
          export PATH="${'$'}proot_wrapper_dir:${'$'}PATH"
        fi
    """.trimIndent()
}

class TermuxProotRuntimeHost(
    private val gateway: V04ProjectGateway,
) : RuntimeCommandHost {

    override fun runtimeSupported(): Boolean = gateway.runtimeSharedRootRelativePath() != null

    override fun runtimeUnsupportedReason(): String =
        "当前运行功能要求项目根目录位于 Android 内部共享存储。" +
            "建议使用 AcodeProjects 或内部存储中的其他目录。"

    override fun sharedRoot(): String {
        val relative = gateway.runtimeSharedRootRelativePath() ?: error(runtimeUnsupportedReason())
        return if (relative.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$relative"
    }

    override fun runtimeId(folderName: String): String {
        val readable = folderName.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .take(30)
            .ifBlank { "project" }
        return "$readable-${Integer.toHexString(folderName.hashCode())}"
    }

    override fun sh(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    override fun wrapUbuntu(inner: String): String = """
        ${hostPreamble()}
        proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${sh(inner)}
    """.trimIndent()

    override fun hostPreamble(): String = """
        set -e
        ROOT=${sh(sharedRoot())}
        if [ ! -d "${'$'}ROOT" ]; then
          echo 'SIFTALPHA_ERROR=SHARED_STORAGE_UNAVAILABLE'
          exit 70
        fi
        if ! command -v proot-distro >/dev/null 2>&1; then
          echo 'SIFTALPHA_ERROR=PROOT_DISTRO_MISSING'
          exit 71
        fi
        runtime_dir="${'$'}HOME/.siftalpha/runtime"
        mkdir -p "${'$'}runtime_dir"
        ${TermuxProotDnsBridge.setupShell()}
    """.trimIndent()

    override fun hostProcessHelpers(): String = """
        siftalpha_pid_alive() {
          [ -n "${'$'}1" ] && kill -0 "${'$'}1" 2>/dev/null
        }

        siftalpha_group_alive() {
          [ -n "${'$'}1" ] && kill -0 -- "-${'$'}1" 2>/dev/null
        }

        siftalpha_descendants() {
          parent="${'$'}1"
          children_file="/proc/${'$'}parent/task/${'$'}parent/children"
          [ -r "${'$'}children_file" ] || return 0
          for child in ${'$'}(cat "${'$'}children_file" 2>/dev/null || true); do
            siftalpha_descendants "${'$'}child"
            printf '%s\n' "${'$'}child"
          done
        }

        siftalpha_leaf_descendants() {
          parent="${'$'}1"
          children_file="/proc/${'$'}parent/task/${'$'}parent/children"
          [ -r "${'$'}children_file" ] || return 0
          children="${'$'}(cat "${'$'}children_file" 2>/dev/null || true)"
          for child in ${'$'}children; do
            grand_file="/proc/${'$'}child/task/${'$'}child/children"
            grandchildren=''
            if [ -r "${'$'}grand_file" ]; then
              grandchildren="${'$'}(cat "${'$'}grand_file" 2>/dev/null || true)"
            fi
            if [ -n "${'$'}grandchildren" ]; then
              siftalpha_leaf_descendants "${'$'}child"
            else
              printf '%s\n' "${'$'}child"
            fi
          done
        }

        siftalpha_tree_alive() {
          root_pid="${'$'}1"
          pgid="${'$'}2"
          descendants="${'$'}3"
          if siftalpha_pid_alive "${'$'}root_pid"; then
            return 0
          fi
          if [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; then
            return 0
          fi
          for child in ${'$'}descendants; do
            if siftalpha_pid_alive "${'$'}child"; then
              return 0
            fi
          done
          return 1
        }

        siftalpha_stop_tree() {
          root_pid="${'$'}1"
          pgid="${'$'}2"
          descendants="${'$'}(siftalpha_descendants "${'$'}root_pid" | tr '\n' ' ')"
          leaves="${'$'}(siftalpha_leaf_descendants "${'$'}root_pid" | tr '\n' ' ')"

          # Phase 1: ask only the deepest workload processes to terminate cleanly.
          # This lets the guest runner flush logs/state before the PRoot host is torn down.
          if [ -n "${'$'}leaves" ]; then
            for leaf in ${'$'}leaves; do
              kill -TERM "${'$'}leaf" 2>/dev/null || true
            done

            grace=0
            while [ "${'$'}grace" -lt 4 ]; do
              if ! siftalpha_tree_alive "${'$'}root_pid" "${'$'}pgid" "${'$'}descendants"; then
                return 0
              fi
              sleep 1
              grace=${'$'}((grace + 1))
            done
          fi

          # Phase 2: graceful shutdown did not complete; terminate the entire runtime tree.
          if [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; then
            kill -TERM -- "-${'$'}pgid" 2>/dev/null || true
          fi
          for child in ${'$'}descendants; do
            kill -TERM "${'$'}child" 2>/dev/null || true
          done
          if siftalpha_pid_alive "${'$'}root_pid"; then
            kill -TERM "${'$'}root_pid" 2>/dev/null || true
          fi

          sleep 1
          if ! siftalpha_tree_alive "${'$'}root_pid" "${'$'}pgid" "${'$'}descendants"; then
            return 0
          fi

          # Phase 3: last-resort hard kill, followed by verification.
          if [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; then
            kill -KILL -- "-${'$'}pgid" 2>/dev/null || true
          fi
          for child in ${'$'}descendants; do
            if siftalpha_pid_alive "${'$'}child"; then
              kill -KILL "${'$'}child" 2>/dev/null || true
            fi
          done
          if siftalpha_pid_alive "${'$'}root_pid"; then
            kill -KILL "${'$'}root_pid" 2>/dev/null || true
          fi

          sleep 1
          ! siftalpha_tree_alive "${'$'}root_pid" "${'$'}pgid" "${'$'}descendants"
        }
    """.trimIndent()
}
