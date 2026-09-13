plugins {
    id("com.android.application")
}

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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn("testDebugUnitTest")
}
