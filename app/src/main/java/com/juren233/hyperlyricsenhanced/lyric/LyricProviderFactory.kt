package com.juren233.hyperlyricsenhanced.lyric

import android.content.Context

object LyricProviderFactory {
    fun create(context: Context): ILyricProvider {
        return LyricProviderImpl(context)
    }
}
