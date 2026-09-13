package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeResultLifecycleNormalizerTest {

    @Test
    fun `exited workload replaces stale running status and restores child exit code`() {
        val raw = """
            SIFTALPHA_STATUS=RUNNING
            SIFTALPHA_HOST_PID=17009
            STATE=EXITED
            EXIT_CODE=7
            SIFTALPHA_LOG_BYTES=2400
            SIFTALPHA_PROCESS_EXIT=7
        """.trimIndent()

        val result = RuntimeResultLifecycleNormalizer.reconcile(raw, rawExitCode = 0)

        assertEquals(RuntimeState.EXITED_ERROR, result.runtimeState)
        assertEquals(7, result.exitCode)
        assertTrue("SIFTALPHA_STATUS=EXITED_ERROR" in result.stdout)
        assertTrue("SIFTALPHA_RUNTIME_STATE=EXITED_ERROR" in result.stdout)
        assertFalse("SIFTALPHA_STATUS=RUNNING" in result.stdout)
    }

    @Test
    fun `successful one shot replaces stale running with stopped`() {
        val raw = """
            SIFTALPHA_STATUS=RUNNING
            STATE=EXITED
            EXIT_CODE=0
            SIFTALPHA_PROCESS_EXIT=0
        """.trimIndent()

        val result = RuntimeResultLifecycleNormalizer.reconcile(raw, rawExitCode = 0)

        assertEquals(RuntimeState.EXITED_SUCCESS, result.runtimeState)
        assertEquals(0, result.exitCode)
        assertTrue("SIFTALPHA_STATUS=STOPPED" in result.stdout)
        assertTrue("SIFTALPHA_RUNTIME_STATE=EXITED_SUCCESS" in result.stdout)
        assertFalse("SIFTALPHA_STATUS=RUNNING" in result.stdout)
    }

    @Test
    fun `stopped by user keeps successful stop transport exit code`() {
        val raw = """
            SIFTALPHA_STATUS=STOPPED_BY_USER
            SIFTALPHA_RUNTIME_STATE=STOPPED_BY_USER
            STATE=STOPPED_BY_USER
            EXIT_CODE=143
        """.trimIndent()

        val result = RuntimeResultLifecycleNormalizer.reconcile(raw, rawExitCode = 0)

        assertEquals(RuntimeState.STOPPED_BY_USER, result.runtimeState)
        assertEquals(0, result.exitCode)
        assertTrue("SIFTALPHA_STATUS=STOPPED_BY_USER" in result.stdout)
        assertTrue("SIFTALPHA_RUNTIME_STATE=STOPPED_BY_USER" in result.stdout)
    }

    @Test
    fun `running workload remains running`() {
        val raw = """
            SIFTALPHA_STATUS=RUNNING
            STATE=RUNNING
        """.trimIndent()

        val result = RuntimeResultLifecycleNormalizer.reconcile(raw, rawExitCode = 0)

        assertEquals(RuntimeState.RUNNING, result.runtimeState)
        assertEquals(0, result.exitCode)
        assertTrue("SIFTALPHA_STATUS=RUNNING" in result.stdout)
        assertTrue("SIFTALPHA_RUNTIME_STATE=RUNNING" in result.stdout)
    }
}
