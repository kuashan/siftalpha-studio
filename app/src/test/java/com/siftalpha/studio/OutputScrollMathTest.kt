package com.siftalpha.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputScrollMathTest {
    @Test
    fun noScrollWhenContentFitsViewport() {
        assertEquals(0, OutputScrollMath.scrollRange(contentHeight = 200, viewportHeight = 300))
        assertEquals(300f, OutputScrollMath.thumbHeight(300, 200, 300, 34), 0.001f)
    }

    @Test
    fun thumbTracksScrollFraction() {
        val track = 300
        val content = 1200
        val viewport = 300
        val thumb = OutputScrollMath.thumbHeight(track, content, viewport, 34)

        assertEquals(75f, thumb, 0.001f)
        assertEquals(0f, OutputScrollMath.thumbTop(track, thumb, content, viewport, 0), 0.001f)
        assertEquals(112.5f, OutputScrollMath.thumbTop(track, thumb, content, viewport, 450), 0.001f)
        assertEquals(225f, OutputScrollMath.thumbTop(track, thumb, content, viewport, 900), 0.001f)
    }

    @Test
    fun dragFractionClampsToValidScrollRange() {
        assertEquals(0, OutputScrollMath.scrollForFraction(1200, 300, -1f))
        assertEquals(450, OutputScrollMath.scrollForFraction(1200, 300, 0.5f))
        assertEquals(900, OutputScrollMath.scrollForFraction(1200, 300, 2f))
    }
}
