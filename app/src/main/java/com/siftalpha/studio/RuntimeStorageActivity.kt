package com.siftalpha.studio

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.siftalpha.studio.project.V04ProjectGateway
import com.siftalpha.studio.runtime.ProjectRuntimeController
import com.siftalpha.studio.runtime.RuntimeCommand
import com.siftalpha.studio.runtime.RuntimeResult
import com.siftalpha.studio.runtime.RuntimeStorageChartModel
import com.siftalpha.studio.runtime.RuntimeStorageController
import com.siftalpha.studio.runtime.RuntimeStoragePresentation
import com.siftalpha.studio.runtime.RuntimeStorageSizeFormatter
import com.siftalpha.studio.runtime.TermuxBackend
import com.siftalpha.studio.runtime.TermuxResultBus

class RuntimeStorageActivity : StudioActivity() {

    private sealed interface PendingAction {
        object Snapshot : PendingAction
        data class CleanProject(val folderName: String) : PendingAction
        data class CleanOrphan(val runtimeId: String) : PendingAction
        data class CleanAllOrphans(val count: Int) : PendingAction
        object ClearDownloadCache : PendingAction
        object ClearPipCache : PendingAction
        object ClearNpmCache : PendingAction
        object ClearAptCache : PendingAction
        object ClearToolchains : PendingAction
        object UninstallUbuntu : PendingAction
    }

    private lateinit var backend: TermuxBackend
    private lateinit var gateway: V04ProjectGateway
    private lateinit var runtime: ProjectRuntimeController
    private lateinit var storage: RuntimeStorageController
    private lateinit var overviewContainer: LinearLayout
    private lateinit var storageChart: RuntimeStorageChartView
    private lateinit var projectsContainer: LinearLayout
    private lateinit var orphansContainer: LinearLayout
    private lateinit var sharedRuntimeContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var outputText: TextView

    private val pageContainers = linkedMapOf<String, LinearLayout>()
    private val tabButtons = linkedMapOf<String, Button>()
    private val familySummaryContainers = linkedMapOf<String, LinearLayout>()
    private val familyProjectsContainers = linkedMapOf<String, LinearLayout>()
    private val projectCards = linkedMapOf<String, View>()
    private val pending = mutableMapOf<Int, PendingAction>()
    private var projectCache: Map<String, V04ProjectGateway.RuntimeProject> = emptyMap()
    private var selectedPageId: String = PAGE_OVERVIEW

    private val resultListener: (RuntimeResult) -> Unit = { result ->
        runOnUiThread {
            val action = pending[result.executionId] ?: return@runOnUiThread
            pending.remove(result.executionId)
            TermuxResultBus.consume(result.executionId)
            handleResult(action, result)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPageId = savedInstanceState
            ?.getString(STATE_SELECTED_PAGE)
            ?.takeIf(::isKnownPageId)
            ?: PAGE_OVERVIEW
        backend = TermuxBackend(this)
        gateway = V04ProjectGateway(this)
        runtime = ProjectRuntimeController(gateway)
        storage = RuntimeStorageController(gateway)
        setContentView(buildUi())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_PAGE, selectedPageId)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        TermuxResultBus.addListener(resultListener)
        pending.keys.toList().forEach { id ->
            TermuxResultBus.consume(id)?.let(resultListener)
        }
        refreshStorage()
    }

    override fun onStop() {
        TermuxResultBus.removeListener(resultListener)
        super.onStop()
    }

    private fun buildUi(): View {
        pageContainers.clear()
        tabButtons.clear()
        familySummaryContainers.clear()
        familyProjectsContainers.clear()
        projectCards.clear()

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 19, 24)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(30))
        }
        scroll.addView(root)

        root.addView(text(getString(R.string.storage_title), 25f, true).apply { setTextColor(Color.WHITE) })
        root.addView(text(getString(R.string.storage_subtitle), 13f, false).apply {
            setTextColor(Color.rgb(160, 166, 178))
            setPadding(0, dp(2), 0, dp(8))
        })
        root.addView(button(getString(R.string.storage_back)) { finish() })
        root.addView(button(getString(R.string.storage_refresh)) { refreshStorage() })
        root.addView(text(getString(R.string.storage_source_preserved), 12.5f, false).apply {
            setTextColor(Color.rgb(170, 204, 235))
            setPadding(0, dp(5), 0, dp(5))
        })

        statusText = text(getString(R.string.storage_scanning), 13f, false).apply {
            setTextColor(Color.rgb(190, 194, 204))
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(statusText)
        root.addView(buildPageSwitcher())

        val overviewPage = pageContainer()
        pageContainers[PAGE_OVERVIEW] = overviewPage
        root.addView(overviewPage)

        overviewPage.addView(section(getString(R.string.storage_section_overview)))
        overviewContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        overviewPage.addView(overviewContainer)

        overviewPage.addView(section(getString(R.string.storage_chart_title)))
        overviewPage.addView(hint(getString(R.string.storage_chart_description)))
        storageChart = RuntimeStorageChartView(this)
        overviewPage.addView(storageChart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        overviewPage.addView(section(getString(R.string.storage_section_projects)))
        projectsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        overviewPage.addView(projectsContainer)

        overviewPage.addView(section(getString(R.string.storage_section_orphans)))
        overviewPage.addView(hint(getString(R.string.storage_unassociated_help)))
        orphansContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        overviewPage.addView(orphansContainer)

        overviewPage.addView(section(getString(R.string.storage_section_shared_runtime)))
        sharedRuntimeContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        overviewPage.addView(sharedRuntimeContainer)

        RuntimeStoragePresentation.families.forEach { family ->
            val page = pageContainer()
            pageContainers[family.id] = page
            root.addView(page)
            page.addView(section(family.displayName))

            val summary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            familySummaryContainers[family.id] = summary
            page.addView(summary)

            val projects = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            familyProjectsContainers[family.id] = projects
            page.addView(projects)
        }

        outputText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.rgb(175, 181, 192))
            setBackgroundColor(Color.rgb(24, 28, 35))
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        root.addView(outputText)
        selectPage(selectedPageId)
        return scroll
    }

    private fun buildPageSwitcher(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            setPadding(0, dp(2), 0, dp(2))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        scroll.addView(row)

        fun addTab(pageId: String, label: String) {
            val tab = storageTabButton(label) { selectPage(pageId) }
            tabButtons[pageId] = tab
            row.addView(tab)
        }

        addTab(PAGE_OVERVIEW, getString(R.string.storage_section_overview))
        RuntimeStoragePresentation.families.forEach { family ->
            addTab(family.id, family.displayName)
        }
        return scroll
    }

    private fun pageContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    private fun isKnownPageId(pageId: String): Boolean =
        pageId == PAGE_OVERVIEW || RuntimeStoragePresentation.families.any { it.id == pageId }

    private fun selectPage(pageId: String) {
        selectedPageId = pageId.takeIf(::isKnownPageId) ?: PAGE_OVERVIEW
        pageContainers.forEach { (id, page) ->
            page.visibility = if (id == selectedPageId) View.VISIBLE else View.GONE
        }
        tabButtons.forEach { (id, tab) ->
            val selected = id == selectedPageId
            tab.backgroundTintList = ColorStateList.valueOf(
                if (selected) Color.rgb(57, 102, 176) else Color.rgb(58, 62, 70),
            )
            tab.setTextColor(if (selected) Color.WHITE else Color.rgb(205, 209, 218))
            tab.alpha = if (selected) 1f else 0.86f
        }
    }

    private fun refreshStorage() {
        if (!ensureBackendAccess()) return
        val projects = if (gateway.rootUri() == null) {
            emptyList()
        } else {
            runCatching { gateway.projects() }.getOrDefault(emptyList())
        }
        projectCache = projects.associateBy { it.folderName }
        statusText.text = getString(R.string.storage_scanning)
        outputText.visibility = View.GONE
        send(storage.snapshot(projects), PendingAction.Snapshot)
    }

    private fun handleResult(action: PendingAction, result: RuntimeResult) {
        val combined = (result.stdout + "\n" + result.stderr).trim()
        outputText.text = combined
        if (!result.successful) {
            when {
                "SIFTALPHA_STORAGE_ERROR=ORPHAN_RUNTIME_IN_USE" in combined ->
                    showError(getString(R.string.storage_orphan_runtime_in_use))
                "SIFTALPHA_STORAGE_ERROR=RUNTIME_IN_USE" in combined ->
                    showError(getString(R.string.storage_runtime_in_use))
                "SIFTALPHA_STORAGE_ERROR=PROOT_DISTRO_MISSING" in combined ->
                    showError(getString(R.string.storage_proot_missing))
                else -> showError(
                    getString(
                        R.string.storage_command_failed,
                        combined.takeLast(1200).ifBlank { result.exitCode.toString() },
                    ),
                )
            }
            return
        }

        when (action) {
            PendingAction.Snapshot -> renderSnapshot(RuntimeStorageController.parseSnapshot(result.stdout))
            is PendingAction.CleanProject -> {
                toast(getString(R.string.storage_clean_project_done))
                refreshStorage()
            }
            is PendingAction.CleanOrphan -> {
                toast(getString(R.string.storage_clean_orphan_done))
                refreshStorage()
            }
            is PendingAction.CleanAllOrphans -> {
                toast(getString(R.string.storage_clean_all_orphans_done))
                refreshStorage()
            }
            PendingAction.ClearDownloadCache -> {
                toast(getString(R.string.storage_clear_cache_done))
                refreshStorage()
            }
            PendingAction.ClearPipCache -> {
                toast(getString(R.string.storage_clear_pip_cache_done))
                refreshStorage()
            }
            PendingAction.ClearNpmCache -> {
                toast(getString(R.string.storage_clear_npm_cache_done))
                refreshStorage()
            }
            PendingAction.ClearAptCache -> {
                toast(getString(R.string.storage_clear_apt_cache_done))
                refreshStorage()
            }
            PendingAction.ClearToolchains -> {
                toast(getString(R.string.storage_clear_toolchains_done))
                refreshStorage()
            }
            PendingAction.UninstallUbuntu -> {
                toast(getString(R.string.storage_uninstall_done))
                refreshStorage()
            }
        }
    }

    private fun renderSnapshot(snapshot: RuntimeStorageController.Snapshot) {
        overviewContainer.removeAllViews()
        projectCards.clear()
        projectsContainer.removeAllViews()
        orphansContainer.removeAllViews()
        sharedRuntimeContainer.removeAllViews()
        familySummaryContainers.values.forEach { it.removeAllViews() }
        familyProjectsContainers.values.forEach { it.removeAllViews() }

        overviewContainer.addView(metric(getString(R.string.storage_download_cache, formatSize(snapshot.downloadCacheKb))))
        sharedRuntimeContainer.addView(button(getString(R.string.storage_clear_cache)) { confirmClearDownloadCache() }.apply {
            isEnabled = snapshot.downloadCacheKb > 0L
        })

        if (!snapshot.ubuntuInstalled) {
            statusText.text = getString(R.string.storage_not_installed)
            overviewContainer.addView(hint(getString(R.string.storage_not_installed)))
            storageChart.render(RuntimeStorageChartModel.empty(), chartTexts())
            projectsContainer.addView(hint(getString(R.string.storage_project_none)))
            orphansContainer.addView(hint(getString(R.string.storage_orphan_none)))
            RuntimeStoragePresentation.families.forEach { family ->
                familySummaryContainers[family.id]?.addView(hint(getString(R.string.storage_not_installed)))
                familyProjectsContainers[family.id]?.addView(hint(getString(R.string.storage_project_none)))
            }
            return
        }

        statusText.text = getString(R.string.storage_ubuntu_total, formatSize(snapshot.ubuntuTotalKb))
        overviewContainer.addView(metric(getString(R.string.storage_ubuntu_total, formatSize(snapshot.ubuntuTotalKb))))
        overviewContainer.addView(metric(getString(R.string.storage_ubuntu_base, formatSize(snapshot.ubuntuBaseKb))))
        overviewContainer.addView(metric(getString(R.string.storage_project_runtime_total, formatSize(snapshot.projectRuntimeKb))))
        overviewContainer.addView(metric(getString(R.string.storage_shared_runtime_data, formatSize(snapshot.sharedRuntimeDataKb))))
        overviewContainer.addView(metric(getString(R.string.storage_toolchains, formatSize(snapshot.toolchainsKb))))
        overviewContainer.addView(metric(getString(R.string.storage_pip_cache, formatSize(snapshot.pipCacheKb))))
        overviewContainer.addView(metric(getString(R.string.storage_npm_cache, formatSize(snapshot.npmCacheKb))))
        overviewContainer.addView(metric(getString(R.string.storage_apt_cache, formatSize(snapshot.aptCacheKb))))

        val chartModel = RuntimeStorageChartModel.from(snapshot.projects) { usage ->
            projectCache[usage.folderName]?.summary?.name ?: usage.folderName
        }
        storageChart.render(chartModel, chartTexts(), ::focusProjectStorageArea)

        if (snapshot.projects.isEmpty()) {
            projectsContainer.addView(hint(getString(R.string.storage_project_none)))
        } else {
            snapshot.projects.forEach { usage ->
                val project = projectCache[usage.folderName]
                val label = project?.summary?.name ?: usage.folderName
                val card = projectOverviewCard(label, usage, project)
                projectCards[projectCardKey(usage.folderName, usage.runtimeId)] = card
                projectsContainer.addView(card)
            }
        }

        renderRuntimeFamilyPages(snapshot)

        if (snapshot.orphans.isEmpty()) {
            orphansContainer.addView(hint(getString(R.string.storage_orphan_none)))
        } else {
            val orphanTotalKb = snapshot.orphans.sumOf { it.sizeKb }
            orphansContainer.addView(
                metric(
                    getString(
                        R.string.storage_orphan_summary,
                        snapshot.orphans.size,
                        formatSize(orphanTotalKb),
                    ),
                ),
            )
            orphansContainer.addView(button(getString(R.string.storage_clean_all_orphans)) {
                confirmCleanAllOrphans(snapshot.orphans)
            })
            snapshot.orphans.forEach { orphan -> orphansContainer.addView(orphanCard(orphan)) }
        }

        sharedRuntimeContainer.addView(metric(getString(R.string.storage_toolchains, formatSize(snapshot.toolchainsKb))))
        sharedRuntimeContainer.addView(button(getString(R.string.storage_clear_toolchains)) {
            confirmClearToolchains()
        }.apply { isEnabled = snapshot.toolchainsKb > 0L })
        sharedRuntimeContainer.addView(button(getString(R.string.storage_clear_pip_cache)) {
            confirmClearPipCache()
        }.apply { isEnabled = snapshot.pipCacheKb > 0L })
        sharedRuntimeContainer.addView(button(getString(R.string.storage_clear_npm_cache)) {
            confirmClearNpmCache()
        }.apply { isEnabled = snapshot.npmCacheKb > 0L })
        sharedRuntimeContainer.addView(button(getString(R.string.storage_clear_apt_cache)) {
            confirmClearAptCache()
        }.apply { isEnabled = snapshot.aptCacheKb > 0L })
        sharedRuntimeContainer.addView(button(getString(R.string.storage_uninstall_ubuntu)) {
            confirmUninstallUbuntu()
        })
    }

    private fun renderRuntimeFamilyPages(snapshot: RuntimeStorageController.Snapshot) {
        RuntimeStoragePresentation.families.forEach { family ->
            val summary = familySummaryContainers[family.id] ?: return@forEach
            val projects = familyProjectsContainers[family.id] ?: return@forEach

            when (family.id) {
                RuntimeStoragePresentation.FAMILY_PYTHON -> {
                    summary.addView(metric(getString(R.string.storage_pip_cache, formatSize(snapshot.pipCacheKb))))
                    summary.addView(button(getString(R.string.storage_clear_pip_cache)) {
                        confirmClearPipCache()
                    }.apply { isEnabled = snapshot.pipCacheKb > 0L })
                }
                RuntimeStoragePresentation.FAMILY_NODE_JS -> {
                    summary.addView(metric(getString(R.string.storage_npm_cache, formatSize(snapshot.npmCacheKb))))
                    summary.addView(button(getString(R.string.storage_clear_npm_cache)) {
                        confirmClearNpmCache()
                    }.apply { isEnabled = snapshot.npmCacheKb > 0L })
                }
            }

            val matching = snapshot.projects.mapNotNull { usage ->
                val components = RuntimeStoragePresentation.familyComponents(usage, family)
                components.takeIf { it.isNotEmpty() }?.let { usage to it }
            }
            if (matching.isEmpty()) {
                projects.addView(hint(getString(R.string.storage_project_none)))
            } else {
                matching.forEach { (usage, components) ->
                    val project = projectCache[usage.folderName]
                    val label = project?.summary?.name ?: usage.folderName
                    projects.addView(runtimeFamilyProjectCard(label, components, project))
                }
            }
        }
    }

    private fun projectOverviewCard(
        label: String,
        usage: RuntimeStorageController.ProjectUsage,
        project: V04ProjectGateway.RuntimeProject?,
    ): View {
        val box = cardBox()
        box.addView(text(getString(R.string.storage_project_env, label, formatSize(usage.sizeKb)), 14f, true).apply {
            setTextColor(Color.WHITE)
        })

        val familyNames = RuntimeStoragePresentation.familiesFor(usage).joinToString(" · ") { it.displayName }
        if (familyNames.isNotBlank()) {
            box.addView(hint(familyNames))
        }
        RuntimeStoragePresentation.overviewComponents(usage).forEach { component ->
            box.addView(componentMetric(component))
        }
        addProjectNavigation(box, project)
        box.addView(smallButton(getString(R.string.storage_clean_project)) {
            if (project != null) confirmCleanProject(project)
        }.apply {
            isEnabled = project != null && usage.sizeKb > 0L && runtime.runtimeSupported()
        })
        return box
    }

    private fun runtimeFamilyProjectCard(
        label: String,
        components: List<RuntimeStorageController.ComponentUsage>,
        project: V04ProjectGateway.RuntimeProject?,
    ): View {
        val familySizeKb = components.sumOf { it.sizeKb }
        val box = cardBox()
        box.addView(text(getString(R.string.storage_project_env, label, formatSize(familySizeKb)), 14f, true).apply {
            setTextColor(Color.WHITE)
        })
        components.forEach { component -> box.addView(componentMetric(component)) }
        addProjectNavigation(box, project)
        return box
    }

    private fun addProjectNavigation(
        box: LinearLayout,
        project: V04ProjectGateway.RuntimeProject?,
    ) {
        if (project == null) return
        box.addView(hint(getString(R.string.storage_open_project_hint)))
        box.isClickable = true
        box.isFocusable = true
        box.setOnClickListener { openProject(project) }
    }

    private fun orphanCard(orphan: RuntimeStorageController.OrphanUsage): View {
        val box = cardBox()
        box.addView(text(
            getString(R.string.storage_orphan_env, orphan.runtimeId, formatSize(orphan.sizeKb)),
            13.5f,
            true,
        ).apply { setTextColor(Color.WHITE) })
        orphan.components.forEach { component ->
            box.addView(componentMetric(component))
        }
        box.addView(smallButton(getString(R.string.storage_clean_orphan)) {
            confirmCleanOrphan(orphan)
        })
        return box
    }

    private fun componentMetric(component: RuntimeStorageController.ComponentUsage): TextView =
        metric(
            getString(
                R.string.storage_component_usage,
                componentLabel(component.componentId),
                formatSize(component.sizeKb),
            ),
        ).apply { setPadding(dp(8), dp(2), 0, dp(2)) }

    private fun componentLabel(componentId: String): String = when (componentId) {
        RuntimeStorageController.COMPONENT_PYTHON_VENV -> getString(R.string.storage_component_python_venv)
        RuntimeStorageController.COMPONENT_NODE_PRIMARY -> getString(R.string.storage_component_node_primary)
        RuntimeStorageController.COMPONENT_NODE_SUPPLEMENTAL -> getString(R.string.storage_component_node_supplemental)
        RuntimeStorageController.COMPONENT_RUNTIME_STATE -> getString(R.string.storage_component_runtime_state)
        else -> componentId
    }

    private fun chartTexts() = RuntimeStorageChartView.Texts(
        emptyMessage = getString(R.string.storage_chart_empty),
        legend = getString(R.string.storage_chart_legend),
        formatComponent = { kind, bytes ->
            val size = RuntimeStorageSizeFormatter.formatBytes(bytes)
            getString(
                when (kind) {
                    RuntimeStorageChartModel.ComponentKind.PYTHON -> R.string.storage_chart_component_python
                    RuntimeStorageChartModel.ComponentKind.NODE_JS -> R.string.storage_chart_component_node
                    RuntimeStorageChartModel.ComponentKind.OTHER -> R.string.storage_chart_component_other
                },
                size,
            )
        },
        formatRowDescription = { name, total, components ->
            getString(R.string.storage_chart_row_description, name, total, components)
        },
    )

    private fun focusProjectStorageArea(project: RuntimeStorageChartModel.Project) {
        projectCards[projectCardKey(project.folderName, project.projectRuntimeId)]?.let { card ->
            card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height), true)
            if (card.isFocusable) card.requestFocus()
            return
        }
    }

    private fun projectCardKey(folderName: String, runtimeId: String): String =
        "$runtimeId\u0000$folderName"

    private fun openProject(project: V04ProjectGateway.RuntimeProject) {
        startActivity(
            Intent(this, ProjectEditorActivity::class.java).apply {
                putExtra(ProjectEditorActivity.EXTRA_PROJECT_NAME, project.summary.name)
                putExtra(ProjectEditorActivity.EXTRA_PROJECT_DOCUMENT_ID, project.summary.documentId)
            },
        )
    }

    private fun confirmCleanProject(project: V04ProjectGateway.RuntimeProject) {
        if (!runtime.runtimeSupported()) {
            showError(runtime.runtimeUnsupportedReason())
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_clean_project_title, project.summary.name))
            .setMessage(getString(R.string.storage_clean_project_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.storage_clean_project_confirm)) { _, _ ->
                send(runtime.clean(project), PendingAction.CleanProject(project.folderName))
            }
            .show()
    }

    private fun confirmCleanOrphan(orphan: RuntimeStorageController.OrphanUsage) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_clean_orphan_title))
            .setMessage(getString(R.string.storage_clean_orphan_message, orphan.runtimeId))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.storage_clean_orphan)) { _, _ ->
                val command = runCatching { storage.cleanOrphan(orphan.runtimeId) }.getOrElse { error ->
                    showError(error.message ?: error.javaClass.simpleName)
                    return@setPositiveButton
                }
                send(command, PendingAction.CleanOrphan(orphan.runtimeId))
            }
            .show()
    }

    private fun confirmCleanAllOrphans(orphans: List<RuntimeStorageController.OrphanUsage>) {
        if (orphans.isEmpty()) return
        val totalKb = orphans.sumOf { it.sizeKb }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_clean_all_orphans_title))
            .setMessage(
                getString(
                    R.string.storage_clean_all_orphans_message,
                    orphans.size,
                    formatSize(totalKb),
                ),
            )
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.storage_clean_all_orphans_confirm)) { _, _ ->
                val command = runCatching {
                    storage.cleanAllOrphans(orphans.map { it.runtimeId })
                }.getOrElse { error ->
                    showError(error.message ?: error.javaClass.simpleName)
                    return@setPositiveButton
                }
                send(command, PendingAction.CleanAllOrphans(orphans.size))
            }
            .show()
    }

    private fun confirmClearDownloadCache() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_clear_cache_title))
            .setMessage(getString(R.string.storage_clear_cache_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_confirm)) { _, _ ->
                send(storage.clearDownloadCache(), PendingAction.ClearDownloadCache)
            }
            .show()
    }

    private fun confirmClearPipCache() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_clear_pip_cache_title))
            .setMessage(getString(R.string.storage_clear_pip_cache_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_confirm)) { _, _ ->
                send(storage.clearPipCache(), PendingAction.ClearPipCache)
            }
            .show()
    }

    private fun confirmClearNpmCache() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_clear_npm_cache_title))
            .setMessage(getString(R.string.storage_clear_npm_cache_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_confirm)) { _, _ ->
                send(storage.clearNpmCache(), PendingAction.ClearNpmCache)
            }
            .show()
    }

    private fun confirmClearAptCache() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_clear_apt_cache_title))
            .setMessage(getString(R.string.storage_clear_apt_cache_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_confirm)) { _, _ ->
                send(storage.clearAptCache(), PendingAction.ClearAptCache)
            }
            .show()
    }

    private fun confirmClearToolchains() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_clear_toolchains_title))
            .setMessage(getString(R.string.storage_clear_toolchains_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_confirm)) { _, _ ->
                send(storage.clearToolchains(), PendingAction.ClearToolchains)
            }
            .show()
    }

    private fun confirmUninstallUbuntu() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_uninstall_title))
            .setMessage(getString(R.string.storage_uninstall_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_confirm)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.storage_uninstall_confirm_title))
                    .setMessage(getString(R.string.storage_uninstall_confirm_message))
                    .setNegativeButton(getString(R.string.common_cancel), null)
                    .setPositiveButton(getString(R.string.storage_uninstall_confirm)) { _, _ ->
                        send(storage.uninstallUbuntu(), PendingAction.UninstallUbuntu)
                    }
                    .show()
            }
            .show()
    }

    private fun send(command: RuntimeCommand, action: PendingAction) {
        if (!ensureBackendAccess()) return
        runCatching { backend.execute(command) }
            .onSuccess { id ->
                pending[id] = action
                statusText.text = getString(R.string.storage_command_sent, id)
                TermuxResultBus.consume(id)?.let(resultListener)
            }
            .onFailure { error -> showError(error.message ?: error.javaClass.simpleName) }
    }

    private fun ensureBackendAccess(): Boolean {
        if (!backend.isTermuxInstalled()) {
            showError(getString(R.string.runtime_termux_missing_message))
            return false
        }
        if (!backend.hasRunCommandPermission()) {
            showError(getString(R.string.runtime_permission_missing_message))
            return false
        }
        return true
    }

    private fun showError(message: String) {
        statusText.text = getString(R.string.storage_command_failed, message)
        outputText.visibility = View.VISIBLE
    }

    private fun formatSize(kb: Long): String {
        return RuntimeStorageSizeFormatter.formatKilobytes(kb)
    }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.rgb(24, 28, 35))
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), Color.rgb(48, 54, 64))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun metric(value: String) = text(value, 13f, false).apply {
        setTextColor(Color.rgb(190, 194, 204))
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun section(value: String) = text(value, 16f, true).apply {
        setTextColor(Color.WHITE)
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
    }

    private fun storageTabButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12.5f
        isAllCaps = false
        minWidth = dp(94)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = dp(7)
            bottomMargin = dp(4)
        }
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11.5f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setOnClickListener { action() }
    }

    private fun hint(value: String) = text(value, 12.5f, false).apply {
        setTextColor(Color.rgb(160, 166, 178))
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.START
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_OVERVIEW = "OVERVIEW"
        private const val STATE_SELECTED_PAGE = "runtime_storage_selected_page"
    }
}
