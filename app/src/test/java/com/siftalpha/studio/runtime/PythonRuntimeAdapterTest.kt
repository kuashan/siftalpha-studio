package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonRuntimeAdapterTest {

    private class FakeHost : RuntimeCommandHost {
        val quotedInputs = mutableListOf<String>()

        override fun runtimeSupported(): Boolean = true
        override fun runtimeUnsupportedReason(): String = "unsupported"
        override fun sharedRoot(): String = "/storage/emulated/0/AcodeProjects"
        override fun runtimeId(folderName: String): String = "runtime-id"
        override fun sh(value: String): String {
            quotedInputs += value
            return "'" + value.replace("'", "'\"'\"'") + "'"
        }
        override fun wrapUbuntu(inner: String): String = "HOST_WRAP_BEGIN\n$inner\nHOST_WRAP_END"
        override fun hostPreamble(): String = """
            set -e
            ROOT='/storage/emulated/0/AcodeProjects'
            runtime_dir="${'$'}HOME/.siftalpha/runtime"
            mkdir -p "${'$'}runtime_dir"
        """.trimIndent()
        override fun hostProcessHelpers(): String = """
            siftalpha_pid_alive() { return 1; }
            siftalpha_group_alive() { return 1; }
            siftalpha_stop_tree() { return 0; }
        """.trimIndent()
    }

    private val host = FakeHost()
    private val adapter = PythonRuntimeAdapter(host)
    private val project = RuntimeProjectSpec(
        name = "Sample",
        folderName = "sample-project",
        entry = "main.py",
        run = "python main.py",
    )

    @Test
    fun `python adapter is registered as executable runtime`() {
        val registry = RuntimeAdapterCatalog.forHost(FakeHost())
        val executable = registry.executableAdapter(RuntimeKind.PYTHON)

        assertNotNull(executable)
        assertEquals(RuntimeKind.PYTHON, executable!!.kind)
        assertTrue(executable.supports(RuntimeAction.PREPARE))
        assertTrue(executable.supports(RuntimeAction.START))
    }

    @Test
    fun `prepare preserves venv pip dependency source and durable readiness contract`() {
        val command = adapter.prepare(project)
        val script = command.shellScript

        assertEquals("Sample · 准备环境", command.label)
        assertTrue(script.contains("HOST_WRAP_BEGIN"))
        assertTrue(script.contains("python3 -m venv"))
        assertTrue(script.contains("python3 -m pip --version"))
        assertTrue(script.contains("apt-get install -y python3-venv python3-pip"))
        assertTrue(script.contains("DEPENDENCY_SOURCE=requirements.txt"))
        assertTrue(script.contains("DEPENDENCY_SOURCE=pyproject.toml"))
        assertTrue(script.contains("/root/siftalpha/env-ready-runtime-id.txt"))
        assertTrue(script.contains("sha256sum"))
        assertTrue(script.contains("printf 'SOURCE=%s\\nHASH=%s\\n'"))
        assertTrue(script.contains("SIFTALPHA_ENV=READY"))

        val pythonValidation = script.indexOf("\"${'$'}venv/bin/python\" --version")
        val readySignal = script.indexOf("echo 'SIFTALPHA_ENV=READY'")
        assertTrue("environment must be validated before READY is emitted", pythonValidation >= 0)
        assertTrue("READY must only be emitted after final Python validation", readySignal > pythonValidation)
    }

    @Test
    fun `start preserves generic secret injection readiness guard auto entry and host lifecycle`() {
        host.quotedInputs.clear()
        val command = adapter.start(project)
        val script = command.shellScript
        val rawQuotedInputs = host.quotedInputs.joinToString("\n---\n")

        assertEquals("sample-project", command.secretNamespace)
        assertTrue(rawQuotedInputs.contains("SIFTALPHA_SECRETS_FORMAT"))
        assertTrue(rawQuotedInputs.contains("SIFTALPHA_BINANCE_API_KEY"))
        assertTrue(rawQuotedInputs.contains("SIFTALPHA_ENV_COUNT"))
        assertTrue(rawQuotedInputs.contains("_NAME_B64"))
        assertTrue(rawQuotedInputs.contains("_VALUE_B64"))
        assertTrue(rawQuotedInputs.contains("SIFTALPHA_ERROR=ENV_NOT_READY"))
        assertTrue(rawQuotedInputs.contains("DEPENDENCY_MANIFEST_CHANGED"))
        assertTrue(rawQuotedInputs.contains("SIFTALPHA_ENTRY_AUTO"))
        assertTrue(rawQuotedInputs.contains("PYTHONUNBUFFERED=1"))
        assertTrue(rawQuotedInputs.contains("VIRTUAL_ENV"))
        assertTrue(rawQuotedInputs.contains("venv='/root/venvs/runtime-id'"))
        assertTrue(rawQuotedInputs.contains("${'$'}venv/bin/python"))
        assertTrue(script.contains("SIFTALPHA_RUNTIME_SESSION=SETSID"))
        assertTrue(script.contains("SIFTALPHA_RUNTIME_BACKEND=TERMUX_PROOT_PID"))
        assertTrue(script.contains("SIFTALPHA_STATUS=RUNNING"))
    }

    @Test
    fun `stop status logs and clean preserve accepted runtime markers`() {
        val stop = adapter.stop(project).shellScript
        val status = adapter.status(project).shellScript
        val logs = adapter.logs(project).shellScript
        val clean = adapter.clean(project).shellScript

        assertTrue(stop.contains("siftalpha_stop_tree"))
        assertTrue(stop.contains("SIFTALPHA_STATUS=STOPPED_BY_USER"))
        assertTrue(stop.contains("SIFTALPHA_ERROR=STOP_INCOMPLETE"))

        assertTrue(status.contains("SIFTALPHA_ENV=READY"))
        assertTrue(status.contains("SIFTALPHA_ENV=NOT_READY"))
        assertTrue(status.contains("SIFTALPHA_ENV_REASON"))
        assertTrue(status.contains("/root/siftalpha/env-ready-runtime-id.txt"))
        assertTrue(status.contains("SIFTALPHA_STATUS=RUNNING"))
        assertTrue(status.contains("SIFTALPHA_ERROR=RUNTIME_LAUNCH_FAILED"))

        assertTrue(logs.contains("=== SiftAlpha Project Log ==="))
        assertTrue(logs.contains("SIFTALPHA_LOG=EMPTY"))
        assertTrue(logs.contains("SIFTALPHA_WEB_PORT"))

        assertTrue(clean.contains("/root/venvs/runtime-id"))
        assertTrue(clean.contains("/root/siftalpha/env-ready-runtime-id.txt"))
        assertTrue(clean.contains("SIFTALPHA_STATUS=CLEAN_BLOCKED_RUNNING_PROCESS"))
        assertTrue(clean.contains("SIFTALPHA_ENV=CLEANED"))
    }

    @Test
    fun `custom run command remains delegated instead of forced to python entry`() {
        host.quotedInputs.clear()
        val custom = project.copy(run = "python -m package.worker --mode live")
        adapter.start(custom)
        val rawQuotedInputs = host.quotedInputs.joinToString("\n---\n")

        assertTrue(rawQuotedInputs.contains("configured_run='python -m package.worker --mode live'"))
        assertTrue(rawQuotedInputs.contains("auto_entry_mode=0"))
        assertTrue(rawQuotedInputs.contains("printf 'COMMAND=%s"))
        assertTrue(rawQuotedInputs.contains("bash -c \"${'$'}configured_run\""))
    }
}
