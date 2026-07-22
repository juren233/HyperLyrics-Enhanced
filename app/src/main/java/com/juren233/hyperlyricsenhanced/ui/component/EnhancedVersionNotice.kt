package com.juren233.hyperlyricsenhanced.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juren233.hyperlyricsenhanced.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun EnhancedVersionNotice(modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Text(
            text = stringResource(R.string.enhanced_version_notice),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp)
        )
    }
}
