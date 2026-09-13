package com.siftalpha.studio.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSecretPolicyInspectorTest {

    @Test
    fun explicitFalseDisablesBinanceApiUi() {
        val policy = ProjectSecretPolicyInspector.parse(
            """{"secrets":{"binanceApi":false}}""",
        )
        assertEquals(
            ProjectSecretPolicyInspector.BinanceApiPolicy.NOT_REQUIRED,
            policy.binanceApi,
        )
        assertFalse(policy.binanceApiUiEnabled)
    }

    @Test
    fun explicitTrueKeepsBinanceApiUiEnabled() {
        val policy = ProjectSecretPolicyInspector.parse(
            """{"secrets":{"binanceApi":true}}""",
        )
        assertEquals(
            ProjectSecretPolicyInspector.BinanceApiPolicy.REQUIRED,
            policy.binanceApi,
        )
        assertTrue(policy.binanceApiUiEnabled)
    }

    @Test
    fun missingFieldPreservesLegacyUi() {
        val policy = ProjectSecretPolicyInspector.parse("""{"name":"legacy"}""")
        assertEquals(
            ProjectSecretPolicyInspector.BinanceApiPolicy.UNSPECIFIED,
            policy.binanceApi,
        )
        assertTrue(policy.binanceApiUiEnabled)
    }
}
