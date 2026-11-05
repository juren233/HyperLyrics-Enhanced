package com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.scroll

import com.juren233.hyperlyricsenhanced.ui.skin.ArrowPreference
import com.juren233.hyperlyricsenhanced.ui.skin.Card
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.SmallTitle
import com.juren233.hyperlyricsenhanced.ui.skin.SwitchPreference
import com.juren233.hyperlyricsenhanced.ui.skin.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants

fun LazyListScope.lyricScrollSections(
    lyricMode: Int,
    marqueeMode: Boolean,
    onMarqueeModeChange: (Boolean) -> Unit,
    marqueeSpeed: Int,
    onMarqueeSpeedClick: () -> Unit,
    marqueeDelay: Int,
    onMarqueeDelayClick: () -> Unit,
    marqueeInfinite: Boolean,
    onMarqueeInfiniteChange: (Boolean) -> Unit,
    marqueeLoop: Int,
    onMarqueeLoopClick: () -> Unit,
    marqueeStopEnd: Boolean,
    onMarqueeStopEndChange: (Boolean) -> Unit,
    marqueeMetadataMode: Boolean,
    onMarqueeMetadataModeChange: (Boolean) -> Unit,
    marqueeMetadataSpeed: Int,
    onMarqueeMetadataSpeedClick: () -> Unit,
    marqueeMetadataDelay: Int,
    onMarqueeMetadataDelayClick: () -> Unit,
    marqueeMetadataInfinite: Boolean,
    onMarqueeMetadataInfiniteChange: (Boolean) -> Unit,
    marqueeMetadataLoopDelay: Int,
    onMarqueeMetadataLoopClick: () -> Unit
) {
    item(key = "lyric_scroll") {
        Column {
            SmallTitle(text = stringResource(id = R.string.title_marquee))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = stringResource(id = R.string.title_lyric_marquee),
                        summary = stringResource(id = R.string.summary_lyric_marquee),
                        checked = marqueeMode,
                        onCheckedChange = onMarqueeModeChange
                    )
                    ArrowPreference(
                        title = stringResource(id = R.string.title_marquee_speed),
                        endActions = {
                            Text(
                                "$marqueeSpeed",
                                fontSize = SkinTheme.textStyles.body2.fontSize,
                                color = if (marqueeMode) SkinTheme.colors.onSurfaceVariantActions else SkinTheme.colors.disabledOnSecondaryVariant
                            )
                        },
                        onClick = onMarqueeSpeedClick,
                        enabled = marqueeMode
                    )
                    ArrowPreference(
                        title = stringResource(id = R.string.title_marquee_delay),
                        endActions = {
                            Text(
                                stringResource(id = R.string.format_ms, marqueeDelay),
                                fontSize = SkinTheme.textStyles.body2.fontSize,
                                color = if (marqueeMode) SkinTheme.colors.onSurfaceVariantActions else SkinTheme.colors.disabledOnSecondaryVariant
                            )
                        },
                        onClick = onMarqueeDelayClick,
                        enabled = marqueeMode
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.title_infinite_loop),
                        checked = marqueeInfinite,
                        onCheckedChange = onMarqueeInfiniteChange,
                        enabled = marqueeMode
                    )
                    ArrowPreference(
                        title = stringResource(id = R.string.title_marquee_loop),
                        endActions = {
                            Text(
                                stringResource(id = R.string.format_ms, marqueeLoop),
                                fontSize = SkinTheme.textStyles.body2.fontSize,
                                color = if (marqueeMode) SkinTheme.colors.onSurfaceVariantActions else SkinTheme.colors.disabledOnSecondaryVariant
                            )
                        },
                        onClick = onMarqueeLoopClick,
                        enabled = marqueeMode
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.title_stop_at_end),
                        checked = marqueeStopEnd,
                        onCheckedChange = onMarqueeStopEndChange,
                        enabled = marqueeMode
                    )
                }
            }
            if (lyricMode == RootConstants.HOOK_LYRIC_MODE_SINGLE_SIDE) {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                    Column {
                        SwitchPreference(
                            title = stringResource(id = R.string.title_marquee_metadata_mode),
                            summary = stringResource(id = R.string.summary_marquee_metadata_mode),
                            checked = marqueeMetadataMode,
                            onCheckedChange = onMarqueeMetadataModeChange
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.title_marquee_metadata_speed),
                            onClick = onMarqueeMetadataSpeedClick,
                            enabled = marqueeMetadataMode,
                            endActions = {
                                Text(
                                    "$marqueeMetadataSpeed",
                                    fontSize = SkinTheme.textStyles.body2.fontSize,
                                    color = if (marqueeMetadataMode) SkinTheme.colors.onSurfaceVariantActions else SkinTheme.colors.disabledOnSecondaryVariant
                                )
                            }
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.title_marquee_metadata_delay),
                            onClick = onMarqueeMetadataDelayClick,
                            enabled = marqueeMetadataMode,
                            endActions = {
                                Text(
                                    stringResource(id = R.string.format_ms, marqueeMetadataDelay),
                                    fontSize = SkinTheme.textStyles.body2.fontSize,
                                    color = if (marqueeMetadataMode) SkinTheme.colors.onSurfaceVariantActions else SkinTheme.colors.disabledOnSecondaryVariant
                                )
                            }
                        )
                        SwitchPreference(
                            title = stringResource(id = R.string.title_marquee_metadata_infinite),
                            checked = marqueeMetadataInfinite,
                            onCheckedChange = onMarqueeMetadataInfiniteChange,
                            enabled = marqueeMetadataMode
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.title_marquee_metadata_loop),
                            onClick = onMarqueeMetadataLoopClick,
                            enabled = marqueeMetadataMode,
                            endActions = {
                                Text(
                                    stringResource(id = R.string.format_ms, marqueeMetadataLoopDelay),
                                    fontSize = SkinTheme.textStyles.body2.fontSize,
                                    color = if (marqueeMetadataMode) SkinTheme.colors.onSurfaceVariantActions else SkinTheme.colors.disabledOnSecondaryVariant
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
