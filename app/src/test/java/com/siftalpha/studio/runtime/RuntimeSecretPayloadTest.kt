package com.siftalpha.studio.runtime

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSecretPayloadTest {
    @Test
    fun payloadDoesNotContainPlaintextSecrets() {
        val apiKey = "demo-api-key-123"
        val apiSecret = "demo-api-secret-456"
        val payload = RuntimeSecretPayload.buildBinance(apiKey, apiSecret)

        assertTrue("SIFTALPHA_SECRETS_FORMAT=1" in payload)
        assertFalse(apiKey in payload)
        assertFalse(apiSecret in payload)
    }

    @Test
    fun payloadRoundTripsBothValues() {
        val apiKey = "api-key-A1"
        val apiSecret = "secret-Z9"
        val values = RuntimeSecretPayload.buildBinance(apiKey, apiSecret)
            .lineSequence()
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        fun decode(name: String): String = String(
            Base64.getDecoder().decode(values.getValue(name)),
            StandardCharsets.UTF_8,
        )

        assertEquals(apiKey, decode("SIFTALPHA_BINANCE_API_KEY_B64"))
        assertEquals(apiSecret, decode("SIFTALPHA_BINANCE_API_SECRET_B64"))
    }

    @Test
    fun genericEnvironmentPayloadEncodesNamesAndValuesWithoutPlaintext() {
        val payload = RuntimeSecretPayload.buildEnvironment(
            linkedMapOf(
                "TUSHARE_TOKEN" to "token-very-secret",
                "DATABASE_PATH" to "/root/siftalpha/app.db",
            ),
        )

        assertTrue("SIFTALPHA_SECRETS_FORMAT=2" in payload)
        assertTrue("SIFTALPHA_ENV_COUNT=2" in payload)
        assertFalse("TUSHARE_TOKEN" in payload)
        assertFalse("token-very-secret" in payload)
        assertFalse("/root/siftalpha/app.db" in payload)

        val values = payload.lineSequence()
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        fun decode(name: String): String = String(
            Base64.getDecoder().decode(values.getValue(name)),
            StandardCharsets.UTF_8,
        )

        // buildEnvironment sorts by variable name for deterministic transport.
        assertEquals("DATABASE_PATH", decode("SIFTALPHA_ENV_0_NAME_B64"))
        assertEquals("/root/siftalpha/app.db", decode("SIFTALPHA_ENV_0_VALUE_B64"))
        assertEquals("TUSHARE_TOKEN", decode("SIFTALPHA_ENV_1_NAME_B64"))
        assertEquals("token-very-secret", decode("SIFTALPHA_ENV_1_VALUE_B64"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun genericEnvironmentPayloadRejectsInvalidVariableNames() {
        RuntimeSecretPayload.buildEnvironment(mapOf("BAD-NAME" to "value"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun genericEnvironmentPayloadRejectsBlankValues() {
        RuntimeSecretPayload.buildEnvironment(mapOf("VALID_NAME" to ""))
    }
}
