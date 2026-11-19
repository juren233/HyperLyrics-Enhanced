/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusProperties
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.ui.component.lazyCardRow
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.InstalledAppsPermission
import com.juren233.hyperlyricsenhanced.common.IslandAlbumCoverWhitelist
import com.juren233.hyperlyricsenhanced.common.IslandMusicAppCatalog
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.StringSetPreferences
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HyperIslandAlbumCoverWhitelistPage() {
    val prefs = rememberHookPrefs()
    var enabledPackages by remember {
        mutableStateOf(IslandAlbumCoverWhitelist.readEnabledPackages(prefs))
    }
    fun updateAppEnabled(packageName: String, enabled: Boolean) {
        val updated = IslandAlbumCoverWhitelist.updateEnabledPackages(
            current = StringSetPreferences.read(prefs, RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST, null),
            packageName = packageName,
            enabled = enabled,
        )
        enabledPackages = updated
        prefs.edit { putStringSet(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST, updated) }
        PrefsBridge.putStringSet(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST, updated)
    }

    InstalledAppSelectionPage(
        title = stringResource(R.string.title_island_album_cover_app_whitelist),
        enabledPackages = enabledPackages,
        onCheckedChange = ::updateAppEnabled,
    )
}

/** 复用封面白名单的搜索、分组卡片与开关样式。 */
@Composable
internal fun InstalledAppSelectionPage(
    title: String,
    enabledPackages: Set<String>,
    onCheckedChange: (String, Boolean) -> Unit,
    includeNonLauncherApps: Boolean = false,
    /** 已在别处单独外显的包：不出现在本页列表里，其开关状态不受影响。 */
    excludedPackages: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    val installedAppsPermission = remember(context) {
        InstalledAppsPermission.resolve(context)
    }
    var hasInstalledAppsPermission by remember(context, installedAppsPermission) {
        mutableStateOf(
            InstalledAppsPermission.isGranted(context, installedAppsPermission),
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember(includeNonLauncherApps) {
        mutableStateOf(
            InstalledAppRepository.cachedApps(includeNonLauncherApps)
                ?.takeIf { hasInstalledAppsPermission }
                ?.map { InstalledApp(it, installed = true) },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasInstalledAppsPermission = granted ||
            InstalledAppsPermission.isGranted(context, installedAppsPermission)
    }

    LaunchedEffect(installedAppsPermission) {
        val permission = installedAppsPermission ?: return@LaunchedEffect
        if (!InstalledAppsPermission.isGranted(context, permission)) {
            permissionLauncher.launch(permission)
        }
    }

    LaunchedEffect(context, hasInstalledAppsPermission, includeNonLauncherApps) {
        installedApps = if (hasInstalledAppsPermission) {
            InstalledAppRepository.loadApps(context, includeNonLauncherApps)
                .map { InstalledApp(it, installed = true) }
        } else {
            emptyList()
        }
    }
    val knownApps = installedApps.orEmpty().filter { it.app.packageName !in excludedPackages }
    // 已卸载或暂时不可见的包仍可从已启用组移除；列表读完后才补，加载中统一显示「加载中」。
    val apps = if (includeNonLauncherApps && installedApps != null) {
        knownApps + enabledPackages
            .filter { pkg -> pkg !in excludedPackages && knownApps.none { it.app.packageName == pkg } }
            .map { pkg -> InstalledApp(IslandMusicAppCatalog.App(pkg, pkg), installed = false) }
    } else knownApps
    val filteredApps = apps.filter { app ->
        val query = searchQuery.trim()
        query.isEmpty() || app.app.displayName.contains(query, ignoreCase = true) ||
            app.app.packageName.contains(query, ignoreCase = true)
    }
    val enabledApps = filteredApps.filter { it.app.packageName in enabledPackages }
    val disabledApps = filteredApps.filter { it.app.packageName !in enabledPackages }

    XposedLyricSettingPage(title = title) {
        item(key = "search") {
            AppSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
            )
        }
        appSection(
            keyPrefix = "enabled",
            titleRes = R.string.title_island_album_cover_whitelist_enabled,
            apps = enabledApps,
            enabledPackages = enabledPackages,
            isLoading = installedApps == null,
            onCheckedChange = onCheckedChange,
        )
        appSection(
            keyPrefix = "disabled",
            titleRes = R.string.title_island_album_cover_whitelist_disabled,
            apps = disabledApps,
            enabledPackages = enabledPackages,
            isLoading = installedApps == null,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun AppSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String = stringResource(R.string.hint_island_album_cover_whitelist_search),
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    var allowSearchFocus by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Miuix InputField requests focus whenever expanded=true. Keep it both
        // collapsed and non-focusable during initial focus traversal, then allow
        // the user's tap to expand and focus it normally.
        focusManager.clearFocus(force = true)
        withFrameNanos { }
        allowSearchFocus = true
    }

    InputField(
        query = query,
        onQueryChange = onQueryChange,
        label = label,
        leadingIcon = {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = stringResource(R.string.search),
                modifier = Modifier
                    .size(44.dp)
                    .padding(start = 16.dp, end = 8.dp),
                tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = MiuixIcons.Basic.SearchCleanup,
                    contentDescription = stringResource(R.string.search),
                    modifier = Modifier
                        .size(44.dp)
                        .padding(start = 8.dp, end = 16.dp)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = { onQueryChange("") },
                        ),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .focusProperties {
                canFocus = allowSearchFocus
            },
        onSearch = {},
        expanded = expanded,
        onExpandedChange = { expanded = it },
    )
}

private fun LazyListScope.appSection(
    keyPrefix: String,
    titleRes: Int,
    apps: List<InstalledApp>,
    enabledPackages: Set<String>,
    isLoading: Boolean,
    onCheckedChange: (String, Boolean) -> Unit,
) {
    item(key = "${keyPrefix}_title") {
        SmallTitle(text = stringResource(titleRes))
    }
    if (apps.isEmpty()) {
        item(key = "${keyPrefix}_empty") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (isLoading) {
                                R.string.title_island_album_cover_whitelist_loading
                            } else {
                                R.string.title_island_album_cover_whitelist_no_apps
                            },
                        ),
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }
    } else {
        // Keep rows as independent lazy items for smooth scrolling, but paint the
        // section background continuously so each section is visually one card.
        itemsIndexed(
            items = apps,
            key = { _, installedApp ->
                "${keyPrefix}_${installedApp.app.packageName}"
            },
            contentType = { _, _ -> "island_music_app" },
        ) { index, installedApp ->
            Box(modifier = Modifier.lazyCardRow(index, apps.size)) {
                IslandMusicAppSwitchPreference(
                    app = installedApp.app,
                    installed = installedApp.installed,
                    checked = installedApp.app.packageName in enabledPackages,
                    onCheckedChange = { checked ->
                        onCheckedChange(installedApp.app.packageName, checked)
                    },
                )
            }
        }
    }
}

@Composable
private fun IslandMusicAppSwitchPreference(
    app: IslandMusicAppCatalog.App,
    installed: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = app.displayName,
        // 读不到应用名时名称即包名，不再重复显示。
        summary = app.packageName.takeIf { it != app.displayName },
        checked = checked,
        onCheckedChange = onCheckedChange,
        startAction = {
            Row {
                AppIcon(app.packageName.takeIf { installed })
                Spacer(modifier = Modifier.width(8.dp))
            }
        },
    )
}

/** 图标在行可见时才加载；加载中留空，读不到时显示音乐图标。 */
@Composable
private fun AppIcon(packageName: String?) {
    val context = LocalContext.current
    // null：加载中；Result 里的 null：读不到图标。按包名重置：同一行可能先以未安装显示，列表读完后才有包名。
    var icon by remember(packageName) {
        mutableStateOf(
            when {
                packageName == null -> Result.success(null)
                else -> InstalledAppRepository.cachedIcon(packageName)?.let { Result.success(it) }
                    ?: Result.success<Bitmap?>(null).takeIf { InstalledAppRepository.isIconMissing(packageName) }
            },
        )
    }
    LaunchedEffect(packageName) {
        if (icon == null && packageName != null) {
            icon = Result.success(InstalledAppRepository.loadIcon(context.applicationContext, packageName))
        }
    }
    val loaded = icon
    Box(modifier = Modifier.size(40.dp)) {
        val bitmap = loaded?.getOrNull()
        if (bitmap != null) {
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (loaded != null) {
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

/** [installed] 为 false：名单里有、但已卸载或当前看不到的包。 */
private data class InstalledApp(
    val app: IslandMusicAppCatalog.App,
    val installed: Boolean,
)
