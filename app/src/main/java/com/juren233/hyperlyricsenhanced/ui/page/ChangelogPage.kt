@file:OptIn(ExperimentalScrollBarApi::class)

package com.juren233.hyperlyricsenhanced.ui.page

import com.juren233.hyperlyricsenhanced.ui.skin.SkinListPage
import com.juren233.hyperlyricsenhanced.ui.skin.ArrowPreference
import com.juren233.hyperlyricsenhanced.ui.skin.Card
import com.juren233.hyperlyricsenhanced.ui.skin.Icon
import com.juren233.hyperlyricsenhanced.ui.skin.IconButton
import com.juren233.hyperlyricsenhanced.ui.skin.SkinIcons
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
import com.juren233.hyperlyricsenhanced.ui.skin.SmallTitle
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.component.MarkdownText
import com.juren233.hyperlyricsenhanced.ui.component.SuperComponent
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.utils.ChangelogData
import com.juren233.hyperlyricsenhanced.ui.skin.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

private sealed interface ChangelogUiState {
    data object Loading : ChangelogUiState
    data class Loaded(val items: List<com.juren233.hyperlyricsenhanced.utils.ChangelogItem>) : ChangelogUiState
    data object Error : ChangelogUiState
}

@Composable
fun ChangelogPage() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
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

        val lazyListState = rememberLazyListState()
    SkinListPage(
        title = stringResource(R.string.title_changelog),
        onBack = { navigator.pop() },
        listState = lazyListState,
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
                        Card(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth(),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (item.title.isNotBlank()) {
                                    top.yukonga.miuix.kmp.basic.Text(
                                        text = item.title,
                                        fontSize = SkinTheme.textStyles.headline1.fontSize,
                                        fontWeight = FontWeight.Medium,
                                        color = SkinTheme.colors.onBackground,
                                    )
                                }
                                if (item.title.isNotBlank() && item.summary.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                }
                                if (item.summary.isNotBlank()) {
                                    MarkdownText(
                                        markdown = item.summary,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
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
