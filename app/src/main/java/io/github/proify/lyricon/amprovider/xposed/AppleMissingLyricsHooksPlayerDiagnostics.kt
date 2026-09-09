/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.ChineseLyricsPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import io.github.libxposed.api.XposedInterface.Chain
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 精确记录 Apple Music 播放页歌词按钮的最终消费者链。
 *
 * 原始 DEX 已确认：player.e1.i(PlaybackItem) 计算可用性，l7.N2.l() 再把结果写入
 * M2.a0.setEnabled。这里仅记录真实调用与 View 前后状态，绝不修改返回值或 View。
 */
internal fun AppleMissingLyricsHooks.installLyricsButtonDiagnostics() {
    runCatching {
        val resolved = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR
        )
        runtime.hookRegistrar.installHook(resolved.method, after = { chain, result ->
            logPlayerLyricsAvailabilityCalculation(
                chain = chain,
                result = result,
                target = resolved.target,
            )
        })
        ProviderLogger.diagnostic(
            "Apple Music 歌词按钮最终可用性计算 Hook 已安装: target=${resolved.method}"
        )
    }.onFailure {
        ProviderLogger.error("Apple Music 歌词按钮最终可用性计算 Hook 安装失败", it)
    }
    runCatching {
        val resolved = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE
        )
        runtime.hookRegistrar.installScopedHook(
            resolved.method,
            enter = { chain ->
                val snapshot = capturePlayerSongBindingSnapshot(
                    binding = chain.thisObject,
                    target = resolved.target,
                )
                playerSongBindingSnapshots.push(snapshot)
                if (playerSongBindingHitLogged.compareAndSet(false, true)) {
                    ProviderLogger.diagnostic(
                        "Apple Music 歌词按钮播放页 binding Hook 首次命中: " +
                            "binding=${snapshot.bindingIdentity}, " +
                            "item=${snapshot.itemIdentity}, " +
                            "itemMediaId=${snapshot.itemMediaId}, " +
                            "button=${snapshot.buttonIdentity}, " +
                            "enabled=${snapshot.enabled}"
                    )
                }
                true
            },
            after = { chain, _ ->
                val before = playerSongBindingSnapshots.current
                val after = capturePlayerSongBindingSnapshot(
                    binding = chain.thisObject,
                    target = resolved.target,
                )
                logPlayerSongBindingExecution(before, after)
            },
            exit = { playerSongBindingSnapshots.pop() },
        )
        ProviderLogger.diagnostic(
            "Apple Music 歌词按钮播放页 binding Hook 已安装: target=${resolved.method}, " +
                "itemField=${resolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD
                )}, buttonField=${resolved.target.runtimeMemberName(
                    AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD
                )}"
        )
    }.onFailure {
        ProviderLogger.error("Apple Music 歌词按钮播放页 binding Hook 安装失败", it)
    }
}

internal fun AppleMissingLyricsHooks.logPlayerLyricsAvailabilityCalculation(
    chain: Chain,
    result: Any?,
    target: AppleMusicHookTarget,
) {
    val item = chain.args.firstOrNull()
    val itemSongId = itemMediaId(item)?.takeIf(String::isNotBlank)
    val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
    val diagnosticSongId = queueSongId ?: itemSongId
    val effectiveHasLyrics = readBooleanMethod(
        item,
        target.runtimeMemberName(
            AppleMusicRuntimeMember.PLAYER_LYRICS_ITEM_HAS_LYRICS_METHOD
        ),
    )
    val hasCustomLyrics = readBooleanMethod(
        item,
        target.runtimeMemberName(
            AppleMusicRuntimeMember.PLAYER_LYRICS_ITEM_HAS_CUSTOM_LYRICS_METHOD
        ),
    )
    val currentCapturedItem = currentPlaybackItemReference?.item?.get()
    val storeContent = store.hasContent(diagnosticSongId)
    val nativeKnown = diagnosticSongId?.let { hasKnownNativeLyrics(it) } == true
    val availabilityExposed =
        diagnosticSongId != null && diagnosticSongId in supplementAvailabilitySongIds
    val presentationAccepted =
        diagnosticSongId != null && diagnosticSongId in acceptedSupplementSongIds
    val signature = listOf(
        debugObjectIdentity(item),
        itemSongId,
        queueSongId,
        effectiveHasLyrics,
        hasCustomLyrics,
        result,
        storeContent,
        nativeKnown,
        availabilityExposed,
        presentationAccepted,
        item != null && item === currentCapturedItem,
    ).joinToString("|")
    val firstHit = playerLyricsAvailabilityHitLogged.compareAndSet(false, true)
    val stateChanged = rememberDiagnostic(playerLyricsAvailabilityDiagnosticKeys, signature)
    if (firstHit || stateChanged) {
        ProviderLogger.diagnostic(
            "Apple Music 歌词按钮最终可用性计算: " +
                "item=${debugObjectIdentity(item)}, itemMediaId=$itemSongId, " +
                "queueMediaId=$queueSongId, effectiveHasLyrics=$effectiveHasLyrics, " +
                "hasCustomLyrics=$hasCustomLyrics, result=${result as? Boolean}, " +
                "storeContent=$storeContent, nativeLyricsKnown=$nativeKnown, " +
                "availabilityExposed=$availabilityExposed, " +
                "presentationAccepted=$presentationAccepted, " +
                "sameAsCapturedPlaybackItem=${item != null && item === currentCapturedItem}"
        )
    }
}

internal fun AppleMissingLyricsHooks.capturePlayerSongBindingSnapshot(
    binding: Any?,
    target: AppleMusicHookTarget,
): AppleMissingLyricsHooks.PlayerSongBindingSnapshot {
    val item = binding?.let { targetBinding ->
        runCatching {
            AppleReflection.field(
                targetBinding,
                target.runtimeMemberName(
                    AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD
                ),
            )
        }.getOrNull()
    }
    val button = binding?.let { targetBinding ->
        runCatching {
            AppleReflection.field(
                targetBinding,
                target.runtimeMemberName(
                    AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD
                ),
            ) as? View
        }.getOrNull()
    }
    val itemSongId = itemMediaId(item)?.takeIf(String::isNotBlank)
    val queueSongId = currentPlaybackQueueMediaId()?.takeIf(String::isNotBlank)
    val currentCapturedItem = currentPlaybackItemReference?.item?.get()
    return AppleMissingLyricsHooks.PlayerSongBindingSnapshot(
        bindingIdentity = debugObjectIdentity(binding) ?: "null",
        itemIdentity = debugObjectIdentity(item),
        itemMediaId = itemSongId,
        queueMediaId = queueSongId,
        sameAsCapturedPlaybackItem = item != null && item === currentCapturedItem,
        supplementContent = store.hasContent(queueSongId ?: itemSongId),
        buttonIdentity = debugObjectIdentity(button),
        enabled = button?.isEnabled,
        selected = button?.isSelected,
        clickable = button?.isClickable,
        visibility = button?.visibility,
        alpha = button?.alpha,
        shown = button?.isShown,
        attached = button?.isAttachedToWindow,
        parentEnabled = (button?.parent as? View)?.isEnabled,
    )
}

internal fun AppleMissingLyricsHooks.logPlayerSongBindingExecution(
    before: AppleMissingLyricsHooks.PlayerSongBindingSnapshot?,
    after: AppleMissingLyricsHooks.PlayerSongBindingSnapshot,
) {
    val signature = listOf(before, after).joinToString("|")
    if (!rememberDiagnostic(playerSongBindingDiagnosticKeys, signature)) return
    ProviderLogger.diagnostic(
        "Apple Music 歌词按钮播放页 binding 执行: " +
            "binding=${after.bindingIdentity}, item=${after.itemIdentity}, " +
            "itemMediaId=${after.itemMediaId}, queueMediaId=${after.queueMediaId}, " +
            "sameAsCapturedPlaybackItem=${after.sameAsCapturedPlaybackItem}, " +
            "supplementContent=${after.supplementContent}, button=${after.buttonIdentity}, " +
            "beforeEnabled=${before?.enabled}, afterEnabled=${after.enabled}, " +
            "beforeSelected=${before?.selected}, afterSelected=${after.selected}, " +
            "clickable=${after.clickable}, visibility=${after.visibility}, " +
            "alpha=${after.alpha}, shown=${after.shown}, attached=${after.attached}, " +
            "parentEnabled=${after.parentEnabled}"
    )
}

internal fun AppleMissingLyricsHooks.readBooleanMethod(instance: Any?, methodName: String): Boolean? =
    instance?.let { target ->
        runCatching { AppleReflection.call(target, methodName) as? Boolean }.getOrNull()
    }

internal fun AppleMissingLyricsHooks.debugObjectIdentity(instance: Any?): String? = instance?.let { target ->
    "${target.javaClass.name}@${System.identityHashCode(target).toString(16)}"
}

internal fun AppleMissingLyricsHooks.rememberDiagnostic(keys: MutableSet<String>, signature: String): Boolean {
    if (keys.size >= 256) keys.clear()
    return keys.add(signature)
}
