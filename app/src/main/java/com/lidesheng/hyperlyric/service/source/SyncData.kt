package com.lidesheng.hyperlyric.service.source

import android.graphics.Bitmap

data class SyncData(
    val identityTitle: String,
    val identityArtist: String,
    val identityAlbum: String,
    val dynamicTitle: String,
    val duration: Long,
    val position: Long,
    val isPlaying: Boolean,
    val currentPackageName: String,
    val isNewSong: Boolean,
    val albumBitmap: Bitmap?,
    val notificationAlbumBitmap: Bitmap?,
    val notificationAlbumBitmapCircular: Bitmap?,
    val identifier: String,
    val lyricInfoRaw: String? = null,
    val lyricRaw: String? = null
)
