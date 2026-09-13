package com.siftalpha.studio.runtime

import com.siftalpha.studio.project.ProjectConfigurationInspector

/** Pure configuration readiness evaluation used by Runtime Center before process launch. */
object ProjectConfigurationPreflight {

    data class Result(
        val requiredCount: Int,
        val configuredRequiredCount: Int,
        val missingRequired: List<ProjectConfigurationInspector.Requirement>,
        val credentialCandidateCount: Int,
    ) {
        val ready: Boolean
            get() = missingRequired.isEmpty()
    }

    fun evaluate(
        profile: ProjectConfigurationInspector.Profile,
        protectedConfiguredKeys: Set<String>,
    ): Result {
        val configured = profile.configuredProjectEnvKeys + protectedConfiguredKeys
        val required = profile.required
        val missing = required.filterNot { it.name in configured }
        return Result(
            requiredCount = required.size,
            configuredRequiredCount = required.size - missing.size,
            missingRequired = missing,
            credentialCandidateCount = profile.credentialCandidates.size,
        )
    }
}
