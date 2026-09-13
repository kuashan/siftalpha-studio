package com.siftalpha.studio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.siftalpha.studio.runtime.RuntimeStorageChartModel
import com.siftalpha.studio.runtime.RuntimeStorageSizeFormatter

/** Lightweight View-based rendering for the Runtime Storage Breakdown. */
class RuntimeStorageChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    data class Texts(
        val emptyMessage: String,
        val legend: String,
        val formatComponent: (RuntimeStorageChartModel.ComponentKind, Long) -> String,
        val formatRowDescription: (String, String, String) -> String,
    )

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun render(
        model: RuntimeStorageChartModel,
        texts: Texts,
        onProjectClick: ((RuntimeStorageChartModel.Project) -> Unit)? = null,
    ) {
        removeAllViews()
        if (model.isEmpty) {
            addView(TextView(context).apply {
                text = texts.emptyMessage
                textSize = 13f
                setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, Color.LTGRAY))
                setPadding(0, dp(4), 0, dp(8))
            })
            return
        }

        addView(TextView(context).apply {
            text = texts.legend
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, Color.LTGRAY))
            setPadding(0, dp(2), 0, dp(7))
        })

        model.projects.filter { it.totalBytes > 0L }.forEach { project ->
            val componentSummary = project.components.joinToString(" · ") { component ->
                texts.formatComponent(component.kind, component.bytes)
            }
            val row = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(resolveThemeColor(android.R.attr.colorBackground, Color.rgb(24, 28, 35)))
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), resolveThemeColor(android.R.attr.colorControlNormal, Color.rgb(48, 54, 64)))
                }
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                isClickable = onProjectClick != null
                isFocusable = onProjectClick != null
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = texts.formatRowDescription(
                    project.projectDisplayName,
                    RuntimeStorageSizeFormatter.formatBytes(project.totalBytes),
                    componentSummary,
                )
                if (onProjectClick != null) {
                    setOnClickListener { onProjectClick.invoke(project) }
                }
            }

            val heading = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            heading.addView(TextView(context).apply {
                text = project.projectDisplayName
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            heading.addView(TextView(context).apply {
                text = RuntimeStorageSizeFormatter.formatBytes(project.totalBytes)
                textSize = 13f
                setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, Color.LTGRAY))
                gravity = android.view.Gravity.END
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                }
            })
            row.addView(heading)

            row.addView(RuntimeStorageBarView(context, project, model).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(16)).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(5)
                }
            })

            row.addView(TextView(context).apply {
                text = componentSummary
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, Color.LTGRAY))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(row)
        }
    }

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) {
            runCatching { context.getColor(value.resourceId) }.getOrDefault(fallback)
        } else {
            value.data
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class RuntimeStorageBarView(
        context: Context,
        private val project: RuntimeStorageChartModel.Project,
        private val model: RuntimeStorageChartModel,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val track = RectF()
        private var strokeWidth = 0f

        init {
            strokeWidth = dp(1).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return

            val widthF = width.toFloat()
            val heightF = height.toFloat()
            val radius = heightF / 2f
            track.set(0f, 0f, widthF, heightF)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(53, 59, 70)
            canvas.drawRoundRect(track, radius, radius, paint)

            val fillWidth = (widthF * model.barFraction(project).toFloat()).coerceIn(0f, widthF)
            if (fillWidth <= 0f) return

            canvas.save()
            canvas.clipRect(0f, 0f, fillWidth, heightF)
            var left = 0f
            project.components.forEachIndexed { index, component ->
                val segmentWidth = if (index == project.components.lastIndex) {
                    (fillWidth - left).coerceAtLeast(0f)
                } else {
                    fillWidth * (component.bytes.toDouble() / project.totalBytes.toDouble()).toFloat()
                }
                if (segmentWidth > 0f) {
                    paint.color = when (component.kind) {
                        RuntimeStorageChartModel.ComponentKind.PYTHON -> Color.rgb(67, 130, 220)
                        RuntimeStorageChartModel.ComponentKind.NODE_JS -> Color.rgb(226, 145, 54)
                        RuntimeStorageChartModel.ComponentKind.OTHER -> Color.rgb(132, 139, 151)
                    }
                    canvas.drawRect(left, 0f, left + segmentWidth, heightF, paint)
                    left += segmentWidth
                }
            }
            canvas.restore()

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = Color.argb(110, 255, 255, 255)
            canvas.drawRoundRect(track, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }
}
