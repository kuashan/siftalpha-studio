package com.siftalpha.studio.runtime

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Builds the one-shot secret payload transported through Termux RUN_COMMAND stdin.
 *
 * Values are Base64 encoded so user input never becomes shell syntax. Plaintext only exists briefly
 * after Android Keystore decryption and inside Runtime's private, short-lived transfer file.
 */
object RuntimeSecretPayload {
    const val BINANCE_FORMAT_VERSION = "1"
    const val ENVIRONMENT_FORMAT_VERSION = "2"
    private const val MAX_ENV_VALUES = 64
    private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** Backward-compatible payload for the original Binance-specific Studio flow. */
    fun buildBinance(apiKey: String, apiSecret: String): String {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        require(apiSecret.isNotBlank()) { "API Secret 不能为空" }
        return buildString {
            appendLine("SIFTALPHA_SECRETS_FORMAT=$BINANCE_FORMAT_VERSION")
            appendLine("SIFTALPHA_BINANCE_API_KEY_B64=${encode(apiKey)}")
            appendLine("SIFTALPHA_BINANCE_API_SECRET_B64=${encode(apiSecret)}")
        }
    }

    /**
     * Generic project configuration transport. Names and values are both encoded; the Runtime adapter
     * validates decoded names again before exporting them. Ordering is deterministic for repeatable
     * diagnostics and tests.
     */
    fun buildEnvironment(values: Map<String, String>): String {
        val normalized = linkedMapOf<String, String>()
        values.toSortedMap().forEach { (rawName, rawValue) ->
            val name = rawName.trim()
            require(ENV_NAME.matches(name)) { "无效环境变量名称：$rawName" }
            require(rawValue.isNotBlank()) { "$name 的值不能为空" }
            normalized[name] = rawValue
        }
        require(normalized.isNotEmpty()) { "环境变量载荷不能为空" }
        require(normalized.size <= MAX_ENV_VALUES) { "环境变量数量不能超过 $MAX_ENV_VALUES" }

        return buildString {
            appendLine("SIFTALPHA_SECRETS_FORMAT=$ENVIRONMENT_FORMAT_VERSION")
            appendLine("SIFTALPHA_ENV_COUNT=${normalized.size}")
            normalized.entries.forEachIndexed { index, entry ->
                appendLine("SIFTALPHA_ENV_${index}_NAME_B64=${encode(entry.key)}")
                appendLine("SIFTALPHA_ENV_${index}_VALUE_B64=${encode(entry.value)}")
            }
        }
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
