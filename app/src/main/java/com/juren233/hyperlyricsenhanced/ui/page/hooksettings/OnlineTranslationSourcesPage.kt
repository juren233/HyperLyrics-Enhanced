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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
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
    val listState = rememberLazyListState()
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

    XposedLyricSettingPage(
        title = stringResource(R.string.title_online_translation_sources),
        listState = listState,
    ) {
        item(key = "platform_sources_title") {
            SmallTitle(text = stringResource(R.string.title_online_translation_platform_sources))
        }
        item(key = "platform_sources") {
            OnlineSourceOrderList(
                order = sourceOrder.toList(),
                enabledSources = sourceEnabled.filterValues { it }.keys,
                sortingVisible = !autoSelectBestSource,
                listState = listState,
                onOrderChange = { order ->
                    sourceOrder.clear()
                    sourceOrder.addAll(order)
                    saveConfig(
                        RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
                        OnlineTranslationSourcePreferences.serializeOrder(order),
                    )
                },
                onCheckedChange = { source, checked ->
                    if (checked || OnlineTranslationSourcePreferences.canToggleSource(
                            source, sourceEnabled.filterValues { it }.keys,
                        )
                    ) {
                        sourceEnabled[source] = checked
                        saveConfig(
                            OnlineTranslationSourcePreferences.sourcePreferenceKey(source),
                            checked,
                        )
                    }
                },
            )
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

private data class TranslationApp(
    val packageName: String,
    val displayName: String,
    val summaryRes: Int,
)

private data class InstalledTranslationApp(val app: TranslationApp, val icon: Drawable?)

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

private val APP_ICON_TO_NAME_GAP_ADJUSTMENT = 8.dp
