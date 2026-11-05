/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.component

import com.juren233.hyperlyricsenhanced.ui.skin.Icon
import com.juren233.hyperlyricsenhanced.ui.skin.IconButton
import com.juren233.hyperlyricsenhanced.ui.skin.SkinIcons
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R

/** 顶栏左侧的应用设置入口。 */
@Composable
fun AppSettingsIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon = SkinIcons.Settings,
            contentDescription = stringResource(R.string.title_app_settings),
            modifier = Modifier.size(26.dp),
        )
    }
}
