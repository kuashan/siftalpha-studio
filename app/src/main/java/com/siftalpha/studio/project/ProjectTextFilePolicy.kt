package com.siftalpha.studio.project

/**
 * Pure filename/content policy for the mobile text editor.
 *
 * Android document providers commonly expose dotfiles such as `.env.example` as
 * application/octet-stream, so MIME alone is not sufficient. Binary protection
 * remains content-based and must still reject NUL-containing payloads.
 */
object ProjectTextFilePolicy {
    private val exactTextFileNames = setOf(
        ".project.json",
        ".gitignore",
        ".env",
        "dockerfile",
        "makefile",
        "pipfile",
        "requirements.txt",
    )

    private val textExtensions = setOf(
        "py", "pyi", "txt", "md", "json", "jsonl", "toml", "yaml", "yml",
        "ini", "cfg", "conf", "sh", "bash", "zsh", "fish", "sql", "csv",
        "xml", "html", "htm", "css", "scss", "js", "mjs", "cjs", "ts",
        "tsx", "jsx", "java", "kt", "kts", "gradle", "properties", "env",
        "gitignore", "dockerfile",
    )

    private val environmentTemplateSuffixes = setOf("example", "sample", "template")

    fun isEditableTextFileName(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("text/", ignoreCase = true)) return true

        val lower = name.lowercase()
        if (lower in exactTextFileNames) return true
        if (isEnvironmentFamilyName(lower)) return true

        val extension = lower.substringAfterLast('.', missingDelimiterValue = "")
        return extension in textExtensions
    }

    /**
     * `.env.example` -> `.env`
     * `.env.sample` -> `.env`
     * `.env.template` -> `.env`
     * `.env.production.example` -> `.env.production`
     *
     * Real environment variants such as `.env.production` are not templates.
     */
    fun environmentTemplateTargetName(name: String): String? {
        val lower = name.lowercase()
        if (!lower.startsWith(".env.")) return null
        val suffix = lower.substringAfterLast('.')
        if (suffix !in environmentTemplateSuffixes) return null

        val target = name.substringBeforeLast('.')
        return target.takeIf {
            val targetLower = it.lowercase()
            targetLower == ".env" || targetLower.startsWith(".env.")
        }
    }

    fun isEnvironmentFamilyName(name: String): Boolean {
        val lower = name.lowercase()
        return lower == ".env" || lower.startsWith(".env.")
    }

    fun isSafeUtf8TextPayload(bytes: ByteArray): Boolean =
        bytes.none { it == 0.toByte() }
}
