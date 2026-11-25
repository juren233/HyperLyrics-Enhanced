package com.juren233.hyperlyricsenhanced.ui.utils

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.juren233.hyperlyricsenhanced.common.UIConstants
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.Colors

/** 应用当前的主题设置（配色模式与莫奈主题色），供需要按同一设置自行配色的界面读取。 */
val LocalAppThemeController = staticCompositionLocalOf<ThemeController?> { null }

private val MONET_SCHEME_MODES = setOf(
    ColorSchemeMode.MonetSystem,
    ColorSchemeMode.MonetLight,
    ColorSchemeMode.MonetDark,
)

/** 是否为莫奈取色配色；不在 [ThemeUtils.MiuixThemeWrapper] 内（控制器为 null）时按非莫奈处理。 */
val ThemeController?.isMonet: Boolean get() = this?.colorSchemeMode in MONET_SCHEME_MODES

object ThemeUtils {

    /** KEY_THEME_MODE 存储值 → 配色模式：0 跟随系统、1 浅色、2 深色、3–5 为对应的莫奈取色，其余按跟随系统。 */
    fun colorSchemeModeOf(themeMode: Int): ColorSchemeMode = when (themeMode) {
        0 -> ColorSchemeMode.System
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        3 -> ColorSchemeMode.MonetSystem
        4 -> ColorSchemeMode.MonetLight
        5 -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.System
    }

    /**
     * 按偏好生成主题控制器。整个 App 的主题与设置页强调色色块都用它，色块显示的就是主题实际生成出来的主色。
     * 莫奈模式下：指定了强调色时以它为种子色生成整套配色；「系统」不指定种子色，由 miuix 读取系统调色板。
     */
    fun themeController(themeMode: Int, monetColorIndex: Int): ThemeController = ThemeController(
        colorSchemeMode = colorSchemeModeOf(themeMode),
        keyColor = MonetPalette.keyColorOf(monetColorIndex),
    )

    /** 控制器实际用的是不是深色方案：浅色 / 莫奈浅色为否，深色 / 莫奈深色为是，其余跟随系统。 */
    @Composable
    fun ThemeController.isDarkScheme(): Boolean {
        val systemDark = isSystemInDarkTheme()
        return isDark ?: when (colorSchemeMode) {
            ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
            ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
            else -> systemDark
        }
    }

    /**
     * App 主题实际使用的整套配色：miuix 按 [controller] 生成，再套 [MonetAccentTuning] 的修正。
     * 主题本身、设置页强调色色块和外观预览都从这里取色，保证三处一致。
     */
    @Composable
    fun themeColors(controller: ThemeController, monetColorIndex: Int): Colors {
        val base = controller.currentColors()
        val dark = controller.isDarkScheme()
        val monet = controller.isMonet
        return remember(base, dark, monet, monetColorIndex) {
            MonetAccentTuning.adjust(base, monet = monet, dark = dark, monetColorIndex = monetColorIndex)
        }
    }

    /** 套用 App 主题：外层 MiuixTheme(controller) 提供配色模式等，内层换成 [themeColors] 修正后的配色。 */
    @Composable
    fun AppMiuixTheme(controller: ThemeController, monetColorIndex: Int, content: @Composable () -> Unit) {
        val colors = themeColors(controller, monetColorIndex)
        MiuixTheme(controller = controller) {
            MiuixTheme(colors = colors, content = content)
        }
    }

    @Composable
    fun MiuixThemeWrapper(content: @Composable () -> Unit) {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
        
        var themeMode by remember { 
            mutableIntStateOf(prefs.getInt(UIConstants.KEY_THEME_MODE, UIConstants.DEFAULT_THEME_MODE)) 
        }
        var monetColorIndex by remember { 
            mutableIntStateOf(prefs.getInt(UIConstants.KEY_MONET_COLOR, UIConstants.DEFAULT_MONET_COLOR)) 
        }

        val listener = remember { 
            SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                if (key == UIConstants.KEY_THEME_MODE) {
                    themeMode = p.getInt(UIConstants.KEY_THEME_MODE, UIConstants.DEFAULT_THEME_MODE)
                }
                if (key == UIConstants.KEY_MONET_COLOR) {
                    monetColorIndex = p.getInt(UIConstants.KEY_MONET_COLOR, UIConstants.DEFAULT_MONET_COLOR)
                }
            }
        }

        DisposableEffect(prefs) {
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }

        val themeController = remember(themeMode, monetColorIndex) { themeController(themeMode, monetColorIndex) }

        val view = LocalView.current
        val isSystemDark = isSystemInDarkTheme()
        
        val isDark = when (themeMode) {
            1, 4 -> false
            2, 5 -> true
            else -> isSystemDark
        }

        LaunchedEffect(isDark, view) {
            if (!view.isInEditMode) {
                var currentContext = context
                while (currentContext is android.content.ContextWrapper) {
                    if (currentContext is Activity) break
                    currentContext = currentContext.baseContext
                }
                
                val window = (currentContext as? Activity)?.window
                if (window != null) {
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !isDark
                    insetsController.isAppearanceLightNavigationBars = !isDark
                }
            }
        }

        CompositionLocalProvider(LocalAppThemeController provides themeController) {
            AppMiuixTheme(controller = themeController, monetColorIndex = monetColorIndex, content = content)
        }
    }
}
