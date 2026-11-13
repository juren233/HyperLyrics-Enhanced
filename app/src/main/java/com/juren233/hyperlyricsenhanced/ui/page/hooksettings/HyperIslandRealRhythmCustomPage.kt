/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.IslandRealRhythmConfig
import com.juren233.hyperlyricsenhanced.ui.component.CollapsibleHelpCard
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.roundToInt

/** 「跟随音乐真实律动」选「自定义」时的参数页；项与范围见 [IslandRealRhythmConfig]。 */
@Composable
fun HyperIslandRealRhythmCustomPage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)
    var weakBand by remember {
        mutableStateOf(prefs.getBoolean(IslandRealRhythmConfig.KEY_WEAK_BAND, IslandRealRhythmConfig.DEFAULT_WEAK_BAND))
    }
    val msFormat = stringResource(R.string.format_real_rhythm_ms)
    val secondsFormat = stringResource(R.string.format_real_rhythm_seconds)
    val ms: (Int) -> String = { String.format(Locale.ROOT, msFormat, it) }
    val decimal: (Int, Int, Int) -> String = { value, divisor, digits ->
        String.format(Locale.ROOT, "%.${digits}f", value.toFloat() / divisor)
    }

    XposedLyricSettingPage(title = stringResource(R.string.title_real_rhythm_custom)) {
        item(key = "real_rhythm_custom_params") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    RhythmParamSlider(
                        title = stringResource(R.string.title_real_rhythm_envelope),
                        param = IslandRealRhythmConfig.ENVELOPE_MS,
                        prefs = prefs,
                        format = ms,
                        save = saveConfig,
                    )
                    RhythmParamSlider(
                        title = stringResource(R.string.title_real_rhythm_stats_window),
                        param = IslandRealRhythmConfig.STATS_MS,
                        prefs = prefs,
                        format = { String.format(Locale.ROOT, secondsFormat, it / 1000f) },
                        save = saveConfig,
                    )
                    RhythmParamSlider(
                        title = stringResource(R.string.title_real_rhythm_deviations),
                        param = IslandRealRhythmConfig.DEVIATIONS_TENTHS,
                        prefs = prefs,
                        format = { decimal(it, 10, 1) },
                        save = saveConfig,
                    )
                    RhythmParamSlider(
                        title = stringResource(R.string.title_real_rhythm_hold),
                        param = IslandRealRhythmConfig.HOLD_MS,
                        prefs = prefs,
                        format = ms,
                        save = saveConfig,
                    )
                    RhythmParamSlider(
                        title = stringResource(R.string.title_real_rhythm_up),
                        param = IslandRealRhythmConfig.UP_PERCENT,
                        prefs = prefs,
                        format = { decimal(it, 100, 2) },
                        save = saveConfig,
                    )
                    RhythmParamSlider(
                        title = stringResource(R.string.title_real_rhythm_down),
                        param = IslandRealRhythmConfig.DOWN_PERCENT,
                        prefs = prefs,
                        format = { decimal(it, 100, 2) },
                        save = saveConfig,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.title_real_rhythm_weak_band),
                        checked = weakBand,
                        onCheckedChange = {
                            weakBand = it
                            saveConfig(IslandRealRhythmConfig.KEY_WEAK_BAND, it)
                        },
                    )
                }
            }
        }
        item(key = "real_rhythm_custom_help") {
            CollapsibleHelpCard(
                title = R.string.title_real_rhythm_help,
                sections = listOf(
                    R.string.real_rhythm_help_overview_title to R.string.real_rhythm_help_overview_body,
                    R.string.title_real_rhythm_envelope to R.string.real_rhythm_help_envelope_body,
                    R.string.title_real_rhythm_stats_window to R.string.real_rhythm_help_stats_window_body,
                    R.string.title_real_rhythm_deviations to R.string.real_rhythm_help_deviations_body,
                    R.string.title_real_rhythm_hold to R.string.real_rhythm_help_hold_body,
                    R.string.real_rhythm_help_speed_title to R.string.real_rhythm_help_speed_body,
                    R.string.title_real_rhythm_weak_band to R.string.real_rhythm_help_weak_band_body,
                ),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** 一项整数参数：拖动时只更新显示，松手后保存（SystemUI 随即按新值刷新）。 */
@Composable
private fun RhythmParamSlider(
    title: String,
    param: IslandRealRhythmConfig.IntParam,
    prefs: android.content.SharedPreferences,
    format: (Int) -> String,
    save: (String, Any) -> Unit,
) {
    var value by remember(param.key) { mutableIntStateOf(param.coerce(prefs.getInt(param.key, param.default))) }
    BasicComponent(
        title = title,
        endActions = {
            Text(
                text = format(value),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
        bottomAction = {
            Slider(
                value = value.toFloat(),
                onValueChange = { value = param.coerce(it.roundToInt()) },
                onValueChangeFinished = { save(param.key, value) },
                valueRange = param.min.toFloat()..param.max.toFloat(),
                steps = param.steps,
            )
        },
    )
}
