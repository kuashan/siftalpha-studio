package com.siftalpha.studio

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
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
 * `.project.json.requiredEnv` is the authoritative generic configuration contract. The older
 * explicit `secrets.binanceApi=true` contract is bridged into the same model for backward
 * compatibility. `.env.example` and static Python inspection can surface useful candidates, but
 * they do not block the first run. A variable explicitly reported as missing by the running
 * project becomes required for the current repair cycle. Values saved through Studio stay in
 * Android Keystore-backed storage and are injected at runtime.
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
        val runtimeConfigurationDiscovered: Boolean,
    ) {
        val allCandidateNames: List<String>
            get() = (
                profile.credentialCandidates +
                    profile.configurationCandidates.map { it.name } +
                    runtimeHints
            )
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
    private val runtimeDiscoveryFolders = mutableSetOf<String>()
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
            runtimeConfigurationDiscovered = folderName in runtimeDiscoveryFolders,
        )
    }

    fun statusText(snapshot: Snapshot): String = when {
        !snapshot.runtimeConfigurationDiscovered && snapshot.preflight.missingRequired.isNotEmpty() ->
            activity.getString(R.string.runtime_configuration_status_pending_discovery)
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

    fun statusIsWarning(snapshot: Snapshot): Boolean =
        snapshot.runtimeConfigurationDiscovered && snapshot.preflight.missingRequired.isNotEmpty()

    fun clearRuntimeDiscovery(folderName: String) {
        runtimeHints.remove(folderName)
        runtimeDiscoveryFolders.remove(folderName)
    }

    fun showConfiguration(
        projectName: String,
        projectDocumentId: String,
        folderName: String,
        onCompleted: () -> Unit = {},
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

        val pendingItems = items.filterNot { isConfigured(snapshot, it.name) }
        if (pendingItems.isNotEmpty()) {
            showConfigurationWizard(
                projectName = projectName,
                folderName = folderName,
                items = pendingItems,
                onCompleted = onCompleted,
            )
            return
        }

        showConfigurationList(
            projectName = projectName,
            projectDocumentId = projectDocumentId,
            folderName = folderName,
            snapshot = snapshot,
            items = items,
            onCompleted = onCompleted,
        )
    }

    private fun showConfigurationList(
        projectName: String,
        projectDocumentId: String,
        folderName: String,
        snapshot: Snapshot,
        items: List<DisplayItem>,
        onCompleted: () -> Unit,
    ) {
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
                    onSaved = onCompleted,
                )
            }
            .setNegativeButton(R.string.common_close, null)
            .show()
    }

    private fun showConfigurationWizard(
        projectName: String,
        folderName: String,
        items: List<DisplayItem>,
        index: Int = 0,
        onCompleted: () -> Unit = {},
    ) {
        if (index >= items.size) {
            onChanged()
            onCompleted()
            toast(activity.getString(R.string.runtime_configuration_completed))
            return
        }

        val item = items[index]
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.runtime_configuration_value_hint, item.name)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or if (item.secret) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
        }
        val description = buildString {
            if (item.description.isNotBlank()) append(item.description)
            if (item.required) {
                if (isNotEmpty()) append("\n\n")
                append(activity.getString(R.string.runtime_configuration_required_section))
            } else {
                if (isNotEmpty()) append("\n\n")
                append(activity.getString(R.string.runtime_configuration_wizard_optional))
            }
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(
                activity.getString(
                    R.string.runtime_configuration_step_title,
                    projectName,
                    index + 1,
                    items.size,
                ),
            )
            .setMessage(description)
            .setView(input)
            .setPositiveButton(R.string.runtime_configuration_save_and_next, null)
            .setNegativeButton(
                if (item.required) R.string.common_cancel else R.string.runtime_configuration_skip,
                null,
            )
        val dialog = builder.create()

        dialog.setOnShowListener {
            if (!item.required) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    dialog.dismiss()
                    showConfigurationWizard(
                        projectName = projectName,
                        folderName = folderName,
                        items = items,
                        index = index + 1,
                        onCompleted = onCompleted,
                    )
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (value.isBlank()) {
                    toast(activity.getString(R.string.runtime_configuration_blank_value, item.name))
                    return@setOnClickListener
                }
                runCatching { store.saveEnvironmentValue(folderName, item.name, value) }
                    .onSuccess {
                        dialog.dismiss()
                        showConfigurationWizard(
                            projectName = projectName,
                            folderName = folderName,
                            items = items,
                            index = index + 1,
                            onCompleted = onCompleted,
                        )
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

    /** Returns true when a high-confidence runtime configuration finding was shown. */
    fun showRuntimeFindingIfAny(
        projectName: String,
        projectDocumentId: String,
        folderName: String,
        output: String,
        onConfigurationCompleted: () -> Unit = {},
    ): Boolean {
        val finding = RuntimeConfigurationDiagnostic.inspect(output)
        if (!finding.hasActionableFinding) return false

        runtimeDiscoveryFolders += folderName

        if (finding.missingEnvironmentNames.isNotEmpty()) {
            val hints = runtimeHints.getOrPut(folderName) { linkedSetOf() }
            hints += finding.missingEnvironmentNames
            val names = finding.missingEnvironmentNames.joinToString("\n") { "• $it" }
            AlertDialog.Builder(activity)
                .setTitle(R.string.runtime_configuration_runtime_missing_title)
                .setMessage(activity.getString(R.string.runtime_configuration_runtime_missing_message, names))
                .setNegativeButton(R.string.common_close, null)
                .setPositiveButton(R.string.runtime_configuration_button) { _, _ ->
                    showConfiguration(
                        projectName = projectName,
                        projectDocumentId = projectDocumentId,
                        folderName = folderName,
                        onCompleted = onConfigurationCompleted,
                    )
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
                showConfiguration(
                    projectName = projectName,
                    projectDocumentId = projectDocumentId,
                    folderName = folderName,
                    onCompleted = onConfigurationCompleted,
                )
            }
            .show()
        onChanged()
        return true
    }

    private fun buildItems(snapshot: Snapshot): List<DisplayItem> {
        val result = linkedMapOf<String, DisplayItem>()
        snapshot.profile.requirements.forEach { requirement ->
            result.putIfAbsent(requirement.name, DisplayItem(
                name = requirement.name,
                secret = requirement.secret,
                required = requirement.required,
                description = requirement.description,
            ))
        }
        snapshot.profile.configurationCandidates.forEach { requirement ->
            result.putIfAbsent(requirement.name, DisplayItem(
                name = requirement.name,
                secret = requirement.secret,
                required = requirement.required,
                description = requirement.description,
            ))
        }
        snapshot.allCandidateNames.forEach { name ->
            result.putIfAbsent(name, DisplayItem(
                name = name,
                secret = true,
                required = false,
                description = "",
            ))
        }
        // A variable explicitly reported as missing by the running project is required for the
        // current repair cycle, even if static inspection originally classified it as optional.
        snapshot.runtimeHints.forEach { name ->
            val existing = result[name]
            result[name] = (existing ?: DisplayItem(
                name = name,
                secret = true,
                required = true,
                description = "",
            )).copy(required = true)
        }
        return result.values.toList()
    }

    private fun isConfigured(snapshot: Snapshot, name: String): Boolean =
        name in snapshot.protectedKeys || name in snapshot.profile.configuredProjectEnvKeys

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
        onSaved: () -> Unit = {},
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
                        onSaved()
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
}
