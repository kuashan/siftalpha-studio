package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePresentationStateTest {
    @Test
    fun webProcessRemainsStartingUntilEndpointIsReachable() {
        assertEquals(
            RuntimeState.STARTING,
            RuntimePresentationState.resolve(
                runtimeState = RuntimeState.RUNNING,
                webExpected = true,
                endpointReachable = false,
            ),
        )
    }

    @Test
    fun webProcessBecomesRunningAfterEndpointIsReachable() {
        assertEquals(
            RuntimeState.RUNNING,
            RuntimePresentationState.resolve(
                runtimeState = RuntimeState.RUNNING,
                webExpected = true,
                endpointReachable = true,
            ),
        )
    }

    @Test
    fun nonWebProcessKeepsRunningState() {
        assertEquals(
            RuntimeState.RUNNING,
            RuntimePresentationState.resolve(
                runtimeState = RuntimeState.RUNNING,
                webExpected = false,
                endpointReachable = false,
            ),
        )
    }

    @Test
    fun terminalStatesAreNeverRewrittenAsStarting() {
        assertEquals(
            RuntimeState.EXITED_ERROR,
            RuntimePresentationState.resolve(
                runtimeState = RuntimeState.EXITED_ERROR,
                webExpected = true,
                endpointReachable = false,
            ),
        )
    }
}
