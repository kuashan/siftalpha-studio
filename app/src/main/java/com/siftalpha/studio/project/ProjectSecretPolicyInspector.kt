package com.siftalpha.studio.project

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Reads optional project secret requirements from .project.json.
 *
 * Current schema:
 *   "secrets": { "binanceApi": false }
 *
 * This class is intentionally defensive because it is consulted while the Runtime Center
 * renders project cards. Any SAF/provider/metadata problem must fall back to legacy behavior
 * instead of being allowed to take down the Activity.
 */
class ProjectSecretPolicyInspector(context: Context) {

    enum class BinanceApiPolicy {
        UNSPECIFIED,
        REQUIRED,
        NOT_REQUIRED,
    }

    data class Policy(
        val binanceApi: BinanceApiPolicy,
    ) {
        val binanceApiUiEnabled: Boolean
            get() = binanceApi != BinanceApiPolicy.NOT_REQUIRED
    }

    private data class Child(
        val id: String,
        val name: String,
    )

    private val resolver = context.contentResolver
    private val projectStore = ProjectStore(context.applicationContext)

    fun inspect(projectDocumentId: String): Policy = runCatching {
        val tree = projectStore.rootUri() ?: return@runCatching unspecified()
        val child = children(tree, projectDocumentId)
            .firstOrNull { it.name == ".project.json" }
            ?: return@runCatching unspecified()
        val metadata = readLimitedText(tree, child.id, MAX_METADATA_BYTES)
        if (metadata.isBlank()) unspecified() else parse(metadata)
    }.getOrElse {
        // Runtime Center startup must remain available even if a document provider behaves oddly.
        unspecified()
    }

    companion object {
        private const val MAX_METADATA_BYTES = 128 * 1024

        private fun unspecified() = Policy(BinanceApiPolicy.UNSPECIFIED)

        /**
         * Tiny dependency-free parser for the single capability we need here.
         * No Android org.json and no precompiled Regex objects are used, so class initialization
         * stays trivial on every supported Android version.
         */
        fun parse(metadata: String): Policy {
            val secretsKey = metadata.indexOf("\"secrets\"")
            if (secretsKey < 0) return unspecified()

            val secretsColon = metadata.indexOf(':', secretsKey + 9)
            if (secretsColon < 0) return unspecified()
            val openBrace = metadata.indexOf('{', secretsColon + 1)
            if (openBrace < 0) return unspecified()
            val closeBrace = findMatchingObjectEnd(metadata, openBrace)
            if (closeBrace < 0) return unspecified()

            val body = metadata.substring(openBrace + 1, closeBrace)
            val keyIndex = body.indexOf("\"binanceApi\"")
            if (keyIndex < 0) return unspecified()
            val valueColon = body.indexOf(':', keyIndex + 12)
            if (valueColon < 0) return unspecified()

            val raw = readJsonScalar(body, valueColon + 1) ?: return unspecified()
            val normalized = raw.trim().removeSurrounding("\"").trim().lowercase()
            val policy = when (normalized) {
                "true", "required", "yes", "enabled" -> BinanceApiPolicy.REQUIRED
                "false", "none", "not_required", "not-required", "no", "disabled" ->
                    BinanceApiPolicy.NOT_REQUIRED
                else -> BinanceApiPolicy.UNSPECIFIED
            }
            return Policy(policy)
        }

        private fun findMatchingObjectEnd(text: String, openBrace: Int): Int {
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in openBrace until text.length) {
                val ch = text[index]
                if (quoted) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        quoted = false
                    }
                    continue
                }
                when (ch) {
                    '"' -> quoted = true
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) return index
                        if (depth < 0) return -1
                    }
                }
            }
            return -1
        }

        private fun readJsonScalar(text: String, start: Int): String? {
            var index = start
            while (index < text.length && text[index].isWhitespace()) index += 1
            if (index >= text.length) return null

            if (text[index] == '"') {
                val begin = index
                index += 1
                var escaped = false
                while (index < text.length) {
                    val ch = text[index]
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        return text.substring(begin, index + 1)
                    }
                    index += 1
                }
                return null
            }

            val begin = index
            while (index < text.length && text[index] != ',' && text[index] != '}') index += 1
            return text.substring(begin, index).trim().takeIf { it.isNotEmpty() }
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
                result += Child(
                    id = id,
                    name = cursor.getString(nameIndex) ?: "",
                )
            }
        }
        return result
    }

    private fun readLimitedText(tree: Uri, id: String, maxBytes: Int): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val stream = resolver.openInputStream(uri) ?: return ""
        stream.use { input ->
            val buffer = ByteArray(maxBytes + 1)
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) break
                if (count == 0) break
                total += count
            }
            return buffer.copyOf(minOf(total, maxBytes)).toString(Charsets.UTF_8)
        }
    }
}
