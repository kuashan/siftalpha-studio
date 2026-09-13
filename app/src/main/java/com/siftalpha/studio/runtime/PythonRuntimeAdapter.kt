package com.siftalpha.studio.runtime

/**
 * Executable Python runtime adapter.
 *
 * Python-specific environment preparation, dependency installation and project command generation
 * live here. Android/Termux/PRoot mechanics are supplied by RuntimeCommandHost.
 */
class PythonRuntimeAdapter(
    private val host: RuntimeCommandHost,
) : ExecutableRuntimeAdapter {
    override val kind: RuntimeKind = RuntimeKind.PYTHON

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
        RuntimeEnvironmentRequirement("python3", "Python interpreter"),
        RuntimeEnvironmentRequirement("python3 -m pip", "Python package manager"),
        RuntimeEnvironmentRequirement("python3 -m venv", "Python virtual environment support"),
    )

    override fun prepare(project: RuntimeProjectSpec): RuntimeCommand = RuntimeCommand(
        shellScript = host.wrapUbuntu(buildPrepare(project)),
        label = "${project.name} · 准备环境",
        description = "${project.name} · 准备环境",
    )

    override fun start(project: RuntimeProjectSpec): RuntimeCommand = RuntimeCommand(
        shellScript = buildStart(project),
        label = "${project.name} · 运行",
        description = "${project.name} · 运行",
        secretNamespace = project.folderName,
    )

    override fun stop(project: RuntimeProjectSpec): RuntimeCommand = RuntimeCommand(
        shellScript = buildStop(project),
        label = "${project.name} · 停止",
        description = "${project.name} · 停止",
    )

    override fun status(project: RuntimeProjectSpec): RuntimeCommand = RuntimeCommand(
        shellScript = buildStatus(project),
        label = "${project.name} · 状态",
        description = "${project.name} · 状态",
    )

    override fun logs(project: RuntimeProjectSpec): RuntimeCommand = RuntimeCommand(
        shellScript = buildLogs(project),
        label = "${project.name} · 日志",
        description = "${project.name} · 日志",
    )

    override fun clean(project: RuntimeProjectSpec): RuntimeCommand = RuntimeCommand(
        shellScript = buildClean(project),
        label = "${project.name} · 清理环境",
        description = "${project.name} · 清理环境",
    )

    private fun buildPrepare(project: RuntimeProjectSpec): String {
        val id = host.runtimeId(project.folderName)
        val path = "/root/projects/${project.folderName}"
        val venv = "/root/venvs/$id"
        val log = "/root/siftalpha/logs/prepare-$id.log"
        val ready = "/root/siftalpha/env-ready-$id.txt"
        return """
            set -e
            project=${sh(path)}
            venv=${sh(venv)}
            log=${sh(log)}
            ready=${sh(ready)}
            mkdir -p /root/venvs /root/siftalpha/logs
            : >"${'$'}log"
            rm -f -- "${'$'}ready"
            if ! command -v python3 >/dev/null 2>&1; then
              echo 'SIFTALPHA_ERROR=PYTHON_MISSING'
              exit 72
            fi
            need_tools=0
            python3 -m venv --help >/dev/null 2>&1 || need_tools=1
            python3 -m pip --version >/dev/null 2>&1 || need_tools=1
            if [ "${'$'}need_tools" -ne 0 ]; then
              export DEBIAN_FRONTEND=noninteractive
              apt-get update >>"${'$'}log" 2>&1
              apt-get install -y python3-venv python3-pip >>"${'$'}log" 2>&1
            fi
            if [ ! -x "${'$'}venv/bin/python" ]; then
              python3 -m venv "${'$'}venv" >>"${'$'}log" 2>&1
            fi
            ${dependencyFingerprintShell()}
            if [ "${'$'}dependency_source" = 'requirements.txt' ]; then
              echo 'DEPENDENCY_SOURCE=requirements.txt'
              "${'$'}venv/bin/python" -m pip install -r "${'$'}project/requirements.txt" >>"${'$'}log" 2>&1
            elif [ "${'$'}dependency_source" = 'pyproject.toml' ]; then
              echo 'DEPENDENCY_SOURCE=pyproject.toml'
              "${'$'}venv/bin/python" -m pip install -e "${'$'}project" >>"${'$'}log" 2>&1
            else
              echo 'DEPENDENCY_SOURCE=none'
            fi
            "${'$'}venv/bin/python" --version 2>&1
            umask 077
            printf 'SOURCE=%s\nHASH=%s\n' "${'$'}dependency_source" "${'$'}dependency_hash" >"${'$'}ready"
            echo 'SIFTALPHA_ENV=READY'
            printf 'SIFTALPHA_ENV_DEPENDENCY_SOURCE=%s\n' "${'$'}dependency_source"
            tail -n 30 "${'$'}log" 2>/dev/null || true
        """.trimIndent()
    }

    private fun buildStart(project: RuntimeProjectSpec): String {
        val id = host.runtimeId(project.folderName)
        val path = "/root/projects/${project.folderName}"
        val venv = "/root/venvs/$id"
        val log = "/root/siftalpha/logs/run-$id.log"
        val state = "/root/siftalpha/state-$id.txt"
        val runner = "/root/siftalpha/run-$id.sh"
        val secrets = "/root/siftalpha/secrets-$id.env"
        val ready = "/root/siftalpha/env-ready-$id.txt"
        val entry = project.entry
        val run = project.run.ifBlank { "python $entry" }
        val trimmedRun = run.trim()
        val autoEntryMode = trimmedRun == "python $entry" ||
            trimmedRun == "python3 $entry" ||
            trimmedRun == "python ${sh(entry)}" ||
            trimmedRun == "python3 ${sh(entry)}"

        val runnerContent = """
            #!/usr/bin/env bash
            set +e
            log=${sh(log)}
            state=${sh(state)}
            project=${sh(path)}
            venv=${sh(venv)}
            secret_file=${sh(secrets)}
            configured_entry=${sh(entry)}
            configured_run=${sh(run)}
            auto_entry_mode=${if (autoEntryMode) "1" else "0"}
            resolved_entry=''
            mkdir -p /root/siftalpha/logs
            exec >>"${'$'}log" 2>&1
            echo '=== SiftAlpha Studio Runtime ==='
            printf 'STARTED_AT=%s\n' "${'$'}(date '+%Y-%m-%d %H:%M:%S')"
            printf 'STATE=RUNNING\n' >"${'$'}state"

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

            if ! cd "${'$'}project"; then
              code=90
              printf 'SIFTALPHA_PROCESS_EXIT=%s\n' "${'$'}code"
              printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
              exit "${'$'}code"
            fi

            if [ "${'$'}auto_entry_mode" = "1" ] && [ ! -f "${'$'}project/${'$'}configured_entry" ]; then
              candidate=''
              for wanted in main.py app.py run.py manage.py; do
                candidate="${'$'}(find "${'$'}project" -maxdepth 4 -type f -name "${'$'}wanted" \
                  ! -path '*/.git/*' ! -path '*/.venv/*' ! -path '*/venv/*' \
                  ! -path '*/__pycache__/*' 2>/dev/null | head -n 1)"
                [ -n "${'$'}candidate" ] && break
              done
              if [ -z "${'$'}candidate" ]; then
                candidate="${'$'}(find "${'$'}project" -maxdepth 4 -type f -name '*.py' \
                  ! -path '*/.git/*' ! -path '*/.venv/*' ! -path '*/venv/*' \
                  ! -path '*/__pycache__/*' 2>/dev/null | head -n 1)"
              fi
              if [ -n "${'$'}candidate" ]; then
                resolved_entry="${'$'}{candidate#"${'$'}project"/}"
                printf 'SIFTALPHA_ENTRY_AUTO=%s\n' "${'$'}resolved_entry"
              else
                code=77
                echo 'SIFTALPHA_ERROR=ENTRY_NOT_FOUND'
                printf 'SIFTALPHA_ENTRY_CONFIGURED=%s\n' "${'$'}configured_entry"
                echo '--- python candidates ---'
                find "${'$'}project" -maxdepth 4 -type f -name '*.py' \
                  ! -path '*/.git/*' ! -path '*/.venv/*' ! -path '*/venv/*' \
                  ! -path '*/__pycache__/*' 2>/dev/null | head -n 30 || true
                printf 'SIFTALPHA_PROCESS_EXIT=%s\n' "${'$'}code"
                printf 'EXITED_AT=%s\n' "${'$'}(date '+%Y-%m-%d %H:%M:%S')"
                printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
                exit "${'$'}code"
              fi
            fi

            export PYTHONUNBUFFERED=1
            export VIRTUAL_ENV="${'$'}venv"
            export PATH="${'$'}venv/bin:${'$'}PATH"
            if [ -n "${'$'}resolved_entry" ]; then
              printf 'COMMAND=python %s\n' "${'$'}resolved_entry"
              "${'$'}venv/bin/python" "${'$'}resolved_entry"
            else
              printf 'COMMAND=%s\n' "${'$'}configured_run"
              bash -c "${'$'}configured_run"
            fi
            code=${'$'}?
            printf 'SIFTALPHA_PROCESS_EXIT=%s\n' "${'$'}code"
            printf 'EXITED_AT=%s\n' "${'$'}(date '+%Y-%m-%d %H:%M:%S')"
            printf 'STATE=EXITED\nEXIT_CODE=%s\n' "${'$'}code" >"${'$'}state"
            exit "${'$'}code"
        """.trimIndent()

        val setupInner = """
            set -e
            project=${sh(path)}
            venv=${sh(venv)}
            ready=${sh(ready)}
            log=${sh(log)}
            state=${sh(state)}
            runner=${sh(runner)}
            secrets=${sh(secrets)}
            incoming="/root/.siftalpha-host/${id}.secrets.in"
            mkdir -p /root/siftalpha/logs
            ${environmentReadyCheckShell()}
            if [ "${'$'}env_ready" -ne 1 ]; then
              echo 'SIFTALPHA_ENV=NOT_READY'
              printf 'SIFTALPHA_ENV_REASON=%s\n' "${'$'}env_reason"
              echo 'SIFTALPHA_ERROR=ENV_NOT_READY'
              exit 73
            fi
            echo 'SIFTALPHA_ENV=READY'
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

        val inspectInner = """
            state=${sh(state)}
            log=${sh(log)}
            if [ -f "${'$'}state" ]; then
              cat "${'$'}state"
            fi
            if [ -f "${'$'}log" ]; then
              printf 'SIFTALPHA_LOG_BYTES=%s\n' "${'$'}(wc -c <"${'$'}log" 2>/dev/null || echo 0)"
              tail -n 80 "${'$'}log" 2>/dev/null || true
            fi
        """.trimIndent()

        val clearSecretInner = "rm -f -- ${sh(secrets)}"

        return """
            ${host.hostPreamble()}
            pid_file="${'$'}runtime_dir/${id}.pid"
            pgid_file="${'$'}runtime_dir/${id}.pgid"
            launch_log="${'$'}runtime_dir/${id}.launch.log"
            secret_input_file="${'$'}runtime_dir/${id}.secrets.in"

            if [ -f "${'$'}pid_file" ]; then
              old_pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
              old_pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
              if [ -n "${'$'}old_pid" ] && kill -0 "${'$'}old_pid" 2>/dev/null; then
                echo 'SIFTALPHA_STATUS=RUNNING'
                printf 'SIFTALPHA_HOST_PID=%s\n' "${'$'}old_pid"
                [ -n "${'$'}old_pgid" ] && printf 'SIFTALPHA_HOST_PGID=%s\n' "${'$'}old_pgid"
                exit 0
              fi
              if [ -n "${'$'}old_pgid" ] && kill -0 -- "-${'$'}old_pgid" 2>/dev/null; then
                echo 'SIFTALPHA_STATUS=RUNNING'
                printf 'SIFTALPHA_HOST_PGID=%s\n' "${'$'}old_pgid"
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
              ubuntu -- bash -lc ${sh(setupInner)}

            rm -f -- "${'$'}secret_input_file"
            trap - EXIT

            : >"${'$'}launch_log"
            rm -f "${'$'}pgid_file"
            if command -v setsid >/dev/null 2>&1; then
              nohup setsid proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash ${sh(runner)} >"${'$'}launch_log" 2>&1 < /dev/null &
              pid=${'$'}!
              printf '%s\n' "${'$'}pid" >"${'$'}pgid_file"
              echo 'SIFTALPHA_RUNTIME_SESSION=SETSID'
            else
              nohup proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash ${sh(runner)} >"${'$'}launch_log" 2>&1 < /dev/null &
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
              proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${sh(inspectInner)} || true
              exit 0
            fi

            rm -f "${'$'}pid_file" "${'$'}pgid_file"
            proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${sh(clearSecretInner)} >/dev/null 2>&1 || true
            echo 'SIFTALPHA_STATUS=COMPLETED_OR_EXITED'
            result="${'$'}(proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${sh(inspectInner)} 2>&1 || true)"
            printf '%s\n' "${'$'}result"
            if [ -s "${'$'}launch_log" ]; then
              echo '--- launcher ---'
              tail -n 80 "${'$'}launch_log" 2>/dev/null || true
            fi
            child_code="${'$'}(printf '%s\n' "${'$'}result" | awk -F= '/^EXIT_CODE=/{code=${'$'}2} END{print code}')"
            if [ -n "${'$'}child_code" ] && [ "${'$'}child_code" != "0" ]; then
              exit "${'$'}child_code"
            fi
        """.trimIndent()
    }

    private fun buildStop(project: RuntimeProjectSpec): String {
        val id = host.runtimeId(project.folderName)
        val state = "/root/siftalpha/state-$id.txt"
        val markStopped = """
            mkdir -p /root/siftalpha
            printf 'STATE=STOPPED_BY_USER\nEXIT_CODE=143\n' >${sh(state)}
        """.trimIndent()
        return """
            ${host.hostPreamble()}
            ${host.hostProcessHelpers()}
            pid_file="${'$'}runtime_dir/${id}.pid"
            pgid_file="${'$'}runtime_dir/${id}.pgid"
            launch_log="${'$'}runtime_dir/${id}.launch.log"
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

            rm -f "${'$'}pid_file" "${'$'}pgid_file" "${'$'}runtime_dir/${id}.secrets.in"
            proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${sh(markStopped)} >/dev/null 2>&1 || true
            echo 'SIFTALPHA_STATUS=STOPPED_BY_USER'
            echo 'SIFTALPHA_RUNTIME_STATE=STOPPED_BY_USER'
            if [ -s "${'$'}launch_log" ]; then
              printf 'SIFTALPHA_LAUNCH_LOG_BYTES=%s\n' "${'$'}(wc -c <"${'$'}launch_log" 2>/dev/null || echo 0)"
            fi
        """.trimIndent()
    }

    private fun buildStatus(project: RuntimeProjectSpec): String {
        val id = host.runtimeId(project.folderName)
        val path = "/root/projects/${project.folderName}"
        val venv = "/root/venvs/$id"
        val ready = "/root/siftalpha/env-ready-$id.txt"
        val state = "/root/siftalpha/state-$id.txt"
        val statusInner = """
            project=${sh(path)}
            venv=${sh(venv)}
            ready=${sh(ready)}
            state=${sh(state)}
            ${environmentReadyCheckShell()}
            if [ "${'$'}env_ready" -eq 1 ]; then
              echo 'SIFTALPHA_ENV=READY'
              printf 'SIFTALPHA_ENV_DEPENDENCY_SOURCE=%s\n' "${'$'}dependency_source"
            else
              echo 'SIFTALPHA_ENV=NOT_READY'
              printf 'SIFTALPHA_ENV_REASON=%s\n' "${'$'}env_reason"
            fi
            if [ -f "${'$'}state" ]; then
              cat "${'$'}state"
            fi
        """.trimIndent()
        return """
            ${host.hostPreamble()}
            ${host.hostProcessHelpers()}
            pid_file="${'$'}runtime_dir/${id}.pid"
            pgid_file="${'$'}runtime_dir/${id}.pgid"
            launch_log="${'$'}runtime_dir/${id}.launch.log"

            result="${'$'}(proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${sh(statusInner)} 2>&1 || true)"
            printf '%s\n' "${'$'}result"

            pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
            pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"
            if siftalpha_pid_alive "${'$'}pid" || { [ -n "${'$'}pgid" ] && siftalpha_group_alive "${'$'}pgid"; }; then
              echo 'SIFTALPHA_STATUS=RUNNING'
              [ -n "${'$'}pid" ] && printf 'SIFTALPHA_HOST_PID=%s\n' "${'$'}pid"
              [ -n "${'$'}pgid" ] && printf 'SIFTALPHA_HOST_PGID=%s\n' "${'$'}pgid"
              echo 'SIFTALPHA_RUNTIME_BACKEND=TERMUX_PROOT_PID'
              exit 0
            fi
            rm -f "${'$'}pid_file" "${'$'}pgid_file"

            if printf '%s\n' "${'$'}result" | grep -q '^STATE=STOPPED_BY_USER${'$'}'; then
              echo 'SIFTALPHA_STATUS=STOPPED_BY_USER'
            elif printf '%s\n' "${'$'}result" | grep -q '^STATE=EXITED${'$'}'; then
              echo 'SIFTALPHA_STATUS=EXITED'
            elif printf '%s\n' "${'$'}result" | grep -q '^STATE=STARTING${'$'}'; then
              echo 'SIFTALPHA_ERROR=RUNTIME_LAUNCH_FAILED'
              echo 'SIFTALPHA_STATUS=EXITED_ERROR'
              if [ -s "${'$'}launch_log" ]; then
                echo '--- launcher ---'
                tail -n 80 "${'$'}launch_log" 2>/dev/null || true
              fi
              exit 75
            else
              echo 'SIFTALPHA_STATUS=STOPPED'
            fi
        """.trimIndent()
    }

    private fun buildLogs(project: RuntimeProjectSpec): String {
        val id = host.runtimeId(project.folderName)
        val log = "/root/siftalpha/logs/run-$id.log"
        val state = "/root/siftalpha/state-$id.txt"
        val logsInner = """
            log=${sh(log)}
            state=${sh(state)}
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
            if [ -s "${'$'}log" ]; then
              tail -n 160 "${'$'}log"
            else
              echo 'SIFTALPHA_LOG=EMPTY'
            fi
        """.trimIndent()
        return """
            ${host.hostPreamble()}
            ${host.hostProcessHelpers()}
            pid_file="${'$'}runtime_dir/${id}.pid"
            pgid_file="${'$'}runtime_dir/${id}.pgid"
            launch_log="${'$'}runtime_dir/${id}.launch.log"
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
            proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${sh(logsInner)} || true
            if [ -s "${'$'}launch_log" ]; then
              echo '--- launcher ---'
              tail -n 40 "${'$'}launch_log" 2>/dev/null || true
            fi
        """.trimIndent()
    }

    private fun buildClean(project: RuntimeProjectSpec): String {
        val id = host.runtimeId(project.folderName)
        val cleanInner = """
            rm -rf -- ${sh("/root/venvs/$id")}
            rm -f -- ${sh("/root/siftalpha/logs/run-$id.log")} ${sh("/root/siftalpha/logs/prepare-$id.log")} ${sh("/root/siftalpha/run-$id.sh")} ${sh("/root/siftalpha/state-$id.txt")} ${sh("/root/siftalpha/secrets-$id.env")} ${sh("/root/siftalpha/env-ready-$id.txt")}
        """.trimIndent()
        return """
            ${host.hostPreamble()}
            ${host.hostProcessHelpers()}
            pid_file="${'$'}runtime_dir/${id}.pid"
            pgid_file="${'$'}runtime_dir/${id}.pgid"
            launch_log="${'$'}runtime_dir/${id}.launch.log"
            pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
            pgid="${'$'}(cat "${'$'}pgid_file" 2>/dev/null || true)"

            if [ -n "${'$'}pid" ] || [ -n "${'$'}pgid" ]; then
              if ! siftalpha_stop_tree "${'$'}pid" "${'$'}pgid"; then
                echo 'SIFTALPHA_ERROR=STOP_INCOMPLETE'
                echo 'SIFTALPHA_STATUS=CLEAN_BLOCKED_RUNNING_PROCESS'
                exit 78
              fi
            fi

            rm -f "${'$'}pid_file" "${'$'}pgid_file" "${'$'}launch_log" "${'$'}runtime_dir/${id}.secrets.in"
            proot-distro login --bind "${'$'}ROOT:/root/projects" ubuntu -- bash -lc ${sh(cleanInner)}
            echo 'SIFTALPHA_ENV=CLEANED'
            echo 'SIFTALPHA_STATUS=STOPPED'
        """.trimIndent()
    }

    /** Sets dependency_source and dependency_hash from the same precedence used by PREPARE. */
    private fun dependencyFingerprintShell(): String = """
        dependency_source='none'
        dependency_hash='none'
        if [ -s "${'$'}project/requirements.txt" ]; then
          dependency_source='requirements.txt'
          dependency_hash="${'$'}(sha256sum "${'$'}project/requirements.txt" | awk '{print ${'$'}1}')"
        elif [ -f "${'$'}project/pyproject.toml" ]; then
          dependency_source='pyproject.toml'
          dependency_hash="${'$'}(sha256sum "${'$'}project/pyproject.toml" | awk '{print ${'$'}1}')"
        fi
    """.trimIndent()

    /**
     * Requires project, venv and ready shell variables. Sets env_ready (0/1), env_reason,
     * dependency_source and dependency_hash without mutating the environment.
     */
    private fun environmentReadyCheckShell(): String = """
        ${dependencyFingerprintShell()}
        env_ready=0
        env_reason='VENV_MISSING'
        if [ -x "${'$'}venv/bin/python" ]; then
          env_reason='PREPARE_MARKER_MISSING'
          if [ -f "${'$'}ready" ]; then
            saved_source="${'$'}(awk -F= '/^SOURCE=/{print substr(${ '$' }0,8); exit}' "${'$'}ready" 2>/dev/null || true)"
            saved_hash="${'$'}(awk -F= '/^HASH=/{print substr(${ '$' }0,6); exit}' "${'$'}ready" 2>/dev/null || true)"
            if [ "${'$'}saved_source" = "${'$'}dependency_source" ] && [ "${'$'}saved_hash" = "${'$'}dependency_hash" ]; then
              env_ready=1
              env_reason='READY'
            else
              env_reason='DEPENDENCY_MANIFEST_CHANGED'
            fi
          fi
        fi
    """.trimIndent()

    private fun sh(value: String): String = host.sh(value)
}

object RuntimeAdapterCatalog {
    fun forHost(host: RuntimeCommandHost): RuntimeAdapterRegistry = RuntimeAdapterRegistry(
        listOf(PythonRuntimeAdapter(host)),
    )
}
