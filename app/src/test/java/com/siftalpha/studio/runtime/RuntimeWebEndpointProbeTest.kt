package com.siftalpha.studio.runtime

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWebEndpointProbeTest {

    @Test
    fun explicitLoopbackPortIsPreserved() {
        assertEquals(
            listOf(RuntimeWebEndpointProbe.Target("127.0.0.1", 8000)),
            RuntimeWebEndpointProbe.targets("http://127.0.0.1:8000"),
        )
    }

    @Test
    fun localhostChecksIpv4ThenIpv6() {
        assertEquals(
            listOf(
                RuntimeWebEndpointProbe.Target("127.0.0.1", 8501),
                RuntimeWebEndpointProbe.Target("::1", 8501),
            ),
            RuntimeWebEndpointProbe.targets("http://localhost:8501"),
        )
    }

    @Test
    fun defaultPortsFollowScheme() {
        assertEquals(
            80,
            RuntimeWebEndpointProbe.targets("http://127.0.0.1").single().port,
        )
        assertEquals(
            443,
            RuntimeWebEndpointProbe.targets("https://127.0.0.1").single().port,
        )
    }

    @Test
    fun externalHostsAreRejectedByExistingUrlPolicy() {
        assertTrue(RuntimeWebEndpointProbe.targets("https://example.com:8000").isEmpty())
    }

    @Test
    fun invalidPortIsRejected() {
        assertTrue(RuntimeWebEndpointProbe.targets("http://127.0.0.1:0").isEmpty())
    }

    @Test
    fun liveIpv4LoopbackListenerIsActuallyReachable() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val url = "http://127.0.0.1:${server.localPort}"
            assertTrue(RuntimeWebEndpointProbe.isListening(url, timeoutMs = 1_000))
        }
    }
}
