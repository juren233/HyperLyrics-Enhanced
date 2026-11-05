package com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.skin.SkinListPage

@Composable
internal fun rememberHookPrefs(): SharedPreferences {
    val context = LocalContext.current
    return remember(context) {
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
    }
}

@Composable
internal fun rememberHookConfigSaver(prefs: SharedPreferences): (String, Any) -> Unit {
    return remember(prefs) {
        { key: String, value: Any ->
            prefs.edit {
                when (value) {
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                }
            }
            when (value) {
                is Int -> PrefsBridge.putInt(key, value)
                is Boolean -> PrefsBridge.putBoolean(key, value)
                is Float -> PrefsBridge.putFloat(key, value)
                is String -> PrefsBridge.putString(key, value)
            }
        }
    }
}

@Composable
internal fun XposedLyricSettingPage(
    title: String,
    subtitle: String = "",
    outerPadding: PaddingValues = PaddingValues(),
    showNavigationIcon: Boolean = true,
    collapsedTitle: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    val navigator = LocalNavigator.current
    SkinListPage(
        title = title,
        subtitle = subtitle,
        collapsedTitle = collapsedTitle,
        outerPadding = outerPadding,
        onBack = if (showNavigationIcon) ({ navigator.pop() }) else null,
        leadingContent = leadingContent,
        trailingContent = trailingContent?.let { trailing -> { trailing() } },
        listState = listState,
        content = content,
    )
}
