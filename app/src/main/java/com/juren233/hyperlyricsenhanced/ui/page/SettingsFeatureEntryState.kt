/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.ui.page.main.MainTabPolicy

/**
 * 设置页四个功能入口的状态，以及由它们决定的主页是否保留。
 *
 * 入口不只在设置页里改：从设置页打开「回顾基础设置流程页」选择工作模式也会改写入口，
 * 而回顾页盖在设置页上时设置页仍在组合中（导航保留栈顶下面一层，平行窗口保留三层），
 * 返回时不会重新读取偏好。因此 [startObserving] 之后跟随偏好变化刷新，与主页面同一口径。
 */
internal class SettingsFeatureEntryState(
    private val prefs: SharedPreferences,
    private val xiaomiDevice: Boolean,
    private val appleMusicInstalled: Boolean,
) {
    var hyperIsland by mutableStateOf(readHyperIsland())
    var aodLyrics by mutableStateOf(readAodLyrics())
    var dynamicIsland by mutableStateOf(readDynamicIsland())
    var appleMusic by mutableStateOf(readAppleMusic())

    /** 主页是否保留，规则与主页面一致，见 [MainTabPolicy.isHomePageVisible]。 */
    val homePageVisible: Boolean
        get() = MainTabPolicy.isHomePageVisible(
            hyperIslandEntryEnabled = hyperIsland,
            aodLyricsEntryEnabled = aodLyrics,
            dynamicIslandEntryEnabled = dynamicIsland,
        )

    // SharedPreferences 只弱引用监听器，必须由本对象强持有，否则会被回收而收不到变化。
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND -> hyperIsland = readHyperIsland()
            UIConstants.KEY_FEATURE_ENTRY_AOD_LYRICS -> aodLyrics = readAodLyrics()
            UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND -> dynamicIsland = readDynamicIsland()
            UIConstants.KEY_FEATURE_ENTRY_APPLE_MUSIC -> appleMusic = readAppleMusic()
            // key 为 null 表示偏好被整体清空（如恢复配置），全部重读。
            null -> refresh()
        }
    }

    /** 开始跟随偏好：先补读一次未监听期间的变化，再注册监听。 */
    fun startObserving() {
        refresh()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun stopObserving() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun refresh() {
        hyperIsland = readHyperIsland()
        aodLyrics = readAodLyrics()
        dynamicIsland = readDynamicIsland()
        appleMusic = readAppleMusic()
    }

    private fun readHyperIsland() = prefs.getBoolean(UIConstants.KEY_FEATURE_ENTRY_HYPER_ISLAND, xiaomiDevice)
    private fun readAodLyrics() = prefs.getBoolean(UIConstants.KEY_FEATURE_ENTRY_AOD_LYRICS, xiaomiDevice)
    private fun readDynamicIsland() = prefs.getBoolean(UIConstants.KEY_FEATURE_ENTRY_DYNAMIC_ISLAND, true)
    private fun readAppleMusic() = prefs.getBoolean(UIConstants.KEY_FEATURE_ENTRY_APPLE_MUSIC, appleMusicInstalled)
}

@Composable
internal fun rememberSettingsFeatureEntryState(
    prefs: SharedPreferences,
    xiaomiDevice: Boolean,
    appleMusicInstalled: Boolean,
): SettingsFeatureEntryState {
    val state = remember(prefs) { SettingsFeatureEntryState(prefs, xiaomiDevice, appleMusicInstalled) }
    DisposableEffect(state) {
        state.startObserving()
        onDispose { state.stopObserving() }
    }
    return state
}
