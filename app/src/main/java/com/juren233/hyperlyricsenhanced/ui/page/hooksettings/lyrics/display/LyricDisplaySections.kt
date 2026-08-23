package com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.display

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.CustomFontColorPreview
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun LazyListScope.lyricDisplaySections(
    textSize: Int,
    onTextSizeClick: () -> Unit,
    textSizeRatio: Float,
    onTextSizeRatioClick: () -> Unit,
    fadingEdge: Int,
    onFadingEdgeClick: () -> Unit,
    fontColorMode: Int,
    onFontColorModeChange: (Int) -> Unit,
    customFontColor: Int,
    onCustomFontColorClick: () -> Unit,
    customFontPath: String,
    onFontPathClick: () -> Unit,
    fontWeight: Int,
    onFontWeightClick: () -> Unit,
    fontItalic: Boolean,
    onFontItalicChange: (Boolean) -> Unit,
    narrowLatinFont: Boolean,
    onNarrowLatinFontChange: (Boolean) -> Unit,
    leftLyricPosition: Int,
    onLeftLyricPositionChange: (Int) -> Unit,
    rightLyricPosition: Int,
    onRightLyricPositionChange: (Int) -> Unit,
    centerGroupVocals: Boolean,
    onCenterGroupVocalsChange: (Boolean) -> Unit,
    showCenterGroupVocals: Boolean
) {
    item(key = "lyric_display") {
        Column {
            SmallTitle(text = stringResource(id = R.string.title_text))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(id = R.string.title_size),
                        endActions = {
                            Text(
                                "$textSize",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        },
                        onClick = onTextSizeClick
                    )
                    ArrowPreference(
                        title = stringResource(id = R.string.title_text_size_ratio),
                        endActions = {
                            Text(
                                stringResource(id = R.string.format_percent, (textSizeRatio * 100).toInt()),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        },
                        onClick = onTextSizeRatioClick
                    )
                    ArrowPreference(
                        title = stringResource(id = R.string.title_fading_edge),
                        endActions = {
                            Text(
                                "$fadingEdge",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        },
                        onClick = onFadingEdgeClick
                    )
                    val positionOptions = listOf(
                        stringResource(id = R.string.option_lyric_position_default),
                        stringResource(id = R.string.option_lyric_position_center),
                        stringResource(id = R.string.option_lyric_position_right),
                    )
                    OverlayDropdownPreference(
                        title = stringResource(id = R.string.title_left_lyric_position),
                        items = positionOptions,
                        selectedIndex = leftLyricPosition.coerceIn(0, positionOptions.lastIndex),
                        onSelectedIndexChange = onLeftLyricPositionChange
                    )
                    OverlayDropdownPreference(
                        title = stringResource(id = R.string.title_right_lyric_position),
                        items = positionOptions,
                        selectedIndex = rightLyricPosition.coerceIn(0, positionOptions.lastIndex),
                        onSelectedIndexChange = onRightLyricPositionChange
                    )
                    AnimatedVisibility(visible = showCenterGroupVocals) {
                        SwitchPreference(
                            title = stringResource(id = R.string.title_center_group_vocals),
                            checked = centerGroupVocals,
                            onCheckedChange = onCenterGroupVocalsChange
                        )
                    }
                }
            }
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    val fontColorOptions = listOf(
                        stringResource(id = R.string.option_font_color_default),
                        stringResource(id = R.string.option_font_color_monet),
                        stringResource(id = R.string.option_font_color_cover),
                        stringResource(id = R.string.option_font_color_cover_gradient),
                        stringResource(id = R.string.option_font_color_custom),
                    )
                    OverlayDropdownPreference(
                        title = stringResource(id = R.string.title_font_color),
                        items = fontColorOptions,
                        selectedIndex = fontColorMode.coerceIn(0, fontColorOptions.lastIndex),
                        onSelectedIndexChange = onFontColorModeChange
                    )
                    AnimatedVisibility(
                        visible = fontColorMode == FONT_COLOR_MODE_CUSTOM,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.title_custom_font_color),
                            onClick = onCustomFontColorClick,
                            endActions = {
                                CustomFontColorPreview(color = customFontColor)
                            },
                        )
                    }
                }
            }
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = stringResource(id = R.string.title_narrow_latin_font),
                        checked = narrowLatinFont,
                        onCheckedChange = onNarrowLatinFontChange
                    )
                    ArrowPreference(
                        title = stringResource(id = R.string.title_custom_font),
                        endActions = {
                            Text(
                                customFontPath.ifEmpty { stringResource(id = R.string.summary_default_font) },
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        },
                        onClick = onFontPathClick
                    )
                    ArrowPreference(
                        title = stringResource(id = R.string.title_font_weight),
                        endActions = {
                            Text(
                                fontWeight.toString(),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        },
                        onClick = onFontWeightClick
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.title_italic),
                        checked = fontItalic,
                        onCheckedChange = onFontItalicChange
                    )
                }
            }
        }
    }
}
