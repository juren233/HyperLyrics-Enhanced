@file:OptIn(ExperimentalScrollBarApi::class)

package com.juren233.hyperlyricsenhanced.ui.page

import com.juren233.hyperlyricsenhanced.ui.skin.SkinListPage
import com.juren233.hyperlyricsenhanced.ui.skin.ArrowPreference
import com.juren233.hyperlyricsenhanced.ui.skin.Card
import com.juren233.hyperlyricsenhanced.ui.skin.Icon
import com.juren233.hyperlyricsenhanced.ui.skin.IconButton
import com.juren233.hyperlyricsenhanced.ui.skin.SkinIcons
import com.juren233.hyperlyricsenhanced.ui.skin.SkinTheme
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.utils.LicenseProvider
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

data class LicenseItem(
    val name: String,
    val author: String,
    val url: String
)

@Composable
fun LicensesPage() {
    val navigator = LocalNavigator.current
        val lazyListState = rememberLazyListState()
    SkinListPage(
        title = stringResource(R.string.title_licenses),
        onBack = { navigator.pop() },
        listState = lazyListState,
    ) {
                licensesPageSections()
    }
}

private fun LazyListScope.licensesPageSections() {
    item(key = "licenses_card") {
        val context = LocalContext.current
        val licenses = remember { LicenseProvider.getLicenses() }
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
            Column {
                licenses.forEach { license ->
                    ArrowPreference(
                        title = license.name,
                        summary = license.author,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, license.url.toUri())
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}
