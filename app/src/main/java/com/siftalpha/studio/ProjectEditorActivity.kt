package com.siftalpha.studio

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.siftalpha.studio.project.EditorTextSearch
import com.siftalpha.studio.project.ProjectStore
import com.siftalpha.studio.project.createEnvironmentFileFromTemplate
import com.siftalpha.studio.project.environmentTemplateTargetIfAvailable

/** Mobile project file tree + safe text editor. */
class ProjectEditorActivity : StudioActivity() {

    private lateinit var projectStore: ProjectStore
    private lateinit var projectName: String
    private lateinit var projectDocumentId: String
    private lateinit var body: FrameLayout
    private lateinit var treePanel: LinearLayout
    private lateinit var treeList: ListView
    private lateinit var treeSummary: TextView
    private lateinit var treeAdapter: TreeAdapter
    private lateinit var editorPanel: LinearLayout
    private lateinit var findPanel: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findStatus: TextView
    private lateinit var findCaseSensitive: CheckBox
    private lateinit var pathText: TextView
    private lateinit var stateText: TextView
    private lateinit var editor: EditText
    private lateinit var saveButton: Button
    private var currentFile: ProjectStore.FileNode? = null
    private var savedText: String = ""
    private var searchInProgress = false
    private var systemBackCallback: OnBackInvokedCallback? = null
    private var findMatches: List<EditorTextSearch.Match> = emptyList()
    private var findMatchIndex = -1

    private val expandedDirectoryIds = linkedSetOf<String>()
    private val childrenCache = mutableMapOf<String, List<ProjectStore.FileNode>>()
    private val visibleNodes = mutableListOf<ProjectStore.FileNode>()
    private var restoredTreeFirst = -1
    private var restoredTreeTop = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty()
        projectDocumentId = intent.getStringExtra(EXTRA_PROJECT_DOCUMENT_ID).orEmpty()
        if (projectName.isBlank() || projectDocumentId.isBlank()) {
            Toast.makeText(this, getString(R.string.editor_invalid_project_info), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        projectStore = ProjectStore(this)
        savedInstanceState?.getStringArrayList(STATE_EXPANDED_DIRECTORIES)?.let {
            expandedDirectoryIds.addAll(it)
        }
        restoredTreeFirst = savedInstanceState?.getInt(STATE_TREE_FIRST, -1) ?: -1
        restoredTreeTop = savedInstanceState?.getInt(STATE_TREE_TOP, 0) ?: 0
        setContentView(buildUi())
        installSystemBackProtection()
        refreshTree(invalidateCache = true)
        showTree()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_EXPANDED_DIRECTORIES, ArrayList(expandedDirectoryIds))
        if (::treeList.isInitialized && treeList.childCount > 0) {
            outState.putInt(STATE_TREE_FIRST, treeList.firstVisiblePosition)
            outState.putInt(STATE_TREE_TOP, treeList.getChildAt(0)?.top ?: 0)
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        systemBackCallback = null
        super.onDestroy()
    }

    private fun installSystemBackProtection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = OnBackInvokedCallback { handleBackNavigation() }
        systemBackCallback = callback
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 19, 24))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(text(projectName, 22f, true).apply { setTextColor(Color.WHITE) })
        root.addView(text(getString(R.string.editor_subtitle, appVersionName()), 12f, false).apply {
            setTextColor(Color.rgb(160, 166, 178)); setPadding(0, dp(1), 0, dp(8))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(smallButton(getString(R.string.editor_back_projects)) { finishWithUnsavedCheck() }, weightParams())
        actions.addView(smallButton(getString(R.string.editor_file_tree)) { showTree() }, weightParams().apply { marginStart = dp(5) })
        saveButton = smallButton(getString(R.string.editor_save)) { saveCurrentFile() }
        actions.addView(saveButton, weightParams().apply { marginStart = dp(5) })
        root.addView(actions)
        pathText = text(getString(R.string.editor_file_unopened), 12f, false).apply {
            setTextColor(Color.rgb(190, 194, 204)); setPadding(0, dp(8), 0, dp(2))
        }
        root.addView(pathText)
        stateText = text(getString(R.string.editor_select_file), 11f, false).apply {
            setTextColor(Color.rgb(150, 157, 169)); setPadding(0, 0, 0, dp(6))
        }
        root.addView(stateText)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        treePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(19, 23, 29))
        }
        val createTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        createTools.addView(smallButton(getString(R.string.editor_new_file)) { showCreateEntryDialog(null, false) }, weightParams())
        createTools.addView(smallButton(getString(R.string.editor_new_folder)) { showCreateEntryDialog(null, true) }, weightParams().apply { marginStart = dp(5) })
        treePanel.addView(createTools)
        val navTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        navTools.addView(smallButton(getString(R.string.editor_search)) { showSearchDialog() }, weightParams())
        navTools.addView(smallButton(getString(R.string.editor_recent_files)) { showRecentFiles() }, weightParams().apply { marginStart = dp(5) })
        treePanel.addView(navTools)

        treeSummary = text(getString(R.string.editor_project_files, 0), 13f, true).apply {
            setTextColor(Color.rgb(170, 224, 190))
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        treePanel.addView(treeSummary)
        treeAdapter = TreeAdapter()
        treeList = ListView(this).apply {
            adapter = treeAdapter
            divider = null
            dividerHeight = dp(3)
            isVerticalScrollBarEnabled = true
            isFastScrollEnabled = false
            setPadding(0, 0, 0, dp(6))
            clipToPadding = false
        }
        treePanel.addView(treeList, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        treePanel.addView(hint(getString(R.string.editor_tree_help)))
        treePanel.addView(hint(getString(R.string.editor_tree_hidden)))
        body.addView(treePanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        editorPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val editorTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(5)) }
        editorTools.addView(smallButton(getString(R.string.editor_find)) { showFindPanel() }, weightParams())
        editorTools.addView(smallButton(getString(R.string.editor_tab)) { insertAtCursor("    ") }, weightParams().apply { marginStart = dp(5) })
        editorTools.addView(smallButton(getString(R.string.editor_indent)) { indentSelectedLines() }, weightParams().apply { marginStart = dp(5) })
        editorTools.addView(smallButton(getString(R.string.editor_unindent)) { unindentSelectedLines() }, weightParams().apply { marginStart = dp(5) })
        editorPanel.addView(editorTools)

        findPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(5))
        }
        val findQueryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        findInput = EditText(this).apply {
            hint = getString(R.string.editor_find_hint)
            setSingleLine(true)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        findQueryRow.addView(findInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val previousFind = smallButton("↑") { moveFindMatch(forward = false) }.apply {
            contentDescription = getString(R.string.editor_find_previous)
        }
        val nextFind = smallButton("↓") { moveFindMatch(forward = true) }.apply {
            contentDescription = getString(R.string.editor_find_next)
        }
        val closeFind = smallButton("×") { hideFindPanel() }.apply {
            contentDescription = getString(R.string.editor_find_close)
        }
        findQueryRow.addView(previousFind, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        findQueryRow.addView(nextFind, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        findQueryRow.addView(closeFind, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        findPanel.addView(findQueryRow)

        val findMetaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        findStatus = text(getString(R.string.editor_find_enter_query), 12f, false).apply {
            setTextColor(Color.rgb(150, 157, 169))
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        findCaseSensitive = CheckBox(this).apply {
            text = getString(R.string.editor_find_case_sensitive)
            textSize = 12f
            setTextColor(Color.rgb(190, 194, 204))
        }
        findMetaRow.addView(findStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        findMetaRow.addView(findCaseSensitive, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        findPanel.addView(findMetaRow)
        editorPanel.addView(findPanel)

        editor = EditText(this).apply {
            setTextColor(Color.rgb(232, 235, 240)); setHintTextColor(Color.rgb(120, 126, 138)); setBackgroundColor(Color.rgb(20, 24, 31))
            typeface = Typeface.MONOSPACE; textSize = 14f; gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(12)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false; setHorizontallyScrolling(true); isHorizontalScrollBarEnabled = true; isVerticalScrollBarEnabled = true
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (currentFile == null || editorPanel.visibility != View.VISIBLE) return
                if (hasUnsavedChanges()) {
                    stateText.text = getString(R.string.editor_unsaved)
                    stateText.setTextColor(Color.rgb(240, 184, 120))
                } else {
                    stateText.text = getString(R.string.editor_saved)
                    stateText.setTextColor(Color.rgb(170, 224, 190))
                }
                if (findPanel.visibility == View.VISIBLE && findInput.text.isNotEmpty()) {
                    refreshFindMatches(selectCurrent = false)
                }
            }
        })
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (findPanel.visibility == View.VISIBLE) refreshFindMatches(selectCurrent = true)
            }
        })
        findCaseSensitive.setOnCheckedChangeListener { _, _ ->
            if (findPanel.visibility == View.VISIBLE) refreshFindMatches(selectCurrent = true)
        }
        editorPanel.addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(editorPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun cacheKey(parent: ProjectStore.FileNode?): String = parent?.documentId ?: "ROOT:$projectDocumentId"

    private fun childrenFor(parent: ProjectStore.FileNode?): List<ProjectStore.FileNode> =
        childrenCache.getOrPut(cacheKey(parent)) {
            projectStore.listProjectChildren(projectDocumentId, parent)
        }

    private fun refreshTree(invalidateCache: Boolean = true) {
        val currentFirst = if (::treeList.isInitialized && treeList.childCount > 0) treeList.firstVisiblePosition else -1
        val currentTop = if (::treeList.isInitialized && treeList.childCount > 0) treeList.getChildAt(0)?.top ?: 0 else 0
        if (invalidateCache) childrenCache.clear()
        try {
            val rebuilt = mutableListOf<ProjectStore.FileNode>()
            fun append(nodes: List<ProjectStore.FileNode>) {
                for (node in nodes) {
                    if (rebuilt.size >= MAX_VISIBLE_TREE_NODES) return
                    rebuilt += node
                    if (node.isDirectory && node.documentId in expandedDirectoryIds) {
                        append(childrenFor(node))
                    }
                }
            }
            append(childrenFor(null))
            visibleNodes.clear()
            visibleNodes.addAll(rebuilt)
            treeAdapter.notifyDataSetChanged()
            treeSummary.text = if (visibleNodes.isEmpty()) {
                getString(R.string.editor_empty_project)
            } else {
                getString(R.string.editor_project_files, visibleNodes.size)
            }
            treeSummary.setTextColor(
                if (visibleNodes.isEmpty()) Color.rgb(150, 157, 169) else Color.rgb(170, 224, 190),
            )

            val targetFirst = if (restoredTreeFirst >= 0) restoredTreeFirst else currentFirst
            val targetTop = if (restoredTreeFirst >= 0) restoredTreeTop else currentTop
            restoredTreeFirst = -1
            restoredTreeTop = 0
            if (targetFirst >= 0 && visibleNodes.isNotEmpty()) {
                treeList.post {
                    treeList.setSelectionFromTop(targetFirst.coerceAtMost(visibleNodes.lastIndex), targetTop)
                }
            }
        } catch (error: Throwable) {
            visibleNodes.clear()
            treeAdapter.notifyDataSetChanged()
            treeSummary.text = getString(R.string.editor_tree_read_failed, error.message ?: error.javaClass.simpleName)
            treeSummary.setTextColor(Color.rgb(240, 184, 120))
        }
    }

    private fun toggleDirectory(node: ProjectStore.FileNode) {
        if (!node.isDirectory) return
        if (!expandedDirectoryIds.add(node.documentId)) {
            expandedDirectoryIds.remove(node.documentId)
        }
        refreshTree(invalidateCache = false)
    }

    private fun expandPathTo(relativePath: String) {
        val directoryParts = relativePath.split('/').filter { it.isNotBlank() }.dropLast(1)
        if (directoryParts.isEmpty()) return
        var parent: ProjectStore.FileNode? = null
        for (part in directoryParts) {
            val next = childrenFor(parent).firstOrNull {
                it.isDirectory && it.name == part
            } ?: break
            expandedDirectoryIds += next.documentId
            parent = next
        }
    }

    private inner class TreeAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleNodes.size
        override fun getItem(position: Int): ProjectStore.FileNode = visibleNodes[position]
        override fun getItemId(position: Int): Long = getItem(position).documentId.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? TextView) ?: TextView(this@ProjectEditorActivity).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minHeight = dp(42)
            }
            val node = getItem(position)
            val expanded = node.documentId in expandedDirectoryIds
            row.text = when {
                node.isDirectory && expanded -> "▼ 📂 ${node.name}"
                node.isDirectory -> "▶ 📁 ${node.name}"
                else -> "   📄 ${node.name}"
            }
            row.textSize = if (node.isDirectory) 13.5f else 13f
            row.typeface = if (node.isDirectory) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            row.setTextColor(if (node.isDirectory) Color.rgb(205, 210, 220) else Color.rgb(225, 228, 234))
            row.setPadding(dp(10 + node.depth * 18), dp(7), dp(8), dp(7))
            row.background = GradientDrawable().apply {
                setColor(Color.rgb(24, 28, 35))
                cornerRadius = dp(6).toFloat()
            }
            row.contentDescription = node.relativePath
            row.setOnClickListener {
                if (node.isDirectory) toggleDirectory(node) else openFileRequested(node)
            }
            row.setOnLongClickListener {
                showNodeActions(node)
                true
            }
            return row
        }
    }

    private fun showNodeActions(node: ProjectStore.FileNode) {
        val labels = if (node.isDirectory) {
            arrayOf(
                getString(R.string.editor_action_new_file_here),
                getString(R.string.editor_action_new_folder_here),
                getString(R.string.editor_action_rename),
                getString(R.string.editor_action_delete),
            )
        } else {
            arrayOf(
                getString(R.string.editor_action_open),
                getString(R.string.editor_action_rename),
                getString(R.string.editor_action_delete),
            )
        }
        AlertDialog.Builder(this).setTitle(node.relativePath).setItems(labels) { _, which ->
            if (node.isDirectory) when (which) {
                0 -> showCreateEntryDialog(node, false)
                1 -> showCreateEntryDialog(node, true)
                2 -> showRenameDialog(node)
                3 -> confirmDeleteNode(node)
            } else when (which) {
                0 -> openFileRequested(node)
                1 -> showRenameDialog(node)
                2 -> confirmDeleteNode(node)
            }
        }.setNegativeButton(R.string.common_cancel, null).show()
    }

    private fun showCreateEntryDialog(parent: ProjectStore.FileNode?, isDirectory: Boolean) {
        val input = EditText(this).apply {
            hint = getString(if (isDirectory) R.string.editor_folder_name_hint else R.string.editor_file_name_hint)
            setSingleLine(true)
        }
        val location = parent?.relativePath ?: projectName
        AlertDialog.Builder(this)
            .setTitle(getString(if (isDirectory) R.string.editor_create_folder_title else R.string.editor_create_file_title))
            .setMessage(getString(R.string.editor_location, location))
            .setView(input)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.editor_create) { _, _ ->
                try {
                    if (parent != null) expandedDirectoryIds += parent.documentId
                    if (isDirectory) {
                        projectStore.createProjectFolder(projectDocumentId, parent, input.text.toString())
                        refreshTree(invalidateCache = true)
                        toast(getString(R.string.editor_folder_created))
                    } else {
                        val file = projectStore.createProjectFile(projectDocumentId, parent, input.text.toString())
                        refreshTree(invalidateCache = true)
                        openFileRequested(file)
                    }
                } catch (error: Throwable) {
                    showOperationError(
                        getString(if (isDirectory) R.string.editor_create_folder_failed else R.string.editor_create_file_failed),
                        error,
                    )
                }
            }.show()
    }

    private fun showRenameDialog(node: ProjectStore.FileNode) {
        val input = EditText(this).apply { setText(node.name); setSelection(text.length); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle(R.string.editor_rename_title).setMessage(node.relativePath).setView(input).setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                try {
                    val wasExpanded = expandedDirectoryIds.remove(node.documentId)
                    val renamed = projectStore.renameProjectNode(projectDocumentId, node, input.text.toString())
                    if (wasExpanded) expandedDirectoryIds += renamed.documentId
                    if (currentFile?.documentId == node.documentId) currentFile = renamed
                    refreshTree(invalidateCache = true)
                    syncCurrentFileAfterTreeMutation()
                    projectStore.recordRecentFile(
                        projectDocumentId,
                        currentFile?.takeIf { it.documentId == renamed.documentId } ?: renamed,
                    )
                    toast(getString(R.string.editor_renamed, renamed.name))
                } catch (error: Throwable) {
                    showOperationError(getString(R.string.editor_rename_failed), error)
                }
            }.show()
    }

    private fun confirmDeleteNode(node: ProjectStore.FileNode) {
        val affectsCurrent = affectsCurrentFile(node)
        val unsavedWarning = if (affectsCurrent && hasUnsavedChanges()) getString(R.string.editor_delete_unsaved_warning) else ""
        val directoryWarning = if (node.isDirectory) getString(R.string.editor_delete_directory_warning) else ""
        AlertDialog.Builder(this).setTitle(getString(R.string.editor_delete_title, node.name))
            .setMessage(getString(R.string.editor_delete_message, node.relativePath, directoryWarning, unsavedWarning))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.editor_delete_confirm) { _, _ ->
                try {
                    projectStore.deleteProjectNode(node)
                    projectStore.removeRecentFile(projectDocumentId, node.documentId)
                    expandedDirectoryIds.remove(node.documentId)
                    if (affectsCurrent) clearCurrentFileAfterDelete()
                    refreshTree(invalidateCache = true)
                    toast(getString(R.string.editor_deleted, node.name))
                } catch (error: Throwable) {
                    showOperationError(getString(R.string.editor_delete_failed), error)
                }
            }.show()
    }

    private fun syncCurrentFileAfterTreeMutation() {
        val current = currentFile ?: return
        val updated = projectStore.findProjectNode(projectDocumentId, current.documentId) ?: return
        currentFile = updated
        pathText.text = getString(R.string.editor_file_path, updated.relativePath)
        expandPathTo(updated.relativePath)
    }

    private fun clearCurrentFileAfterDelete() {
        currentFile = null
        savedText = ""
        editor.setText("")
        findPanel.visibility = View.GONE
        findMatches = emptyList()
        findMatchIndex = -1
        pathText.text = getString(R.string.editor_file_unopened)
        stateText.text = getString(R.string.editor_current_file_deleted)
        stateText.setTextColor(Color.rgb(240, 184, 120))
        showTree()
    }

    private fun affectsCurrentFile(node: ProjectStore.FileNode): Boolean {
        val current = currentFile ?: return false
        return current.relativePath == node.relativePath || current.relativePath.startsWith("${node.relativePath}/")
    }

    private fun openFileRequested(file: ProjectStore.FileNode, targetLine: Int? = null) {
        if (!projectStore.isEditableTextFile(file)) {
            toast(getString(R.string.editor_unsupported_file, file.name))
            return
        }
        val environmentTarget = try {
            projectStore.environmentTemplateTargetIfAvailable(projectDocumentId, file)
        } catch (error: Throwable) {
            showOperationError(getString(R.string.editor_env_template_check_failed), error)
            return
        }
        if (environmentTarget != null) {
            showEnvironmentTemplateDialog(file, environmentTarget, targetLine)
            return
        }
        continueOpenFileRequested(file, targetLine)
    }

    private fun showEnvironmentTemplateDialog(
        template: ProjectStore.FileNode,
        targetName: String,
        targetLine: Int?,
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_env_template_title)
            .setMessage(
                getString(
                    R.string.editor_env_template_message,
                    template.relativePath,
                    targetName,
                ),
            )
            .setPositiveButton(getString(R.string.editor_env_template_create, targetName)) { _, _ ->
                createEnvironmentFromTemplate(template)
            }
            .setNegativeButton(R.string.editor_env_template_open_only) { _, _ ->
                continueOpenFileRequested(template, targetLine)
            }
            .setNeutralButton(R.string.common_cancel, null)
            .show()
    }

    private fun createEnvironmentFromTemplate(template: ProjectStore.FileNode) {
        try {
            val created = projectStore.createEnvironmentFileFromTemplate(projectDocumentId, template)
            refreshTree(invalidateCache = true)
            toast(getString(R.string.editor_env_template_created, created.name))
            continueOpenFileRequested(created)
        } catch (error: Throwable) {
            showOperationError(getString(R.string.editor_env_template_create_failed), error)
        }
    }

    private fun continueOpenFileRequested(file: ProjectStore.FileNode, targetLine: Int? = null) {
        expandPathTo(file.relativePath)
        refreshTree(invalidateCache = false)
        val current = currentFile
        if (current != null && current.documentId != file.documentId && hasUnsavedChanges()) {
            AlertDialog.Builder(this).setTitle(R.string.editor_unsaved_file_title).setMessage(getString(R.string.editor_save_before_open, current.relativePath))
                .setPositiveButton(R.string.editor_save_and_open) { _, _ -> if (saveCurrentFile(false)) openFile(file, targetLine) }
                .setNegativeButton(R.string.editor_discard_changes) { _, _ -> openFile(file, targetLine) }
                .setNeutralButton(R.string.common_cancel, null).show()
            return
        }
        openFile(file, targetLine)
    }

    private fun openFile(file: ProjectStore.FileNode, targetLine: Int? = null) {
        try {
            val content = projectStore.readProjectTextFile(file)
            currentFile = file
            savedText = content
            editor.setText(content)
            editor.setSelection(targetLine?.let { lineStartOffset(content, it) }?.coerceIn(0, content.length) ?: 0)
            pathText.text = getString(R.string.editor_file_path, file.relativePath)
            stateText.text = getString(R.string.editor_file_stats, content.length)
            stateText.setTextColor(Color.rgb(150, 157, 169))
            projectStore.recordRecentFile(projectDocumentId, file)
            showEditor()
            if (findPanel.visibility == View.VISIBLE && findInput.text.isNotEmpty()) {
                refreshFindMatches(selectCurrent = true)
            }
        } catch (error: Throwable) {
            showOperationError(getString(R.string.editor_open_failed), error)
        }
    }

    private fun saveCurrentFile(showToast: Boolean = true): Boolean {
        val file = currentFile ?: run {
            if (showToast) toast(getString(R.string.editor_open_file_first))
            return false
        }
        return try {
            val content = editor.text.toString()
            projectStore.writeProjectTextFile(file, content)
            savedText = content
            stateText.text = getString(R.string.editor_saved_stats, content.length)
            stateText.setTextColor(Color.rgb(170, 224, 190))
            projectStore.recordRecentFile(projectDocumentId, file)
            if (showToast) toast(getString(R.string.editor_file_saved, file.name))
            true
        } catch (error: Throwable) {
            stateText.text = getString(R.string.editor_save_failed)
            stateText.setTextColor(Color.rgb(240, 184, 120))
            showOperationError(getString(R.string.editor_save_failed), error)
            false
        }
    }

    private fun showFindPanel() {
        if (currentFile == null || editorPanel.visibility != View.VISIBLE) {
            toast(getString(R.string.editor_open_file_first))
            return
        }
        findPanel.visibility = View.VISIBLE
        if (findInput.text.isEmpty()) {
            val start = editor.selectionStart.coerceAtLeast(0)
            val end = editor.selectionEnd.coerceAtLeast(start)
            if (end > start && end - start <= MAX_FIND_PREFILL_LENGTH) {
                val selected = editor.text.substring(start, end)
                if ('\n' !in selected && '\r' !in selected) findInput.setText(selected)
            }
        }
        refreshFindMatches(selectCurrent = true)
        findInput.requestFocus()
        findInput.setSelection(findInput.text.length)
    }

    private fun hideFindPanel() {
        findPanel.visibility = View.GONE
        findMatches = emptyList()
        findMatchIndex = -1
        editor.requestFocus()
    }

    private fun refreshFindMatches(selectCurrent: Boolean) {
        if (!::editor.isInitialized || !::findInput.isInitialized) return
        val query = findInput.text.toString()
        if (query.isEmpty()) {
            findMatches = emptyList()
            findMatchIndex = -1
            findStatus.text = getString(R.string.editor_find_enter_query)
            findStatus.setTextColor(Color.rgb(150, 157, 169))
            return
        }
        val previousStart = findMatches.getOrNull(findMatchIndex)?.start
            ?: editor.selectionStart.coerceAtLeast(0)
        findMatches = EditorTextSearch.findAll(
            text = editor.text.toString(),
            query = query,
            caseSensitive = findCaseSensitive.isChecked,
        )
        if (findMatches.isEmpty()) {
            findMatchIndex = -1
            findStatus.text = getString(R.string.editor_find_no_matches)
            findStatus.setTextColor(Color.rgb(240, 184, 120))
            return
        }
        findMatchIndex = EditorTextSearch.indexAtOrAfter(findMatches, previousStart)
        updateFindStatus()
        if (selectCurrent) selectCurrentFindMatch()
    }

    private fun moveFindMatch(forward: Boolean) {
        if (findInput.text.isEmpty()) {
            findInput.requestFocus()
            return
        }
        refreshFindMatches(selectCurrent = false)
        if (findMatches.isEmpty()) return
        findMatchIndex = EditorTextSearch.stepIndex(findMatches.size, findMatchIndex, forward)
        updateFindStatus()
        selectCurrentFindMatch()
    }

    private fun updateFindStatus() {
        if (findMatchIndex !in findMatches.indices) return
        findStatus.text = getString(R.string.editor_find_status, findMatchIndex + 1, findMatches.size)
        findStatus.setTextColor(Color.rgb(170, 224, 190))
    }

    private fun selectCurrentFindMatch() {
        val match = findMatches.getOrNull(findMatchIndex) ?: return
        if (match.start !in 0..editor.text.length || match.endExclusive !in 0..editor.text.length) return
        editor.setSelection(match.start, match.endExclusive)
        editor.post { editor.bringPointIntoView(match.start) }
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply { hint = getString(R.string.editor_search_hint); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle(R.string.editor_search_title).setMessage(R.string.editor_search_message).setView(input).setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.editor_search) { _, _ -> startProjectSearch(input.text.toString()) }.show()
    }

    private fun startProjectSearch(query: String) {
        if (searchInProgress) { toast(getString(R.string.editor_search_in_progress)); return }
        if (query.isBlank()) { toast(getString(R.string.editor_search_required)); return }
        searchInProgress = true
        stateText.text = getString(R.string.editor_searching, query)
        stateText.setTextColor(Color.rgb(150, 157, 169))
        Thread {
            val result = runCatching { projectStore.searchProject(projectDocumentId, query) }
            runOnUiThread {
                searchInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { hits ->
                    stateText.text = getString(R.string.editor_search_complete, hits.size)
                    stateText.setTextColor(Color.rgb(150, 157, 169))
                    showSearchResults(query, hits)
                }.onFailure { error ->
                    stateText.text = getString(R.string.editor_search_failed)
                    stateText.setTextColor(Color.rgb(240, 184, 120))
                    showOperationError(getString(R.string.editor_search_failed), error)
                }
            }
        }.start()
    }

    private fun showSearchResults(query: String, hits: List<ProjectStore.SearchHit>) {
        if (hits.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.editor_search_no_result_title, query)).setMessage(R.string.editor_search_no_result_message).setPositiveButton(R.string.common_confirm, null).show()
            return
        }
        val openLabel = getString(R.string.editor_action_open)
        val items = hits.map { hit ->
            val location = if (hit.lineNumber == null) hit.file.relativePath else "${hit.file.relativePath}:${hit.lineNumber}"
            "$openLabel · $location\n${hit.preview}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(getString(R.string.editor_search_results, hits.size)).setItems(items) { _, which ->
            val hit = hits[which]
            openFileRequested(hit.file, hit.lineNumber)
        }.setNegativeButton(R.string.common_close, null).show()
    }

    private fun showRecentFiles() {
        val files = try {
            projectStore.listRecentFiles(projectDocumentId)
        } catch (error: Throwable) {
            showOperationError(getString(R.string.editor_recent_read_failed), error)
            return
        }
        if (files.isEmpty()) {
            AlertDialog.Builder(this).setTitle(R.string.editor_recent_files).setMessage(R.string.editor_recent_empty).setPositiveButton(R.string.common_confirm, null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.editor_recent_files).setItems(files.map { it.relativePath }.toTypedArray()) { _, which ->
            openFileRequested(files[which])
        }.setNegativeButton(R.string.common_close, null).show()
    }

    private fun showTree() {
        treePanel.visibility = View.VISIBLE
        editorPanel.visibility = View.GONE
        val current = currentFile
        if (current == null) {
            pathText.text = getString(R.string.editor_file_unopened)
            stateText.text = getString(R.string.editor_select_file)
            stateText.setTextColor(Color.rgb(150, 157, 169))
        } else {
            expandPathTo(current.relativePath)
            refreshTree(invalidateCache = false)
            pathText.text = getString(R.string.editor_current_file, current.relativePath)
            stateText.text = if (hasUnsavedChanges()) getString(R.string.editor_unsaved_return) else getString(R.string.editor_saved_switch)
            stateText.setTextColor(if (hasUnsavedChanges()) Color.rgb(240, 184, 120) else Color.rgb(150, 157, 169))
        }
    }

    private fun showEditor() {
        treePanel.visibility = View.GONE
        editorPanel.visibility = View.VISIBLE
        editor.requestFocus()
    }

    private fun insertAtCursor(value: String) {
        if (editorPanel.visibility != View.VISIBLE) return
        val start = editor.selectionStart.coerceAtLeast(0)
        editor.text.insert(start, value)
    }

    private fun indentSelectedLines() = transformSelectedLines { line -> "    $line" }

    private fun unindentSelectedLines() {
        transformSelectedLines { line ->
            when {
                line.startsWith("    ") -> line.drop(4)
                line.startsWith("\t") -> line.drop(1)
                else -> line
            }
        }
    }

    private fun transformSelectedLines(transform: (String) -> String) {
        if (editorPanel.visibility != View.VISIBLE) return
        val full = editor.text.toString()
        val rawStart = editor.selectionStart.coerceAtLeast(0)
        val rawEnd = editor.selectionEnd.coerceAtLeast(rawStart)
        val lineStart = if (rawStart == 0) 0 else full.lastIndexOf('\n', rawStart - 1).let { if (it < 0) 0 else it + 1 }
        val nextBreak = full.indexOf('\n', rawEnd)
        val lineEnd = if (nextBreak < 0) full.length else nextBreak
        val selectedBlock = full.substring(lineStart, lineEnd)
        val replaced = selectedBlock.split('\n').joinToString("\n", transform = transform)
        editor.text.replace(lineStart, lineEnd, replaced)
        editor.setSelection(lineStart, (lineStart + replaced.length).coerceAtMost(editor.text.length))
    }

    private fun lineStartOffset(content: String, lineNumber: Int): Int {
        if (lineNumber <= 1) return 0
        var offset = 0
        var currentLine = 1
        while (currentLine < lineNumber) {
            val next = content.indexOf('\n', offset)
            if (next < 0) return content.length
            offset = next + 1
            currentLine += 1
        }
        return offset
    }

    private fun hasUnsavedChanges(): Boolean = currentFile != null && editor.text.toString() != savedText

    private fun finishWithUnsavedCheck() {
        if (!hasUnsavedChanges()) { finish(); return }
        val label = currentFile?.relativePath ?: getString(R.string.editor_current_file_fallback)
        AlertDialog.Builder(this).setTitle(R.string.editor_unsaved_return_title).setMessage(getString(R.string.editor_unsaved_return_message, label))
            .setPositiveButton(R.string.editor_save_and_return) { _, _ -> if (saveCurrentFile(false)) finish() }
            .setNegativeButton(R.string.editor_discard_changes) { _, _ -> finish() }
            .setNeutralButton(R.string.common_cancel, null).show()
    }

    private fun handleBackNavigation() {
        if (editorPanel.visibility == View.VISIBLE) showTree() else finishWithUnsavedCheck()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { handleBackNavigation() }

    private fun showOperationError(title: String, error: Throwable) {
        AlertDialog.Builder(this).setTitle(title).setMessage(error.message ?: error.javaClass.simpleName).setPositiveButton(R.string.common_confirm, null).show()
    }

    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty().ifBlank { "?" }

    private fun smallButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setOnClickListener { action() }
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun text(value: String, size: Float, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.START
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun hint(value: String): TextView = text(value, 12f, false).apply {
        setTextColor(Color.rgb(150, 157, 169))
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_PROJECT_DOCUMENT_ID = "project_document_id"
        private const val MAX_VISIBLE_TREE_NODES = 1500
        private const val MAX_FIND_PREFILL_LENGTH = 128
        private const val STATE_EXPANDED_DIRECTORIES = "expanded_directories"
        private const val STATE_TREE_FIRST = "tree_first"
        private const val STATE_TREE_TOP = "tree_top"
    }
}
