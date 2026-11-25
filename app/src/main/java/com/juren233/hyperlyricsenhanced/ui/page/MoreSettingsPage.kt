package com.juren233.hyperlyricsenhanced.ui.page

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.MoreSettingsPreferences
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.root.settings.SettingsEntryProfile
import com.juren233.hyperlyricsenhanced.ui.component.SectionEnter
import com.juren233.hyperlyricsenhanced.ui.component.SectionExit
import com.juren233.hyperlyricsenhanced.ui.component.settingsCard
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.utils.AppUtils
import com.juren233.hyperlyricsenhanced.ui.utils.rememberIsWideScreen
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

private fun setExcludeFromRecents(context: Context, exclude: Boolean) {
    try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.appTasks?.forEach { it.setExcludeFromRecents(exclude) }
    } catch (_: Exception) { }
}

/** 应用设置 → 外观 → 更多：「手势与界面」「高级」两组，开关行为与原先设置页里的「界面与手势」「隐私」一致。 */
@Composable
fun MoreSettingsPage() {
    XposedLyricSettingPage(title = stringResource(R.string.title_more_settings)) {
        // 手势与界面
        item(key = "gestures_interface_title") {
            SmallTitle(text = stringResource(R.string.title_gestures_interface))
        }
        item(key = "gestures_interface_content") {
            GesturesInterfaceCard()
        }

        // 高级（原「隐私」）
        item(key = "advanced_title") {
            SmallTitle(text = stringResource(R.string.title_more_advanced))
        }
        item(key = "advanced_content") {
            AdvancedCard()
        }
    }
}

@Composable
private fun GesturesInterfaceCard() {
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current
    val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
    Card(modifier = Modifier.settingsCard()) {
        Column {
            // 手势：侧滑返回、预测性返回。
            var swipeBackGestureEnabled by remember { mutableStateOf(MoreSettingsPreferences.swipeBackGesture(prefs)) }
            SwitchPreference(title = stringResource(R.string.title_swipe_back_gesture), checked = swipeBackGestureEnabled, onCheckedChange = { swipeBackGestureEnabled = it; prefs.edit { putBoolean(UIConstants.KEY_SWIPE_BACK_GESTURE, it) } })
            var predictiveBackGestureEnabled by remember { mutableStateOf(MoreSettingsPreferences.predictiveBackGesture(prefs)) }
            SwitchPreference(title = stringResource(R.string.title_predictive_back), checked = predictiveBackGestureEnabled, onCheckedChange = {
                predictiveBackGestureEnabled = it; prefs.edit { putBoolean(UIConstants.KEY_PREDICTIVE_BACK_GESTURE, it) }
                runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"); val m = android.content.pm.ApplicationInfo::class.java.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType); m.isAccessible = true; m.invoke(context.applicationInfo, it) }
                activity?.recreate()
            })
            // 界面：平行窗口、悬浮底栏、顶栏渐进模糊。
            var parallelWindowUiEnabled by remember { mutableStateOf(MoreSettingsPreferences.parallelWindowUi(prefs)) }
            val isWideScreen = rememberIsWideScreen()
            // 平行窗口 UI 仅平板（宽屏）提供；开启期间底部栏导航不可用，隐藏悬浮底栏开关
            if (isWideScreen) {
                SwitchPreference(title = stringResource(R.string.title_parallel_window_ui), checked = parallelWindowUiEnabled, onCheckedChange = { parallelWindowUiEnabled = it; prefs.edit { putBoolean(UIConstants.KEY_PARALLEL_WINDOW_UI, it) } })
            }
            var floatingNavBarEnabled by remember { mutableStateOf(MoreSettingsPreferences.floatingNavBar(prefs)) }
            if (!(isWideScreen && parallelWindowUiEnabled)) {
                SwitchPreference(title = stringResource(R.string.title_floating_nav), checked = floatingNavBarEnabled, onCheckedChange = { floatingNavBarEnabled = it; prefs.edit { putBoolean(UIConstants.KEY_FLOATING_NAV_BAR, it) } })
            }
            var topBarProgressiveBlurEnabled by remember {
                mutableStateOf(MoreSettingsPreferences.topBarProgressiveBlur(prefs))
            }
            SwitchPreference(
                title = stringResource(R.string.title_top_bar_progressive_blur),
                checked = topBarProgressiveBlurEnabled,
                onCheckedChange = {
                    topBarProgressiveBlurEnabled = it
                    prefs.edit {
                        putBoolean(UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR, it)
                        remove(UIConstants.KEY_TOP_BAR_PROGRESSIVE_BLUR_MODE)
                    }
                },
            )
        }
    }
}

@Composable
private fun AdvancedCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
    Card(modifier = Modifier.settingsCard()) {
        Column {
            var excludeFromRecents by remember { mutableStateOf(MoreSettingsPreferences.excludeFromRecents(prefs)) }
            SwitchPreference(title = stringResource(R.string.title_exclude_from_recents), checked = excludeFromRecents, onCheckedChange = { excludeFromRecents = it; prefs.edit { putBoolean(UIConstants.KEY_EXCLUDE_FROM_RECENTS, it) }; setExcludeFromRecents(context, it) })
            var hideAppIcon by remember { mutableStateOf(MoreSettingsPreferences.hideAppIcon(prefs)) }
            SwitchPreference(title = stringResource(R.string.title_hide_app_icon), checked = hideAppIcon, onCheckedChange = { hideAppIcon = it; PrefsBridge.putBoolean(UIConstants.KEY_HIDE_APP_ICON, it); AppUtils.applyLauncherIconVisibility(context, it) })
            AnimatedVisibility(
                visible = hideAppIcon,
                enter = SectionEnter,
                exit = SectionExit,
            ) {
                var settingsEntryPosition by remember {
                    mutableStateOf(
                        SettingsEntryProfile.resolveEntryPosition(
                            positionExists = prefs.contains(UIConstants.KEY_SETTINGS_ENTRY_POSITION),
                            positionValue = prefs.getInt(UIConstants.KEY_SETTINGS_ENTRY_POSITION, 0),
                            legacyShowEntryExists = prefs.contains(UIConstants.KEY_SHOW_SETTINGS_ENTRY),
                            legacyShowEntry = prefs.getBoolean(UIConstants.KEY_SHOW_SETTINGS_ENTRY, UIConstants.DEFAULT_SHOW_SETTINGS_ENTRY),
                        )
                    )
                }
                val settingsEntryPositionOptions = listOf(
                    stringResource(R.string.settings_entry_position_hidden),
                    stringResource(R.string.settings_entry_position_top),
                    stringResource(R.string.settings_entry_position_middle),
                    stringResource(R.string.settings_entry_position_bottom),
                )
                WindowDropdownPreference(
                    title = stringResource(R.string.title_show_settings_entry),
                    summary = stringResource(R.string.summary_show_settings_entry),
                    items = settingsEntryPositionOptions,
                    selectedIndex = settingsEntryPosition,
                    onSelectedIndexChange = {
                        settingsEntryPosition = it
                        PrefsBridge.putInt(UIConstants.KEY_SETTINGS_ENTRY_POSITION, it)
                    },
                )
            }
        }
    }
}
