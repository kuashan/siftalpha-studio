package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RuntimeAgentProtocolTest {

    @Test
    fun tokenFormatIsExactly256BitHex() {
        assertTrue(RuntimeAgentProtocol.isValidToken("a".repeat(64)))
        assertFalse(RuntimeAgentProtocol.isValidToken("a".repeat(63)))
        assertFalse(RuntimeAgentProtocol.isValidToken("A".repeat(64)))
        assertFalse(RuntimeAgentProtocol.isValidToken("z".repeat(64)))
        assertFalse(RuntimeAgentProtocol.isValidToken(null))
    }

    @Test
    fun agentSourceBindsOnlyToIpv4LoopbackAndExposesMultiPtyCapability() {
        val source = RuntimeAgentProtocol.pythonSource()

        assertEquals("0.5.4", RuntimeAgentProtocol.AGENT_VERSION)
        assertEquals(4, RuntimeAgentProtocol.PROTOCOL_VERSION)
        assertEquals(4, RuntimeAgentProtocol.MAX_TERMINAL_SESSIONS)
        assertTrue(source.contains("HOST = '127.0.0.1'"))
        assertTrue(source.contains("ThreadingHTTPServer((HOST, PORT), Handler)"))
        assertTrue(source.contains("\"terminal-multi\""))
        assertTrue(source.contains("\"terminal-list\""))
        assertFalse(source.contains("\"terminal-single\""))
        assertTrue(source.contains("Authorization"))
        assertTrue(source.contains("hmac.compare_digest"))
        assertTrue(source.contains("import pty"))
        assertTrue(source.contains("pty.fork()"))
        assertTrue(source.contains("os.execvpe(\"/bin/bash\", [\"/bin/bash\", \"-i\"], env)"))
        assertFalse(source.contains("/exec"))
        assertFalse(source.contains("/shell"))
        assertFalse(source.contains("0.0.0.0"))
    }

    @Test
    fun terminalProtocolHasBoundedIndependentSessionsAndResizeEndpoints() {
        val source = RuntimeAgentProtocol.pythonSource()

        assertTrue(source.contains(RuntimeAgentProtocol.TERMINAL_SESSIONS_PATH))
        assertTrue(source.contains(RuntimeAgentProtocol.TERMINAL_SESSION_PATH))
        assertTrue(source.contains(RuntimeAgentProtocol.TERMINAL_INPUT_PATH))
        assertTrue(source.contains(RuntimeAgentProtocol.TERMINAL_OUTPUT_PATH))
        assertTrue(source.contains(RuntimeAgentProtocol.TERMINAL_RESIZE_PATH))
        assertTrue(source.contains(RuntimeAgentProtocol.TERMINAL_CLOSE_PATH))
        assertTrue(source.contains("MAX_TERMINAL_SESSIONS = 4"))
        assertTrue(source.contains("SESSIONS = {}"))
        assertTrue(source.contains("SESSION_ORDER = []"))
        assertTrue(source.contains("SESSIONS.get(session_id)"))
        assertTrue(source.contains("max_sessions_running"))
        assertFalse(source.contains("session_already_running"))
        assertFalse(source.contains("SESSION = None"))
        assertTrue(source.contains("TIOCSWINSZ"))
        assertTrue(source.contains("resize_terminal"))
        assertTrue(source.contains("MIN_COLS = 20"))
        assertTrue(source.contains("MAX_COLS = 300"))
        assertTrue(source.contains("MIN_ROWS = 5"))
        assertTrue(source.contains("MAX_ROWS = 120"))
        assertTrue(source.contains("MAX_TERMINAL_BUFFER_BYTES = 512 * 1024"))
        assertTrue(source.contains("MAX_TERMINAL_OUTPUT_BYTES = 32 * 1024"))
        assertTrue(source.contains("MAX_TERMINAL_INPUT_BYTES = 16 * 1024"))
    }

    @Test
    fun stoppedSessionsCanBePrunedButFourRunningSessionsCannotBeExceeded() {
        val source = RuntimeAgentProtocol.pythonSource()

        assertTrue(source.contains("def prune_stopped_for_capacity():"))
        assertTrue(source.contains("if session is not None and not session[\"running\"]:"))
        assertTrue(source.contains("if not prune_stopped_for_capacity():"))
        assertTrue(source.contains("return None, \"max_sessions_running\""))
        assertTrue(source.contains("SESSIONS.pop(session_id, None)"))
        assertTrue(source.contains("SESSION_ORDER.remove(session_id)"))
    }

    @Test
    fun generatedAgentPythonIsSyntacticallyValid() {
        val source = RuntimeAgentProtocol.pythonSource()
        val file = Files.createTempFile("siftalpha-agent-v4-", ".py")
        try {
            Files.write(file, source.toByteArray(Charsets.UTF_8))
            val process = ProcessBuilder("python3", "-m", "py_compile", file.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            assertEquals("Generated Runtime Agent Python failed py_compile:\n$output", 0, exitCode)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(file.resolveSibling(file.fileName.toString() + "c"))
        }
    }

    @Test
    fun startCommandDoesNotEmbedBearerTokenInShellOrMetadata() {
        val token = "0123456789abcdef".repeat(4)
        val command = RuntimeAgentController().start { token }

        assertFalse(command.shellScript.contains(token))
        assertFalse(command.label.contains(token))
        assertFalse(command.description.contains(token))
        assertEquals(token, RuntimeStdinPolicy.resolvePayload(command, null))
        assertTrue(RuntimeStdinPolicy.shouldAttachStdin(command, token))
    }

    @Test
    fun startCommandUsesPrivateUbuntuPathsAndProvenHostProcessBackend() {
        val command = RuntimeAgentController().start { "a".repeat(64) }

        assertTrue(command.shellScript.contains(RuntimeAgentProtocol.AGENT_DIR))
        assertTrue(command.shellScript.contains("chmod 600 ${RuntimeAgentProtocol.TOKEN_PATH}"))
        assertTrue(command.shellScript.contains("SIFTALPHA_AGENT_HOST=${RuntimeAgentProtocol.HOST}"))
        assertTrue(command.shellScript.contains("SIFTALPHA_AGENT_PORT=${RuntimeAgentProtocol.PORT}"))
        assertTrue(command.shellScript.contains("nohup setsid proot-distro login ubuntu"))
        assertTrue(command.shellScript.contains("SIFTALPHA_AGENT_BACKEND=TERMUX_PROOT_PID"))
        assertTrue(command.shellScript.contains("host.pid"))
        assertTrue(command.shellScript.contains("host.pgid"))
        assertFalse(command.shellScript.contains("tmux new-session"))
    }

    @Test
    fun startCommandRequiresTcpListenerReadinessBeforeReportingRunning() {
        val command = RuntimeAgentController().start { "a".repeat(64) }

        assertTrue(command.shellScript.contains("socket.create_connection"))
        assertTrue(command.shellScript.contains("SIFTALPHA_AGENT_READY=1"))
        assertTrue(command.shellScript.contains("SIFTALPHA_AGENT_ERROR=NOT_LISTENING"))
        assertTrue(command.shellScript.contains("--- agent.log ---"))
        assertTrue(command.shellScript.contains("--- launcher ---"))
    }

    @Test
    fun stopCommandTargetsOnlyTrackedAgentProcessTree() {
        val command = RuntimeAgentController().stop()

        assertTrue(command.shellScript.contains(".siftalpha/agent"))
        assertTrue(command.shellScript.contains("siftalpha_stop_tree"))
        assertTrue(command.shellScript.contains("SIFTALPHA_AGENT=STOPPED"))
        assertFalse(command.shellScript.contains("/root/projects"))
        assertFalse(command.shellScript.contains("/root/venvs"))
    }

    @Test
    fun healthHttpParserSeparatesStatusAndBody() {
        val response = RuntimeAgentHealthClient.parseHttpResponse(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}",
        )

        assertEquals(200, response.statusCode)
        assertEquals("{\"ok\":true}", response.body)
    }

    @Test
    fun terminalHttpParserPreservesCreatedStatus() {
        val response = RuntimeAgentTerminalClient.parseHttpResponse(
            "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}",
        )

        assertEquals(201, response.statusCode)
        assertEquals("{\"ok\":true}", response.body)
    }
}
