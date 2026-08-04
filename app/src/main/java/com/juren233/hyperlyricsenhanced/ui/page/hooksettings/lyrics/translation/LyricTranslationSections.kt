package com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.translation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

fun LazyListScope.translationSections(
    adjacentBackgroundTranslationAvailable: Boolean,
    translationDisplay: Boolean,
    onTranslationDisplayChange: (Boolean) -> Unit,
    translationOnly: Boolean,
    onTranslationOnlyChange: (Boolean) -> Unit,
    swapTranslation: Boolean,
    onSwapTranslationChange: (Boolean) -> Unit,
    adjacentBackgroundTranslation: Boolean,
    onAdjacentBackgroundTranslationChange: (Boolean) -> Unit,
) {
    item(key = "translation") {
        val translationControlsEnabled = translationDisplay

        Column {
            SmallTitle(text = stringResource(R.string.title_translation))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                SwitchPreference(
                    title = stringResource(R.string.title_translation_display),
                    checked = translationDisplay,
                    onCheckedChange = onTranslationDisplayChange,
                )
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
                        summary = stringResource(
                            R.string.summary_adjacent_background_translation
                        ),
                        checked = adjacentBackgroundTranslation,
                        onCheckedChange = onAdjacentBackgroundTranslationChange,
                        enabled = translationControlsEnabled &&
                            adjacentBackgroundTranslationAvailable,
                    )
                }
            }
        }
    }
}
