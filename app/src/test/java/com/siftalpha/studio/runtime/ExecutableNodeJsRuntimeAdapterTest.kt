package com.siftalpha.studio.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutableNodeJsRuntimeAdapterTest {

    private class FakeHost : RuntimeCommandHost {
        override fun runtimeSupported(): Boolean = true
        override fun runtimeUnsupportedReason(): String = "unsupported"
        override fun sharedRoot(): String = "/storage/emulated/0/AcodeProjects"
        override fun runtimeId(folderName: String): String = "runtime-id"
        override fun sh(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
        override fun wrapUbuntu(inner: String): String = inner
        override fun hostPreamble(): String = "HOST_PREAMBLE"
        override fun hostProcessHelpers(): String = "HOST_PROCESS_HELPERS"
    }

    private fun project(
        paths: List<String> = listOf("package.json", "package-lock.json", "server.js"),
        declaredRun: String? = null,
    ) = RuntimeProjectSpec(
        name = "Node Fixture",
        folderName = "node-fixture",
        entry = "package.json",
        run = declaredRun ?: "npm start",
        declaredType = "nodejs",
        declaredRun = declaredRun,
        relativePaths = paths,
    )

    @Test
    fun `prepare uses verified managed Node and persistent runtime local executable workspace`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).prepare(project()).shellScript

        assertTrue(shell.contains("node_version='${ManagedNodeToolchainShell.VERSION}'"))
        assertTrue(shell.contains("SHASUMS256.txt"))
        assertTrue(shell.contains("sha256sum -c -"))
        assertTrue(shell.contains("/root/siftalpha/node-exec-workspaces/runtime-id"))
        assertTrue(shell.contains("source-manager.cjs"))
        assertTrue(shell.contains("source-manifest.json"))
        assertTrue(shell.contains("fingerprint"))
        assertTrue(shell.contains("npm_config_engine_strict=true npm ci --no-audit --no-fund"))
        assertTrue(shell.contains("node-exec-ready-runtime-id.txt"))
        assertTrue(shell.contains("SIFTALPHA_NODE_ENV=READY"))
        assertTrue(shell.contains("SIFTALPHA_ENV=READY"))
        assertFalse(shell.contains("apt-get install -y nodejs npm"))
    }

    @Test
    fun `prepare validates package start instead of guessing dev or build`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).prepare(project()).shellScript

        assertTrue(shell.contains("p.scripts.start"))
        assertTrue(shell.contains("NODE_START_CONTRACT_MISSING"))
        assertFalse(shell.contains("npm run dev"))
        assertFalse(shell.contains("npm run build"))
    }

    @Test
    fun `explicit project run is authoritative and bypasses package start validation at runtime`() {
        val explicit = "node custom-server.mjs"
        val prepare = ExecutableNodeJsRuntimeAdapter(FakeHost()).prepare(
            project(
                paths = listOf("package.json", "package-lock.json", "custom-server.mjs"),
                declaredRun = explicit,
            ),
        ).shellScript
        val start = ExecutableNodeJsRuntimeAdapter(FakeHost()).start(
            project(
                paths = listOf("package.json", "package-lock.json", "custom-server.mjs"),
                declaredRun = explicit,
            ),
        ).shellScript

        assertTrue(prepare.contains("START_MODE=DECLARED_RUN"))
        assertTrue(prepare.contains("if [ 0 -eq 1 ]; then"))
        assertTrue(start.contains("node custom-server.mjs"))
        assertTrue(start.contains("bash -c"))
    }

    @Test
    fun `package start is the only automatic launch fallback`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).start(project()).shellScript

        assertTrue(shell.contains("COMMAND=npm start"))
        assertTrue(shell.contains("npm start"))
        assertFalse(shell.contains("npm run dev"))
        assertFalse(shell.contains("npm run serve"))
    }

    @Test
    fun `start proactively runs bounded project scoped Web discovery`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).start(project()).shellScript

        assertTrue(shell.contains("HOST_PROCESS_HELPERS"))
        assertTrue(shell.contains("SIFTALPHA_WEB_AUTODISCOVERY=PASS"))
        assertTrue(shell.contains("PROOT_PROJECT_PID_SCOPE"))
        assertTrue(shell.contains("SIFTALPHA_WEB_URL=http://127.0.0.1:"))
        assertFalse(shell.contains("SIFTALPHA_WEB_ACTIVE_SCAN=START"))
        assertFalse(shell.contains("TERMUX_UID_UNIQUE_HTTP"))
    }

    @Test
    fun `start and already-running recovery include bounded runtime log Web fallback`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).start(project()).shellScript

        assertTrue(shell.contains("SIFTALPHA_WEB_DISCOVERY_SOURCE=RUNTIME_LOG"))
        assertTrue(shell.contains("SIFTALPHA_WEB_LOG_DISCOVERY=NO_CANDIDATE"))
        assertTrue(shell.contains("grep -Eio"))
        assertTrue(shell.contains("127\\.0\\.0\\.1"))
        assertTrue(shell.indexOf("old_pid") < shell.indexOf("SIFTALPHA_WEB_DISCOVERY_SOURCE=RUNTIME_LOG"))
    }

    @Test
    fun `pnpm evidence is rejected instead of silently running npm`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).prepare(
            project(paths = listOf("package.json", "pnpm-lock.yaml", "server.js")),
        ).shellScript

        assertTrue(shell.contains("NODE_PACKAGE_MANAGER_UNSUPPORTED"))
        assertTrue(shell.contains("SIFTALPHA_NODE_PACKAGE_MANAGER=PNPM"))
        assertFalse(shell.contains("npm ci --no-audit --no-fund"))
        assertFalse(shell.contains("npm install --no-audit --no-fund"))
    }

    @Test
    fun `yarn evidence is rejected instead of silently running npm`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).prepare(
            project(paths = listOf("package.json", "yarn.lock", "server.js")),
        ).shellScript

        assertTrue(shell.contains("NODE_PACKAGE_MANAGER_UNSUPPORTED"))
        assertTrue(shell.contains("SIFTALPHA_NODE_PACKAGE_MANAGER=YARN"))
        assertFalse(shell.contains("npm ci --no-audit --no-fund"))
        assertFalse(shell.contains("npm install --no-audit --no-fund"))
    }

    @Test
    fun `conflicting package manager evidence fails closed`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).prepare(
            project(paths = listOf("package.json", "package-lock.json", "yarn.lock", "server.js")),
        ).shellScript

        assertTrue(shell.contains("NODE_PACKAGE_MANAGER_AMBIGUOUS"))
        assertTrue(shell.contains("SIFTALPHA_NODE_ENV=NOT_READY"))
    }

    @Test
    fun `multiple nested package roots without a root package are rejected as ambiguous`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).prepare(
            project(
                paths = listOf(
                    "apps/api/package.json",
                    "apps/api/package-lock.json",
                    "apps/api/server.js",
                    "apps/web/package.json",
                    "apps/web/package-lock.json",
                ),
            ),
        ).shellScript

        assertTrue(shell.contains("NODE_EXECUTABLE_ROOT_AMBIGUOUS"))
        assertTrue(shell.contains("NODE_EXECUTABLE_ROOT_REQUIRED"))
    }

    @Test
    fun `status requires exact source fingerprint workspace and managed Node version`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).status(project()).shellScript

        assertTrue(shell.contains("SOURCE_CHANGED"))
        assertTrue(shell.contains("PREPARE_MARKER_MISSING"))
        assertTrue(shell.contains("WORKSPACE_MISSING"))
        assertTrue(shell.contains("NODE_RUNTIME_VERSION_CHANGED"))
        assertTrue(shell.contains("SIFTALPHA_NODE_ENV=READY"))
        assertTrue(shell.contains("SIFTALPHA_ENV=READY"))
    }

    @Test
    fun `stop uses shared process tree shutdown and reports incomplete stop`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).stop(project()).shellScript

        assertTrue(shell.contains("HOST_PROCESS_HELPERS"))
        assertTrue(shell.contains("siftalpha_stop_tree"))
        assertTrue(shell.contains("SIFTALPHA_ERROR=STOP_INCOMPLETE"))
        assertTrue(shell.contains("SIFTALPHA_STATUS=STOPPED_BY_USER"))
    }

    @Test
    fun `logs retain runtime web listener discovery and ordinary log fallback`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).logs(project()).shellScript

        assertTrue(shell.contains("SIFTALPHA_WEB_AUTODISCOVERY"))
        assertTrue(shell.contains("SIFTALPHA_WEB_URL=http://127.0.0.1"))
        assertTrue(shell.contains("SIFTALPHA_WEB_DISCOVERY_SOURCE=RUNTIME_LOG"))
        assertTrue(shell.contains("=== SiftAlpha Project Log ==="))
    }

    @Test
    fun `clean removes project execution state but preserves source and shared toolchain`() {
        val shell = ExecutableNodeJsRuntimeAdapter(FakeHost()).clean(project()).shellScript

        assertTrue(shell.contains("/root/siftalpha/node-exec-workspaces/runtime-id"))
        assertTrue(shell.contains("node-exec-ready-runtime-id.txt"))
        assertTrue(shell.contains("SIFTALPHA_NODE_RUNTIME_DATA=CLEANED"))
        assertFalse(shell.contains("rm -rf -- '/root/projects/node-fixture'"))
        assertFalse(shell.contains("rm -rf -- '/root/siftalpha/toolchains'"))
    }
}
