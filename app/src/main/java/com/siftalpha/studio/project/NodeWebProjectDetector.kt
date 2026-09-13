package com.siftalpha.studio.project

/**
 * Pure best-effort Node.js Web endpoint detector.
 *
 * The detector never requires SiftAlpha-specific project changes. It combines authoritative
 * package.json evidence, the selected start command, and a bounded set of project source files.
 * A detected URL is only a candidate; the Android-side endpoint probe still has to verify that the
 * loopback listener is actually reachable before Studio enables Browser.
 */
object NodeWebProjectDetector {

    data class Detection(
        val framework: String,
        val source: String,
        val host: String? = null,
        val port: Int? = null,
    )

    private val frameworkDependencies = linkedMapOf(
        "next" to setOf("next"),
        "vite" to setOf("vite", "@vitejs/plugin-react", "@vitejs/plugin-vue"),
        "fastify" to setOf("fastify"),
        "express" to setOf("express"),
    )

    fun detect(
        dependencies: Set<String>,
        packageStartCommand: String?,
        nodeSources: List<String>,
        declaredRun: String? = null,
    ): Detection? {
        val normalizedDependencies = dependencies.map { it.trim().lowercase() }.toSet()
        val sourceText = nodeSources.joinToString("\n")
        val effectiveRun = declaredRun?.takeIf { it.isNotBlank() }
            ?: packageStartCommand?.takeIf { it.isNotBlank() }

        val dependencyFramework = frameworkDependencies.entries
            .firstOrNull { (_, names) -> names.any { it in normalizedDependencies } }
            ?.key
        val runFramework = frameworkFromCommand(effectiveRun)
        val sourceFramework = frameworkFromSource(sourceText)
        val framework = dependencyFramework ?: runFramework ?: sourceFramework ?: return null

        val explicitPort = extractCommandPort(effectiveRun)
            ?: extractSourcePort(sourceText)
        val port = explicitPort ?: when (framework) {
            "vite" -> 5173
            "next" -> 3000
            else -> null
        }
        val source = when {
            explicitPort != null && !effectiveRun.isNullOrBlank() && extractCommandPort(effectiveRun) != null -> "run-command"
            explicitPort != null -> "source"
            dependencyFramework != null -> "dependencies"
            runFramework != null -> "run-command"
            else -> "source"
        }

        return Detection(
            framework = framework,
            source = source,
            host = port?.let { "127.0.0.1" },
            port = port,
        )
    }

    private fun frameworkFromCommand(command: String?): String? {
        val value = command.orEmpty()
        return when {
            Regex("(?i)(^|[\\s;&|])next(?:\\s|$)").containsMatchIn(value) -> "next"
            Regex("(?i)(^|[\\s;&|])vite(?:\\s|$)").containsMatchIn(value) -> "vite"
            Regex("(?i)(^|[\\s;&|])fastify(?:\\s|$)").containsMatchIn(value) -> "fastify"
            else -> null
        }
    }

    private fun frameworkFromSource(source: String): String? = when {
        Regex("(?i)(?:require\\(\\s*['\"]fastify['\"]\\s*\\)|from\\s+['\"]fastify['\"])").containsMatchIn(source) -> "fastify"
        Regex("(?i)(?:require\\(\\s*['\"]express['\"]\\s*\\)|from\\s+['\"]express['\"])").containsMatchIn(source) -> "express"
        Regex("(?i)(?:require\\(\\s*['\"](?:node:)?https?['\"]\\s*\\)|from\\s+['\"](?:node:)?https?['\"]|\\bcreateServer\\s*\\()").containsMatchIn(source) -> "node-http"
        else -> null
    }

    private fun extractCommandPort(command: String?): Int? {
        val value = command.orEmpty()
        val patterns = listOf(
            Regex("(?i)(?:^|\\s)--port(?:=|\\s+)(\\d{1,5})(?=\\s|$)"),
            Regex("(?i)(?:^|\\s)-p\\s+(\\d{1,5})(?=\\s|$)"),
            Regex("(?i)(?:^|\\s)PORT=(\\d{1,5})(?=\\s|$)"),
        )
        return patterns.asSequence()
            .mapNotNull { it.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .firstOrNull { it in 1..65535 }
    }

    private fun extractSourcePort(source: String): Int? {
        val directPatterns = listOf(
            Regex("(?i)\\.listen\\s*\\(\\s*(\\d{1,5})\\b"),
            Regex("(?i)\\.listen\\s*\\(\\s*\\{[^}]{0,240}?\\bport\\s*:\\s*(\\d{1,5})\\b"),
            Regex("(?i)\\bserver\\s*:\\s*\\{[^}]{0,240}?\\bport\\s*:\\s*(\\d{1,5})\\b"),
        )
        directPatterns.forEach { regex ->
            regex.find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?.let { return it }
        }

        val assignments = mutableMapOf<String, Int>()
        Regex(
            "(?i)\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:process\\.env\\.[A-Za-z_$][\\w$]*\\s*(?:\\|\\||\\?\\?)\\s*)?(\\d{1,5})\\b",
        ).findAll(source).forEach { match ->
            val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@forEach
            assignments[match.groupValues[1]] = port
        }
        Regex("(?i)\\.listen\\s*\\(\\s*([A-Za-z_$][\\w$]*)\\b")
            .findAll(source)
            .forEach { match -> assignments[match.groupValues[1]]?.let { return it } }
        Regex("(?i)\\bport\\s*:\\s*([A-Za-z_$][\\w$]*)\\b")
            .findAll(source)
            .forEach { match -> assignments[match.groupValues[1]]?.let { return it } }
        return null
    }
}
