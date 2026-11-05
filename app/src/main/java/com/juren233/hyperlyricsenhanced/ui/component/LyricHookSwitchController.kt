/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.component

import com.juren233.hyperlyricsenhanced.ui.skin.SkinSnackbarHostState
import com.juren233.hyperlyricsenhanced.ui.skin.ArrowPreference
import com.juren233.hyperlyricsenhanced.ui.skin.Card
import com.juren233.hyperlyricsenhanced.ui.skin.Icon
import com.juren233.hyperlyricsenhanced.ui.skin.IconButton
import com.juren233.hyperlyricsenhanced.ui.skin.SkinIcons
import com.juren233.hyperlyricsenhanced.ui.skin.SkinSnackbarHost
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.rememberSkinSnackbarHostState
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import com.juren233.hyperlyricsenhanced.ui.skin.LocalUiStyle
import com.juren233.hyperlyricsenhanced.ui.skin.isGlasmio
import com.juren233.hyperlyricsenhanced.ui.skin.Text
import com.juren233.hyperlyricsenhanced.ui.skin.TextButton
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.root.RootApplication
import com.juren233.hyperlyricsenhanced.service.LiveLyricService
import com.juren233.hyperlyricsenhanced.ui.utils.LocaleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * 歌词相关开关（通知型灵动岛歌词 + 特殊功能）的共享状态。
 *
 * 这些开关在主页与设置页都可能出现：三个歌词入口至少有一个开启时留在主页，
 * 全部关闭（主页被隐藏）时迁到设置页顶部。为保证两处读写一致，状态与副作用统一由本控制器持有。
 */
@Stable
class LyricHookSwitchController internal constructor(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val messages: LyricHookMessages,
) {
    var enableDynamicIsland by mutableStateOf(
        prefs.getBoolean(
            RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND,
            RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND,
        )
    )
        private set

    var unlockIslandLength by mutableStateOf(
        prefs.getBoolean(
            RootConstants.KEY_HOOK_UNLOCK_ISLAND_LENGTH,
            RootConstants.DEFAULT_HOOK_UNLOCK_ISLAND_LENGTH,
        )
    )
        private set

    var removeFocusWhitelist by mutableStateOf(
        prefs.getBoolean(
            RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST,
            RootConstants.DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST,
        )
    )
        private set

    var removeIslandWhitelist by mutableStateOf(
        prefs.getBoolean(
            RootConstants.KEY_HOOK_REMOVE_ISLAND_WHITELIST,
            RootConstants.DEFAULT_HOOK_REMOVE_ISLAND_WHITELIST,
        )
    )
        private set

    /** 通知型灵动岛歌词缺少权限时弹出的授权面板。 */
    var showPermissionSheet by mutableStateOf(false)
        private set

    /** 授权面板内的 Snackbar 宿主（与原主页实现保持一致，提示落在面板内）。 */
    val sheetSnackbarHostState = SkinSnackbarHostState()

    internal val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND ->
                enableDynamicIsland = p.getBoolean(
                    RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND,
                    RootConstants.DEFAULT_HOOK_ENABLE_DYNAMIC_ISLAND,
                )
            RootConstants.KEY_HOOK_UNLOCK_ISLAND_LENGTH ->
                unlockIslandLength = p.getBoolean(
                    RootConstants.KEY_HOOK_UNLOCK_ISLAND_LENGTH,
                    RootConstants.DEFAULT_HOOK_UNLOCK_ISLAND_LENGTH,
                )
            RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST ->
                removeFocusWhitelist = p.getBoolean(
                    RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST,
                    RootConstants.DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST,
                )
            RootConstants.KEY_HOOK_REMOVE_ISLAND_WHITELIST ->
                removeIslandWhitelist = p.getBoolean(
                    RootConstants.KEY_HOOK_REMOVE_ISLAND_WHITELIST,
                    RootConstants.DEFAULT_HOOK_REMOVE_ISLAND_WHITELIST,
                )
        }
    }

    fun onDynamicIslandToggle(checked: Boolean) {
        if (checked) {
            if (hasDynamicIslandPermissions()) {
                setDynamicIslandEnabled(true)
            } else {
                showPermissionSheet = true
            }
        } else {
            setDynamicIslandEnabled(false)
        }
    }

    fun onUnlockIslandLengthToggle(checked: Boolean) {
        toggleXposedPreference(
            key = RootConstants.KEY_HOOK_UNLOCK_ISLAND_LENGTH,
            checked = checked,
            currentValue = unlockIslandLength,
        ) { unlockIslandLength = it }
    }

    fun onRemoveFocusWhitelistToggle(checked: Boolean) {
        toggleXposedPreference(
            key = RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST,
            checked = checked,
            currentValue = removeFocusWhitelist,
        ) { removeFocusWhitelist = it }
    }

    fun onRemoveIslandWhitelistToggle(checked: Boolean) {
        toggleXposedPreference(
            key = RootConstants.KEY_HOOK_REMOVE_ISLAND_WHITELIST,
            checked = checked,
            currentValue = removeIslandWhitelist,
        ) { removeIslandWhitelist = it }
    }

    fun dismissPermissionSheet() {
        showPermissionSheet = false
    }

    fun confirmPermissionSheet() {
        if (hasDynamicIslandPermissions()) {
            showPermissionSheet = false
            setDynamicIslandEnabled(true)
        } else {
            showSheetSnackbar(messages.permissionNotGranted)
        }
    }

    /** 通知权限系统弹窗回调。 */
    fun onNotificationPermissionResult(granted: Boolean) {
        showSheetSnackbar(
            if (granted) messages.permissionGranted else messages.permissionDenied
        )
    }

    /** 打开系统“通知使用权”设置页，失败时在面板内提示。 */
    fun openNotificationListenerSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            showSheetSnackbar(messages.openSettingsFailed)
        }
    }

    private fun hasDynamicIslandPermissions(): Boolean {
        val hasPostNotification = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val hasListenerPermission =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        return hasPostNotification && hasListenerPermission
    }

    private fun setDynamicIslandEnabled(enabled: Boolean) {
        enableDynamicIsland = enabled
        prefs.edit { putBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, enabled) }
        PrefsBridge.putBoolean(RootConstants.KEY_HOOK_ENABLE_DYNAMIC_ISLAND, enabled)
        if (enabled) {
            LiveLyricService.ensureListenerBound(context)
        }
    }

    /** 需要 LSPosed 作用域的开关：未激活时只提示，不写入配置。 */
    private fun toggleXposedPreference(
        key: String,
        checked: Boolean,
        currentValue: Boolean,
        onChanged: (Boolean) -> Unit,
    ) {
        if (checked && RootApplication.xposedService == null) {
            scope.launch {
                sheetSnackbarHostState.showSnackbar(
                    message = messages.xposedNotActive,
                    durationMillis = 2000L,
                )
            }
        } else if (checked != currentValue) {
            onChanged(checked)
            prefs.edit { putBoolean(key, checked) }
            PrefsBridge.putBoolean(key, checked)
        }
    }

    private fun showSheetSnackbar(message: String) {
        scope.launch {
            sheetSnackbarHostState.showSnackbar(
                message = message,
                durationMillis = 2000L,
            )
        }
    }
}

/** 控制器需要的文案，随语言切换重建控制器。 */
@Immutable
internal data class LyricHookMessages(
    val xposedNotActive: String,
    val permissionGranted: String,
    val permissionDenied: String,
    val permissionNotGranted: String,
    val openSettingsFailed: String,
)

@Composable
fun rememberLyricHookSwitchController(): LyricHookSwitchController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) {
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
    }
    val messages = LyricHookMessages(
        xposedNotActive = stringResource(R.string.toast_xposed_module_not_active),
        permissionGranted = stringResource(R.string.toast_permission_granted),
        permissionDenied = stringResource(R.string.toast_permission_denied),
        permissionNotGranted = stringResource(R.string.toast_permission_not_granted),
        openSettingsFailed = stringResource(R.string.toast_open_settings_failed),
    )
    val controller = remember(prefs, messages) {
        LyricHookSwitchController(context, prefs, scope, messages)
    }
    DisposableEffect(prefs, controller) {
        prefs.registerOnSharedPreferenceChangeListener(controller.listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(controller.listener) }
    }
    return controller
}

/** 通知型灵动岛歌词的权限面板，主页与设置页共用。 */
@Composable
fun LyricHookPermissionSheet(controller: LyricHookSwitchController) {
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = controller::onNotificationPermissionResult,
    )

    if (LocalUiStyle.current.isGlasmio) {
        if (controller.showPermissionSheet) {
            GlasmioBottomSheet(onDismissRequest = controller::dismissPermissionSheet, fitContent = true) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    Text(
                        text = stringResource(R.string.sheet_permission_title),
                        color = SkinTheme.colors.onBackground,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    GlasmioGroupedList {
                        GlasmioListRow(
                            title = stringResource(R.string.title_permission_post_notification),
                            onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        )
                        GlasmioListRow(
                            title = stringResource(R.string.title_permission_listener),
                            onClick = controller::openNotificationListenerSettings,
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = stringResource(R.string.close),
                            onClick = controller::dismissPermissionSheet,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = stringResource(R.string.confirm),
                            onClick = controller::confirmPermissionSheet,
                            modifier = Modifier.weight(1f),
                            primary = true,
                        )
                    }
                }
                SkinSnackbarHost(state = controller.sheetSnackbarHostState)
            }
        }
        return
    }

    WindowBottomSheet(
        show = controller.showPermissionSheet,
        title = stringResource(R.string.sheet_permission_title),
        allowDismiss = false,
        backgroundColor = SkinTheme.colors.surface,
        startAction = LocaleUtils.localizedWindowContent {
            IconButton(onClick = controller::dismissPermissionSheet) {
                Icon(
                    icon = SkinIcons.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = SkinTheme.colors.onBackground,
                )
            }
        },
        endAction = LocaleUtils.localizedWindowContent {
            IconButton(onClick = controller::confirmPermissionSheet) {
                Icon(
                    icon = SkinIcons.Ok,
                    contentDescription = stringResource(R.string.confirm),
                    tint = SkinTheme.colors.onBackground,
                )
            }
        },
        onDismissRequest = controller::dismissPermissionSheet,
        content = LocaleUtils.localizedWindowContent {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val paddingPx = 24.dp.roundToPx()
                        val placeable = measurable.measure(
                            constraints.copy(maxWidth = constraints.maxWidth + paddingPx * 2)
                        )
                        layout(constraints.maxWidth, placeable.height) {
                            placeable.place(-paddingPx, 0)
                        }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 40.dp)
                ) {
                    Card(modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                        ArrowPreference(
                            title = stringResource(R.string.title_permission_post_notification),
                            onClick = {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.title_permission_listener),
                            onClick = controller::openNotificationListenerSettings,
                        )
                    }
                }
                SkinSnackbarHost(
                    state = controller.sheetSnackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
    )
}
