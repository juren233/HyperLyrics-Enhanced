/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application

class LyricRequester(
    private val classLoader: ClassLoader,
    private val application: Application
) {
    private var playerLyricsViewModel: Any? = null

    fun ownsViewModel(instance: Any?): Boolean = instance === playerLyricsViewModel

    /**
     * 欺骗 Apple Music 触发歌词下载
     *
     * @see Apple.hookLyricBuildMethod
     */
    fun requestDownload(mediaId: String, queueId: Long) {
        if (mediaId.isBlank()) {
            ProviderLogger.debug("LyricRequester: mediaId is null or blank")
            return
        }
        try {
            val song =
                AppleReflection.newInstance(classLoader.loadClass("com.apple.android.music.model.Song"))
            AppleReflection.call(song, "setId", mediaId)
            AppleReflection.call(song, "setQueueId", queueId)
            AppleReflection.call(song, "setHasLyrics", true)

            if (playerLyricsViewModel == null) {
                playerLyricsViewModel = classLoader
                    .loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                    .getConstructor(Application::class.java)
                    .newInstance(application)
            }

            AppleReflection.call(requireNotNull(playerLyricsViewModel), "loadLyrics", song)
            ProviderLogger.debug("LyricRequester: Triggered download for $mediaId, queueId=$queueId")

        } catch (e: Exception) {
            ProviderLogger.error("LyricRequester: Failed to trigger download", e)
        }
    }
}
