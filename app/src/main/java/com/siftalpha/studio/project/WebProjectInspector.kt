package com.siftalpha.studio.project

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject

/**
 * Reads project-local metadata and a bounded set of project files to determine Web capability.
 * This is intentionally read-only: Studio never rewrites project source while inspecting.
 *
 * Explicit .project.json Web configuration remains authoritative. Without it, Python and Node.js
 * projects are inspected independently so a normal Express/Fastify/Vite/Next project does not need
 * to emit SiftAlpha-specific log markers merely to expose a Browser candidate.
 */
class WebProjectInspector(context: Context) {

    data class Profile(
        val enabled: Boolean,
        val framework: String?,
        val source: String,
        val host: String?,
        val port: Int?,
    ) {
        fun configuredLocalUrl(): String? {
            if (!enabled) return null
            val safeHost = when (host?.trim()?.lowercase()) {
                "127.0.0.1", "localhost" -> host.trim()
                "::1", "[::1]" -> "[::1]"
                else -> return null
            }
            val safePort = port?.takeIf { it in 1..65535 } ?: return null
            return "http://$safeHost:$safePort"
        }
    }

    private data class Child(
        val id: String,
        val name: String,
        val mime: String,
        val relativePath: String,
    )

    private data class PendingDirectory(
        val id: String,
        val relativePath: String,
        val depth: Int,
    )

    private val resolver = context.contentResolver
    private val projectStore = ProjectStore(context)

    fun inspect(projectDocumentId: String): Profile {
        val tree = projectStore.rootUri() ?: return Profile(false, null, "none", null, null)
        val files = projectFiles(tree, projectDocumentId)
        val metadata = files.firstOrNull { it.relativePath == ".project.json" }
            ?.let { readLimitedText(tree, it.id, MAX_METADATA_BYTES) }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

        metadata?.optJSONObject("web")?.let { web ->
            val enabled = if (web.has("enabled")) web.optBoolean("enabled", true) else true
            if (!enabled) return Profile(false, null, "config", null, null)

            val framework = WebProjectDetector.normalizeFramework(web.optString("framework"))
            val host = web.optString("host").trim().takeIf { it.isNotBlank() }
            val port = web.optInt("port", -1).takeIf { it in 1..65535 }
            return Profile(true, framework, "config", host, port)
        }

        val runCommand = metadata
            ?.optString("run")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val configuredEntry = metadata
            ?.optString("entry")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        detectNode(tree, files, runCommand)?.let { detection ->
            return Profile(
                enabled = true,
                framework = detection.framework,
                source = "node-${detection.source}",
                host = detection.host,
                port = detection.port,
            )
        }

        val requirements = files.firstOrNull { it.relativePath.equals("requirements.txt", ignoreCase = true) }
            ?.let { readLimitedText(tree, it.id, MAX_SCAN_FILE_BYTES) }
        val pyproject = files.firstOrNull { it.relativePath.equals("pyproject.toml", ignoreCase = true) }
            ?.let { readLimitedText(tree, it.id, MAX_SCAN_FILE_BYTES) }

        val preferredNames = buildList {
            configuredEntry?.substringAfterLast('/')?.let { add(it.lowercase()) }
            add("main.py")
            add("app.py")
            add("run.py")
            add("server.py")
            add("manage.py")
        }.distinct()

        val pythonChildren = files
            .filter { it.name.endsWith(".py", ignoreCase = true) }
            .sortedWith(
                compareBy<Child> {
                    val index = preferredNames.indexOf(it.name.lowercase())
                    if (index >= 0) index else Int.MAX_VALUE
                }.thenBy { pathDepth(it.relativePath) }
                    .thenBy { it.relativePath.lowercase() },
            )
            .take(MAX_PY_FILES)

        val pythonSources = pythonChildren
            .map { readLimitedText(tree, it.id, MAX_SCAN_FILE_BYTES) }

        val detection = WebProjectDetector.detect(
            requirements = requirements,
            pyproject = pyproject,
            pythonSources = pythonSources,
            runCommand = runCommand,
        ) ?: return Profile(false, null, "none", null, null)

        return Profile(
            enabled = true,
            framework = detection.framework,
            source = detection.source,
            host = detection.host,
            port = detection.port,
        )
    }

    private fun detectNode(
        tree: Uri,
        files: List<Child>,
        declaredRun: String?,
    ): NodeWebProjectDetector.Detection? {
        val packageJsons = files
            .filter { it.name.equals("package.json", ignoreCase = true) }
            .filterNot { child -> child.relativePath.split('/').any { it in IGNORED_DIRECTORIES } }
            .sortedBy { it.relativePath }
        val rootPackage = packageJsons.firstOrNull { '/' !in it.relativePath }
        val selectedPackage = rootPackage ?: packageJsons.singleOrNull() ?: return null
        val packageRoot = selectedPackage.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val packageText = readLimitedText(tree, selectedPackage.id, MAX_PACKAGE_JSON_BYTES)
        val packageJson = runCatching { JSONObject(packageText) }.getOrNull() ?: return null
        val dependencies = buildSet {
            listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies").forEach { key ->
                val objectValue = packageJson.optJSONObject(key) ?: return@forEach
                val names = objectValue.keys()
                while (names.hasNext()) add(names.next().lowercase())
            }
        }
        val packageStart = packageJson.optJSONObject("scripts")
            ?.optString("start")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val nodeSources = files
            .asSequence()
            .filter { isWithinPackageRoot(it.relativePath, packageRoot) }
            .filter { isNodeWebSource(it.name) }
            .filterNot { child -> child.relativePath.split('/').any { it in IGNORED_DIRECTORIES } }
            .sortedWith(
                compareBy<Child> { nodeSourcePriority(it.name) }
                    .thenBy { pathDepth(it.relativePath) }
                    .thenBy { it.relativePath.lowercase() },
            )
            .take(MAX_NODE_FILES)
            .map { readLimitedText(tree, it.id, MAX_SCAN_FILE_BYTES) }
            .toList()

        return NodeWebProjectDetector.detect(
            dependencies = dependencies,
            packageStartCommand = packageStart,
            nodeSources = nodeSources,
            declaredRun = declaredRun,
        )
    }

    private fun projectFiles(tree: Uri, projectDocumentId: String): List<Child> {
        val result = mutableListOf<Child>()
        val queue = ArrayDeque<PendingDirectory>()
        queue.add(PendingDirectory(projectDocumentId, "", 0))
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_PROJECT_ENTRIES) {
            val directory = queue.removeFirst()
            val children = children(tree, directory.id, directory.relativePath)
            for (child in children) {
                if (visited++ >= MAX_PROJECT_ENTRIES) break
                if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (
                        directory.depth < MAX_SCAN_DEPTH &&
                        child.name !in IGNORED_DIRECTORIES &&
                        !child.name.startsWith(".")
                    ) {
                        queue.add(PendingDirectory(child.id, child.relativePath, directory.depth + 1))
                    }
                } else {
                    result += child
                }
            }
        }
        return result
    }

    private fun children(tree: Uri, parentId: String, parentPath: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val result = mutableListOf<Child>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val childName = cursor.getString(name) ?: ""
                val relativePath = if (parentPath.isBlank()) childName else "$parentPath/$childName"
                result += Child(
                    id = cursor.getString(id),
                    name = childName,
                    mime = cursor.getString(mime) ?: "",
                    relativePath = relativePath,
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
                total += count
            }
            val length = minOf(total, maxBytes)
            return buffer.copyOf(length).toString(Charsets.UTF_8)
        }
    }

    private fun isWithinPackageRoot(path: String, packageRoot: String): Boolean =
        packageRoot.isBlank() || path == packageRoot || path.startsWith("$packageRoot/")

    private fun isNodeWebSource(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs") ||
            lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".jsx") ||
            lower.startsWith("vite.config.") || lower.startsWith("next.config.")
    }

    private fun nodeSourcePriority(name: String): Int = when (name.lowercase().substringBeforeLast('.')) {
        "server" -> 0
        "app" -> 1
        "index" -> 2
        "main" -> 3
        else -> if (name.lowercase().startsWith("vite.config.")) 4 else 10
    }

    private fun pathDepth(path: String): Int = path.count { it == '/' }

    companion object {
        private val IGNORED_DIRECTORIES = setOf(".git", "node_modules", "dist", "build", ".next", ".venv", "venv")
        private const val MAX_METADATA_BYTES = 128 * 1024
        private const val MAX_PACKAGE_JSON_BYTES = 256 * 1024
        private const val MAX_SCAN_FILE_BYTES = 256 * 1024
        private const val MAX_PY_FILES = 8
        private const val MAX_NODE_FILES = 8
        private const val MAX_SCAN_DEPTH = 4
        private const val MAX_PROJECT_ENTRIES = 192
    }
}
