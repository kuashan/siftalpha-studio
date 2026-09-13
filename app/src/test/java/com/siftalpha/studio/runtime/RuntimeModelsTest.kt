package com.siftalpha.studio.runtime

import com.siftalpha.studio.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateTest {

    @Test
    fun runningMarkerMapsToRunning() {
        assertEquals(
            RuntimeState.RUNNING,
            RuntimeState.fromOutput("SIFTALPHA_STATUS=RUNNING\nSTATE=RUNNING\n"),
        )
    }

    @Test
    fun stoppedByUserWinsOverOtherMarkers() {
        assertEquals(
            RuntimeState.STOPPED_BY_USER,
            RuntimeState.fromOutput(
                "SIFTALPHA_STATUS=RUNNING\nSTATE=STOPPED_BY_USER\n",
            ),
        )
    }

    @Test
    fun zeroExitCodeMapsToExitedSuccess() {
        assertEquals(
            RuntimeState.EXITED_SUCCESS,
            RuntimeState.fromOutput("STATE=EXITED\nEXIT_CODE=0\n"),
        )
    }

    @Test
    fun nonZeroExitCodeMapsToExitedError() {
        assertEquals(
            RuntimeState.EXITED_ERROR,
            RuntimeState.fromOutput("STATE=EXITED\nSIFTALPHA_PROCESS_EXIT=2\nEXIT_CODE=2\n"),
        )
    }

    @Test
    fun exitedSuccessWinsOverTrailingStoppedMarkerFromLogs() {
        assertEquals(
            RuntimeState.EXITED_SUCCESS,
            RuntimeState.fromOutput(
                "STATE=EXITED\nEXIT_CODE=0\nSIFTALPHA_RUNTIME_STATE=EXITED_SUCCESS\nSIFTALPHA_STATUS=STOPPED\n",
            ),
        )
    }

    @Test
    fun exitedErrorWinsOverTrailingStoppedMarkerFromLogs() {
        assertEquals(
            RuntimeState.EXITED_ERROR,
            RuntimeState.fromOutput(
                "STATE=EXITED\nEXIT_CODE=1\nSIFTALPHA_RUNTIME_STATE=EXITED_ERROR\nSIFTALPHA_STATUS=STOPPED\n",
            ),
        )
    }

    @Test
    fun runtimeErrorMarkerMapsToEnvironmentError() {
        assertEquals(
            RuntimeState.ENVIRONMENT_ERROR,
            RuntimeState.fromOutput("SIFTALPHA_ERROR=ENV_NOT_READY\n"),
        )
    }

    @Test
    fun startingWithoutFailureStaysStarting() {
        assertEquals(
            RuntimeState.STARTING,
            RuntimeState.fromOutput("STATE=STARTING\n"),
        )
    }

    @Test
    fun extractExitCodeUsesExplicitExitCode() {
        assertEquals(
            77,
            RuntimeState.extractExitCode(
                "SIFTALPHA_PROCESS_EXIT=2\nEXIT_CODE=77\n",
            ),
        )
    }

    @Test
    fun stateVariantsMapToDedicatedLocalizedResources() {
        assertEquals(R.string.runtime_state_running, RuntimeState.RUNNING.labelResource())
        assertEquals(R.string.runtime_state_exited_success, RuntimeState.EXITED_SUCCESS.labelResource())
        assertEquals(R.string.runtime_state_exited_error, RuntimeState.EXITED_ERROR.labelResource())
        assertEquals(R.string.runtime_state_stopped_by_user, RuntimeState.STOPPED_BY_USER.labelResource())
    }
}

class RuntimeDiagnosticTest {

    @Test
    fun completeProbeIsParsedIntoStructuredDiagnostic() {
        val diagnostic = RuntimeDiagnostic.fromProbe(
            output = """
                === SiftAlpha Runtime Probe ===
                ARCH=aarch64
                TERMUX=OK
                PROOT_DISTRO=OK
                UBUNTU_LOGIN=OK
                PYTHON=Python 3.12.3
                PIP=pip 24.0
                VENV=OK
                TMUX=tmux 3.4
                LIBC=ldd (Ubuntu GLIBC 2.39-0ubuntu8) 2.39
            """.trimIndent(),
            runCommandPermission = true,
        )

        assertEquals("aarch64", diagnostic.architecture)
        assertTrue(diagnostic.termux)
        assertTrue(diagnostic.runCommandPermission)
        assertTrue(diagnostic.proot)
        assertTrue(diagnostic.ubuntu)
        assertEquals("Python 3.12.3", diagnostic.python)
        assertEquals("pip 24.0", diagnostic.pip)
        assertTrue(diagnostic.venv)
        assertTrue(diagnostic.tmux)
        assertTrue(diagnostic.libc)
    }

    @Test
    fun missingProbeValuesRemainMissing() {
        val diagnostic = RuntimeDiagnostic.fromProbe(
            output = """
                ARCH=
                TERMUX=OK
                PROOT_DISTRO=MISSING
                UBUNTU=MISSING
                PYTHON=MISSING
                PIP=MISSING
                VENV=MISSING
                TMUX=MISSING
                LIBC=MISSING
            """.trimIndent(),
            runCommandPermission = false,
        )

        assertEquals("unknown", diagnostic.architecture)
        assertTrue(diagnostic.termux)
        assertFalse(diagnostic.runCommandPermission)
        assertFalse(diagnostic.proot)
        assertFalse(diagnostic.ubuntu)
        assertNull(diagnostic.python)
        assertNull(diagnostic.pip)
        assertFalse(diagnostic.venv)
        assertFalse(diagnostic.tmux)
        assertFalse(diagnostic.libc)
    }
}
