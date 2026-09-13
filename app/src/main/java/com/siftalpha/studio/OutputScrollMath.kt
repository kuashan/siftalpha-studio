package com.siftalpha.studio

import kotlin.math.max

internal object OutputScrollMath {
    fun scrollRange(contentHeight: Int, viewportHeight: Int): Int =
        max(0, contentHeight - viewportHeight)

    fun thumbHeight(
        trackHeight: Int,
        contentHeight: Int,
        viewportHeight: Int,
        minThumbHeight: Int,
    ): Float {
        if (trackHeight <= 0) return 0f
        if (contentHeight <= 0 || viewportHeight <= 0 || contentHeight <= viewportHeight) {
            return trackHeight.toFloat()
        }
        val proportional = trackHeight * (viewportHeight.toFloat() / contentHeight.toFloat())
        return proportional.coerceIn(minThumbHeight.toFloat(), trackHeight.toFloat())
    }

    fun thumbTop(
        trackHeight: Int,
        thumbHeight: Float,
        contentHeight: Int,
        viewportHeight: Int,
        scrollY: Int,
    ): Float {
        val range = scrollRange(contentHeight, viewportHeight)
        if (range <= 0 || trackHeight <= 0) return 0f
        val fraction = scrollY.coerceIn(0, range).toFloat() / range.toFloat()
        return fraction * max(0f, trackHeight - thumbHeight)
    }

    fun scrollForFraction(contentHeight: Int, viewportHeight: Int, fraction: Float): Int {
        val range = scrollRange(contentHeight, viewportHeight)
        return (range * fraction.coerceIn(0f, 1f)).toInt()
    }
}
