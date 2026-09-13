package com.siftalpha.studio.project

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * 基于 Android Storage Access Framework (SAF) 的项目目录访问层。
 *
 * 文件浏览采用按目录读取：UI 首次只读取项目根目录，展开文件夹时才读取直接子项。
 * 全量递归扫描只保留给搜索、最近文件定位等明确需要全项目遍历的能力。
 *
 * 仍然不申请 MANAGE_EXTERNAL_STORAGE，只访问用户已授权的项目根目录。
 */
class ProjectStore(private val context: Context) {

    data class ProjectSummary(
        val name: String,
        val description: String,
        val entry: String,
        val run: String,
        val source: String,
        val documentId: String,
    )

    data class FileNode(
        val name: String,
        val relativePath: String,
        val documentId: String,
        val mimeType: String,
        val depth: Int,
        val isDirectory: Boolean,
    )

    data class SearchHit(
        val file: FileNode,
        val lineNumber: Int?,
        val preview: String,
    )

    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun rootUri(): Uri? = prefs.getString(KEY_ROOT_URI, null)?.let(Uri::parse)

    fun saveRootUri(uri: Uri) {
        prefs.edit().putString(KEY_ROOT_URI, uri.toString()).apply()
    }

    fun rootDisplayName(): String {
        val treeUri = rootUri() ?: return "尚未选择"
        val rootDoc = rootDocumentUri(treeUri)
        resolver.query(
            rootDoc,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "项目目录"
        }
        return "项目目录"
    }

    fun listProjects(): List<ProjectSummary> {
        val treeUri = rootUri() ?: return emptyList()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        return listChildren(treeUri, rootId)
            .asSequence()
            .filter { it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR }
            .filterNot { it.name.startsWith(".") }
            .map { directory -> readProject(treeUri, directory) }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    fun createProject(name: String, description: String): ProjectSummary {
        require(PROJECT_NAME.matches(name)) {
            "项目名只能包含英文字母、数字、点、下划线和短横线"
        }

        val treeUri = rootUri() ?: error("请先选择项目目录")
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val existing = listChildren(treeUri, rootId)
        require(existing.none { it.name.equals(name, ignoreCase = true) }) {
            "项目 $name 已存在"
        }

        val rootDoc = rootDocumentUri(treeUri)
        val projectUri = DocumentsContract.createDocument(
            resolver,
            rootDoc,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: error("无法创建项目目录")

        val projectId = DocumentsContract.getDocumentId(projectUri)
        createTextFile(treeUri, projectId, "main.py", """#!/usr/bin/env python3


def main():
    print("Hello from $name")


if __name__ == "__main__":
    main()
""")
        createTextFile(treeUri, projectId, "requirements.txt", "")

        val metadata = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("entry", "main.py")
            put("run", "python main.py")
            put("type", "python")
            put("source", JSONObject().apply {
                put("type", "created")
                put("label", "SiftAlpha Studio")
            })
        }
        createTextFile(treeUri, projectId, ".project.json", metadata.toString(2) + "\n")

        return ProjectSummary(
            name = name,
            description = description,
            entry = "main.py",
            run = "python main.py",
            source = "SiftAlpha Studio",
            documentId = projectId,
        )
    }

    fun deleteProject(project: ProjectSummary) {
        val treeUri = rootUri() ?: error("项目目录不可用")
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, project.documentId)
        check(DocumentsContract.deleteDocument(resolver, uri)) { "删除失败" }
    }

    /**
     * 只读取一个目录的直接子项。文件树 UI 必须优先使用这个接口，禁止首屏递归整个项目。
     */
    fun listProjectChildren(
        projectDocumentId: String,
        parent: FileNode? = null,
    ): List<FileNode> {
        require(parent == null || parent.isDirectory) { "只能读取文件夹的子项" }
        val treeUri = rootUri() ?: error("项目目录不可用")
        val parentId = parent?.documentId ?: projectDocumentId
        val parentPath = parent?.relativePath.orEmpty()
        val depth = (parent?.depth ?: -1) + 1
        return sortedVisibleChildren(treeUri, parentId)
            .take(MAX_TREE_ITEMS)
            .map { child ->
                val isDirectory = child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                FileNode(
                    name = child.name,
                    relativePath = joinPath(parentPath, child.name),
                    documentId = child.documentId,
                    mimeType = child.mimeType,
                    depth = depth,
                    isDirectory = isDirectory,
                )
            }
    }

    /**
     * 全项目递归扫描只用于搜索 / 最近文件 / 精确定位等需要完整视图的后台操作。
     */
    fun listProjectTree(projectDocumentId: String): List<FileNode> {
        val treeUri = rootUri() ?: error("项目目录不可用")
        val result = mutableListOf<FileNode>()

        fun walk(parentId: String, depth: Int, parentPath: String) {
            if (depth > MAX_TREE_DEPTH || result.size >= MAX_TREE_ITEMS) return
            for (child in sortedVisibleChildren(treeUri, parentId)) {
                if (result.size >= MAX_TREE_ITEMS) break
                val relativePath = joinPath(parentPath, child.name)
                val isDirectory = child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                result += FileNode(
                    name = child.name,
                    relativePath = relativePath,
                    documentId = child.documentId,
                    mimeType = child.mimeType,
                    depth = depth,
                    isDirectory = isDirectory,
                )
                if (isDirectory) walk(child.documentId, depth + 1, relativePath)
            }
        }

        walk(projectDocumentId, 0, "")
        return result
    }

    fun findProjectNode(
        projectDocumentId: String,
        documentId: String,
    ): FileNode? =
        listProjectTree(projectDocumentId).firstOrNull { it.documentId == documentId }

    fun createProjectFile(
        projectDocumentId: String,
        parent: FileNode?,
        rawName: String,
    ): FileNode {
        val name = validateEntryName(rawName)
        require(parent == null || parent.isDirectory) { "只能在文件夹中创建文件" }

        val treeUri = rootUri() ?: error("项目目录不可用")
        val parentId = parent?.documentId ?: projectDocumentId
        require(listChildren(treeUri, parentId).none { it.name.equals(name, ignoreCase = true) }) {
            "$name 已存在"
        }

        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val fileUri = createExactDocument(parentUri, name, "text/plain")
        val actualMime = documentMimeType(fileUri) ?: "application/octet-stream"

        val path = joinPath(parent?.relativePath.orEmpty(), name)
        return FileNode(
            name = name,
            relativePath = path,
            documentId = DocumentsContract.getDocumentId(fileUri),
            mimeType = actualMime,
            depth = (parent?.depth ?: -1) + 1,
            isDirectory = false,
        )
    }

    fun createProjectFolder(
        projectDocumentId: String,
        parent: FileNode?,
        rawName: String,
    ): FileNode {
        val name = validateEntryName(rawName)
        require(parent == null || parent.isDirectory) { "只能在文件夹中创建子文件夹" }

        val treeUri = rootUri() ?: error("项目目录不可用")
        val parentId = parent?.documentId ?: projectDocumentId
        require(listChildren(treeUri, parentId).none { it.name.equals(name, ignoreCase = true) }) {
            "$name 已存在"
        }

        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val folderUri = DocumentsContract.createDocument(
            resolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: error("无法创建文件夹 $name")

        val path = joinPath(parent?.relativePath.orEmpty(), name)
        return FileNode(
            name = name,
            relativePath = path,
            documentId = DocumentsContract.getDocumentId(folderUri),
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            depth = (parent?.depth ?: -1) + 1,
            isDirectory = true,
        )
    }

    fun renameProjectNode(
        projectDocumentId: String,
        node: FileNode,
        rawName: String,
    ): FileNode {
        val name = validateEntryName(rawName)
        if (name == node.name) return node

        val treeUri = rootUri() ?: error("项目目录不可用")
        val parentPath = node.relativePath.substringBeforeLast('/', "")
        val parentId = if (parentPath.isBlank()) {
            projectDocumentId
        } else {
            listProjectTree(projectDocumentId)
                .firstOrNull { it.isDirectory && it.relativePath == parentPath }
                ?.documentId
                ?: error("无法定位父目录：$parentPath")
        }

        require(
            listChildren(treeUri, parentId).none {
                it.documentId != node.documentId &&
                    it.name.equals(name, ignoreCase = true)
            },
        ) { "$name 已存在" }

        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId)
        val renamedUri = DocumentsContract.renameDocument(resolver, uri, name)
            ?: error("重命名失败")
        val actualName = documentDisplayName(renamedUri)
        require(actualName == null || actualName == name) {
            "Android 文件提供器修改了文件名：$name -> $actualName"
        }

        return node.copy(
            name = name,
            relativePath = joinPath(parentPath, name),
            documentId = DocumentsContract.getDocumentId(renamedUri),
            mimeType = documentMimeType(renamedUri) ?: node.mimeType,
        )
    }

    fun deleteProjectNode(node: FileNode) {
        val treeUri = rootUri() ?: error("项目目录不可用")
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId)
        check(DocumentsContract.deleteDocument(resolver, uri)) {
            "删除失败：${node.relativePath}"
        }
    }

    fun isEditableTextFile(file: FileNode): Boolean =
        !file.isDirectory && ProjectTextFilePolicy.isEditableTextFileName(file.name, file.mimeType)

    fun readProjectTextFile(file: FileNode): String {
        require(!file.isDirectory) { "目录不能作为文本文件打开" }
        require(isEditableTextFile(file)) { "当前文件类型暂不支持文本编辑" }

        val bytes = readFileBytes(file, MAX_EDIT_FILE_BYTES + 1)
        require(bytes.size <= MAX_EDIT_FILE_BYTES) {
            "文件超过 ${MAX_EDIT_FILE_BYTES / 1024} KB，当前编辑器暂不直接打开"
        }
        require(ProjectTextFilePolicy.isSafeUtf8TextPayload(bytes)) {
            "检测到二进制内容，当前编辑器不会打开该文件"
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun writeProjectTextFile(file: FileNode, content: String) {
        require(!file.isDirectory) { "目录不能写入文本" }
        require(isEditableTextFile(file)) { "当前文件类型暂不支持文本编辑" }

        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_EDIT_FILE_BYTES) {
            "文件超过 ${MAX_EDIT_FILE_BYTES / 1024} KB，当前编辑器暂不保存"
        }

        val treeUri = rootUri() ?: error("项目目录不可用")
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId)
        resolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: error("无法写入 ${file.relativePath}")
    }

    fun searchProject(
        projectDocumentId: String,
        rawQuery: String,
    ): List<SearchHit> {
        val query = rawQuery.trim()
        require(query.isNotBlank()) { "请输入搜索内容" }

        val result = mutableListOf<SearchHit>()
        var searchedFileCount = 0

        for (file in listProjectTree(projectDocumentId)) {
            if (result.size >= MAX_SEARCH_RESULTS) break
            if (file.isDirectory) continue

            if (file.relativePath.contains(query, ignoreCase = true)) {
                result += SearchHit(
                    file = file,
                    lineNumber = null,
                    preview = "路径匹配",
                )
                if (result.size >= MAX_SEARCH_RESULTS) break
            }

            if (!isEditableTextFile(file) || searchedFileCount >= MAX_SEARCH_FILES) continue
            searchedFileCount += 1

            val text = readSearchText(file) ?: continue
            var matchesInFile = 0
            text.lineSequence().forEachIndexed { index, line ->
                if (result.size >= MAX_SEARCH_RESULTS || matchesInFile >= MAX_MATCHES_PER_FILE) {
                    return@forEachIndexed
                }
                if (line.contains(query, ignoreCase = true)) {
                    result += SearchHit(
                        file = file,
                        lineNumber = index + 1,
                        preview = line.trim().take(MAX_SEARCH_PREVIEW_CHARS),
                    )
                    matchesInFile += 1
                }
            }
        }

        return result
    }

    fun recordRecentFile(
        projectDocumentId: String,
        file: FileNode,
    ) {
        if (file.isDirectory) return

        val key = recentKey(projectDocumentId)
        val ids = parseRecentIds(prefs.getString(key, null))
            .filterNot { it == file.documentId }
            .toMutableList()
        ids.add(0, file.documentId)

        val array = JSONArray()
        ids.take(MAX_RECENT_FILES).forEach { array.put(it) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun listRecentFiles(projectDocumentId: String): List<FileNode> {
        val ids = parseRecentIds(prefs.getString(recentKey(projectDocumentId), null))
        if (ids.isEmpty()) return emptyList()

        val byId = listProjectTree(projectDocumentId)
            .asSequence()
            .filterNot { it.isDirectory }
            .associateBy { it.documentId }

        return ids.mapNotNull { byId[it] }.take(MAX_RECENT_FILES)
    }

    fun removeRecentFile(
        projectDocumentId: String,
        documentId: String,
    ) {
        val key = recentKey(projectDocumentId)
        val array = JSONArray()
        parseRecentIds(prefs.getString(key, null))
            .filterNot { it == documentId }
            .forEach { array.put(it) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun readProject(treeUri: Uri, directory: ChildDocument): ProjectSummary {
        val children = listChildren(treeUri, directory.documentId)
        val metadata = children.firstOrNull { it.name == ".project.json" }
            ?.let { readText(treeUri, it.documentId) }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: children.firstOrNull { it.name == ".project.json.txt" }
                ?.let { readText(treeUri, it.documentId) }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }

        val explicitEntry = metadata?.optString("entry")?.takeIf { it.isNotBlank() }
        val explicitRun = metadata?.optString("run")?.takeIf { it.isNotBlank() }
        val declaredType = metadata?.optString("type")?.trim()?.lowercase().orEmpty()
        val childNames = children.map { it.name.lowercase() }.toSet()
        val hasNodeRoot = "package.json" in childNames
        val hasPythonRoot = childNames.any { it in PYTHON_ROOT_EVIDENCE } ||
            children.any { it.name.endsWith(".py", ignoreCase = true) }

        val inferredPythonEntry = ENTRY_PRIORITY.firstOrNull { wanted ->
            children.any { it.name == wanted }
        } ?: children.firstOrNull { it.name.endsWith(".py", ignoreCase = true) }?.name

        val nodePrimary = when {
            declaredType in NODE_DECLARED_TYPES -> true
            declaredType.isNotBlank() -> false
            hasNodeRoot && !hasPythonRoot -> true
            else -> false
        }
        val pythonPrimary = when {
            declaredType == "python" || declaredType == "py" -> true
            declaredType.isNotBlank() -> false
            hasPythonRoot && !hasNodeRoot -> true
            else -> false
        }

        val nodeHasStart = if (nodePrimary) {
            children.firstOrNull { it.name.equals("package.json", ignoreCase = true) }
                ?.let { runCatching { JSONObject(readText(treeUri, it.documentId)) }.getOrNull() }
                ?.optJSONObject("scripts")
                ?.optString("start")
                ?.isNotBlank() == true
        } else {
            false
        }

        val entry = when {
            explicitEntry != null -> explicitEntry
            nodePrimary -> "package.json"
            pythonPrimary -> inferredPythonEntry.orEmpty()
            else -> ""
        }
        val run = when {
            explicitRun != null -> explicitRun
            nodePrimary && nodeHasStart -> "npm start"
            pythonPrimary && entry.isNotBlank() -> "python $entry"
            else -> ""
        }

        val name = metadata?.optString("name")?.takeIf { it.isNotBlank() } ?: directory.name
        val description = metadata?.optString("description") ?: ""
        val sourceObject = metadata?.optJSONObject("source")
        val source = when {
            sourceObject == null -> "本地项目"
            sourceObject.optString("repository").isNotBlank() -> {
                val label = sourceObject.optString("label").ifBlank { "GitHub" }
                "$label · ${sourceObject.optString("repository")}"
            }
            sourceObject.optString("label").isNotBlank() -> sourceObject.optString("label")
            sourceObject.optString("type").isNotBlank() -> sourceObject.optString("type")
            else -> "本地项目"
        }

        return ProjectSummary(
            name = name,
            description = description,
            entry = entry,
            run = run,
            source = source,
            documentId = directory.documentId,
        )
    }

    private fun sortedVisibleChildren(treeUri: Uri, parentId: String): List<ChildDocument> =
        listChildren(treeUri, parentId)
            .filterNot { child ->
                child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR &&
                    child.name in SKIPPED_TREE_DIRECTORIES
            }
            .sortedWith(
                compareBy<ChildDocument> {
                    it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR
                }.thenBy { it.name.lowercase() },
            )

    private fun createTextFile(
        treeUri: Uri,
        parentId: String,
        name: String,
        content: String,
    ) {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val fileUri = createExactDocument(parentUri, name, "text/plain")
        resolver.openOutputStream(fileUri, "wt")?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: error("无法写入 $name")
    }

    private fun createExactDocument(parentUri: Uri, name: String, requestedMime: String): Uri {
        val createMime = if (
            requestedMime.startsWith("text/") ||
            name.startsWith(".") ||
            name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
        ) {
            "application/octet-stream"
        } else {
            requestedMime
        }

        var uri = DocumentsContract.createDocument(resolver, parentUri, createMime, name)
            ?: error("无法创建 $name")
        var actualName = documentDisplayName(uri)
        if (actualName != null && actualName != name) {
            uri = DocumentsContract.renameDocument(resolver, uri, name) ?: uri
            actualName = documentDisplayName(uri)
        }
        require(actualName == null || actualName == name) {
            "Android 文件提供器修改了文件名：$name -> $actualName"
        }
        return uri
    }

    private fun documentDisplayName(uri: Uri): String? {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun documentMimeType(uri: Uri): String? {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun readText(treeUri: Uri, documentId: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return resolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
    }

    private fun readFileBytes(
        file: FileNode,
        maxBytes: Int,
    ): ByteArray {
        val treeUri = rootUri() ?: error("项目目录不可用")
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId)
        return resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(maxBytes)
            var offset = 0
            while (offset < buffer.size) {
                val count = input.read(buffer, offset, buffer.size - offset)
                if (count < 0) break
                offset += count
            }
            buffer.copyOf(offset)
        } ?: error("无法读取 ${file.relativePath}")
    }

    private fun readSearchText(file: FileNode): String? {
        return runCatching {
            val bytes = readFileBytes(file, MAX_SEARCH_FILE_BYTES + 1)
            if (bytes.size > MAX_SEARCH_FILE_BYTES) return@runCatching null
            if (!ProjectTextFilePolicy.isSafeUtf8TextPayload(bytes)) return@runCatching null
            bytes.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun listChildren(treeUri: Uri, parentId: String): List<ChildDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val result = mutableListOf<ChildDocument>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
            val nameIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val mimeIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            while (cursor.moveToNext()) {
                result += ChildDocument(
                    documentId = cursor.getString(idIndex),
                    name = cursor.getString(nameIndex) ?: "",
                    mimeType = cursor.getString(mimeIndex) ?: "",
                )
            }
        }
        return result
    }

    private fun validateEntryName(rawName: String): String {
        val name = rawName.trim()
        require(name.isNotBlank()) { "名称不能为空" }
        require(name.length <= MAX_ENTRY_NAME_CHARS) { "名称过长" }
        require(name != "." && name != "..") { "不能使用 $name 作为名称" }
        require('/' !in name && '\\' !in name) { "名称不能包含 / 或 \\" }
        require(name.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "名称包含不支持的字符"
        }
        return name
    }

    private fun joinPath(parent: String, name: String): String =
        if (parent.isBlank()) name else "$parent/$name"

    private fun recentKey(projectDocumentId: String): String =
        "$KEY_RECENT_PREFIX$projectDocumentId"

    private fun parseRecentIds(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) add(value)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    private data class ChildDocument(
        val documentId: String,
        val name: String,
        val mimeType: String,
    )

    companion object {
        private const val PREFS_NAME = "siftalpha_project_store"
        private const val KEY_ROOT_URI = "root_uri"
        private const val KEY_RECENT_PREFIX = "recent_files_"
        private const val MAX_TREE_DEPTH = 12
        private const val MAX_TREE_ITEMS = 1500
        private const val MAX_EDIT_FILE_BYTES = 1024 * 1024
        private const val MAX_ENTRY_NAME_CHARS = 180
        private const val MAX_SEARCH_FILES = 250
        private const val MAX_SEARCH_RESULTS = 100
        private const val MAX_MATCHES_PER_FILE = 4
        private const val MAX_SEARCH_FILE_BYTES = 256 * 1024
        private const val MAX_SEARCH_PREVIEW_CHARS = 120
        private const val MAX_RECENT_FILES = 12

        private val PROJECT_NAME = Regex("^[A-Za-z0-9._-]+$")
        private val ENTRY_PRIORITY = listOf("main.py", "app.py", "run.py", "manage.py")
        private val NODE_DECLARED_TYPES = setOf("node", "nodejs", "javascript", "js")
        private val PYTHON_ROOT_EVIDENCE = setOf(
            "requirements.txt",
            "pyproject.toml",
            "setup.py",
            "setup.cfg",
            "pipfile",
            "poetry.lock",
            "main.py",
            "app.py",
            "run.py",
            "manage.py",
        )
        private val SKIPPED_TREE_DIRECTORIES = setOf(
            ".git",
            ".venv",
            "venv",
            "__pycache__",
            "node_modules",
        )
        private val TEXT_FILENAMES = setOf(
            ".project.json",
            ".gitignore",
            ".env",
            "dockerfile",
            "makefile",
            "pipfile",
            "requirements.txt",
        )
        private val TEXT_EXTENSIONS = setOf(
            "py", "pyi", "txt", "md", "json", "jsonl", "toml", "yaml", "yml",
            "ini", "cfg", "conf", "sh", "bash", "zsh", "fish", "sql", "csv",
            "xml", "html", "htm", "css", "scss", "js", "mjs", "cjs", "ts",
            "tsx", "jsx", "java", "kt", "kts", "gradle", "properties", "env",
            "gitignore", "dockerfile",
        )
    }
}
