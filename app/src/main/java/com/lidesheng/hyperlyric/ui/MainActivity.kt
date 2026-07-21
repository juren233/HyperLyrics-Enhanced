package com.lidesheng.hyperlyric.ui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lidesheng.hyperlyric.common.UIConstants
import com.lidesheng.hyperlyric.ui.navigation.AppNavigation
import com.lidesheng.hyperlyric.ui.navigation.Route
import com.lidesheng.hyperlyric.ui.utils.LocaleUtils
import com.lidesheng.hyperlyric.ui.utils.ThemeUtils

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(UIConstants.PREF_NAME, MODE_PRIVATE)
        val themeMode = prefs.getInt(UIConstants.KEY_THEME_MODE, UIConstants.DEFAULT_THEME_MODE)
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = when (themeMode) {
            1, 4 -> false
            2, 5 -> true
            else -> systemDark
        }
        window.setBackgroundDrawable(ColorDrawable(if (isDark) Color.BLACK else 0xFFF7F7F7.toInt()))

        val setupCompleted = prefs.getBoolean(UIConstants.KEY_SETUP_COMPLETED, UIConstants.DEFAULT_SETUP_COMPLETED)
        
        val excludeFromRecents = prefs.getBoolean(UIConstants.KEY_EXCLUDE_FROM_RECENTS, UIConstants.DEFAULT_EXCLUDE_FROM_RECENTS)
        if (excludeFromRecents) {
            try {
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                am.appTasks?.forEach { it.setExcludeFromRecents(true) }
            } catch (_: Exception) { }
        }

        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false

        setContent {
            LocaleUtils.ProvideAppLocale {
                ThemeUtils.MiuixThemeWrapper {
                    AppNavigation(startRoute = if (setupCompleted) Route.Main else Route.Setup)
                }
            }
        }
    }
}
