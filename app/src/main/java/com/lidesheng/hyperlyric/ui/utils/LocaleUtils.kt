package com.lidesheng.hyperlyric.ui.utils

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.lidesheng.hyperlyric.common.UIConstants

object LocaleUtils {
    const val LANGUAGE_SYSTEM = 0
    const val LANGUAGE_SIMPLIFIED_CHINESE = 1
    const val LANGUAGE_ENGLISH = 2

    fun clearLegacyPlatformLocale(context: Context) {
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
        if (!localeManager.applicationLocales.isEmpty) {
            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    @Composable
    fun ProvideAppLocale(content: @Composable () -> Unit) {
        val baseContext = LocalContext.current
        val baseConfiguration = LocalConfiguration.current
        val prefs = remember(baseContext) {
            baseContext.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        }
        var languageMode by remember {
            mutableIntStateOf(
                prefs.getInt(UIConstants.KEY_APP_LANGUAGE, UIConstants.DEFAULT_APP_LANGUAGE)
            )
        }

        DisposableEffect(prefs) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
                if (key == UIConstants.KEY_APP_LANGUAGE) {
                    languageMode = sharedPrefs.getInt(
                        UIConstants.KEY_APP_LANGUAGE,
                        UIConstants.DEFAULT_APP_LANGUAGE
                    )
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        val localizedConfiguration = remember(baseConfiguration, languageMode) {
            Configuration(baseConfiguration).apply {
                val locales = when (languageMode) {
                    LANGUAGE_SIMPLIFIED_CHINESE -> LocaleList.forLanguageTags("zh-Hans")
                    LANGUAGE_ENGLISH -> LocaleList.forLanguageTags("en")
                    else -> Resources.getSystem().configuration.locales
                }
                setLocales(locales)
            }
        }
        val localizedContext = remember(baseContext, localizedConfiguration) {
            ContextThemeWrapper(baseContext, baseContext.theme).apply {
                applyOverrideConfiguration(localizedConfiguration)
            }
        }

        CompositionLocalProvider(
            LocalContext provides localizedContext,
            LocalConfiguration provides localizedConfiguration,
            content = content
        )
    }
}
