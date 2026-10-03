/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.IslandTouchAction
import com.juren233.hyperlyricsenhanced.common.IslandTouchBinding
import com.juren233.hyperlyricsenhanced.common.IslandTouchConfig
import com.juren233.hyperlyricsenhanced.common.IslandTouchGesture
import com.juren233.hyperlyricsenhanced.common.IslandTouchSide
import com.juren233.hyperlyricsenhanced.ui.component.NumberInputDialog
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun HyperIslandTouchSettingsPage() {
    val prefs = rememberHookPrefs()
    val save = rememberHookConfigSaver(prefs)
    var config by remember { mutableStateOf(IslandTouchConfig.read(prefs::getBoolean, prefs::getInt)) }
    var editingSeconds by remember { mutableStateOf<Pair<IslandTouchSide, IslandTouchGesture>?>(null) }
    var editingDoubleTap by remember { mutableStateOf(false) }
    var editingLongPress by remember { mutableStateOf(false) }
    var selectedSide by rememberSaveable { mutableIntStateOf(0) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val actions = IslandTouchAction.entries

    fun update(side: IslandTouchSide, gesture: IslandTouchGesture, binding: IslandTouchBinding) {
        config = config.withBinding(side, gesture, binding)
    }

    XposedLyricSettingPage(title = stringResource(R.string.title_island_touch_config)) {
        item(key = "touch_enable") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.island_touch_enable),
                        summary = stringResource(R.string.island_touch_description),
                        checked = config.enabled,
                        onCheckedChange = {
                            config = config.copy(enabled = it)
                            save(IslandTouchConfig.ENABLED, it)
                        },
                    )
                    AnimatedVisibility(
                        visible = config.enabled,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        Column {
                            SwitchPreference(
                                title = stringResource(R.string.island_touch_unified_sides),
                                summary = stringResource(R.string.island_touch_unified_sides_description),
                                checked = config.unifiedSides,
                                onCheckedChange = {
                                    editingSeconds = null
                                    config = config.copy(unifiedSides = it)
                                    save(IslandTouchConfig.UNIFIED_SIDES, it)
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.island_touch_haptic),
                                summary = stringResource(R.string.island_touch_haptic_description),
                                checked = config.hapticFeedback,
                                onCheckedChange = {
                                    config = config.copy(hapticFeedback = it)
                                    save(IslandTouchConfig.HAPTIC_FEEDBACK, it)
                                },
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = config.enabled,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.island_touch_double_interval),
                            endActions = {
                                Text(stringResource(R.string.island_touch_milliseconds_value, config.doubleTapMs),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                            },
                            onClick = { editingDoubleTap = true },
                        )
                    }
                    AnimatedVisibility(
                        visible = config.enabled,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.island_touch_long_press_time),
                            endActions = {
                                Text(stringResource(R.string.island_touch_milliseconds_value, config.longPressMs),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                            },
                            onClick = { editingLongPress = true },
                        )
                    }
                }
            }
        }
        item(key = "touch_gestures") {
            AnimatedVisibility(
                visible = config.enabled,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    AnimatedVisibility(
                        visible = !config.unifiedSides,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        TabRow(
                            tabs = listOf(stringResource(R.string.island_touch_left),
                                stringResource(R.string.island_touch_right)),
                            selectedTabIndex = selectedSide,
                            onTabSelected = { selectedSide = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                            colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent),
                        )
                    }
                    val side = IslandTouchSide.entries[selectedSide]
                    Card(modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                        Column {
                            for (gesture in IslandTouchGesture.entries) {
                                val binding = config.binding(side, gesture)
                                val actionLabels = actions.map {
                                    stringResource(if (it == IslandTouchAction.NONE &&
                                        gesture == IslandTouchGesture.DOUBLE_TAP)
                                        R.string.island_touch_double_disabled else actionLabel(it))
                                }
                                OverlayDropdownPreference(
                                    title = stringResource(gestureLabel(gesture)),
                                    items = actionLabels,
                                    selectedIndex = actions.indexOf(binding.action),
                                    onSelectedIndexChange = { index ->
                                        val action = actions[index]
                                        update(side, gesture, binding.copy(action = action))
                                        save(config.bindingActionKey(side, gesture), action.id)
                                    },
                                )
                                AnimatedVisibility(
                                    visible = binding.action == IslandTouchAction.FORWARD ||
                                        binding.action == IslandTouchAction.BACKWARD,
                                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                                ) {
                                    ArrowPreference(
                                        title = stringResource(R.string.island_touch_seek_seconds),
                                        summary = stringResource(R.string.island_touch_seconds_value, binding.seconds),
                                        onClick = { editingSeconds = side to gesture },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "touch_help") {
            val helpState = stringResource(if (showHelp)
                R.string.island_touch_help_collapse else R.string.island_touch_help_expand)
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp).fillMaxWidth()) {
                Column {
                    BasicComponent(
                        title = stringResource(R.string.island_touch_help),
                        modifier = Modifier.semantics { stateDescription = helpState },
                        onClick = { showHelp = !showHelp },
                        endActions = {
                            val helpIconRotation by animateFloatAsState(
                                targetValue = if (showHelp) 90f else -90f,
                                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                                label = "helpIconRotation",
                            )
                            Icon(MiuixIcons.ChevronBackward, contentDescription = null,
                                modifier = Modifier.rotate(helpIconRotation))
                        },
                    )
                    AnimatedVisibility(
                        visible = showHelp,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (text in listOf(R.string.island_touch_help_area,
                                R.string.island_touch_help_default, R.string.island_touch_help_player,
                                R.string.island_touch_help_mute_copy)) {
                                Text(stringResource(text), fontSize = 14.sp, lineHeight = 21.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                            }
                        }
                    }
                }
            }
        }
    }
    val selected = editingSeconds
    NumberInputDialog(
        show = editingDoubleTap,
        title = stringResource(R.string.island_touch_double_interval),
        label = stringResource(R.string.island_touch_double_interval_range),
        initialValue = config.doubleTapMs,
        min = IslandTouchConfig.MIN_DOUBLE_TAP_MS,
        max = IslandTouchConfig.MAX_DOUBLE_TAP_MS,
        onDismiss = { editingDoubleTap = false },
        onConfirm = { milliseconds ->
            config = config.copy(doubleTapMs = milliseconds)
            save(IslandTouchConfig.DOUBLE_TAP_MS, milliseconds)
        },
    )
    NumberInputDialog(
        show = editingLongPress,
        title = stringResource(R.string.island_touch_long_press_time),
        label = stringResource(R.string.island_touch_long_press_range),
        initialValue = config.longPressMs,
        min = IslandTouchConfig.MIN_LONG_PRESS_MS,
        max = IslandTouchConfig.MAX_LONG_PRESS_MS,
        onDismiss = { editingLongPress = false },
        onConfirm = { milliseconds ->
            config = config.copy(longPressMs = milliseconds)
            save(IslandTouchConfig.LONG_PRESS_MS, milliseconds)
        },
    )
    NumberInputDialog(
        show = selected != null,
        title = stringResource(R.string.island_touch_seek_seconds),
        label = stringResource(R.string.island_touch_seconds_range),
        initialValue = selected?.let { config.binding(it.first, it.second).seconds }
            ?: IslandTouchConfig.DEFAULT_SECONDS,
        min = IslandTouchConfig.MIN_SECONDS,
        max = IslandTouchConfig.MAX_SECONDS,
        onDismiss = { editingSeconds = null },
        onConfirm = { seconds ->
            selected?.let { (side, gesture) ->
                update(side, gesture, config.binding(side, gesture).copy(seconds = seconds))
                save(config.bindingSecondsKey(side, gesture), seconds)
            }
        },
    )
}

private fun gestureLabel(gesture: IslandTouchGesture): Int = when (gesture) {
    IslandTouchGesture.TAP -> R.string.island_touch_tap
    IslandTouchGesture.DOUBLE_TAP -> R.string.island_touch_double_tap
    IslandTouchGesture.LONG_PRESS -> R.string.island_touch_long_press
    IslandTouchGesture.SWIPE_LEFT -> R.string.island_touch_swipe_left
    IslandTouchGesture.SWIPE_RIGHT -> R.string.island_touch_swipe_right
}

private fun actionLabel(action: IslandTouchAction): Int = when (action) {
    IslandTouchAction.NONE -> R.string.island_touch_none
    IslandTouchAction.PLAY_PAUSE -> R.string.island_touch_play_pause
    IslandTouchAction.PREVIOUS -> R.string.island_touch_previous
    IslandTouchAction.NEXT -> R.string.island_touch_next
    IslandTouchAction.FORWARD -> R.string.island_touch_forward
    IslandTouchAction.BACKWARD -> R.string.island_touch_backward
    IslandTouchAction.RESTART -> R.string.island_touch_restart
    IslandTouchAction.TOGGLE_MUTE -> R.string.island_touch_toggle_mute
    IslandTouchAction.VOLUME_UP -> R.string.island_touch_volume_up
    IslandTouchAction.VOLUME_DOWN -> R.string.island_touch_volume_down
    IslandTouchAction.OPEN_APP -> R.string.island_touch_open_app
    IslandTouchAction.OPEN_APP_FREEFORM -> R.string.island_touch_open_app_freeform
    IslandTouchAction.EXPAND_ISLAND -> R.string.island_touch_expand
    IslandTouchAction.OPEN_MEDIA_OUTPUT -> R.string.island_touch_media_output
    IslandTouchAction.COPY_CURRENT_LYRIC -> R.string.island_touch_copy_current_lyric
}
