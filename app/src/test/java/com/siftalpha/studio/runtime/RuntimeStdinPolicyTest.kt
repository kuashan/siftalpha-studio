package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStdinPolicyTest {
    @Test
    fun noSecretStartRedirectsShellStdinToDevNull() {
        val command = RuntimeCommand(
            shellScript = "echo START",
            label = "start",
            secretNamespace = "demo-project",
        )

        val effective = RuntimeStdinPolicy.effectiveShellScript(command, null)

        assertTrue(effective.startsWith("exec </dev/null\n"))
        assertTrue(effective.endsWith("echo START"))
        assertFalse(RuntimeStdinPolicy.shouldAttachStdin(command, null))
    }

    @Test
    fun secretStartKeepsShellAndAttachesPayload() {
        val command = RuntimeCommand(
            shellScript = "echo START",
            label = "start",
            secretNamespace = "demo-project",
        )
        val payload = "SIFTALPHA_SECRETS_FORMAT=1\n"

        assertEquals("echo START", RuntimeStdinPolicy.effectiveShellScript(command, payload))
        assertTrue(RuntimeStdinPolicy.shouldAttachStdin(command, payload))
    }

    @Test
    fun deferredRuntimePayloadIsResolvedOnlyThroughProvider() {
        var calls = 0
        val command = RuntimeCommand(
            shellScript = "cat >/tmp/token",
            label = "agent-start",
            stdinPayloadProvider = {
                calls += 1
                "0123456789abcdef"
            },
        )

        assertEquals(0, calls)
        val resolved = RuntimeStdinPolicy.resolvePayload(command, null)
        assertEquals(1, calls)
        assertEquals("0123456789abcdef", resolved)
        assertEquals(command.shellScript, RuntimeStdinPolicy.effectiveShellScript(command, resolved))
        assertTrue(RuntimeStdinPolicy.shouldAttachStdin(command, resolved))
    }

    @Test
    fun projectSecretAndDeferredPayloadAreMutuallyExclusive() {
        val command = RuntimeCommand(
            shellScript = "echo invalid",
            label = "invalid",
            secretNamespace = "demo-project",
            stdinPayloadProvider = { "payload" },
        )

        assertThrows(IllegalStateException::class.java) {
            RuntimeStdinPolicy.resolvePayload(command, "secret")
        }
    }

    @Test
    fun ordinaryCommandsAreUnchanged() {
        val command = RuntimeCommand(
            shellScript = "echo STATUS",
            label = "status",
        )

        assertEquals("echo STATUS", RuntimeStdinPolicy.effectiveShellScript(command, null))
        assertFalse(RuntimeStdinPolicy.shouldAttachStdin(command, null))
    }
}
