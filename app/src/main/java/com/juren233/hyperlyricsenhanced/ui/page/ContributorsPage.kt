@file:OptIn(ExperimentalScrollBarApi::class)

package com.juren233.hyperlyricsenhanced.ui.page

import com.juren233.hyperlyricsenhanced.ui.skin.SkinListPage
import com.juren233.hyperlyricsenhanced.ui.skin.ArrowPreference
import com.juren233.hyperlyricsenhanced.ui.skin.BasicComponent
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.utils.ContributorItem
import com.juren233.hyperlyricsenhanced.ui.utils.ContributorsProvider
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale


@Composable
fun ContributorsPage() {
    val navigator = LocalNavigator.current
        val lazyListState = rememberLazyListState()
    SkinListPage(
        title = stringResource(R.string.title_contributors),
        onBack = { navigator.pop() },
        listState = lazyListState,
    ) {
                contributorsPageSections()
    }
}

private fun LazyListScope.contributorsPageSections() {
    item(key = "contributors_intro_card") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            BasicComponent(
                summary = stringResource(R.string.summary_contributors)
            )
        }
    }
    item(key = "maintainer_title") {
        SmallTitle(text = stringResource(R.string.title_main_maintainer))
    }
    item(key = "maintainer_card") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                ContributorEntry(contributor = ContributorsProvider.maintainer)
            }
        }
    }
    if (ContributorsProvider.developmentContributors.isNotEmpty()) {
        item(key = "development_contributors_title") {
            SmallTitle(text = stringResource(R.string.title_development_contributors))
        }
        item(key = "development_contributors_card") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                Column {
                    ContributorsProvider.developmentContributors.forEach { contributor ->
                        ContributorEntry(contributor = contributor)
                    }
                }
            }
        }
    }
    item(key = "original_contributors_title") {
        SmallTitle(text = stringResource(R.string.title_original_hyperlyric_contributors))
    }
    item(key = "original_acknowledgments_card") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            BasicComponent(
                summary = stringResource(R.string.summary_acknowledgments)
            )
        }
    }
    item(key = "original_contributors_card") {
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                ContributorsProvider.originalHyperLyricContributors.forEach { contributor ->
                    ContributorEntry(contributor = contributor)
                }
            }
        }
    }
}

@Composable
private fun ContributorEntry(contributor: ContributorItem) {
    val context = LocalContext.current
    ArrowPreference(
        title = contributor.name,
        summary = contributor.summary.ifEmpty { null },
        startAction = {
            Image(
                painter = painterResource(id = contributor.avatarRes),
                contentDescription = contributor.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SkinTheme.colors.primaryContainer)
            )
        },
        onClick = {
            if (contributor.githubUrl.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW, contributor.githubUrl.toUri())
                context.startActivity(intent)
            }
        }
    )
}
