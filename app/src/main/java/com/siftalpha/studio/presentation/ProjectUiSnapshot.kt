package com.siftalpha.studio.presentation

import com.siftalpha.studio.runtime.ProjectRuntimeExecutionPlanner
import com.siftalpha.studio.runtime.RuntimeKind
import com.siftalpha.studio.runtime.RuntimePresentationState
import com.siftalpha.studio.runtime.RuntimeState
import com.siftalpha.studio.runtime.RuntimeWebUiStatus

/**
 * One immutable, project-scoped input for Runtime Center presentation policy.
 *
 * This model deliberately contains facts rather than Android Views or localized strings. The
 * Activity may still own dialogs and side effects, but it cannot derive a different lifecycle or
 * action decision from the same project facts in each button callback.
 */
data class ProjectUiSnapshot(
    val identity: Identity,
    val runtime: Runtime,
    val environment: Environment,
    val configuration: Configuration,
    /** The last typed lifecycle state known to the UI/result reconciler. */
    val lifecycle: RuntimeState,
    val web: Web,
    val pending: PendingOperation? = null,
    val evidence: Evidence = Evidence(),
) {

    /**
     * Web readiness is a presentation overlay only. Terminal Runtime states remain terminal even
     * when a stale URL or a late Web probe is present.
     */
    val displayedLifecycle: RuntimeState
        get() = RuntimePresentationState.resolve(
            runtimeState = lifecycle,
            webExpected = web.expected,
            endpointReachable = web.endpointReachable == true,
        )

    val processMayBeActive: Boolean
        get() = runtime.stopCapability || (
            lifecycle in ACTIVE_STATES && evidence.lifecycle != LifecycleEvidence.NONE
        )

    data class Identity(
        /** SAF document ID is the stable UI identity; it must not be replaced by a list index. */
        val documentId: String,
        /** Runtime commands still need the current directory display name. */
        val folderName: String,
        val displayName: String,
        val sourceUrl: String? = null,
    ) {
        init {
            require(documentId.isNotBlank()) { "documentId must not be blank" }
            require(folderName.isNotBlank()) { "folderName must not be blank" }
            require(displayName.isNotBlank()) { "displayName must not be blank" }
        }

        val stableKey: String
            get() = documentId
    }

    data class Runtime(
        val selection: Selection,
        /** Host capability, distinct from whether project evidence resolved to a Runtime. */
        val supported: Boolean,
        /** Process ownership can remain actionable after source/config detection becomes stale. */
        val stopCapability: Boolean = false,
        val supportReasonKey: String? = null,
    ) {
        data class Selection(
            val status: SelectionStatus,
            val primary: RuntimeKind? = null,
            val supplemental: List<RuntimeKind> = emptyList(),
            val candidates: List<RuntimeKind> = emptyList(),
            val source: SelectionSource = SelectionSource.NONE,
        ) {
            init {
                require((status == SelectionStatus.RESOLVED) == (primary != null)) {
                    "resolved selection needs a primary Runtime and unresolved selection cannot expose one"
                }
            }

            val isResolved: Boolean
                get() = status == SelectionStatus.RESOLVED && primary != null
        }

        enum class SelectionStatus {
            RESOLVED,
            AMBIGUOUS,
            UNSUPPORTED,
        }

        enum class SelectionSource {
            DECLARED_METADATA,
            ROOT_EVIDENCE,
            SINGLE_RUNTIME_EVIDENCE,
            NONE,
        }

        companion object {
            fun fromPlannerSelection(
                selection: ProjectRuntimeExecutionPlanner.Selection,
                supported: Boolean,
                stopCapability: Boolean = false,
                supportReasonKey: String? = null,
            ): Runtime {
                val modelSelection = when (selection) {
                    is ProjectRuntimeExecutionPlanner.Selection.Resolved -> Selection(
                        status = SelectionStatus.RESOLVED,
                        primary = selection.primary,
                        supplemental = selection.supplemental,
                        candidates = listOf(selection.primary) + selection.supplemental,
                        source = when (selection.source) {
                            ProjectRuntimeExecutionPlanner.SelectionSource.DECLARED_METADATA ->
                                SelectionSource.DECLARED_METADATA
                            ProjectRuntimeExecutionPlanner.SelectionSource.ROOT_EVIDENCE ->
                                SelectionSource.ROOT_EVIDENCE
                            ProjectRuntimeExecutionPlanner.SelectionSource.SINGLE_RUNTIME_EVIDENCE ->
                                SelectionSource.SINGLE_RUNTIME_EVIDENCE
                        },
                    )
                    is ProjectRuntimeExecutionPlanner.Selection.Ambiguous -> Selection(
                        status = SelectionStatus.AMBIGUOUS,
                        candidates = selection.candidates,
                    )
                    is ProjectRuntimeExecutionPlanner.Selection.Unsupported -> Selection(
                        status = SelectionStatus.UNSUPPORTED,
                    )
                }
                return Runtime(
                    selection = modelSelection,
                    supported = supported,
                    stopCapability = stopCapability,
                    supportReasonKey = supportReasonKey,
                )
            }
        }
    }

    data class Environment(
        val readiness: Readiness,
        val reasonKey: String? = null,
    ) {
        companion object {
            fun from(value: Boolean?, reasonKey: String? = null): Environment = Environment(
                readiness = when (value) {
                    true -> Readiness.READY
                    false -> Readiness.NOT_READY
                    null -> Readiness.UNKNOWN
                },
                reasonKey = reasonKey,
            )
        }
    }

    enum class Readiness {
        UNKNOWN,
        READY,
        NOT_READY,
    }

    data class Configuration(
        val requiredCount: Int,
        val configuredRequiredCount: Int,
        val missingRequiredNames: List<String> = emptyList(),
        val credentialCandidateCount: Int = 0,
        /** True only after a running project has reported an actionable configuration finding. */
        val runtimeConfigurationDiscovered: Boolean = false,
    ) {
        init {
            require(requiredCount >= 0) { "requiredCount must not be negative" }
            require(configuredRequiredCount >= 0) { "configuredRequiredCount must not be negative" }
            require(configuredRequiredCount <= requiredCount) {
                "configuredRequiredCount must not exceed requiredCount"
            }
            require(credentialCandidateCount >= 0) { "credentialCandidateCount must not be negative" }
        }

        val missingRequiredCount: Int
            get() = maxOf(
                missingRequiredNames.size,
                (requiredCount - configuredRequiredCount).coerceAtLeast(0),
            )

        val ready: Boolean
            get() = missingRequiredCount == 0

        val hasOnlyOptionalCandidates: Boolean
            get() = ready && credentialCandidateCount > 0
    }

    data class Web(
        val expected: Boolean,
        val status: RuntimeWebUiStatus,
        val endpointReachable: Boolean?,
        val reachableUrl: String? = null,
        val framework: String? = null,
    ) {
        companion object {
            fun resolve(
                profileEnabled: Boolean,
                hasKnownRuntimeUrl: Boolean,
                hasConfiguredLocalUrl: Boolean,
                runtimeState: RuntimeState,
                endpointReachable: Boolean?,
                reachableUrl: String? = null,
                framework: String? = null,
            ): Web {
                val expected = profileEnabled || hasKnownRuntimeUrl || hasConfiguredLocalUrl
                return Web(
                    expected = expected,
                    status = RuntimeWebUiStatus.resolve(
                        profileEnabled = profileEnabled,
                        hasKnownRuntimeUrl = hasKnownRuntimeUrl,
                        hasConfiguredLocalUrl = hasConfiguredLocalUrl,
                        runtimeState = runtimeState,
                        endpointReachable = endpointReachable,
                    ),
                    endpointReachable = endpointReachable,
                    reachableUrl = reachableUrl,
                    framework = framework,
                )
            }
        }
    }

    data class PendingOperation(
        val operation: Operation,
        val executionId: Int? = null,
    )

    enum class Operation {
        PREPARE,
        START,
        STOP,
        STATUS,
        LOGS,
        CLEAN,
    }

    data class Evidence(
        val lifecycle: LifecycleEvidence = LifecycleEvidence.NONE,
        val environment: EnvironmentEvidence = EnvironmentEvidence.NONE,
        val web: WebEvidence = WebEvidence.NONE,
    )

    enum class LifecycleEvidence {
        NONE,
        CACHED,
        RUNTIME_RESULT,
    }

    enum class EnvironmentEvidence {
        NONE,
        CACHED,
        RUNTIME_RESULT,
    }

    enum class WebEvidence {
        NONE,
        PROBE_IN_FLIGHT,
        VERIFIED,
        UNREACHABLE,
    }

    companion object {
        private val ACTIVE_STATES = setOf(
            RuntimeState.PREPARING,
            RuntimeState.STARTING,
            RuntimeState.RUNNING,
        )
    }
}
