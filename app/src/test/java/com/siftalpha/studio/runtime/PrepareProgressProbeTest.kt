package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepareProgressProbeTest {
    private class FakeHost : RuntimeCommandHost {
        override fun runtimeSupported(): Boolean = true
        override fun runtimeUnsupportedReason(): String = "unsupported"
        override fun sharedRoot(): String = "/storage/emulated/0/AcodeProjects"
        override fun runtimeId(folderName: String): String = "runtime-id"
        override fun sh(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
        override fun wrapUbuntu(inner: String): String = "HOST_WRAP_BEGIN\n$inner\nHOST_WRAP_END"
        override fun hostPreamble(): String = "HOST_PREAMBLE"
        override fun hostProcessHelpers(): String = "HOST_PROCESS_HELPERS"
    }

    private val project = RuntimeProjectSpec(
        name = "Sample",
        folderName = "sample-project",
        entry = "main.py",
        run = "python main.py",
    )

    @Test
    fun `probe is read only and tails accepted prepare log`() {
        val command = PrepareProgressProbe.command(project, FakeHost())
        val script = command.shellScript

        assertTrue(script.contains("/root/siftalpha/logs/prepare-runtime-id.log"))
        assertTrue(script.contains("SIFTALPHA_PREPARE_PROGRESS=ACTIVE"))
        assertTrue(script.contains("SIFTALPHA_PREPARE_STAGE"))
        assertTrue(script.contains("SIFTALPHA_PREPARE_LOG_BYTES"))
        assertTrue(script.contains("tail -n 45"))
        assertTrue(script.contains("requirements.txt"))
        assertTrue(script.contains("pyproject.toml"))
        assertFalse(script.contains("pip install"))
        assertFalse(script.contains("apt-get"))
        assertFalse(script.contains("rm -rf"))
    }

    @Test
    fun `parse extracts stage source bytes and tail`() {
        val parsed = PrepareProgressProbe.parse(
            """
                SIFTALPHA_PREPARE_PROGRESS=ACTIVE
                SIFTALPHA_PREPARE_STAGE=INSTALL_REQUIREMENTS
                SIFTALPHA_PREPARE_DEPENDENCY_SOURCE=requirements.txt
                SIFTALPHA_PREPARE_LOG_BYTES=12345
                SIFTALPHA_PREPARE_TAIL_BEGIN
                Collecting pandas
                Using cached numpy.whl
                SIFTALPHA_PREPARE_TAIL_END
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals("INSTALL_REQUIREMENTS", parsed!!.stage)
        assertEquals("requirements.txt", parsed.dependencySource)
        assertEquals(12345L, parsed.logBytes)
        assertEquals("Collecting pandas\nUsing cached numpy.whl", parsed.tail)
    }

    @Test
    fun `parse ignores unrelated command output`() {
        assertEquals(null, PrepareProgressProbe.parse("SIFTALPHA_STATUS=RUNNING"))
    }
}
