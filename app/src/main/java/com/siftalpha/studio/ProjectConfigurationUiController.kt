package com.siftalpha.studio

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.siftalpha.studio.project.LegacyProjectConfigurationBridge
import com.siftalpha.studio.project.ProjectConfigurationInspector
import com.siftalpha.studio.project.ProjectSecretPolicyInspector
import com.siftalpha.studio.runtime.ProjectConfigurationPreflight
import com.siftalpha.studio.runtime.ProjectSecretStore
import com.siftalpha.studio.runtime.RuntimeConfigurationDiagnostic

/**
 * Product-level project configuration UX for Runtime Center.
 *
 * `.project.json.requiredEnv` is the authoritative generic pre-launch contract. The older explicit
 * `secrets.binanceApi=true` contract is bridged into the same model for backward compatibility.
 * `.env.example` and runtime diagnostics can surface useful credential candidates, but they never
 * become required by guessing. Values saved through Studio stay in Android Keystore-backed storage
 * and are injected at runtime.
 */
class ProjectConfigurationUiController(
    private val activity: Activity,
    private val inspector: ProjectConfigurationInspector,
    private val store: ProjectSecretStore,
    private val onChanged: () -> Unit,
) {

    data class Snapshot(
        val profile: ProjectConfigurationInspector.Profile,
        val protectedKeys: Set<String>,
        val preflight: ProjectConfigurationPreflight.Result,
        val runtimeHints: Set<String>,
    ) {
        val allCandidateNames: List<String>
            get() = (profile.credentialCandidates + runtimeHints)
                .filterNot { it in profile.declaredNames }
                .distinct()
                .sorted()
    }

    private data class DisplayItem(
        val name: String,
        val secret: Boolean,
        val required: Boolean,
        val description: String,
    )

    private val runtimeHints = mutableMapOf<String, LinkedHashSet<String>>()
    private val legacyPolicyInspector = ProjectSecretPolicyInspector(activity.applicationContext)

    fun snapshot(projectDocumentId: String, folderName: String): Snapshot {
        val inspected = runCatching { inspector.inspect(projectDocumentId) }
            .getOrElse { ProjectConfigurationInspector.emptyProfile() }
        val legacyPolicy = runCatching { legacyPolicyInspector.inspect(projectDocumentId) }
            .getOrElse {
                ProjectSecretPolicyInspector.Policy(ProjectSecretPolicyInspector.BinanceApiPolicy.UNSPECIFIED)
            }
        val profile = LegacyProjectConfigurationBridge.augment(inspected, legacyPolicy)
        val protected = runCatching { store.configuredEnvironmentKeys(folderName) }
            .getOrDefault(emptySet())
        return Snapshot(
            profile = profile,
            protectedKeys = protected,
            preflight = ProjectConfigurationPreflight.evaluate(profile, protected),
            runtimeHints = runtimeHints[folderName].orEmpty(),
        )
    }

    fun statusText(snapshot: Snapshot): String = when {
        snapshot.preflight.missingRequired.isNotEmpty() -> activity.getString(
            R.string.runtime_configuration_status_missing,
            snapshot.preflight.missingRequired.size,
        )
        snapshot.preflight.requiredCount > 0 ->
            activity.getString(R.string.runtime_configuration_status_ready)
        snapshot.allCandidateNames.isNotEmpty() -> activity.getString(
            R.string.runtime_configuration_status_candidates,
            snapshot.allCandidateNames.size,
        )
        else -> activity.getString(R.string.runtime_configuration_status_none)
    }

    fun statusIsWarning(snapshot: Snapshot): Boolean = snapshot.preflight.missingRequired.isNotEmpty()

    fun showConfiguration(
        projectName: String,
        projectDocumentId: String,
        folderName: String,
    ) {
        val snapshot = snapshot(projectDocumentId, folderName)
        val items = buildItems(snapshot)
        if (items.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.runtime_configuration_title, projectName))
                .setMessage(
                    activity.getString(R.string.runtime_configuration_summary) + "\n\n" +
                        activity.getString(R.string.runtime_configuration_none),
                )
                .setPositiveButton(R.string.common_close, null)
                .show()
            return
        }

        val labels = items.map { item ->
            val state = stateLabel(snapshot, item.name, item.required)
            val requirement = if (item.required) {
                activity.getString(R.string.runtime_configuration_required_section)
            } else {
                activity.getString(R.string.runtime_configuration_item_optional)
            }
            buildString {
                append(item.name)
                append(" · ")
                append(requirement)
                append('\n')
                append(state)
                if (item.description.isNotBlank()) {
                    append(" · ")
                    append(item.description)
                }
            }
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.runtime_configuration_title, projectName))
            .setMessage(activity.getString(R.string.runtime_configuration_summary))
            .setItems(labels) { _, which ->
                showValueEditor(
                    projectName = projectName,
                    projectDocumentId = projectDocumentId,
                    folderName = folderName,
                    item = items[which],
                )
            }
            .setNegativeButton(R.string.common_close, null)
            .show()
    }

    /**
     * Returns true when launch may continue immediately. If false, a blocking configuration dialog
     * is already shown. Saving that dialog invokes [onSavedAndRun] directly.
     */
    fun ensureRequiredBeforeRun(
        projectName: String,
        projectDocumentId: String,
        folderName: String,
        onSavedAndRun: () -> Unit,
    ): Boolean {
        val current = snapshot(projectDocumentId, folderName)
        if (current.preflight.ready) return true

        showPreflightDialog(
            projectName = projectName,
            projectDocumentId = projectDocumentId,
            folderName = folderName,
            missing = current.preflight.missingRequired,
            onSavedAndRun = onSavedAndRun,
        )
        return false
    }

    /** Returns true when a high-confidence runtime configuration finding was shown. */
    fun showRuntimeFindingIfAny(
        projectName: String,
        projectDocumentId: String,
        folderName: String,
        output: String,
    ): Boolean {
        val finding = RuntimeConfigurationDiagnostic.inspect(output)
        if (!finding.hasActionableFinding) return false

        if (finding.missingEnvironmentNames.isNotEmpty()) {
            val hints = runtimeHints.getOrPut(folderName) { linkedSetOf() }
            hints += finding.missingEnvironmentNames
            val names = finding.missingEnvironmentNames.joinToString("\n") { "• $it" }
            AlertDialog.Builder(activity)
                .setTitle(R.string.runtime_configuration_runtime_missing_title)
                .setMessage(activity.getString(R.string.runtime_configuration_runtime_missing_message, names))
                .setNegativeButton(R.string.common_close, null)
                .setPositiveButton(R.string.runtime_configuration_button) { _, _ ->
                    showConfiguration(projectName, projectDocumentId, folderName)
                }
                .show()
            onChanged()
            return true
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.runtime_configuration_runtime_unnamed_title)
            .setMessage(R.string.runtime_configuration_runtime_unnamed_message)
            .setNegativeButton(R.string.common_close, null)
            .setPositiveButton(R.string.runtime_configuration_button) { _, _ ->
                showConfiguration(projectName, projectDocumentId, folderName)
            }
            .show()
        return true
    }

    private fun buildItems(snapshot: Snapshot): List<DisplayItem> {
        val result = mutableListOf<DisplayItem>()
        snapshot.profile.requirements.forEach { requirement ->
            result += DisplayItem(
                name = requirement.name,
                secret = requirement.secret,
                required = requirement.required,
                description = requirement.description,
            )
        }
        snapshot.allCandidateNames.forEach { name ->
            result += DisplayItem(
                name = name,
                secret = true,
                required = false,
                description = "",
            )
        }
        return result
    }

    private fun stateLabel(snapshot: Snapshot, name: String, required: Boolean): String = when {
        name in snapshot.protectedKeys ->
            activity.getString(R.string.runtime_configuration_item_protected)
        name in snapshot.profile.configuredProjectEnvKeys ->
            activity.getString(R.string.runtime_configuration_item_project_env)
        required -> activity.getString(R.string.runtime_configuration_item_missing)
        else -> activity.getString(R.string.runtime_configuration_item_optional)
    }

    private fun showValueEditor(
        projectName: String,
        projectDocumentId: String,
        folderName: String,
        item: DisplayItem,
    ) {
        val configuredInStudio = runCatching { store.hasEnvironmentValue(folderName, item.name) }
            .getOrDefault(false)
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.runtime_configuration_value_hint, item.name)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or if (item.secret) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.runtime_configuration_edit_title, item.name))
            .setMessage(
                activity.getString(
                    if (item.secret) {
                        R.string.runtime_configuration_edit_secret_message
                    } else {
                        R.string.runtime_configuration_edit_value_message
                    },
                ),
            )
            .setView(input)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.runtime_configuration_save, null)

        if (configuredInStudio) {
            builder.setNeutralButton(R.string.runtime_configuration_clear) { _, _ ->
                runCatching { store.clearEnvironmentValue(folderName, item.name) }
                    .onSuccess {
                        toast(activity.getString(R.string.runtime_configuration_cleared, item.name))
                        onChanged()
                        showConfiguration(projectName, projectDocumentId, folderName)
                    }
                    .onFailure {
                        errorDialog(
                            activity.getString(R.string.runtime_configuration_clear_failed),
                            it.message ?: it.javaClass.simpleName,
                        )
                    }
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (value.isBlank()) {
                    toast(activity.getString(R.string.runtime_configuration_blank_value, item.name))
                    return@setOnClickListener
                }
                runCatching { store.saveEnvironmentValue(folderName, item.name, value) }
                    .onSuccess {
                        toast(activity.getString(R.string.runtime_configuration_saved, item.name))
                        dialog.dismiss()
                        onChanged()
                    }
                    .onFailure {
                        errorDialog(
                            activity.getString(R.string.runtime_configuration_save_failed),
                            it.message ?: it.javaClass.simpleName,
                        )
                    }
            }
        }
        dialog.show()
    }

    private fun showPreflightDialog(
        projectName: String,
        projectDocumentId: String,
        folderName: String,
        missing: List<ProjectConfigurationInspector.Requirement>,
        onSavedAndRun: () -> Unit,
    ) {
        val fields = linkedMapOf<ProjectConfigurationInspector.Requirement, EditText>()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.runtime_preflight_message)
                textSize = 14f
                setPadding(0, 0, 0, dp(8))
            })
            missing.forEach { requirement ->
                addView(TextView(activity).apply {
                    text = buildString {
                        append(requirement.name)
                        if (requirement.description.isNotBlank()) {
                            append("\n")
                            append(requirement.description)
                        }
                    }
                    textSize = 13f
                    setPadding(0, dp(7), 0, dp(2))
                })
                val input = EditText(activity).apply {
                    hint = activity.getString(
                        R.string.runtime_configuration_value_hint,
                        requirement.name,
                    )
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or if (requirement.secret) {
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                    } else {
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    }
                }
                fields[requirement] = input
                addView(input)
            }
            addView(TextView(activity).apply {
                text = activity.getString(R.string.runtime_preflight_project_env_note)
                textSize = 12f
                setPadding(0, dp(10), 0, 0)
            })
        }
        val scroll = ScrollView(activity).apply {
            addView(content)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("${activity.getString(R.string.runtime_preflight_title)} · $projectName")
            .setView(scroll)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.runtime_preflight_save_and_run, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = linkedMapOf<ProjectConfigurationInspector.Requirement, String>()
                for ((requirement, input) in fields) {
                    val value = input.text.toString()
                    if (value.isBlank()) {
                        toast(activity.getString(R.string.runtime_configuration_blank_value, requirement.name))
                        return@setOnClickListener
                    }
                    values[requirement] = value
                }

                val saved = mutableListOf<String>()
                runCatching {
                    values.forEach { (requirement, value) ->
                        store.saveEnvironmentValue(folderName, requirement.name, value)
                        saved += requirement.name
                    }
                }.onSuccess {
                    dialog.dismiss()
                    onChanged()
                    val verified = snapshot(projectDocumentId, folderName)
                    if (verified.preflight.ready) {
                        onSavedAndRun()
                    } else {
                        errorDialog(
                            activity.getString(R.string.runtime_preflight_save_failed),
                            verified.preflight.missingRequired.joinToString(", ") { it.name },
                        )
                    }
                }.onFailure { error ->
                    saved.forEach { name -> runCatching { store.clearEnvironmentValue(folderName, name) } }
                    errorDialog(
                        activity.getString(R.string.runtime_preflight_save_failed),
                        error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
        dialog.show()
    }

    private fun errorDialog(title: String, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.common_confirm, null)
            .show()
    }

    private fun toast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
