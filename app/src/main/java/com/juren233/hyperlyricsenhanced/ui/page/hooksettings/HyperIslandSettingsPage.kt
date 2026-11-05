package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import com.juren233.hyperlyricsenhanced.ui.skin.SkinListPage
import com.juren233.hyperlyricsenhanced.ui.skin.ArrowPreference
import com.juren233.hyperlyricsenhanced.ui.skin.BasicComponent
import com.juren233.hyperlyricsenhanced.ui.skin.Card
import com.juren233.hyperlyricsenhanced.ui.skin.Icon
import com.juren233.hyperlyricsenhanced.ui.skin.IconButton
import com.juren233.hyperlyricsenhanced.ui.skin.OverlayDropdownPreference
import com.juren233.hyperlyricsenhanced.ui.skin.SkinIcons
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.SmallTitle
import com.juren233.hyperlyricsenhanced.ui.skin.SwitchPreference
import com.juren233.hyperlyricsenhanced.ui.skin.Text
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.juren233.hyperlyricsenhanced.ui.component.NumberInputDialog
import com.juren233.hyperlyricsenhanced.ui.component.PaddingInputDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.common.IslandMusicWaveColorMode
import com.juren233.hyperlyricsenhanced.common.IslandNextLineMode
import com.juren233.hyperlyricsenhanced.common.IslandProgressColorMode
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.ui.component.CustomFontColorPickerDialog
import com.juren233.hyperlyricsenhanced.ui.component.CustomFontColorPreview
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import androidx.compose.ui.res.stringResource
import com.juren233.hyperlyricsenhanced.R

@Composable
fun HyperIslandSettingsPage() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }

    var islandContentLeft by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT)) }
    var islandContentRight by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_RIGHT)) }
    val lyricMode by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_LYRIC_MODE, RootConstants.DEFAULT_HOOK_LYRIC_MODE)) }
    val lyricSource by remember {
        mutableStateOf(
            prefs.getString(
                RootConstants.KEY_HOOK_LYRIC_SOURCE,
                RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
            ) ?: RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
        )
    }
    var nextLyricLine by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_NEXT_LYRIC_LINE,
                RootConstants.DEFAULT_HOOK_NEXT_LYRIC_LINE
            )
        )
    }
    var fullIslandNextLineMode by remember {
        mutableIntStateOf(IslandNextLineMode.resolve(
            prefs.getInt(RootConstants.KEY_HOOK_ISLAND_NEXT_LINE_MODE, IslandNextLineMode.UNSPECIFIED),
            nextLyricLine,
        ))
    }
    var audioCover by remember { mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_ALBUM)) }
    var audioCoverStyle by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE,
                RootConstants.DEFAULT_HOOK_ISLAND_ALBUM_COVER_STYLE
            ).coerceIn(
                RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT,
                RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT
            )
        )
    }
    var audioRhythm by remember { mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_ICON)) }
    var interludeMinimalDisplay by remember {
        mutableStateOf(prefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_INTERLUDE_MINIMAL_DISPLAY,
            RootConstants.DEFAULT_HOOK_ISLAND_INTERLUDE_MINIMAL_DISPLAY,
        ))
    }
    var shortLyricSongInfo by remember {
        mutableStateOf(prefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_SHORT_LYRIC_SONG_INFO,
            RootConstants.DEFAULT_HOOK_ISLAND_SHORT_LYRIC_SONG_INFO,
        ))
    }
    var musicWaveColorMode by remember {
        mutableIntStateOf(
            IslandMusicWaveColorMode.resolve(
                storedMode = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR_MODE,
                    IslandMusicWaveColorMode.UNSPECIFIED,
                ),
                hasLegacyCoverPreference = prefs.contains(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR),
                legacyCoverEnabled = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR,
                    RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_COLOR,
                ),
                legacyCoverGradient = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
                    RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
                ),
            )
        )
    }
    var leftPaddingLeft by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_PADDING_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_PADDING_LEFT)) }
    var leftPaddingRight by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_PADDING_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_PADDING_RIGHT)) }
    var rightPaddingLeft by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_PADDING_LEFT)) }
    var rightPaddingRight by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_PADDING_RIGHT)) }
    var leftContentWidth by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH).coerceIn(0, 500)) }
    var rightContentWidth by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH).coerceIn(0, 500)) }
    var dynamicLimit by remember { mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT, RootConstants.DEFAULT_HOOK_ISLAND_DYNAMIC_LIMIT)) }
    var dynamicWidth by remember { mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH, RootConstants.DEFAULT_HOOK_ISLAND_DYNAMIC_WIDTH)) }
    var duetFixedLength by remember { mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_DUET_FIXED_LENGTH, RootConstants.DEFAULT_HOOK_ISLAND_DUET_FIXED_LENGTH)) }
    var afterPauseBehavior by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE, RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE)) }
    var forceNextSongAtEnd by remember { mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_FORCE_NEXT_SONG_AT_END, RootConstants.DEFAULT_HOOK_ISLAND_FORCE_NEXT_SONG_AT_END)) }
    val storedNextSongDuration = prefs.getInt(
        RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_DURATION,
        RootConstants.DEFAULT_HOOK_ISLAND_NEXT_SONG_DURATION
    ).coerceIn(0, 5)
    val storedNextSongPreviewStyle = prefs.getInt(
        RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_STYLE,
        Int.MIN_VALUE
    )
    var nextSongPreviewStyle by remember {
        mutableIntStateOf(
            if (storedNextSongPreviewStyle != Int.MIN_VALUE) {
                storedNextSongPreviewStyle.coerceIn(
                    RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_NONE,
                    RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF
                )
            } else if (storedNextSongDuration > 0) {
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL
            } else {
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_NONE
            }
        )
    }
    var nextSongDurationSeconds by remember {
        mutableIntStateOf(
            storedNextSongDuration.takeIf { it > 0 }
                ?: RootConstants.DEFAULT_HOOK_ISLAND_NEXT_SONG_DURATION
        )
    }
    var nextSongPreviewPosition by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_POSITION,
                RootConstants.DEFAULT_HOOK_ISLAND_NEXT_SONG_PREVIEW_POSITION
            ).coerceIn(
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_OTHER_SIDE,
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_RIGHT
            )
        )
    }
    var nextSongPreviewWeight by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_WEIGHT,
                RootConstants.DEFAULT_HOOK_ISLAND_NEXT_SONG_PREVIEW_WEIGHT
            ).coerceIn(
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_WEIGHT_TOP,
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_WEIGHT_BOTTOM
            )
        )
    }
    var progressColorMode by remember {
        mutableIntStateOf(
            IslandProgressColorMode.resolve(
                storedMode = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_PROGRESS_COLOR_MODE,
                    IslandProgressColorMode.UNSPECIFIED,
                ),
                legacyProgressEnabled = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_PROGRESS_GLOW,
                    RootConstants.DEFAULT_HOOK_ISLAND_PROGRESS_GLOW,
                ),
                legacyCoverEnabled = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
                    RootConstants.DEFAULT_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
                ),
                legacyCoverGradient = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_PROGRESS_GRADIENT,
                    RootConstants.DEFAULT_HOOK_ISLAND_PROGRESS_GRADIENT,
                ),
            )
        )
    }
    var progressCustomColor by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_CUSTOM_COLOR,
                RootConstants.DEFAULT_HOOK_ISLAND_PROGRESS_CUSTOM_COLOR,
            )
        )
    }
    var progressStyle by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_ISLAND_PROGRESS_STYLE,
                RootConstants.DEFAULT_HOOK_ISLAND_PROGRESS_STYLE
            ).coerceIn(
                RootConstants.ISLAND_PROGRESS_STYLE_TOP_CLOCKWISE,
                RootConstants.ISLAND_PROGRESS_STYLE_BOTTOM_BIDIRECTIONAL
            )
        )
    }

    var showLeftPaddingDialog by remember { mutableStateOf(false) }
    var showRightPaddingDialog by remember { mutableStateOf(false) }
    var showLeftContentWidthDialog by remember { mutableStateOf(false) }
    var showRightContentWidthDialog by remember { mutableStateOf(false) }
    var showProgressCustomColorDialog by remember { mutableStateOf(false) }

    fun saveConfig(key: String, value: Any) {
        prefs.edit {
            when (value) {
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
            }
        }
        when (value) {
            is Int -> PrefsBridge.putInt(key, value)
            is Boolean -> PrefsBridge.putBoolean(key, value)
        }
    }

    val contentOptions = remember {
        listOf(R.string.option_content_none, R.string.option_content_title, R.string.option_content_artist, R.string.option_content_album, R.string.option_content_title_artist, R.string.option_content_title_plus_artist, R.string.option_content_title_plus_artist_album, R.string.option_content_lyric)
    }.map { stringResource(id = it) }
    val afterPauseOptions = remember {
        listOf(R.string.option_after_pause_default, R.string.option_after_pause_keep)
    }.map { stringResource(id = it) }
    val nextSongDurationOptions = remember {
        listOf(
            R.string.option_next_song_duration_1,
            R.string.option_next_song_duration_2,
            R.string.option_next_song_duration_3,
            R.string.option_next_song_duration_4,
            R.string.option_next_song_duration_5
        )
    }.map { stringResource(id = it) }
    val nextSongPreviewStyleOptions = remember {
        listOf(
            R.string.option_island_next_song_preview_none,
            R.string.option_island_next_song_preview_full,
            R.string.option_island_next_song_preview_half
        )
    }.map { stringResource(id = it) }
    val nextSongPreviewPositionOptionValues = remember(lyricMode) {
        if (lyricMode == RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE) {
            listOf(
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_OTHER_SIDE,
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_LEFT,
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_RIGHT,
            )
        } else {
            listOf(
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_LEFT,
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_RIGHT,
            )
        }
    }
    val nextSongPreviewPositionOptions = nextSongPreviewPositionOptionValues.map { value ->
        stringResource(
            id = when (value) {
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_OTHER_SIDE ->
                    R.string.option_island_next_song_preview_other_side
                RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_LEFT ->
                    R.string.option_island_next_song_preview_left
                else -> R.string.option_island_next_song_preview_right
            }
        )
    }
    val nextSongPreviewWeightOptions = remember {
        listOf(
            R.string.option_island_next_song_preview_weight_top,
            R.string.option_island_next_song_preview_weight_bottom
        )
    }.map { stringResource(id = it) }
    val audioCoverStyleValues = remember {
        listOf(
            RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT,
            RootConstants.ISLAND_ALBUM_COVER_STYLE_CIRCLE,
            RootConstants.ISLAND_ALBUM_COVER_STYLE_ROTATING_CIRCLE,
            RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT,
            RootConstants.ISLAND_ALBUM_COVER_STYLE_APP_ICON
        )
    }
    val audioCoverStyleOptions = remember {
        listOf(
            R.string.option_audio_cover_style_default,
            R.string.option_audio_cover_style_circle,
            R.string.option_audio_cover_style_rotating_circle,
            R.string.option_audio_cover_style_gradient,
            R.string.option_audio_cover_style_app_icon
        )
    }.map { stringResource(id = it) }
    val musicWaveColorModeValues = remember {
        listOf(
            RootConstants.ISLAND_MUSIC_WAVE_COLOR_MODE_DISABLED,
            RootConstants.ISLAND_MUSIC_WAVE_COLOR_MODE_COVER,
            RootConstants.ISLAND_MUSIC_WAVE_COLOR_MODE_COVER_GRADIENT
        )
    }
    val musicWaveColorModeOptions = remember {
        listOf(
            R.string.option_music_wave_color_mode_off,
            R.string.option_music_wave_color_mode_cover,
            R.string.option_music_wave_color_mode_cover_gradient
        )
    }.map { stringResource(id = it) }
    val progressStyleOptions = remember {
        listOf(
            R.string.option_island_progress_top_clockwise,
            R.string.option_island_progress_right_clockwise,
            R.string.option_island_progress_bottom_clockwise,
            R.string.option_island_progress_left_clockwise,
            R.string.option_island_progress_left_bidirectional,
            R.string.option_island_progress_top_bidirectional,
            R.string.option_island_progress_bottom_bidirectional
        )
    }.map { stringResource(id = it) }
    val progressColorModeOptions = remember {
        listOf(
            R.string.option_island_progress_disabled,
            R.string.option_island_progress_system_blue,
            R.string.option_island_progress_monet,
            R.string.option_island_progress_cover,
            R.string.option_island_progress_cover_gradient,
            R.string.option_island_progress_custom,
        )
    }.map { stringResource(id = it) }

        val lazyListState = rememberLazyListState()
    SkinListPage(
        title = stringResource(id = R.string.title_hyper_island),
        subtitle = stringResource(id = R.string.subtitle_hyper_island),
        onBack = { navigator.pop() },
        listState = lazyListState,
    ) {
                item(key = "layout_title") { SmallTitle(text = stringResource(id = R.string.title_layout)) }
                item(key = "layout_content") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                        Column {
                            SwitchPreference(
                                title = stringResource(id = R.string.title_island_dynamic_width),
                                summary = stringResource(id = R.string.summary_island_dynamic_width),
                                checked = dynamicWidth,
                                onCheckedChange = {
                                    dynamicWidth = it
                                    saveConfig(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_WIDTH, it)
                                }
                            )
                            AnimatedVisibility(
                                visible = dynamicWidth,
                                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                            ) {
                                SwitchPreference(
                                    title = stringResource(id = R.string.title_island_duet_fixed_length),
                                    summary = stringResource(id = R.string.summary_island_duet_fixed_length),
                                    checked = duetFixedLength,
                                    onCheckedChange = {
                                        duetFixedLength = it
                                        saveConfig(RootConstants.KEY_HOOK_ISLAND_DUET_FIXED_LENGTH, it)
                                    }
                                )
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.title_island_dynamic_limit),
                                summary = stringResource(id = R.string.summary_island_dynamic_limit),
                                checked = dynamicLimit,
                                onCheckedChange = {
                                    dynamicLimit = it
                                    if (it) {
                                        showLeftContentWidthDialog = false
                                        showRightContentWidthDialog = false
                                    }
                                    saveConfig(RootConstants.KEY_HOOK_ISLAND_DYNAMIC_LIMIT, it)
                                }
                            )
                            AnimatedVisibility(
                                visible = !dynamicLimit,
                                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                            ) {
                                Column {
                                    ArrowPreference(
                                        title = stringResource(id = R.string.title_left_content_width),
                                        endActions = { Text("$leftContentWidth", fontSize = SkinTheme.textStyles.body2.fontSize, color = SkinTheme.colors.onSurfaceVariantActions) },
                                        enabled = !dynamicLimit,
                                        onClick = { if (!dynamicLimit) showLeftContentWidthDialog = true }
                                    )
                                    ArrowPreference(
                                        title = stringResource(id = R.string.title_right_content_width),
                                        endActions = { Text("$rightContentWidth", fontSize = SkinTheme.textStyles.body2.fontSize, color = SkinTheme.colors.onSurfaceVariantActions) },
                                        enabled = !dynamicLimit,
                                        onClick = { if (!dynamicLimit) showRightContentWidthDialog = true }
                                    )
                                }
                            }
                            ArrowPreference(
                                title = stringResource(id = R.string.title_left_padding), 
                                endActions = { Text(stringResource(id = R.string.format_padding_pair, leftPaddingLeft, leftPaddingRight), fontSize = SkinTheme.textStyles.body2.fontSize, color = SkinTheme.colors.onSurfaceVariantActions) }, 
                                onClick = { showLeftPaddingDialog = true }
                            )
                            ArrowPreference(
                                title = stringResource(id = R.string.title_right_padding), 
                                endActions = { Text(stringResource(id = R.string.format_padding_pair, rightPaddingLeft, rightPaddingRight), fontSize = SkinTheme.textStyles.body2.fontSize, color = SkinTheme.colors.onSurfaceVariantActions) }, 
                                onClick = { showRightPaddingDialog = true }
                            )
                        }
                    }
                }
                item(key = "content_title") { SmallTitle(text = stringResource(id = R.string.title_content)) }
                item(key = "media_components") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                        Column {
                            SwitchPreference(title = stringResource(id = R.string.title_audio_cover), checked = audioCover, onCheckedChange = { audioCover = it; saveConfig(RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM, it) })
                            AnimatedVisibility(visible = audioCover) {
                                Column {
                                    OverlayDropdownPreference(
                                        title = stringResource(id = R.string.title_audio_cover_style),
                                        items = audioCoverStyleOptions,
                                        selectedIndex = audioCoverStyleValues.indexOf(audioCoverStyle).coerceAtLeast(0),
                                        onSelectedIndexChange = { index ->
                                            val style = audioCoverStyleValues[index]
                                            audioCoverStyle = style
                                            saveConfig(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE, style)
                                        }
                                    )
                                    AnimatedVisibility(
                                        visible = audioCoverStyle != RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT,
                                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                                    ) {
                                        ArrowPreference(
                                            title = stringResource(R.string.title_island_album_cover_app_whitelist),
                                            onClick = { navigator.navigate(Route.HyperIslandAlbumCoverWhitelist) },
                                        )
                                    }
                                }
                            }
                            SwitchPreference(
                                title = stringResource(R.string.title_island_interlude_minimal_display),
                                summary = stringResource(R.string.summary_island_interlude_minimal_display),
                                checked = interludeMinimalDisplay,
                                enabled = audioCover || audioRhythm,
                                onCheckedChange = {
                                    interludeMinimalDisplay = it
                                    saveConfig(RootConstants.KEY_HOOK_ISLAND_INTERLUDE_MINIMAL_DISPLAY, it)
                                },
                            )
                            if (lyricMode == RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND) {
                                SwitchPreference(
                                    title = stringResource(R.string.title_island_short_lyric_song_info),
                                    summary = stringResource(R.string.summary_island_short_lyric_song_info),
                                    checked = shortLyricSongInfo,
                                    onCheckedChange = {
                                        shortLyricSongInfo = it
                                        saveConfig(RootConstants.KEY_HOOK_ISLAND_SHORT_LYRIC_SONG_INFO, it)
                                    },
                                )
                            }
                            SwitchPreference(title = stringResource(id = R.string.title_audio_rhythm), checked = audioRhythm, onCheckedChange = { audioRhythm = it; saveConfig(RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON, it) })
                            AnimatedVisibility(visible = audioRhythm) {
                                Column {
                                    OverlayDropdownPreference(
                                        title = stringResource(id = R.string.title_audio_rhythm_cover_color),
                                        items = musicWaveColorModeOptions,
                                        selectedIndex = musicWaveColorModeValues.indexOf(musicWaveColorMode).coerceAtLeast(0),
                                        onSelectedIndexChange = { index ->
                                            val mode = musicWaveColorModeValues[index]
                                            musicWaveColorMode = mode
                                            saveConfig(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR_MODE, mode)
                                            saveConfig(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR, IslandMusicWaveColorMode.isEnabled(mode))
                                            saveConfig(RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT, IslandMusicWaveColorMode.usesCoverGradient(mode))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                if ((lyricMode == RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND ||
                        lyricMode == RootConstants.HOOK_LYRIC_MODE_SEPARATED) &&
                    (lyricSource == "lyricon" || lyricSource == "lyricinfo")
                ) {
                    item(key = "island_next_lyric") {
                        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                            if (lyricMode == RootConstants.HOOK_LYRIC_MODE_FULL_ISLAND) {
                                OverlayDropdownPreference(
                                    title = stringResource(id = R.string.title_next_lyric_line),
                                    summary = stringResource(id = R.string.summary_next_lyric_line),
                                    items = listOf(
                                        stringResource(R.string.island_next_line_off),
                                        stringResource(R.string.island_next_line_second_line),
                                        stringResource(R.string.island_next_line_right),
                                    ),
                                    selectedIndex = fullIslandNextLineMode,
                                    onSelectedIndexChange = {
                                        fullIslandNextLineMode = it
                                        saveConfig(RootConstants.KEY_HOOK_ISLAND_NEXT_LINE_MODE, it)
                                    }
                                )
                            } else {
                                SwitchPreference(
                                    title = stringResource(id = R.string.title_next_lyric_line),
                                    summary = stringResource(id = R.string.summary_next_lyric_line),
                                    checked = nextLyricLine,
                                    onCheckedChange = {
                                        nextLyricLine = it
                                        saveConfig(RootConstants.KEY_HOOK_NEXT_LYRIC_LINE, it)
                                    }
                                )
                            }
                        }
                    }
                }
                item(key = "edge_glow") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                        Column {
                            OverlayDropdownPreference(
                                title = stringResource(id = R.string.title_island_progress_glow),
                                items = progressColorModeOptions,
                                selectedIndex = progressColorMode.coerceIn(
                                    RootConstants.ISLAND_PROGRESS_COLOR_MODE_DISABLED,
                                    RootConstants.ISLAND_PROGRESS_COLOR_MODE_CUSTOM,
                                ),
                                onSelectedIndexChange = { mode ->
                                    progressColorMode = mode
                                    saveConfig(RootConstants.KEY_HOOK_ISLAND_PROGRESS_COLOR_MODE, mode)
                                    saveConfig(
                                        RootConstants.KEY_HOOK_ISLAND_PROGRESS_GLOW,
                                        IslandProgressColorMode.isEnabled(mode),
                                    )
                                    saveConfig(
                                        RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
                                        IslandProgressColorMode.usesCover(mode),
                                    )
                                    saveConfig(
                                        RootConstants.KEY_HOOK_ISLAND_PROGRESS_GRADIENT,
                                        IslandProgressColorMode.usesCoverGradient(mode),
                                    )
                                },
                            )
                            AnimatedVisibility(
                                visible = IslandProgressColorMode.isEnabled(progressColorMode),
                                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                            ) {
                                Column {
                                    OverlayDropdownPreference(
                                        title = stringResource(id = R.string.title_island_progress_style),
                                        items = progressStyleOptions,
                                        selectedIndex = progressStyle,
                                        onSelectedIndexChange = {
                                            progressStyle = it
                                            saveConfig(
                                                RootConstants.KEY_HOOK_ISLAND_PROGRESS_STYLE,
                                                it
                                            )
                                        }
                                    )
                                    AnimatedVisibility(
                                        visible = progressColorMode == RootConstants.ISLAND_PROGRESS_COLOR_MODE_CUSTOM,
                                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                                    ) {
                                        BasicComponent(
                                            title = stringResource(R.string.title_custom_font_color),
                                            onClick = { showProgressCustomColorDialog = true },
                                            endActions = {
                                                CustomFontColorPreview(color = progressCustomColor)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (lyricMode == RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE) {
                    item(key = "content_options") {
                        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                            Column {
                                OverlayDropdownPreference(
                                    title = stringResource(id = R.string.title_hyper_island_left),
                                    items = contentOptions,
                                    selectedIndex = islandContentLeft,
                                    onSelectedIndexChange = {
                                        islandContentLeft = it; saveConfig(
                                        RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT,
                                        it
                                        )
                                    }
                                )
                                OverlayDropdownPreference(
                                    title = stringResource(id = R.string.title_hyper_island_right),
                                    items = contentOptions,
                                    selectedIndex = islandContentRight,
                                    onSelectedIndexChange = {
                                        islandContentRight = it; saveConfig(
                                        RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT,
                                        it
                                        )
                                    }
                                )
                                if (lyricSource == "lyricon" || lyricSource == "lyricinfo") {
                                    SwitchPreference(
                                        title = stringResource(id = R.string.title_next_lyric_line),
                                        summary = stringResource(id = R.string.summary_next_lyric_line),
                                        checked = nextLyricLine,
                                        onCheckedChange = {
                                            nextLyricLine = it
                                            saveConfig(RootConstants.KEY_HOOK_NEXT_LYRIC_LINE, it)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "next_song_preview") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                        Column {
                            OverlayDropdownPreference(
                                title = stringResource(id = R.string.title_island_next_song_preview),
                                summary = stringResource(id = R.string.summary_island_next_song_preview),
                                items = nextSongPreviewStyleOptions,
                                selectedIndex = nextSongPreviewStyle,
                                onSelectedIndexChange = {
                                    nextSongPreviewStyle = it
                                    saveConfig(
                                        RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_STYLE,
                                        it
                                    )
                                }
                            )
                            AnimatedVisibility(
                                visible = nextSongPreviewStyle ==
                                    RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_HALF
                            ) {
                                Column {
                                    OverlayDropdownPreference(
                                        title = stringResource(
                                            id = R.string.title_island_next_song_preview_position
                                        ),
                                        items = nextSongPreviewPositionOptions,
                                        selectedIndex = nextSongPreviewPositionOptionValues
                                            .indexOfFirst { it == nextSongPreviewPosition }
                                            .coerceAtLeast(0),
                                        onSelectedIndexChange = { index ->
                                            val value = nextSongPreviewPositionOptionValues
                                                .getOrNull(index)
                                                ?: RootConstants.ISLAND_NEXT_SONG_PREVIEW_POSITION_LEFT
                                            nextSongPreviewPosition = value
                                            saveConfig(
                                                RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_POSITION,
                                                value
                                            )
                                        }
                                    )
                                    OverlayDropdownPreference(
                                        title = stringResource(
                                            id = R.string.title_island_next_song_preview_weight
                                        ),
                                        items = nextSongPreviewWeightOptions,
                                        selectedIndex = nextSongPreviewWeight,
                                        onSelectedIndexChange = {
                                            nextSongPreviewWeight = it
                                            saveConfig(
                                                RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_PREVIEW_WEIGHT,
                                                it
                                            )
                                        }
                                    )
                                }
                            }
                            AnimatedVisibility(
                                visible = nextSongPreviewStyle ==
                                    RootConstants.ISLAND_NEXT_SONG_PREVIEW_STYLE_FULL
                            ) {
                                Column {
                                    OverlayDropdownPreference(
                                        title = stringResource(id = R.string.title_next_song_at_end),
                                        summary = stringResource(id = R.string.summary_next_song_at_end),
                                        items = nextSongDurationOptions,
                                        selectedIndex = nextSongDurationSeconds - 1,
                                        onSelectedIndexChange = {
                                            nextSongDurationSeconds = it + 1
                                            saveConfig(
                                                RootConstants.KEY_HOOK_ISLAND_NEXT_SONG_DURATION,
                                                it + 1
                                            )
                                        }
                                    )
                                    SwitchPreference(
                                        title = stringResource(id = R.string.title_force_next_song_at_end),
                                        summary = stringResource(id = R.string.summary_force_next_song_at_end),
                                        checked = forceNextSongAtEnd,
                                        onCheckedChange = {
                                            forceNextSongAtEnd = it
                                            saveConfig(RootConstants.KEY_HOOK_ISLAND_FORCE_NEXT_SONG_AT_END, it)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "special_features_title") { SmallTitle(text = stringResource(id = R.string.title_special_features)) }
                item(key = "playback_behavior") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                        Column {
                            OverlayDropdownPreference(
                                title = stringResource(id = R.string.title_behavior_after_pause),
                                items = afterPauseOptions,
                                selectedIndex = afterPauseBehavior,
                                onSelectedIndexChange = {
                                    afterPauseBehavior = it; saveConfig(
                                    RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
                                    it
                                    )
                                }
                            )
                        }
                    }
                }
    }
}
