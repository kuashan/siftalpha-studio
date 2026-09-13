package com.siftalpha.studio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Global browser preference used by Settings and Runtime Center. */
object StudioBrowser {
    data class Target(
        val label: String,
        val packageName: String,
    )

    private const val PREFS = "studio_browser"
    private const val KEY_PACKAGE_NAME = "package_name"

    fun selectedPackage(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PACKAGE_NAME, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun selectedTarget(context: Context, actualUri: Uri = DEFAULT_URI): Target? {
        val packageName = selectedPackage(context) ?: return null
        return discoverInstalled(context, actualUri).firstOrNull { it.packageName == packageName }
    }

    fun selectedLabel(context: Context): String? =
        selectedTarget(context)?.label

    fun remember(context: Context, target: Target) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGE_NAME, target.packageName)
            .apply()
    }

    @Suppress("DEPRECATION")
    fun discoverInstalled(context: Context, actualUri: Uri = DEFAULT_URI): List<Target> {
        val packageManager = context.packageManager
        val candidates = linkedMapOf<String, Target>()
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
                    candidates.putIfAbsent(packageName, Target(label, packageName))
                }
        }

        return candidates.values
            .filter { target ->
                val actualIntent = Intent(Intent.ACTION_VIEW, actualUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage(target.packageName)
                }
                packageManager.resolveActivity(actualIntent, PackageManager.MATCH_DEFAULT_ONLY) != null
            }
            .sortedBy { it.label.lowercase() }
    }

    private val DEFAULT_URI: Uri = Uri.parse("http://example.com/")
}
