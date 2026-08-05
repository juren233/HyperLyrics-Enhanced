/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.hooks.ApplePlaybackHooks

/** Installs LocalMediaPlayerController callbacks that feed playback metadata resolution. */
internal class ApplePlaybackMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val playbackHooks: () -> ApplePlaybackHooks,
    private val metadataCoordinator: ApplePlaybackMetadataCoordinator,
) {
    fun installHooks() {
        val metadataUpdated = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_METADATA_UPDATED,
        )
        runtime.hookRegistrar.installHook(metadataUpdated.method, after = { chain, _ ->
            val mediaPlayer = chain.args.firstOrNull()
            val activePlayer = playbackHooks().activePlayer()
            if (!isActivePlaybackCallback(mediaPlayer, activePlayer)) {
                ProviderLogger.debug(
                    "忽略非活动播放器的歌曲元数据：source=onMetadataUpdated, " +
                        "callback=${mediaPlayer?.let(System::identityHashCode)}, " +
                        "active=${activePlayer?.let(System::identityHashCode)}"
                )
                return@installHook
            }
            val callbackPlayer = mediaPlayer ?: return@installHook
            val changedItem = chain.args.getOrNull(1)
            val currentItem = runCatching {
                AppleReflection.call(callbackPlayer, "getCurrentItem")
            }.getOrNull()
            val publishAsCurrent = metadataCoordinator.isCurrentQueueItem(
                changedItem,
                currentItem,
            )
            val refreshPlaybackMetadata = if (publishAsCurrent) {
                val controllerInstance = chain.thisObject
                {
                    runCatching {
                        metadataUpdated.method.invoke(
                            controllerInstance,
                            callbackPlayer,
                            changedItem,
                        )
                    }.onFailure {
                        ProviderLogger.error("Apple 播放元数据覆盖刷新失败", it)
                    }
                    Unit
                }
            } else {
                null
            }
            metadataCoordinator.handleQueueItem(
                queueItem = changedItem,
                source = "onMetadataUpdated",
                publishAsCurrent = publishAsCurrent,
                refreshPlaybackMetadata = refreshPlaybackMetadata,
            )
        })

        val indexChanged = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_INDEX_CHANGED,
        )
        runtime.hookRegistrar.installHook(indexChanged.method, after = { chain, _ ->
            metadataCoordinator.refreshCurrentQueueItemIfActive(
                chain.args.firstOrNull(),
                "onPlaybackIndexChanged",
            )
        })
        ProviderLogger.info(
            "Apple 播放元数据 Hook 已安装: " +
                "metadata=${metadataUpdated.target.className}#" +
                "${metadataUpdated.target.methodName}, " +
                "index=${indexChanged.target.className}#${indexChanged.target.methodName}, " +
                "fallback=${metadataUpdated.compatibilityFallback || indexChanged.compatibilityFallback}"
        )
    }
}
