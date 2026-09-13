package com.siftalpha.studio.runtime

/**
 * Android-independent presentation data for the Runtime Storage Breakdown chart.
 *
 * The input remains the authoritative Runtime Storage Manager project model. Values are exposed
 * in bytes for chart proportions, with saturating arithmetic so malformed or unusually large
 * filesystem reports cannot wrap around into a smaller bar.
 */
data class RuntimeStorageChartModel(
    val projects: List<Project>,
    val maxTotalBytes: Long,
) {

    data class Project(
        val projectRuntimeId: String,
        val projectDisplayName: String,
        val totalBytes: Long,
        val components: List<Component>,
        /** The source project key used to route a chart tap to the existing project card. */
        val folderName: String = projectDisplayName,
    ) {
        /** Returns zero when a component is absent from the authoritative usage record. */
        fun componentBytes(kind: ComponentKind): Long =
            components.firstOrNull { it.kind == kind }?.bytes ?: 0L
    }

    data class Component(
        val kind: ComponentKind,
        val bytes: Long,
    )

    enum class ComponentKind {
        PYTHON,
        NODE_JS,
        OTHER,
    }

    val isEmpty: Boolean
        get() = projects.none { it.totalBytes > 0L }

    fun barFraction(project: Project): Double =
        if (maxTotalBytes <= 0L) {
            0.0
        } else {
            (project.totalBytes.toDouble() / maxTotalBytes.toDouble()).coerceIn(0.0, 1.0)
        }

    companion object {
        private const val BYTES_PER_KILOBYTE = 1024L
        private val COMPONENT_ORDER = listOf(
            ComponentKind.PYTHON,
            ComponentKind.NODE_JS,
            ComponentKind.OTHER,
        )

        fun empty(): RuntimeStorageChartModel = RuntimeStorageChartModel(emptyList(), 0L)

        fun from(
            usages: List<RuntimeStorageController.ProjectUsage>,
            displayNameFor: (RuntimeStorageController.ProjectUsage) -> String = {
                it.folderName
            },
        ): RuntimeStorageChartModel = prepare(usages, displayNameFor)

        fun prepare(
            usages: List<RuntimeStorageController.ProjectUsage>,
            displayNameFor: (RuntimeStorageController.ProjectUsage) -> String = {
                it.folderName
            },
        ): RuntimeStorageChartModel {
            val projects = usages.map { usage ->
                val totalBytes = kilobytesToBytes(usage.sizeKb)
                val displayName = displayNameFor(usage).ifBlank { usage.folderName }
                Project(
                    projectRuntimeId = usage.runtimeId,
                    projectDisplayName = displayName,
                    totalBytes = totalBytes,
                    components = normalizedComponents(usage, totalBytes),
                    folderName = usage.folderName,
                )
            }.sortedWith(
                compareByDescending<Project> { it.totalBytes }
                    .thenBy { it.projectDisplayName }
                    .thenBy { it.projectRuntimeId }
                    .thenBy { it.folderName },
            )

            return RuntimeStorageChartModel(
                projects = projects,
                maxTotalBytes = projects.maxOfOrNull { it.totalBytes } ?: 0L,
            )
        }

        private fun normalizedComponents(
            usage: RuntimeStorageController.ProjectUsage,
            totalBytes: Long,
        ): List<Component> {
            if (totalBytes <= 0L) return emptyList()

            val aggregated = linkedMapOf<ComponentKind, Long>()
            usage.components
                .filter(RuntimeStoragePresentation::isMeaningfulComponent)
                .forEach { source ->
                    val bytes = kilobytesToBytes(source.sizeKb)
                    if (bytes <= 0L) return@forEach
                    val kind = when (source.componentId) {
                        RuntimeStorageController.COMPONENT_PYTHON_VENV -> ComponentKind.PYTHON
                        RuntimeStorageController.COMPONENT_NODE_PRIMARY,
                        RuntimeStorageController.COMPONENT_NODE_SUPPLEMENTAL,
                        -> ComponentKind.NODE_JS
                        else -> ComponentKind.OTHER
                    }
                    aggregated[kind] = safeAdd(aggregated[kind] ?: 0L, bytes)
                }

            var remaining = totalBytes
            val capped = mutableListOf<Component>()
            COMPONENT_ORDER.forEach { kind ->
                val amount = minOf(aggregated[kind] ?: 0L, remaining)
                if (amount > 0L) capped += Component(kind, amount)
                remaining -= amount
            }

            // Any authoritative bytes not classified by the existing storage protocol remain
            // visible as Other instead of shortening the project's bar.
            if (remaining > 0L) {
                val otherIndex = capped.indexOfFirst { it.kind == ComponentKind.OTHER }
                if (otherIndex >= 0) {
                    val other = capped[otherIndex]
                    capped[otherIndex] = other.copy(bytes = safeAdd(other.bytes, remaining))
                } else {
                    capped += Component(ComponentKind.OTHER, remaining)
                }
            }
            return capped
        }

        private fun kilobytesToBytes(kilobytes: Long): Long {
            val safeKilobytes = kilobytes.coerceAtLeast(0L)
            if (safeKilobytes == 0L) return 0L
            if (safeKilobytes > Long.MAX_VALUE / BYTES_PER_KILOBYTE) return Long.MAX_VALUE
            return safeKilobytes * BYTES_PER_KILOBYTE
        }

        private fun safeAdd(left: Long, right: Long): Long =
            if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}

/** Shared stable formatter for the Storage Manager's human-readable byte values. */
object RuntimeStorageSizeFormatter {
    private const val BYTES_PER_KILOBYTE = 1024.0
    private const val BYTES_PER_MEGABYTE = BYTES_PER_KILOBYTE * 1024.0
    private const val BYTES_PER_GIGABYTE = BYTES_PER_MEGABYTE * 1024.0

    fun formatKilobytes(kilobytes: Long): String =
        formatBytes(safeKilobytesToBytes(kilobytes))

    fun formatBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L).toDouble()
        return when {
            safeBytes >= BYTES_PER_GIGABYTE ->
                String.format(java.util.Locale.US, "%.2f GB", safeBytes / BYTES_PER_GIGABYTE)
            safeBytes >= BYTES_PER_MEGABYTE ->
                String.format(java.util.Locale.US, "%.1f MB", safeBytes / BYTES_PER_MEGABYTE)
            safeBytes >= BYTES_PER_KILOBYTE ->
                String.format(java.util.Locale.US, "%.1f KB", safeBytes / BYTES_PER_KILOBYTE)
            else -> String.format(java.util.Locale.US, "%.0f B", safeBytes)
        }
    }

    private fun safeKilobytesToBytes(kilobytes: Long): Long {
        val safeKilobytes = kilobytes.coerceAtLeast(0L)
        return if (safeKilobytes > Long.MAX_VALUE / 1024L) {
            Long.MAX_VALUE
        } else {
            safeKilobytes * 1024L
        }
    }
}
