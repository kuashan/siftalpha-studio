plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val trustedKeystorePayload = providers.gradleProperty("SIFTALPHA_DEBUG_KEYSTORE_B64")
    .orElse(providers.environmentVariable("SIFTALPHA_DEBUG_KEYSTORE_B64"))
    .orNull
val trustedStorePassword = providers.gradleProperty("SIFTALPHA_DEBUG_STORE_PASSWORD")
    .orElse(providers.environmentVariable("SIFTALPHA_DEBUG_STORE_PASSWORD"))
    .orNull
val trustedKeyAlias = providers.gradleProperty("SIFTALPHA_DEBUG_KEY_ALIAS")
    .orElse(providers.environmentVariable("SIFTALPHA_DEBUG_KEY_ALIAS"))
    .orNull
val trustedKeyPassword = providers.gradleProperty("SIFTALPHA_DEBUG_KEY_PASSWORD")
    .orElse(providers.environmentVariable("SIFTALPHA_DEBUG_KEY_PASSWORD"))
    .orNull
val trustedKeystoreFile = providers.gradleProperty("siftalphaDebugKeystoreFile")
    .orElse(providers.environmentVariable("SIFTALPHA_DEBUG_KEYSTORE_FILE"))
    .orNull
val trustedSigningEnabled = listOf(
    trustedStorePassword,
    trustedKeyAlias,
    trustedKeyPassword,
    trustedKeystoreFile,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.siftalpha.studio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.siftalpha.studio"
        minSdk = 26
        targetSdk = 36
        versionCode = 86
        versionName = "0.8.0-alpha10"
    }

    buildFeatures {
        compose = true
    }

    if (trustedSigningEnabled) {
        signingConfigs.create("siftalphaTrustedDebug") {
            storeFile = file(trustedKeystoreFile!!)
            storePassword = trustedStorePassword!!
            keyAlias = trustedKeyAlias!!
            keyPassword = trustedKeyPassword!!
        }
    }

    buildTypes {
        getByName("debug") {
            if (trustedSigningEnabled) {
                signingConfig = signingConfigs.getByName("siftalphaTrustedDebug")
            }
        }
        getByName("release") {
            if (trustedSigningEnabled) {
                signingConfig = signingConfigs.getByName("siftalphaTrustedDebug")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn("testDebugUnitTest")
}
