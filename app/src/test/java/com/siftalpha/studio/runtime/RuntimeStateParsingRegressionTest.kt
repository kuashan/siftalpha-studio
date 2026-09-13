package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeStateParsingRegressionTest {

    @Test
    fun `current exited guest state wins over stale running evidence`() {
        val output = """
            SIFTALPHA_NODE_ENV=READY
            SIFTALPHA_ENV=READY
            SIFTALPHA_HOST_PID=17009
            SIFTALPHA_STATUS=RUNNING
            STATE=EXITED
            EXIT_CODE=1
            SIFTALPHA_LOG_BYTES=2400
            SIFTALPHA_WEB_DISCOVERY_SOURCE=RUNTIME_LOG
            SIFTALPHA_WEB_URL=http://127.0.0.1:8000
            === SiftAlpha Studio Runtime ===
            COMMAND=npm start
            SIFTALPHA_PROCESS_EXIT=1
            EXITED_AT=2026-09-12 17:17:40
        """.trimIndent()

        assertEquals(RuntimeState.EXITED_ERROR, RuntimeState.fromOutput(output))
    }

    @Test
    fun `current running guest state wins over historical exited log text`() {
        val output = """
            SIFTALPHA_STATUS=RUNNING
            STATE=RUNNING
            SIFTALPHA_LOG_BYTES=1024
            previous diagnostic text
            STATE=EXITED
            EXIT_CODE=7
        """.trimIndent()

        assertEquals(RuntimeState.RUNNING, RuntimeState.fromOutput(output))
    }

    @Test
    fun `explicit reconciled runtime state is authoritative`() {
        val output = """
            SIFTALPHA_STATUS=RUNNING
            STATE=RUNNING
            SIFTALPHA_RUNTIME_STATE=EXITED_ERROR
        """.trimIndent()

        assertEquals(RuntimeState.EXITED_ERROR, RuntimeState.fromOutput(output))
    }

    @Test
    fun `zero exit code maps current exited state to success`() {
        val output = """
            SIFTALPHA_STATUS=RUNNING
            STATE=EXITED
            EXIT_CODE=0
        """.trimIndent()

        assertEquals(RuntimeState.EXITED_SUCCESS, RuntimeState.fromOutput(output))
    }
}
