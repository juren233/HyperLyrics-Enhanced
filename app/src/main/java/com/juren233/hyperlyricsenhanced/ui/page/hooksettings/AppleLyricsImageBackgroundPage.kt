/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** Picks, tunes and previews the custom background of the Apple Music lyric share image. */
@Composable
fun AppleLyricsImageBackgroundPage() {
    val context = LocalContext.current
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)
    val coroutineScope = rememberCoroutineScope()

    var blur by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_BLUR,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_BLUR,
            )
        )
    }
    var brightness by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_BRIGHTNESS,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_BRIGHTNESS,
            )
        )
    }
    val withQr = remember {
        prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_QR_CODE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_QR_CODE,
        )
    }
    // What a poster was drawn from; the last one is reused when the page is opened again.
    fun previewKey(sourceStamp: Long, blurLevel: Int, brightnessLevel: Int): Any =
        listOf(sourceStamp, blurLevel, brightnessLevel, withQr)
    val initialKey = remember { previewKey(AppleLyricsImageBackgroundStore.sourceStamp(context), blur, brightness) }
    var preview by remember {
        mutableStateOf(AppleLyricsImagePreviewCache.preview(AppleLyricsImagePreviewCache.PAGE_IMAGE, initialKey))
    }
    var drawnKey by remember { mutableStateOf<Any?>(initialKey.takeIf { preview != null }) }

    // Downscaled source for the live preview; null until read. Bumped after a new image is imported.
    var sourceRevision by remember { mutableIntStateOf(0) }
    var previewSource by remember { mutableStateOf<PreviewSource?>(null) }
    // The story background stands in when no image is set.
    val samples = rememberAppleLyricsSamples()
    LaunchedEffect(sourceRevision) {
        val loaded = withContext(Dispatchers.IO) {
            PreviewSource(
                stamp = AppleLyricsImageBackgroundStore.sourceStamp(context),
                bitmap = AppleLyricsImageBackgroundStore.loadSource(
                    context,
                    AppleLyricsImageBackgroundStore.PREVIEW_EDGE,
                ),
            )
        }
        // Not recycled by hand: a cancelled render may still be reading the previous one.
        previewSource = loaded
    }
    // collectLatest-like: a newer slider value cancels the pending render. Waits for the source,
    // so the story background never flashes in before a picked image.
    LaunchedEffect(previewSource, samples, blur, brightness) {
        val source = previewSource ?: return@LaunchedEffect
        val card = samples?.card ?: return@LaunchedEffect
        val key = previewKey(source.stamp, blur, brightness)
        if (key == drawnKey) return@LaunchedEffect
        val composed = withContext(Dispatchers.Default) {
            val rendered = source.bitmap?.let { AppleLyricsImageBackgroundStore.render(it, blur, brightness) }
            val background = rendered ?: samples.background ?: return@withContext null
            try {
                AppleLyricsImageBackgroundStore.composePreview(background, card, samples.wordmark, withQr)
            } finally {
                rendered?.recycle()
            }
        } ?: return@LaunchedEffect
        preview = composed
        drawnKey = key
        AppleLyricsImagePreviewCache.putPreview(AppleLyricsImagePreviewCache.PAGE_IMAGE, key, composed)
    }

    val saveFailed = stringResource(R.string.toast_apple_music_lyrics_image_background_save_failed)
    val readFailed = stringResource(R.string.toast_apple_music_lyrics_image_background_read_failed)
    val publish: () -> Unit = {
        val blurValue = blur
        val brightnessValue = brightness
        coroutineScope.launch {
            val published = withContext(Dispatchers.IO) {
                AppleLyricsImageBackgroundStore.publish(context, blurValue, brightnessValue)
            }
            if (!published && AppleLyricsImageBackgroundStore.hasSource(context)) {
                Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val imported = withContext(Dispatchers.IO) {
                AppleLyricsImageBackgroundStore.importSource(context, uri)
            }
            if (!imported) {
                Toast.makeText(context, readFailed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            sourceRevision++
            publish()
        }
    }

    XposedLyricSettingPage(
        title = stringResource(R.string.title_apple_music_lyrics_image_background_image),
    ) {
        item(key = "apple_lyrics_image_background_preview") {
            AppleLyricsImagePreviewCard(preview, withQr)
        }
        item(key = "apple_lyrics_image_background_controls") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.action_apple_music_lyrics_image_pick_background),
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                    BackgroundSlider(
                        title = stringResource(R.string.title_apple_music_lyrics_image_background_blur),
                        value = blur,
                        range = 0..100,
                        onValueChange = { blur = it },
                        onValueChangeFinished = {
                            saveConfig(RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_BLUR, blur)
                            publish()
                        },
                    )
                    BackgroundSlider(
                        title = stringResource(R.string.title_apple_music_lyrics_image_background_brightness),
                        value = brightness,
                        range = -100..100,
                        onValueChange = { brightness = it },
                        onValueChangeFinished = {
                            saveConfig(
                                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_BRIGHTNESS,
                                brightness,
                            )
                            publish()
                        },
                    )
                }
            }
        }
    }
}

/** The picked image as read for the preview; [bitmap] is null when none is set. */
private class PreviewSource(val stamp: Long, val bitmap: Bitmap?)

@Composable
private fun BackgroundSlider(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    BasicComponent(
        title = title,
        endActions = {
            Text(
                text = value.toString(),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
        bottomAction = {
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.roundToInt().coerceIn(range.first, range.last)) },
                onValueChangeFinished = onValueChangeFinished,
                valueRange = range.first.toFloat()..range.last.toFloat(),
            )
        },
    )
}
