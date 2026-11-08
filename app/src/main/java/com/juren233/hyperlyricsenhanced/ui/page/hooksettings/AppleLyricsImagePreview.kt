/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card

/**
 * Shared by the custom image and custom colour pages. Keeps Apple Music's sample renders and each
 * page's last poster for the app's lifetime, so re-entering a page shows its preview at once
 * instead of rendering it again.
 */
internal object AppleLyricsImagePreviewCache {
    const val PAGE_IMAGE = "image"
    const val PAGE_COLOR = "color"

    /**
     * Apple Music's own render of the lyric card and its story background, and its wordmark for
     * the footer subtitle (read from the installed app).
     */
    class Samples(val card: Bitmap?, val background: Bitmap?, val wordmark: Drawable?)

    @Volatile
    private var samples: Samples? = null

    @Volatile
    private var cardSize: Pair<Int, Int>? = null

    private val previews = HashMap<String, Pair<Any, Bitmap>>()

    fun cachedSamples(): Samples? = samples

    fun loadSamples(context: Context): Samples = samples ?: synchronized(this) {
        samples ?: Samples(
            card = AppleLyricsImageBackgroundStore.loadSample(context, R.drawable.apple_lyrics_share_sample_card),
            background = AppleLyricsImageBackgroundStore.loadSample(
                context,
                R.drawable.apple_lyrics_share_sample_background,
            ),
            wordmark = AppleLyricsImageBackgroundStore.loadWordmark(context),
        ).also { samples = it }
    }

    /** Sample card size from the resource header only, so the preview's space is known up front. */
    fun cardSize(context: Context): Pair<Int, Int> = cardSize ?: run {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeResource(context.resources, R.drawable.apple_lyrics_share_sample_card, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "sample card has no size" }
        (bounds.outWidth to bounds.outHeight).also { cardSize = it }
    }

    /** The page's last poster, if it was drawn for exactly [key]. */
    @Synchronized
    fun preview(page: String, key: Any): Bitmap? = previews[page]?.takeIf { it.first == key }?.second

    @Synchronized
    fun putPreview(page: String, key: Any, bitmap: Bitmap) {
        previews[page] = key to bitmap
    }
}

@Composable
internal fun rememberAppleLyricsSamples(): AppleLyricsImagePreviewCache.Samples? {
    val context = LocalContext.current
    var samples by remember { mutableStateOf(AppleLyricsImagePreviewCache.cachedSamples()) }
    LaunchedEffect(Unit) {
        if (samples == null) {
            samples = withContext(Dispatchers.IO) { AppleLyricsImagePreviewCache.loadSamples(context) }
        }
    }
    return samples
}

/**
 * Poster preview card. Its size is fixed from the start, so the controls below never move when
 * the first poster arrives; that first poster fades in.
 */
@Composable
internal fun AppleLyricsImagePreviewCard(preview: Bitmap?, withQr: Boolean) {
    val context = LocalContext.current
    val aspectRatio = remember(withQr) {
        val (width, height) = AppleLyricsImagePreviewCache.cardSize(context)
        AppleLyricsImageBackgroundStore.previewAspectRatio(width, height, withQr)
    }
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
        ) {
            // Qualified: inside the Card's ColumnScope the scoped overload would be picked.
            androidx.compose.animation.AnimatedVisibility(
                visible = preview != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                preview?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
