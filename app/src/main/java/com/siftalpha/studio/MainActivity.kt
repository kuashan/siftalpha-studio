package com.siftalpha.studio

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.siftalpha.studio.project.ProjectStore
import com.siftalpha.studio.runtime.RuntimeCommand
import com.siftalpha.studio.runtime.RuntimeResult
import com.siftalpha.studio.runtime.TermuxBackend
import com.siftalpha.studio.runtime.TermuxContract
import com.siftalpha.studio.runtime.TermuxResultBus

class MainActivity : StudioActivity() {

    private lateinit var backend: TermuxBackend
    private lateinit var projectStore: ProjectStore
    private lateinit var permissionStateText: TextView
    private lateinit var bridgeStateText: TextView
    private lateinit var permissionButton: Button
    private lateinit var projectRootText: TextView
    private lateinit var projectListContainer: LinearLayout
    private lateinit var outputText: TextView
    private var autoBridgeProbeStarted = false

    private val resultListener: (RuntimeResult) -> Unit = { result ->
        runOnUiThread {
            outputText.text = buildString {
                appendLine("executionId = ${result.executionId}")
                appendLine("exitCode = ${result.exitCode}")
                appendLine("termuxError = ${result.internalErrorCode}")
                if (result.internalErrorMessage.isNotBlank()) appendLine("errmsg = ${result.internalErrorMessage}")
                if (result.stdout.isNotBlank()) {
                    appendLine("\n--- stdout ---")
                    append(result.stdout.trimEnd())
                }
                if (result.stderr.isNotBlank()) {
                    appendLine("\n\n--- stderr ---")
                    append(result.stderr.trimEnd())
                }
            }
            val bridgeOk = result.internalErrorMessage.isBlank() &&
                (result.exitCode == 0 || result.internalErrorCode == Activity.RESULT_OK)
            bridgeStateText.text = getString(
                if (bridgeOk) R.string.home_bridge_connected else R.string.home_bridge_abnormal,
            )
            bridgeStateText.setTextColor(
                if (bridgeOk) Color.rgb(170, 224, 190) else Color.rgb(240, 184, 120),
            )
            refreshPermissionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = TermuxBackend(this)
        projectStore = ProjectStore(this)
        setContentView(buildUi())
        refreshPermissionState()
        refreshProjects()
    }

    override fun onStart() {
        super.onStart()
        TermuxResultBus.addListener(resultListener)
        refreshPermissionState()
        maybeAutoProbeBridge()
    }

    override fun onResume() {
        super.onResume()
        if (::projectListContainer.isInitialized) refreshProjects()
    }

    override fun onStop() {
        TermuxResultBus.removeListener(resultListener)
        super.onStop()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 19, 24)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
        }
        scroll.addView(root)

        root.addView(text(getString(R.string.app_name), 28f, true).apply { setTextColor(Color.WHITE) })
        root.addView(text(getString(R.string.home_subtitle, appVersionName()), 13f, false).apply {
            setTextColor(Color.rgb(165, 170, 180))
            setPadding(0, dp(2), 0, dp(8))
        })
        root.addView(button(StudioLanguage.buttonLabel(this)) { StudioLanguage.showPicker(this) })

        permissionStateText = text(getString(R.string.home_checking_termux_permission), 17f, true).apply {
            setTextColor(Color.rgb(170, 224, 190))
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(permissionStateText)

        bridgeStateText = text(getString(R.string.home_bridge_ready_probe), 14f, false).apply {
            setTextColor(Color.rgb(165, 170, 180))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(bridgeStateText)

        root.addView(section(getString(R.string.home_section_project_management)))
        projectRootText = text(getString(R.string.home_project_root_unselected), 14f, false).apply {
            setTextColor(Color.rgb(190, 194, 204))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(projectRootText)
        root.addView(button(getString(R.string.home_connect_acode)) { chooseProjectRoot(preferAcodeProjects = true) })
        root.addView(button(getString(R.string.home_choose_other_root)) { chooseProjectRoot(preferAcodeProjects = false) })
        root.addView(button(getString(R.string.home_new_python_project)) { showCreateProjectDialog() })
        root.addView(button(getString(R.string.home_runtime_center)) { startActivity(Intent(this, V04Activity::class.java)) })
        root.addView(button(getString(R.string.home_runtime_storage_manager)) { startActivity(Intent(this, RuntimeStorageActivity::class.java)) })
        root.addView(button(getString(R.string.home_terminal)) { startActivity(Intent(this, V05Activity::class.java)) })
        root.addView(button(getString(R.string.home_refresh_projects)) { refreshProjects() })

        projectListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(projectListContainer)

        root.addView(section(getString(R.string.home_section_runtime_bridge)))
        root.addView(button(getString(R.string.home_probe_environment)) { runCommand(TermuxBackend.ENVIRONMENT_PROBE) })
        root.addView(button(getString(R.string.home_test_termux)) { runCommand(TermuxBackend.CONNECTION_TEST) })
        permissionButton = button(getString(R.string.home_request_permission)) { requestRunCommandPermission() }
        root.addView(permissionButton)
        root.addView(button(getString(R.string.home_copy_termux_setup)) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("SiftAlpha Studio Termux setup", TermuxBackend.FIRST_RUN_SETUP_COMMAND),
            )
            toast(getString(R.string.home_setup_copied))
        })
        root.addView(button(getString(R.string.home_open_termux)) { openTermux() })

        root.addView(section(getString(R.string.home_section_command_output)))
        outputText = TextView(this).apply {
            text = getString(R.string.home_no_command)
            textSize = 13f
            setTextColor(Color.rgb(220, 224, 232))
            setBackgroundColor(Color.rgb(24, 28, 35))
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        root.addView(
            outputText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        root.addView(section(getString(R.string.home_section_stage)))
        root.addView(text(getString(R.string.home_stage_text, appVersionName()), 14f, false).apply {
            setTextColor(Color.rgb(190, 194, 204))
        })

        return scroll
    }

    private fun requestRunCommandPermission() {
        if (!backend.isTermuxInstalled()) {
            toast(getString(R.string.home_termux_not_found))
            refreshPermissionState()
            return
        }
        if (backend.hasRunCommandPermission()) {
            toast(getString(R.string.home_run_command_granted))
            refreshPermissionState()
            maybeAutoProbeBridge()
            return
        }
        requestPermissions(arrayOf(TermuxContract.RUN_COMMAND_PERMISSION), REQUEST_RUN_COMMAND)
    }

    private fun maybeAutoProbeBridge() {
        if (autoBridgeProbeStarted) return
        if (!backend.isTermuxInstalled()) {
            bridgeStateText.text = getString(R.string.home_bridge_unavailable)
            bridgeStateText.setTextColor(Color.rgb(240, 184, 120))
            return
        }
        if (!backend.hasRunCommandPermission()) {
            bridgeStateText.text = getString(R.string.home_bridge_wait_permission)
            bridgeStateText.setTextColor(Color.rgb(165, 170, 180))
            return
        }
        autoBridgeProbeStarted = true
        bridgeStateText.text = getString(R.string.home_bridge_detecting)
        bridgeStateText.setTextColor(Color.rgb(165, 170, 180))
        runCommand(TermuxBackend.CONNECTION_TEST)
    }

    private fun chooseProjectRoot(preferAcodeProjects: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            if (preferAcodeProjects) {
                val initialUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:AcodeProjects",
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
        if (preferAcodeProjects) toast(getString(R.string.home_locating_acode))
        startActivityForResult(intent, REQUEST_PROJECT_ROOT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECT_ROOT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val permissionFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, permissionFlags)
            projectStore.saveRootUri(uri)
            toast(getString(R.string.home_root_saved))
            refreshProjects()
        } catch (error: Throwable) {
            toast(getString(R.string.home_root_save_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun refreshProjects() {
        if (!::projectListContainer.isInitialized) return
        projectListContainer.removeAllViews()
        val rootUri = projectStore.rootUri()
        if (rootUri == null) {
            projectRootText.text = getString(R.string.home_project_root_unselected)
            projectListContainer.addView(emptyHint(getString(R.string.home_root_help)))
            return
        }
        try {
            projectRootText.text = getString(R.string.home_root_selected, projectStore.rootDisplayName())
            val projects = projectStore.listProjects()
            if (projects.isEmpty()) {
                projectListContainer.addView(emptyHint(getString(R.string.home_projects_empty)))
                return
            }
            projectListContainer.addView(text(getString(R.string.home_all_projects, projects.size), 14f, true).apply {
                setTextColor(Color.rgb(170, 224, 190))
                setPadding(0, dp(4), 0, dp(8))
            })
            projects.forEach { projectListContainer.addView(projectCard(it)) }
        } catch (error: Throwable) {
            projectRootText.text = getString(R.string.home_root_access_failed)
            projectListContainer.addView(
                emptyHint(
                    getString(R.string.home_root_read_failed, error.message ?: error.javaClass.simpleName),
                ),
            )
        }
    }

    private fun projectCard(project: ProjectStore.ProjectSummary): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 28, 35))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(48, 54, 64))
            }
        }
        box.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
        box.addView(text("📁 ${project.name}", 17f, true).apply { setTextColor(Color.WHITE) })
        if (project.description.isNotBlank()) {
            box.addView(text(project.description, 13f, false).apply {
                setTextColor(Color.rgb(190, 194, 204))
                setPadding(0, dp(3), 0, 0)
            })
        }
        box.addView(text(getString(R.string.home_source, project.source), 12f, false).apply {
            setTextColor(Color.rgb(150, 157, 169))
            setPadding(0, dp(5), 0, 0)
        })
        box.addView(text("▶ ${project.run}", 12f, false).apply {
            setTextColor(Color.rgb(150, 157, 169))
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, dp(6))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(smallButton(getString(R.string.home_open)) { openProject(project) }, weightParams())
        actions.addView(
            smallButton(getString(R.string.home_details)) { showProjectDetails(project) },
            weightParams().apply { marginStart = dp(5) },
        )
        actions.addView(
            smallButton(getString(R.string.home_delete)) { confirmDeleteProject(project) },
            weightParams().apply { marginStart = dp(5) },
        )
        box.addView(actions)
        return box
    }

    private fun openProject(project: ProjectStore.ProjectSummary) {
        startActivity(Intent(this, ProjectEditorActivity::class.java).apply {
            putExtra(ProjectEditorActivity.EXTRA_PROJECT_NAME, project.name)
            putExtra(ProjectEditorActivity.EXTRA_PROJECT_DOCUMENT_ID, project.documentId)
        })
    }

    private fun showCreateProjectDialog() {
        if (projectStore.rootUri() == null) {
            toast(getString(R.string.home_select_root_first))
            return
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.home_project_name_hint)
            setSingleLine(true)
        }
        val descriptionInput = EditText(this).apply {
            hint = getString(R.string.home_project_description_hint)
            setSingleLine(true)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(nameInput)
            addView(descriptionInput)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.home_create_project_title))
            .setView(content)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.home_create)) { _, _ ->
                val name = nameInput.text.toString().trim()
                val description = descriptionInput.text.toString().trim()
                try {
                    projectStore.createProject(name, description)
                    toast(getString(R.string.home_project_created, name))
                    refreshProjects()
                } catch (error: Throwable) {
                    toast(error.message ?: getString(R.string.home_create_failed))
                }
            }
            .show()
    }

    private fun showProjectDetails(project: ProjectStore.ProjectSummary) {
        AlertDialog.Builder(this)
            .setTitle(project.name)
            .setMessage(
                getString(
                    R.string.home_project_details,
                    project.description.ifBlank { getString(R.string.home_none) },
                    project.entry,
                    project.run,
                    project.source,
                ),
            )
            .setPositiveButton(getString(R.string.common_confirm), null)
            .show()
    }

    private fun confirmDeleteProject(project: ProjectStore.ProjectSummary) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.home_confirm_delete_title, project.name))
            .setMessage(getString(R.string.home_confirm_delete_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.home_confirm_delete)) { _, _ ->
                try {
                    projectStore.deleteProject(project)
                    toast(getString(R.string.home_project_deleted, project.name))
                    refreshProjects()
                } catch (error: Throwable) {
                    toast(getString(R.string.home_delete_failed, error.message ?: error.javaClass.simpleName))
                }
            }
            .show()
    }

    private fun runCommand(command: RuntimeCommand) {
        if (!backend.isTermuxInstalled()) {
            outputText.text = getString(R.string.home_termux_not_installed)
            bridgeStateText.text = getString(R.string.home_bridge_unavailable)
            refreshPermissionState()
            return
        }
        if (!backend.hasRunCommandPermission()) {
            outputText.text = getString(R.string.home_permission_missing_output)
            bridgeStateText.text = getString(R.string.home_bridge_wait_permission)
            refreshPermissionState()
            return
        }
        try {
            val id = backend.execute(command)
            outputText.text = getString(R.string.home_command_sent, id)
            bridgeStateText.text = getString(R.string.home_bridge_command_running)
            bridgeStateText.setTextColor(Color.rgb(165, 170, 180))
        } catch (error: Throwable) {
            outputText.text = getString(R.string.home_send_failed, error.message ?: error.javaClass.simpleName)
            bridgeStateText.text = getString(R.string.home_bridge_send_failed)
            bridgeStateText.setTextColor(Color.rgb(240, 184, 120))
        }
    }

    private fun refreshPermissionState() {
        if (!::permissionStateText.isInitialized) return
        val termux = backend.isTermuxInstalled()
        val permission = termux && backend.hasRunCommandPermission()
        permissionStateText.text = when {
            !termux -> getString(R.string.home_termux_permission_missing)
            !permission -> getString(R.string.home_termux_installed_permission_missing)
            else -> getString(R.string.home_termux_installed_permission_granted)
        }
        permissionStateText.setTextColor(
            if (!termux || !permission) Color.rgb(240, 184, 120) else Color.rgb(170, 224, 190),
        )
        if (::permissionButton.isInitialized) {
            permissionButton.text = getString(
                if (permission) R.string.home_permission_granted_button else R.string.home_request_permission,
            )
        }
    }

    private fun openTermux() {
        val launch = packageManager.getLaunchIntentForPackage(TermuxContract.PACKAGE_NAME)
        if (launch != null) startActivity(launch) else toast(getString(R.string.home_no_launchable_termux))
    }

    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "?" }

    private fun section(title: String): TextView = text(title, 16f, true).apply {
        setTextColor(Color.WHITE)
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(7) }
    }

    private fun smallButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener { action() }
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun emptyHint(value: String): TextView = text(value, 13f, false).apply {
        setTextColor(Color.rgb(165, 170, 180))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun text(value: String, size: Float, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.START
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RUN_COMMAND) {
            refreshPermissionState()
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                toast(getString(R.string.home_permission_granted_toast))
                autoBridgeProbeStarted = false
                maybeAutoProbeBridge()
            } else {
                toast(getString(R.string.home_permission_denied))
                openAppPermissionSettings()
            }
        }
    }

    private fun openAppPermissionSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    companion object {
        private const val REQUEST_RUN_COMMAND = 501
        private const val REQUEST_PROJECT_ROOT = 601
    }
}
