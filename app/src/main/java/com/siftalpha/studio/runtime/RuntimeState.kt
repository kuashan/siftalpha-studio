package com.siftalpha.studio.runtime

import android.content.Context
import com.siftalpha.studio.R

/**
 * Runtime 生命周期状态。
 * 用于区分用户停止、程序退出、启动阶段与环境异常。
 */
enum class RuntimeState {
    PREPARING,
    STARTING,
    RUNNING,
    EXITED_SUCCESS,
    EXITED_ERROR,
    STOPPED_BY_USER,
    ENVIRONMENT_ERROR,
    UNKNOWN;

    fun uiLabel(context: Context, environmentReady: Boolean? = null): String {
        val env = when (environmentReady) {
            true -> context.getString(R.string.runtime_state_env_ready_suffix)
            false -> context.getString(R.string.runtime_state_env_not_ready_suffix)
            null -> ""
        }
        return when (this) {
            PREPARING -> context.getString(R.string.runtime_state_preparing)
            STARTING -> context.getString(R.string.runtime_state_starting)
            RUNNING -> context.getString(R.string.runtime_state_running, env)
            EXITED_SUCCESS -> context.getString(R.string.runtime_state_exited_success, env)
            EXITED_ERROR -> context.getString(R.string.runtime_state_exited_error, env)
            STOPPED_BY_USER -> context.getString(R.string.runtime_state_stopped_by_user, env)
            ENVIRONMENT_ERROR -> context.getString(R.string.runtime_state_environment_error, env)
            UNKNOWN -> when (environmentReady) {
                true -> context.getString(R.string.runtime_state_env_ready)
                false -> context.getString(R.string.runtime_state_env_not_ready)
                null -> context.getString(R.string.runtime_state_not_checked)
            }
        }
    }

    internal fun labelResource(): Int = when (this) {
        PREPARING -> R.string.runtime_state_preparing
        STARTING -> R.string.runtime_state_starting
        RUNNING -> R.string.runtime_state_running
        EXITED_SUCCESS -> R.string.runtime_state_exited_success
        EXITED_ERROR -> R.string.runtime_state_exited_error
        STOPPED_BY_USER -> R.string.runtime_state_stopped_by_user
        ENVIRONMENT_ERROR -> R.string.runtime_state_environment_error
        UNKNOWN -> R.string.runtime_state_not_checked
    }

    companion object {
        fun fromOutput(output: String): RuntimeState {
            val lines = output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()

            // An explicit reconciled Runtime state is authoritative when the Runtime command emits one.
            lines.lastOrNull { it.startsWith("SIFTALPHA_RUNTIME_STATE=") }
                ?.substringAfter('=')
                ?.let(::fromStateToken)
                ?.let { return it }

            // ManagedProcessRuntime prints the current guest state file before the bounded historical
            // runtime-log tail. Use that current snapshot before any host PID liveness/status evidence:
            // a PRoot wrapper can remain alive briefly after the actual workload has already EXITED.
            lines.firstOrNull { it.startsWith("STATE=") }
                ?.substringAfter('=')
                ?.let { state ->
                    when (state) {
                        "EXITED" -> return if (extractExitCode(output) == 0) EXITED_SUCCESS else EXITED_ERROR
                        else -> fromStateToken(state)?.let { return it }
                    }
                }

            return when {
                lines.any { it == "SIFTALPHA_STATUS=STOPPED_BY_USER" } -> STOPPED_BY_USER
                lines.any { it == "SIFTALPHA_STATUS=EXITED_SUCCESS" } -> EXITED_SUCCESS
                lines.any { it == "SIFTALPHA_STATUS=EXITED_ERROR" || it == "SIFTALPHA_STATUS=EXITED" } ->
                    if (extractExitCode(output) == 0) EXITED_SUCCESS else EXITED_ERROR
                lines.any { it == "SIFTALPHA_STATUS=RUNNING" } -> RUNNING
                lines.any { it.startsWith("SIFTALPHA_ERROR=") || it == "STATE=START_FAILED" } -> ENVIRONMENT_ERROR
                else -> UNKNOWN
            }
        }

        fun extractExitCode(output: String): Int? {
            val lines = output.lineSequence().map { it.trim() }.toList()
            val exitLine = lines.lastOrNull { it.startsWith("EXIT_CODE=") }
                ?: lines.lastOrNull { it.startsWith("SIFTALPHA_PROCESS_EXIT=") }
                ?: return null
            return exitLine.substringAfter('=').trim().toIntOrNull()
        }

        private fun fromStateToken(token: String): RuntimeState? = when (token.trim()) {
            "PREPARING" -> PREPARING
            "STARTING" -> STARTING
            "RUNNING" -> RUNNING
            "EXITED_SUCCESS" -> EXITED_SUCCESS
            "EXITED_ERROR" -> EXITED_ERROR
            "STOPPED_BY_USER" -> STOPPED_BY_USER
            "ENVIRONMENT_ERROR" -> ENVIRONMENT_ERROR
            else -> null
        }
    }
}
