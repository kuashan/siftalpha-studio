package com.siftalpha.studio.runtime

import android.content.Context
import com.siftalpha.studio.R

/**
 * Project-level lifecycle states shown by Runtime Center.
 *
 * This is intentionally separate from the low-level RuntimeState parsed from Termux output:
 * the presentation state can say "Recovering" while the app is reconciling a persisted active
 * process with a fresh status command.
 */
enum class RuntimeLifecycleState {
    ENVIRONMENT_NOT_PREPARED,
    PREPARING,
    READY_TO_RUN,
    DETECTING,
    NEEDS_CONFIGURATION,
    RUNNING,
    STOPPED,
    RUN_FAILED,
    RECOVERING;

    fun uiLabel(context: Context): String = context.getString(
        when (this) {
            ENVIRONMENT_NOT_PREPARED -> R.string.runtime_lifecycle_environment_not_prepared
            PREPARING -> R.string.runtime_lifecycle_preparing
            READY_TO_RUN -> R.string.runtime_lifecycle_ready_to_run
            DETECTING -> R.string.runtime_lifecycle_detecting
            NEEDS_CONFIGURATION -> R.string.runtime_lifecycle_needs_configuration
            RUNNING -> R.string.runtime_lifecycle_running
            STOPPED -> R.string.runtime_lifecycle_stopped
            RUN_FAILED -> R.string.runtime_lifecycle_run_failed
            RECOVERING -> R.string.runtime_lifecycle_recovering
        },
    )
}

enum class RuntimeLifecycleOperation {
    NONE,
    PREPARE,
    START,
    STOP,
    STATUS,
    LOGS,
    CLEAN,
}

object RuntimeLifecycleResolver {
    fun resolve(
        environmentReady: Boolean?,
        runtimeState: RuntimeState,
        operation: RuntimeLifecycleOperation = RuntimeLifecycleOperation.NONE,
        configurationRequired: Boolean = false,
        processActive: Boolean = false,
        recoveryInProgress: Boolean = false,
    ): RuntimeLifecycleState {
        if (recoveryInProgress) return RuntimeLifecycleState.RECOVERING

        when (operation) {
            RuntimeLifecycleOperation.PREPARE -> return RuntimeLifecycleState.PREPARING
            RuntimeLifecycleOperation.START,
            RuntimeLifecycleOperation.STATUS,
            RuntimeLifecycleOperation.LOGS,
            -> return RuntimeLifecycleState.DETECTING
            RuntimeLifecycleOperation.STOP,
            RuntimeLifecycleOperation.CLEAN,
            -> return RuntimeLifecycleState.RECOVERING
            RuntimeLifecycleOperation.NONE -> Unit
        }

        if (environmentReady != true) return RuntimeLifecycleState.ENVIRONMENT_NOT_PREPARED
        if (configurationRequired) return RuntimeLifecycleState.NEEDS_CONFIGURATION

        return when {
            runtimeState == RuntimeState.RUNNING || processActive ->
                RuntimeLifecycleState.RUNNING
            runtimeState == RuntimeState.PREPARING ||
                runtimeState == RuntimeState.STARTING ->
                RuntimeLifecycleState.DETECTING
            runtimeState == RuntimeState.EXITED_ERROR ||
                runtimeState == RuntimeState.ENVIRONMENT_ERROR ->
                RuntimeLifecycleState.RUN_FAILED
            runtimeState == RuntimeState.EXITED_SUCCESS ||
                runtimeState == RuntimeState.STOPPED_BY_USER ->
                RuntimeLifecycleState.STOPPED
            else -> RuntimeLifecycleState.READY_TO_RUN
        }
    }
}
