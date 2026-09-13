package com.siftalpha.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioImeInsetPolicyTest {

    @Test
    fun `hidden ime preserves original bottom padding`() {
        assertEquals(
            14,
            StudioImeInsetPolicy.bottomPadding(
                baseBottom = 14,
                imeBottom = 320,
                imeVisible = false,
            ),
        )
    }

    @Test
    fun `visible ime adds safe bottom inset to original padding`() {
        assertEquals(
            334,
            StudioImeInsetPolicy.bottomPadding(
                baseBottom = 14,
                imeBottom = 320,
                imeVisible = true,
            ),
        )
    }

    @Test
    fun `invalid negative inset never removes original padding`() {
        assertEquals(
            14,
            StudioImeInsetPolicy.bottomPadding(
                baseBottom = 14,
                imeBottom = -100,
                imeVisible = true,
            ),
        )
    }
}
