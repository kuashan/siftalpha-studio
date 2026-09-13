package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStorageChartModelTest {

    @Test
    fun `projects sort by total descending and use the global maximum for proportions`() {
        val model = RuntimeStorageChartModel.from(
            listOf(
                usage("small", "small-id", 250),
                usage("large", "large-id", 1_000),
                usage("medium", "medium-id", 500),
            ),
        )

        assertEquals(listOf("large", "medium", "small"), model.projects.map { it.projectDisplayName })
        assertEquals(1.0, model.barFraction(model.projects[0]), 0.0001)
        assertEquals(0.5, model.barFraction(model.projects[1]), 0.0001)
        assertEquals(0.25, model.barFraction(model.projects[2]), 0.0001)
    }

    @Test
    fun `Python-only project keeps only the Python segment`() {
        val project = RuntimeStorageChartModel.from(
            listOf(
                usage(
                    "python",
                    "python-id",
                    600,
                    RuntimeStorageController.COMPONENT_PYTHON_VENV to 600,
                ),
            ),
        ).projects.single()

        assertEquals(listOf(RuntimeStorageChartModel.ComponentKind.PYTHON), project.components.map { it.kind })
        assertEquals(600L * 1024L, project.components.single().bytes)
        assertEquals(0L, project.componentBytes(RuntimeStorageChartModel.ComponentKind.NODE_JS))
    }

    @Test
    fun `Node-only project combines supported Node workspaces`() {
        val project = RuntimeStorageChartModel.from(
            listOf(
                usage(
                    "node",
                    "node-id",
                    700,
                    RuntimeStorageController.COMPONENT_NODE_PRIMARY to 400,
                    RuntimeStorageController.COMPONENT_NODE_SUPPLEMENTAL to 300,
                ),
            ),
        ).projects.single()

        assertEquals(listOf(RuntimeStorageChartModel.ComponentKind.NODE_JS), project.components.map { it.kind })
        assertEquals(700L * 1024L, project.components.single().bytes)
        assertEquals(0L, project.componentBytes(RuntimeStorageChartModel.ComponentKind.PYTHON))
    }

    @Test
    fun `mixed project renders Python and Node segments in stable order`() {
        val project = RuntimeStorageChartModel.from(
            listOf(
                usage(
                    "mixed",
                    "mixed-id",
                    1_000,
                    RuntimeStorageController.COMPONENT_NODE_PRIMARY to 300,
                    RuntimeStorageController.COMPONENT_PYTHON_VENV to 600,
                ),
            ),
        ).projects.single()

        assertEquals(
            listOf(RuntimeStorageChartModel.ComponentKind.PYTHON, RuntimeStorageChartModel.ComponentKind.NODE_JS,
                RuntimeStorageChartModel.ComponentKind.OTHER),
            project.components.map { it.kind },
        )
        assertEquals(100L * 1024L, project.components.last().bytes)
    }

    @Test
    fun `zero-byte project is safe and produces an empty visualization state`() {
        val model = RuntimeStorageChartModel.from(
            listOf(
                usage("empty", "empty-id", 0, RuntimeStorageController.COMPONENT_PYTHON_VENV to 20),
            ),
        )

        assertEquals(0L, model.projects.single().totalBytes)
        assertTrue(model.projects.single().components.isEmpty())
        assertTrue(model.isEmpty)
    }

    @Test
    fun `unknown remainder is preserved as Other`() {
        val project = RuntimeStorageChartModel.from(
            listOf(
                usage(
                    "remainder",
                    "remainder-id",
                    1_000,
                    RuntimeStorageController.COMPONENT_PYTHON_VENV to 400,
                    RuntimeStorageController.COMPONENT_RUNTIME_STATE to 100,
                ),
            ),
        ).projects.single()

        assertEquals(
            600L * 1024L,
            project.components.single { it.kind == RuntimeStorageChartModel.ComponentKind.OTHER }.bytes,
        )
        assertEquals(project.totalBytes, project.components.sumOf { it.bytes })
    }

    @Test
    fun `Other-only project remains visible without inventing a known runtime`() {
        val project = RuntimeStorageChartModel.from(
            listOf(
                usage(
                    "other",
                    "other-id",
                    240,
                    RuntimeStorageController.COMPONENT_RUNTIME_STATE to 240,
                ),
            ),
        ).projects.single()

        assertEquals(
            listOf(RuntimeStorageChartModel.ComponentKind.OTHER),
            project.components.map { it.kind },
        )
        assertEquals(project.totalBytes, project.componentBytes(RuntimeStorageChartModel.ComponentKind.OTHER))
        assertEquals(0L, project.componentBytes(RuntimeStorageChartModel.ComponentKind.PYTHON))
        assertEquals(0L, project.componentBytes(RuntimeStorageChartModel.ComponentKind.NODE_JS))
    }

    @Test
    fun `component sum is capped at authoritative total`() {
        val project = RuntimeStorageChartModel.from(
            listOf(
                usage(
                    "inconsistent",
                    "inconsistent-id",
                    100,
                    RuntimeStorageController.COMPONENT_PYTHON_VENV to 80,
                    RuntimeStorageController.COMPONENT_NODE_PRIMARY to 80,
                ),
            ),
        ).projects.single()

        assertTrue(project.components.sumOf { it.bytes } <= project.totalBytes)
        assertEquals(project.totalBytes, project.components.sumOf { it.bytes })
    }

    @Test
    fun `negative totals and component values normalize to zero`() {
        val project = RuntimeStorageChartModel.from(
            listOf(
                usage(
                    "negative",
                    "negative-id",
                    -10,
                    RuntimeStorageController.COMPONENT_PYTHON_VENV to -20,
                ),
            ),
        ).projects.single()

        assertEquals(0L, project.totalBytes)
        assertTrue(project.components.isEmpty())
        assertTrue(RuntimeStorageChartModel.from(listOf(projectUsage("negative", "negative-id", -10))).isEmpty)
    }

    @Test
    fun `very large byte counts do not overflow`() {
        val project = RuntimeStorageChartModel.from(
            listOf(
                usage(
                    "huge",
                    "huge-id",
                    Long.MAX_VALUE,
                    RuntimeStorageController.COMPONENT_PYTHON_VENV to Long.MAX_VALUE,
                ),
            ),
        ).projects.single()

        assertEquals(Long.MAX_VALUE, project.totalBytes)
        assertTrue(project.components.sumOf { it.bytes } <= project.totalBytes)
        assertEquals(1.0, RuntimeStorageChartModel.from(listOf(projectUsage("huge", "huge-id", Long.MAX_VALUE)))
            .barFraction(project), 0.0001)
    }

    @Test
    fun `equal-size projects use name then Runtime ID as deterministic tie breakers`() {
        val model = RuntimeStorageChartModel.from(
            listOf(
                usage("same", "z-id", 100),
                usage("same", "a-id", 100),
                usage("alpha", "b-id", 100),
            ),
        )

        assertEquals(
            listOf("alpha:b-id", "same:a-id", "same:z-id"),
            model.projects.map { "${it.projectDisplayName}:${it.projectRuntimeId}" },
        )
    }

    @Test
    fun `empty project list produces an explicit empty state`() {
        val model = RuntimeStorageChartModel.from(emptyList())

        assertTrue(model.projects.isEmpty())
        assertEquals(0L, model.maxTotalBytes)
        assertTrue(model.isEmpty)
    }

    private fun usage(
        name: String,
        runtimeId: String,
        totalKb: Long,
        vararg components: Pair<String, Long>,
    ): RuntimeStorageController.ProjectUsage = projectUsage(
        name,
        runtimeId,
        totalKb,
        components.map { RuntimeStorageController.ComponentUsage(it.first, it.second) },
    )

    private fun projectUsage(
        name: String,
        runtimeId: String,
        totalKb: Long,
        components: List<RuntimeStorageController.ComponentUsage> = emptyList(),
    ) = RuntimeStorageController.ProjectUsage(name, runtimeId, totalKb, components)
}
