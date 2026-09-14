plugins {
    id("com.android.application")
}

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
        versionCode = 77
        versionName = "0.7.0-alpha15"
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
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn("testDebugUnitTest")
}
