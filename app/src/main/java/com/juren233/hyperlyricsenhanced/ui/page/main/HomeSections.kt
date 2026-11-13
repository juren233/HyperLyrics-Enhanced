package com.juren233.hyperlyricsenhanced.ui.page.main

import com.juren233.hyperlyricsenhanced.ui.component.SectionEnter
import com.juren233.hyperlyricsenhanced.ui.component.SectionExit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.HeroCard
import com.juren233.hyperlyricsenhanced.ui.component.IslandGlyph
import com.juren233.hyperlyricsenhanced.ui.component.LyricHookSwitchController
import com.juren233.hyperlyricsenhanced.ui.component.SettingsTileGrid
import com.juren233.hyperlyricsenhanced.ui.component.SettingsTileSpec
import com.juren233.hyperlyricsenhanced.ui.component.SpecialFeaturesContent
import com.juren233.hyperlyricsenhanced.ui.component.settingsCard
import com.juren233.hyperlyricsenhanced.ui.component.settingsHeroCard
import com.juren233.hyperlyricsenhanced.ui.page.setup.setupOutroItemReveal
import com.juren233.hyperlyricsenhanced.utils.formatAppVersion
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** 首页最多的列表项数，分帧组合时按此分阶段。 */
const val HOME_SECTION_MAX = 7

/**
 * 首页：顶部头卡显示当前歌词来源（点击进入歌词设置）、版本、更新与 Debug 包提示；下面的磁贴是三种显示位置的开关，
 * 已开启的显示位置在磁贴下方列出各自的配置入口。
 *
 * @param sectionLimit 只组合前几项（基础设置完成转场中逐帧放开，每项组合出来时逐项浮现），默认全部。
 */
fun LazyListScope.homePageSections(
    availableUpdateVersion: String?,
    lyricSourceLabel: String,
    showHyperIslandEntry: Boolean,
    showAodLyricsEntry: Boolean,
    showDynamicIslandEntry: Boolean,
    lyricHookSwitches: LyricHookSwitchController,
    enableHyperIsland: Boolean,
    onHyperIslandToggle: (Boolean) -> Unit,
    enableAodLyrics: Boolean,
    onAodLyricsToggle: (Boolean) -> Unit,
    onHyperIslandConfigClick: () -> Unit,
    onHyperIslandTouchConfigClick: () -> Unit,
    onMediaCardConfigClick: () -> Unit,
    onLyricSettingsClick: () -> Unit,
    onDynamicIslandConfigClick: () -> Unit,
    onLockScreenAodConfigClick: () -> Unit,
    onClassicAodConfigClick: () -> Unit,
    sectionLimit: Int = Int.MAX_VALUE,
) {
    var sections = 0
    fun allow(): Boolean = sections++ < sectionLimit

    if (allow()) item(key = "home_hero") {
        Box(Modifier.setupOutroItemReveal()) {
            HeroCard(
                overline = stringResource(R.string.title_lyric_source),
                title = lyricSourceLabel,
                subtitle = stringResource(
                    R.string.home_hero_version,
                    formatAppVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
                ),
                onClick = onLyricSettingsClick,
                notes = listOfNotNull(
                    availableUpdateVersion?.let { stringResource(R.string.home_hero_update_available, it) },
                    if (BuildConfig.DEBUG) stringResource(R.string.debug_build_notice) else null,
                ),
                decorationIcon = MiuixIcons.Notes,
                modifier = Modifier.settingsHeroCard(),
            )
        }
    }

    if (allow()) item(key = "home_tiles") {
        val on = stringResource(R.string.tile_state_on)
        val off = stringResource(R.string.tile_state_off)
        val tiles = buildList {
            if (showHyperIslandEntry) add(
                SettingsTileSpec(
                    key = "hyper_island",
                    title = stringResource(R.string.title_miui_systemui_enhancement),
                    status = if (enableHyperIsland) on else off,
                    active = enableHyperIsland,
                    onClick = { onHyperIslandToggle(!enableHyperIsland) },
                ) { tint, modifier -> IslandGlyph(color = tint, modifier = modifier) }
            )
            if (showAodLyricsEntry) add(
                SettingsTileSpec(
                    key = "aod_lyrics",
                    title = stringResource(R.string.title_aod_lyrics),
                    status = if (enableAodLyrics) on else off,
                    active = enableAodLyrics,
                    onClick = { onAodLyricsToggle(!enableAodLyrics) },
                ) { tint, modifier -> Icon(MiuixIcons.Lock, null, modifier, tint) }
            )
            if (showDynamicIslandEntry) add(
                SettingsTileSpec(
                    key = "dynamic_island",
                    title = stringResource(R.string.title_dynamic_island_lyrics),
                    status = if (lyricHookSwitches.enableDynamicIsland) on else off,
                    active = lyricHookSwitches.enableDynamicIsland,
                    onClick = { lyricHookSwitches.onDynamicIslandToggle(!lyricHookSwitches.enableDynamicIsland) },
                ) { tint, modifier -> Icon(MiuixIcons.Community, null, modifier, tint) }
            )
        }
        Box(Modifier.setupOutroItemReveal()) { SettingsTileGrid(tiles) }
    }

    if (showHyperIslandEntry && allow()) item(key = "home_hyper_island_config") {
        Box(Modifier.setupOutroItemReveal()) {
            FeatureConfigGroup(visible = enableHyperIsland, title = stringResource(R.string.title_miui_systemui_enhancement)) {
                ArrowPreference(title = stringResource(R.string.title_hyper_island_lyrics_config), onClick = onHyperIslandConfigClick)
                ArrowPreference(title = stringResource(R.string.title_island_touch_config), onClick = onHyperIslandTouchConfigClick)
                ArrowPreference(title = stringResource(R.string.title_media_cards), onClick = onMediaCardConfigClick)
            }
        }
    }

    if (showAodLyricsEntry && allow()) item(key = "home_aod_lyrics_config") {
        Box(Modifier.setupOutroItemReveal()) {
            FeatureConfigGroup(visible = enableAodLyrics, title = stringResource(R.string.title_aod_lyrics)) {
                ArrowPreference(title = stringResource(R.string.title_lock_screen_aod), onClick = onLockScreenAodConfigClick)
                ArrowPreference(title = stringResource(R.string.title_classic_aod), onClick = onClassicAodConfigClick)
            }
        }
    }

    if (showDynamicIslandEntry && allow()) item(key = "home_dynamic_island_config") {
        Box(Modifier.setupOutroItemReveal()) {
            FeatureConfigGroup(
                visible = lyricHookSwitches.enableDynamicIsland,
                title = stringResource(R.string.title_dynamic_island_lyrics),
            ) {
                ArrowPreference(title = stringResource(R.string.title_dynamic_island_config), onClick = onDynamicIslandConfigClick)
            }
        }
    }

    if (allow()) item(key = "special_features_title") {
        Box(Modifier.setupOutroItemReveal()) {
            SmallTitle(text = stringResource(R.string.title_special_features))
        }
    }

    if (allow()) item(key = "special_features_content") {
        Box(Modifier.setupOutroItemReveal()) {
            SpecialFeaturesContent(lyricHookSwitches)
        }
    }
}

/** 已开启的显示位置的配置入口：分组标题 + 卡片，关闭时整组收起。 */
@Composable
private fun FeatureConfigGroup(
    visible: Boolean,
    title: String,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = SectionEnter,
        exit = SectionExit,
    ) {
        Column {
            SmallTitle(text = title)
            Card(modifier = Modifier.settingsCard()) { content() }
        }
    }
}
