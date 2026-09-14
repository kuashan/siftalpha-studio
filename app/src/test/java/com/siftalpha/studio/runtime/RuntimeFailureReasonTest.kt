package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFailureReasonTest {
    @Test
    fun prefersStableErrorMarker() {
        assertEquals(
            "ENV_NOT_READY",
            RuntimeFailureReason.summarize(
                exitCode = 1,
                internalErrorMessage = "",
                stdout = "SIFTALPHA_ERROR=ENV_NOT_READY\nsecret-value",
                stderr = "",
            ),
        )
    }

    @Test
    fun boundsFallbackReason() {
        val reason = RuntimeFailureReason.summarize(
            exitCode = 2,
            internalErrorMessage = "",
            stdout = "",
            stderr = "x".repeat(800),
        )
        assertTrue(reason!!.length <= 240)
        assertTrue(reason.startsWith("exitCode=2:"))
    }
}
