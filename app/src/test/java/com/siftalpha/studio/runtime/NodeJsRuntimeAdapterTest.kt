package com.siftalpha.studio.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeJsRuntimeAdapterTest {

    private class FakeHost : RuntimeCommandHost {
        override fun runtimeSupported(): Boolean = true
        override fun runtimeUnsupportedReason(): String = "unsupported"
        override fun sharedRoot(): String = "/storage/emulated/0/AcodeProjects"
        override fun runtimeId(folderName: String): String = "runtime-id"
        override fun sh(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
        override fun wrapUbuntu(inner: String): String = inner
        override fun hostPreamble(): String = ""
        override fun hostProcessHelpers(): String = ""
    }

    private val project = RuntimeProjectSpec(
        name = "Benchmark",
        folderName = "daily_stock_analysis",
        entry = "main.py",
        run = "python main.py --serve-only",
    )

    @Test
    fun `prepare discovers Vite components and builds them in a runtime local project mirror`() {
        val shell = NodeJsRuntimeAdapter(FakeHost()).prepareDetectedWebComponents(project).shellScript

        assertTrue(shell.contains("vite.config.ts"))
        assertTrue(shell.contains("vite.config.js"))
        assertTrue(shell.contains("/root/siftalpha/node-workspaces/runtime-id"))
        assertTrue(shell.contains("repo_workspace=\"${'$'}work_root/repo\""))
        assertTrue(shell.contains("npm_config_engine_strict=true npm ci --no-audit --no-fund"))
        assertTrue(shell.contains("npm run build"))
        assertTrue(shell.contains("resolve_vite_output_dir"))
        assertTrue(shell.contains("outDir:[[:space:]]*path"))
        assertTrue(shell.contains("__dirname"))
        assertTrue(shell.contains("realpath -m"))
        assertTrue(shell.contains("workspace_output/index.html"))
        assertTrue(shell.contains("SIFTALPHA_NODE_ENV=READY"))
        assertTrue(shell.contains("node-ready-runtime-id.txt"))
    }

    @Test
    fun `prepare snapshot honors VCS ignores and fails closed on an unstable mirror`() {
        val shell = NodeJsRuntimeAdapter(FakeHost()).prepareDetectedWebComponents(project).shellScript

        assertTrue(shell.contains("--exclude-vcs-ignores"))
        assertTrue(shell.contains("set -o pipefail"))
        assertTrue(shell.contains("SIFTALPHA_NODE_DIAG=NODE_WORKSPACE_SNAPSHOT_FAILED"))
        assertTrue(shell.contains("exit 89"))
    }

    @Test
    fun `prepare surfaces npm and Vite failure log tails to the Studio output`() {
        val shell = NodeJsRuntimeAdapter(FakeHost()).prepareDetectedWebComponents(project).shellScript

        assertTrue(shell.contains("SIFTALPHA_NODE_DIAG=NPM_INSTALL_FAILED"))
        assertTrue(shell.contains("SIFTALPHA_NODE_DIAG=NODE_BUILD_FAILED"))
        assertTrue(shell.contains("SIFTALPHA_NODE_LOG_TAIL_BEGIN"))
        assertTrue(shell.contains("tail -n 120 \"${'$'}log\""))
        assertTrue(shell.contains("SIFTALPHA_NODE_LOG_TAIL_END"))
    }

    @Test
    fun `prepare uses verified runtime managed Node LTS instead of distro Node packages`() {
        val shell = NodeJsRuntimeAdapter(FakeHost()).prepareDetectedWebComponents(project).shellScript

        assertTrue(shell.contains("node_version='${NodeJsRuntimeAdapter.MANAGED_NODE_VERSION}'"))
        assertTrue(shell.contains(NodeJsRuntimeAdapter.MANAGED_NODE_BASE_URL))
        assertTrue(shell.contains("SHASUMS256.txt"))
        assertTrue(shell.contains("sha256sum -c -"))
        assertTrue(shell.contains("node-v${'$'}node_version-linux-${'$'}node_arch.tar.xz"))
        assertFalse(shell.contains("apt-get install -y nodejs npm"))
    }

    @Test
    fun `prepare keeps npm dependency tree off Android shared project storage`() {
        val shell = NodeJsRuntimeAdapter(FakeHost()).prepareDetectedWebComponents(project).shellScript

        assertTrue(shell.contains("/root/siftalpha/node-workspaces/runtime-id"))
        assertTrue(shell.contains("--exclude='./node_modules'"))
        assertFalse(shell.contains("npm ci --prefix"))
        assertFalse(shell.contains("npm install --prefix"))
    }

    @Test
    fun `status fingerprints source requires resolved output and pinned toolchain`() {
        val shell = NodeJsRuntimeAdapter(FakeHost()).statusDetectedWebComponents(project).shellScript

        assertTrue(shell.contains("component_fingerprint"))
        assertTrue(shell.contains("resolve_vite_output_dir"))
        assertTrue(shell.contains("source_output/index.html"))
        assertTrue(shell.contains("NODE_RUNTIME_VERSION_CHANGED"))
        assertTrue(shell.contains("SOURCE_CHANGED"))
        assertTrue(shell.contains("BUILD_OUTPUT_MISSING"))
        assertTrue(shell.contains("SIFTALPHA_NODE_ENV=NOT_REQUIRED"))
    }

    @Test
    fun `clean removes project Node workspace but preserves shared managed toolchain`() {
        val shell = NodeJsRuntimeAdapter(FakeHost()).cleanDetectedWebComponents(project).shellScript

        assertTrue(shell.contains("/root/siftalpha/node-workspaces/runtime-id"))
        assertTrue(shell.contains("/root/siftalpha/node-ready-runtime-id.txt"))
        assertFalse(shell.contains("/root/projects/daily_stock_analysis"))
        assertFalse(shell.contains("/root/siftalpha/toolchains"))
        assertTrue(shell.contains("SIFTALPHA_NODE_ENV=CLEANED"))
    }
}
