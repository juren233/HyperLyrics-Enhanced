package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import com.juren233.hyperlyricsenhanced.ui.skin.SkinListPage
import com.juren233.hyperlyricsenhanced.ui.skin.ArrowPreference
import com.juren233.hyperlyricsenhanced.ui.skin.Card
import com.juren233.hyperlyricsenhanced.ui.skin.Icon
import com.juren233.hyperlyricsenhanced.ui.skin.IconButton
import com.juren233.hyperlyricsenhanced.ui.skin.OverlayDropdownPreference
import com.juren233.hyperlyricsenhanced.ui.skin.SkinIcons
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.Text
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.ui.component.FloatInputDialog
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.YoYoPresets
import com.juren233.hyperlyricsenhanced.ui.skin.RadioButtonPreference

private val animLabelResMap = mapOf(
    "default" to R.string.option_anim_default,
    "fade_out_fade_in" to R.string.option_anim_fade,
    "fade_out_up_fade_in_up" to R.string.option_anim_fade_up,
    "fade_out_down_fade_in_down" to R.string.option_anim_fade_down,
    "fade_out_left_fade_in_right" to R.string.option_anim_fade_left_right,
    "fade_out_left_fade_in_up" to R.string.option_anim_fade_left_up,
    "fade_out_left_zoom_in" to R.string.option_anim_fade_left_zoom,
    "fade_out_left_landing" to R.string.option_anim_fade_left_landing,
    "fade_out_right_fade_in_left" to R.string.option_anim_fade_right_left,
    "fade_out_right_fade_in_up" to R.string.option_anim_fade_right_up,
    "fade_out_right_zoom_in" to R.string.option_anim_fade_right_zoom,
    "fade_out_right_landing" to R.string.option_anim_fade_right_landing_focus,
    "fade_out_left_zoom_in_right" to R.string.option_anim_fade_left_zoom_right,
    "fade_out_right_zoom_in_left" to R.string.option_anim_fade_right_zoom_left,
    "slide_out_left_slide_in_right" to R.string.option_anim_slide_left_right,
    "slide_out_left_fade_in_up" to R.string.option_anim_slide_left_up,
    "slide_out_left_zoom_in" to R.string.option_anim_slide_left_zoom,
    "slide_out_left_landing" to R.string.option_anim_slide_left_landing,
    "slide_out_right_slide_in_left" to R.string.option_anim_slide_right_left,
    "slide_out_right_fade_in_up" to R.string.option_anim_slide_right_up,
    "slide_out_right_zoom_in" to R.string.option_anim_slide_right_zoom,
    "slide_out_right_landing" to R.string.option_anim_slide_right_landing,
    "flip_out_x_flip_in_x" to R.string.option_anim_flip_x,
    "flip_out_y_flip_in_y" to R.string.option_anim_flip_y,
    "rotate_out_rotate_in" to R.string.option_anim_rotate,
    "zoom_out_zoom_in" to R.string.option_anim_zoom,
)

@Composable
fun LyricAnimationPage() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current


    // 无动画=样式切换动画关闭，此时动画速率整卡置灰（速率只作用于样式切换动画）。
    val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
    var animEnable by remember {
        mutableStateOf(prefs.getBoolean(RootConstants.KEY_HOOK_ANIM_ENABLE, RootConstants.DEFAULT_HOOK_ANIM_ENABLE))
    }
    val onAnimEnableChange: (Boolean) -> Unit = { animEnable = it }
        val lazyListState = rememberLazyListState()
    SkinListPage(
        title = stringResource(id = R.string.title_lyric_anim),
        onBack = { navigator.pop() },
        listState = lazyListState,
    ) {
                animationRateSection(enabled = animEnable)
                animationPageSections(animEnable = animEnable, onAnimEnableChange = onAnimEnableChange)
    }
}

private fun LazyListScope.animationRateSection(enabled: Boolean) {
    item(key = "animation_rate") {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }

        var rate by remember {
            mutableStateOf(
                prefs.getString(RootConstants.KEY_HOOK_SWITCH_ANIM_RATE, RootConstants.DEFAULT_HOOK_SWITCH_ANIM_RATE)
                    ?: RootConstants.DEFAULT_HOOK_SWITCH_ANIM_RATE
            )
        }
        var customRate by remember {
            mutableFloatStateOf(prefs.getFloat(RootConstants.KEY_HOOK_SWITCH_ANIM_CUSTOM_RATE, RootConstants.DEFAULT_HOOK_SWITCH_ANIM_CUSTOM_RATE))
        }
        var showDurationDialog by remember { mutableStateOf(false) }

        val saveConfig = remember {
            { key: String, value: Any ->
                prefs.edit {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
                when (value) {
                    is Boolean -> PrefsBridge.putBoolean(key, value)
                    is Int -> PrefsBridge.putInt(key, value)
                    is Float -> PrefsBridge.putFloat(key, value)
                    is String -> PrefsBridge.putString(key, value)
                }
            }
        }

        val rateValues = listOf(
            RootConstants.SWITCH_ANIM_RATE_SWIFT,
            RootConstants.SWITCH_ANIM_RATE_MODERATE,
            RootConstants.SWITCH_ANIM_RATE_ELEGANT,
            RootConstants.SWITCH_ANIM_RATE_CUSTOM,
        )
        val rateLabels = listOf(
            stringResource(id = R.string.option_switch_anim_rate_swift),
            stringResource(id = R.string.option_switch_anim_rate_moderate),
            stringResource(id = R.string.option_switch_anim_rate_elegant),
            stringResource(id = R.string.option_switch_anim_rate_custom),
        )

        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                OverlayDropdownPreference(
                    title = stringResource(id = R.string.title_switch_anim_rate),
                    items = rateLabels,
                    selectedIndex = rateValues.indexOf(rate).coerceAtLeast(0),
                    enabled = enabled,
                    onSelectedIndexChange = { index ->
                        val value = rateValues[index]
                        rate = value
                        saveConfig(RootConstants.KEY_HOOK_SWITCH_ANIM_RATE, value)
                    }
                )
                AnimatedVisibility(
                    visible = rate == RootConstants.SWITCH_ANIM_RATE_CUSTOM,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                ) {
                    Column {
                        ArrowPreference(
                            title = stringResource(id = R.string.title_switch_anim_custom_rate),
                            enabled = enabled,
                            endActions = {
                                Text(
                                    String.format("%.1f", customRate) + "x",
                                    fontSize = SkinTheme.textStyles.body2.fontSize,
                                    color = if (enabled) {
                                        SkinTheme.colors.onSurfaceVariantActions
                                    } else {
                                        SkinTheme.colors.disabledOnSecondaryVariant
                                    }
                                )
                            },
                            onClick = { showDurationDialog = true }
                        )
                    }
                }
            }
        }

        FloatInputDialog(
            show = showDurationDialog,
            title = stringResource(id = R.string.title_switch_anim_custom_rate),
            label = stringResource(id = R.string.label_switch_anim_custom_rate_range),
            initialValue = customRate,
            min = RootConstants.SWITCH_ANIM_CUSTOM_RATE_MIN,
            max = RootConstants.SWITCH_ANIM_CUSTOM_RATE_MAX,
            onDismiss = { showDurationDialog = false },
            onConfirm = { value ->
                customRate = value
                saveConfig(RootConstants.KEY_HOOK_SWITCH_ANIM_CUSTOM_RATE, value)
            }
        )
    }
}

private fun LazyListScope.animationPageSections(
    animEnable: Boolean,
    onAnimEnableChange: (Boolean) -> Unit
) {
    item(key = "animation_options") {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }

        var animId by remember { mutableStateOf(prefs.getString(RootConstants.KEY_HOOK_ANIM_ID, RootConstants.DEFAULT_HOOK_ANIM_ID) ?: RootConstants.DEFAULT_HOOK_ANIM_ID) }

        val saveConfig = remember {
            { key: String, value: Any ->
                prefs.edit {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                    }
                }
                when (value) {
                    is Boolean -> PrefsBridge.putBoolean(key, value)
                    is String -> PrefsBridge.putString(key, value)
                }
            }
        }

        Card(modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
            Column {
                RadioButtonPreference(
                    title = stringResource(id = R.string.option_anim_none),
                    selected = !animEnable,
                    onClick = {
                        onAnimEnableChange(false)
                        saveConfig(RootConstants.KEY_HOOK_ANIM_ENABLE, false)
                    }
                )
                YoYoPresets.registry.keys.forEach { key ->
                    val labelRes = animLabelResMap[key]
                    val label = if (labelRes != null) stringResource(id = labelRes) else key
                    RadioButtonPreference(
                        title = label,
                        selected = animEnable && animId == key,
                        onClick = {
                            onAnimEnableChange(true)
                            saveConfig(RootConstants.KEY_HOOK_ANIM_ENABLE, true)
                            animId = key
                            saveConfig(RootConstants.KEY_HOOK_ANIM_ID, key)
                        }
                    )
                }
            }
        }
    }
}

