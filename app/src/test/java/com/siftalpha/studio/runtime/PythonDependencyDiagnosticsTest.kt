package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonDependencyDiagnosticsTest {

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

    private val host = FakeHost()
    private val project = RuntimeProjectSpec(
        name = "Sample",
        folderName = "sample-project",
        entry = "main.py",
        run = "python main.py",
    )

    @Test
    fun `parse latest structured missing-module diagnostic`() {
        val parsed = PythonDependencyDiagnostics.parseLatest(
            """
                old output
                SIFTALPHA_DIAG_STAGE=RUN
                SIFTALPHA_DIAG=PYTHON_MODULE_MISSING
                SIFTALPHA_DIAG_DETAIL=missing_demo
                SIFTALPHA_DIAG_DEPENDENCY_SOURCE=requirements.txt
                SIFTALPHA_DIAG_HINT=CHECK_DECLARED_DEPENDENCIES
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals(PythonDependencyDiagnostics.Kind.PYTHON_MODULE_MISSING, parsed!!.kind)
        assertEquals("RUN", parsed.stage)
        assertEquals("missing_demo", parsed.detail)
        assertEquals("requirements.txt", parsed.dependencySource)
        assertEquals("CHECK_DECLARED_DEPENDENCIES", parsed.hint)
    }

    @Test
    fun `parse filesystem lock compatibility diagnostic`() {
        val parsed = PythonDependencyDiagnostics.parseLatest(
            """
                SIFTALPHA_DIAG_STAGE=RUN
                SIFTALPHA_DIAG=FILESYSTEM_LOCK_UNSUPPORTED
                SIFTALPHA_DIAG_DETAIL=fcntl.flock returned ENOSYS on the current project-backed filesystem
                SIFTALPHA_DIAG_HINT=MOVE_LOCKED_MUTABLE_DATA_TO_RUNTIME_LOCAL_FILESYSTEM
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals(PythonDependencyDiagnostics.Kind.FILESYSTEM_LOCK_UNSUPPORTED, parsed!!.kind)
        assertEquals("MOVE_LOCKED_MUTABLE_DATA_TO_RUNTIME_LOCAL_FILESYSTEM", parsed.hint)
    }

    @Test
    fun `failure wrapper really runs diagnosis after child exits under errexit`() {
        val script = PythonDependencyDiagnostics.wrapFailureShell(
            baseShell = """
                set -e
                echo BASE_STARTED
                false
                echo SHOULD_NOT_RUN
            """.trimIndent(),
            diagnosisShell = "echo SIFTALPHA_DIAG=WRAPPER_EXECUTED",
        )

        val process = ProcessBuilder("bash", "-lc", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(1, exitCode)
        assertTrue(output.contains("BASE_STARTED"))
        assertFalse(output.contains("SHOULD_NOT_RUN"))
        assertTrue(output.contains("SIFTALPHA_DIAG=WRAPPER_EXECUTED"))
    }

    @Test
    fun `always wrapper diagnoses successful upstream exit while preserving exit code`() {
        val script = PythonDependencyDiagnostics.wrapAlwaysShell(
            baseShell = "echo BASE_OK; exit 0",
            diagnosisShell = "echo SIFTALPHA_DIAG=POST_RUN_CHECK",
        )

        val process = ProcessBuilder("bash", "-lc", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(0, exitCode)
        assertTrue(output.contains("BASE_OK"))
        assertTrue(output.contains("SIFTALPHA_DIAG=POST_RUN_CHECK"))
    }

    @Test
    fun `prepare failure wrapper classifies common pip compatibility failures`() {
        val base = RuntimeCommand("echo prepare; exit 1", "prepare")
        val wrapped = PythonDependencyDiagnostics.wrapPrepareFailure(base, project, host)
        val script = wrapped.shellScript

        assertTrue(script.contains("bash -lc"))
        assertTrue(script.contains("SIFTALPHA_DIAG_STAGE=PREPARE"))
        assertTrue(script.contains("PYTHON_VERSION_MISMATCH"))
        assertTrue(script.contains("PACKAGE_NOT_FOUND"))
        assertTrue(script.contains("PLATFORM_WHEEL_INCOMPATIBLE"))
        assertTrue(script.contains("NATIVE_BUILD_FAILED"))
        assertTrue(script.contains("NETWORK_ERROR"))
        assertTrue(script.contains("STORAGE_FULL"))
        assertTrue(script.contains("DEPENDENCY_INSTALL_FAILED"))
        assertTrue(script.contains("SIFTALPHA_DIAG_DEPENDENCY_SOURCE"))
        assertTrue(script.contains("dependency diagnostic tail"))
        assertTrue(script.contains("exit \"${'$'}siftalpha_base_code\""))
    }

    @Test
    fun `runtime diagnosis covers imports dns and unsupported shared-storage flock`() {
        val base = RuntimeCommand(
            shellScript = "echo run; exit 1",
            label = "run",
            secretNamespace = "sample-project",
        )
        val wrapped = PythonDependencyDiagnostics.wrapStartFailure(base, project, host)
        val script = wrapped.shellScript

        assertEquals("sample-project", wrapped.secretNamespace)
        assertTrue(script.contains("STATE=EXITED"))
        assertTrue(script.contains("EXIT_CODE"))
        assertTrue(script.contains("ModuleNotFoundError: No module named"))
        assertTrue(script.contains("SIFTALPHA_DIAG=PYTHON_MODULE_MISSING"))
        assertTrue(script.contains("SIFTALPHA_DIAG_HINT=DECLARE_DEPENDENCY"))
        assertTrue(script.contains("SYSTEM_LIBRARY_MISSING"))
        assertTrue(script.contains("SYSTEM_COMMAND_MISSING"))
        assertTrue(script.contains("FILESYSTEM_LOCK_UNSUPPORTED"))
        assertTrue(script.contains("fcntl.flock"))
        assertTrue(script.contains("Errno 38"))
        assertTrue(script.contains("Repeated DNS resolution failures"))
        assertTrue(script.contains("CHECK_RUNTIME_DNS_AND_NETWORK"))
        assertFalse(script.contains("pip install ${'$'}missing"))
        assertFalse(script.contains("pip install \"${'$'}missing\""))
    }

    @Test
    fun `log diagnosis preserves base result and keeps generic errors exit-code guarded`() {
        val base = RuntimeCommand("echo logs", "logs")
        val wrapped = PythonDependencyDiagnostics.appendRuntimeLogDiagnosis(base, project, host)
        val script = wrapped.shellScript

        assertTrue(script.contains("echo logs"))
        assertTrue(script.contains("STATE=EXITED"))
        assertTrue(script.contains("runtime_exit"))
        assertTrue(script.contains("FILESYSTEM_LOCK_UNSUPPORTED"))
        // The diagnosis shell is passed through shellQuote(), so literal single quotes in the
        // inner `[ "$runtime_exit" != '0' ]` guard are intentionally transformed. Verify the
        // guard semantically instead of coupling this test to that quoting representation.
        assertTrue(script.contains("|| exit 0"))
        assertTrue(script.contains("CHECK_PACKAGE_VERSION_OR_IMPORT_API"))
        assertTrue(script.contains("exit \"${'$'}siftalpha_base_code\""))
    }
}
