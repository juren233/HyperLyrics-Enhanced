package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** 「启用App」卡片末尾的入口；应用名单即开关，没有另设总开关。 */
@Composable
internal fun MediaSessionOnlineFallbackEntry() {
    val prefs = rememberHookPrefs()
    val navigator = LocalNavigator.current
    var packages by remember { mutableStateOf(MediaSessionOnlineFallbackPreferences.read(prefs).packages) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (MediaSessionOnlineFallbackPreferences.isPreference(key)) {
                packages = MediaSessionOnlineFallbackPreferences.read(prefs).packages
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    ArrowPreference(
        title = stringResource(R.string.media_session_fallback_more_apps),
        summary = if (packages.isNotEmpty()) {
            stringResource(R.string.media_session_fallback_players_count, packages.size)
        } else null,
        onClick = { navigator.navigate(Route.MediaSessionOnlineFallbackPlayers) },
    )
}

@Composable
internal fun MediaSessionOnlineFallbackPlayersPage() {
    val prefs = rememberHookPrefs()
    var packages by remember { mutableStateOf(MediaSessionOnlineFallbackPreferences.read(prefs).packages) }
    InstalledAppSelectionPage(
        title = stringResource(R.string.media_session_fallback_more_apps),
        enabledPackages = packages,
        includeNonLauncherApps = true,
        onCheckedChange = { packageName, checked ->
            packages = if (checked) packages + packageName else packages - packageName
            prefs.edit { putStringSet(MediaSessionOnlineFallbackPreferences.PACKAGES, packages) }
            PrefsBridge.putStringSet(MediaSessionOnlineFallbackPreferences.PACKAGES, packages)
        },
    )
}
