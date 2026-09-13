package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRuntimeDetectorTest {

    @Test
    fun `daily stock analysis style repository is detected as python`() {
        val profile = ProjectRuntimeDetector.detect(
            listOf(
                "main.py",
                "requirements.txt",
                ".env.example",
                "src/services/analysis.py",
                "api/server.py",
                "docs/README.md",
            ),
        )

        assertEquals(RuntimeKind.PYTHON, profile.primary?.kind)
        assertFalse(profile.isPolyglot)
        assertTrue(profile.candidate(RuntimeKind.PYTHON)!!.evidence.contains("requirements.txt"))
    }

    @Test
    fun `root package json is detected as node js`() {
        val profile = ProjectRuntimeDetector.detect(
            listOf("package.json", "package-lock.json", "src/index.ts"),
        )

        assertEquals(RuntimeKind.NODE_JS, profile.primary?.kind)
        assertNull(profile.candidate(RuntimeKind.PYTHON))
    }

    @Test
    fun `python backend and nested node frontend are kept as multiple runtime candidates`() {
        val profile = ProjectRuntimeDetector.detect(
            listOf(
                "pyproject.toml",
                "backend/main.py",
                "frontend/package.json",
                "frontend/src/App.tsx",
            ),
        )

        assertEquals(RuntimeKind.PYTHON, profile.primary?.kind)
        assertNotNull(profile.candidate(RuntimeKind.NODE_JS))
        assertTrue(profile.isPolyglot)
    }

    @Test
    fun `strong root marker is not overwhelmed by many weak nested source files`() {
        val paths = mutableListOf("pyproject.toml", "backend/main.py")
        repeat(200) { index -> paths += "frontend/src/component$index.tsx" }

        val profile = ProjectRuntimeDetector.detect(paths)

        assertEquals(RuntimeKind.PYTHON, profile.primary?.kind)
        assertNotNull(profile.candidate(RuntimeKind.NODE_JS))
    }

    @Test
    fun `strong project marker beats stale declared python metadata`() {
        val profile = ProjectRuntimeDetector.detect(
            relativePaths = listOf("package.json", "src/index.ts"),
            declaredType = "python",
        )

        assertEquals(RuntimeKind.NODE_JS, profile.primary?.kind)
        assertNotNull(profile.candidate(RuntimeKind.PYTHON))
    }

    @Test
    fun `dependency directories do not create false runtime candidates`() {
        val profile = ProjectRuntimeDetector.detect(
            listOf(
                "README.md",
                "node_modules/a/package.json",
                ".venv/lib/site-packages/tool.py",
                "target/generated/example.rs",
            ),
        )

        assertTrue(profile.candidates.isEmpty())
    }

    @Test
    fun `jvm go and rust root markers are recognized`() {
        assertEquals(
            RuntimeKind.JVM,
            ProjectRuntimeDetector.detect(listOf("build.gradle.kts", "src/main/kotlin/App.kt")).primary?.kind,
        )
        assertEquals(
            RuntimeKind.GO,
            ProjectRuntimeDetector.detect(listOf("go.mod", "cmd/server/main.go")).primary?.kind,
        )
        assertEquals(
            RuntimeKind.RUST,
            ProjectRuntimeDetector.detect(listOf("Cargo.toml", "src/main.rs")).primary?.kind,
        )
    }
}

class RuntimeAdapterRegistryTest {

    private class FakeAdapter(override val kind: RuntimeKind) : RuntimeAdapter {
        override val supportedActions: Set<RuntimeAction> = setOf(RuntimeAction.STATUS)
        override val environmentRequirements: List<RuntimeEnvironmentRequirement> = emptyList()
    }

    private class CatalogHost : RuntimeCommandHost {
        override fun runtimeSupported(): Boolean = true
        override fun runtimeUnsupportedReason(): String = "unsupported"
        override fun sharedRoot(): String = "/storage/emulated/0/AcodeProjects"
        override fun runtimeId(folderName: String): String = "runtime-id"
        override fun sh(value: String): String = "'$value'"
        override fun wrapUbuntu(inner: String): String = inner
        override fun hostPreamble(): String = ""
        override fun hostProcessHelpers(): String = ""
    }

    @Test
    fun `registry returns adapters in detected candidate order`() {
        val registry = RuntimeAdapterRegistry(
            listOf(FakeAdapter(RuntimeKind.PYTHON), FakeAdapter(RuntimeKind.NODE_JS)),
        )
        val profile = ProjectRuntimeDetector.detect(
            listOf("pyproject.toml", "frontend/package.json"),
        )

        assertEquals(
            listOf(RuntimeKind.PYTHON, RuntimeKind.NODE_JS),
            registry.adaptersFor(profile).map { it.kind },
        )
    }

    @Test
    fun `host catalog exposes python execution support only`() {
        val catalog = RuntimeAdapterCatalog.forHost(CatalogHost())

        assertNotNull(catalog.executableAdapter(RuntimeKind.PYTHON))
        assertNull(catalog.adapter(RuntimeKind.NODE_JS))
        assertNull(catalog.adapter(RuntimeKind.JVM))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `registry rejects duplicate adapter kinds`() {
        RuntimeAdapterRegistry(
            listOf(FakeAdapter(RuntimeKind.PYTHON), FakeAdapter(RuntimeKind.PYTHON)),
        )
    }
}
