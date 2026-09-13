package com.siftalpha.studio.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyProjectConfigurationBridgeTest {

    private val empty = ProjectConfigurationInspector.emptyProfile()

    @Test
    fun `explicit required legacy Binance policy becomes two required secret env values`() {
        val result = LegacyProjectConfigurationBridge.augment(
            empty,
            ProjectSecretPolicyInspector.Policy(ProjectSecretPolicyInspector.BinanceApiPolicy.REQUIRED),
        )

        assertEquals(
            listOf(
                LegacyProjectConfigurationBridge.BINANCE_API_KEY_ENV,
                LegacyProjectConfigurationBridge.BINANCE_API_SECRET_ENV,
            ),
            result.requirements.map { it.name },
        )
        assertTrue(result.requirements.all { it.required })
        assertTrue(result.requirements.all { it.secret })
    }

    @Test
    fun `unspecified legacy policy never invents a requirement`() {
        val result = LegacyProjectConfigurationBridge.augment(
            empty,
            ProjectSecretPolicyInspector.Policy(ProjectSecretPolicyInspector.BinanceApiPolicy.UNSPECIFIED),
        )

        assertTrue(result.requirements.isEmpty())
    }

    @Test
    fun `not required legacy policy never invents a requirement`() {
        val result = LegacyProjectConfigurationBridge.augment(
            empty,
            ProjectSecretPolicyInspector.Policy(ProjectSecretPolicyInspector.BinanceApiPolicy.NOT_REQUIRED),
        )

        assertTrue(result.requirements.isEmpty())
    }

    @Test
    fun `explicit generic declaration wins without duplication`() {
        val profile = ProjectConfigurationInspector.Profile(
            requirements = listOf(
                ProjectConfigurationInspector.Requirement(
                    name = LegacyProjectConfigurationBridge.BINANCE_API_KEY_ENV,
                    secret = true,
                    required = true,
                    description = "custom description",
                ),
            ),
            configuredProjectEnvKeys = emptySet(),
            credentialCandidates = emptyList(),
        )

        val result = LegacyProjectConfigurationBridge.augment(
            profile,
            ProjectSecretPolicyInspector.Policy(ProjectSecretPolicyInspector.BinanceApiPolicy.REQUIRED),
        )

        assertEquals(2, result.requirements.size)
        assertEquals("custom description", result.requirements.first().description)
    }
}
