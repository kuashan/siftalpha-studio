package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRuntimeExecutionPlannerTest {

    @Test
    fun pythonRootStaysPrimaryWhenNestedNodeFrontendScoresHighly() {
        val result = ProjectRuntimeExecutionPlanner.select(
            listOf(
                "requirements.txt",
                "main.py",
                "apps/web/package.json",
                "apps/web/package-lock.json",
                "apps/web/vite.config.ts",
                "apps/web/src/main.tsx",
            ),
        )

        assertTrue(result is ProjectRuntimeExecutionPlanner.Selection.Resolved)
        result as ProjectRuntimeExecutionPlanner.Selection.Resolved
        assertEquals(RuntimeKind.PYTHON, result.primary)
        assertEquals(ProjectRuntimeExecutionPlanner.SelectionSource.ROOT_EVIDENCE, result.source)
        assertTrue(RuntimeKind.NODE_JS in result.supplemental)
    }

    @Test
    fun nodeRootIsPrimaryForPureNodeProject() {
        val result = ProjectRuntimeExecutionPlanner.select(
            listOf("package.json", "package-lock.json", "src/server.js"),
        ) as ProjectRuntimeExecutionPlanner.Selection.Resolved

        assertEquals(RuntimeKind.NODE_JS, result.primary)
        assertEquals(ProjectRuntimeExecutionPlanner.SelectionSource.ROOT_EVIDENCE, result.source)
    }

    @Test
    fun explicitRecognizedMetadataWinsOverLayout() {
        val result = ProjectRuntimeExecutionPlanner.select(
            listOf("requirements.txt", "main.py", "package.json"),
            declaredType = "nodejs",
        ) as ProjectRuntimeExecutionPlanner.Selection.Resolved

        assertEquals(RuntimeKind.NODE_JS, result.primary)
        assertEquals(ProjectRuntimeExecutionPlanner.SelectionSource.DECLARED_METADATA, result.source)
    }

    @Test
    fun conflictingRootRuntimeEvidenceIsAmbiguousWithoutMetadata() {
        val result = ProjectRuntimeExecutionPlanner.select(
            listOf("requirements.txt", "main.py", "package.json", "package-lock.json"),
        )

        assertTrue(result is ProjectRuntimeExecutionPlanner.Selection.Ambiguous)
        result as ProjectRuntimeExecutionPlanner.Selection.Ambiguous
        assertTrue(RuntimeKind.PYTHON in result.candidates)
        assertTrue(RuntimeKind.NODE_JS in result.candidates)
    }

    @Test
    fun unknownProjectIsUnsupportedInsteadOfDefaultingToPython() {
        val result = ProjectRuntimeExecutionPlanner.select(
            listOf("README.md", "assets/logo.svg"),
        )

        assertTrue(result is ProjectRuntimeExecutionPlanner.Selection.Unsupported)
    }

    @Test
    fun nestedSingleRuntimeCanResolveWhenRootHasNoRuntimeManifest() {
        val result = ProjectRuntimeExecutionPlanner.select(
            listOf("apps/server/package.json", "apps/server/package-lock.json", "apps/server/server.js"),
        ) as ProjectRuntimeExecutionPlanner.Selection.Resolved

        assertEquals(RuntimeKind.NODE_JS, result.primary)
        assertEquals(ProjectRuntimeExecutionPlanner.SelectionSource.SINGLE_RUNTIME_EVIDENCE, result.source)
    }

    @Test
    fun npmLockUsesDeterministicCiInstall() {
        val result = NodePackageManagerPolicy.resolve(listOf("package.json", "package-lock.json"))
            as NodePackageManagerPolicy.Result.Supported

        assertEquals(NodePackageManagerPolicy.Manager.NPM, result.manager)
        assertEquals("npm ci --no-audit --no-fund", result.installCommand)
        assertEquals("package-lock.json", result.lockfile)
    }

    @Test
    fun npmShrinkwrapUsesDeterministicCiInstallAndPreservesExactEvidence() {
        val result = NodePackageManagerPolicy.resolve(listOf("package.json", "npm-shrinkwrap.json"))
            as NodePackageManagerPolicy.Result.Supported

        assertEquals(NodePackageManagerPolicy.Manager.NPM, result.manager)
        assertEquals("npm ci --no-audit --no-fund", result.installCommand)
        assertEquals("npm-shrinkwrap.json", result.lockfile)
    }

    @Test
    fun packageJsonWithoutLockUsesNpmInstallWithoutCreatingHiddenLockState() {
        val result = NodePackageManagerPolicy.resolve(listOf("package.json"))
            as NodePackageManagerPolicy.Result.Supported

        assertEquals(NodePackageManagerPolicy.Manager.NPM, result.manager)
        assertEquals(
            "bash -c 'rm -f package-lock.json npm-shrinkwrap.json && npm install --no-audit --no-fund --package-lock=false'",
            result.installCommand,
        )
        assertEquals(null, result.lockfile)
    }

    @Test
    fun pnpmLockIsExplicitlyUnsupportedInsteadOfFallingThroughToNpm() {
        val result = NodePackageManagerPolicy.resolve(listOf("package.json", "pnpm-lock.yaml"))

        assertTrue(result is NodePackageManagerPolicy.Result.Unsupported)
        result as NodePackageManagerPolicy.Result.Unsupported
        assertEquals(NodePackageManagerPolicy.Manager.PNPM, result.manager)
    }

    @Test
    fun conflictingPackageManagerLocksAreAmbiguous() {
        val result = NodePackageManagerPolicy.resolve(
            listOf("package.json", "package-lock.json", "yarn.lock"),
        )

        assertTrue(result is NodePackageManagerPolicy.Result.Ambiguous)
    }

    @Test
    fun explicitRunWinsOverPackageStart() {
        val result = NodeStartContractPolicy.resolve(
            declaredRun = "node custom-server.mjs",
            hasPackageStartScript = true,
        ) as NodeStartContractPolicy.Result.Resolved

        assertEquals("node custom-server.mjs", result.command)
        assertEquals(NodeStartContractPolicy.Source.DECLARED_PROJECT_RUN, result.source)
    }

    @Test
    fun packageStartIsOnlyAutomaticFallback() {
        val result = NodeStartContractPolicy.resolve(
            declaredRun = null,
            hasPackageStartScript = true,
        ) as NodeStartContractPolicy.Result.Resolved

        assertEquals("npm start", result.command)
        assertEquals(NodeStartContractPolicy.Source.PACKAGE_START_SCRIPT, result.source)
    }

    @Test
    fun missingStartContractDoesNotGuessDevOrBuild() {
        val result = NodeStartContractPolicy.resolve(
            declaredRun = "   ",
            hasPackageStartScript = false,
        )

        assertEquals(NodeStartContractPolicy.Result.Missing, result)
    }
}
