package com.siftalpha.studio.runtime

enum class RuntimeAction {
    PREPARE,
    INSTALL_DEPENDENCIES,
    START,
    STOP,
    STATUS,
    LOGS,
    CLEAN,
}

data class RuntimeEnvironmentRequirement(
    val command: String,
    val description: String,
    val required: Boolean = true,
)

/**
 * Runtime-neutral project facts required to execute a project.
 *
 * `entry` / `run` remain the presentation-compatible resolved values used by the accepted Python
 * path. `declaredEntry` / `declaredRun` preserve only explicit project metadata so a new Runtime can
 * distinguish author intent from a legacy/default UI fallback. `relativePaths` are authoritative
 * imported-project evidence captured at action time for runtime/package-manager planning.
 */
data class RuntimeProjectSpec(
    val name: String,
    val folderName: String,
    val entry: String,
    val run: String,
    val declaredType: String? = null,
    val declaredEntry: String? = null,
    val declaredRun: String? = null,
    val relativePaths: List<String> = emptyList(),
)

interface RuntimeAdapter {
    val kind: RuntimeKind
    val supportedActions: Set<RuntimeAction>
    val environmentRequirements: List<RuntimeEnvironmentRequirement>

    fun supports(profile: ProjectRuntimeProfile): Boolean = profile.candidate(kind) != null

    fun supports(action: RuntimeAction): Boolean = action in supportedActions
}

/**
 * Adapter contract for a runtime that can actually execute imported projects.
 *
 * Detection support alone must never be treated as execution support. A runtime becomes executable
 * only when an implementation of this interface is registered and passes its acceptance gates.
 */
interface ExecutableRuntimeAdapter : RuntimeAdapter {
    fun prepare(project: RuntimeProjectSpec): RuntimeCommand
    fun start(project: RuntimeProjectSpec): RuntimeCommand
    fun stop(project: RuntimeProjectSpec): RuntimeCommand
    fun status(project: RuntimeProjectSpec): RuntimeCommand
    fun logs(project: RuntimeProjectSpec): RuntimeCommand
    fun clean(project: RuntimeProjectSpec): RuntimeCommand
}

class RuntimeAdapterRegistry(adapters: Collection<RuntimeAdapter>) {
    private val adaptersByKind: Map<RuntimeKind, RuntimeAdapter>

    init {
        val duplicates = adapters.groupBy { it.kind }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Duplicate runtime adapters: ${duplicates.joinToString { it.id }}"
        }
        adaptersByKind = adapters.associateBy { it.kind }
    }

    fun adapter(kind: RuntimeKind): RuntimeAdapter? = adaptersByKind[kind]

    fun executableAdapter(kind: RuntimeKind): ExecutableRuntimeAdapter? =
        adaptersByKind[kind] as? ExecutableRuntimeAdapter

    fun adaptersFor(profile: ProjectRuntimeProfile): List<RuntimeAdapter> =
        profile.candidates.mapNotNull { adaptersByKind[it.kind] }
}
