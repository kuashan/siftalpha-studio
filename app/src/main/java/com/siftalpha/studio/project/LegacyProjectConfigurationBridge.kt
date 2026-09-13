package com.siftalpha.studio.project

/**
 * Migration bridge from the original `secrets.binanceApi` metadata to the generic `requiredEnv`
 * model. Only an explicit REQUIRED policy is bridged; UNSPECIFIED must never become a requirement.
 */
object LegacyProjectConfigurationBridge {
    const val BINANCE_API_KEY_ENV = "SIFTALPHA_BINANCE_API_KEY"
    const val BINANCE_API_SECRET_ENV = "SIFTALPHA_BINANCE_API_SECRET"

    fun augment(
        profile: ProjectConfigurationInspector.Profile,
        policy: ProjectSecretPolicyInspector.Policy,
    ): ProjectConfigurationInspector.Profile {
        if (policy.binanceApi != ProjectSecretPolicyInspector.BinanceApiPolicy.REQUIRED) return profile

        val requirements = profile.requirements.toMutableList()
        val declared = requirements.mapTo(linkedSetOf()) { it.name }
        if (BINANCE_API_KEY_ENV !in declared) {
            requirements += ProjectConfigurationInspector.Requirement(
                name = BINANCE_API_KEY_ENV,
                secret = true,
                required = true,
                description = "Binance API Key",
            )
        }
        if (BINANCE_API_SECRET_ENV !in declared) {
            requirements += ProjectConfigurationInspector.Requirement(
                name = BINANCE_API_SECRET_ENV,
                secret = true,
                required = true,
                description = "Binance API Secret",
            )
        }
        return profile.copy(requirements = requirements)
    }
}
