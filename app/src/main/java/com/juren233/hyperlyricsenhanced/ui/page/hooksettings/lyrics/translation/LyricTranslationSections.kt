package com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.translation

import com.juren233.hyperlyricsenhanced.common.LyricModeUsage
import com.juren233.hyperlyricsenhanced.ui.component.lyricModeScopedSummary
import com.juren233.hyperlyricsenhanced.ui.component.SectionEnter
import com.juren233.hyperlyricsenhanced.ui.component.SectionExit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

fun LazyListScope.translationSections(
    /** 为 null 时不可用（置灰）；只对一个方向可用时副标题标出方向。 */
    adjacentBackgroundTranslationScope: LyricModeUsage.Scope?,
    translationDisplayMode: Int,
    onTranslationDisplayModeChange: (Int) -> Unit,
    translationFallback: Boolean,
    onTranslationFallbackChange: (Boolean) -> Unit,
    translationOnly: Boolean,
    onTranslationOnlyChange: (Boolean) -> Unit,
    swapTranslation: Boolean,
    onSwapTranslationChange: (Boolean) -> Unit,
    adjacentBackgroundTranslation: Boolean,
    onAdjacentBackgroundTranslationChange: (Boolean) -> Unit,
) {
    item(key = "translation") {
        val translationControlsEnabled =
            translationDisplayMode != RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF

        Column {
            SmallTitle(text = stringResource(R.string.title_translation))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.title_translation_pronunciation_display),
                        items = listOf(
                            stringResource(R.string.option_translation_pronunciation_off),
                            stringResource(R.string.option_translation_pronunciation_translation),
                            stringResource(R.string.option_translation_pronunciation_pronunciation),
                        ),
                        selectedIndex = translationDisplayMode,
                        onSelectedIndexChange = onTranslationDisplayModeChange,
                    )
                    AnimatedVisibility(
                        visible = translationDisplayMode != RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                        enter = SectionEnter,
                        exit = SectionExit,
                    ) {
                        SwitchPreference(
                            title = stringResource(R.string.title_translation_pronunciation_fallback),
                            checked = translationFallback,
                            onCheckedChange = onTranslationFallbackChange,
                        )
                    }
                }
            }
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.title_translation_only),
                        checked = translationOnly,
                        onCheckedChange = onTranslationOnlyChange,
                        enabled = translationControlsEnabled,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.title_swap_translation),
                        checked = swapTranslation,
                        onCheckedChange = onSwapTranslationChange,
                        enabled = translationControlsEnabled,
                    )
                    SwitchPreference(
                        title = stringResource(
                            R.string.title_adjacent_background_translation
                        ),
                        summary = lyricModeScopedSummary(
                            adjacentBackgroundTranslationScope,
                            stringResource(R.string.summary_adjacent_background_translation),
                        ),
                        checked = adjacentBackgroundTranslation,
                        onCheckedChange = onAdjacentBackgroundTranslationChange,
                        enabled = translationControlsEnabled &&
                            adjacentBackgroundTranslationScope != null,
                    )
                }
            }
        }
    }
}
