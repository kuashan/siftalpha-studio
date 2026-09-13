package com.siftalpha.studio.runtime

object ProjectRuntimeDetector {

    fun detect(
        relativePaths: Collection<String>,
        declaredType: String? = null,
    ): ProjectRuntimeProfile {
        val scores = linkedMapOf<RuntimeKind, Int>()
        val evidence = linkedMapOf<RuntimeKind, MutableList<String>>()
        val weakScores = linkedMapOf<RuntimeKind, Int>()
        val weakEvidence = linkedMapOf<RuntimeKind, MutableList<String>>()

        fun add(kind: RuntimeKind, points: Int, reason: String) {
            scores[kind] = (scores[kind] ?: 0) + points
            evidence.getOrPut(kind) { mutableListOf() }.add(reason)
        }

        fun addWeak(kind: RuntimeKind, points: Int, reason: String) {
            weakScores[kind] = minOf(WEAK_EVIDENCE_CAP, (weakScores[kind] ?: 0) + points)
            val items = weakEvidence.getOrPut(kind) { mutableListOf() }
            if (items.size < MAX_WEAK_EVIDENCE_ITEMS) items.add(reason)
        }

        RuntimeKind.fromDeclaredType(declaredType)?.let {
            add(it, 60, "metadata:type=${it.id}")
        }

        relativePaths
            .asSequence()
            .map(::normalize)
            .filter { it.isNotBlank() }
            .filterNot(::isIgnoredPath)
            .forEach { path ->
                val lower = path.lowercase()
                val name = lower.substringAfterLast('/')
                val depth = lower.count { it == '/' }
                val root = depth == 0

                when (name) {
                    "pyproject.toml" -> add(RuntimeKind.PYTHON, if (root) 110 else 70, path)
                    "requirements.txt" -> add(RuntimeKind.PYTHON, if (root) 100 else 60, path)
                    "setup.py", "setup.cfg", "pipfile", "poetry.lock" ->
                        add(RuntimeKind.PYTHON, if (root) 90 else 55, path)

                    "package.json" -> add(RuntimeKind.NODE_JS, if (root) 110 else 70, path)
                    "package-lock.json", "pnpm-lock.yaml", "yarn.lock" ->
                        add(RuntimeKind.NODE_JS, if (root) 90 else 55, path)

                    "pom.xml", "build.gradle", "build.gradle.kts" ->
                        add(RuntimeKind.JVM, if (root) 110 else 70, path)
                    "settings.gradle", "settings.gradle.kts" ->
                        add(RuntimeKind.JVM, if (root) 90 else 55, path)

                    "go.mod" -> add(RuntimeKind.GO, if (root) 110 else 70, path)
                    "go.sum" -> add(RuntimeKind.GO, if (root) 80 else 50, path)

                    "cargo.toml" -> add(RuntimeKind.RUST, if (root) 110 else 70, path)
                    "cargo.lock" -> add(RuntimeKind.RUST, if (root) 80 else 50, path)
                }

                when {
                    name.endsWith(".py") -> addWeak(RuntimeKind.PYTHON, if (root) 18 else 6, path)
                    name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs") ||
                        name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".jsx") ->
                        addWeak(RuntimeKind.NODE_JS, if (root) 14 else 4, path)
                    name.endsWith(".java") || name.endsWith(".kt") ->
                        addWeak(RuntimeKind.JVM, if (root) 14 else 4, path)
                    name.endsWith(".go") -> addWeak(RuntimeKind.GO, if (root) 18 else 6, path)
                    name.endsWith(".rs") -> addWeak(RuntimeKind.RUST, if (root) 18 else 6, path)
                }
            }

        weakScores.forEach { (kind, score) ->
            scores[kind] = (scores[kind] ?: 0) + score
            evidence.getOrPut(kind) { mutableListOf() }.addAll(weakEvidence[kind].orEmpty())
        }

        val candidates = scores
            .asSequence()
            .filter { (kind, score) -> kind != RuntimeKind.UNKNOWN && score >= MINIMUM_SCORE }
            .map { (kind, score) ->
                RuntimeCandidate(
                    kind = kind,
                    score = score,
                    evidence = evidence[kind].orEmpty().distinct(),
                )
            }
            .sortedWith(compareByDescending<RuntimeCandidate> { it.score }.thenBy { it.kind.id })
            .toList()

        return ProjectRuntimeProfile(candidates)
    }

    private fun normalize(raw: String): String =
        raw.replace('\\', '/').trim().trim('/')

    private fun isIgnoredPath(path: String): Boolean {
        val segments = path.lowercase().split('/')
        return segments.any { it in IGNORED_DIRECTORIES }
    }

    private const val MINIMUM_SCORE = 12
    private const val WEAK_EVIDENCE_CAP = 36
    private const val MAX_WEAK_EVIDENCE_ITEMS = 6

    private val IGNORED_DIRECTORIES = setOf(
        ".git",
        ".venv",
        "venv",
        "__pycache__",
        "node_modules",
        "dist",
        "build",
        ".gradle",
        "target",
    )
}
