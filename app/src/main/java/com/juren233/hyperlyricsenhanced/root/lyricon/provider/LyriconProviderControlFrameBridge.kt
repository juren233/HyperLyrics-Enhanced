/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.lyricon.provider

import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.provider.RemotePlayer
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps HyperLyrics control frames separate from Lyricon's SONG/TEXT reconnect cache.
 *
 * Binary evidence from provider-0.1.70.aar (verified with javap):
 * - io.github.proify.lyricon.provider.CachedRemotePlayer.sendText(String): boolean
 * - io.github.proify.lyricon.provider.CachedRemotePlayer.syncs$provider(): void
 * - io.github.proify.lyricon.provider.CachedRemotePlayer.getPlayer(): RemotePlayer
 *
 * CachedRemotePlayer intentionally has one last-lyric slot. Sending next-track metadata through
 * sendText would therefore replace the cached Song with TEXT. Store that private control channel
 * independently, forward it to the underlying player, and replay it only after Lyricon has restored
 * the real lyric slot on reconnect.
 */
internal object LyriconProviderControlFrameBridge {
    internal const val CACHED_REMOTE_PLAYER_CLASS_NAME =
        "io.github.proify.lyricon.provider.CachedRemotePlayer"
    internal const val SEND_TEXT_METHOD_NAME = "sendText"
    internal const val SYNC_METHOD_NAME = "syncs\$provider"
    internal const val PLAYER_GETTER_METHOD_NAME = "getPlayer"

    private const val TAG = "LyriconControlBridge"
    private val controlFrames = WeakHashMap<Any, String>()
    private val firstControlFrameHit = AtomicBoolean(false)
    private val firstReconnectReplayHit = AtomicBoolean(false)

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val cachedPlayerClass = Class.forName(
            CACHED_REMOTE_PLAYER_CLASS_NAME,
            false,
            classLoader,
        )
        val playerGetter = cachedPlayerClass.getDeclaredMethod(PLAYER_GETTER_METHOD_NAME)
            .apply { isAccessible = true }
        val sendText = cachedPlayerClass.getDeclaredMethod(
            SEND_TEXT_METHOD_NAME,
            String::class.java,
        ).apply { isAccessible = true }
        val sync = cachedPlayerClass.getDeclaredMethod(SYNC_METHOD_NAME)
            .apply { isAccessible = true }

        module.deoptimize(sendText)
        module.hook(sendText).intercept(ControlFrameSendHook(playerGetter))
        module.deoptimize(sync)
        module.hook(sync).intercept(ControlFrameSyncHook(playerGetter))
        HookLogger.i(TAG, "Lyricon 控制帧独立重连通道已安装")
    }

    internal fun shouldUseIndependentChannel(text: String?): Boolean =
        OfficialProviderControlProtocol.isReservedFrame(text)

    private fun remember(owner: Any, frame: String) = synchronized(controlFrames) {
        controlFrames[owner] = frame
    }

    private fun remembered(owner: Any): String? = synchronized(controlFrames) {
        controlFrames[owner]
    }

    private fun delegate(owner: Any, getter: Method): RemotePlayer? =
        getter.invoke(owner) as? RemotePlayer

    private class ControlFrameSendHook(
        private val playerGetter: Method,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val frame = chain.args.firstOrNull() as? String
            if (!shouldUseIndependentChannel(frame)) return chain.proceed()

            remember(chain.thisObject, frame!!)
            if (BuildConfig.DEBUG && firstControlFrameHit.compareAndSet(false, true)) {
                HookLogger.i(TAG, "Lyricon 控制帧独立通道首次命中")
            }
            return runCatching {
                delegate(chain.thisObject, playerGetter)?.sendText(frame) == true
            }.onFailure { error ->
                if (BuildConfig.DEBUG) {
                    HookLogger.e(TAG, "发送 Lyricon 控制帧失败", error)
                }
            }.getOrDefault(false)
        }
    }

    private class ControlFrameSyncHook(
        private val playerGetter: Method,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val frame = remembered(chain.thisObject) ?: return result
            if (BuildConfig.DEBUG && firstReconnectReplayHit.compareAndSet(false, true)) {
                HookLogger.i(TAG, "Lyricon 控制帧重连补发首次命中")
            }
            runCatching {
                delegate(chain.thisObject, playerGetter)?.sendText(frame)
            }.onFailure { error ->
                if (BuildConfig.DEBUG) {
                    HookLogger.e(TAG, "重连后补发 Lyricon 控制帧失败", error)
                }
            }
            return result
        }
    }
}
