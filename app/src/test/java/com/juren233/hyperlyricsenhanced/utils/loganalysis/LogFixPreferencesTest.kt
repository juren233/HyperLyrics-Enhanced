package com.juren233.hyperlyricsenhanced.utils.loganalysis

import android.content.Context
import com.juren233.hyperlyricsenhanced.common.IslandRealRhythmConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 一键处理改设置：写进 App 的设置，并逐项同步给系统界面，与设置页的写法相同。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LogFixPreferencesTest {
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)

    private fun applyRecording(action: LogFixAction): List<PrefWrite> {
        val synced = mutableListOf<PrefWrite>()
        LogFixPreferences.apply(prefs, LogFixPreferences.writesFor(action)) { synced += it }
        return synced
    }

    @Test
    fun clearCustomFont_emptiesPathAndSyncs() {
        prefs.edit().putString(RootConstants.KEY_HOOK_CUSTOM_FONT_PATH, "/sdcard/Fonts/x.ttf").commit()
        val synced = applyRecording(LogFixAction.ClearCustomFont)
        assertEquals("", prefs.getString(RootConstants.KEY_HOOK_CUSTOM_FONT_PATH, null))
        assertEquals(listOf<PrefWrite>(PrefWrite.StringValue(RootConstants.KEY_HOOK_CUSTOM_FONT_PATH, "")), synced)
    }

    @Test
    fun switchToLyricon_writesSourcePref() {
        prefs.edit().putString(RootConstants.KEY_HOOK_LYRIC_SOURCE, "superlyric").commit()
        val synced = applyRecording(LogFixAction.SwitchLyricSource(LogAnalyzer.LYRICON_SOURCE))
        assertEquals("lyricon", prefs.getString(RootConstants.KEY_HOOK_LYRIC_SOURCE, null))
        assertEquals(1, synced.size)
    }

    @Test
    fun turnOffEachFeature_resetsItsSwitches() {
        prefs.edit()
            .putInt(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE, 4)
            .putInt(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR_MODE, 1)
            .putBoolean(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR, true)
            .putBoolean(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT, true)
            .putInt(RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_COVER_STYLE, 2)
            .putBoolean(RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE, true)
            .putBoolean(RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH, true)
            .putBoolean(RootConstants.KEY_HOOK_NARROW_LATIN_FONT, true)
            .commit()
        SystemFeature.entries.forEach { applyRecording(LogFixAction.TurnOffFeature(it)) }
        assertEquals(RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT, prefs.getInt(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE, -1))
        assertEquals(RootConstants.ISLAND_MUSIC_WAVE_COLOR_MODE_DISABLED, prefs.getInt(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR_MODE, -1))
        assertEquals(false, prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR, true))
        assertEquals(false, prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT, true))
        assertEquals(RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT, prefs.getInt(RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_COVER_STYLE, -1))
        assertEquals(false, prefs.getBoolean(RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE, true))
        assertEquals(false, prefs.getBoolean(RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH, true))
        assertEquals(false, prefs.getBoolean(RootConstants.KEY_HOOK_NARROW_LATIN_FONT, true))
    }

    /** 关掉之后，分析用的开关判断要认为它已关闭，否则一键处理完还会再报。 */
    @Test
    fun turnedOffFeatures_readAsOffByAnalysis() {
        SystemFeature.entries.forEach { applyRecording(LogFixAction.TurnOffFeature(it)) }
        val switches = LogAnalysisRunner.featureSwitches(prefs)
        // Hook 失效类（11-26 起只给反馈按钮）不写设置；能一键关闭的只有这四项。
        assertEquals(
            listOf(false, false, false, false),
            listOf(switches.islandCoverStyle, switches.musicWaveColor, switches.notificationMediaCover, switches.narrowLatinFont),
        )
    }

    @Test
    fun hookFeatures_turnOffWritesNothing() {
        SystemFeature.entries.filter { it.hookHost != null && it !in OLD_TURN_OFF }.forEach {
            assertTrue(it.name, LogFixPreferences.writesFor(LogFixAction.TurnOffFeature(it)).isEmpty())
        }
    }

    /** 「跟随音乐真实律动」拿不到频谱：一键设为「不开启」，并同步给系统界面。 */
    @Test
    fun turnOffRealRhythm_writesModeOffAndSyncs() {
        prefs.edit().putBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, true)
            .putInt(IslandRealRhythmConfig.KEY_MODE, IslandRealRhythmConfig.MODE_ON).commit()
        assertTrue(LogAnalysisRunner.featureSwitches(prefs).realRhythm)
        val synced = applyRecording(LogFixAction.TurnOffRealRhythm)
        assertEquals(IslandRealRhythmConfig.MODE_OFF, prefs.getInt(IslandRealRhythmConfig.KEY_MODE, -1))
        assertEquals(listOf<PrefWrite>(PrefWrite.IntValue(IslandRealRhythmConfig.KEY_MODE, IslandRealRhythmConfig.MODE_OFF)), synced)
        assertFalse(LogAnalysisRunner.featureSwitches(prefs).realRhythm)
    }

    /** 媒体卡片、光效、真实律动都受超级岛总开关控制：总开关关了就不算开着。 */
    @Test
    fun hyperIslandOff_gatesMediaCardFeatures() {
        prefs.edit()
            .putBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, false)
            .putInt(IslandRealRhythmConfig.KEY_MODE, IslandRealRhythmConfig.MODE_ON)
            .putInt(RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_STYLE, RootConstants.NOTIFICATION_MEDIA_BACKGROUND_STYLE_DEFAULT + 1)
            .commit()
        val switches = LogAnalysisRunner.featureSwitches(prefs)
        assertFalse(switches.realRhythm)
        assertFalse(switches.notificationMediaCard)
        assertFalse(switches.notificationMediaBackground)
        assertFalse(switches.islandExpandedFlow)
        assertFalse(switches.islandGlow)
        prefs.edit().putBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, true).commit()
        val on = LogAnalysisRunner.featureSwitches(prefs)
        assertTrue(on.realRhythm)
        assertTrue(on.notificationMediaCard)
        assertTrue(on.notificationMediaBackground)
    }

    private companion object {
        val OLD_TURN_OFF = setOf(SystemFeature.ISLAND_COVER_STYLE, SystemFeature.MUSIC_WAVE_COLOR, SystemFeature.NOTIFICATION_MEDIA_COVER)
    }

    @Test
    fun navigationActions_writeNothing() {
        listOf(LogFixAction.OpenFontSettings, LogFixAction.OpenAiTranslationSettings, LogFixAction.RepairPlugins, LogFixAction.OpenIssuePage, LogFixAction.OpenReleasePage).forEach {
            assertTrue(LogFixPreferences.writesFor(it).isEmpty())
        }
    }
}
