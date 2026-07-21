package com.lidesheng.hyperlyric.lyric

import android.content.Context

object LyricProviderFactory {
    fun create(context: Context): ILyricProvider {
        return LyricProviderImpl(context)
    }
}
