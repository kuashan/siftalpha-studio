package com.siftalpha.studio.runtime

/**
 * Structured Python dependency and runtime compatibility diagnostics.
 *
 * This component never installs packages from import names. It only inspects prepare/run logs and
 * emits machine-readable SIFTALPHA_DIAG markers that the UI (or future Runtime Agent) can explain.
 * Dependency installation remains driven by requirements.txt / pyproject.toml.
 */
object PythonDependencyDiagnostics {

    enum class Kind(val marker: String) {
        PYTHON_VERSION_MISMATCH("PYTHON_VERSION_MISMATCH"),
        PACKAGE_NOT_FOUND("PACKAGE_NOT_FOUND"),
        PLATFORM_WHEEL_INCOMPATIBLE("PLATFORM_WHEEL_INCOMPATIBLE"),
        NATIVE_BUILD_FAILED("NATIVE_BUILD_FAILED"),
        NETWORK_ERROR("NETWORK_ERROR"),
        STORAGE_FULL("STORAGE_FULL"),
        DEPENDENCY_INSTALL_FAILED("DEPENDENCY_INSTALL_FAILED"),
        PYTHON_MODULE_MISSING("PYTHON_MODULE_MISSING"),
        PYTHON_IMPORT_ERROR("PYTHON_IMPORT_ERROR"),
        SYSTEM_LIBRARY_MISSING("SYSTEM_LIBRARY_MISSING"),
        SYSTEM_COMMAND_MISSING("SYSTEM_COMMAND_MISSING"),
        FILESYSTEM_LOCK_UNSUPPORTED("FILESYSTEM_LOCK_UNSUPPORTED"),
    }

    data class Diagnostic(
        val kind: Kind,
        val stage: String?,
        val detail: String?,
        val dependencySource: String?,
        val hint: String?,
    )

    /** Parse the latest structured diagnostic block from command output. */
    fun parseLatest(text: String): Diagnostic? {
        var kind: Kind? = null
        var stage: String? = null
        var detail: String? = null
        var dependencySource: String? = null
        var hint: String? = null

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("SIFTALPHA_DIAG_STAGE=") -> stage = line.substringAfter('=')
                line.startsWith("SIFTALPHA_DIAG=") -> {
                    kind = Kind.entries.firstOrNull { it.marker == line.substringAfter('=') }
                    detail = null
                    dependencySource = null
                    hint = null
                }
                line.startsWith("SIFTALPHA_DIAG_DETAIL=") -> detail = line.substringAfter('=')
                line.startsWith("SIFTALPHA_DIAG_DEPENDENCY_SOURCE=") ->
                    dependencySource = line.substringAfter('=')
                line.startsWith("SIFTALPHA_DIAG_HINT=") -> hint = line.substringAfter('=')
            }
        }

        val resolved = kind ?: return null
        return Diagnostic(resolved, stage, detail, dependencySource, hint)
    }

    fun wrapPrepareFailure(
        base: RuntimeCommand,
        project: RuntimeProjectSpec,
        host: RuntimeCommandHost,
    ): RuntimeCommand = wrapFailure(base, prepareFailureShell(project, host))

    /**
     * Runtime logs may expose a real compatibility failure even when an upstream application catches
     * the exception and exits with code 0. Diagnose after every START result; the diagnosis itself is
     * read-only and exits silently while a process is still running or when no known issue exists.
     */
    fun wrapStartFailure(
        base: RuntimeCommand,
        project: RuntimeProjectSpec,
        host: RuntimeCommandHost,
    ): RuntimeCommand = wrapAlways(base, runtimeLogDiagnosisShell(project, host))

    fun appendRuntimeLogDiagnosis(
        base: RuntimeCommand,
        project: RuntimeProjectSpec,
        host: RuntimeCommandHost,
    ): RuntimeCommand = wrapAlways(base, runtimeLogDiagnosisShell(project, host))

    /**
     * Execute the accepted Runtime command in its own bash child, then diagnose from the parent.
     *
     * The base commands intentionally use `set -e`. Keeping them in a separate `bash -lc` process
     * prevents their errexit/exit behaviour from terminating the diagnostic wrapper before it can
     * inspect logs and emit SIFTALPHA_DIAG markers on real Termux/PRoot devices.
     */
    internal fun wrapFailureShell(baseShell: String, diagnosisShell: String): String = """
        set +e
        bash -lc ${shellQuote(baseShell)}
        siftalpha_base_code=${'$'}?
        if [ "${'$'}siftalpha_base_code" -ne 0 ]; then
          bash -lc ${shellQuote(diagnosisShell)}
        fi
        exit "${'$'}siftalpha_base_code"
    """.trimIndent()

    internal fun wrapAlwaysShell(baseShell: String, diagnosisShell: String): String = """
        set +e
        bash -lc ${shellQuote(baseShell)}
        siftalpha_base_code=${'$'}?
        bash -lc ${shellQuote(diagnosisShell)} || true
        exit "${'$'}siftalpha_base_code"
    """.trimIndent()

    private fun wrapFailure(base: RuntimeCommand, diagnosisShell: String): RuntimeCommand =
        base.copy(shellScript = wrapFailureShell(base.shellScript, diagnosisShell))

    private fun wrapAlways(base: RuntimeCommand, diagnosisShell: String): RuntimeCommand =
        base.copy(shellScript = wrapAlwaysShell(base.shellScript, diagnosisShell))

    private fun prepareFailureShell(project: RuntimeProjectSpec, host: RuntimeCommandHost): String {
        val id = host.runtimeId(project.folderName)
        val log = "/root/siftalpha/logs/prepare-$id.log"
        val path = "/root/projects/${project.folderName}"
        val inner = """
            set +e
            log=${host.sh(log)}
            project=${host.sh(path)}
            source='none'
            if [ -s "${'$'}project/requirements.txt" ]; then
              source='requirements.txt'
            elif [ -f "${'$'}project/pyproject.toml" ]; then
              source='pyproject.toml'
            fi

            diag=''
            detail=''
            hint='CHECK_PREPARE_LOG'

            if [ -s "${'$'}log" ]; then
              if grep -Eiq 'No space left on device|Disk quota exceeded' "${'$'}log"; then
                diag='STORAGE_FULL'
                detail="${'$'}(grep -Ei 'No space left on device|Disk quota exceeded' "${'$'}log" | tail -n 1 || true)"
                hint='FREE_RUNTIME_STORAGE'
              elif grep -Eiq 'requires a different Python|Requires-Python|not supported on this version of Python|Python version .* is not supported' "${'$'}log"; then
                diag='PYTHON_VERSION_MISMATCH'
                detail="${'$'}(grep -Ei 'requires a different Python|Requires-Python|not supported on this version of Python|Python version .* is not supported' "${'$'}log" | tail -n 1 || true)"
                hint='USE_COMPATIBLE_PYTHON_VERSION'
              elif grep -Eiq 'No matching distribution found for|Could not find a version that satisfies the requirement' "${'$'}log"; then
                diag='PACKAGE_NOT_FOUND'
                detail="${'$'}(grep -Ei 'No matching distribution found for|Could not find a version that satisfies the requirement' "${'$'}log" | tail -n 1 || true)"
                hint='CHECK_PACKAGE_VERSION_AND_PLATFORM'
              elif grep -Eiq 'not a supported wheel on this platform|UnsupportedWheel|manylinux_[0-9_]+.*not compatible|GLIBC_[0-9.]+' "${'$'}log"; then
                diag='PLATFORM_WHEEL_INCOMPATIBLE'
                detail="${'$'}(grep -Ei 'not a supported wheel on this platform|UnsupportedWheel|manylinux_[0-9_]+.*not compatible|GLIBC_[0-9.]+' "${'$'}log" | tail -n 1 || true)"
                hint='CHECK_ANDROID_ARM64_GLIBC_COMPATIBILITY'
              elif grep -Eiq 'Failed building wheel for|Could not build wheels for|subprocess-exited-with-error|error: command .* failed' "${'$'}log"; then
                diag='NATIVE_BUILD_FAILED'
                detail="${'$'}(grep -Ei 'Failed building wheel for|Could not build wheels for|subprocess-exited-with-error|error: command .* failed' "${'$'}log" | tail -n 1 || true)"
                hint='CHECK_NATIVE_BUILD_DEPENDENCIES'
              elif grep -Eiq 'Temporary failure in name resolution|Connection timed out|Read timed out|SSLError|ProxyError|ConnectionError|Max retries exceeded' "${'$'}log"; then
                diag='NETWORK_ERROR'
                detail="${'$'}(grep -Ei 'Temporary failure in name resolution|Connection timed out|Read timed out|SSLError|ProxyError|ConnectionError|Max retries exceeded' "${'$'}log" | tail -n 1 || true)"
                hint='CHECK_NETWORK_AND_RETRY'
              fi
            fi

            if [ -z "${'$'}diag" ]; then
              diag='DEPENDENCY_INSTALL_FAILED'
            fi
            echo 'SIFTALPHA_DIAG_STAGE=PREPARE'
            printf 'SIFTALPHA_DIAG=%s\n' "${'$'}diag"
            [ -n "${'$'}detail" ] && printf 'SIFTALPHA_DIAG_DETAIL=%s\n' "${'$'}detail"
            printf 'SIFTALPHA_DIAG_DEPENDENCY_SOURCE=%s\n' "${'$'}source"
            printf 'SIFTALPHA_DIAG_HINT=%s\n' "${'$'}hint"
            if [ -s "${'$'}log" ]; then
              echo '--- dependency diagnostic tail ---'
              tail -n 80 "${'$'}log" 2>/dev/null || true
            fi
            exit 0
        """.trimIndent()
        return host.wrapUbuntu(inner)
    }

    private fun runtimeLogDiagnosisShell(project: RuntimeProjectSpec, host: RuntimeCommandHost): String {
        val id = host.runtimeId(project.folderName)
        val log = "/root/siftalpha/logs/run-$id.log"
        val state = "/root/siftalpha/state-$id.txt"
        val path = "/root/projects/${project.folderName}"
        val inner = """
            set +e
            log=${host.sh(log)}
            state=${host.sh(state)}
            project=${host.sh(path)}
            [ -s "${'$'}log" ] || exit 0
            [ -f "${'$'}state" ] || exit 0
            grep -q '^STATE=EXITED${'$'}' "${'$'}state" || exit 0
            runtime_exit="${'$'}(awk -F= '/^EXIT_CODE=/{code=${'$'}2} END{print code}' "${'$'}state" 2>/dev/null || true)"
            [ -n "${'$'}runtime_exit" ] || exit 0

            source='none'
            if [ -s "${'$'}project/requirements.txt" ]; then
              source='requirements.txt'
            elif [ -f "${'$'}project/pyproject.toml" ]; then
              source='pyproject.toml'
            fi

            # Android shared-storage filesystems can reject POSIX flock with ENOSYS even though
            # Python successfully imports fcntl. This is a host/filesystem compatibility failure,
            # not a missing package. Detect the exact high-confidence traceback even if the upstream
            # application catches it and later exits 0.
            if grep -Fq 'fcntl.flock' "${'$'}log" && \
               grep -Fq 'OSError: [Errno 38] Function not implemented' "${'$'}log"; then
              echo 'SIFTALPHA_DIAG_STAGE=RUN'
              echo 'SIFTALPHA_DIAG=FILESYSTEM_LOCK_UNSUPPORTED'
              echo 'SIFTALPHA_DIAG_DETAIL=fcntl.flock returned ENOSYS on the current project-backed filesystem'
              echo 'SIFTALPHA_DIAG_HINT=MOVE_LOCKED_MUTABLE_DATA_TO_RUNTIME_LOCAL_FILESYSTEM'
              exit 0
            fi

            # Repeated resolver failures across a completed run are strong evidence of a Runtime
            # DNS/network problem. One transient lookup failure is not enough to classify the run.
            dns_failures="${'$'}(grep -Eic 'Temporary failure in name resolution|NameResolutionError|Resolving timed out' "${'$'}log" 2>/dev/null || true)"
            if [ "${'$'}{dns_failures:-0}" -ge 3 ]; then
              echo 'SIFTALPHA_DIAG_STAGE=RUN'
              echo 'SIFTALPHA_DIAG=NETWORK_ERROR'
              printf 'SIFTALPHA_DIAG_DETAIL=Repeated DNS resolution failures (%s matches)\n' "${'$'}dns_failures"
              echo 'SIFTALPHA_DIAG_HINT=CHECK_RUNTIME_DNS_AND_NETWORK'
              exit 0
            fi

            # Import/library/command failures below remain tied to a non-zero child exit. A Python
            # application may intentionally log handled exceptions while still completing normally.
            [ "${'$'}runtime_exit" != '0' ] || exit 0

            # Python's standard ModuleNotFoundError renders the missing import in single quotes.
            # Use a fixed-string prefix and simple sed capture instead of a quote-heavy ERE; the
            # latter proved fragile after Kotlin -> host.sh -> bash -lc nesting on real devices.
            missing="${'$'}(grep -F "ModuleNotFoundError: No module named '" "${'$'}log" 2>/dev/null | tail -n 1 | sed -E "s/.*No module named '([^']+)'.*/\\1/" || true)"
            if [ -n "${'$'}missing" ]; then
              echo 'SIFTALPHA_DIAG_STAGE=RUN'
              echo 'SIFTALPHA_DIAG=PYTHON_MODULE_MISSING'
              printf 'SIFTALPHA_DIAG_DETAIL=%s\n' "${'$'}missing"
              printf 'SIFTALPHA_DIAG_DEPENDENCY_SOURCE=%s\n' "${'$'}source"
              if [ "${'$'}source" = 'none' ]; then
                echo 'SIFTALPHA_DIAG_HINT=DECLARE_DEPENDENCY'
              else
                echo 'SIFTALPHA_DIAG_HINT=CHECK_DECLARED_DEPENDENCIES'
              fi
              exit 0
            fi

            if grep -Eiq 'cannot open shared object file: No such file or directory' "${'$'}log"; then
              echo 'SIFTALPHA_DIAG_STAGE=RUN'
              echo 'SIFTALPHA_DIAG=SYSTEM_LIBRARY_MISSING'
              detail="${'$'}(grep -Ei 'cannot open shared object file: No such file or directory' "${'$'}log" | tail -n 1 || true)"
              printf 'SIFTALPHA_DIAG_DETAIL=%s\n' "${'$'}detail"
              echo 'SIFTALPHA_DIAG_HINT=INSTALL_SYSTEM_LIBRARY'
              exit 0
            fi

            if grep -Eiq 'command not found|FileNotFoundError: \[Errno 2\]' "${'$'}log"; then
              echo 'SIFTALPHA_DIAG_STAGE=RUN'
              echo 'SIFTALPHA_DIAG=SYSTEM_COMMAND_MISSING'
              detail="${'$'}(grep -Ei 'command not found|FileNotFoundError: \[Errno 2\]' "${'$'}log" | tail -n 1 || true)"
              printf 'SIFTALPHA_DIAG_DETAIL=%s\n' "${'$'}detail"
              echo 'SIFTALPHA_DIAG_HINT=INSTALL_SYSTEM_COMMAND_OR_CHECK_PATH'
              exit 0
            fi

            if grep -Eiq '^ImportError:|ImportError: cannot import name' "${'$'}log"; then
              echo 'SIFTALPHA_DIAG_STAGE=RUN'
              echo 'SIFTALPHA_DIAG=PYTHON_IMPORT_ERROR'
              detail="${'$'}(grep -Ei '^ImportError:|ImportError: cannot import name' "${'$'}log" | tail -n 1 || true)"
              printf 'SIFTALPHA_DIAG_DETAIL=%s\n' "${'$'}detail"
              printf 'SIFTALPHA_DIAG_DEPENDENCY_SOURCE=%s\n' "${'$'}source"
              echo 'SIFTALPHA_DIAG_HINT=CHECK_PACKAGE_VERSION_OR_IMPORT_API'
            fi
            exit 0
        """.trimIndent()
        return host.wrapUbuntu(inner)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
