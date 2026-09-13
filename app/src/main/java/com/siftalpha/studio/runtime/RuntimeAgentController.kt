package com.siftalpha.studio.runtime

import java.util.Base64

/**
 * Builds lifecycle commands for the Ubuntu Runtime Agent.
 *
 * The Agent uses the same host-side persistent PRoot process model already proven by project
 * Runtime: nohup + setsid + tracked host PID/PGID. Authentication material is delivered only
 * through RUN_COMMAND stdin and written with mode 0600 inside Ubuntu.
 */
class RuntimeAgentController {

    fun start(tokenProvider: () -> String?): RuntimeCommand {
        val encodedAgent = Base64.getEncoder().encodeToString(
            RuntimeAgentProtocol.pythonSource().toByteArray(Charsets.UTF_8),
        )
        val dir = RuntimeAgentProtocol.AGENT_DIR
        val script = RuntimeAgentProtocol.SCRIPT_PATH
        val token = RuntimeAgentProtocol.TOKEN_PATH
        val agentPid = RuntimeAgentProtocol.PID_PATH
        val agentLog = RuntimeAgentProtocol.LOG_PATH
        val launchInner = "exec python3 $script >> $agentLog 2>&1"

        return RuntimeCommand(
            shellScript = """
                set -euo pipefail
                if ! command -v proot-distro >/dev/null 2>&1; then
                  echo 'SIFTALPHA_AGENT_ERROR=PROOT_DISTRO_MISSING'
                  exit 20
                fi

                agent_token="${'$'}(cat)"
                if [ "${'$'}{#agent_token}" -ne 64 ]; then
                  echo 'SIFTALPHA_AGENT_ERROR=TOKEN_INVALID'
                  exit 41
                fi
                case "${'$'}agent_token" in
                  *[!0-9a-f]*)
                    echo 'SIFTALPHA_AGENT_ERROR=TOKEN_INVALID'
                    exit 41
                    ;;
                esac

                if ! proot-distro login ubuntu -- bash -lc 'command -v python3 >/dev/null 2>&1'; then
                  echo 'SIFTALPHA_AGENT_ERROR=UBUNTU_RUNTIME_MISSING'
                  exit 42
                fi

                proot-distro login ubuntu -- python3 -c "import base64,pathlib,sys; p=pathlib.Path('$dir'); p.mkdir(parents=True, exist_ok=True); (p/'agent.py').write_bytes(base64.b64decode(sys.argv[1]))" '$encodedAgent'
                proot-distro login ubuntu -- bash -lc ${sh("chmod 700 $script; : > $agentLog; rm -f $agentPid")}
                printf '%s' "${'$'}agent_token" | proot-distro login ubuntu -- bash -lc ${sh("umask 077; mkdir -p $dir; cat > $token; chmod 600 $token")}
                unset agent_token

                runtime_dir="${'$'}HOME/.siftalpha/agent"
                mkdir -p "${'$'}runtime_dir"
                pid_file="${'$'}runtime_dir/host.pid"
                pgid_file="${'$'}runtime_dir/host.pgid"
                launch_log="${'$'}runtime_dir/launch.log"

                ${hostProcessHelpers()}

                old_pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
                old_pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
                if [ -n "${'$'}old_pid" ] || [ -n "${'$'}old_pgid" ]; then
                  siftalpha_stop_tree "${'$'}old_pid" "${'$'}old_pgid" || true
                fi
                rm -f "${'$'}pid_file" "${'$'}pgid_file"
                : >"${'$'}launch_log"

                if command -v setsid >/dev/null 2>&1; then
                  nohup setsid proot-distro login ubuntu -- bash -lc ${sh(launchInner)} >"${'$'}launch_log" 2>&1 < /dev/null &
                  host_pid=${'$'}!
                  printf '%s\n' "${'$'}host_pid" >"${'$'}pgid_file"
                  echo 'SIFTALPHA_AGENT_SESSION=SETSID'
                else
                  nohup proot-distro login ubuntu -- bash -lc ${sh(launchInner)} >"${'$'}launch_log" 2>&1 < /dev/null &
                  host_pid=${'$'}!
                  echo 'SIFTALPHA_AGENT_SESSION=PID_TREE'
                fi
                printf '%s\n' "${'$'}host_pid" >"${'$'}pid_file"
                printf 'SIFTALPHA_AGENT_HOST_PID=%s\n' "${'$'}host_pid"
                if [ -s "${'$'}pgid_file" ]; then
                  printf 'SIFTALPHA_AGENT_HOST_PGID=%s\n' "${'$'}(cat "${'$'}pgid_file")"
                fi

                ready=0
                for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
                  if proot-distro login ubuntu -- python3 -c 'import socket,sys; s=socket.create_connection((sys.argv[1], int(sys.argv[2])), 0.25); s.close()' ${RuntimeAgentProtocol.HOST} ${RuntimeAgentProtocol.PORT} >/dev/null 2>&1; then
                    ready=1
                    break
                  fi
                  pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
                  if ! siftalpha_pid_alive "${'$'}host_pid" && { [ -z "${'$'}pgid" ] || ! siftalpha_group_alive "${'$'}pgid"; }; then
                    break
                  fi
                  sleep 0.25
                done

                if [ "${'$'}ready" = 1 ]; then
                  echo 'SIFTALPHA_AGENT=RUNNING'
                  echo 'SIFTALPHA_AGENT_READY=1'
                  echo 'SIFTALPHA_AGENT_BACKEND=TERMUX_PROOT_PID'
                  echo 'SIFTALPHA_AGENT_HOST=${RuntimeAgentProtocol.HOST}'
                  echo 'SIFTALPHA_AGENT_PORT=${RuntimeAgentProtocol.PORT}'
                  echo 'SIFTALPHA_AGENT_PROTOCOL=${RuntimeAgentProtocol.PROTOCOL_VERSION}'
                  proot-distro login ubuntu -- bash -lc ${sh("if [ -r $agentPid ]; then printf 'SIFTALPHA_AGENT_PID='; cat $agentPid; printf '\\n'; fi")} || true
                  exit 0
                fi

                echo 'SIFTALPHA_AGENT=FAILED'
                echo 'SIFTALPHA_AGENT_ERROR=NOT_LISTENING'
                pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
                if siftalpha_pid_alive "${'$'}host_pid" || { [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; }; then
                  echo 'SIFTALPHA_AGENT_HOST_PROCESS=RUNNING_BUT_NOT_READY'
                else
                  echo 'SIFTALPHA_AGENT_HOST_PROCESS=EXITED'
                fi
                echo '--- agent.log ---'
                proot-distro login ubuntu -- bash -lc ${sh("tail -n 80 $agentLog 2>/dev/null || true")} || true
                if [ -s "${'$'}launch_log" ]; then
                  echo '--- launcher ---'
                  tail -n 80 "${'$'}launch_log" 2>/dev/null || true
                fi
                siftalpha_stop_tree "${'$'}host_pid" "${'$'}pgid" || true
                rm -f "${'$'}pid_file" "${'$'}pgid_file"
                exit 43
            """.trimIndent(),
            label = "SiftAlpha Runtime Agent 启动",
            description = "安装并启动仅监听本机回环地址的 SiftAlpha Runtime Agent。",
            stdinPayloadProvider = tokenProvider,
        )
    }

    fun status(): RuntimeCommand = RuntimeCommand(
        shellScript = """
            set -eu
            runtime_dir="${'$'}HOME/.siftalpha/agent"
            pid_file="${'$'}runtime_dir/host.pid"
            pgid_file="${'$'}runtime_dir/host.pgid"
            launch_log="${'$'}runtime_dir/launch.log"
            ${hostProcessHelpers()}
            pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
            pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
            if siftalpha_pid_alive "${'$'}pid" || { [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; }; then
              echo 'SIFTALPHA_AGENT=RUNNING'
              echo 'SIFTALPHA_AGENT_BACKEND=TERMUX_PROOT_PID'
              [ -n "${'$'}pid" ] && printf 'SIFTALPHA_AGENT_HOST_PID=%s\n' "${'$'}pid"
              [ -n "${'$'}pgid" ] && printf 'SIFTALPHA_AGENT_HOST_PGID=%s\n' "${'$'}pgid"
              echo 'SIFTALPHA_AGENT_HOST=${RuntimeAgentProtocol.HOST}'
              echo 'SIFTALPHA_AGENT_PORT=${RuntimeAgentProtocol.PORT}'
              echo 'SIFTALPHA_AGENT_PROTOCOL=${RuntimeAgentProtocol.PROTOCOL_VERSION}'
              exit 0
            fi
            rm -f "${'$'}pid_file" "${'$'}pgid_file"
            echo 'SIFTALPHA_AGENT=STOPPED'
            if [ -s "${'$'}launch_log" ]; then
              echo '--- launcher ---'
              tail -n 40 "${'$'}launch_log" 2>/dev/null || true
            fi
        """.trimIndent(),
        label = "SiftAlpha Runtime Agent 状态",
        description = "检查 Runtime Agent 的宿主进程状态。",
    )

    fun stop(): RuntimeCommand = RuntimeCommand(
        shellScript = """
            set -eu
            runtime_dir="${'$'}HOME/.siftalpha/agent"
            pid_file="${'$'}runtime_dir/host.pid"
            pgid_file="${'$'}runtime_dir/host.pgid"
            ${hostProcessHelpers()}
            pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
            pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
            if [ -n "${'$'}pid" ] || [ -n "${'$'}pgid" ]; then
              if ! siftalpha_stop_tree "${'$'}pid" "${'$'}pgid"; then
                echo 'SIFTALPHA_AGENT=STOP_FAILED'
                echo 'SIFTALPHA_AGENT_ERROR=STOP_INCOMPLETE'
                exit 78
              fi
            fi
            rm -f "${'$'}pid_file" "${'$'}pgid_file"
            if command -v proot-distro >/dev/null 2>&1; then
              proot-distro login ubuntu -- bash -lc ${sh("rm -f ${RuntimeAgentProtocol.PID_PATH}")} >/dev/null 2>&1 || true
            fi
            echo 'SIFTALPHA_AGENT=STOPPED'
        """.trimIndent(),
        label = "SiftAlpha Runtime Agent 停止",
        description = "停止 SiftAlpha Runtime Agent，不删除项目源码或项目 Runtime。",
    )

    private fun hostProcessHelpers(): String = """
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

        siftalpha_tree_alive() {
          root_pid="${'$'}1"
          pgid="${'$'}2"
          descendants="${'$'}3"
          if siftalpha_pid_alive "${'$'}root_pid"; then return 0; fi
          if [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; then return 0; fi
          for child in ${'$'}descendants; do
            if siftalpha_pid_alive "${'$'}child"; then return 0; fi
          done
          return 1
        }

        siftalpha_stop_tree() {
          root_pid="${'$'}1"
          pgid="${'$'}2"
          descendants="${'$'}(siftalpha_descendants "${'$'}root_pid" | tr '\n' ' ')"
          if [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; then
            kill -TERM -- "-${'$'}pgid" 2>/dev/null || true
          fi
          for child in ${'$'}descendants; do kill -TERM "${'$'}child" 2>/dev/null || true; done
          if siftalpha_pid_alive "${'$'}root_pid"; then kill -TERM "${'$'}root_pid" 2>/dev/null || true; fi
          sleep 1
          if ! siftalpha_tree_alive "${'$'}root_pid" "${'$'}pgid" "${'$'}descendants"; then return 0; fi
          if [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; then
            kill -KILL -- "-${'$'}pgid" 2>/dev/null || true
          fi
          for child in ${'$'}descendants; do
            if siftalpha_pid_alive "${'$'}child"; then kill -KILL "${'$'}child" 2>/dev/null || true; fi
          done
          if siftalpha_pid_alive "${'$'}root_pid"; then kill -KILL "${'$'}root_pid" 2>/dev/null || true; fi
          sleep 1
          ! siftalpha_tree_alive "${'$'}root_pid" "${'$'}pgid" "${'$'}descendants"
        }
    """.trimIndent()

    private fun sh(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
