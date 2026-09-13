package com.siftalpha.studio.project

/**
 * Pure Web detector used by Studio project inspection.
 *
 * Explicit .project.json Web configuration is handled by WebProjectInspector first. This detector
 * is the best-effort fallback for projects without an explicit Web endpoint: it identifies known
 * Python Web frameworks and derives a small local port candidate from the run command or source.
 */
object WebProjectDetector {

    data class Detection(
        val framework: String,
        val source: String,
        val host: String? = null,
        val port: Int? = null,
    )

    private data class FrameworkRule(
        val id: String,
        val dependencyRegex: Regex,
        val sourceRegex: Regex,
    )

    private val rules = listOf(
        FrameworkRule(
            id = "streamlit",
            dependencyRegex = Regex("(?i)(^|[\\s\\\"'])streamlit(?:\\[[^]]+])?(?=\\s|[<>=~!;,\\\"']|$)"),
            sourceRegex = Regex("(?im)^\\s*(?:import\\s+streamlit\\b|from\\s+streamlit\\b)"),
        ),
        FrameworkRule(
            id = "gradio",
            dependencyRegex = Regex("(?i)(^|[\\s\\\"'])gradio(?:\\[[^]]+])?(?=\\s|[<>=~!;,\\\"']|$)"),
            sourceRegex = Regex("(?im)^\\s*(?:import\\s+gradio\\b|from\\s+gradio\\b)"),
        ),
        FrameworkRule(
            id = "dash",
            dependencyRegex = Regex("(?i)(^|[\\s\\\"'])dash(?:\\[[^]]+])?(?=\\s|[<>=~!;,\\\"']|$)"),
            sourceRegex = Regex("(?im)^\\s*(?:import\\s+dash\\b|from\\s+dash\\b)"),
        ),
        FrameworkRule(
            id = "fastapi",
            dependencyRegex = Regex("(?i)(^|[\\s\\\"'])fastapi(?:\\[[^]]+])?(?=\\s|[<>=~!;,\\\"']|$)"),
            sourceRegex = Regex("(?im)^\\s*(?:import\\s+fastapi\\b|from\\s+fastapi\\b)"),
        ),
        FrameworkRule(
            id = "flask",
            dependencyRegex = Regex("(?i)(^|[\\s\\\"'])flask(?:\\[[^]]+])?(?=\\s|[<>=~!;,\\\"']|$)"),
            sourceRegex = Regex("(?im)^\\s*(?:import\\s+flask\\b|from\\s+flask\\b)"),
        ),
    )

    private val defaultPorts = mapOf(
        "streamlit" to 8501,
        "gradio" to 7860,
        "fastapi" to 8000,
        "dash" to 8050,
        "flask" to 5000,
    )

    fun detect(
        requirements: String?,
        pyproject: String?,
        pythonSources: List<String>,
        runCommand: String? = null,
    ): Detection? {
        val dependencyText = buildString {
            if (!requirements.isNullOrBlank()) appendLine(requirements)
            if (!pyproject.isNullOrBlank()) appendLine(pyproject)
        }
        val sourceText = pythonSources.joinToString("\n")

        val dependencyFramework = rules
            .firstOrNull { it.dependencyRegex.containsMatchIn(dependencyText) }
            ?.id
        val sourceFramework = rules
            .firstOrNull { it.sourceRegex.containsMatchIn(sourceText) }
            ?.id
        val runFramework = frameworkFromRunCommand(runCommand)
        val pythonHttpServer = PYTHON_HTTP_SERVER_SOURCE.containsMatchIn(sourceText) ||
            PYTHON_HTTP_SERVER_RUN.containsMatchIn(runCommand.orEmpty())

        val framework = dependencyFramework ?: sourceFramework ?: runFramework ?: "python-http".takeIf { pythonHttpServer }
            ?: return null
        val source = when {
            dependencyFramework != null -> "dependencies"
            sourceFramework != null || pythonHttpServer && sourceText.isNotBlank() -> "source"
            runFramework != null || pythonHttpServer -> "run-command"
            else -> "source"
        }

        val port = extractRunCommandPort(runCommand)
            ?: extractSourcePort(sourceText)
            ?: defaultPorts[framework]
        val host = port?.let { "127.0.0.1" }

        return Detection(
            framework = framework,
            source = source,
            host = host,
            port = port,
        )
    }

    internal fun extractRunCommandPort(runCommand: String?): Int? {
        val command = runCommand.orEmpty()
        RUN_PORT_FLAG.find(command)?.groupValues?.getOrNull(1)?.toIntOrNull()?.validPort()?.let { return it }
        PYTHON_HTTP_SERVER_POSITIONAL_PORT.find(command)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.validPort()
            ?.let { return it }
        return null
    }

    internal fun extractSourcePort(sourceText: String): Int? {
        if (sourceText.isBlank()) return null

        // Resolve the actual second element passed to HTTPServer/ThreadingHTTPServer. The bind host
        // may itself be a variable, so discovery must not require a literal "127.0.0.1" in the call.
        HTTP_SERVER_TUPLE_PORT.find(sourceText)?.groupValues?.getOrNull(1)?.let { token ->
            token.toIntOrNull()?.validPort()?.let { return it }
            resolveAssignedPort(sourceText, token)?.let { return it }
        }

        DIRECT_HTTP_SERVER_PORT.find(sourceText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.validPort()
            ?.let { return it }

        DIRECT_KEYWORD_PORT.find(sourceText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.validPort()
            ?.let { return it }

        val constants = INTEGER_CONSTANT.findAll(sourceText)
            .mapNotNull { match ->
                val name = match.groupValues.getOrNull(1).orEmpty()
                val value = match.groupValues.getOrNull(2)?.toIntOrNull()?.validPort()
                if (name.isBlank() || value == null) null else name to value
            }
            .toMap()

        HTTP_SERVER_VARIABLE_PORT.find(sourceText)?.groupValues?.getOrNull(1)?.let { variable ->
            constants[variable]?.let { return it }
            resolveAssignedPort(sourceText, variable)?.let { return it }
        }
        KEYWORD_VARIABLE_PORT.find(sourceText)?.groupValues?.getOrNull(1)?.let { variable ->
            constants[variable]?.let { return it }
            resolveAssignedPort(sourceText, variable)?.let { return it }
        }

        // Conventional Web port constants are a final static hint only after a Web framework/server
        // has already been identified by detect(). This keeps ordinary Python files from becoming
        // Web projects merely because they contain a variable named PORT.
        val preferredPortNames = listOf("SERVER_PORT", "WEB_PORT", "HTTP_PORT", "PORT")
        preferredPortNames.firstNotNullOfOrNull { preferred ->
            constants.entries.firstOrNull { it.key.equals(preferred, ignoreCase = true) }?.value
                ?: resolveAssignedPort(sourceText, preferred)
        }?.let { return it }

        return null
    }

    private fun resolveAssignedPort(sourceText: String, variable: String): Int? {
        if (!PYTHON_IDENTIFIER.matches(variable)) return null
        val assignment = Regex(
            "(?m)^\\s*${Regex.escape(variable)}\\s*=\\s*([^\\r\\n#]+)",
        ).find(sourceText)?.groupValues?.getOrNull(1)?.trim() ?: return null

        DIRECT_ASSIGNMENT_PORT.matchEntire(assignment)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.validPort()
            ?.let { return it }

        INT_LITERAL_PORT.matchEntire(assignment)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.validPort()
            ?.let { return it }

        // Common portable-server shape:
        // SERVER_PORT = int(os.getenv("PORT", "9127"))
        // SERVER_PORT = int(os.environ.get("PORT", 9127))
        ENV_DEFAULT_PORT.find(assignment)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.validPort()
            ?.let { return it }

        return null
    }

    private fun frameworkFromRunCommand(runCommand: String?): String? {
        val command = runCommand.orEmpty()
        return when {
            Regex("(?i)(^|\\s)streamlit\\s+run(?:\\s|$)").containsMatchIn(command) -> "streamlit"
            Regex("(?i)(^|\\s)uvicorn(?:\\s|$)").containsMatchIn(command) -> "fastapi"
            Regex("(?i)(^|\\s)flask(?:\\s|$)").containsMatchIn(command) -> "flask"
            else -> null
        }
    }

    fun normalizeFramework(raw: String?): String? {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when (value) {
            "streamlit", "gradio", "dash", "fastapi", "flask", "python-http" -> value
            else -> value.takeIf { it.isNotBlank() }
        }
    }

    private fun Int.validPort(): Int? = takeIf { it in 1..65535 }

    private val PYTHON_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val PYTHON_HTTP_SERVER_SOURCE = Regex(
        "(?i)\\b(?:ThreadingHTTPServer|HTTPServer)\\s*\\(",
    )
    private val PYTHON_HTTP_SERVER_RUN = Regex(
        "(?i)\\bpython(?:3)?\\s+-m\\s+http\\.server\\b",
    )
    private val RUN_PORT_FLAG = Regex(
        "(?i)(?:--port|--server\\.port)(?:=|\\s+)([0-9]{1,5})(?=\\s|$)",
    )
    private val PYTHON_HTTP_SERVER_POSITIONAL_PORT = Regex(
        "(?i)\\bpython(?:3)?\\s+-m\\s+http\\.server(?:\\s+--bind\\s+\\S+)?\\s+([0-9]{1,5})(?=\\s|$)",
    )
    private val HTTP_SERVER_TUPLE_PORT = Regex(
        "(?is)\\b(?:ThreadingHTTPServer|HTTPServer)\\s*\\(\\s*\\(\\s*[^,\\r\\n]{1,256}\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*|[0-9]{1,5})\\s*\\)",
    )
    private val DIRECT_HTTP_SERVER_PORT = Regex(
        "(?is)\\b(?:ThreadingHTTPServer|HTTPServer)\\s*\\(\\s*\\(\\s*[\"'][^\"']+[\"']\\s*,\\s*([0-9]{1,5})\\b",
    )
    private val DIRECT_KEYWORD_PORT = Regex(
        "(?is)\\b(?:uvicorn\\.run|[A-Za-z_][A-Za-z0-9_\\.]*\\.(?:run|launch))\\s*\\(.{0,1200}?\\b(?:port|server_port)\\s*=\\s*([0-9]{1,5})\\b",
    )
    private val INTEGER_CONSTANT = Regex(
        "(?m)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([0-9]{1,5})\\s*(?:#.*)?$",
    )
    private val DIRECT_ASSIGNMENT_PORT = Regex(
        "^[\"']?([0-9]{1,5})[\"']?$",
    )
    private val INT_LITERAL_PORT = Regex(
        "(?i)^int\\s*\\(\\s*[\"']?([0-9]{1,5})[\"']?\\s*\\)$",
    )
    private val ENV_DEFAULT_PORT = Regex(
        "(?is)(?:os\\.getenv|os\\.environ\\.get)\\s*\\(\\s*[\"'][^\"']+[\"']\\s*,\\s*[\"']?([0-9]{1,5})[\"']?\\s*\\)",
    )
    private val HTTP_SERVER_VARIABLE_PORT = Regex(
        "(?is)\\b(?:ThreadingHTTPServer|HTTPServer)\\s*\\(\\s*\\(\\s*[\"'][^\"']+[\"']\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\b",
    )
    private val KEYWORD_VARIABLE_PORT = Regex(
        "(?is)\\b(?:uvicorn\\.run|[A-Za-z_][A-Za-z0-9_\\.]*\\.(?:run|launch))\\s*\\(.{0,1200}?\\b(?:port|server_port)\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)\\b",
    )
}
