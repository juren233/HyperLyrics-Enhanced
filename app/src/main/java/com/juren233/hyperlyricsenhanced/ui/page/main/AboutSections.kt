package com.juren233.hyperlyricsenhanced.ui.page.main

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.juren233.hyperlyricsenhanced.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 组装关于页的品牌头部、设备信息和功能入口。 */
fun LazyListScope.aboutPageSections(
    aboutAppVersion: String?,
    availableUpdateVersion: String?,
    aboutDeviceName: String,
    aboutDeviceModel: String,
    aboutOsVersion: String,
    aboutAndroidVersion: String,
    headerVersionAlpha: Float,
    headerVersionScale: Float,
    cardSurfaceRestoreProgress: Float,
    onHelpClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onContributorsClick: () -> Unit,
) {
    item(key = "about_header") {
        val version = aboutAppVersion ?: stringResource(R.string.version_unknown)
        val secondaryColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.66f)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(478.dp)
                .padding(top = 382.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = headerVersionAlpha
                        scaleX = headerVersionScale
                        scaleY = headerVersionScale
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = version,
                    fontSize = 14.sp,
                    color = secondaryColor,
                )
                if (availableUpdateVersion != null) {
                    Text(
                        text = stringResource(
                            R.string.module_update_available_version,
                            availableUpdateVersion,
                        ),
                        fontSize = 13.sp,
                        color = secondaryColor,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    item(key = "system_info_content") {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth(),
            colors = aboutCardColors(cardSurfaceRestoreProgress),
        ) {
            Column {
                Text(
                    text = aboutDeviceName,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(
                        start = 16.dp,
                        top = 18.dp,
                        end = 16.dp,
                        bottom = 8.dp,
                    ),
                )
                BasicComponent(title = aboutDeviceModel, summary = stringResource(R.string.info_device_model))
                BasicComponent(title = aboutAndroidVersion, summary = stringResource(R.string.info_android_version))
                BasicComponent(title = aboutOsVersion, summary = stringResource(R.string.info_os_version))
            }
        }
    }

    item(key = "help_title") {
        SmallTitle(
            text = stringResource(R.string.title_help)
        )
    }

    item(key = "help_content") {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth(),
            colors = aboutCardColors(cardSurfaceRestoreProgress),
        ) {
            Column {
                ArrowPreference(
                    title = stringResource(R.string.title_help),
                    onClick = onHelpClick,
                )
                ArrowPreference(
                    title = stringResource(R.string.title_changelog),
                    onClick = onChangelogClick,
                )
            }
        }
    }

    item(key = "developer_content") {
        val context = LocalContext.current
        Card(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth(),
            colors = aboutCardColors(cardSurfaceRestoreProgress),
        ) {

            ArrowPreference(
                title = stringResource(R.string.title_contributors),
                onClick = onContributorsClick,
            )
            ArrowPreference(
                title = stringResource(R.string.title_project),
                onClick = {
                    val uri = "https://github.com/juren233/HyperLyrics-Enhanced".toUri()
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    context.startActivity(intent)
                }
            )
            ArrowPreference(
                title = stringResource(R.string.title_licenses),
                onClick = onLicensesClick,
            )
        }
    }
}

/** 流光完整显示时透出背景，背景淡出后恢复本项目正常卡片色。 */
@Composable
private fun aboutCardColors(surfaceRestoreProgress: Float): CardColors {
    val color = MiuixTheme.colorScheme.surfaceContainer.copy(
        alpha = aboutCardContainerAlpha(surfaceRestoreProgress),
    )
    return CardDefaults.defaultColors(
        color = color,
    )
}

internal fun aboutCardContainerAlpha(surfaceRestoreProgress: Float): Float {
    val progress = surfaceRestoreProgress.coerceIn(0f, 1f)
    return FLOWING_BACKGROUND_CARD_ALPHA + (1f - FLOWING_BACKGROUND_CARD_ALPHA) * progress
}

private const val FLOWING_BACKGROUND_CARD_ALPHA = 0.40f
