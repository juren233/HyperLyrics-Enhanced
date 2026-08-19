/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun OnlineTranslationSourcesPage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)
    val sourceOrder = remember {
        mutableStateListOf<Source>().apply {
            addAll(
                OnlineTranslationSourcePreferences.normalizeOrder(
                    prefs.getString(
                        com.juren233.hyperlyricsenhanced.common.RootConstants
                            .KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
                        com.juren233.hyperlyricsenhanced.common.RootConstants
                            .DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
                    )
                )
            )
        }
    }
    val configuredEnabledSources = remember {
        sourceOrder.filter { source ->
            OnlineTranslationSourcePreferences.isSourceEnabled(prefs, source)
        }
    }
    val initialEnabledSources = remember {
        OnlineTranslationSourcePreferences.resolveEnabledSources(sourceOrder) { source ->
            source in configuredEnabledSources
        }
    }
    val sourceEnabled = remember {
        mutableStateMapOf<Source, Boolean>().apply {
            sourceOrder.forEach { source ->
                this[source] = source in initialEnabledSources
            }
        }
    }
    var autoSelectBestSource by remember {
        mutableStateOf(
            OnlineTranslationSourcePreferences.isAutoSelectBestSourceEnabled(prefs)
        )
    }
    var saltPreferOnline by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE,
                RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE,
            )
        )
    }
    var pendingSwap by remember { mutableStateOf<SourceSwap?>(null) }
    val swapProgress = remember { Animatable(0f) }
    val sourceRowHeightPx = with(LocalDensity.current) { SOURCE_ROW_HEIGHT.toPx() }
    val appEnabled = remember {
        mutableStateMapOf<String, Boolean>().apply {
            ENABLED_APPS.forEach { app ->
                this[app.packageName] = OnlineTranslationSourcePreferences.isAppEnabled(
                    prefs,
                    app.packageName,
                )
            }
        }
    }
    val context = LocalContext.current
    var installedApps by remember { mutableStateOf<List<InstalledTranslationApp>?>(null) }
    LaunchedEffect(Unit) {
        if (configuredEnabledSources.isEmpty()) {
            initialEnabledSources.firstOrNull()?.let { source ->
                saveConfig(
                    OnlineTranslationSourcePreferences.sourcePreferenceKey(source),
                    true,
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val installedPackageNames = packageManager
                .getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                .map { it.packageName }
                .toSet()
            ENABLED_APPS
                .filter { it.packageName in installedPackageNames }
                .mapNotNull { app ->
                    runCatching {
                        val info = packageManager.getApplicationInfo(app.packageName, 0)
                        InstalledTranslationApp(
                            app = app,
                            icon = info.loadIcon(packageManager),
                        )
                    }.getOrNull()
                }
        }
    }

    /** 请求相邻来源互换，动画期间拒绝新的排序操作。 */
    fun requestSourceMove(source: Source, direction: Int) {
        if (pendingSwap != null || swapProgress.value != 0f) return
        val sourceIndex = sourceOrder.indexOf(source)
        val targetIndex = sourceIndex + direction
        if (sourceIndex !in sourceOrder.indices || targetIndex !in sourceOrder.indices) return
        pendingSwap = SourceSwap(
            source = source,
            adjacentSource = sourceOrder[targetIndex],
            direction = direction,
        )
    }

    // 两个相邻行沿相反方向移动，抵达目标位置后再提交实际顺序。
    LaunchedEffect(pendingSwap) {
        val swap = pendingSwap
        if (swap == null) {
            swapProgress.snapTo(0f)
            return@LaunchedEffect
        }
        swapProgress.snapTo(0f)
        swapProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = SOURCE_SWAP_ANIMATION_DURATION_MS,
                easing = FastOutSlowInEasing,
            ),
        )
        val sourceIndex = sourceOrder.indexOf(swap.source)
        val targetIndex = sourceOrder.indexOf(swap.adjacentSource)
        val orderChanged = targetIndex == sourceIndex + swap.direction
        Snapshot.withMutableSnapshot {
            if (orderChanged) {
                sourceOrder[sourceIndex] = swap.adjacentSource
                sourceOrder[targetIndex] = swap.source
            }
            pendingSwap = null
        }
        if (orderChanged) {
            saveConfig(
                com.juren233.hyperlyricsenhanced.common.RootConstants
                    .KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
                OnlineTranslationSourcePreferences.serializeOrder(sourceOrder),
            )
        }
    }

    XposedLyricSettingPage(title = stringResource(R.string.title_online_translation_sources)) {
        item(key = "platform_sources_title") {
            SmallTitle(text = stringResource(R.string.title_online_translation_platform_sources))
        }
        item(key = "platform_sources") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    val enabledSources = sourceEnabled
                        .filterValues { enabled -> enabled }
                        .keys
                    sourceOrder.forEachIndexed { index, source ->
                        key(source) {
                            val swapDirection = when (source) {
                                pendingSwap?.source -> pendingSwap?.direction ?: 0
                                pendingSwap?.adjacentSource -> -(pendingSwap?.direction ?: 0)
                                else -> 0
                            }
                            SourceOrderPreference(
                                source = source,
                                priority = index + 1,
                                checked = sourceEnabled[source] == true,
                                enabled = OnlineTranslationSourcePreferences.canToggleSource(
                                    source,
                                    enabledSources,
                                ),
                                sortingVisible = !autoSelectBestSource,
                                offsetY = swapDirection * sourceRowHeightPx * swapProgress.value,
                                isMovingForward = pendingSwap?.source == source,
                                canMoveUp = index > 0 &&
                                    pendingSwap == null && swapProgress.value == 0f,
                                canMoveDown = index < sourceOrder.lastIndex &&
                                    pendingSwap == null && swapProgress.value == 0f,
                                onCheckedChange = { checked ->
                                    val canApplyChange = checked ||
                                        OnlineTranslationSourcePreferences.canToggleSource(
                                            source,
                                            enabledSources,
                                        )
                                    if (canApplyChange) {
                                        sourceEnabled[source] = checked
                                        saveConfig(
                                            OnlineTranslationSourcePreferences
                                                .sourcePreferenceKey(source),
                                            checked,
                                        )
                                    }
                                },
                                onMoveUp = { requestSourceMove(source, -1) },
                                onMoveDown = { requestSourceMove(source, 1) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "auto_select_best_source") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                SwitchPreference(
                    title = stringResource(
                        R.string.title_online_translation_auto_select_best_source
                    ),
                    summary = stringResource(
                        R.string.summary_online_translation_auto_select_best_source
                    ),
                    checked = autoSelectBestSource,
                    onCheckedChange = { checked ->
                        autoSelectBestSource = checked
                        if (checked) pendingSwap = null
                        saveConfig(
                            RootConstants.KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
                            checked,
                        )
                    },
                )
            }
        }
        enabledAppsSection(
            installedApps = installedApps,
            appEnabled = appEnabled,
            onCheckedChange = { packageName, checked ->
                appEnabled[packageName] = checked
                OnlineTranslationSourcePreferences.appPreferenceKey(packageName)?.let { key ->
                    saveConfig(key, checked)
                }
            },
        )
        val saltPackageName = OnlineTranslationSourcePreferences.SALT_PACKAGE
        val saltApplies = installedApps?.any { it.app.packageName == saltPackageName } == true &&
            appEnabled[saltPackageName] == true
        specialSettingsSection(
            saltApplies = saltApplies,
            preferOnline = saltPreferOnline,
            onPreferOnlineChange = { checked ->
                saltPreferOnline = checked
                saveConfig(
                    RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE,
                    checked,
                )
            },
        )
    }
}

private fun LazyListScope.specialSettingsSection(
    saltApplies: Boolean,
    preferOnline: Boolean,
    onPreferOnlineChange: (Boolean) -> Unit,
) {
    item(key = "special_settings") {
        AnimatedVisibility(
            visible = saltApplies,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Column {
                SmallTitle(
                    text = stringResource(
                        R.string.title_online_translation_salt_special_settings
                    )
                )
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .fillMaxWidth()
                ) {
                    Column {
                        SwitchPreference(
                            title = stringResource(
                                R.string.title_online_translation_salt_prefer_online
                            ),
                            summary = stringResource(
                                R.string.summary_online_translation_salt_prefer_online
                            ),
                            checked = preferOnline,
                            onCheckedChange = onPreferOnlineChange,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.enabledAppsSection(
    installedApps: List<InstalledTranslationApp>?,
    appEnabled: Map<String, Boolean>,
    onCheckedChange: (String, Boolean) -> Unit,
) {
    item(key = "enabled_apps_title") {
        SmallTitle(text = stringResource(R.string.title_online_translation_enabled_apps))
    }
    item(key = "enabled_apps") {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
        ) {
            val apps = installedApps
            when {
                apps == null -> Unit
                apps.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.title_online_translation_no_enabled_apps),
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                else -> Column {
                    apps.forEach { installedApp ->
                        AppSwitchPreference(
                            app = installedApp.app,
                            icon = installedApp.icon,
                            checked = appEnabled[installedApp.app.packageName] == true,
                            onCheckedChange = { checked ->
                                onCheckedChange(installedApp.app.packageName, checked)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 来源行右侧通过上下箭头调整优先级，开关区域保持独立可点击。 */
@Composable
private fun SourceOrderPreference(
    source: Source,
    priority: Int,
    checked: Boolean,
    enabled: Boolean,
    sortingVisible: Boolean,
    offsetY: Float,
    isMovingForward: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val sourceControlsSlideDistancePx = with(LocalDensity.current) {
        SOURCE_CONTROLS_SLIDE_DISTANCE.toPx()
    }
    val sortingProgress by animateFloatAsState(
        targetValue = if (sortingVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = SOURCE_CONTROLS_ANIMATION_DURATION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "sourceSortingControls",
    )
    val prioritySlotWidth = (SOURCE_PRIORITY_BADGE_SIZE + SOURCE_PRIORITY_TO_NAME_GAP) * sortingProgress
    val moveButtonsSlotWidth = SOURCE_MOVE_BUTTONS_WIDTH * sortingProgress
    val rowEndPadding = SOURCE_ROW_HORIZONTAL_PADDING -
        (SOURCE_ROW_HORIZONTAL_PADDING - SOURCE_ROW_END_PADDING) * sortingProgress
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SOURCE_ROW_HEIGHT)
            .zIndex(if (isMovingForward) 1f else 0f)
            .graphicsLayer {
                translationY = offsetY
            }
            .padding(
                start = SOURCE_ROW_HORIZONTAL_PADDING,
                end = rowEndPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Keep the slot and badge measured throughout the animation. Only the
        // slot width and the badge's visual offset change, so the title never
        // jumps when the controls reach their final state.
        Box(
            modifier = Modifier
                .width(prioritySlotWidth)
                .requiredHeight(SOURCE_PRIORITY_BADGE_SIZE)
                .graphicsLayer { clip = true },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .requiredWidth(SOURCE_PRIORITY_BADGE_SIZE)
                    .requiredHeight(SOURCE_PRIORITY_BADGE_SIZE)
                    .graphicsLayer {
                        alpha = sortingProgress
                        translationX = -sourceControlsSlideDistancePx *
                            (1f - sortingProgress)
                    },
            ) {
                SourcePriorityBadge(priority)
            }
        }
        Text(
            text = source.displayName(),
            modifier = Modifier.weight(1f),
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackground,
        )
        // The switch always has the same measured height and remains on the
        // row's center line even while the move-button slot is entering/leaving.
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Box(
            modifier = Modifier
                .width(moveButtonsSlotWidth)
                .requiredHeight(SOURCE_MOVE_BUTTON_SIZE)
                .graphicsLayer { clip = true },
        ) {
            Row(
                modifier = Modifier
                    .requiredWidth(SOURCE_MOVE_BUTTONS_WIDTH)
                    .requiredHeight(SOURCE_MOVE_BUTTON_SIZE)
                    .graphicsLayer {
                        alpha = sortingProgress
                        translationX = sourceControlsSlideDistancePx *
                            (1f - sortingProgress)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(SOURCE_SWITCH_TO_MOVE_BUTTON_GAP))
                SourceMoveButton(
                    moveUp = true,
                    enabled = sortingVisible && canMoveUp,
                    onClick = onMoveUp,
                )
                SourceMoveButton(
                    moveUp = false,
                    enabled = sortingVisible && canMoveDown,
                    onClick = onMoveDown,
                    modifier = Modifier.offset(x = SOURCE_DOWN_BUTTON_END_OFFSET),
                )
            }
        }
    }
}

/** 单个排序按钮复用 Chevron 图标，通过旋转表达上移或下移。 */
@Composable
private fun SourceMoveButton(
    moveUp: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(SOURCE_MOVE_BUTTON_SIZE),
    ) {
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = stringResource(
                if (moveUp) R.string.action_move_source_up else R.string.action_move_source_down,
            ),
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurfaceVariantActions
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
            modifier = Modifier
                .size(20.dp)
                .rotate(if (moveUp) -90f else 90f),
        )
    }
}

/** 左侧圆形序号使用成对的主题表面色，深色模式会自动反转明暗关系。 */
@Composable
private fun SourcePriorityBadge(priority: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(SOURCE_PRIORITY_BADGE_SIZE)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Text(
            text = priority.toString(),
            color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AppSwitchPreference(
    app: TranslationApp,
    icon: Drawable?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = app.displayName,
        summary = stringResource(app.summaryRes),
        checked = checked,
        onCheckedChange = onCheckedChange,
        startAction = {
            Row {
                AppIcon(icon)
                Spacer(modifier = Modifier.width(APP_ICON_TO_NAME_GAP_ADJUSTMENT))
            }
        },
    )
}

@Composable
private fun AppIcon(icon: Drawable?) {
    Box(modifier = Modifier.size(40.dp)) {
        if (icon != null) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(icon)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = MiuixIcons.Music,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun Source.displayName(): String = when (this) {
    Source.NE -> stringResource(R.string.source_netease_music)
    Source.QM -> stringResource(R.string.source_qq_music)
    Source.KUWO -> stringResource(R.string.source_kuwo_music)
    Source.KUGOU -> stringResource(R.string.source_kugou_music)
}

private data class TranslationApp(
    val packageName: String,
    val displayName: String,
    val summaryRes: Int,
)

private data class InstalledTranslationApp(val app: TranslationApp, val icon: Drawable?)

private data class SourceSwap(
    val source: Source,
    val adjacentSource: Source,
    val direction: Int,
)

private val ENABLED_APPS = listOf(
    TranslationApp(
        OnlineTranslationSourcePreferences.APPLE_MUSIC_PACKAGE,
        "Apple Music",
        R.string.summary_online_translation_app_lyrics_translation,
    ),
    TranslationApp(
        OnlineTranslationSourcePreferences.QISHUI_PACKAGE,
        "汽水音乐",
        R.string.summary_online_translation_app_translation,
    ),
    TranslationApp(
        OnlineTranslationSourcePreferences.SPOTIFY_PACKAGE,
        "Spotify",
        R.string.summary_online_translation_app_translation,
    ),
    TranslationApp(
        OnlineTranslationSourcePreferences.SALT_PACKAGE,
        "椒盐音乐",
        R.string.summary_online_translation_app_lyrics_translation,
    ),
)

private val SOURCE_ROW_HEIGHT = 64.dp
private val SOURCE_ROW_HORIZONTAL_PADDING = 16.dp
private val SOURCE_ROW_END_PADDING = 12.dp
private val SOURCE_PRIORITY_BADGE_SIZE = 30.dp
private val SOURCE_PRIORITY_TO_NAME_GAP = 16.dp
private val SOURCE_MOVE_BUTTON_SIZE = 44.dp
private val SOURCE_SWITCH_TO_MOVE_BUTTON_GAP = 12.dp
private val SOURCE_DOWN_BUTTON_END_OFFSET = 4.dp
private val SOURCE_MOVE_BUTTONS_WIDTH = SOURCE_SWITCH_TO_MOVE_BUTTON_GAP +
    SOURCE_MOVE_BUTTON_SIZE * 2 + SOURCE_DOWN_BUTTON_END_OFFSET
private val APP_ICON_TO_NAME_GAP_ADJUSTMENT = 8.dp
private const val SOURCE_SWAP_ANIMATION_DURATION_MS = 220
private const val SOURCE_CONTROLS_ANIMATION_DURATION_MS = 320
private val SOURCE_CONTROLS_SLIDE_DISTANCE = 8.dp
