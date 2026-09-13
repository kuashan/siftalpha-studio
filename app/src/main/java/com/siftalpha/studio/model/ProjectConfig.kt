package com.siftalpha.studio.model

/**
 * 与旧版 DLQS Project Manager 的 .project.json 思路保持兼容。
 * v0.1 先定义模型，v0.2 开始接入项目扫描 / 创建 / 导入。
 */
data class ProjectSource(
    val type: String,
    val label: String? = null,
    val repository: String? = null,
    val url: String? = null,
    val branch: String? = null,
    val importedAt: String? = null,
)

data class ProjectConfig(
    val name: String,
    val description: String = "",
    val entry: String = "main.py",
    val run: String = "python main.py",
    val type: String = "python",
    val source: ProjectSource? = null,
)
