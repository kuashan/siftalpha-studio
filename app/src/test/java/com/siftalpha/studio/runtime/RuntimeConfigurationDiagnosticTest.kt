package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConfigurationDiagnosticTest {

    @Test
    fun `named missing environment variable is actionable`() {
        val result = RuntimeConfigurationDiagnostic.inspect(
            "RuntimeError: required environment variable GEMINI_API_KEY is not set",
        )

        assertEquals(listOf("GEMINI_API_KEY"), result.missingEnvironmentNames)
        assertFalse(result.unnamedCredentialRequired)
        assertTrue(result.hasActionableFinding)
    }

    @Test
    fun `provider token wording is recognized only when variable is named`() {
        val result = RuntimeConfigurationDiagnostic.inspect(
            "TUSHARE_TOKEN is missing; configure it before startup",
        )

        assertEquals(listOf("TUSHARE_TOKEN"), result.missingEnvironmentNames)
        assertFalse(result.unnamedCredentialRequired)
    }

    @Test
    fun `python environment key error exposes credential shaped name`() {
        val result = RuntimeConfigurationDiagnostic.inspect(
            "Traceback (most recent call last):\nKeyError: 'BINANCE_API_KEY'",
        )

        assertEquals(listOf("BINANCE_API_KEY"), result.missingEnvironmentNames)
        assertTrue(result.hasActionableFinding)
    }

    @Test
    fun `ordinary python key error is not reclassified as configuration`() {
        val result = RuntimeConfigurationDiagnostic.inspect(
            "KeyError: 'missing_dictionary_key'",
        )

        assertFalse(result.hasActionableFinding)
    }

    @Test
    fun `generic api key warning never invents a variable name`() {
        val result = RuntimeConfigurationDiagnostic.inspect(
            "Configuration error: API key is required to continue",
        )

        assertTrue(result.missingEnvironmentNames.isEmpty())
        assertTrue(result.unnamedCredentialRequired)
    }

    @Test
    fun `ordinary runtime errors are not reclassified as configuration`() {
        val result = RuntimeConfigurationDiagnostic.inspect(
            "ConnectionError: temporary upstream failure\nValueError: bad response",
        )

        assertFalse(result.hasActionableFinding)
    }

    @Test
    fun `multiple named variables are deduplicated`() {
        val result = RuntimeConfigurationDiagnostic.inspect(
            """
                OPENAI_API_KEY is required
                OPENAI_API_KEY is not set
                required environment variable SERVICE_TOKEN is missing
            """.trimIndent(),
        )

        assertEquals(listOf("OPENAI_API_KEY", "SERVICE_TOKEN"), result.missingEnvironmentNames)
    }
}
