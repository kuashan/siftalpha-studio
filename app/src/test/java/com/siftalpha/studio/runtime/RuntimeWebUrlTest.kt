package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeWebUrlTest {
    @Test
    fun extractsLatestExplicitLoopbackHttpUrl() {
        val output = """
            SIFTALPHA_WEB_URL=http://127.0.0.1:8765
            noise
            SIFTALPHA_WEB_URL=http://localhost:9000/dashboard
        """.trimIndent()

        assertEquals(
            "http://localhost:9000/dashboard",
            RuntimeWebUrl.extractLocalHttpUrl(output),
        )
    }

    @Test
    fun explicitProtocolWinsOverOrdinaryFrameworkLog() {
        val output = """
            SIFTALPHA_WEB_URL=http://127.0.0.1:8765
            Local: http://localhost:5173/
            Node fixture listening on 127.0.0.1:8000
        """.trimIndent()
        assertEquals("http://127.0.0.1:8765", RuntimeWebUrl.extractLocalHttpUrl(output))
    }

    @Test
    fun acceptsOrdinaryViteAndFastifyLoopbackLogs() {
        assertEquals(
            "http://localhost:5173/",
            RuntimeWebUrl.extractLocalHttpUrl("  ➜  Local:   http://localhost:5173/"),
        )
        assertEquals(
            "http://127.0.0.1:3000",
            RuntimeWebUrl.extractLocalHttpUrl("Server listening at http://127.0.0.1:3000"),
        )
    }

    @Test
    fun acceptsBareLoopbackEndpointFromRealDeviceNodeLog() {
        assertEquals(
            "http://127.0.0.1:8000",
            RuntimeWebUrl.extractLocalHttpUrl("Node fixture listening on 127.0.0.1:8000"),
        )
        assertEquals(
            "http://localhost:5173",
            RuntimeWebUrl.extractLocalHttpUrl("dev server ready at localhost:5173"),
        )
    }

    @Test
    fun infersLoopbackFromStrongListeningPortLog() {
        assertEquals(
            "http://127.0.0.1:3001",
            RuntimeWebUrl.extractLocalHttpUrl("Example app listening on port 3001!"),
        )
        assertEquals(
            "http://127.0.0.1:8080",
            RuntimeWebUrl.extractLocalHttpUrl("Server running on port: 8080"),
        )
    }

    @Test
    fun normalizesWildcardBindAddressForBrowser() {
        assertEquals(
            "http://127.0.0.1:8080/api",
            RuntimeWebUrl.extractLocalHttpUrl("listening on http://0.0.0.0:8080/api"),
        )
        assertEquals(
            "http://127.0.0.1:9090",
            RuntimeWebUrl.extractLocalHttpUrl("listening on http://[::]:9090"),
        )
        assertEquals(
            "http://127.0.0.1:7000",
            RuntimeWebUrl.extractLocalHttpUrl("listening on 0.0.0.0:7000"),
        )
    }

    @Test
    fun acceptsIpv6LoopbackAndNormalizesItSafely() {
        assertEquals(
            "http://[::1]:8765",
            RuntimeWebUrl.extractLocalHttpUrl("SIFTALPHA_WEB_URL=http://[::1]:8765"),
        )
        assertEquals(
            "http://[::1]:9000",
            RuntimeWebUrl.extractLocalHttpUrl("listening on [::1]:9000"),
        )
    }

    @Test
    fun rejectsExternalHostAndCustomScheme() {
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("SIFTALPHA_WEB_URL=https://example.com"))
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("SIFTALPHA_WEB_URL=javascript:alert(1)"))
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("SIFTALPHA_WEB_URL=content://something"))
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("ready at https://example.com:8443"))
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("server listening on example.com:443"))
    }

    @Test
    fun rejectsCredentialsAndInvalidPort() {
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("SIFTALPHA_WEB_URL=http://user:pass@127.0.0.1:8765"))
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("SIFTALPHA_WEB_URL=http://127.0.0.1:99999"))
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("Node fixture listening on 127.0.0.1:99999"))
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("Example app listening on port 99999"))
    }

    @Test
    fun invalidExplicitLineDoesNotBlockIndependentOrdinaryRuntimeLog() {
        val output = """
            SIFTALPHA_WEB_URL=http://user:pass@127.0.0.1:8765
            Node fixture listening on 127.0.0.1:8000
        """.trimIndent()

        assertEquals(
            "http://127.0.0.1:8000",
            RuntimeWebUrl.extractLocalHttpUrl(output),
        )
    }

    @Test
    fun doesNotInferPortFromUnrelatedNumbers() {
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("processed port records: 3000"))
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("retry in 3000 ms"))
    }

    @Test
    fun returnsNullWhenNoLocalUrlExists() {
        assertNull(RuntimeWebUrl.extractLocalHttpUrl("STATE=RUNNING"))
    }
}
