package com.siftalpha.studio.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Project-scoped protected configuration storage.
 *
 * - Plaintext is never written to the project directory or GitHub.
 * - SharedPreferences stores AES/GCM ciphertext only.
 * - The AES key remains in Android Keystore.
 * - Values are decrypted only immediately before Runtime dispatch or while explicitly editing them.
 * - Environment variable names are metadata, not secret values; names may be enumerated for status UI.
 *
 * The original Binance-specific API remains for backward compatibility while new projects use the
 * generic environment-value API. Complete legacy Binance credentials are also surfaced by name to
 * the generic preflight layer so migration never asks the user to re-enter an already saved secret.
 */
class ProjectSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasBinanceSecrets(projectFolderName: String): Boolean =
        runCatching {
            !read(projectFolderName, FIELD_API_KEY).isNullOrBlank() &&
                !read(projectFolderName, FIELD_API_SECRET).isNullOrBlank()
        }.getOrDefault(false)

    fun saveBinanceSecrets(projectFolderName: String, apiKey: String, apiSecret: String) {
        requireProject(projectFolderName)
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        require(apiSecret.isNotBlank()) { "API Secret 不能为空" }
        prefs.edit()
            .putString(prefKey(projectFolderName, FIELD_API_KEY), encrypt(apiKey.trim()))
            .putString(prefKey(projectFolderName, FIELD_API_SECRET), encrypt(apiSecret.trim()))
            .apply()
    }

    fun clearBinanceSecrets(projectFolderName: String) {
        prefs.edit()
            .remove(prefKey(projectFolderName, FIELD_API_KEY))
            .remove(prefKey(projectFolderName, FIELD_API_SECRET))
            .apply()
    }

    fun hasEnvironmentValue(projectFolderName: String, name: String): Boolean {
        val normalized = validateEnvName(name)
        if (normalized == LEGACY_BINANCE_API_KEY_ENV || normalized == LEGACY_BINANCE_API_SECRET_ENV) {
            val generic = read(projectFolderName, envField(normalized))
            if (!generic.isNullOrBlank()) return true
            if (hasBinanceSecrets(projectFolderName)) return true
        }
        return runCatching {
            !read(projectFolderName, envField(normalized)).isNullOrBlank()
        }.getOrDefault(false)
    }

    fun saveEnvironmentValue(projectFolderName: String, name: String, value: String) {
        requireProject(projectFolderName)
        val normalized = validateEnvName(name)
        require(value.isNotBlank()) { "$normalized 的值不能为空" }
        prefs.edit()
            .putString(prefKey(projectFolderName, envField(normalized)), encrypt(value))
            .apply()
    }

    fun clearEnvironmentValue(projectFolderName: String, name: String) {
        val normalized = validateEnvName(name)
        val editor = prefs.edit().remove(prefKey(projectFolderName, envField(normalized)))
        if (normalized == LEGACY_BINANCE_API_KEY_ENV || normalized == LEGACY_BINANCE_API_SECRET_ENV) {
            // Legacy Binance credentials were stored as an inseparable pair. Clearing either migrated
            // entry removes the pair instead of leaving a hidden orphan that could later be injected.
            editor
                .remove(prefKey(projectFolderName, FIELD_API_KEY))
                .remove(prefKey(projectFolderName, FIELD_API_SECRET))
        }
        editor.apply()
    }

    /** Returns configured variable names only; secret values are never exposed. */
    fun configuredEnvironmentKeys(projectFolderName: String): Set<String> {
        val prefix = prefKey(projectFolderName, ENV_FIELD_PREFIX)
        val result = prefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { ENV_NAME.matches(it) }
            .filter { hasEnvironmentValue(projectFolderName, it) }
            .toCollection(linkedSetOf())
        if (hasBinanceSecrets(projectFolderName)) {
            result += LEGACY_BINANCE_API_KEY_ENV
            result += LEGACY_BINANCE_API_SECRET_ENV
        }
        return result
    }

    /**
     * Returns the one-shot stdin payload for Termux RUN_COMMAND.
     *
     * Legacy-only projects keep format 1. As soon as generic project configuration exists, format 2
     * carries all configured values and also includes complete legacy Binance values so old projects
     * remain compatible during migration.
     */
    fun runtimePayload(projectFolderName: String): String? {
        val environment = linkedMapOf<String, String>()
        configuredEnvironmentKeys(projectFolderName).sorted().forEach { name ->
            read(projectFolderName, envField(name))
                ?.takeIf { it.isNotBlank() }
                ?.let { environment[name] = it }
        }

        val apiKey = read(projectFolderName, FIELD_API_KEY)?.takeIf { it.isNotBlank() }
        val apiSecret = read(projectFolderName, FIELD_API_SECRET)?.takeIf { it.isNotBlank() }

        if (environment.isEmpty()) {
            if (apiKey == null || apiSecret == null) return null
            return RuntimeSecretPayload.buildBinance(apiKey, apiSecret)
        }

        if (apiKey != null && apiSecret != null) {
            environment.putIfAbsent(LEGACY_BINANCE_API_KEY_ENV, apiKey)
            environment.putIfAbsent(LEGACY_BINANCE_API_SECRET_ENV, apiSecret)
        }
        return RuntimeSecretPayload.buildEnvironment(environment)
    }

    private fun read(projectFolderName: String, field: String): String? {
        val packed = prefs.getString(prefKey(projectFolderName, field), null) ?: return null
        return try {
            decrypt(packed)
        } catch (_: Throwable) {
            // Keystore keys can become invalid after reinstall/restore. Invalid ciphertext must not
            // block the app; remove only the unreadable entry and report it as unconfigured.
            prefs.edit().remove(prefKey(projectFolderName, field)).apply()
            null
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        val ciphertext = Base64.getEncoder().encodeToString(
            cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
        )
        return "$iv:$ciphertext"
    }

    private fun decrypt(packed: String): String {
        val parts = packed.split(':', limit = 2)
        require(parts.size == 2) { "无效的秘密密文" }
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun requireProject(projectFolderName: String) {
        require(projectFolderName.isNotBlank()) { "项目名不能为空" }
    }

    private fun validateEnvName(name: String): String {
        val normalized = name.trim()
        require(ENV_NAME.matches(normalized)) { "无效环境变量名称：$name" }
        return normalized
    }

    private fun envField(name: String): String = "$ENV_FIELD_PREFIX$name"

    private fun prefKey(projectFolderName: String, field: String): String =
        "project:$projectFolderName:$field"

    companion object {
        const val LEGACY_BINANCE_API_KEY_ENV = "SIFTALPHA_BINANCE_API_KEY"
        const val LEGACY_BINANCE_API_SECRET_ENV = "SIFTALPHA_BINANCE_API_SECRET"
        private const val PREFS_NAME = "siftalpha_project_secrets_v1"
        private const val FIELD_API_KEY = "binance_api_key"
        private const val FIELD_API_SECRET = "binance_api_secret"
        private const val ENV_FIELD_PREFIX = "env:"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "siftalpha_project_secrets_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}
