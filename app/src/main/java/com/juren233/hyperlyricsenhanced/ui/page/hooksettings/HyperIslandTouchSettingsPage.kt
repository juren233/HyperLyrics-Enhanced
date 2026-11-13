/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.IslandTouchAction
import com.juren233.hyperlyricsenhanced.common.IslandTouchBinding
import com.juren233.hyperlyricsenhanced.common.IslandTouchConfig
import com.juren233.hyperlyricsenhanced.common.IslandTouchGesture
import com.juren233.hyperlyricsenhanced.common.IslandTouchSide
import com.juren233.hyperlyricsenhanced.ui.component.CollapsibleHelpCard
import com.juren233.hyperlyricsenhanced.ui.component.NumberInputDialog
import com.juren233.hyperlyricsenhanced.ui.component.SwipeableSegmentedTabs
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
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
    // Unified mode has a single shared card, so there is nothing to swipe between.
    val pagerState = rememberPagerState { if (config.unifiedSides) 1 else IslandTouchSide.entries.size }
    val actions = IslandTouchAction.entries

    fun update(side: IslandTouchSide, gesture: IslandTouchGesture, binding: IslandTouchBinding) {
        config = config.withBinding(side, gesture, binding)
    }

    @Composable
    fun GestureCard(side: IslandTouchSide) {
    Card(modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
        Column {
            for (gesture in IslandTouchGesture.entries) {
                val binding = config.binding(side, gesture)
                val items = actions.filter { it.availableFor(gesture) }.map { action ->
                    DropdownItem(
                        text = stringResource(if (action == IslandTouchAction.NONE &&
                            gesture == IslandTouchGesture.DOUBLE_TAP)
                            R.string.island_touch_double_disabled else actionLabel(action)),
                        selected = action == binding.action,
                        summary = actionSummary(action)?.let { stringResource(it) },
                        onClick = {
                            update(side, gesture, binding.copy(action = action))
                            save(config.bindingActionKey(side, gesture), action.id)
                        },
                    )
                }
                if (binding.action == IslandTouchAction.FORWARD ||
                    binding.action == IslandTouchAction.BACKWARD) {
                    // The left side edits the duration; only the right-side value opens the action list.
                    SeekActionPreference(
                        entry = DropdownEntry(items),
                        title = stringResource(gestureLabel(gesture)),
                        summary = stringResource(R.string.island_touch_seek_edit_hint),
                        value = stringResource(
                            if (binding.action == IslandTouchAction.FORWARD)
                                R.string.island_touch_forward_value
                            else R.string.island_touch_backward_value,
                            binding.seconds,
                        ),
                        onEditSeconds = { editingSeconds = side to gesture },
                    )
                } else {
                    OverlayDropdownPreference(
                        entry = DropdownEntry(items),
                        title = stringResource(gestureLabel(gesture)),
                    )
                }
            }
        }
    }
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
                        // The thumb follows the pager and can itself be dragged; tapping a segment also switches sides.
                        SwipeableSegmentedTabs(
                            labels = listOf(stringResource(R.string.island_touch_left),
                                stringResource(R.string.island_touch_right)),
                            pagerState = pagerState,
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                        )
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        beyondViewportPageCount = 1,
                    ) { page -> GestureCard(IslandTouchSide.entries[page]) }
                }
            }
        }
        item(key = "touch_help") {
            CollapsibleHelpCard(
                title = R.string.island_touch_help,
                sections = listOf(
                    R.string.island_touch_help_area_title to R.string.island_touch_help_area_body,
                    R.string.island_touch_help_swipe_title to R.string.island_touch_help_swipe_body,
                    R.string.island_touch_help_tap_title to R.string.island_touch_help_tap_body,
                    R.string.island_touch_help_actions_title to R.string.island_touch_help_actions_body,
                ),
                modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp),
            )
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

/**
 * Mirrors miuix's OverlayDropdownPreference, but the row body edits the seek duration and the
 * action list is opened only from the value on the right.
 */
@Composable
private fun SeekActionPreference(
    entry: DropdownEntry,
    title: String,
    summary: String,
    value: String,
    onEditSeconds: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions
    BasicComponent(
        title = title,
        summary = summary,
        onClick = onEditSeconds,
        endActions = {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .weight(1f, fill = false)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.DropdownList,
                    ) {
                        expanded = !expanded
                        if (expanded) haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp).weight(1f, fill = false),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = actionColor,
                    textAlign = TextAlign.End,
                )
                DropdownArrowEndAction(actionColor = actionColor)
            }
            OverlayDropdownPopup(
                entry = entry,
                show = expanded,
                onDismiss = { expanded = false },
                onDismissFinished = {},
                maxHeight = null,
                dropdownColors = DropdownDefaults.dropdownColors(),
                renderInRootScaffold = true,
                collapseOnSelection = true,
            )
        },
    )
}

private fun gestureLabel(gesture: IslandTouchGesture): Int = when (gesture) {
    IslandTouchGesture.TAP -> R.string.island_touch_tap
    IslandTouchGesture.DOUBLE_TAP -> R.string.island_touch_double_tap
    IslandTouchGesture.LONG_PRESS -> R.string.island_touch_long_press
    IslandTouchGesture.SWIPE_LEFT -> R.string.island_touch_swipe_left
    IslandTouchGesture.SWIPE_RIGHT -> R.string.island_touch_swipe_right
    IslandTouchGesture.SWIPE_UP -> R.string.island_touch_swipe_up
    IslandTouchGesture.SWIPE_DOWN -> R.string.island_touch_swipe_down
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
    IslandTouchAction.VOLUME_CONTINUOUS -> R.string.island_touch_volume_continuous
    IslandTouchAction.OPEN_APP -> R.string.island_touch_open_app
    IslandTouchAction.OPEN_APP_FREEFORM -> R.string.island_touch_open_app_freeform
    IslandTouchAction.EXPAND_ISLAND -> R.string.island_touch_expand
    IslandTouchAction.OPEN_MEDIA_OUTPUT -> R.string.island_touch_media_output
    IslandTouchAction.COPY_CURRENT_LYRIC -> R.string.island_touch_copy_current_lyric
}

private fun actionSummary(action: IslandTouchAction): Int? = when (action) {
    IslandTouchAction.TOGGLE_MUTE -> R.string.island_touch_toggle_mute_summary
    IslandTouchAction.VOLUME_CONTINUOUS -> R.string.island_touch_volume_continuous_summary
    else -> null
}
