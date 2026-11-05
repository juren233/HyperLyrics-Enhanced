package com.juren233.hyperlyricsenhanced.ui.page

import com.juren233.hyperlyricsenhanced.ui.skin.SkinListPage
import com.juren233.hyperlyricsenhanced.ui.skin.ArrowPreference
import com.juren233.hyperlyricsenhanced.ui.skin.Card
import com.juren233.hyperlyricsenhanced.ui.skin.Icon
import com.juren233.hyperlyricsenhanced.ui.skin.IconButton
import com.juren233.hyperlyricsenhanced.ui.skin.OverlayDropdownPreference
import com.juren233.hyperlyricsenhanced.ui.skin.SkinIcons
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.SmallTitle
import com.juren233.hyperlyricsenhanced.ui.skin.SwitchPreference
import android.content.Context
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route

@Composable
fun HookSettingsPage() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
        val lazyListState = rememberLazyListState()
    SkinListPage(
        title = stringResource(R.string.title_hyper_island_lyrics),
        onBack = { navigator.pop() },
        listState = lazyListState,
    ) {
                hookSettingsSections()
    }
}

private fun LazyListScope.hookSettingsSections() {
    item(key = "lyric_mode") {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
        var lyricMode by remember { mutableIntStateOf(prefs.getInt(RootConstants.KEY_HOOK_LYRIC_MODE, RootConstants.DEFAULT_HOOK_LYRIC_MODE)) }
        val lyricModeOptions = listOf(
            stringResource(R.string.lyric_mode_single_side),
            stringResource(R.string.lyric_mode_full_island),
            stringResource(R.string.lyric_mode_separated)
        )
        Card(modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
            OverlayDropdownPreference(
                title = stringResource(R.string.title_lyric_mode),
                items = lyricModeOptions,
                selectedIndex = lyricMode,
                    onSelectedIndexChange = { index ->
                    lyricMode = index
                    prefs.edit { putInt(RootConstants.KEY_HOOK_LYRIC_MODE, index) }
                    PrefsBridge.putInt(RootConstants.KEY_HOOK_LYRIC_MODE, index)
                }
            )
        }
    }
    item(key = "custom_config_title") {
        SmallTitle(text = stringResource(R.string.title_custom_config))
    }
    item(key = "custom_config_content") {
        val navigator = LocalNavigator.current
        Card(modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
            Column {
                ArrowPreference(title = stringResource(R.string.title_hyper_island), onClick = { navigator.navigate(Route.HyperIslandSettings) })
                ArrowPreference(title = stringResource(R.string.title_text), onClick = { navigator.navigate(Route.LyricDisplay) })
                ArrowPreference(title = stringResource(R.string.title_marquee), onClick = { navigator.navigate(Route.LyricScroll) })
                ArrowPreference(title = stringResource(R.string.title_verbatim_lyric), onClick = { navigator.navigate(Route.VerbatimLyric) })
                ArrowPreference(title = stringResource(R.string.title_translation), onClick = { navigator.navigate(Route.LyricTranslation) })
                ArrowPreference(title = stringResource(R.string.title_lyric_anim), onClick = { navigator.navigate(Route.LyricAnimation) })
            }
        }
    }
}
