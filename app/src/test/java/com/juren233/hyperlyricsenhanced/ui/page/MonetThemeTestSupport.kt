package com.juren233.hyperlyricsenhanced.ui.page

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.ui.utils.ThemeUtils
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 测试用：在真实 Activity 里组合，读出各种主题下的配色。 */
internal object MonetThemeTestSupport {

    /** Colors 的全部颜色 token（名字 → 颜色），用来逐项比较两套配色。 */
    fun tokens(colors: Colors): Map<String, Color> =
        Colors::class.java.methods
            .filter { it.name.startsWith("get") && it.name.endsWith("-0d7_KjU") && it.parameterCount == 0 }
            .associate { m ->
                m.name.removePrefix("get").removeSuffix("-0d7_KjU") to Color((m.invoke(colors) as Long).toULong())
            }

    fun <T> compose(read: @Composable () -> T): T {
        var result: Any? = NONE
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { result = read() }
        shadowOf(Looper.getMainLooper()).idle()
        check(result !== NONE) { "没有组合到读取内容" }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** 按用户存储的偏好走 App 真正的入口 MiuixThemeWrapper，读出界面实际拿到的配色。 */
    fun appThemeTokens(themeMode: Int, monetColorIndex: Int): Map<String, Color> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putInt(UIConstants.KEY_THEME_MODE, themeMode)
            putInt(UIConstants.KEY_MONET_COLOR, monetColorIndex)
        }
        var result: Map<String, Color>? = null
        activity.setContent {
            ThemeUtils.MiuixThemeWrapper { result = tokens(MiuixTheme.colorScheme) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        return checkNotNull(result)
    }

    /** miuix 按控制器原样生成、未经修正的配色。 */
    fun miuixTokens(themeMode: Int, monetColorIndex: Int): Map<String, Color> = compose {
        var t: Map<String, Color>? = null
        MiuixTheme(controller = ThemeUtils.themeController(themeMode, monetColorIndex)) { t = tokens(MiuixTheme.colorScheme) }
        checkNotNull(t)
    }

    private val NONE = Any()
}
