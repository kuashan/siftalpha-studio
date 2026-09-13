package com.siftalpha.studio.runtime

import android.content.Context

/**
 * App-private persistence for the latest validated Runtime Web URL.
 * This never writes into the user's project directory or GitHub repository.
 */
class RuntimeWebStateStore(context: Context) {

    data class Snapshot(
        val url: String?,
        val framework: String?,
        val detectedAtEpochMs: Long,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(projectKey: String): Snapshot = Snapshot(
        url = prefs.getString(key(projectKey, "url"), null),
        framework = prefs.getString(key(projectKey, "framework"), null),
        detectedAtEpochMs = prefs.getLong(key(projectKey, "detected_at"), 0L),
    )

    fun rememberUrl(projectKey: String, url: String, framework: String?) {
        prefs.edit()
            .putString(key(projectKey, "url"), url)
            .putString(key(projectKey, "framework"), framework)
            .putLong(key(projectKey, "detected_at"), System.currentTimeMillis())
            .apply()
    }

    fun clear(projectKey: String) {
        prefs.edit()
            .remove(key(projectKey, "url"))
            .remove(key(projectKey, "framework"))
            .remove(key(projectKey, "detected_at"))
            .apply()
    }

    private fun key(projectKey: String, suffix: String): String =
        "${projectKey.length}:$projectKey:$suffix"

    companion object {
        private const val PREFS_NAME = "siftalpha_runtime_web_state_v1"
    }
}
