package com.siftalpha.studio.runtime

/**
 * Pure policy layer that decides which Runtime owns the executable project lifecycle.
 *
 * The policy intentionally prefers authoritative root project evidence over aggregate detector scores:
 * a Python project with a nested Node/Vite frontend must stay Python-primary even when the nested
 * frontend contributes several Node evidence files. Explicit recognized metadata remains authoritative.
 */
object ProjectRuntimeExecutionPlanner {

    enum class SelectionSource {
        DECLARED_METADATA,
        ROOT_EVIDENCE,
        SINGLE_RUNTIME_EVIDENCE,
    }

    sealed interface Selection {
        data class Resolved(
            val primary: RuntimeKind,
            val supplemental: List<RuntimeKind>,
            val source: SelectionSource,
            val profile: ProjectRuntimeProfile,
        ) : Selection

        data class Ambiguous(
            val candidates: List<RuntimeKind>,
            val profile: ProjectRuntimeProfile,
        ) : Selection

        data class Unsupported(
            val profile: ProjectRuntimeProfile,
        ) : Selection
    }

    fun select(
        relativePaths: Collection<String>,
        declaredType: String? = null,
    ): Selection {
        val profile = ProjectRuntimeDetector.detect(relativePaths, declaredType)
        val declared = RuntimeKind.fromDeclaredType(declaredType)
        if (declared != null && declared != RuntimeKind.UNKNOWN) {
            return resolved(declared, SelectionSource.DECLARED_METADATA, profile)
        }

        val normalized = relativePaths
            .asSequence()
            .map { it.replace('\\', '/').trim().trim('/') }
            .filter { it.isNotBlank() }
            .toSet()
        val rootNames = normalized
            .asSequence()
            .filterNot { '/' in it }
            .map { it.lowercase() }
            .toSet()

        val rootKinds = buildSet {
            if (rootNames.any { it in PYTHON_ROOT_EVIDENCE }) add(RuntimeKind.PYTHON)
            if ("package.json" in rootNames) add(RuntimeKind.NODE_JS)
            if (rootNames.any { it in JVM_ROOT_EVIDENCE }) add(RuntimeKind.JVM)
            if ("go.mod" in rootNames) add(RuntimeKind.GO)
            if ("cargo.toml" in rootNames) add(RuntimeKind.RUST)
        }.toList()

        if (rootKinds.size == 1) {
            return resolved(rootKinds.single(), SelectionSource.ROOT_EVIDENCE, profile)
        }
        if (rootKinds.size > 1) {
            return Selection.Ambiguous(rootKinds.sortedBy { it.id }, profile)
        }

        val candidateKinds = profile.candidates.map { it.kind }.distinct()
        return when (candidateKinds.size) {
            0 -> Selection.Unsupported(profile)
            1 -> resolved(candidateKinds.single(), SelectionSource.SINGLE_RUNTIME_EVIDENCE, profile)
            else -> Selection.Ambiguous(candidateKinds.sortedBy { it.id }, profile)
        }
    }

    private fun resolved(
        primary: RuntimeKind,
        source: SelectionSource,
        profile: ProjectRuntimeProfile,
    ): Selection.Resolved = Selection.Resolved(
        primary = primary,
        supplemental = profile.candidates
            .asSequence()
            .filter { it.kind != primary && it.score >= ProjectRuntimeProfile.POLYGLOT_SCORE }
            .map { it.kind }
            .distinct()
            .toList(),
        source = source,
        profile = profile,
    )

    private val PYTHON_ROOT_EVIDENCE = setOf(
        "pyproject.toml",
        "requirements.txt",
        "setup.py",
        "setup.cfg",
        "pipfile",
        "poetry.lock",
        "main.py",
        "app.py",
        "run.py",
        "manage.py",
    )

    private val JVM_ROOT_EVIDENCE = setOf(
        "pom.xml",
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
    )
}

/** Node package-manager choice is explicit so unsupported lockfiles can never fall through to npm. */
object NodePackageManagerPolicy {
    enum class Manager {
        NPM,
        PNPM,
        YARN,
    }

    sealed interface Result {
        data class Supported(
            val manager: Manager,
            val installCommand: String,
            val lockfile: String?,
        ) : Result

        data class Unsupported(
            val manager: Manager,
            val evidence: String,
        ) : Result

        data class Ambiguous(
            val evidence: List<String>,
        ) : Result
    }

    fun resolve(rootRelativePaths: Collection<String>): Result {
        val names = rootRelativePaths
            .asSequence()
            .map { it.replace('\\', '/').trim().trim('/') }
            .filter { it.isNotBlank() && '/' !in it }
            .map { it.lowercase() }
            .toSet()

        val npmLock = when {
            "package-lock.json" in names -> "package-lock.json"
            "npm-shrinkwrap.json" in names -> "npm-shrinkwrap.json"
            else -> null
        }
        val managers = buildList {
            if (npmLock != null) add(Manager.NPM)
            if ("pnpm-lock.yaml" in names) add(Manager.PNPM)
            if ("yarn.lock" in names) add(Manager.YARN)
        }.distinct()

        if (managers.size > 1) {
            return Result.Ambiguous(
                evidence = managers.map { manager ->
                    when (manager) {
                        Manager.NPM -> npmLock ?: "npm lockfile"
                        Manager.PNPM -> "pnpm-lock.yaml"
                        Manager.YARN -> "yarn.lock"
                    }
                },
            )
        }

        return when (managers.singleOrNull()) {
            Manager.PNPM -> Result.Unsupported(Manager.PNPM, "pnpm-lock.yaml")
            Manager.YARN -> Result.Unsupported(Manager.YARN, "yarn.lock")
            Manager.NPM -> Result.Supported(Manager.NPM, "npm ci --no-audit --no-fund", npmLock)
            null -> Result.Supported(
                Manager.NPM,
                "bash -c 'rm -f package-lock.json npm-shrinkwrap.json && npm install --no-audit --no-fund --package-lock=false'",
                null,
            )
        }
    }
}

/**
 * Resolves only authoritative Node launch contracts.
 *
 * A declared project run command is explicit author/user intent. Without it, `scripts.start` is the
 * one conventional package.json lifecycle command accepted automatically. `dev`, `serve`, `build`,
 * or any other script is deliberately not guessed.
 */
object NodeStartContractPolicy {
    enum class Source {
        DECLARED_PROJECT_RUN,
        PACKAGE_START_SCRIPT,
    }

    sealed interface Result {
        data class Resolved(
            val command: String,
            val source: Source,
        ) : Result

        data object Missing : Result
    }

    fun resolve(
        declaredRun: String?,
        hasPackageStartScript: Boolean,
    ): Result {
        val explicit = declaredRun?.trim().orEmpty()
        if (explicit.isNotBlank()) {
            return Result.Resolved(explicit, Source.DECLARED_PROJECT_RUN)
        }
        if (hasPackageStartScript) {
            return Result.Resolved("npm start", Source.PACKAGE_START_SCRIPT)
        }
        return Result.Missing
    }
}
