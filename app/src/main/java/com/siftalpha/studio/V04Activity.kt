package com.siftalpha.studio

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.siftalpha.studio.project.ProjectConfigurationInspector
import com.siftalpha.studio.project.ProjectSecretPolicyInspector
import com.siftalpha.studio.project.V04ProjectGateway
import com.siftalpha.studio.project.WebProjectInspector
import com.siftalpha.studio.runtime.ProjectRuntimeController
import com.siftalpha.studio.runtime.ProjectSecretStore
import com.siftalpha.studio.runtime.RuntimeCommand
import com.siftalpha.studio.runtime.RuntimePresentationState
import com.siftalpha.studio.runtime.RuntimeResult
import com.siftalpha.studio.runtime.RuntimeState
import com.siftalpha.studio.runtime.RuntimeWebAvailabilityTracker
import com.siftalpha.studio.runtime.RuntimeWebStateStore
import com.siftalpha.studio.runtime.RuntimeWebUiStatus
import com.siftalpha.studio.runtime.RuntimeWebUrl
import com.siftalpha.studio.runtime.TermuxBackend
import com.siftalpha.studio.runtime.TermuxResultBus

/** Import/get project -> isolated venv -> dependencies -> run/stop/status/logs. */
class V04Activity : StudioActivity() {

    private data class Pending(
        val action: ProjectRuntimeController.Action,
        val folderName: String,
        val cloneSpec: ProjectRuntimeController.GitHubCloneSpec? = null,
        val openBrowserAfterLogs: Boolean = false,
        val browserConfiguredUrl: String? = null,
        val browserFramework: String? = null,
    )

    private data class BrowserTarget(
        val label: String,
        val packageName: String,
    )

    private lateinit var backend: TermuxBackend
    private lateinit var gateway: V04ProjectGateway
    private lateinit var runtime: ProjectRuntimeController
    private lateinit var secretStore: ProjectSecretStore
    private lateinit var secretPolicyInspector: ProjectSecretPolicyInspector
    private lateinit var configurationInspector: ProjectConfigurationInspector
    private lateinit var configurationUi: ProjectConfigurationUiController
    private lateinit var webInspector: WebProjectInspector
    private lateinit var webStateStore: RuntimeWebStateStore
    private lateinit var webAvailability: RuntimeWebAvailabilityTracker
    private lateinit var projectOutputs: ProjectOutputPanelController
    private lateinit var prepareLiveProgress: PrepareLiveProgressController
    private lateinit var rootState: TextView
    private lateinit var projectList: LinearLayout
    private lateinit var output: TextView

    private val pending: MutableMap<Int, Pending>
        get() = PENDING_TASKS
    private val states: MutableMap<String, String>
        get() = RUNTIME_STATES
    private val typedStates: MutableMap<String, RuntimeState>
        get() = RUNTIME_TYPED_STATES
    private val environmentStates: MutableMap<String, Boolean>
        get() = RUNTIME_ENVIRONMENT_READY

    private val resultListener: (RuntimeResult) -> Unit = { result ->
        runOnUiThread {
            if (::prepareLiveProgress.isInitialized && prepareLiveProgress.consumeIfProbe(result)) {
                return@runOnUiThread
            }
            // A very fast Termux command can publish before the UI has registered its Pending item.
            // Never consume such an unmatched result: registerPending() will immediately reconcile it.
            val item = pending.remove(result.executionId) ?: return@runOnUiThread
            TermuxResultBus.consume(result.executionId)
            if (item.action == ProjectRuntimeController.Action.CLONE_GITHUB) {
                renderResult(result)
            } else {
                renderProjectResult(item.folderName, result)
            }
            handleResult(item, result)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clearLocalizedStateCacheIfNeeded()
        backend = TermuxBackend(this)
        gateway = V04ProjectGateway(this)
        runtime = ProjectRuntimeController(gateway)
        secretStore = ProjectSecretStore(this)
        secretPolicyInspector = ProjectSecretPolicyInspector(this)
        configurationInspector = ProjectConfigurationInspector(this)
        webInspector = WebProjectInspector(this)
        webStateStore = RuntimeWebStateStore(this)
        webAvailability = RuntimeWebAvailabilityTracker {
            if (!isFinishing && !isDestroyed && ::projectList.isInitialized) {
                refresh()
            }
        }
        projectOutputs = ProjectOutputPanelController(this)
        configurationUi = ProjectConfigurationUiController(
            activity = this,
            inspector = configurationInspector,
            store = secretStore,
        ) {
            if (!isFinishing && !isDestroyed && ::projectList.isInitialized) {
                refresh()
            }
        }
        setContentView(buildUi())
        prepareLiveProgress = PrepareLiveProgressController(
            context = this,
            backend = backend,
            gateway = gateway,
            runtime = runtime,
        ) { folderName, liveText ->
            if (::projectOutputs.isInitialized) {
                projectOutputs.write(folderName, liveText, expand = true)
            }
        }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        if (::webAvailability.isInitialized) webAvailability.resume()
        TermuxResultBus.addListener(resultListener)
        if (::prepareLiveProgress.isInitialized) prepareLiveProgress.resume()
        pending.keys.toList().forEach { id ->
            TermuxResultBus.consume(id)?.let(resultListener)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::projectList.isInitialized) refresh()
    }

    override fun onStop() {
        if (::webAvailability.isInitialized) webAvailability.pause()
        if (::prepareLiveProgress.isInitialized) prepareLiveProgress.pause()
        TermuxResultBus.removeListener(resultListener)
        super.onStop()
    }

    private fun clearLocalizedStateCacheIfNeeded() {
        val tag = StudioLanguage.current(this).tag
        if (RUNTIME_STATES_LANGUAGE_TAG != tag) {
            RUNTIME_STATES.clear()
            RUNTIME_STATES_LANGUAGE_TAG = tag
        }
    }

    private fun buildUi(): android.view.View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 19, 24)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(30))
        }
        scroll.addView(root)

        root.addView(text(getString(R.string.app_name), 25f, true).apply { setTextColor(Color.WHITE) })
        root.addView(text(getString(R.string.runtime_center_subtitle, appVersionName()), 13f, false).apply {
            setTextColor(Color.rgb(160, 166, 178))
            setPadding(0, dp(2), 0, dp(8))
        })
        root.addView(button(getString(R.string.runtime_center_back)) { finish() })

        rootState = text(getString(R.string.runtime_center_reading_root), 13f, false).apply {
            setTextColor(Color.rgb(190, 194, 204))
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(rootState)

        root.addView(section(getString(R.string.runtime_center_section_import)))
        val importRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        importRow.addView(smallButton(getString(R.string.runtime_center_import_py)) { chooseFile(false) }, weight())
        importRow.addView(
            smallButton(getString(R.string.runtime_center_import_zip)) { chooseFile(true) },
            weight().apply { marginStart = dp(5) },
        )
        importRow.addView(smallButton("GitHub") { showGitHubDialog() }, weight().apply { marginStart = dp(5) })
        root.addView(importRow)
        root.addView(button(getString(R.string.runtime_center_refresh)) { refresh() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(7)
        })

        root.addView(section(getString(R.string.runtime_center_section_system_output)))
        output = TextView(this).apply {
            text = getString(R.string.runtime_center_no_command)
            textSize = 12.5f
            setTextColor(Color.rgb(224, 228, 236))
            setBackgroundColor(Color.rgb(24, 28, 35))
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        root.addView(output)

        root.addView(section(getString(R.string.runtime_center_section_run)))
        projectList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(projectList)
        return scroll
    }

    private fun refresh() {
        if (!::projectList.isInitialized) return
        projectOutputs.beginRefresh()
        projectList.removeAllViews()
        if (gateway.rootUri() == null) {
            projectOutputs.retainOnly(emptySet())
            rootState.text = getString(R.string.runtime_center_root_unselected)
            projectList.addView(hint(getString(R.string.runtime_center_root_required)))
            return
        }

        val runtimeText = getString(
            if (runtime.runtimeSupported()) {
                R.string.runtime_center_runtime_available
            } else {
                R.string.runtime_center_runtime_files_only
            },
        )
        rootState.text = getString(R.string.runtime_center_root_connected, runtimeText)
        try {
            val projects = gateway.projects()
            projectOutputs.retainOnly(projects.map { it.folderName }.toSet())
            if (projects.isEmpty()) {
                projectList.addView(hint(getString(R.string.runtime_center_projects_empty)))
                return
            }
            projectList.addView(text(getString(R.string.runtime_center_all_projects, projects.size), 14f, true).apply {
                setTextColor(Color.rgb(170, 224, 190))
                setPadding(0, 0, 0, dp(8))
            })
            projects.forEach { projectList.addView(card(it)) }
        } catch (e: Throwable) {
            projectList.addView(
                hint(getString(R.string.runtime_center_read_projects_failed, e.message ?: e.javaClass.simpleName)),
            )
        }
    }

    private fun card(project: V04ProjectGateway.RuntimeProject): android.view.View {
        val summary = project.summary
        // Keep the legacy inspector alive for the old dedicated dialog while the generic controller
        // bridges only an explicitly REQUIRED legacy policy into its product-level preflight model.
        val secretPolicy = runCatching { secretPolicyInspector.inspect(summary.documentId) }
            .getOrElse {
                ProjectSecretPolicyInspector.Policy(ProjectSecretPolicyInspector.BinanceApiPolicy.UNSPECIFIED)
            }
        val secretsRequired = secretPolicy.binanceApi == ProjectSecretPolicyInspector.BinanceApiPolicy.REQUIRED
        val secretsConfigured = secretsRequired && runCatching {
            secretStore.hasBinanceSecrets(project.folderName)
        }.getOrDefault(false)
        val configurationSnapshot = configurationUi.snapshot(summary.documentId, project.folderName)
        val webProfile = runCatching { webInspector.inspect(summary.documentId) }
            .getOrElse { WebProjectInspector.Profile(false, null, "none", null, null) }
        val webSnapshot = webStateStore.snapshot(project.folderName)
        val typedState = typedStates[project.folderName] ?: RuntimeState.UNKNOWN
        val configuredWebUrl = webProfile.configuredLocalUrl()
        val candidateWebUrls = listOfNotNull(webSnapshot.url, configuredWebUrl).distinct()
        val reachableWebUrl = if (::webAvailability.isInitialized) {
            webAvailability.reachableUrl(project.folderName, typedState, candidateWebUrls)
        } else {
            null
        }
        val reachableWebFramework = when (reachableWebUrl) {
            webSnapshot.url -> webSnapshot.framework ?: webProfile.framework
            configuredWebUrl -> webProfile.framework ?: webSnapshot.framework
            else -> webProfile.framework ?: webSnapshot.framework
        }
        val webUiStatus = RuntimeWebUiStatus.resolve(
            profileEnabled = webProfile.enabled,
            hasKnownRuntimeUrl = !webSnapshot.url.isNullOrBlank(),
            hasConfiguredLocalUrl = configuredWebUrl != null,
            runtimeState = typedState,
            endpointReachable = reachableWebUrl != null,
        )
        val presentationState = RuntimePresentationState.resolve(
            runtimeState = typedState,
            webExpected = webProfile.enabled || !webSnapshot.url.isNullOrBlank() || configuredWebUrl != null,
            endpointReachable = reachableWebUrl != null,
        )

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 28, 35))
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), Color.rgb(48, 54, 64))
            }
        }
        box.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(9) }

        box.addView(text("📁 ${summary.name}", 16f, true).apply { setTextColor(Color.WHITE) })
        box.addView(text(getString(R.string.runtime_center_source, summary.source), 12f, false).apply {
            setTextColor(Color.rgb(150, 157, 169))
            setPadding(0, dp(3), 0, 0)
        })
        box.addView(text("▶ ${summary.run}", 12f, false).apply {
            setTextColor(Color.rgb(150, 157, 169))
            typeface = Typeface.MONOSPACE
        })

        val environmentLabel = when (environmentStates[project.folderName]) {
            true -> getString(R.string.runtime_state_env_ready)
            false -> getString(R.string.runtime_state_env_not_ready)
            null -> getString(R.string.runtime_environment_unknown)
        }
        box.addView(text(getString(R.string.runtime_environment_label, environmentLabel), 12f, false).apply {
            setTextColor(
                if (environmentStates[project.folderName] == true) {
                    Color.rgb(170, 224, 190)
                } else {
                    Color.rgb(150, 157, 169)
                },
            )
            setPadding(0, dp(2), 0, 0)
        })

        box.addView(text(configurationUi.statusText(configurationSnapshot), 12f, false).apply {
            setTextColor(
                when {
                    configurationUi.statusIsWarning(configurationSnapshot) -> Color.rgb(240, 184, 120)
                    configurationSnapshot.preflight.requiredCount > 0 -> Color.rgb(170, 224, 190)
                    configurationSnapshot.allCandidateNames.isNotEmpty() -> Color.rgb(170, 204, 235)
                    else -> Color.rgb(150, 157, 169)
                },
            )
            setPadding(0, dp(2), 0, 0)
        })

        val stateLabel = when {
            presentationState != typedState -> presentationState.uiLabel(this)
            else -> states[project.folderName]
                ?: typedState.takeIf { it != RuntimeState.UNKNOWN }?.uiLabel(this)
                ?: if (environmentStates[project.folderName] != null) {
                    getString(R.string.runtime_state_not_running)
                } else {
                    getString(R.string.runtime_state_not_checked)
                }
        }
        box.addView(text(getString(R.string.runtime_center_state, stateLabel), 12f, false).apply {
            setTextColor(Color.rgb(170, 224, 190))
            setPadding(0, dp(2), 0, 0)
        })
        box.addView(text(webProfileLabel(webUiStatus), 12f, false).apply {
            setTextColor(
                if (webProfile.enabled || !webSnapshot.url.isNullOrBlank()) {
                    Color.rgb(170, 204, 235)
                } else {
                    Color.rgb(150, 157, 169)
                },
            )
            setPadding(0, dp(2), 0, dp(5))
        })

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(smallButton(getString(R.string.runtime_button_edit)) { openEditor(project) }, weight())
        row1.addView(
            smallButton(getString(R.string.runtime_button_prepare)) { confirmPrepare(project) },
            weight().apply { marginStart = dp(5) },
        )
        row1.addView(
            smallButton(getString(R.string.runtime_button_run)) { confirmRun(project) },
            weight().apply { marginStart = dp(5) },
        )
        box.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, 0)
        }
        row2.addView(
            smallButton(getString(R.string.runtime_button_stop)) {
                dispatch(project, ProjectRuntimeController.Action.STOP)
            },
            weight(),
        )
        row2.addView(
            smallButton(getString(R.string.runtime_button_status)) {
                dispatch(project, ProjectRuntimeController.Action.STATUS)
            },
            weight().apply { marginStart = dp(5) },
        )
        row2.addView(
            smallButton(getString(R.string.runtime_button_refresh_logs)) {
                dispatch(project, ProjectRuntimeController.Action.LOGS)
            },
            weight().apply { marginStart = dp(5) },
        )
        box.addView(row2)

        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, 0)
        }
        val browserButton = smallButton(browserButtonLabel(webUiStatus)) {
            openBrowserForProject(
                folderName = project.folderName,
                runtimeState = typedState,
                url = reachableWebUrl,
                framework = reachableWebFramework,
            )
        }.apply {
            isEnabled = webUiStatus == RuntimeWebUiStatus.AVAILABLE && reachableWebUrl != null
            alpha = if (isEnabled) 1f else 0.55f
        }
        row3.addView(browserButton, weight())
        row3.addView(
            smallButton(getString(R.string.runtime_configuration_button)) {
                configurationUi.showConfiguration(
                    projectName = summary.name,
                    projectDocumentId = summary.documentId,
                    folderName = project.folderName,
                )
            },
            weight().apply { marginStart = dp(5) },
        )
        box.addView(row3)

        box.addView(smallButton(getString(R.string.runtime_button_clean)) { confirmClean(project) }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        })

        if (!project.sourceUrl.isNullOrBlank()) {
            box.addView(smallButton(getString(R.string.runtime_button_open_source)) { openSource(project.sourceUrl) }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(5) }
            })
        }
        projectOutputs.attach(project.folderName, box)
        return box
    }

    private fun webProfileLabel(uiStatus: RuntimeWebUiStatus): String =
        getString(R.string.runtime_web_label, uiStatus.uiLabel(this))

    private fun browserButtonLabel(uiStatus: RuntimeWebUiStatus): String =
        getString(R.string.runtime_browser_label, uiStatus.uiLabel(this))

    private fun openEditor(project: V04ProjectGateway.RuntimeProject) {
        startActivity(
            Intent(this, ProjectEditorActivity::class.java).apply {
                putExtra(ProjectEditorActivity.EXTRA_PROJECT_NAME, project.summary.name)
                putExtra(ProjectEditorActivity.EXTRA_PROJECT_DOCUMENT_ID, project.summary.documentId)
            },
        )
    }

    /** Legacy dedicated editor retained for migration; new project cards use the generic Configuration UI. */
    private fun showSecretsDialog(project: V04ProjectGateway.RuntimeProject) {
        val policy = runCatching { secretPolicyInspector.inspect(project.summary.documentId) }.getOrNull()
        if (policy?.binanceApi == ProjectSecretPolicyInspector.BinanceApiPolicy.NOT_REQUIRED) {
            toast(getString(R.string.runtime_api_not_required))
            return
        }
        val configured = runCatching { secretStore.hasBinanceSecrets(project.folderName) }.getOrDefault(false)
        val apiKey = EditText(this).apply {
            hint = getString(R.string.runtime_api_key_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val apiSecret = EditText(this).apply {
            hint = getString(R.string.runtime_api_secret_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
            addView(apiKey)
            addView(apiSecret)
        }
        val message = buildString {
            append(getString(R.string.runtime_api_dialog_message))
            if (configured) append(getString(R.string.runtime_api_dialog_configured))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.runtime_api_dialog_title, project.summary.name))
            .setMessage(message)
            .setView(layout)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.runtime_api_save), null)
        if (configured) {
            builder.setNeutralButton(getString(R.string.runtime_api_clear)) { _, _ ->
                runCatching { secretStore.clearBinanceSecrets(project.folderName) }
                    .onSuccess {
                        toast(getString(R.string.runtime_api_cleared))
                        refresh()
                    }
                    .onFailure {
                        errorDialog(
                            getString(R.string.runtime_api_clear_failed),
                            it.message ?: it.javaClass.simpleName,
                        )
                    }
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = apiKey.text.toString().trim()
                val secret = apiSecret.text.toString().trim()
                if (key.isBlank() || secret.isBlank()) {
                    toast(getString(R.string.runtime_api_missing_both))
                    return@setOnClickListener
                }
                runCatching { secretStore.saveBinanceSecrets(project.folderName, key, secret) }
                    .onSuccess {
                        toast(getString(R.string.runtime_api_saved))
                        dialog.dismiss()
                        refresh()
                    }
                    .onFailure {
                        errorDialog(
                            getString(R.string.runtime_api_save_failed),
                            it.message ?: it.javaClass.simpleName,
                        )
                    }
            }
        }
        dialog.show()
    }

    private fun confirmPrepare(project: V04ProjectGateway.RuntimeProject) {
        if (!ensureRuntime()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.runtime_prepare_title, project.summary.name))
            .setMessage(getString(R.string.runtime_prepare_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.runtime_prepare_start)) { _, _ ->
                dispatch(project, ProjectRuntimeController.Action.PREPARE)
            }
            .show()
    }

    private fun confirmClean(project: V04ProjectGateway.RuntimeProject) {
        if (!ensureRuntime()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.runtime_clean_title, project.summary.name))
            .setMessage(getString(R.string.runtime_clean_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.runtime_clean_confirm)) { _, _ ->
                dispatch(project, ProjectRuntimeController.Action.CLEAN)
            }
            .show()
    }

    private fun confirmRun(project: V04ProjectGateway.RuntimeProject) {
        if (!ensureRuntime()) return
        val webProfile = runCatching { webInspector.inspect(project.summary.documentId) }.getOrNull()
        val startAction = {
            dispatch(
                project = project,
                action = ProjectRuntimeController.Action.START,
                browserConfiguredUrl = webProfile?.configuredLocalUrl(),
                browserFramework = webProfile?.framework,
            )
        }

        if (!configurationUi.ensureRequiredBeforeRun(
                projectName = project.summary.name,
                projectDocumentId = project.summary.documentId,
                folderName = project.folderName,
                onSavedAndRun = startAction,
            )
        ) {
            return
        }

        val configurationNote = "\n\n${configurationUi.statusText(configurationUi.snapshot(project.summary.documentId, project.folderName))}"
        val webNote = if (webProfile?.enabled == true) {
            getString(R.string.runtime_run_web_detected)
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.runtime_run_title, project.summary.name))
            .setMessage(getString(R.string.runtime_run_message, project.summary.run, configurationNote, webNote))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.runtime_button_run)) { _, _ -> startAction() }
            .show()
    }

    private fun dispatch(
        project: V04ProjectGateway.RuntimeProject,
        action: ProjectRuntimeController.Action,
        openBrowserAfterLogs: Boolean = false,
        browserConfiguredUrl: String? = null,
        browserFramework: String? = null,
    ) {
        if (!ensureRuntime()) return
        val command = try {
            when (action) {
                ProjectRuntimeController.Action.PREPARE -> runtime.prepare(project)
                ProjectRuntimeController.Action.START -> runtime.start(project)
                ProjectRuntimeController.Action.STOP -> runtime.stop(project)
                ProjectRuntimeController.Action.STATUS -> runtime.status(project)
                ProjectRuntimeController.Action.LOGS -> runtime.logs(project)
                ProjectRuntimeController.Action.CLEAN -> runtime.clean(project)
                ProjectRuntimeController.Action.CLONE_GITHUB -> error("clone requires spec")
            }
        } catch (e: Throwable) {
            errorDialog(
                getString(R.string.runtime_command_generation_failed),
                e.message ?: getString(R.string.runtime_unavailable),
            )
            return
        }
        val id = send(command, renderToSystemOutput = false) ?: return
        if (
            ::webAvailability.isInitialized &&
            (action == ProjectRuntimeController.Action.START ||
                action == ProjectRuntimeController.Action.STOP ||
                action == ProjectRuntimeController.Action.CLEAN)
        ) {
            webAvailability.invalidate(project.folderName)
        }
        projectOutputs.write(
            project.folderName,
            getString(R.string.runtime_command_sent, id),
            expand = true,
        )
        if (action == ProjectRuntimeController.Action.PREPARE && ::prepareLiveProgress.isInitialized) {
            prepareLiveProgress.start(project.folderName, id)
        }
        val pendingItem = Pending(
            action = action,
            folderName = project.folderName,
            openBrowserAfterLogs = openBrowserAfterLogs,
            browserConfiguredUrl = browserConfiguredUrl,
            browserFramework = browserFramework,
        )
        states[project.folderName] = when (action) {
            ProjectRuntimeController.Action.PREPARE -> getString(R.string.runtime_action_preparing)
            ProjectRuntimeController.Action.START -> getString(R.string.runtime_action_starting)
            ProjectRuntimeController.Action.STOP -> getString(R.string.runtime_action_stopping)
            ProjectRuntimeController.Action.STATUS -> getString(R.string.runtime_action_checking)
            ProjectRuntimeController.Action.LOGS ->
                states[project.folderName] ?: getString(R.string.runtime_state_not_checked)
            ProjectRuntimeController.Action.CLEAN -> getString(R.string.runtime_action_cleaning)
            ProjectRuntimeController.Action.CLONE_GITHUB -> getString(R.string.runtime_action_importing)
        }
        when (action) {
            ProjectRuntimeController.Action.START -> typedStates[project.folderName] = RuntimeState.STARTING
            ProjectRuntimeController.Action.PREPARE -> typedStates[project.folderName] = RuntimeState.PREPARING
            else -> Unit
        }
        refresh()
        registerPending(id, pendingItem)
    }

    private fun registerPending(id: Int, item: Pending) {
        pending[id] = item
        // If the command finished before Pending was registered, the listener intentionally left the
        // result in TermuxResultBus. Reconcile it immediately so UI state can never remain stuck.
        TermuxResultBus.consume(id)?.let(resultListener)
    }

    private fun updateEnvironmentState(folderName: String, stdout: String): Boolean? {
        when {
            "SIFTALPHA_ENV=READY" in stdout -> environmentStates[folderName] = true
            "SIFTALPHA_ENV=NOT_READY" in stdout || "SIFTALPHA_ENV=CLEANED" in stdout ->
                environmentStates[folderName] = false
        }
        return environmentStates[folderName]
    }

    private fun showRuntimeConfigurationFinding(item: Pending, result: RuntimeResult): Boolean {
        if (!::configurationUi.isInitialized) return false
        val project = runCatching {
            gateway.projects().firstOrNull { it.folderName == item.folderName }
        }.getOrNull() ?: return false
        val text = buildString {
            append(result.stdout)
            if (result.stderr.isNotBlank()) {
                append('\n')
                append(result.stderr)
            }
        }
        return configurationUi.showRuntimeFindingIfAny(
            projectName = project.summary.name,
            projectDocumentId = project.summary.documentId,
            folderName = project.folderName,
            output = text,
        )
    }

    private fun handleResult(item: Pending, result: RuntimeResult) {
        val stdout = result.stdout
        val success = result.exitCode == 0 && result.internalErrorMessage.isBlank()
        val runtimeState = RuntimeState.fromOutput(stdout)
        if (runtimeState != RuntimeState.UNKNOWN) {
            typedStates[item.folderName] = runtimeState
        }
        val runtimeUrl = RuntimeWebUrl.extractLocalHttpUrl(stdout)
        runtimeUrl?.let { url ->
            webStateStore.rememberUrl(item.folderName, url, item.browserFramework)
        }
        updateEnvironmentState(item.folderName, stdout)

        when (item.action) {
            ProjectRuntimeController.Action.CLONE_GITHUB -> {
                if (success && "SIFTALPHA_CLONE=OK" in stdout) {
                    item.cloneSpec?.let { attachCloneMetadata(it) }
                } else {
                    states[item.folderName] = getString(R.string.runtime_github_import_failed)
                    refresh()
                    runtimeError(result)
                }
            }
            ProjectRuntimeController.Action.PREPARE -> {
                if (::prepareLiveProgress.isInitialized) prepareLiveProgress.finish(item.folderName)
                val prepared = success && "SIFTALPHA_ENV=READY" in stdout
                states[item.folderName] = if (prepared) {
                    getString(R.string.runtime_state_not_running)
                } else {
                    getString(R.string.runtime_prepare_failed)
                }
                if (prepared) {
                    environmentStates[item.folderName] = true
                    typedStates[item.folderName] = RuntimeState.UNKNOWN
                } else {
                    typedStates[item.folderName] = RuntimeState.ENVIRONMENT_ERROR
                }
                refresh()
                if (!success) runtimeError(result)
            }
            ProjectRuntimeController.Action.START -> {
                states[item.folderName] = when (runtimeState) {
                    RuntimeState.RUNNING,
                    RuntimeState.EXITED_SUCCESS,
                    RuntimeState.EXITED_ERROR,
                    RuntimeState.STOPPED_BY_USER,
                    RuntimeState.ENVIRONMENT_ERROR -> runtimeState.uiLabel(this)
                    else -> when {
                        "SIFTALPHA_STATUS=COMPLETED_OR_EXITED" in stdout ->
                            getString(R.string.runtime_run_completed)
                        success -> getString(R.string.runtime_started)
                        else -> getString(R.string.runtime_start_failed)
                    }
                }

                val sourceOrConfiguredUrl = item.browserConfiguredUrl
                if (success && runtimeState == RuntimeState.RUNNING && sourceOrConfiguredUrl != null) {
                    webStateStore.rememberUrl(item.folderName, sourceOrConfiguredUrl, item.browserFramework)
                }
                refresh()

                val configurationFindingShown = showRuntimeConfigurationFinding(item, result)
                if (!success) {
                    if (!configurationFindingShown) runtimeError(result)
                } else if (
                    runtimeState == RuntimeState.RUNNING &&
                    runtimeUrl == null &&
                    sourceOrConfiguredUrl == null
                ) {
                    val currentProject = runCatching {
                        gateway.projects().firstOrNull { it.folderName == item.folderName }
                    }.getOrNull()
                    if (currentProject != null) {
                        dispatch(
                            project = currentProject,
                            action = ProjectRuntimeController.Action.LOGS,
                            browserFramework = item.browserFramework,
                        )
                    }
                }
            }
            ProjectRuntimeController.Action.STOP -> {
                states[item.folderName] = if (success) {
                    val stoppedState = if (runtimeState == RuntimeState.UNKNOWN) {
                        RuntimeState.STOPPED_BY_USER
                    } else {
                        runtimeState
                    }
                    typedStates[item.folderName] = stoppedState
                    stoppedState.uiLabel(this)
                } else {
                    getString(R.string.runtime_stop_failed)
                }
                refresh()
                if (!success) runtimeError(result)
            }
            ProjectRuntimeController.Action.STATUS -> {
                states[item.folderName] = if (runtimeState != RuntimeState.UNKNOWN) {
                    runtimeState.uiLabel(this)
                } else if (success) {
                    getString(R.string.runtime_state_not_running)
                } else {
                    getString(R.string.runtime_detection_failed)
                }
                refresh()
                if (!success) runtimeError(result)
            }
            ProjectRuntimeController.Action.LOGS -> {
                if (runtimeState != RuntimeState.UNKNOWN) {
                    states[item.folderName] = runtimeState.uiLabel(this)
                    refresh()
                }
                if (!success) {
                    runtimeError(result)
                } else if (item.openBrowserAfterLogs) {
                    if (runtimeState != RuntimeState.RUNNING) {
                        errorDialog(
                            getString(R.string.runtime_web_not_running_title),
                            getString(R.string.runtime_web_not_running_message),
                        )
                    } else {
                        openBrowserFromRuntimeLogs(item, stdout)
                    }
                }
            }
            ProjectRuntimeController.Action.CLEAN -> {
                states[item.folderName] = getString(
                    if (success) R.string.runtime_state_not_running else R.string.runtime_clean_failed,
                )
                if (success) {
                    typedStates[item.folderName] = RuntimeState.UNKNOWN
                    environmentStates[item.folderName] = false
                }
                refresh()
                if (!success) runtimeError(result)
            }
        }
    }

    private fun openBrowserForProject(
        folderName: String,
        runtimeState: RuntimeState,
        url: String?,
        framework: String?,
    ) {
        if (runtimeState != RuntimeState.RUNNING) {
            errorDialog(
                getString(R.string.runtime_web_not_running_title),
                getString(R.string.runtime_web_not_running_message),
            )
            return
        }
        if (url == null) {
            errorDialog(
                getString(R.string.runtime_web_not_found_title),
                getString(R.string.runtime_web_not_found_running),
            )
            return
        }
        if (!::webAvailability.isInitialized) return
        webAvailability.verifyNow(folderName, url) { listening ->
            if (!listening) {
                errorDialog(
                    getString(R.string.runtime_web_not_listening_title),
                    getString(R.string.runtime_web_not_listening_message, url),
                )
                return@verifyNow
            }
            openBrowserUrl(folderName, url, framework)
        }
    }

    private fun openBrowserFromRuntimeLogs(item: Pending, stdout: String) {
        val logUrl = RuntimeWebUrl.extractLocalHttpUrl(stdout)
        val stored = webStateStore.snapshot(item.folderName)
        val url = logUrl ?: stored.url ?: item.browserConfiguredUrl
        if (url == null) {
            errorDialog(
                getString(R.string.runtime_web_not_found_title),
                getString(R.string.runtime_web_not_found_logs),
            )
            return
        }
        openBrowserForProject(
            folderName = item.folderName,
            runtimeState = RuntimeState.RUNNING,
            url = url,
            framework = item.browserFramework ?: stored.framework,
        )
    }

    private fun openBrowserUrl(folderName: String, url: String, framework: String?) {
        val validatedUrl = RuntimeWebUrl.extractLocalHttpUrl("SIFTALPHA_WEB_URL=$url")
        if (validatedUrl == null) {
            errorDialog(
                getString(R.string.runtime_web_invalid_title),
                getString(R.string.runtime_web_invalid_message, url),
            )
            return
        }
        webStateStore.rememberUrl(folderName, validatedUrl, framework)

        val uri = Uri.parse(validatedUrl)
        val browsers = discoverInstalledBrowsers(uri)
        if (browsers.isEmpty()) {
            errorDialog(
                getString(R.string.runtime_no_browser_title),
                getString(R.string.runtime_no_browser_message, validatedUrl),
            )
            return
        }

        val items = browsers.map { "${it.label}\n${it.packageName}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.runtime_choose_browser))
            .setItems(items) { _, which ->
                val target = browsers[which]
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage(target.packageName)
                }
                runCatching { startActivity(intent) }
                    .onFailure {
                        errorDialog(
                            getString(R.string.runtime_browser_failed_title),
                            getString(
                                R.string.runtime_browser_failed_message,
                                target.label,
                                target.packageName,
                                validatedUrl,
                            ),
                        )
                    }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun discoverInstalledBrowsers(actualUri: Uri): List<BrowserTarget> {
        val candidates = linkedMapOf<String, BrowserTarget>()

        val probes = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com/")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            },
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
        )

        probes.forEach { probe ->
            packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .forEach { info ->
                    val packageName = info.activityInfo?.packageName ?: return@forEach
                    val label = runCatching { info.loadLabel(packageManager).toString().trim() }
                        .getOrNull()
                        .orEmpty()
                        .ifBlank { packageName }
                    candidates.putIfAbsent(packageName, BrowserTarget(label, packageName))
                }
        }

        return candidates.values
            .filter { candidate ->
                val actualIntent = Intent(Intent.ACTION_VIEW, actualUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage(candidate.packageName)
                }
                packageManager.resolveActivity(actualIntent, PackageManager.MATCH_DEFAULT_ONLY) != null
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun send(command: RuntimeCommand, renderToSystemOutput: Boolean = true): Int? {
        if (!ensureRuntime()) return null
        return try {
            val id = backend.execute(command)
            if (renderToSystemOutput) {
                output.text = getString(R.string.runtime_command_sent, id)
            }
            id
        } catch (e: Throwable) {
            errorDialog(
                getString(R.string.runtime_command_send_failed),
                e.message ?: e.javaClass.simpleName,
            )
            null
        }
    }

    private fun formatResult(result: RuntimeResult): String = buildString {
        appendLine("executionId = ${result.executionId}")
        appendLine("exitCode = ${result.exitCode}")
        if (result.stdout.isNotBlank()) {
            appendLine("\n--- stdout ---")
            append(result.stdout.trimEnd())
        }
        if (result.stderr.isNotBlank()) {
            appendLine("\n\n--- stderr ---")
            append(result.stderr.trimEnd())
        }
        if (result.internalErrorMessage.isNotBlank()) {
            appendLine("\n\ntermuxError = ${result.internalErrorMessage}")
        }
    }

    private fun renderResult(result: RuntimeResult) {
        output.text = formatResult(result)
    }

    private fun renderProjectResult(folderName: String, result: RuntimeResult) {
        projectOutputs.write(folderName, formatResult(result), expand = true)
    }

    private fun runtimeError(result: RuntimeResult) {
        val text = (result.stdout + "\n" + result.stderr).trim()
        val message = when {
            "SIFTALPHA_ERROR=SHARED_STORAGE_UNAVAILABLE" in text ->
                getString(R.string.runtime_error_shared_storage)
            "SIFTALPHA_ERROR=PROOT_DISTRO_MISSING" in text ->
                getString(R.string.runtime_error_proot_missing)
            "SIFTALPHA_ERROR=PYTHON_MISSING" in text ->
                getString(R.string.runtime_error_python_missing)
            "SIFTALPHA_ERROR=ENV_NOT_READY" in text ->
                getString(R.string.runtime_error_env_not_ready)
            "SIFTALPHA_ERROR=TMUX_MISSING" in text ->
                getString(R.string.runtime_error_tmux_missing)
            "SIFTALPHA_ERROR=PROJECT_EXISTS" in text ->
                getString(R.string.runtime_error_project_exists)
            "SIFTALPHA_ERROR=SECRET_PAYLOAD_INVALID" in text ->
                getString(R.string.runtime_error_secret_invalid)
            "SIFTALPHA_ERROR=SECRET_DECODE_FAILED" in text ->
                getString(R.string.runtime_error_secret_decode)
            else -> text.takeLast(1600).ifBlank {
                getString(R.string.runtime_error_command_failed, result.exitCode)
            }
        }
        errorDialog(getString(R.string.runtime_operation_failed), message)
    }

    private fun chooseFile(isZip: Boolean) {
        if (gateway.rootUri() == null) {
            toast(getString(R.string.runtime_choose_root_first))
            return
        }
        val kind = if (isZip) "ZIP" else "PY"
        output.text = "IMPORT_STAGE=OPEN_PICKER\nIMPORT_KIND=$kind"
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            type = "*/*"
        }
        try {
            startActivityForResult(picker, if (isZip) REQUEST_ZIP else REQUEST_PY)
        } catch (e: Throwable) {
            output.text = "IMPORT_STAGE=PICKER_FAILED\nIMPORT_KIND=$kind\nERROR=${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            errorDialog(
                getString(R.string.runtime_picker_open_failed),
                e.message ?: e.javaClass.simpleName,
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PY && requestCode != REQUEST_ZIP) return
        if (resultCode != RESULT_OK) {
            if (::output.isInitialized) output.text = "IMPORT_STAGE=PICKER_CANCELLED"
            return
        }
        try {
            val uri = data?.data ?: error(getString(R.string.runtime_picker_missing_uri))
            val grantedFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if ((data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 && grantedFlags != 0) {
                runCatching { contentResolver.takePersistableUriPermission(uri, grantedFlags) }
            }
            val isZip = requestCode == REQUEST_ZIP
            val filename = displayName(uri) ?: if (isZip) "project.zip" else "script.py"
            output.text = "IMPORT_STAGE=FILE_SELECTED\nIMPORT_KIND=${if (isZip) "ZIP" else "PY"}\nFILE=$filename"
            showImportDialog(uri, isZip, filename)
        } catch (e: Throwable) {
            val message = "${e.javaClass.simpleName}: ${e.message ?: getString(R.string.runtime_unknown_error)}"
            if (::output.isInitialized) output.text = "IMPORT_STAGE=FILE_RESULT_FAILED\nERROR=$message"
            errorDialog(getString(R.string.runtime_picker_read_failed), message)
        }
    }

    private fun showImportDialog(uri: Uri, zip: Boolean, knownFilename: String? = null) {
        val filename = knownFilename ?: displayName(uri) ?: if (zip) "project.zip" else "script.py"
        if (zip && !filename.lowercase().endsWith(".zip")) {
            errorDialog(
                getString(R.string.runtime_choose_zip_title),
                getString(R.string.runtime_current_selection, filename),
            )
            return
        }
        if (!zip && !filename.lowercase().endsWith(".py")) {
            errorDialog(
                getString(R.string.runtime_choose_py_title),
                getString(R.string.runtime_current_selection, filename),
            )
            return
        }
        val base = if (zip) filename.substringBeforeLast('.') else filename.removeSuffix(".py")
        val name = EditText(this).apply {
            setText(suggestName(base))
            setSelection(text.length)
            setSingleLine(true)
        }
        val importLabel = getString(R.string.runtime_import_action)
        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    if (zip) R.string.runtime_import_zip_title else R.string.runtime_import_py_title,
                ),
            )
            .setMessage(getString(R.string.runtime_import_message, filename))
            .setView(name)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(importLabel) { _, _ ->
                safeUiAction(importLabel) {
                    importLocal(uri, filename, name.text.toString().trim(), zip)
                }
            }
            .show()
    }

    private fun importLocal(uri: Uri, filename: String, projectName: String, zip: Boolean) {
        if (!PROJECT_NAME.matches(projectName)) {
            errorDialog(
                getString(R.string.runtime_project_name_invalid),
                getString(R.string.runtime_project_name_rule),
            )
            return
        }
        output.text = buildString {
            appendLine("IMPORT_STAGE=IMPORTING")
            appendLine("IMPORT_KIND=${if (zip) "ZIP" else "PY"}")
            appendLine("FILE=$filename")
            append("PROJECT=$projectName")
        }
        Thread {
            try {
                val project = if (zip) {
                    gateway.importZip(uri, projectName, filename)
                } else {
                    gateway.importPython(uri, projectName, filename)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    output.text = buildString {
                        appendLine("IMPORT_STAGE=COMPLETE")
                        appendLine("PROJECT=${project.summary.name}")
                        appendLine("ENTRY=${project.summary.entry}")
                        append("SOURCE=${project.summary.source}")
                    }
                    toast(getString(R.string.runtime_project_imported, project.summary.name))
                    refresh()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val message = "${e.javaClass.simpleName}: ${e.message ?: getString(R.string.runtime_unknown_error)}"
                    output.text = "IMPORT_STAGE=FAILED\nERROR=$message"
                    errorDialog(getString(R.string.runtime_import_failed), message)
                }
            }
        }.start()
    }

    private fun showGitHubDialog() {
        if (!ensureRuntime()) return
        val url = EditText(this).apply {
            hint = "https://github.com/owner/repository"
            setSingleLine(true)
        }
        val branch = EditText(this).apply {
            setText("main")
            hint = getString(R.string.runtime_github_branch_hint)
            setSingleLine(true)
        }
        val name = EditText(this).apply {
            hint = getString(R.string.runtime_github_name_hint)
            setSingleLine(true)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
            addView(url)
            addView(branch)
            addView(name)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.runtime_github_title))
            .setMessage(getString(R.string.runtime_github_message))
            .setView(layout)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.runtime_github_start)) { _, _ ->
                try {
                    val spec = parseGitHub(url.text.toString(), branch.text.toString(), name.text.toString())
                    require(!gateway.folderExists(spec.projectName)) {
                        getString(R.string.runtime_github_project_exists, spec.projectName)
                    }
                    val id = send(runtime.cloneGitHub(spec)) ?: return@setPositiveButton
                    states[spec.projectName] = getString(R.string.runtime_github_importing)
                    output.text = getString(
                        R.string.runtime_github_importing_detail,
                        spec.sourceUrl,
                        spec.branch,
                    )
                    registerPending(
                        id,
                        Pending(ProjectRuntimeController.Action.CLONE_GITHUB, spec.projectName, spec),
                    )
                } catch (e: Throwable) {
                    errorDialog(
                        getString(R.string.runtime_github_parameters_invalid),
                        e.message ?: getString(R.string.runtime_github_cannot_import),
                    )
                }
            }
            .show()
    }

    private fun parseGitHub(
        raw: String,
        rawBranch: String,
        rawName: String,
    ): ProjectRuntimeController.GitHubCloneSpec {
        val input = raw.trim()
        require(input.isNotBlank()) { getString(R.string.runtime_github_enter_address) }
        val repoPath: String
        val cloneUrl: String
        val sourceUrl: String
        when {
            input.startsWith("https://github.com/") -> {
                val clean = input.substringBefore('?').substringBefore('#').trimEnd('/')
                repoPath = clean.removePrefix("https://github.com/").removeSuffix(".git")
                cloneUrl = if (clean.endsWith(".git")) clean else "$clean.git"
                sourceUrl = "https://github.com/$repoPath"
            }
            input.startsWith("git@github.com:") -> {
                repoPath = input.removePrefix("git@github.com:").removeSuffix(".git").trim('/')
                cloneUrl = input
                sourceUrl = "https://github.com/$repoPath"
            }
            else -> error(getString(R.string.runtime_github_only_supported))
        }
        val parts = repoPath.trim('/').split('/').filter { it.isNotBlank() }
        require(parts.size == 2) { getString(R.string.runtime_github_address_rule) }
        val branch = rawBranch.trim().ifBlank { "main" }
        val projectName = rawName.trim().ifBlank { suggestName(parts.last()) }
        require(PROJECT_NAME.matches(projectName)) { getString(R.string.runtime_project_name_rule) }
        return ProjectRuntimeController.GitHubCloneSpec(cloneUrl, sourceUrl, branch, projectName)
    }

    private fun attachCloneMetadata(spec: ProjectRuntimeController.GitHubCloneSpec) {
        output.append("\n\n${getString(R.string.runtime_registering_source)}")
        Thread {
            var project: V04ProjectGateway.RuntimeProject? = null
            var last: Throwable? = null
            for (attempt in 0 until 8) {
                try {
                    project = gateway.attachGitHubSource(spec.projectName, spec.sourceUrl, spec.branch)
                    break
                } catch (e: Throwable) {
                    last = e
                    if (attempt < 7) Thread.sleep(250)
                }
            }
            runOnUiThread {
                if (project != null) {
                    states[spec.projectName] = getString(R.string.runtime_state_not_checked)
                    typedStates[spec.projectName] = RuntimeState.UNKNOWN
                    environmentStates.remove(spec.projectName)
                    output.append("\n${getString(R.string.runtime_import_completed, spec.sourceUrl)}")
                    toast(getString(R.string.runtime_project_imported, spec.projectName))
                } else {
                    output.append(
                        "\n${getString(
                            R.string.runtime_source_metadata_failed,
                            last?.message ?: getString(R.string.runtime_unknown_error),
                        )}",
                    )
                }
                refresh()
            }
        }.start()
    }

    private fun ensureRuntime(): Boolean {
        if (gateway.rootUri() == null) {
            toast(getString(R.string.runtime_choose_root_first))
            return false
        }
        if (!runtime.runtimeSupported()) {
            errorDialog(
                getString(R.string.runtime_current_directory_unsupported),
                getString(R.string.runtime_current_directory_unsupported_message),
            )
            return false
        }
        if (!backend.isTermuxInstalled()) {
            errorDialog(
                getString(R.string.runtime_termux_missing_title),
                getString(R.string.runtime_termux_missing_message),
            )
            return false
        }
        if (!backend.hasRunCommandPermission()) {
            errorDialog(
                getString(R.string.runtime_permission_missing_title),
                getString(R.string.runtime_permission_missing_message),
            )
            return false
        }
        return true
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun suggestName(raw: String): String {
        val value = raw.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(60)
        return value.ifBlank { "imported-project" }
    }

    private fun openSource(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                errorDialog(
                    getString(R.string.runtime_open_source_failed),
                    it.message ?: url,
                )
            }
    }

    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "?" }

    private fun safeUiAction(label: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            val message = "${e.javaClass.simpleName}: ${e.message ?: getString(R.string.runtime_unknown_error)}"
            if (::output.isInitialized) output.text = "UI_ACTION_FAILED=$label\nERROR=$message"
            errorDialog(getString(R.string.runtime_ui_action_failed, label), message)
        }
    }

    private fun errorDialog(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.common_confirm), null)
                .show()
        }.onFailure {
            runCatching { toast("$title: $message") }
        }
    }

    private fun section(value: String) = text(value, 16f, true).apply {
        setTextColor(Color.WHITE)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { safeUiAction(label, action) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11.5f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setOnClickListener { safeUiAction(label, action) }
    }

    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.START
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun hint(value: String) = text(value, 12.5f, false).apply {
        setTextColor(Color.rgb(160, 166, 178))
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PY = 801
        private const val REQUEST_ZIP = 802
        private val PROJECT_NAME = Regex("^[A-Za-z0-9._-]+$")
        private val PENDING_TASKS = mutableMapOf<Int, Pending>()
        private val RUNTIME_STATES = mutableMapOf<String, String>()
        private val RUNTIME_TYPED_STATES = mutableMapOf<String, RuntimeState>()
        private val RUNTIME_ENVIRONMENT_READY = mutableMapOf<String, Boolean>()
        private var RUNTIME_STATES_LANGUAGE_TAG: String? = null
    }
}
