package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStoragePresentationTest {

    @Test
    fun `runtime families keep Python and Node project components on separate pages`() {
        val usage = RuntimeStorageController.ProjectUsage(
            folderName = "mixed-project",
            runtimeId = "mixed-project-a1",
            sizeKb = 1_000,
            components = listOf(
                RuntimeStorageController.ComponentUsage(
                    RuntimeStorageController.COMPONENT_PYTHON_VENV,
                    600,
                ),
                RuntimeStorageController.ComponentUsage(
                    RuntimeStorageController.COMPONENT_NODE_PRIMARY,
                    300,
                ),
                RuntimeStorageController.ComponentUsage(
                    RuntimeStorageController.COMPONENT_RUNTIME_STATE,
                    100,
                ),
            ),
        )

        val python = RuntimeStoragePresentation.families.single {
            it.id == RuntimeStoragePresentation.FAMILY_PYTHON
        }
        val node = RuntimeStoragePresentation.families.single {
            it.id == RuntimeStoragePresentation.FAMILY_NODE_JS
        }

        assertEquals(
            listOf(RuntimeStorageController.COMPONENT_PYTHON_VENV),
            RuntimeStoragePresentation.familyComponents(usage, python).map { it.componentId },
        )
        assertEquals(
            listOf(RuntimeStorageController.COMPONENT_NODE_PRIMARY),
            RuntimeStoragePresentation.familyComponents(usage, node).map { it.componentId },
        )
        assertEquals(600L, RuntimeStoragePresentation.familySizeKb(usage, python))
        assertEquals(300L, RuntimeStoragePresentation.familySizeKb(usage, node))
        assertEquals(
            listOf(
                RuntimeStorageController.COMPONENT_PYTHON_VENV,
                RuntimeStorageController.COMPONENT_NODE_PRIMARY,
                RuntimeStorageController.COMPONENT_RUNTIME_STATE,
            ),
            RuntimeStoragePresentation.overviewComponents(usage).map { it.componentId },
        )
    }

    @Test
    fun `legacy four kilobyte supplemental Node scratch directory is not a Node Runtime`() {
        val usage = RuntimeStorageController.ProjectUsage(
            folderName = "python-project",
            runtimeId = "python-project-a1",
            sizeKb = 604,
            components = listOf(
                RuntimeStorageController.ComponentUsage(
                    RuntimeStorageController.COMPONENT_PYTHON_VENV,
                    600,
                ),
                RuntimeStorageController.ComponentUsage(
                    RuntimeStorageController.COMPONENT_NODE_SUPPLEMENTAL,
                    4,
                ),
            ),
        )
        val node = RuntimeStoragePresentation.families.single {
            it.id == RuntimeStoragePresentation.FAMILY_NODE_JS
        }

        assertTrue(RuntimeStoragePresentation.familyComponents(usage, node).isEmpty())
        assertEquals(
            listOf(RuntimeStoragePresentation.FAMILY_PYTHON),
            RuntimeStoragePresentation.familiesFor(usage).map { it.id },
        )
        assertEquals(
            listOf(RuntimeStorageController.COMPONENT_PYTHON_VENV),
            RuntimeStoragePresentation.overviewComponents(usage).map { it.componentId },
        )
    }

    @Test
    fun `nontrivial supplemental Node workspace remains visible`() {
        val component = RuntimeStorageController.ComponentUsage(
            RuntimeStorageController.COMPONENT_NODE_SUPPLEMENTAL,
            8,
        )

        assertTrue(RuntimeStoragePresentation.isMeaningfulComponent(component))
    }

    @Test
    fun `future unregistered components remain visible on overview`() {
        val usage = RuntimeStorageController.ProjectUsage(
            folderName = "future-project",
            runtimeId = "future-project-a1",
            sizeKb = 321,
            components = listOf(
                RuntimeStorageController.ComponentUsage("RUST_WORKSPACE", 321),
            ),
        )

        assertEquals(
            listOf("RUST_WORKSPACE"),
            RuntimeStoragePresentation.overviewComponents(usage).map { it.componentId },
        )
        assertFalse(RuntimeStoragePresentation.families.any {
            "RUST_WORKSPACE" in it.componentIds
        })
    }

    @Test
    fun `runtime family names contain only invariant technology names`() {
        assertEquals(
            listOf("Python", "Node.js"),
            RuntimeStoragePresentation.families.map { it.displayName },
        )
    }
}
