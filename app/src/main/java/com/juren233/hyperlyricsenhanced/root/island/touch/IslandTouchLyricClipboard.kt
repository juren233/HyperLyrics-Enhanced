/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.SongPreprocessor
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge

internal object IslandTouchLyricClipboard {
    fun copy(context: Context, packageName: String): Boolean {
        val text = currentText(packageName) ?: return false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("HyperLyrics Enhanced", text))
        return true
    }

    internal fun currentText(packageName: String): String? {
        val version = LyriconDataBridge.versionCounter.get()
        val position = LyriconDataBridge.estimatedPosition() ?: LyriconDataBridge.currentPosition
        val lines = LyriconDataBridge.currentSong?.lyrics
        // The displayed line can already be the next-line preview. Resolve the singing
        // line from the unmerged timeline, before slot splitting and language filtering.
        val line = if (!LyriconDataBridge.isTextMode && !lines.isNullOrEmpty()) {
            lines.lastOrNull { position >= it.begin && position <= it.end }
        } else {
            // Single-line/text providers have no full timeline; their current callback is authoritative.
            LyriconDataBridge.currentLyricLine
        }
        val text = textFor(
            packageName,
            LyriconDataBridge.currentLyricPackageName,
            line,
        ) ?: return null
        if (version != LyriconDataBridge.versionCounter.get() ||
            packageName != LyriconDataBridge.currentLyricPackageName) return null
        return text
    }

    internal fun textFor(packageName: String, lyricPackageName: String?, line: IRichLyricLine?): String? {
        if (packageName.isBlank() || packageName != lyricPackageName || line == null) return null
        if (line.metadata?.getBoolean(SongPreprocessor.KEY_TITLE_LINE) == true ||
            line.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) == true) return null
        val original = line.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val translation = line.translation?.trim()?.takeIf { it.isNotEmpty() }
        return if (translation == null) original else "$original\n$translation"
    }
}
