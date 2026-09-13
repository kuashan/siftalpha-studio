package com.siftalpha.studio.project

/**
 * Explicit environment-template operations layered on ProjectStore.
 *
 * Copying is opt-in, never overwrites an existing target, and removes a partially
 * created target if the write fails. The source template itself is never mutated.
 */
fun ProjectStore.environmentTemplateTargetIfAvailable(
    projectDocumentId: String,
    template: ProjectStore.FileNode,
): String? {
    val targetName = ProjectTextFilePolicy.environmentTemplateTargetName(template.name) ?: return null
    val parent = resolveTemplateParent(projectDocumentId, template)
    val alreadyExists = listProjectChildren(projectDocumentId, parent).any {
        !it.isDirectory && it.name.equals(targetName, ignoreCase = true)
    }
    return targetName.takeUnless { alreadyExists }
}

fun ProjectStore.createEnvironmentFileFromTemplate(
    projectDocumentId: String,
    template: ProjectStore.FileNode,
): ProjectStore.FileNode {
    require(!template.isDirectory) { "环境配置模板不能是文件夹" }
    val targetName = ProjectTextFilePolicy.environmentTemplateTargetName(template.name)
        ?: error("${template.name} 不是受支持的环境配置模板")
    val parent = resolveTemplateParent(projectDocumentId, template)
    require(
        listProjectChildren(projectDocumentId, parent).none {
            it.name.equals(targetName, ignoreCase = true)
        },
    ) { "$targetName 已存在，不会覆盖现有配置" }

    // Read/validate first so unsupported, oversized, or binary templates do not
    // leave an empty target behind.
    val content = readProjectTextFile(template)
    val created = createProjectFile(projectDocumentId, parent, targetName)
    return try {
        writeProjectTextFile(created, content)
        created
    } catch (error: Throwable) {
        runCatching { deleteProjectNode(created) }
            .exceptionOrNull()
            ?.let(error::addSuppressed)
        throw error
    }
}

private fun ProjectStore.resolveTemplateParent(
    projectDocumentId: String,
    template: ProjectStore.FileNode,
): ProjectStore.FileNode? {
    val parentPath = template.relativePath.substringBeforeLast('/', "")
    if (parentPath.isBlank()) return null
    return listProjectTree(projectDocumentId)
        .firstOrNull { it.isDirectory && it.relativePath == parentPath }
        ?: error("无法定位环境配置模板所在目录：$parentPath")
}
