package com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.IslandLyricPosition
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.ui.component.CustomFontColorPickerDialog
import com.juren233.hyperlyricsenhanced.ui.component.NumberInputDialog
import com.juren233.hyperlyricsenhanced.ui.component.TextInputDialog
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs

internal const val FONT_COLOR_MODE_DEFAULT = 0
internal const val FONT_COLOR_MODE_COVER = 1
internal const val FONT_COLOR_MODE_COVER_GRADIENT = 2
internal const val FONT_COLOR_MODE_CUSTOM = 3

internal fun resolveFontColorMode(
    customEnabled: Boolean,
    coverEnabled: Boolean,
    coverGradient: Boolean,
): Int = when {
    customEnabled -> FONT_COLOR_MODE_CUSTOM
    !coverEnabled -> FONT_COLOR_MODE_DEFAULT
    coverGradient -> FONT_COLOR_MODE_COVER_GRADIENT
    else -> FONT_COLOR_MODE_COVER
}

@Composable
fun LyricDisplayPage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)

    var textSize by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_TEXT_SIZE, RootConstants.DEFAULT_HOOK_TEXT_SIZE)) }
    var textSizeRatio by remember { mutableFloatStateOf(prefs.getFloat(RootConstants.KEY_HOOK_TEXT_SIZE_RATIO, RootConstants.DEFAULT_HOOK_TEXT_SIZE_RATIO)) }
    var fadingEdge by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_FADING_EDGE_LENGTH, RootConstants.DEFAULT_HOOK_FADING_EDGE_LENGTH)) }
    var customFontColorEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR_ENABLED,
                RootConstants.DEFAULT_HOOK_CUSTOM_TEXT_COLOR_ENABLED
            )
        )
    }
    var customFontColor by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR,
                RootConstants.DEFAULT_HOOK_CUSTOM_TEXT_COLOR
            )
        )
    }
    var fontColorMode by remember {
        mutableIntStateOf(
            resolveFontColorMode(
                customEnabled = customFontColorEnabled,
                coverEnabled = prefs.getBoolean(
                    RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR,
                    RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_COLOR
                ),
                coverGradient = prefs.getBoolean(
                    RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_GRADIENT,
                    RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_GRADIENT
                ),
            )
        )
    }
    var customFontPath by remember { mutableStateOf(prefs.getString(RootConstants.KEY_HOOK_CUSTOM_FONT_PATH, null) ?: "") }
    var narrowLatinFont by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_NARROW_LATIN_FONT,
                RootConstants.DEFAULT_HOOK_NARROW_LATIN_FONT
            )
        )
    }
    var fontWeight by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_FONT_WEIGHT, RootConstants.DEFAULT_HOOK_FONT_WEIGHT)) }
    var fontItalic by remember { mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_FONT_ITALIC, RootConstants.DEFAULT_HOOK_FONT_ITALIC)) }
    val legacyLyricPosition = remember {
        IslandLyricPosition.resolve(
            storedPosition = prefs.getInt(RootConstants.KEY_HOOK_LYRIC_POSITION, Int.MIN_VALUE)
                .takeUnless { it == Int.MIN_VALUE },
            legacyCenterEnabled = prefs.getBoolean(
                RootConstants.KEY_HOOK_CENTER_LYRIC,
                RootConstants.DEFAULT_HOOK_CENTER_LYRIC
            )
        )
    }
    var leftLyricPosition by remember {
        mutableIntStateOf(
            IslandLyricPosition.resolveSide(
                storedSidePosition = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_LYRIC_POSITION, Int.MIN_VALUE)
                    .takeUnless { it == Int.MIN_VALUE },
                legacyGlobalPosition = legacyLyricPosition,
                legacyCenterEnabled = false
            )
        )
    }
    var rightLyricPosition by remember {
        mutableIntStateOf(
            IslandLyricPosition.resolveSide(
                storedSidePosition = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_RIGHT_LYRIC_POSITION, Int.MIN_VALUE)
                    .takeUnless { it == Int.MIN_VALUE },
                legacyGlobalPosition = legacyLyricPosition,
                legacyCenterEnabled = false
            )
        )
    }
    var centerGroupVocals by remember { mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_CENTER_GROUP_VOCALS, RootConstants.DEFAULT_HOOK_CENTER_GROUP_VOCALS)) }
    val lyricMode = prefs.getInt(RootConstants.KEY_HOOK_LYRIC_MODE, RootConstants.DEFAULT_HOOK_LYRIC_MODE)
    val leftContent = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT)
    val rightContent = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_RIGHT)
    val showCenterGroupVocals = IslandLyricPosition.supportsGroupVocalCentering(
        lyricMode = lyricMode,
        leftContent = leftContent,
        rightContent = rightContent
    )

    var showTextSizeDialog by remember { mutableStateOf(false) }
    var showTextSizeRatioDialog by remember { mutableStateOf(false) }
    var showFadingEdgeDialog by remember { mutableStateOf(false) }
    var showFontPathDialog by remember { mutableStateOf(false) }
    var showFontWeightDialog by remember { mutableStateOf(false) }
    var showCustomFontColorDialog by remember { mutableStateOf(false) }

    CustomFontColorPickerDialog(
        show = showCustomFontColorDialog,
        initialColor = customFontColor,
        onDismiss = { showCustomFontColorDialog = false },
        onConfirm = { color ->
            customFontColor = color
            saveConfig(RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR, color)
        },
    )

    NumberInputDialog(
        show = showTextSizeDialog,
        title = stringResource(id = R.string.title_size),
        label = stringResource(id = R.string.label_size_range),
        initialValue = textSize,
        min = 8,
        max = 16,
        onDismiss = { showTextSizeDialog = false },
        onConfirm = { value ->
            textSize = value
            saveConfig(RootConstants.KEY_HOOK_TEXT_SIZE, value)
        }
    )
    NumberInputDialog(
        show = showTextSizeRatioDialog,
        title = stringResource(id = R.string.title_text_size_ratio),
        label = stringResource(id = R.string.label_text_size_ratio_range),
        initialValue = (textSizeRatio * 100).toInt(),
        min = 10,
        max = 100,
        onDismiss = { showTextSizeRatioDialog = false },
        onConfirm = { value ->
            textSizeRatio = value.toFloat() / 100f
            saveConfig(RootConstants.KEY_HOOK_TEXT_SIZE_RATIO, textSizeRatio)
        }
    )
    NumberInputDialog(
        show = showFadingEdgeDialog,
        title = stringResource(id = R.string.title_fading_edge),
        label = stringResource(id = R.string.label_fading_edge_range),
        initialValue = fadingEdge,
        min = 0,
        max = 100,
        onDismiss = { showFadingEdgeDialog = false },
        onConfirm = { value ->
            fadingEdge = value
            saveConfig(RootConstants.KEY_HOOK_FADING_EDGE_LENGTH, value)
        }
    )
    TextInputDialog(
        show = showFontPathDialog,
        title = stringResource(id = R.string.title_custom_font),
        label = stringResource(id = R.string.label_custom_font_path),
        initialValue = customFontPath,
        onDismiss = { showFontPathDialog = false },
        onConfirm = { path ->
            customFontPath = path
            saveConfig(RootConstants.KEY_HOOK_CUSTOM_FONT_PATH, path)
        }
    )
    NumberInputDialog(
        show = showFontWeightDialog,
        title = stringResource(id = R.string.title_font_weight),
        label = stringResource(id = R.string.label_font_weight_range),
        initialValue = fontWeight,
        min = 100,
        max = 900,
        onDismiss = { showFontWeightDialog = false },
        onConfirm = { value ->
            fontWeight = value
            saveConfig(RootConstants.KEY_HOOK_FONT_WEIGHT, value)
        }
    )

    XposedLyricSettingPage(title = stringResource(id = R.string.title_text)) {
        lyricDisplaySections(
            textSize = textSize,
            onTextSizeClick = { showTextSizeDialog = true },
            textSizeRatio = textSizeRatio,
            onTextSizeRatioClick = { showTextSizeRatioDialog = true },
            fadingEdge = fadingEdge,
            onFadingEdgeClick = { showFadingEdgeDialog = true },
            fontColorMode = fontColorMode,
            onFontColorModeChange = { mode ->
                fontColorMode = mode
                when (mode) {
                    FONT_COLOR_MODE_COVER -> {
                        saveConfig(RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_GRADIENT, false)
                        saveConfig(RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR, true)
                        customFontColorEnabled = false
                        saveConfig(RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR_ENABLED, false)
                    }
                    FONT_COLOR_MODE_COVER_GRADIENT -> {
                        saveConfig(RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_GRADIENT, true)
                        saveConfig(RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR, true)
                        customFontColorEnabled = false
                        saveConfig(RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR_ENABLED, false)
                    }
                    FONT_COLOR_MODE_CUSTOM -> {
                        customFontColorEnabled = true
                        saveConfig(RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR_ENABLED, true)
                        saveConfig(RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR, false)
                    }
                    else -> {
                        saveConfig(RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR, false)
                        customFontColorEnabled = false
                        saveConfig(RootConstants.KEY_HOOK_CUSTOM_TEXT_COLOR_ENABLED, false)
                    }
                }
            },
            customFontColor = customFontColor,
            onCustomFontColorClick = { showCustomFontColorDialog = true },
            customFontPath = customFontPath,
            onFontPathClick = { showFontPathDialog = true },
            fontWeight = fontWeight,
            onFontWeightClick = { showFontWeightDialog = true },
            fontItalic = fontItalic,
            onFontItalicChange = {
                fontItalic = it
                saveConfig(RootConstants.KEY_HOOK_FONT_ITALIC, it)
            },
            narrowLatinFont = narrowLatinFont,
            onNarrowLatinFontChange = {
                narrowLatinFont = it
                saveConfig(RootConstants.KEY_HOOK_NARROW_LATIN_FONT, it)
            },
            leftLyricPosition = leftLyricPosition,
            onLeftLyricPositionChange = {
                leftLyricPosition = it
                saveConfig(RootConstants.KEY_HOOK_ISLAND_LEFT_LYRIC_POSITION, it)
            },
            rightLyricPosition = rightLyricPosition,
            onRightLyricPositionChange = {
                rightLyricPosition = it
                saveConfig(RootConstants.KEY_HOOK_ISLAND_RIGHT_LYRIC_POSITION, it)
            },
            centerGroupVocals = centerGroupVocals,
            showCenterGroupVocals = showCenterGroupVocals,
            onCenterGroupVocalsChange = {
                centerGroupVocals = it
                saveConfig(RootConstants.KEY_HOOK_CENTER_GROUP_VOCALS, it)
            }
        )
    }
}
