package com.siftalpha.studio.presentation

import com.siftalpha.studio.runtime.RuntimeKind
import com.siftalpha.studio.runtime.RuntimeState
import com.siftalpha.studio.runtime.RuntimeWebUiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectActionPolicyTest {

    @Test
    fun `web process stays starting until endpoint is verified`() {
        val snapshot = snapshot(
            lifecycle = RuntimeState.RUNNING,
            web = ProjectUiSnapshot.Web(
                expected = true,
                status = RuntimeWebUiStatus.UNAVAILABLE,
                endpointReachable = false,
                reachableUrl = null,
            ),
            stopCapability = true,
        )

        val policy = ProjectActionPolicy.resolve(snapshot)

        assertEquals(RuntimeState.STARTING, snapshot.displayedLifecycle)
        assertEquals(ProjectActionPolicy.MessageKey.WEB_ENDPOINT_PENDING, policy.summary)
        assertEquals(ProjectActionPolicy.Action.STOP, policy.primaryAction)
        assertFalse(policy.isEnabled(ProjectActionPolicy.Action.OPEN_BROWSER))
    }

    @Test
    fun `non web process remains running`() {
        val snapshot = snapshot(
            lifecycle = RuntimeState.RUNNING,
            web = ProjectUiSnapshot.Web(
                expected = false,
                status = RuntimeWebUiStatus.AUTO_DETECT,
                endpointReachable = null,
            ),
            stopCapability = true,
        )

        assertEquals(RuntimeState.RUNNING, snapshot.displayedLifecycle)
        assertEquals(ProjectActionPolicy.MessageKey.RUNTIME_ACTIVE, ProjectActionPolicy.resolve(snapshot).summary)
    }

    @Test
    fun `terminal state is not overwritten by stale web evidence`() {
        listOf(
            RuntimeState.EXITED_SUCCESS,
            RuntimeState.EXITED_ERROR,
            RuntimeState.STOPPED_BY_USER,
        ).forEach { terminalState ->
            val snapshot = snapshot(
                lifecycle = terminalState,
                web = ProjectUiSnapshot.Web(
                    expected = true,
                    status = RuntimeWebUiStatus.AVAILABLE,
                    endpointReachable = true,
                    reachableUrl = "http://127.0.0.1:8080",
                ),
            )

            assertEquals(terminalState, snapshot.displayedLifecycle)
            assertEquals(ProjectActionPolicy.Action.START, ProjectActionPolicy.resolve(snapshot).primaryAction)
        }
    }

    @Test
    fun `prepared environment allows the first run before lifecycle status is known`() {
        val policy = ProjectActionPolicy.resolve(
            snapshot(
                lifecycle = RuntimeState.UNKNOWN,
                environment = ProjectUiSnapshot.Environment(ProjectUiSnapshot.Readiness.READY),
            ),
        )

        assertEquals(ProjectActionPolicy.Action.START, policy.primaryAction)
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.START))
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.CONFIGURE))
    }

    @Test
    fun `configuration stays available before environment preparation`() {
        val policy = ProjectActionPolicy.resolve(
            snapshot(
                lifecycle = RuntimeState.UNKNOWN,
                environment = ProjectUiSnapshot.Environment(ProjectUiSnapshot.Readiness.NOT_READY),
            ),
        )

        assertEquals(ProjectActionPolicy.Action.PREPARE, policy.primaryAction)
        assertFalse(policy.isEnabled(ProjectActionPolicy.Action.START))
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.CONFIGURE))
    }

    @Test
    fun `configuration stays available when runtime selection is unresolved`() {
        val policy = ProjectActionPolicy.resolve(
            snapshot(
                runtime = ProjectUiSnapshot.Runtime(
                    selection = ProjectUiSnapshot.Runtime.Selection(
                        status = ProjectUiSnapshot.Runtime.SelectionStatus.AMBIGUOUS,
                        candidates = listOf(RuntimeKind.PYTHON, RuntimeKind.NODE_JS),
                    ),
                    supported = true,
                ),
                environment = ProjectUiSnapshot.Environment(ProjectUiSnapshot.Readiness.NOT_READY),
            ),
        )

        assertFalse(policy.isEnabled(ProjectActionPolicy.Action.START))
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.CONFIGURE))
    }

    @Test
    fun `configuration stays available when runtime host is unavailable`() {
        val policy = ProjectActionPolicy.resolve(
            snapshot(
                runtime = ProjectUiSnapshot.Runtime(
                    selection = ProjectUiSnapshot.Runtime.Selection(
                        status = ProjectUiSnapshot.Runtime.SelectionStatus.RESOLVED,
                        primary = RuntimeKind.PYTHON,
                    ),
                    supported = false,
                ),
            ),
        )

        assertFalse(policy.isEnabled(ProjectActionPolicy.Action.START))
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.CONFIGURE))
    }

    @Test
    fun `static missing configuration does not block the first run`() {
        val policy = ProjectActionPolicy.resolve(
            snapshot(
                lifecycle = RuntimeState.UNKNOWN,
                environment = ProjectUiSnapshot.Environment(ProjectUiSnapshot.Readiness.READY),
                configuration = ProjectUiSnapshot.Configuration(
                    requiredCount = 2,
                    configuredRequiredCount = 1,
                    missingRequiredNames = listOf("SERVICE_TOKEN"),
                ),
            ),
        )

        assertEquals(ProjectActionPolicy.Action.START, policy.primaryAction)
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.START))
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.CONFIGURE))
        assertEquals(ProjectActionPolicy.DetailEntry.RUNTIME, policy.detailEntry)
    }

    @Test
    fun `optional credential candidates do not block start`() {
        val policy = ProjectActionPolicy.resolve(
            snapshot(
                lifecycle = RuntimeState.STOPPED_BY_USER,
                environment = ProjectUiSnapshot.Environment(ProjectUiSnapshot.Readiness.READY),
                configuration = ProjectUiSnapshot.Configuration(
                    requiredCount = 0,
                    configuredRequiredCount = 0,
                    credentialCandidateCount = 2,
                ),
            ),
        )

        assertEquals(ProjectActionPolicy.Action.START, policy.primaryAction)
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.START))
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.CONFIGURE))
        assertTrue(snapshot(
            lifecycle = RuntimeState.STOPPED_BY_USER,
            environment = ProjectUiSnapshot.Environment(ProjectUiSnapshot.Readiness.READY),
            configuration = ProjectUiSnapshot.Configuration(0, 0, credentialCandidateCount = 2),
        ).configuration.hasOnlyOptionalCandidates)
    }

    @Test
    fun runtimeDiscoveryRequiresConfigurationBeforeRetry() {
        val policy = ProjectActionPolicy.resolve(
            snapshot(
                lifecycle = RuntimeState.EXITED_ERROR,
                environment = ProjectUiSnapshot.Environment(ProjectUiSnapshot.Readiness.READY),
                configuration = ProjectUiSnapshot.Configuration(
                    requiredCount = 1,
                    configuredRequiredCount = 0,
                    missingRequiredNames = listOf("SERVICE_TOKEN"),
                    runtimeConfigurationDiscovered = true,
                ),
            ),
        )

        assertEquals(ProjectActionPolicy.Action.CONFIGURE, policy.primaryAction)
        assertEquals(ProjectActionPolicy.Action.STATUS, policy.directSecondaryAction)
        assertFalse(policy.isEnabled(ProjectActionPolicy.Action.START))
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.CONFIGURE))
        assertEquals(
            ProjectActionPolicy.DisableReason.REQUIRED_CONFIGURATION_MISSING,
            policy.reasonFor(ProjectActionPolicy.Action.START),
        )
    }

    @Test
    fun recoveryBlocksDuplicateRuntimeSideEffects() {
        val policy = ProjectActionPolicy.resolve(
            snapshot(
                lifecycle = RuntimeState.RUNNING,
                stopCapability = true,
                recoveryInProgress = true,
            ),
        )

        assertEquals(ProjectActionPolicy.MessageKey.RUNTIME_RECOVERING, policy.summary)
        assertEquals(null, policy.primaryAction)
        assertFalse(policy.isEnabled(ProjectActionPolicy.Action.START))
        assertEquals(
            ProjectActionPolicy.DisableReason.RUNTIME_RECOVERY,
            policy.reasonFor(ProjectActionPolicy.Action.STATUS),
        )
    }

    @Test
    fun `stop remains available when source selection becomes ambiguous`() {
        val policy = ProjectActionPolicy.resolve(
            snapshot(
                lifecycle = RuntimeState.UNKNOWN,
                runtime = ProjectUiSnapshot.Runtime(
                    selection = ProjectUiSnapshot.Runtime.Selection(
                        status = ProjectUiSnapshot.Runtime.SelectionStatus.AMBIGUOUS,
                        candidates = listOf(RuntimeKind.NODE_JS, RuntimeKind.PYTHON),
                    ),
                    supported = true,
                    stopCapability = true,
                ),
                environment = ProjectUiSnapshot.Environment(ProjectUiSnapshot.Readiness.NOT_READY),
                configuration = ProjectUiSnapshot.Configuration(1, 0, listOf("TOKEN")),
            ),
        )

        assertEquals(ProjectActionPolicy.Action.STOP, policy.primaryAction)
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.STOP))
        assertTrue(policy.isEnabled(ProjectActionPolicy.Action.CONFIGURE))
        assertEquals(ProjectActionPolicy.DisableReason.PROCESS_ACTIVE, policy.reasonFor(ProjectActionPolicy.Action.START))
    }

    @Test
    fun `pending operation blocks all duplicate runtime side effects`() {
        val policy = ProjectActionPolicy.resolve(
            snapshot(
                lifecycle = RuntimeState.STARTING,
                stopCapability = true,
                pending = ProjectUiSnapshot.PendingOperation(ProjectUiSnapshot.Operation.START, 44),
            ),
        )

        assertEquals(ProjectActionPolicy.MessageKey.PENDING_OPERATION, policy.summary)
        assertEquals(null, policy.primaryAction)
        listOf(
            ProjectActionPolicy.Action.PREPARE,
            ProjectActionPolicy.Action.START,
            ProjectActionPolicy.Action.STOP,
            ProjectActionPolicy.Action.STATUS,
            ProjectActionPolicy.Action.LOGS,
            ProjectActionPolicy.Action.CLEAN,
            ProjectActionPolicy.Action.CONFIGURE,
        ).forEach { action ->
            assertFalse("$action should be disabled", policy.isEnabled(action))
            assertEquals(ProjectActionPolicy.DisableReason.PENDING_OPERATION, policy.reasonFor(action))
        }
    }

    @Test
    fun `every policy result has a complete and self-consistent action matrix`() {
        val policy = ProjectActionPolicy.resolve(snapshot())

        assertEquals(ProjectActionPolicy.Action.entries.toSet(), policy.actions.keys)
        assertEquals(policy.summary.resourceKey, policy.explanationResourceKey)
        policy.primaryAction?.let { assertTrue(policy.isEnabled(it)) }
        policy.directSecondaryAction?.let { assertTrue(policy.isEnabled(it)) }
        policy.actions.values.forEach { decision ->
            assertEquals(decision.enabled, decision.disableReason == null)
        }
    }

    @Test
    fun `document identity is independent from display name`() {
        val first = snapshot(identity = ProjectUiSnapshot.Identity("doc-1", "same-name", "same-name"))
        val second = snapshot(identity = ProjectUiSnapshot.Identity("doc-2", "same-name", "same-name"))

        assertNotEquals(first.identity.stableKey, second.identity.stableKey)
        assertNotEquals(first.identity, second.identity)
    }

    private fun snapshot(
        identity: ProjectUiSnapshot.Identity = ProjectUiSnapshot.Identity("doc-1", "project", "Project"),
        runtime: ProjectUiSnapshot.Runtime = ProjectUiSnapshot.Runtime(
            selection = ProjectUiSnapshot.Runtime.Selection(
                status = ProjectUiSnapshot.Runtime.SelectionStatus.RESOLVED,
                primary = RuntimeKind.PYTHON,
            ),
            supported = true,
        ),
        environment: ProjectUiSnapshot.Environment = ProjectUiSnapshot.Environment(ProjectUiSnapshot.Readiness.READY),
        configuration: ProjectUiSnapshot.Configuration = ProjectUiSnapshot.Configuration(0, 0),
        lifecycle: RuntimeState = RuntimeState.STOPPED_BY_USER,
        web: ProjectUiSnapshot.Web = ProjectUiSnapshot.Web(
            expected = false,
            status = RuntimeWebUiStatus.AUTO_DETECT,
            endpointReachable = null,
        ),
        pending: ProjectUiSnapshot.PendingOperation? = null,
        stopCapability: Boolean = runtime.stopCapability,
        recoveryInProgress: Boolean = false,
        evidence: ProjectUiSnapshot.Evidence = ProjectUiSnapshot.Evidence(
            lifecycle = if (lifecycle == RuntimeState.UNKNOWN) {
                ProjectUiSnapshot.LifecycleEvidence.NONE
            } else {
                ProjectUiSnapshot.LifecycleEvidence.CACHED
            },
            environment = when (environment.readiness) {
                ProjectUiSnapshot.Readiness.UNKNOWN -> ProjectUiSnapshot.EnvironmentEvidence.NONE
                else -> ProjectUiSnapshot.EnvironmentEvidence.CACHED
            },
        ),
    ): ProjectUiSnapshot = ProjectUiSnapshot(
        identity = identity,
        runtime = if (stopCapability == runtime.stopCapability) runtime else runtime.copy(stopCapability = stopCapability),
        environment = environment,
        configuration = configuration,
        lifecycle = lifecycle,
        web = web,
        pending = pending,
        evidence = evidence,
        recoveryInProgress = recoveryInProgress,
    )
}
