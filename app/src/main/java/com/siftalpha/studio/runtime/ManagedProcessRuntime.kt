package com.siftalpha.studio.runtime

/**
 * Runtime-neutral managed process lifecycle for executable adapters.
 *
 * The host owns Termux/PRoot process mechanics. Adapters provide environment preflight and workload
 * commands. This prevents every new language Runtime from inventing its own PID/PGID, STOP, log,
 * secret-injection and recovery behavior.
 */
object ManagedProcessRuntime {

    data class GuestPaths(
        val runLog: String,
        val state: String,
        val runner: String,
        val secrets: String,
    )

    fun defaultGuestPaths(runtimeId: String): GuestPaths = GuestPaths(
        runLog = "/root/siftalpha/logs/run-$runtimeId.log",
        state = "/root/siftalpha/state-$runtimeId.txt",
        runner = "/root/siftalpha/run-$runtimeId.sh",
        secrets = "/root/siftalpha/secrets-$runtimeId.env",
    )

    fun start(
        host: RuntimeCommandHost,
        project: RuntimeProjectSpec,
        runtimeId: String,
        workdir: String,
        preflightShell: String,
        environmentShell: String,
        workloadShell: String,
        label: String = "${project.name} · 运行",
        description: String = "${project.name} · 运行",
    ): RuntimeCommand {
        val paths = defaultGuestPaths(runtimeId)
        val runnerContent = runnerContent(
            host = host,
            workdir = workdir,
            paths = paths,
            environmentShell = environmentShell,
            workloadShell = workloadShell,
        )
        val setupInner = """
            set -e
            log=${host.sh(paths.runLog)}
            state=${host.sh(paths.state)}
            runner=${host.sh(paths.runner)}
            secrets=${host.sh(paths.secrets)}
            incoming="/root/.siftalpha-host/${runtimeId}.secrets.in"
            mkdir -p /root/siftalpha/logs
            $preflightShell
            if [ -s "${'$'}incoming" ]; then
              umask 077
              cp -- "${'$'}incoming" "${'$'}secrets"
              chmod 600 "${'$'}secrets"
            else
              rm -f -- "${'$'}secrets"
            fi
            cat >"${'$'}runner" <<'SIFTALPHA_RUNNER'
$runnerContent
SIFTALPHA_RUNNER
            chmod 700 "${'$'}runner"
            : >"${'$'}log"
            printf 'STATE=STARTING\n' >"${'$'}state"
        """.trimIndent()

        val inspectInner = guestInspectShell(host, paths)
        val clearSecretInner = "rm -f -- ${host.sh(paths.secrets)}"

        return RuntimeCommand(
            shellScript = """
                ${host.hostPreamble()}
                ${host.hostProcessHelpers()}
                pid_file="${'$'}runtime_dir/${runtimeId}.pid"
                pgid_file="${'$'}runtime_dir/${runtimeId}.pgid"
                launch_log="${'$'}runtime_dir/${runtimeId}.launch.log"
                secret_input_file="${'$'}runtime_dir/${runtimeId}.secrets.in"

                if [ -f "${'$'}pid_file" ]; then
                  old_pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
                  old_pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
                  if [ -n "${'$'}old_pid" ] && kill -0 "${'$'}old_pid" 2>/dev/null; then
                    echo 'SIFTALPHA_STATUS=RUNNING'
                    printf 'SIFTALPHA_HOST_PID=%s\n' "${'$'}old_pid"
                    [ -n "${'$'}old_pgid" ] && printf 'SIFTALPHA_HOST_PGID=%s\n' "${'$'}old_pgid"
                    ${RuntimeWebPortDiscovery.shellSnippet()}
                    proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${host.sh(inspectInner)} || true
                    exit 0
                  fi
                  if [ -n "${'$'}old_pgid" ] && kill -0 -- "-${'$'}old_pgid" 2>/dev/null; then
                    echo 'SIFTALPHA_STATUS=RUNNING'
                    printf 'SIFTALPHA_HOST_PGID=%s\n' "${'$'}old_pgid"
                    ${RuntimeWebPortDiscovery.shellSnippet()}
                    proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${host.sh(inspectInner)} || true
                    exit 0
                  fi
                  rm -f "${'$'}pid_file" "${'$'}pgid_file"
                fi

                umask 077
                : >"${'$'}secret_input_file"
                cat >"${'$'}secret_input_file" || true
                chmod 600 "${'$'}secret_input_file"
                trap 'rm -f -- "${'$'}secret_input_file"' EXIT

                proot-distro login \
                  --bind "${'$'}ROOT:/root/projects" \
                  --bind "${'$'}runtime_dir:/root/.siftalpha-host" \
                  ubuntu -- bash -lc ${host.sh(setupInner)}

                rm -f -- "${'$'}secret_input_file"
                trap - EXIT

                : >"${'$'}launch_log"
                rm -f "${'$'}pgid_file"
                if command -v setsid >/dev/null 2>&1; then
                  nohup setsid proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash ${host.sh(paths.runner)} >"${'$'}launch_log" 2>&1 < /dev/null &
                  pid=${'$'}!
                  printf '%s\n' "${'$'}pid" >"${'$'}pgid_file"
                  echo 'SIFTALPHA_RUNTIME_SESSION=SETSID'
                else
                  nohup proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash ${host.sh(paths.runner)} >"${'$'}launch_log" 2>&1 < /dev/null &
                  pid=${'$'}!
                  echo 'SIFTALPHA_RUNTIME_SESSION=PID_TREE'
                fi
                printf '%s\n' "${'$'}pid" >"${'$'}pid_file"
                printf 'SIFTALPHA_HOST_PID=%s\n' "${'$'}pid"
                if [ -s "${'$'}pgid_file" ]; then
                  printf 'SIFTALPHA_HOST_PGID=%s\n' "${'$'}(cat "${'$'}pgid_file")"
                fi
                echo 'SIFTALPHA_RUNTIME_BACKEND=TERMUX_PROOT_PID'

                sleep 1
                pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
                if kill -0 "${'$'}pid" 2>/dev/null || { [ -n "${'$'}pgid" ] && kill -0 -- "-${'$'}pgid" 2>/dev/null; }; then
                  echo 'SIFTALPHA_STATUS=RUNNING'
                  ${RuntimeWebPortDiscovery.shellSnippet()}
                  proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${host.sh(inspectInner)} || true
                  exit 0
                fi

                rm -f "${'$'}pid_file" "${'$'}pgid_file"
                proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${host.sh(clearSecretInner)} >/dev/null 2>&1 || true
                echo 'SIFTALPHA_STATUS=COMPLETED_OR_EXITED'
                result="${'$'}(proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${host.sh(inspectInner)} 2>&1 || true)"
                printf '%s\n' "${'$'}result"
                if [ -s "${'$'}launch_log" ]; then
                  echo '--- launcher ---'
                  tail -n 80 "${'$'}launch_log" 2>/dev/null || true
                fi
                child_code="${'$'}(printf '%s\n' "${'$'}result" | awk -F= '/^EXIT_CODE=/{code=${'$'}2} END{print code}')"
                if [ -n "${'$'}child_code" ] && [ "${'$'}child_code" != "0" ]; then
                  exit "${'$'}child_code"
                fi
            """.trimIndent(),
            label = label,
            description = description,
            secretNamespace = project.folderName,
        )
    }

    fun stop(
        host: RuntimeCommandHost,
        project: RuntimeProjectSpec,
        runtimeId: String,
    ): RuntimeCommand {
        val paths = defaultGuestPaths(runtimeId)
        val markStopped = """
            mkdir -p /root/siftalpha
            printf 'STATE=STOPPED_BY_USER\nEXIT_CODE=143\n' >${host.sh(paths.state)}
            rm -f -- ${host.sh(paths.secrets)}
        """.trimIndent()
        return RuntimeCommand(
            shellScript = """
                ${host.hostPreamble()}
                ${host.hostProcessHelpers()}
                pid_file="${'$'}runtime_dir/${runtimeId}.pid"
                pgid_file="${'$'}runtime_dir/${runtimeId}.pgid"
                launch_log="${'$'}runtime_dir/${runtimeId}.launch.log"
                pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
                pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"

                if [ -n "${'$'}pid" ] || [ -n "${'$'}pgid" ]; then
                  if ! siftalpha_stop_tree "${'$'}pid" "${'$'}pgid"; then
                    echo 'SIFTALPHA_ERROR=STOP_INCOMPLETE'
                    echo 'SIFTALPHA_STATUS=STOP_FAILED'
                    [ -n "${'$'}pid" ] && printf 'SIFTALPHA_HOST_PID=%s\n' "${'$'}pid"
                    [ -n "${'$'}pgid" ] && printf 'SIFTALPHA_HOST_PGID=%s\n' "${'$'}pgid"
                    exit 78
                  fi
                fi

                rm -f "${'$'}pid_file" "${'$'}pgid_file" "${'$'}runtime_dir/${runtimeId}.secrets.in"
                proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${host.sh(markStopped)} >/dev/null 2>&1 || true
                echo 'SIFTALPHA_STATUS=STOPPED_BY_USER'
                echo 'SIFTALPHA_RUNTIME_STATE=STOPPED_BY_USER'
                if [ -s "${'$'}launch_log" ]; then
                  printf 'SIFTALPHA_LAUNCH_LOG_BYTES=%s\n' "${'$'}(wc -c <"${'$'}launch_log" 2>/dev/null || echo 0)"
                fi
            """.trimIndent(),
            label = "${project.name} · 停止",
            description = "${project.name} · 停止",
        )
    }

    fun status(
        host: RuntimeCommandHost,
        project: RuntimeProjectSpec,
        runtimeId: String,
        guestStatusShell: String,
    ): RuntimeCommand {
        val paths = defaultGuestPaths(runtimeId)
        return RuntimeCommand(
            shellScript = """
                ${host.hostPreamble()}
                ${host.hostProcessHelpers()}
                pid_file="${'$'}runtime_dir/${runtimeId}.pid"
                pgid_file="${'$'}runtime_dir/${runtimeId}.pgid"
                launch_log="${'$'}runtime_dir/${runtimeId}.launch.log"

                result="${'$'}(proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${host.sh(guestStatusShell)} 2>&1 || true)"
                printf '%s\n' "${'$'}result"

                pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
                pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
                if siftalpha_pid_alive "${'$'}pid" || { [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; }; then
                  echo 'SIFTALPHA_STATUS=RUNNING'
                  [ -n "${'$'}pid" ] && printf 'SIFTALPHA_HOST_PID=%s\n' "${'$'}pid"
                  [ -n "${'$'}pgid" ] && printf 'SIFTALPHA_HOST_PGID=%s\n' "${'$'}pgid"
                  echo 'SIFTALPHA_RUNTIME_BACKEND=TERMUX_PROOT_PID'
                  echo 'SIFTALPHA_RUNTIME_STATE=RUNNING'
                  exit 0
                fi
                rm -f "${'$'}pid_file" "${'$'}pgid_file"

                if printf '%s\n' "${'$'}result" | grep -q '^STATE=STOPPED_BY_USER${'$'}'; then
                  echo 'SIFTALPHA_STATUS=STOPPED_BY_USER'
                  echo 'SIFTALPHA_RUNTIME_STATE=STOPPED_BY_USER'
                elif printf '%s\n' "${'$'}result" | grep -q '^STATE=EXITED${'$'}'; then
                  exit_code="${'$'}(printf '%s\n' "${'$'}result" | awk -F= '/^EXIT_CODE=/{print ${'$'}2; exit}')"
                  echo 'SIFTALPHA_STATUS=EXITED'
                  if [ "${'$'}{exit_code:-1}" = '0' ]; then
                    echo 'SIFTALPHA_RUNTIME_STATE=EXITED_SUCCESS'
                  else
                    echo 'SIFTALPHA_RUNTIME_STATE=EXITED_ERROR'
                  fi
                elif printf '%s\n' "${'$'}result" | grep -q '^STATE=STARTING${'$'}'; then
                  echo 'SIFTALPHA_ERROR=RUNTIME_LAUNCH_FAILED'
                  echo 'SIFTALPHA_STATUS=EXITED_ERROR'
                  echo 'SIFTALPHA_RUNTIME_STATE=EXITED_ERROR'
                  if [ -s "${'$'}launch_log" ]; then
                    echo '--- launcher ---'
                    tail -n 80 "${'$'}launch_log" 2>/dev/null || true
                  fi
                  exit 75
                else
                  echo 'SIFTALPHA_STATUS=STOPPED'
                fi
            """.trimIndent(),
            label = "${project.name} · 状态",
            description = "${project.name} · 状态",
        )
    }

    fun logs(
        host: RuntimeCommandHost,
        project: RuntimeProjectSpec,
        runtimeId: String,
    ): RuntimeCommand {
        val paths = defaultGuestPaths(runtimeId)
        val logsInner = """
            log=${host.sh(paths.runLog)}
            state=${host.sh(paths.state)}
            echo '=== SiftAlpha Project Log ==='
            if [ -f "${'$'}state" ]; then
              echo '--- state ---'
              cat "${'$'}state"
            fi
            if [ -f "${'$'}log" ]; then
              printf 'LOG_BYTES=%s\n' "${'$'}(wc -c <"${'$'}log" 2>/dev/null || echo 0)"
            else
              echo 'LOG_BYTES=0'
            fi
            ${RuntimeWebLogDiscoveryShell.shellSnippet()}
            if [ -s "${'$'}log" ]; then
              tail -n 160 "${'$'}log"
            else
              echo 'SIFTALPHA_LOG=EMPTY'
            fi
        """.trimIndent()
        return RuntimeCommand(
            shellScript = """
                ${host.hostPreamble()}
                ${host.hostProcessHelpers()}
                pid_file="${'$'}runtime_dir/${runtimeId}.pid"
                pgid_file="${'$'}runtime_dir/${runtimeId}.pgid"
                launch_log="${'$'}runtime_dir/${runtimeId}.launch.log"
                pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
                pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
                if siftalpha_pid_alive "${'$'}pid" || { [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; }; then
                  echo 'SIFTALPHA_STATUS=RUNNING'
                  [ -n "${'$'}pid" ] && printf 'SIFTALPHA_HOST_PID=%s\n' "${'$'}pid"
                  [ -n "${'$'}pgid" ] && printf 'SIFTALPHA_HOST_PGID=%s\n' "${'$'}pgid"
                else
                  rm -f "${'$'}pid_file" "${'$'}pgid_file"
                fi
                ${RuntimeWebPortDiscovery.shellSnippet()}
                proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${host.sh(logsInner)} || true
                if [ -s "${'$'}launch_log" ]; then
                  echo '--- launcher ---'
                  tail -n 40 "${'$'}launch_log" 2>/dev/null || true
                fi
            """.trimIndent(),
            label = "${project.name} · 日志",
            description = "${project.name} · 日志",
        )
    }

    fun clean(
        host: RuntimeCommandHost,
        project: RuntimeProjectSpec,
        runtimeId: String,
        guestCleanShell: String,
    ): RuntimeCommand = RuntimeCommand(
        shellScript = """
            ${host.hostPreamble()}
            ${host.hostProcessHelpers()}
            pid_file="${'$'}runtime_dir/${runtimeId}.pid"
            pgid_file="${'$'}runtime_dir/${runtimeId}.pgid"
            launch_log="${'$'}runtime_dir/${runtimeId}.launch.log"
            pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
            pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"

            if [ -n "${'$'}pid" ] || [ -n "${'$'}pgid" ]; then
              if ! siftalpha_stop_tree "${'$'}pid" "${'$'}pgid"; then
                echo 'SIFTALPHA_ERROR=STOP_INCOMPLETE'
                echo 'SIFTALPHA_STATUS=CLEAN_BLOCKED_RUNNING_PROCESS'
                exit 78
              fi
            fi

            rm -f "${'$'}pid_file" "${'$'}pgid_file" "${'$'}launch_log" "${'$'}runtime_dir/${runtimeId}.secrets.in"
            proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${host.sh(guestCleanShell)}
            echo 'SIFTALPHA_ENV=CLEANED'
            echo 'SIFTALPHA_STATUS=STOPPED'
        """.trimIndent(),
        label = "${project.name} · 清理环境",
        description = "${project.name} · 清理 Runtime 环境，不删除项目源码",
    )

    private fun guestInspectShell(host: RuntimeCommandHost, paths: GuestPaths): String = """
        state=${host.sh(paths.state)}
        log=${host.sh(paths.runLog)}
        if [ -f "${'$'}state" ]; then
          cat "${'$'}state"
        fi
        if [ -f "${'$'}log" ]; then
          printf 'SIFTALPHA_LOG_BYTES=%s\n' "${'$'}(wc -c <"${'$'}log" 2>/dev/null || echo 0)"
          ${RuntimeWebLogDiscoveryShell.shellSnippet()}
          tail -n 80 "${'$'}log" 2>/dev/null || true
        else
          echo 'SIFTALPHA_WEB_LOG_DISCOVERY=NO_LOG'
        fi
    """.trimIndent()

    private fun runnerContent(
        host: RuntimeCommandHost,
        workdir: String,
        paths: GuestPaths,
        environmentShell: String,
        workloadShell: String,
    ): String = """
        #!/usr/bin/env bash
        set +e
        log=${host.sh(paths.runLog)}
        state=${host.sh(paths.state)}
        secret_file=${host.sh(paths.secrets)}
        workdir=${host.sh(workdir)}
        mkdir -p /root/siftalpha/logs
        exec >>"${'$'}log" 2>&1
        echo '=== SiftAlpha Studio Runtime ==='
        printf 'STARTED_AT=%s\n' "${'$'}(date '+%Y-%m-%d %H:%M:%S')"
        printf 'STATE=RUNNING\n' >"${'$'}state"

        ${secretLoaderShell()}

        if ! cd "${'$'}workdir"; then
          code=90
          echo 'SIFTALPHA_ERROR=RUNTIME_WORKDIR_MISSING'
          printf 'SIFTALPHA_PROCESS_EXIT=%s\n' "${'$'}code"
          printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
          exit "${'$'}code"
        fi

        $environmentShell
        $workloadShell
        code=${'$'}?
        printf 'SIFTALPHA_PROCESS_EXIT=%s\n' "${'$'}code"
        printf 'EXITED_AT=%s\n' "${'$'}(date '+%Y-%m-%d %H:%M:%S')"
        printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
        rm -f -- "${'$'}secret_file"
        exit "${'$'}code"
    """.trimIndent()

    /** Same protected environment payload contract already accepted by the Python executable Runtime. */
    private fun secretLoaderShell(): String = """
        secret_status='NONE'
        if [ -s "${'$'}secret_file" ]; then
          if . "${'$'}secret_file"; then
            rm -f -- "${'$'}secret_file"
            case "${'$'}{SIFTALPHA_SECRETS_FORMAT:-}" in
              1)
                if [ -z "${'$'}{SIFTALPHA_BINANCE_API_KEY_B64:-}" ] || \
                   [ -z "${'$'}{SIFTALPHA_BINANCE_API_SECRET_B64:-}" ]; then
                  code=79
                  echo 'SIFTALPHA_ERROR=SECRET_PAYLOAD_INVALID'
                  printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
                  exit "${'$'}code"
                fi
                api_key_decoded="${'$'}(printf '%s' "${'$'}SIFTALPHA_BINANCE_API_KEY_B64" | base64 -d 2>/dev/null)"
                api_key_code=${'$'}?
                api_secret_decoded="${'$'}(printf '%s' "${'$'}SIFTALPHA_BINANCE_API_SECRET_B64" | base64 -d 2>/dev/null)"
                api_secret_code=${'$'}?
                unset SIFTALPHA_SECRETS_FORMAT SIFTALPHA_BINANCE_API_KEY_B64 SIFTALPHA_BINANCE_API_SECRET_B64
                if [ "${'$'}api_key_code" -ne 0 ] || [ "${'$'}api_secret_code" -ne 0 ]; then
                  unset api_key_decoded api_secret_decoded
                  code=79
                  echo 'SIFTALPHA_ERROR=SECRET_DECODE_FAILED'
                  printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
                  exit "${'$'}code"
                fi
                export SIFTALPHA_BINANCE_API_KEY="${'$'}api_key_decoded"
                export SIFTALPHA_BINANCE_API_SECRET="${'$'}api_secret_decoded"
                unset api_key_decoded api_secret_decoded
                secret_status='INJECTED:2'
                ;;
              2)
                env_count="${'$'}{SIFTALPHA_ENV_COUNT:-}"
                if ! printf '%s' "${'$'}env_count" | grep -Eq '^[0-9]+${'$'}' || \
                   [ "${'$'}env_count" -lt 1 ] || [ "${'$'}env_count" -gt 64 ]; then
                  code=79
                  echo 'SIFTALPHA_ERROR=SECRET_PAYLOAD_INVALID'
                  printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
                  exit "${'$'}code"
                fi
                env_index=0
                while [ "${'$'}env_index" -lt "${'$'}env_count" ]; do
                  eval "env_name_b64=\${'$'}{SIFTALPHA_ENV_${'$'}{env_index}_NAME_B64:-}"
                  eval "env_value_b64=\${'$'}{SIFTALPHA_ENV_${'$'}{env_index}_VALUE_B64:-}"
                  if [ -z "${'$'}env_name_b64" ] || [ -z "${'$'}env_value_b64" ]; then
                    code=79
                    echo 'SIFTALPHA_ERROR=SECRET_PAYLOAD_INVALID'
                    printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
                    exit "${'$'}code"
                  fi
                  env_name="${'$'}(printf '%s' "${'$'}env_name_b64" | base64 -d 2>/dev/null)"
                  env_name_code=${'$'}?
                  env_value="${'$'}(printf '%s' "${'$'}env_value_b64" | base64 -d 2>/dev/null)"
                  env_value_code=${'$'}?
                  if [ "${'$'}env_name_code" -ne 0 ] || [ "${'$'}env_value_code" -ne 0 ]; then
                    unset env_name env_value env_name_b64 env_value_b64
                    code=79
                    echo 'SIFTALPHA_ERROR=SECRET_DECODE_FAILED'
                    printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
                    exit "${'$'}code"
                  fi
                  case "${'$'}env_name" in
                    ''|[0-9]*|*[!A-Za-z0-9_]*)
                      unset env_name env_value env_name_b64 env_value_b64
                      code=79
                      echo 'SIFTALPHA_ERROR=SECRET_PAYLOAD_INVALID'
                      printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
                      exit "${'$'}code"
                      ;;
                  esac
                  export "${'$'}env_name=${'$'}env_value"
                  unset "SIFTALPHA_ENV_${'$'}{env_index}_NAME_B64" "SIFTALPHA_ENV_${'$'}{env_index}_VALUE_B64"
                  unset env_name env_value env_name_b64 env_value_b64 env_name_code env_value_code
                  env_index=${'$'}((env_index + 1))
                done
                unset SIFTALPHA_SECRETS_FORMAT SIFTALPHA_ENV_COUNT env_index
                secret_status="INJECTED:${'$'}env_count"
                unset env_count
                ;;
              *)
                code=79
                echo 'SIFTALPHA_ERROR=SECRET_PAYLOAD_INVALID'
                printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
                exit "${'$'}code"
                ;;
            esac
          else
            rm -f -- "${'$'}secret_file"
            code=79
            echo 'SIFTALPHA_ERROR=SECRET_PAYLOAD_INVALID'
            printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
            exit "${'$'}code"
          fi
        else
          rm -f -- "${'$'}secret_file"
        fi
        printf 'SIFTALPHA_SECRETS=%s\n' "${'$'}secret_status"
    """.trimIndent()
}
