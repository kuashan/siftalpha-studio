package com.siftalpha.studio.project

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Reads project configuration requirements without guessing that every .env.example entry is required.
 *
 * Product rules:
 * - `.project.json.requiredEnv` is authoritative for required configuration.
 * - `.env` is inspected only to determine whether a declared value is already configured by the project.
 * - `.env.example` contributes credential candidates for user guidance, never a blocking requirement.
 * - malformed metadata or Storage Access Framework provider failures must not take down Runtime Center.
 */
class ProjectConfigurationInspector(context: Context) {

    data class Requirement(
        val name: String,
        val secret: Boolean,
        val required: Boolean,
        val description: String,
    )

    data class Profile(
        val requirements: List<Requirement>,
        val configuredProjectEnvKeys: Set<String>,
        val credentialCandidates: List<String>,
    ) {
        val required: List<Requirement>
            get() = requirements.filter { it.required }

        val declaredNames: Set<String>
            get() = requirements.mapTo(linkedSetOf()) { it.name }
    }

    private data class Child(
        val id: String,
        val name: String,
    )

    private val resolver = context.contentResolver
    private val projectStore = ProjectStore(context.applicationContext)

    fun inspect(projectDocumentId: String): Profile = runCatching {
        val tree = projectStore.rootUri() ?: return@runCatching emptyProfile()
        val children = children(tree, projectDocumentId)
        val byName = children.associateBy { it.name }

        val metadata = byName[".project.json"]
            ?.let { readLimitedText(tree, it.id, MAX_METADATA_BYTES) }
            .orEmpty()
        val env = byName[".env"]
            ?.let { readLimitedText(tree, it.id, MAX_ENV_BYTES) }
            .orEmpty()
        val envExample = byName[".env.example"]
            ?.let { readLimitedText(tree, it.id, MAX_ENV_BYTES) }
            .orEmpty()

        val requirements = parseRequiredEnv(metadata)
        val configuredKeys = parseConfiguredEnvKeys(env)
        val credentialCandidates = parseEnvCandidateKeys(envExample)
            .filter(::looksSensitive)
            .filterNot { candidate -> requirements.any { it.name == candidate } }
            .distinct()
            .sorted()

        Profile(
            requirements = requirements,
            configuredProjectEnvKeys = configuredKeys,
            credentialCandidates = credentialCandidates,
        )
    }.getOrElse {
        emptyProfile()
    }

    companion object {
        private const val MAX_METADATA_BYTES = 128 * 1024
        private const val MAX_ENV_BYTES = 256 * 1024
        private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        fun emptyProfile(): Profile = Profile(emptyList(), emptySet(), emptyList())

        /**
         * Supported project schema:
         *
         * "requiredEnv": [
         *   {
         *     "name": "GEMINI_API_KEY",
         *     "secret": true,
         *     "required": true,
         *     "description": "Gemini API key"
         *   }
         * ]
         *
         * String entries such as "DATABASE_PATH" are also accepted and default to required=true,
         * secret=false. Duplicate names keep the first declaration so metadata order is stable.
         */
        fun parseRequiredEnv(metadata: String): List<Requirement> {
            val array = extractNamedArray(metadata, "requiredEnv") ?: return emptyList()
            val entries = splitTopLevelArray(array)
            val result = linkedMapOf<String, Requirement>()

            for (entry in entries) {
                val trimmed = entry.trim()
                val requirement = when {
                    trimmed.startsWith('"') -> {
                        val name = decodeJsonString(trimmed) ?: continue
                        if (!ENV_NAME.matches(name)) continue
                        Requirement(name, secret = false, required = true, description = "")
                    }
                    trimmed.startsWith('{') -> {
                        val name = readJsonStringField(trimmed, "name")?.trim().orEmpty()
                        if (!ENV_NAME.matches(name)) continue
                        Requirement(
                            name = name,
                            secret = readJsonBooleanField(trimmed, "secret") ?: looksSensitive(name),
                            required = readJsonBooleanField(trimmed, "required") ?: true,
                            description = readJsonStringField(trimmed, "description").orEmpty().trim(),
                        )
                    }
                    else -> null
                } ?: continue
                result.putIfAbsent(requirement.name, requirement)
            }
            return result.values.toList()
        }

        /** Returns only keys whose .env values are non-blank. Values are never returned. */
        fun parseConfiguredEnvKeys(text: String): Set<String> {
            val result = linkedSetOf<String>()
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith('#')) return@forEach
                val normalized = line.removePrefix("export ").trimStart()
                val equals = normalized.indexOf('=')
                if (equals <= 0) return@forEach
                val name = normalized.substring(0, equals).trim()
                if (!ENV_NAME.matches(name)) return@forEach
                val value = normalized.substring(equals + 1).trim()
                if (value.isBlank() || value == "\"\"" || value == "''") return@forEach
                result += name
            }
            return result
        }

        /**
         * Extract candidate variable names from .env.example. Commented examples are intentionally
         * included, but callers must treat them as suggestions only, never as required configuration.
         */
        fun parseEnvCandidateKeys(text: String): List<String> {
            val result = linkedSetOf<String>()
            text.lineSequence().forEach { raw ->
                var line = raw.trim()
                if (line.isBlank()) return@forEach
                while (line.startsWith('#')) line = line.drop(1).trimStart()
                line = line.removePrefix("export ").trimStart()
                val equals = line.indexOf('=')
                if (equals <= 0) return@forEach
                val name = line.substring(0, equals).trim()
                if (ENV_NAME.matches(name)) result += name
            }
            return result.toList()
        }

        fun looksSensitive(name: String): Boolean {
            val upper = name.uppercase()
            return upper.endsWith("_API_KEY") ||
                upper.endsWith("_API_KEYS") ||
                upper.endsWith("_KEY") ||
                upper.endsWith("_KEYS") ||
                upper.endsWith("_TOKEN") ||
                upper.endsWith("_TOKENS") ||
                upper.endsWith("_SECRET") ||
                upper.endsWith("_SECRETS") ||
                upper.endsWith("_PASSWORD") ||
                upper.endsWith("_PASS") ||
                upper.contains("CREDENTIAL")
        }

        private fun extractNamedArray(text: String, key: String): String? {
            val keyIndex = text.indexOf("\"$key\"")
            if (keyIndex < 0) return null
            val colon = text.indexOf(':', keyIndex + key.length + 2)
            if (colon < 0) return null
            var index = colon + 1
            while (index < text.length && text[index].isWhitespace()) index += 1
            if (index >= text.length || text[index] != '[') return null
            val end = findMatching(text, index, '[', ']') ?: return null
            return text.substring(index + 1, end)
        }

        private fun splitTopLevelArray(body: String): List<String> {
            val result = mutableListOf<String>()
            var start = 0
            var objectDepth = 0
            var arrayDepth = 0
            var quoted = false
            var escaped = false
            for (index in body.indices) {
                val ch = body[index]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') quoted = false
                    continue
                }
                when (ch) {
                    '"' -> quoted = true
                    '{' -> objectDepth += 1
                    '}' -> objectDepth -= 1
                    '[' -> arrayDepth += 1
                    ']' -> arrayDepth -= 1
                    ',' -> if (objectDepth == 0 && arrayDepth == 0) {
                        body.substring(start, index).trim().takeIf { it.isNotEmpty() }?.let(result::add)
                        start = index + 1
                    }
                }
            }
            body.substring(start).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            return result
        }

        private fun readJsonStringField(objectText: String, field: String): String? {
            val valueStart = findFieldValueStart(objectText, field) ?: return null
            if (valueStart >= objectText.length || objectText[valueStart] != '"') return null
            var index = valueStart + 1
            var escaped = false
            while (index < objectText.length) {
                val ch = objectText[index]
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') return decodeJsonString(objectText.substring(valueStart, index + 1))
                index += 1
            }
            return null
        }

        private fun readJsonBooleanField(objectText: String, field: String): Boolean? {
            val start = findFieldValueStart(objectText, field) ?: return null
            return when {
                objectText.regionMatches(start, "true", 0, 4, ignoreCase = false) -> true
                objectText.regionMatches(start, "false", 0, 5, ignoreCase = false) -> false
                else -> null
            }
        }

        private fun findFieldValueStart(objectText: String, field: String): Int? {
            val key = "\"$field\""
            val keyIndex = objectText.indexOf(key)
            if (keyIndex < 0) return null
            val colon = objectText.indexOf(':', keyIndex + key.length)
            if (colon < 0) return null
            var index = colon + 1
            while (index < objectText.length && objectText[index].isWhitespace()) index += 1
            return index.takeIf { it < objectText.length }
        }

        private fun decodeJsonString(raw: String): String? {
            val text = raw.trim()
            if (text.length < 2 || text.first() != '"' || text.last() != '"') return null
            val result = StringBuilder()
            var index = 1
            while (index < text.length - 1) {
                val ch = text[index]
                if (ch != '\\') {
                    result.append(ch)
                    index += 1
                    continue
                }
                index += 1
                if (index >= text.length - 1) return null
                when (val escaped = text[index]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        if (index + 4 >= text.length) return null
                        val hex = text.substring(index + 1, index + 5)
                        val code = hex.toIntOrNull(16) ?: return null
                        result.append(code.toChar())
                        index += 4
                    }
                    else -> return null
                }
                index += 1
            }
            return result.toString()
        }

        private fun findMatching(text: String, start: Int, open: Char, close: Char): Int? {
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until text.length) {
                val ch = text[index]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') quoted = false
                    continue
                }
                when (ch) {
                    '"' -> quoted = true
                    open -> depth += 1
                    close -> {
                        depth -= 1
                        if (depth == 0) return index
                        if (depth < 0) return null
                    }
                }
            }
            return null
        }
    }

    private fun children(tree: Uri, parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val result = mutableListOf<Child>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idIndex < 0 || nameIndex < 0) return@use
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex) ?: continue
                result += Child(id, cursor.getString(nameIndex) ?: "")
            }
        }
        return result
    }

    private fun readLimitedText(tree: Uri, id: String, maxBytes: Int): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(maxBytes + 1)
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count <= 0) break
                total += count
            }
            buffer.copyOf(minOf(total, maxBytes)).toString(Charsets.UTF_8)
        }.orEmpty()
    }
}
