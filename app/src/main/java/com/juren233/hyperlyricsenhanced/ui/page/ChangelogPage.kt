@file:OptIn(ExperimentalScrollBarApi::class)

package com.juren233.hyperlyricsenhanced.ui.page

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.SuperComponent
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBar
import com.juren233.hyperlyricsenhanced.ui.utils.pageScrollModifiers
import com.juren233.hyperlyricsenhanced.ui.utils.rememberBlurBackdrop
import com.juren233.hyperlyricsenhanced.utils.ChangelogData
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference

private sealed interface ChangelogUiState {
    data object Loading : ChangelogUiState
    data class Loaded(val items: List<com.juren233.hyperlyricsenhanced.utils.ChangelogItem>) : ChangelogUiState
    data object Error : ChangelogUiState
}

@Composable
fun ChangelogPage() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    var reloadKey by remember { mutableIntStateOf(0) }
    var uiState by remember { mutableStateOf<ChangelogUiState>(ChangelogUiState.Loading) }

    LaunchedEffect(reloadKey) {
        uiState = ChangelogUiState.Loading
        uiState = try {
            ChangelogUiState.Loaded(
                ChangelogData.fetchChangelog(
                    currentVersionName = BuildConfig.VERSION_NAME
                )
            )
        } catch (_: Exception) {
            ChangelogUiState.Error
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.title_changelog),
                    scrollBehavior = topAppBarScrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.back))
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        val lazyListState = rememberLazyListState()
        val top = innerPadding.calculateTopPadding()
        val bottom = innerPadding.calculateBottomPadding()
        val contentPadding = remember(top, bottom) {
            PaddingValues(
                top = top,
                start = 0.dp,
                end = 0.dp,
                bottom = bottom + 20.dp
            )
        }
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.pageScrollModifiers(
                    enableScrollEndHaptic = true,
                    showTopAppBar = true,
                    topAppBarScrollBehavior = topAppBarScrollBehavior
                ),
                contentPadding = contentPadding,
            ) {
                changelogPageSections(
                    state = uiState,
                    onRetry = { reloadKey++ },
                    onOpenOriginalRepository = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                ChangelogData.ORIGINAL_REPOSITORY_URL.toUri()
                            )
                        )
                    }
                )
            }
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(lazyListState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                trackPadding = contentPadding,
            )
        }
    }
}

private fun LazyListScope.changelogPageSections(
    state: ChangelogUiState,
    onRetry: () -> Unit,
    onOpenOriginalRepository: () -> Unit
) {
    when (state) {
        ChangelogUiState.Loading -> {
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        ChangelogUiState.Error -> {
            item(key = "load_error") {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.changelog_load_failed),
                        summary = stringResource(R.string.changelog_retry),
                        onClick = onRetry
                    )
                }
            }
        }
        is ChangelogUiState.Loaded -> {
            if (state.items.isEmpty()) {
                item(key = "empty") {
                    SuperComponent(
                        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth(),
                        title = "",
                        summary = stringResource(R.string.changelog_empty)
                    )
                }
            } else {
                state.items.forEachIndexed { index, item ->
                    if (item.version.isNotBlank()) {
                        item(key = "version_${item.version}_$index") {
                            SmallTitle(text = item.version)
                        }
                    }
                    item(key = "content_${item.version}_${item.title}_$index") {
                        SuperComponent(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth(),
                            title = item.title,
                            summary = item.summary
                        )
                    }
                }
            }
        }
    }

    item(key = "original_repository_banner") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(R.string.changelog_original_repository),
                onClick = onOpenOriginalRepository
            )
        }
    }
}
