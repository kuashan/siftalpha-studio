package com.siftalpha.studio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Touch-draggable vertical scrollbar for the project output pane.
 *
 * Android ScrollView's normal scrollbar is primarily an indicator. This view exposes a real thumb
 * that can be dragged while the ScrollView itself remains swipe-scrollable.
 */
class DraggableVerticalScrollBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 64, 74)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(166, 173, 186)
    }

    private var contentHeight = 0
    private var viewportHeight = 0
    private var currentScrollY = 0
    private var dragging = false
    private var dragOffsetY = 0f

    var onFractionChanged: ((Float) -> Unit)? = null

    fun updateMetrics(contentHeight: Int, viewportHeight: Int, scrollY: Int) {
        this.contentHeight = max(0, contentHeight)
        this.viewportHeight = max(0, viewportHeight)
        this.currentScrollY = max(0, scrollY)
        visibility = if (scrollRange() > 0) VISIBLE else INVISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scrollRange() <= 0 || height <= 0 || width <= 0) return

        val centerX = width / 2f
        val trackHalf = dp(2).toFloat()
        canvas.drawRoundRect(
            centerX - trackHalf,
            0f,
            centerX + trackHalf,
            height.toFloat(),
            trackHalf,
            trackHalf,
            trackPaint,
        )

        val thumbHeight = thumbHeightPx()
        val thumbTop = thumbTopPx(thumbHeight)
        val thumbHalf = dp(5).toFloat()
        canvas.drawRoundRect(
            centerX - thumbHalf,
            thumbTop,
            centerX + thumbHalf,
            thumbTop + thumbHeight,
            thumbHalf,
            thumbHalf,
            thumbPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (scrollRange() <= 0) return false
        val thumbHeight = thumbHeightPx()
        val thumbTop = thumbTopPx(thumbHeight)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragOffsetY = if (event.y in thumbTop..(thumbTop + thumbHeight)) {
                    event.y - thumbTop
                } else {
                    thumbHeight / 2f
                }
                updateFromTouch(event.y, thumbHeight)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                updateFromTouch(event.y, thumbHeight)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) updateFromTouch(event.y, thumbHeight)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(y: Float, thumbHeight: Float) {
        val travel = max(1f, height - thumbHeight)
        val top = (y - dragOffsetY).coerceIn(0f, travel)
        onFractionChanged?.invoke((top / travel).coerceIn(0f, 1f))
    }

    private fun scrollRange(): Int =
        OutputScrollMath.scrollRange(contentHeight, viewportHeight)

    private fun thumbHeightPx(): Float =
        OutputScrollMath.thumbHeight(
            trackHeight = height,
            contentHeight = contentHeight,
            viewportHeight = viewportHeight,
            minThumbHeight = dp(MIN_THUMB_DP),
        )

    private fun thumbTopPx(thumbHeight: Float): Float =
        OutputScrollMath.thumbTop(
            trackHeight = height,
            thumbHeight = thumbHeight,
            contentHeight = contentHeight,
            viewportHeight = viewportHeight,
            scrollY = currentScrollY,
        )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_THUMB_DP = 34
    }
}
