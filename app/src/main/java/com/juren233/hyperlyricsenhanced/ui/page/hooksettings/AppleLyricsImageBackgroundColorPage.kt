/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.ui.component.TextInputDialog
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.OkHsvHueSlider
import top.yukonga.miuix.kmp.basic.OkHsvSaturationSlider
import top.yukonga.miuix.kmp.basic.OkHsvValueSlider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.color.core.Transforms
import top.yukonga.miuix.kmp.color.space.OkHsv
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Picks and previews the flat background colour of the Apple Music lyric share image. The
 * Provider paints that background opaque, so this uses Miuix's OkHSV sliders without the alpha
 * one that [top.yukonga.miuix.kmp.basic.ColorPicker] always includes.
 */
@Composable
fun AppleLyricsImageBackgroundColorPage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)

    val storedColor = remember {
        prefs.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR,
        )
    }
    val initial = remember { Transforms.colorToOkhsv(Color(storedColor)) }
    // Hue is kept separately so it survives greys, where the colour itself carries no hue.
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    // The stored or typed colour, kept exact until a slider moves: the OkHSV round trip may be
    // off by one in a channel.
    var exactColor by remember { mutableStateOf<Int?>(storedColor) }
    val color = exactColor ?: OkHsv(hue, saturation, value).toColor(1f).toArgb()
    var savedColor by remember { mutableIntStateOf(storedColor) }
    val withQr = remember {
        prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_QR_CODE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_QR_CODE,
        )
    }

    // What a poster was drawn from; the last one is reused when the page is opened again.
    val initialKey = remember { listOf(color, withQr) }
    var preview by remember {
        mutableStateOf(AppleLyricsImagePreviewCache.preview(AppleLyricsImagePreviewCache.PAGE_COLOR, initialKey))
    }
    var drawnKey by remember { mutableStateOf<Any?>(initialKey.takeIf { preview != null }) }
    val samples = rememberAppleLyricsSamples()
    // A newer colour cancels the pending render.
    LaunchedEffect(samples, color) {
        val card = samples?.card ?: return@LaunchedEffect
        val key = listOf(color, withQr)
        if (key == drawnKey) return@LaunchedEffect
        val composed = withContext(Dispatchers.Default) {
            AppleLyricsImageBackgroundStore.composePreview(color, card, samples.wordmark, withQr)
        }
        preview = composed
        drawnKey = key
        AppleLyricsImagePreviewCache.putPreview(AppleLyricsImagePreviewCache.PAGE_COLOR, key, composed)
    }

    // The sliders have no "finished" callback: save once the finger leaves them, like the image
    // page's sliders. Reads the state objects directly, so it always sees the latest colour.
    val saveIfChanged: () -> Unit = remember {
        {
            val current = exactColor ?: OkHsv(hue, saturation, value).toColor(1f).toArgb()
            if (current != savedColor) {
                savedColor = current
                saveConfig(RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR, current)
            }
        }
    }

    var showHexInput by remember { mutableStateOf(false) }
    TextInputDialog(
        show = showHexInput,
        title = stringResource(R.string.title_apple_music_lyrics_image_background_color),
        initialValue = hexOf(color),
        label = HEX_HINT,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
        ),
        isValid = { parseHex(it) != null },
        onDismiss = { showHexInput = false },
        onConfirm = { text ->
            parseHex(text)?.let { rgb ->
                val (h, s, v) = Transforms.colorToOkhsv(Color(rgb))
                hue = h
                saturation = s
                value = v
                exactColor = rgb
                saveIfChanged()
            }
        },
    )

    XposedLyricSettingPage(
        title = stringResource(R.string.title_apple_music_lyrics_image_background_color),
    ) {
        item(key = "apple_lyrics_image_background_color_preview") {
            AppleLyricsImagePreviewCard(preview, withQr)
        }
        item(key = "apple_lyrics_image_background_color_controls") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    BasicComponent(
                        title = stringResource(R.string.title_apple_music_lyrics_image_background_color),
                        onClick = { showHexInput = true },
                        endActions = {
                            Text(
                                text = hexOf(color),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                    )
                    // Same 12 dp gap as ColorPicker's own slider column.
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp)
                            .pointerInput(Unit) {
                                // Final pass: observes after the sliders handled each event.
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                    } while (event.changes.any { it.pressed })
                                    saveIfChanged()
                                }
                            },
                    ) {
                        OkHsvHueSlider(currentH = hue, onHueChanged = { hue = it; exactColor = null })
                        OkHsvSaturationSlider(currentH = hue, currentS = saturation, onSaturationChanged = { saturation = it; exactColor = null })
                        OkHsvValueSlider(currentH = hue, currentS = saturation, currentV = value, onValueChanged = { value = it; exactColor = null })
                    }
                }
            }
        }
    }
}

private const val HEX_HINT = "#RRGGBB"
private val HEX_COLOR = Regex("#?([0-9A-Fa-f]{6})")

private fun hexOf(color: Int) = String.format("#%06X", color and 0xFFFFFF)

/** Opaque ARGB for "#RRGGBB" or "RRGGBB" (any case), or null when it is not one. */
private fun parseHex(text: String): Int? =
    HEX_COLOR.matchEntire(text.trim())?.groupValues?.get(1)?.toInt(16)?.let { it or 0xFF000000.toInt() }
