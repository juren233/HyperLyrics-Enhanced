package com.lidesheng.hyperlyric.lyric

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

val commonMusicApps = mapOf(
    "com.salt.music" to "Salt Player",
    "com.netease.cloudmusic" to "网易云音乐",
    "com.tencent.qqmusic" to "QQ音乐",
    "cn.kuwo.player" to "酷我音乐",
    "com.kugou.android" to "酷狗音乐",
    "com.apple.android.music" to "Apple Music",
    "com.spotify.music" to "Spotify",
    "cmccwm.mobilemusic" to "咪咕音乐",
    "com.luna.music" to "汽水音乐",
    "com.kugou.android.lite" to "酷狗音乐概念版",
    "com.google.android.apps.youtube.music" to "YouTube Music",
    "cn.wenyu.bodian" to "波点音乐",
    "com.miui.player" to "小米音乐",
    "com.xuncorp.qinalt.music" to "青盐云听"
)

data class PlaybackAnchor(
    val position: Long = 0L,
    val timestamp: Long = 0L,
    val speed: Float = 1.0f,
    val isPlaying: Boolean = false
)

data class LyricState(
    val islandTitleLeft: String = "等待播放...",
    val islandTitleRight: String = "HyperLyric",
    val notificationTitleLeft: String = "",
    val notificationTitleRight: String = "",
    val songLyric: String = "",
    val songInfo: String = "",
    val showIslandLeftAlbum: Boolean = false,
    val duration: Long = 100L,
    val isPlaying: Boolean = false,
    val targetPackageName: String = "",
    val albumColor: Int = Color.BLACK,
    val albumColorEnd: Int = Color.BLACK,
    val albumBitmap: Bitmap? = null,
    val notificationAlbumBitmap: Bitmap? = null,
    val notificationAlbumBitmapCircular: Bitmap? = null,
    val islandLeftIconStyle: Int = 0,
    
    val isFetchingLyrics: Boolean = false,
    val isLoadingAlbumArt: Boolean = false,
    val playbackAnchor: PlaybackAnchor = PlaybackAnchor()
)

object DynamicLyricData {
    private val _musicState = MutableStateFlow(LyricState())
    val musicState = _musicState.asStateFlow()

    val currentState: LyricState
        get() = _musicState.value

    private val _progressFlow = MutableSharedFlow<Float>(extraBufferCapacity = 1)
    val progressFlow: SharedFlow<Float> = _progressFlow

    fun emitProgress(progress: Float) {
        _progressFlow.tryEmit(progress)
    }

    fun updateFetchingLyrics(fetching: Boolean) {
        _musicState.update { it.copy(isFetchingLyrics = fetching) }
    }

    fun updateLoadingAlbumArt(loading: Boolean) {
        _musicState.update { it.copy(isLoadingAlbumArt = loading) }
    }

    fun updateAnchor(position: Long, isPlaying: Boolean, speed: Float = 1.0f) {
        val newAnchor = PlaybackAnchor(
            position = position,
            timestamp = SystemClock.elapsedRealtime(),
            speed = speed,
            isPlaying = isPlaying
        )
        _musicState.update { it.copy(playbackAnchor = newAnchor, isPlaying = isPlaying) }
    }

    fun updateLeftTitles(islandText: String, notificationText: String = "") {
        _musicState.update { 
            it.copy(
                islandTitleLeft = islandText.ifBlank { " " },
                notificationTitleLeft = notificationText
            ) 
        }
    }

    fun updateBitmaps(albumBmp: Bitmap?, notificationAlbumBmp: Bitmap? = null, notificationAlbumBmpCircular: Bitmap? = null) {
        _musicState.update { it.copy(
            albumBitmap = albumBmp,
            notificationAlbumBitmap = notificationAlbumBmp ?: it.notificationAlbumBitmap,
            notificationAlbumBitmapCircular = notificationAlbumBmpCircular ?: it.notificationAlbumBitmapCircular
        ) }
    }

    fun updateIslandLeftIconStyle(style: Int) {
        _musicState.update { it.copy(islandLeftIconStyle = style) }
    }

    fun updateColor(color: Int, colorEnd: Int) {
        _musicState.update { it.copy(albumColor = color, albumColorEnd = colorEnd) }
    }

    fun updateRightTitles(
        islandText: String,
        notificationText: String = "",
        newSongLyric: String,
        newSongInfo: String,
        newDuration: Long,
        newIsPlaying: Boolean,
        newPackageName: String,
        newShowIslandLeftAlbum: Boolean = false
    ) {
        _musicState.update { oldState ->
            oldState.copy(
                islandTitleRight = islandText,
                notificationTitleRight = notificationText,
                songLyric = newSongLyric,
                songInfo = newSongInfo,
                duration = if (newDuration > 0) newDuration else oldState.duration,
                isPlaying = newIsPlaying,
                targetPackageName = newPackageName,
                showIslandLeftAlbum = newShowIslandLeftAlbum
            )
        }
    }

    fun LyricState.getCurrentPosition(): Long {
        if (!playbackAnchor.isPlaying) return playbackAnchor.position
        val elapsed = SystemClock.elapsedRealtime() - playbackAnchor.timestamp
        return playbackAnchor.position + (elapsed * playbackAnchor.speed).toLong()
    }
}
