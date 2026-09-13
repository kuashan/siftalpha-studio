package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeWebUiStatusTest {

    @Test
    fun runningWithUnknownProbeStateIsDetecting() {
        assertEquals(
            RuntimeWebUiStatus.DETECTING,
            RuntimeWebUiStatus.resolve(
                profileEnabled = true,
                hasKnownRuntimeUrl = true,
                hasConfiguredLocalUrl = false,
                runtimeState = RuntimeState.RUNNING,
                endpointReachable = null,
            ),
        )
    }

    @Test
    fun runningWithVerifiedEndpointIsAvailable() {
        assertEquals(
            RuntimeWebUiStatus.AVAILABLE,
            RuntimeWebUiStatus.resolve(
                profileEnabled = true,
                hasKnownRuntimeUrl = true,
                hasConfiguredLocalUrl = false,
                runtimeState = RuntimeState.RUNNING,
                endpointReachable = true,
            ),
        )
    }

    @Test
    fun completedNegativeProbeIsExplainableUnavailableState() {
        assertEquals(
            RuntimeWebUiStatus.UNAVAILABLE,
            RuntimeWebUiStatus.resolve(
                profileEnabled = true,
                hasKnownRuntimeUrl = true,
                hasConfiguredLocalUrl = false,
                runtimeState = RuntimeState.RUNNING,
                endpointReachable = false,
            ),
        )
    }

    @Test
    fun runningWithoutUrlDoesNotRemainDetectingAfterNegativeResolution() {
        assertEquals(
            RuntimeWebUiStatus.UNAVAILABLE,
            RuntimeWebUiStatus.resolve(
                profileEnabled = true,
                hasKnownRuntimeUrl = false,
                hasConfiguredLocalUrl = false,
                runtimeState = RuntimeState.RUNNING,
                endpointReachable = false,
            ),
        )
    }

    @Test
    fun detectedWebProjectNotRunningIsWaiting() {
        assertEquals(
            RuntimeWebUiStatus.WAITING,
            RuntimeWebUiStatus.resolve(
                profileEnabled = true,
                hasKnownRuntimeUrl = false,
                hasConfiguredLocalUrl = false,
                runtimeState = RuntimeState.STOPPED_BY_USER,
                endpointReachable = null,
            ),
        )
    }

    @Test
    fun unknownProjectWithoutUrlUsesAutoDetect() {
        assertEquals(
            RuntimeWebUiStatus.AUTO_DETECT,
            RuntimeWebUiStatus.resolve(
                profileEnabled = false,
                hasKnownRuntimeUrl = false,
                hasConfiguredLocalUrl = false,
                runtimeState = RuntimeState.UNKNOWN,
                endpointReachable = null,
            ),
        )
    }

    @Test
    fun configuredUrlWithClosedEndpointIsUnavailable() {
        assertEquals(
            RuntimeWebUiStatus.UNAVAILABLE,
            RuntimeWebUiStatus.resolve(
                profileEnabled = true,
                hasKnownRuntimeUrl = false,
                hasConfiguredLocalUrl = true,
                runtimeState = RuntimeState.RUNNING,
                endpointReachable = false,
            ),
        )
    }
}
