package com.siftalpha.studio.runtime

/**
 * Composes the primary Python environment with supplemental Runtime preparation steps.
 *
 * The UI consumes one project-level SIFTALPHA_ENV marker. Child adapters keep their own markers so
 * a supplemental failure cannot be hidden by an earlier Python READY line.
 */
object RuntimeEnvironmentComposer {

    fun prepare(
        host: RuntimeCommandHost,
        projectName: String,
        primary: RuntimeCommand,
        node: RuntimeCommand,
    ): RuntimeCommand = RuntimeCommand(
        shellScript = """
            set +e
            primary_output="${'$'}(bash -lc ${host.sh(primary.shellScript)} 2>&1)"
            primary_code=${'$'}?
            printf '%s\n' "${'$'}primary_output" | sed \
              -e '/^SIFTALPHA_ENV=READY${'$'}/d' \
              -e '/^SIFTALPHA_ENV=NOT_READY${'$'}/d' \
              -e '/^SIFTALPHA_ENV=CLEANED${'$'}/d'
            if [ "${'$'}primary_code" -ne 0 ]; then
              echo 'SIFTALPHA_ENV=NOT_READY'
              exit "${'$'}primary_code"
            fi

            node_output="${'$'}(bash -lc ${host.sh(node.shellScript)} 2>&1)"
            node_code=${'$'}?
            printf '%s\n' "${'$'}node_output"
            if [ "${'$'}node_code" -ne 0 ]; then
              echo 'SIFTALPHA_ENV=NOT_READY'
              exit "${'$'}node_code"
            fi
            if printf '%s\n' "${'$'}node_output" | grep -Eq '^SIFTALPHA_NODE_ENV=(READY|NOT_REQUIRED)${'$'}'; then
              echo 'SIFTALPHA_ENV=READY'
              exit 0
            fi
            echo 'SIFTALPHA_ENV=NOT_READY'
            echo 'SIFTALPHA_ENV_REASON=NODE_ENVIRONMENT_UNKNOWN'
            exit 89
        """.trimIndent(),
        label = "$projectName · 准备环境",
        description = "$projectName · 准备 Python 与检测到的 Node.js Web 组件",
    )

    /**
     * Re-check supplemental readiness immediately before launching the primary process.
     *
     * This closes the gap where frontend sources could change after PREPARE but before START. The
     * accepted Python start command remains untouched (including secret injection and lifecycle
     * behavior) and runs only after Node reports READY or NOT_REQUIRED.
     */
    fun start(
        host: RuntimeCommandHost,
        projectName: String,
        primary: RuntimeCommand,
        nodeStatus: RuntimeCommand,
    ): RuntimeCommand = RuntimeCommand(
        shellScript = """
            set +e
            node_output="${'$'}(bash -lc ${host.sh(nodeStatus.shellScript)} 2>&1)"
            node_code=${'$'}?
            printf '%s\n' "${'$'}node_output"
            if [ "${'$'}node_code" -ne 0 ] || \
               ! printf '%s\n' "${'$'}node_output" | grep -Eq '^SIFTALPHA_NODE_ENV=(READY|NOT_REQUIRED)${'$'}'; then
              node_reason="${'$'}(printf '%s\n' "${'$'}node_output" | awk -F= '/^SIFTALPHA_NODE_ENV_REASON=/{print ${'$'}2; exit}')"
              echo 'SIFTALPHA_ERROR=NODE_ENV_NOT_READY'
              printf 'SIFTALPHA_ENV_REASON=NODE_%s\n' "${'$'}{node_reason:-NOT_READY}"
              exit 73
            fi
            exec bash -lc ${host.sh(primary.shellScript)}
        """.trimIndent(),
        label = primary.label,
        description = primary.description.ifBlank { "$projectName · 启动" },
        background = primary.background,
        secretNamespace = primary.secretNamespace,
        stdinPayloadProvider = primary.stdinPayloadProvider,
    )

    fun status(
        host: RuntimeCommandHost,
        projectName: String,
        primary: RuntimeCommand,
        node: RuntimeCommand,
    ): RuntimeCommand = RuntimeCommand(
        shellScript = """
            set +e
            primary_output="${'$'}(bash -lc ${host.sh(primary.shellScript)} 2>&1)"
            primary_code=${'$'}?
            node_output="${'$'}(bash -lc ${host.sh(node.shellScript)} 2>&1)"
            node_code=${'$'}?

            printf '%s\n' "${'$'}primary_output" | sed \
              -e '/^SIFTALPHA_ENV=READY${'$'}/d' \
              -e '/^SIFTALPHA_ENV=NOT_READY${'$'}/d' \
              -e '/^SIFTALPHA_ENV_REASON=/d'
            printf '%s\n' "${'$'}node_output"

            primary_ready=0
            node_ready=0
            printf '%s\n' "${'$'}primary_output" | grep -q '^SIFTALPHA_ENV=READY${'$'}' && primary_ready=1
            printf '%s\n' "${'$'}node_output" | grep -Eq '^SIFTALPHA_NODE_ENV=(READY|NOT_REQUIRED)${'$'}' && node_ready=1

            if [ "${'$'}primary_ready" -eq 1 ] && [ "${'$'}node_ready" -eq 1 ]; then
              echo 'SIFTALPHA_ENV=READY'
            else
              echo 'SIFTALPHA_ENV=NOT_READY'
              if [ "${'$'}primary_ready" -ne 1 ]; then
                primary_reason="${'$'}(printf '%s\n' "${'$'}primary_output" | awk -F= '/^SIFTALPHA_ENV_REASON=/{print ${'$'}2; exit}')"
                printf 'SIFTALPHA_ENV_REASON=%s\n' "${'$'}{primary_reason:-PYTHON_NOT_READY}"
              else
                node_reason="${'$'}(printf '%s\n' "${'$'}node_output" | awk -F= '/^SIFTALPHA_NODE_ENV_REASON=/{print ${'$'}2; exit}')"
                printf 'SIFTALPHA_ENV_REASON=NODE_%s\n' "${'$'}{node_reason:-NOT_READY}"
              fi
            fi

            if [ "${'$'}primary_code" -ne 0 ]; then
              exit "${'$'}primary_code"
            fi
            exit "${'$'}node_code"
        """.trimIndent(),
        label = "$projectName · 状态",
        description = "$projectName · 检查项目运行状态与多 Runtime 环境就绪状态",
    )

    fun clean(
        host: RuntimeCommandHost,
        projectName: String,
        primary: RuntimeCommand,
        node: RuntimeCommand,
    ): RuntimeCommand = RuntimeCommand(
        shellScript = """
            set +e
            primary_output="${'$'}(bash -lc ${host.sh(primary.shellScript)} 2>&1)"
            primary_code=${'$'}?
            printf '%s\n' "${'$'}primary_output" | sed -e '/^SIFTALPHA_ENV=CLEANED${'$'}/d'
            if [ "${'$'}primary_code" -ne 0 ]; then
              exit "${'$'}primary_code"
            fi

            node_output="${'$'}(bash -lc ${host.sh(node.shellScript)} 2>&1)"
            node_code=${'$'}?
            printf '%s\n' "${'$'}node_output"
            if [ "${'$'}node_code" -ne 0 ]; then
              exit "${'$'}node_code"
            fi
            echo 'SIFTALPHA_ENV=CLEANED'
        """.trimIndent(),
        label = "$projectName · 清理环境",
        description = "$projectName · 清理 Python 与 Runtime 本地 Node.js 环境",
    )
}
