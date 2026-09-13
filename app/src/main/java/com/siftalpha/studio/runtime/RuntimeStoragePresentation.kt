package com.siftalpha.studio.runtime

/**
 * Presentation policy for the Multi-Runtime Storage Manager.
 *
 * Storage ownership remains project/runtime-id based in [RuntimeStorageController]. This layer only
 * decides how registered Runtime components are grouped into user-facing Runtime pages. Human-language
 * UI copy belongs to Android resources (`values`, `values-en`, `values-ja`, `values-ko`, `values-zh-rTW`),
 * not this runtime-domain policy. Runtime family display names therefore stay limited to invariant
 * technology names (Python / Node.js) that do not require translation.
 */
object RuntimeStoragePresentation {

    data class RuntimeFamily(
        val id: String,
        val displayName: String,
        val componentIds: Set<String>,
    )

    const val FAMILY_PYTHON = "PYTHON"
    const val FAMILY_NODE_JS = "NODE_JS"

    val families: List<RuntimeFamily> = listOf(
        RuntimeFamily(
            id = FAMILY_PYTHON,
            displayName = "Python",
            componentIds = setOf(RuntimeStorageController.COMPONENT_PYTHON_VENV),
        ),
        RuntimeFamily(
            id = FAMILY_NODE_JS,
            displayName = "Node.js",
            componentIds = setOf(
                RuntimeStorageController.COMPONENT_NODE_PRIMARY,
                RuntimeStorageController.COMPONENT_NODE_SUPPLEMENTAL,
            ),
        ),
    )

    private val groupedComponentIds: Set<String> = families
        .flatMapTo(linkedSetOf()) { it.componentIds }

    fun familyComponents(
        usage: RuntimeStorageController.ProjectUsage,
        family: RuntimeFamily,
    ): List<RuntimeStorageController.ComponentUsage> = usage.components
        .filter { it.componentId in family.componentIds }
        .filter(::isMeaningfulComponent)

    fun familySizeKb(
        usage: RuntimeStorageController.ProjectUsage,
        family: RuntimeFamily,
    ): Long = familyComponents(usage, family).sumOf { it.sizeKb }

    /**
     * Overview is intentionally complete: it shows every meaningful project-owned component so the
     * project total can be explained directly on the overview card. Runtime-specific pages remain the
     * filtered operational views. The same compatibility filter still hides legacy empty Node scratch
     * directories, while unknown future Runtime components remain visible instead of being discarded.
     */
    fun overviewComponents(
        usage: RuntimeStorageController.ProjectUsage,
    ): List<RuntimeStorageController.ComponentUsage> = usage.components.filter(::isMeaningfulComponent)

    fun familiesFor(
        usage: RuntimeStorageController.ProjectUsage,
    ): List<RuntimeFamily> = families.filter { family -> familyComponents(usage, family).isNotEmpty() }

    internal fun isMeaningfulComponent(component: RuntimeStorageController.ComponentUsage): Boolean {
        if (component.sizeKb <= 0L) return false

        /*
         * Alpha11 and earlier supplemental-Node status checks created a tiny scratch workspace even
         * when no Vite component existed. On the Ubuntu filesystems used by Studio this residual
         * directory is reported as 4 KiB and must not be presented as a real Node Runtime. This is a
         * narrowly-scoped compatibility filter; primary Node workspaces and every non-Node component
         * remain fully visible regardless of size.
         */
        if (
            component.componentId == RuntimeStorageController.COMPONENT_NODE_SUPPLEMENTAL &&
            component.sizeKb <= LEGACY_EMPTY_NODE_SUPPLEMENTAL_MAX_KB
        ) {
            return false
        }
        return true
    }

    private const val LEGACY_EMPTY_NODE_SUPPLEMENTAL_MAX_KB = 4L
}
