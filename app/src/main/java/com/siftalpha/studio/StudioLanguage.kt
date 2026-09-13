package com.siftalpha.studio

import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import java.util.Locale

/** App-wide language preference and locale application. */
object StudioLanguage {
    enum class Language(val tag: String, val selfName: String) {
        ZH_CN("zh-CN", "简体中文"),
        ZH_TW("zh-TW", "繁體中文"),
        EN("en", "English"),
        KO("ko", "한국어"),
        JA("ja", "日本語"),
    }

    private const val PREFS = "siftalpha_language"
    private const val KEY = "language_tag"

    fun current(context: Context): Language {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales != null && !locales.isEmpty) {
                fromTag(locales[0].toLanguageTag())?.let { return it }
            }
        }
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return fromTag(stored) ?: Language.ZH_CN
    }

    fun set(activity: Activity, language: Language) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, language.tag)
            .apply()

        if (Build.VERSION.SDK_INT >= 33) {
            val manager = activity.getSystemService(LocaleManager::class.java)
            manager.applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else {
            applyLegacy(activity.applicationContext, language)
            activity.recreate()
        }
    }

    fun showPicker(activity: Activity) {
        val options = Language.entries
        val currentIndex = options.indexOf(current(activity)).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.language_title))
            .setSingleChoiceItems(options.map { it.selfName }.toTypedArray(), currentIndex) { dialog, which ->
                val selected = options.getOrNull(which) ?: return@setSingleChoiceItems
                dialog.dismiss()
                if (selected != current(activity)) set(activity, selected)
            }
            .setNegativeButton(activity.getString(R.string.common_cancel), null)
            .show()
    }

    fun buttonLabel(context: Context): String =
        context.getString(R.string.language_button, current(context).selfName)

    fun applyPersisted(context: Context) {
        val language = current(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, language.tag)
            .apply()

        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            if (manager != null && manager.applicationLocales.isEmpty) {
                manager.applicationLocales = LocaleList.forLanguageTags(language.tag)
            }
        } else {
            applyLegacy(context, language)
        }
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val locale = localeFor(current(base))
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    private fun applyLegacy(context: Context, language: Language) {
        val locale = localeFor(language)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    private fun localeFor(language: Language): Locale = Locale.forLanguageTag(language.tag)

    private fun fromTag(raw: String?): Language? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.replace('_', '-').lowercase(Locale.ROOT)
        return when {
            normalized.startsWith("zh-tw") || normalized.startsWith("zh-hant") || normalized.startsWith("zh-hk") -> Language.ZH_TW
            normalized.startsWith("zh") -> Language.ZH_CN
            normalized.startsWith("en") -> Language.EN
            normalized.startsWith("ko") -> Language.KO
            normalized.startsWith("ja") -> Language.JA
            else -> null
        }
    }
}

internal object StudioImeInsetPolicy {
    fun bottomPadding(baseBottom: Int, imeBottom: Int, imeVisible: Boolean): Int =
        if (imeVisible) baseBottom + imeBottom.coerceAtLeast(0) else baseBottom
}

open class StudioActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(StudioLanguage.wrap(newBase))
    }

    /**
     * Android 15+ enforces edge-to-edge for this target SDK. adjustResize still supplies IME insets,
     * but custom View hierarchies must consume those insets so focused editors are not covered by the
     * keyboard. Applying this once at the shared Activity boundary protects every full-screen Studio
     * editing surface (project editor, terminal input and future View-based editors) without changing
     * Android 14-and-earlier layout behavior.
     */
    override fun setContentView(view: View?) {
        super.setContentView(view)
        if (view != null) installImeInsetProtection(view)
    }

    private fun installImeInsetProtection(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        root.setOnApplyWindowInsetsListener { target, insets ->
            val imeVisible = insets.isVisible(WindowInsets.Type.ime())
            val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            val bottom = StudioImeInsetPolicy.bottomPadding(baseBottom, imeBottom, imeVisible)

            if (
                target.paddingLeft != baseLeft ||
                target.paddingTop != baseTop ||
                target.paddingRight != baseRight ||
                target.paddingBottom != bottom
            ) {
                target.setPadding(baseLeft, baseTop, baseRight, bottom)
            }

            if (imeVisible) {
                target.post {
                    when (val focused = currentFocus) {
                        is EditText -> {
                            val cursor = focused.selectionStart.coerceAtLeast(0)
                            focused.bringPointIntoView(cursor)
                        }
                        null -> Unit
                        else -> focused.requestRectangleOnScreen(
                            Rect(0, 0, focused.width, focused.height),
                            false,
                        )
                    }
                }
            }
            insets
        }
        root.requestApplyInsets()
    }
}
