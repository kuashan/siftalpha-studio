package com.siftalpha.studio.runtime

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Small, non-secret persistence layer for lifecycle recovery.
 *
 * It stores only project identity-derived keys, the last typed state, environment readiness, and
 * a bounded redacted failure reason. Persisted active states are hints only and are reconciled by
 * a real STATUS command when the Activity returns to the foreground.
 */
class RuntimeLifecycleStore(context: Context) {
    data class Snapshot(
        val environmentReady: Boolean?,
        val runtimeState: RuntimeState,
        val failureReason: String?,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(projectKey: String): Snapshot {
        val env = when {
            prefs.getBoolean(key(projectKey, FIELD_ENV_PRESENT), false) ->
                prefs.getBoolean(key(projectKey, FIELD_ENV_VALUE), false)
            else -> null
        }
        val state = prefs.getString(key(projectKey, FIELD_STATE), null)
            ?.let { token -> runCatching { RuntimeState.valueOf(token) }.getOrNull() }
            ?: RuntimeState.UNKNOWN
        return Snapshot(
            environmentReady = env,
            runtimeState = state,
            failureReason = prefs.getString(key(projectKey, FIELD_FAILURE), null),
        )
    }

    fun write(
        projectKey: String,
        environmentReady: Boolean?,
        runtimeState: RuntimeState,
        failureReason: String?,
    ) {
        val editor = prefs.edit()
            .putString(key(projectKey, FIELD_STATE), runtimeState.name)
        if (environmentReady == null) {
            editor.remove(key(projectKey, FIELD_ENV_PRESENT))
                .remove(key(projectKey, FIELD_ENV_VALUE))
        } else {
            editor.putBoolean(key(projectKey, FIELD_ENV_PRESENT), true)
                .putBoolean(key(projectKey, FIELD_ENV_VALUE), environmentReady)
        }
        if (failureReason.isNullOrBlank()) {
            editor.remove(key(projectKey, FIELD_FAILURE))
        } else {
            editor.putString(key(projectKey, FIELD_FAILURE), failureReason.trim().take(MAX_FAILURE_LENGTH))
        }
        editor.apply()
    }

    fun clear(projectKey: String) {
        prefs.edit()
            .remove(key(projectKey, FIELD_STATE))
            .remove(key(projectKey, FIELD_ENV_PRESENT))
            .remove(key(projectKey, FIELD_ENV_VALUE))
            .remove(key(projectKey, FIELD_FAILURE))
            .apply()
    }

    private fun key(projectKey: String, suffix: String): String =
        "project:\${digest(projectKey)}:\$suffix"

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val PREFS_NAME = "siftalpha_runtime_lifecycle_v1"
        private const val FIELD_STATE = "state"
        private const val FIELD_ENV_PRESENT = "env_present"
        private const val FIELD_ENV_VALUE = "env_value"
        private const val FIELD_FAILURE = "failure"
        private const val MAX_FAILURE_LENGTH = 240
    }
}
