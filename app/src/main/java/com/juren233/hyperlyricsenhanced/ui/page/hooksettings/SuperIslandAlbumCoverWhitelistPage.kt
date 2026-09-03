/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.IslandAlbumCoverWhitelist
import com.juren233.hyperlyricsenhanced.common.IslandMusicAppCatalog
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

private const val GET_INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
private const val MIUI_SECURITY_PACKAGE = "com.lbe.security.miui"
private const val APP_ICON_BITMAP_SIZE_PX = 96

@Composable
fun SuperIslandAlbumCoverWhitelistPage() {
    val context = LocalContext.current
    val prefs = rememberHookPrefs()
    val installedAppsPermission = remember(context) {
        resolveInstalledAppsPermission(context)
    }
    var hasInstalledAppsPermission by remember(context, installedAppsPermission) {
        mutableStateOf(
            installedAppsPermission == null ||
                ContextCompat.checkSelfPermission(
                    context,
                    installedAppsPermission,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var enabledPackages by remember {
        mutableStateOf(IslandAlbumCoverWhitelist.readEnabledPackages(prefs))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasInstalledAppsPermission = granted ||
            installedAppsPermission == null ||
            ContextCompat.checkSelfPermission(
                context,
                installedAppsPermission,
            ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(installedAppsPermission) {
        val permission = installedAppsPermission ?: return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permission)
        }
    }

    LaunchedEffect(context, hasInstalledAppsPermission) {
        installedApps = if (hasInstalledAppsPermission) {
            withContext(Dispatchers.IO) {
                loadInstalledApps(context)
            }
        } else {
            emptyList()
        }
    }

    fun updateAppEnabled(packageName: String, enabled: Boolean) {
        val updated = IslandAlbumCoverWhitelist.updateEnabledPackages(
            current = prefs.getStringSet(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST, null),
            packageName = packageName,
            enabled = enabled,
        )
        enabledPackages = updated
        prefs.edit { putStringSet(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST, updated) }
        PrefsBridge.putStringSet(RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST, updated)
    }

    val filteredApps = installedApps.orEmpty().filter { app ->
        val query = searchQuery.trim()
        query.isEmpty() || app.app.displayName.contains(query, ignoreCase = true) ||
            app.app.packageName.contains(query, ignoreCase = true)
    }
    val enabledApps = filteredApps.filter { it.app.packageName in enabledPackages }
    val disabledApps = filteredApps.filter { it.app.packageName !in enabledPackages }

    XposedLyricSettingPage(title = stringResource(R.string.title_island_album_cover_app_whitelist)) {
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
            onCheckedChange = ::updateAppEnabled,
        )
        appSection(
            keyPrefix = "disabled",
            titleRes = R.string.title_island_album_cover_whitelist_disabled,
            apps = disabledApps,
            enabledPackages = enabledPackages,
            isLoading = installedApps == null,
            onCheckedChange = ::updateAppEnabled,
        )
    }
}

private fun resolveInstalledAppsPermission(context: Context): String? =
    runCatching {
        context.packageManager
            .getPermissionInfo(GET_INSTALLED_APPS_PERMISSION, 0)
            .takeIf { it.packageName == MIUI_SECURITY_PACKAGE }
            ?.name
    }.getOrNull()

@Composable
private fun AppSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
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
        label = stringResource(R.string.hint_island_album_cover_whitelist_search),
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
            val shape = when {
                apps.size == 1 -> RoundedCornerShape(16.dp)
                index == 0 -> RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                )
                index == apps.lastIndex -> RoundedCornerShape(
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
                )
                else -> RoundedCornerShape(0.dp)
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = if (index == apps.lastIndex) 12.dp else 0.dp)
                    .fillMaxWidth()
                    .background(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        shape = shape,
                    ),
            ) {
                IslandMusicAppSwitchPreference(
                    app = installedApp.app,
                    icon = installedApp.icon,
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
    icon: Bitmap?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = app.displayName,
        checked = checked,
        onCheckedChange = onCheckedChange,
        startAction = {
            Row {
                AppIcon(icon)
                Spacer(modifier = Modifier.width(8.dp))
            }
        },
    )
}

@Composable
private fun AppIcon(icon: Bitmap?) {
    Box(modifier = Modifier.size(40.dp)) {
        if (icon != null) {
            Image(
                bitmap = remember(icon) { icon.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Fit,
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

private data class InstalledApp(
    val app: IslandMusicAppCatalog.App,
    val icon: Bitmap?,
)

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    val knownApps = IslandMusicAppCatalog.apps.associateBy { it.packageName }
    val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    return packageManager
        .queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
        .asSequence()
        .mapNotNull { it.activityInfo?.applicationInfo }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .map { applicationInfo ->
            val app = knownApps[applicationInfo.packageName] ?: IslandMusicAppCatalog.App(
                packageName = applicationInfo.packageName,
                displayName = applicationInfo.loadLabel(packageManager)
                    .toString()
                    .takeUnless(String::isBlank)
                    ?: applicationInfo.packageName,
            )
            InstalledApp(
                app = app,
                icon = runCatching {
                    applicationInfo.loadIcon(packageManager).toBitmap(
                        width = APP_ICON_BITMAP_SIZE_PX,
                        height = APP_ICON_BITMAP_SIZE_PX,
                        config = Bitmap.Config.ARGB_8888,
                    )
                }.getOrNull(),
            )
        }
        .sortedWith(
            Comparator { left, right ->
                val nameComparison = left.app.displayName.compareTo(
                    right.app.displayName,
                    ignoreCase = true,
                )
                if (nameComparison != 0) {
                    nameComparison
                } else {
                    left.app.packageName.compareTo(right.app.packageName)
                }
            },
        )
        .toList()
}
