package com.lidesheng.hyperlyric.service.source

import android.content.Context
import com.lidesheng.hyperlyric.common.ServiceConstants
import com.lidesheng.hyperlyric.lyric.LyricProviderFactory

class ServiceSourceManager(private val context: Context) {

    private val lyricProvider by lazy { LyricProviderFactory.create(context) }

    private val lyricInfoSource = LyricInfoLyricSource()
    private val lrcSource = MetadataLrcLyricSource()
    private val onlineSource by lazy { OnlineLyricSource(lyricProvider) }
    private val titleSource = TitleLyricSource()
    private val autoSource by lazy { AutoLyricSource(lyricInfoSource, lrcSource) }

    /**
     * 根据设置的整型 ID 获取歌词源实例
     */
    fun getSource(sourceId: Int): ServiceLyricSource {
        return when (sourceId) {
            ServiceConstants.LYRIC_SOURCE_AUTO -> autoSource
            ServiceConstants.LYRIC_SOURCE_ONLINE -> onlineSource
            ServiceConstants.LYRIC_SOURCE_LYRIC_INFO -> lyricInfoSource
            ServiceConstants.LYRIC_SOURCE_LRC -> lrcSource
            ServiceConstants.LYRIC_SOURCE_TITLE -> titleSource
            else -> autoSource
        }
    }
}
