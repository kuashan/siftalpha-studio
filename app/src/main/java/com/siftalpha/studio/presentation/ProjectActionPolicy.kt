package com.siftalpha.studio.presentation

import com.siftalpha.studio.runtime.RuntimeState
import com.siftalpha.studio.runtime.RuntimeWebUiStatus

/**
 * Pure action policy for a single [ProjectUiSnapshot].
 *
 * Every disabled operation carries a stable resource key. Android translation and dialogs remain
 * outside this class, while all cards use the same fail-closed decision matrix.
 */
object ProjectActionPolicy {

    enum class Action {
        EDIT,
        PREPARE,
        START,
        STOP,
        STATUS,
        LOGS,
        OPEN_BROWSER,
        CONFIGURE,
        CLEAN,
        OPEN_SOURCE,
    }

    enum class DetailEntry {
        RUNTIME,
        ENVIRONMENT,
        CONFIGURATION,
        WEB,
        SOURCE,
    }

    enum class MessageKey(val resourceKey: String) {
        PENDING_OPERATION("runtime_policy_pending_operation"),
        RUNTIME_HOST_UNAVAILABLE("runtime_policy_runtime_host_unavailable"),
        RUNTIME_SELECTION_REQUIRED("runtime_policy_runtime_selection_required"),
        RUNTIME_ACTIVE("runtime_policy_runtime_active"),
        WEB_ENDPOINT_PENDING("runtime_policy_web_endpoint_pending"),
        CONFIGURATION_REQUIRED("runtime_policy_configuration_required"),
        ENVIRONMENT_PREPARE_REQUIRED("runtime_policy_environment_prepare_required"),
        ENVIRONMENT_STATUS_REQUIRED("runtime_policy_environment_status_required"),
        RUNTIME_STATUS_REQUIRED("runtime_policy_runtime_status_required"),
        READY_TO_RUN("runtime_policy_ready_to_run"),
    }

    enum class DisableReason(val resourceKey: String) {
        PENDING_OPERATION("runtime_policy_reason_pending_operation"),
        RUNTIME_HOST_UNAVAILABLE("runtime_policy_reason_runtime_host_unavailable"),
        RUNTIME_SELECTION_REQUIRED("runtime_policy_reason_runtime_selection_required"),
        PROCESS_ACTIVE("runtime_policy_reason_process_active"),
        PROCESS_NOT_ACTIVE("runtime_policy_reason_process_not_active"),
        UNKNOWN_RUNTIME_STATE("runtime_policy_reason_unknown_runtime_state"),
        ENVIRONMENT_UNKNOWN("runtime_policy_reason_environment_unknown"),
        ENVIRONMENT_NOT_READY("runtime_policy_reason_environment_not_ready"),
        REQUIRED_CONFIGURATION_MISSING("runtime_policy_reason_required_configuration_missing"),
        WEB_NOT_AVAILABLE("runtime_policy_reason_web_not_available"),
        SOURCE_NOT_AVAILABLE("runtime_policy_reason_source_not_available"),
    }

    data class ActionDecision(
        val enabled: Boolean,
        val disableReason: DisableReason? = null,
    ) {
        init {
            require(enabled || disableReason != null) {
                "a disabled action must explain why it is disabled"
            }
            require(!enabled || disableReason == null) {
                "an enabled action cannot carry a disable reason"
            }
        }
    }

    data class Result(
        val summary: MessageKey,
        val explanationResourceKey: String,
        val primaryAction: Action?,
        val directSecondaryAction: Action?,
        val disableReason: DisableReason?,
        val detailEntry: DetailEntry,
        val actions: Map<Action, ActionDecision>,
    ) {
        init {
            require(actions.keys == Action.entries.toSet()) {
                "policy must provide a decision for every action"
            }
            require(explanationResourceKey == summary.resourceKey) {
                "explanation resource key must match the summary key"
            }
            primaryAction?.let { action ->
                require(actions.getValue(action).enabled) {
                    "primary action must be enabled: $action"
                }
            }
            directSecondaryAction?.let { action ->
                require(actions.getValue(action).enabled) {
                    "direct secondary action must be enabled: $action"
                }
            }
        }

        fun decision(action: Action): ActionDecision =
            actions[action] ?: error("No policy decision for $action")

        fun isEnabled(action: Action): Boolean = decision(action).enabled

        fun reasonFor(action: Action): DisableReason? = decision(action).disableReason
    }

    fun resolve(snapshot: ProjectUiSnapshot): Result {
        val actions = linkedMapOf<Action, ActionDecision>()
        actions[Action.EDIT] = enabled()
        // Configuration is a project-level operation, not a runtime-discovery result. It remains
        // available before and after environment preparation so the user can review or enter
        // optional values proactively. The Run action is still responsible for runtime detection.
        actions[Action.CONFIGURE] = enabled()
        actions[Action.OPEN_SOURCE] = if (snapshot.identity.sourceUrl.isNullOrBlank()) {
            disabled(DisableReason.SOURCE_NOT_AVAILABLE)
        } else {
            enabled()
        }

        val runtimeActions = listOf(
            Action.PREPARE,
            Action.START,
            Action.STOP,
            Action.STATUS,
            Action.LOGS,
            Action.CLEAN,
        )
        if (!snapshot.runtime.supported) {
            runtimeActions.forEach { actions[it] = disabled(DisableReason.RUNTIME_HOST_UNAVAILABLE) }
            actions[Action.OPEN_BROWSER] = disabled(DisableReason.WEB_NOT_AVAILABLE)
            return result(
                actions = actions,
                summary = MessageKey.RUNTIME_HOST_UNAVAILABLE,
                primaryAction = null,
                directSecondaryAction = null,
                disableReason = DisableReason.RUNTIME_HOST_UNAVAILABLE,
                detailEntry = DetailEntry.RUNTIME,
            )
        }

        if (snapshot.pending != null) {
            runtimeActions.forEach { actions[it] = disabled(DisableReason.PENDING_OPERATION) }
            actions[Action.CONFIGURE] = disabled(DisableReason.PENDING_OPERATION)
            actions[Action.OPEN_BROWSER] = disabled(DisableReason.PENDING_OPERATION)
            return result(
                actions = actions,
                summary = MessageKey.PENDING_OPERATION,
                primaryAction = null,
                directSecondaryAction = null,
                disableReason = DisableReason.PENDING_OPERATION,
                detailEntry = DetailEntry.RUNTIME,
            )
        }

        val selectionReason = when (snapshot.runtime.selection.status) {
            ProjectUiSnapshot.Runtime.SelectionStatus.RESOLVED -> null
            ProjectUiSnapshot.Runtime.SelectionStatus.AMBIGUOUS,
            ProjectUiSnapshot.Runtime.SelectionStatus.UNSUPPORTED,
            -> DisableReason.RUNTIME_SELECTION_REQUIRED
        }

        actions[Action.STATUS] = enabled()
        actions[Action.LOGS] = enabled()
        actions[Action.CLEAN] = enabled()
        actions[Action.PREPARE] = if (selectionReason == null) {
            enabled()
        } else {
            disabled(selectionReason)
        }
        actions[Action.START] = if (selectionReason == null) {
            enabled()
        } else {
            disabled(selectionReason)
        }

        val active = snapshot.processMayBeActive
        val primaryAction: Action?
        val secondaryAction: Action?
        val message: MessageKey
        val disableReason: DisableReason?
        val detailEntry: DetailEntry

        if (active) {
            actions[Action.PREPARE] = disabled(DisableReason.PROCESS_ACTIVE)
            actions[Action.START] = disabled(DisableReason.PROCESS_ACTIVE)
            actions[Action.CLEAN] = disabled(DisableReason.PROCESS_ACTIVE)
            actions[Action.STOP] = enabled()
            primaryAction = Action.STOP
            secondaryAction = Action.STATUS
            if (
                snapshot.lifecycle == RuntimeState.RUNNING &&
                snapshot.web.expected &&
                snapshot.web.status != RuntimeWebUiStatus.AVAILABLE
            ) {
                message = MessageKey.WEB_ENDPOINT_PENDING
                detailEntry = DetailEntry.WEB
            } else {
                message = MessageKey.RUNTIME_ACTIVE
                detailEntry = DetailEntry.RUNTIME
            }
            disableReason = null
        } else {
            actions[Action.STOP] = disabled(DisableReason.PROCESS_NOT_ACTIVE)
            when {
                selectionReason != null -> {
                    primaryAction = Action.STATUS
                    secondaryAction = Action.CLEAN
                    message = MessageKey.RUNTIME_SELECTION_REQUIRED
                    disableReason = selectionReason
                    detailEntry = DetailEntry.RUNTIME
                }
                snapshot.environment.readiness == ProjectUiSnapshot.Readiness.NOT_READY -> {
                    actions[Action.START] = disabled(DisableReason.ENVIRONMENT_NOT_READY)
                    primaryAction = Action.PREPARE
                    secondaryAction = Action.STATUS
                    message = MessageKey.ENVIRONMENT_PREPARE_REQUIRED
                    disableReason = DisableReason.ENVIRONMENT_NOT_READY
                    detailEntry = DetailEntry.ENVIRONMENT
                }
                snapshot.environment.readiness == ProjectUiSnapshot.Readiness.UNKNOWN -> {
                    actions[Action.START] = disabled(DisableReason.ENVIRONMENT_UNKNOWN)
                    primaryAction = Action.STATUS
                    secondaryAction = Action.PREPARE
                    message = MessageKey.ENVIRONMENT_STATUS_REQUIRED
                    disableReason = DisableReason.ENVIRONMENT_UNKNOWN
                    detailEntry = DetailEntry.ENVIRONMENT
                }
                else -> {
                    primaryAction = Action.START
                    secondaryAction = Action.STATUS
                    message = MessageKey.READY_TO_RUN
                    disableReason = null
                    detailEntry = DetailEntry.RUNTIME
                }
            }
        }

        actions[Action.OPEN_BROWSER] = if (
            snapshot.lifecycle == RuntimeState.RUNNING &&
                snapshot.web.status == RuntimeWebUiStatus.AVAILABLE &&
                snapshot.web.endpointReachable == true &&
                !snapshot.web.reachableUrl.isNullOrBlank()
        ) {
            enabled()
        } else {
            disabled(DisableReason.WEB_NOT_AVAILABLE)
        }

        return result(
            actions = actions,
            summary = message,
            primaryAction = primaryAction,
            directSecondaryAction = secondaryAction,
            disableReason = disableReason,
            detailEntry = detailEntry,
        )
    }

    private fun result(
        actions: Map<Action, ActionDecision>,
        summary: MessageKey,
        primaryAction: Action?,
        directSecondaryAction: Action?,
        disableReason: DisableReason?,
        detailEntry: DetailEntry,
    ): Result = Result(
        summary = summary,
        explanationResourceKey = summary.resourceKey,
        primaryAction = primaryAction,
        directSecondaryAction = directSecondaryAction,
        disableReason = disableReason,
        detailEntry = detailEntry,
        actions = actions,
    )

    private fun enabled(): ActionDecision = ActionDecision(enabled = true)

    private fun disabled(reason: DisableReason): ActionDecision =
        ActionDecision(enabled = false, disableReason = reason)
}
