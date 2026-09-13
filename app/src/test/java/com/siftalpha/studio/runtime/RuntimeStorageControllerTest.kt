package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStorageControllerTest {

    @Test
    fun `snapshot parser aggregates multi-runtime project components and shared storage`() {
        val snapshot = RuntimeStorageController.parseSnapshot(
            """
                SIFTALPHA_STORAGE_CACHE_KB=180000
                SIFTALPHA_STORAGE_UBUNTU=INSTALLED
                SIFTALPHA_STORAGE_UBUNTU_TOTAL_KB=3200000
                SIFTALPHA_STORAGE_UBUNTU_BASE_KB=1500000
                SIFTALPHA_STORAGE_PROJECT_RUNTIME_KB=1050000
                SIFTALPHA_STORAGE_SHARED_RUNTIME_KB=120000
                SIFTALPHA_STORAGE_TOOLCHAINS_KB=300000
                SIFTALPHA_STORAGE_PIP_CACHE_KB=75000
                SIFTALPHA_STORAGE_NPM_CACHE_KB=125000
                SIFTALPHA_STORAGE_APT_CACHE_KB=30000
                SIFTALPHA_STORAGE_PROJECT_COMPONENT=project-a|project-a-a1|PYTHON_VENV|240000
                SIFTALPHA_STORAGE_PROJECT_COMPONENT=project-a|project-a-a1|NODE_SUPPLEMENTAL_WORKSPACE|110000
                SIFTALPHA_STORAGE_PROJECT_COMPONENT=project-a|project-a-a1|RUNTIME_STATE|10000
                SIFTALPHA_STORAGE_PROJECT=project-a|project-a-a1|360000
                SIFTALPHA_STORAGE_PROJECT_COMPONENT=project-b|project-b-b2|NODE_PRIMARY_WORKSPACE|640000
                SIFTALPHA_STORAGE_PROJECT_COMPONENT=project-b|project-b-b2|RUNTIME_STATE|5000
                SIFTALPHA_STORAGE_PROJECT=project-b|project-b-b2|645000
                SIFTALPHA_STORAGE_ORPHAN_COMPONENT=old-project-c3|NODE_PRIMARY_WORKSPACE|40000
                SIFTALPHA_STORAGE_ORPHAN_COMPONENT=old-project-c3|RUNTIME_STATE|5000
                SIFTALPHA_STORAGE_ORPHAN=old-project-c3|45000
            """.trimIndent(),
        )

        assertTrue(snapshot.ubuntuInstalled)
        assertEquals(3_200_000L, snapshot.ubuntuTotalKb)
        assertEquals(1_500_000L, snapshot.ubuntuBaseKb)
        assertEquals(1_050_000L, snapshot.projectRuntimeKb)
        assertEquals(120_000L, snapshot.sharedRuntimeDataKb)
        assertEquals(300_000L, snapshot.toolchainsKb)
        assertEquals(180_000L, snapshot.downloadCacheKb)
        assertEquals(75_000L, snapshot.pipCacheKb)
        assertEquals(125_000L, snapshot.npmCacheKb)
        assertEquals(30_000L, snapshot.aptCacheKb)

        val nodeProject = snapshot.projects.first()
        assertEquals("project-b", nodeProject.folderName)
        assertEquals(645_000L, nodeProject.sizeKb)
        assertEquals(
            setOf(
                RuntimeStorageController.COMPONENT_NODE_PRIMARY,
                RuntimeStorageController.COMPONENT_RUNTIME_STATE,
            ),
            nodeProject.components.map { it.componentId }.toSet(),
        )

        val mixedProject = snapshot.projects.last()
        assertEquals("project-a", mixedProject.folderName)
        assertEquals(3, mixedProject.components.size)
        assertEquals("old-project-c3", snapshot.orphans.single().runtimeId)
        assertEquals(45_000L, snapshot.orphans.single().sizeKb)
        assertEquals(2, snapshot.orphans.single().components.size)
    }

    @Test
    fun `future safe runtime component ids remain visible instead of being discarded`() {
        val snapshot = RuntimeStorageController.parseSnapshot(
            """
                SIFTALPHA_STORAGE_UBUNTU=INSTALLED
                SIFTALPHA_STORAGE_PROJECT_COMPONENT=future-project|future-a1|RUST_WORKSPACE|321
                SIFTALPHA_STORAGE_PROJECT=future-project|future-a1|321
            """.trimIndent(),
        )

        assertEquals("RUST_WORKSPACE", snapshot.projects.single().components.single().componentId)
        assertEquals(321L, snapshot.projects.single().components.single().sizeKb)
    }

    @Test
    fun `legacy python-only storage protocol remains compatible`() {
        val snapshot = RuntimeStorageController.parseSnapshot(
            """
                SIFTALPHA_STORAGE_CACHE_KB=180000
                SIFTALPHA_STORAGE_UBUNTU=INSTALLED
                SIFTALPHA_STORAGE_UBUNTU_TOTAL_KB=2200000
                SIFTALPHA_STORAGE_UBUNTU_BASE_KB=1100000
                SIFTALPHA_STORAGE_VENVS_KB=900000
                SIFTALPHA_STORAGE_RUNTIME_DATA_KB=100000
                SIFTALPHA_STORAGE_PIP_CACHE_KB=75000
                SIFTALPHA_STORAGE_APT_CACHE_KB=25000
                SIFTALPHA_STORAGE_PROJECT=project-a|project-a-a1|640000
                SIFTALPHA_STORAGE_ORPHAN=old-project-c3|50000
            """.trimIndent(),
        )

        assertEquals(900_000L, snapshot.projectRuntimeKb)
        assertEquals(100_000L, snapshot.sharedRuntimeDataKb)
        assertEquals(900_000L, snapshot.projectEnvironmentsKb)
        assertEquals(100_000L, snapshot.runtimeDataKb)
        assertEquals(0L, snapshot.npmCacheKb)
        assertEquals(0L, snapshot.toolchainsKb)
    }

    @Test
    fun `not installed snapshot still preserves reusable download cache`() {
        val snapshot = RuntimeStorageController.parseSnapshot(
            """
                SIFTALPHA_STORAGE_CACHE_KB=75000
                SIFTALPHA_STORAGE_UBUNTU=NOT_INSTALLED
            """.trimIndent(),
        )

        assertFalse(snapshot.ubuntuInstalled)
        assertEquals(0L, snapshot.ubuntuTotalKb)
        assertEquals(75_000L, snapshot.downloadCacheKb)
        assertTrue(snapshot.projects.isEmpty())
        assertTrue(snapshot.orphans.isEmpty())
    }

    @Test
    fun `invalid orphan identifiers and unsafe component ids are ignored`() {
        val snapshot = RuntimeStorageController.parseSnapshot(
            """
                SIFTALPHA_STORAGE_UBUNTU=INSTALLED
                SIFTALPHA_STORAGE_ORPHAN=../../unsafe|999
                SIFTALPHA_STORAGE_ORPHAN_COMPONENT=safe-runtime-1|../../BAD|777
                SIFTALPHA_STORAGE_ORPHAN_COMPONENT=safe-runtime-1|NODE_PRIMARY_WORKSPACE|123
                SIFTALPHA_STORAGE_ORPHAN=safe-runtime-1|123
            """.trimIndent(),
        )

        assertEquals(1, snapshot.orphans.size)
        assertEquals("safe-runtime-1", snapshot.orphans.single().runtimeId)
        assertEquals(
            RuntimeStorageController.COMPONENT_NODE_PRIMARY,
            snapshot.orphans.single().components.single().componentId,
        )
    }

    @Test
    fun `project directory component registry uses unique non-overlapping roots`() {
        val components = RuntimeStorageController.PROJECT_DIRECTORY_COMPONENTS
        assertEquals(components.size, components.map { it.id }.toSet().size)
        assertEquals(components.size, components.map { it.guestRoot }.toSet().size)
        components.forEach { component ->
            assertTrue(component.guestRoot.startsWith("/root/"))
        }
        components.forEachIndexed { index, component ->
            components.drop(index + 1).forEach { other ->
                assertFalse(
                    component.guestRoot.startsWith("${other.guestRoot}/") ||
                        other.guestRoot.startsWith("${component.guestRoot}/"),
                )
            }
        }
    }

    @Test
    fun `bulk orphan cleanup ids are validated and deduplicated`() {
        assertEquals(
            listOf("orphan-a", "orphan-b"),
            RuntimeStorageController.validatedOrphanIds(
                listOf("orphan-a", "orphan-a", "orphan-b"),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bulk orphan cleanup rejects unsafe runtime ids`() {
        RuntimeStorageController.validatedOrphanIds(listOf("safe-runtime", "../../unsafe"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bulk orphan cleanup rejects empty runtime list`() {
        RuntimeStorageController.validatedOrphanIds(emptyList())
    }
}
