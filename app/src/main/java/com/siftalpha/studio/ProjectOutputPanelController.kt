package com.siftalpha.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Keeps one collapsible, scrollable output panel per project card.
 *
 * Output state survives Runtime Center card refreshes inside the same Activity instance. The panel
 * is collapsed by default, expands automatically when a project command produces output, and never
 * mixes output between projects.
 *
 * The TextView remains selectable for normal Android long-press copy. Copy All handles large logs.
 * Output follows the newest line while the user stays at the bottom; manual upward scrolling pauses
 * auto-follow until the user returns to the bottom or starts another command.
 */
class ProjectOutputPanelController(
    private val context: Context,
) {
    private data class Views(
        val toggle: Button,
        val tools: LinearLayout,
        val scroll: ScrollView,
        val body: TextView,
        val scrollbar: DraggableVerticalScrollBar,
        val copyAll: Button,
    )

    private val contentByFolder = mutableMapOf<String, String>()
    private val expandedFolders = mutableSetOf<String>()
    private val followTailByFolder = mutableMapOf<String, Boolean>()
    private val viewsByFolder = mutableMapOf<String, Views>()

    fun beginRefresh() {
        viewsByFolder.clear()
    }

    fun retainOnly(folders: Set<String>) {
        contentByFolder.keys.retainAll(folders)
        expandedFolders.retainAll(folders)
        followTailByFolder.keys.retainAll(folders)
        viewsByFolder.keys.retainAll(folders)
    }

    fun attach(folderName: String, parent: LinearLayout) {
        val toggle = compactButton("")
        val copyAll = compactButton(context.getString(R.string.runtime_project_output_copy_all)).apply {
            setOnClickListener { copyAll(folderName) }
        }
        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                copyAll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val body = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(224, 228, 236))
            setBackgroundColor(Color.rgb(16, 19, 24))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
            text = displayContent(folderName)
        }
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.rgb(16, 19, 24))
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                body,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        val scrollbar = DraggableVerticalScrollBar(context).apply {
            onFractionChanged = { fraction ->
                val range = OutputScrollMath.scrollRange(body.height, scroll.height)
                scroll.scrollTo(0, (range * fraction).toInt())
            }
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val range = OutputScrollMath.scrollRange(body.height, scroll.height)
            followTailByFolder[folderName] = scrollY >= range - dp(FOLLOW_TAIL_SLOP_DP)
            scrollbar.updateMetrics(body.height, scroll.height, scrollY)
        }

        val bodyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(16, 19, 24))
            addView(
                scroll,
                LinearLayout.LayoutParams(0, dp(EXPANDED_HEIGHT_DP), 1f),
            )
            addView(
                scrollbar,
                LinearLayout.LayoutParams(dp(SCROLLBAR_TOUCH_WIDTH_DP), dp(EXPANDED_HEIGHT_DP)),
            )
        }

        fun syncExpandedState() {
            val expanded = folderName in expandedFolders
            toggle.text = context.getString(
                if (expanded) {
                    R.string.runtime_project_output_collapse
                } else {
                    R.string.runtime_project_output_expand
                },
            )
            tools.visibility = if (expanded) View.VISIBLE else View.GONE
            bodyRow.visibility = if (expanded) View.VISIBLE else View.GONE
            copyAll.isEnabled = contentByFolder[folderName].orEmpty().isNotBlank()
            if (expanded) {
                bodyRow.post {
                    updateScrollMetrics(folderName)
                    if (followTailByFolder.getOrPut(folderName) { true }) scrollToBottom(folderName)
                }
            }
        }

        toggle.setOnClickListener {
            if (!expandedFolders.add(folderName)) expandedFolders.remove(folderName)
            syncExpandedState()
        }

        parent.addView(
            toggle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        parent.addView(
            tools,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
        parent.addView(
            bodyRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
        viewsByFolder[folderName] = Views(toggle, tools, scroll, body, scrollbar, copyAll)
        syncExpandedState()
    }

    fun write(
        folderName: String,
        text: String,
        expand: Boolean = true,
        forceFollowTail: Boolean = false,
    ) {
        val looksLikeNewCommand = text.contains("executionId =") && !text.contains("exitCode =")
        contentByFolder[folderName] = text
        if (expand) expandedFolders += folderName
        if (forceFollowTail || looksLikeNewCommand) followTailByFolder[folderName] = true
        val followTail = followTailByFolder.getOrPut(folderName) { true }
        viewsByFolder[folderName]?.let { views ->
            val previousNestedScrollY = views.scroll.scrollY
            val outerPage = findAncestorScrollView(views.scroll)
            val previousOuterScrollY = outerPage?.scrollY ?: 0

            views.body.text = displayContent(folderName)
            val expanded = folderName in expandedFolders
            views.toggle.text = context.getString(
                if (expanded) R.string.runtime_project_output_collapse else R.string.runtime_project_output_expand,
            )
            views.tools.visibility = if (expanded) View.VISIBLE else View.GONE
            (views.scroll.parent as? View)?.visibility = if (expanded) View.VISIBLE else View.GONE
            views.copyAll.isEnabled = text.isNotBlank()
            views.body.post {
                if (expanded) {
                    if (followTail) {
                        scrollToBottom(folderName)
                    } else {
                        restoreNestedScroll(folderName, previousNestedScrollY)
                    }
                }
                updateScrollMetrics(folderName)
                restoreOuterPageIfReset(outerPage, previousOuterScrollY)
            }
        }
    }

    fun expand(folderName: String) {
        expandedFolders += folderName
        viewsByFolder[folderName]?.let { views ->
            views.toggle.text = context.getString(R.string.runtime_project_output_collapse)
            views.tools.visibility = View.VISIBLE
            (views.scroll.parent as? View)?.visibility = View.VISIBLE
            views.scroll.post {
                updateScrollMetrics(folderName)
                if (followTailByFolder.getOrPut(folderName) { true }) scrollToBottom(folderName)
            }
        }
    }

    private fun displayContent(folderName: String): String =
        contentByFolder[folderName].orEmpty().ifBlank {
            context.getString(R.string.runtime_center_no_command)
        }

    private fun copyAll(folderName: String) {
        val content = contentByFolder[folderName].orEmpty()
        if (content.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SiftAlpha project output", content))
        Toast.makeText(context, R.string.runtime_project_output_copied, Toast.LENGTH_SHORT).show()
    }

    private fun updateScrollMetrics(folderName: String) {
        viewsByFolder[folderName]?.let { views ->
            views.scrollbar.updateMetrics(views.body.height, views.scroll.height, views.scroll.scrollY)
        }
    }

    private fun restoreNestedScroll(folderName: String, previousScrollY: Int) {
        viewsByFolder[folderName]?.let { views ->
            val range = OutputScrollMath.scrollRange(views.body.height, views.scroll.height)
            views.scroll.scrollTo(0, previousScrollY.coerceIn(0, range))
        }
    }

    private fun scrollToBottom(folderName: String) {
        viewsByFolder[folderName]?.let { views ->
            views.scroll.post {
                // ScrollView.fullScroll() performs focus navigation in addition to scrolling.
                // Because this log panel lives inside the Runtime Center's outer ScrollView and its
                // TextView is selectable/focusable, calling fullScroll every live PREPARE poll can
                // move focus across the hierarchy and make the whole page jump. Move only this
                // nested log viewport instead; the outer page must remain exactly where the user put it.
                val range = OutputScrollMath.scrollRange(views.body.height, views.scroll.height)
                views.scroll.scrollTo(0, range)
                views.scrollbar.updateMetrics(views.body.height, views.scroll.height, views.scroll.scrollY)
            }
        }
    }

    /** Find the Runtime Center page ScrollView outside this panel's own nested log ScrollView. */
    private fun findAncestorScrollView(nestedScroll: ScrollView): ScrollView? {
        var parent: ViewParent? = nestedScroll.parent
        while (parent != null) {
            if (parent is ScrollView) return parent
            parent = parent.parent
        }
        return null
    }

    /**
     * Live text replacement must never teleport the Runtime Center page to the top. Restore only
     * the specific unintended top-reset case so a user's intentional movement to another non-zero
     * position between frames is never overwritten.
     */
    private fun restoreOuterPageIfReset(scroll: ScrollView?, previousScrollY: Int) {
        if (scroll == null || previousScrollY <= 0) return
        scroll.post {
            if (scroll.scrollY != 0) return@post
            val contentHeight = scroll.getChildAt(0)?.height ?: 0
            val range = OutputScrollMath.scrollRange(contentHeight, scroll.height)
            scroll.scrollTo(0, previousScrollY.coerceIn(0, range))
        }
    }

    private fun compactButton(label: String): Button = Button(context).apply {
        text = label
        textSize = 11.5f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val EXPANDED_HEIGHT_DP = 230
        private const val SCROLLBAR_TOUCH_WIDTH_DP = 28
        private const val FOLLOW_TAIL_SLOP_DP = 12
    }
}
