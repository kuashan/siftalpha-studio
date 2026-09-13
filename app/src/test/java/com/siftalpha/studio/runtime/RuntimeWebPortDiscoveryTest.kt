package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWebPortDiscoveryTest {

    @Test
    fun preferredFrameworkPortsAreRankedBeforeArbitraryPorts() {
        assertEquals(
            listOf(5173, 3000, 8000, 8501, 7860, 5000, 12345, 49152),
            RuntimeWebPortDiscovery.rankCandidates(
                listOf(49152, 5000, 12345, 8000, 7860, 8501, 3000, 5173, 8000),
            ),
        )
    }

    @Test
    fun invalidPortsAreDiscarded() {
        assertEquals(
            listOf(8080, 65535),
            RuntimeWebPortDiscovery.rankCandidates(listOf(-1, 0, 65536, 65535, 8080)),
        )
    }

    @Test
    fun shellProbeKeepsHostFastPathAndAddsProjectScopedProotFallback() {
        val script = RuntimeWebPortDiscovery.shellSnippet()

        assertTrue("PID descendants must scope host discovery", "siftalpha_descendants" in script)
        assertTrue("PGID members must be considered", "ps -eo pid=,pgid=" in script)
        assertTrue("TCP listeners should remain available as the fast path", "/proc/net/tcp" in script)
        assertTrue("Only LISTEN state should be selected", "== \"0A\"" in script)
        assertTrue("HTTP verification must be tightly bounded", "timeout 1" in script)
        assertTrue("runtime candidate probing must be capped", "SIFTALPHA_WEB_RUNTIME_CANDIDATE_LIMIT=REACHED" in script)
        assertTrue("PASS marker missing", "SIFTALPHA_WEB_AUTODISCOVERY=PASS" in script)
        assertTrue("URL marker missing", "SIFTALPHA_WEB_URL=http://127.0.0.1:" in script)
        assertTrue("PRoot fallback must run from Ubuntu guest", "proot-distro login" in script)
        assertTrue("PRoot fallback must remain project PID scoped", "PROOT_PROJECT_PID_SCOPE" in script)
        assertTrue("guest descendants must not scan unrelated processes", "/task/" in script && "/children" in script)
        assertTrue("terminal no-listen result should be explicit", "NO_LISTEN_PORT source=PROJECT_AND_PROOT_PID_SCOPE" in script)

        assertFalse("discovery must not start an active all-port scan", "SIFTALPHA_WEB_ACTIVE_SCAN=START" in script)
        assertFalse("discovery must not embed a Python TCP scanner", "base64.b64decode" in script)
        assertFalse("discovery must not invoke the old active scan source", "ACTIVE_LOOPBACK_SCAN" in script)
        assertFalse("discovery must not use the unsafe Termux UID fallback", "TERMUX_UID_UNIQUE_HTTP" in script)
    }
}
