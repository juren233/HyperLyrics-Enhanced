/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application

class LyricRequester internal constructor(
    private val hookResolver: AppleMusicHookResolver,
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
            val resolvedSong = hookResolver.resolveClass(
                AppleMusicHookPoint.APPLE_SONG_MODEL_CLASS
            )
            val song = AppleReflection.newInstance(resolvedSong.clazz)
            AppleReflection.call(
                song,
                resolvedSong.target.runtimeMemberName(
                    AppleMusicRuntimeMember.APPLE_SONG_SET_ID_METHOD
                ),
                mediaId,
            )
            AppleReflection.call(
                song,
                resolvedSong.target.runtimeMemberName(
                    AppleMusicRuntimeMember.APPLE_SONG_SET_QUEUE_ID_METHOD
                ),
                queueId,
            )
            AppleReflection.call(
                song,
                resolvedSong.target.runtimeMemberName(
                    AppleMusicRuntimeMember.APPLE_SONG_SET_HAS_LYRICS_METHOD
                ),
                true,
            )

            if (playerLyricsViewModel == null) {
                playerLyricsViewModel = hookResolver
                    .resolveClass(AppleMusicHookPoint.PLAYER_LYRICS_VIEW_MODEL_CLASS)
                    .clazz
                    .getConstructor(Application::class.java)
                    .newInstance(application)
            }

            hookResolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).method.invoke(
                requireNotNull(playerLyricsViewModel),
                song,
            )
            ProviderLogger.debug("LyricRequester: Triggered download for $mediaId, queueId=$queueId")

        } catch (e: Exception) {
            ProviderLogger.error("LyricRequester: Failed to trigger download", e)
        }
    }
}
