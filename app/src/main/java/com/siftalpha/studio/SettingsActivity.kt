package com.siftalpha.studio

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.siftalpha.studio.ui.settings.SettingsScreen
import com.siftalpha.studio.ui.theme.StudioTheme

/** Shared host for new Compose-only Studio surfaces without changing legacy View activities. */
open class StudioComposeActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(StudioLanguage.wrap(newBase))
    }
}

class SettingsActivity : StudioComposeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme {
                SettingsScreen(
                    currentLanguage = StudioLanguage.current(this@SettingsActivity).selfName,
                    versionName = appVersionName(),
                    applicationId = packageName,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onChangeLanguage = { StudioLanguage.showPicker(this@SettingsActivity) },
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "?" }
}
