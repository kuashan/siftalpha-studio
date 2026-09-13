package com.siftalpha.studio.runtime

/**
 * Runtime 诊断结果模型。
 * v0.4.3 将环境探测输出转换成结构化结果，便于直接定位 Runtime 缺失项。
 */
data class RuntimeDiagnostic(
    val architecture: String,
    val termux: Boolean,
    val runCommandPermission: Boolean,
    val proot: Boolean,
    val ubuntu: Boolean,
    val python: String?,
    val pip: String?,
    val venv: Boolean,
    val tmux: Boolean,
    val libc: Boolean,
) {
    fun summary(): String = buildString {
        appendLine("ARCH=$architecture")
        appendLine("TERMUX=$termux")
        appendLine("RUN_COMMAND=$runCommandPermission")
        appendLine("PROOT=$proot")
        appendLine("UBUNTU=$ubuntu")
        appendLine("PYTHON=${python ?: "MISSING"}")
        appendLine("PIP=${pip ?: "MISSING"}")
        appendLine("VENV=$venv")
        appendLine("TMUX=$tmux")
        appendLine("LIBC=$libc")
    }

    companion object {
        fun fromProbe(output: String, runCommandPermission: Boolean): RuntimeDiagnostic {
            fun value(prefix: String): String? = output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith(prefix) }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("MISSING", ignoreCase = true) }

            val tmuxValue = value("TMUX=")
            val libcValue = value("LIBC=")
            return RuntimeDiagnostic(
                architecture = value("ARCH=") ?: "unknown",
                termux = "TERMUX=OK" in output,
                runCommandPermission = runCommandPermission,
                proot = "PROOT_DISTRO=OK" in output,
                ubuntu = "UBUNTU=OK" in output || "UBUNTU_LOGIN=OK" in output,
                python = value("PYTHON="),
                pip = value("PIP="),
                venv = "VENV=OK" in output,
                tmux = tmuxValue != null,
                libc = libcValue != null,
            )
        }
    }
}
