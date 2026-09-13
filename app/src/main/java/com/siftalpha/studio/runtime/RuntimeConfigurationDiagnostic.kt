package com.siftalpha.studio.runtime

/**
 * High-confidence, read-only configuration diagnosis for completed Runtime output.
 *
 * This intentionally does not guess provider/package names. A variable is surfaced only when the
 * application output itself names an environment variable and states that it is missing/required.
 * Generic messages such as "API key is required" are reported without inventing a variable name.
 */
object RuntimeConfigurationDiagnostic {

    data class Result(
        val missingEnvironmentNames: List<String>,
        val unnamedCredentialRequired: Boolean,
    ) {
        val hasActionableFinding: Boolean
            get() = missingEnvironmentNames.isNotEmpty() || unnamedCredentialRequired
    }

    private data class NamedMatch(
        val position: Int,
        val name: String,
    )

    private val ENV_NAME = Regex("^[A-Z_][A-Z0-9_]*$")

    private val namedPatterns = listOf(
        Regex(
            "(?i)(?:missing|required|unset|not\\s+set)[^\\n]{0,80}(?:environment\\s+variable|env(?:ironment)?\\s+var(?:iable)?)\\s*[:=]?\\s*[`'\"]?([A-Z_][A-Z0-9_]*)",
        ),
        Regex(
            "(?i)(?:environment\\s+variable|env(?:ironment)?\\s+var(?:iable)?)\\s*[`'\"]?([A-Z_][A-Z0-9_]*)[`'\"]?[^\\n]{0,80}(?:missing|required|unset|not\\s+set)",
        ),
        Regex(
            "(?i)\\b([A-Z_][A-Z0-9_]*(?:API_KEY|API_KEYS|TOKEN|TOKENS|SECRET|SECRETS|PASSWORD|CREDENTIALS?))\\b[^\\n]{0,60}(?:is\\s+)?(?:missing|required|unset|not\\s+set)",
        ),
        Regex(
            "(?i)(?:missing|required)\\s+(?:configuration|credential)\\s*[:=]?\\s*[`'\"]?([A-Z_][A-Z0-9_]*)",
        ),
    )

    private val genericCredentialPatterns = listOf(
        Regex("(?i)\\bAPI\\s+key\\b[^\\n]{0,50}(?:missing|required|not\\s+set|unset)"),
        Regex("(?i)(?:missing|required)[^\\n]{0,40}\\bAPI\\s+key\\b"),
        Regex("(?i)\\bcredential(?:s)?\\b[^\\n]{0,50}(?:missing|required|not\\s+configured)"),
    )

    fun inspect(text: String): Result {
        val matches = mutableListOf<NamedMatch>()
        namedPatterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val candidate = match.groupValues.getOrNull(1).orEmpty().uppercase()
                if (ENV_NAME.matches(candidate) && candidate !in IGNORED_GENERIC_WORDS) {
                    val group = match.groups[1]
                    matches += NamedMatch(
                        position = group?.range?.first ?: match.range.first,
                        name = candidate,
                    )
                }
            }
        }

        val names = linkedSetOf<String>()
        matches.sortedBy { it.position }.forEach { names += it.name }

        val unnamed = names.isEmpty() && genericCredentialPatterns.any { it.containsMatchIn(text) }
        return Result(
            missingEnvironmentNames = names.take(MAX_NAMES),
            unnamedCredentialRequired = unnamed,
        )
    }

    private const val MAX_NAMES = 10
    private val IGNORED_GENERIC_WORDS = setOf(
        "API_KEY",
        "API_KEYS",
        "TOKEN",
        "SECRET",
        "PASSWORD",
        "CREDENTIAL",
        "CREDENTIALS",
    )
}
