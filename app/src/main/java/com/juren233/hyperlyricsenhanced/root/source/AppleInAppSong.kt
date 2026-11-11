/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong

/**
 * 一次在线翻译/发音检索所作用的对象：手动选择状态与检索任务槽位（generation、attempt 都在其中）。
 *
 * 活动播放者是 Apple Music 时用 [LyriconSource] 上的正式状态；Apple Music 不是活动播放者时，
 * 来源弹窗仍要能检索，则用 [AppleInAppSong] 的独立状态，两者互不取消、互不覆盖。
 */
internal class OnlineTranslationTarget(
    /** 为 true 时结果只经直连桥回传给 Apple Music，绝不发布到岛端（sink），也不碰 [LyriconPublication]。 */
    val inAppOnly: Boolean,
    val manual: AppleManualSourceRequestState,
    val request: OnlineTranslationRequest<LyriconSource.PendingOnlineTranslationCommit>,
    /** 歌词来源检索任务槽位（generation / job / delay），与翻译检索槽位并列、各自独立。 */
    val fallback: AppleFallbackRequest,
    /** 各来源取词结果的缓存，每个检索对象各持一份，互不清除。 */
    val fetchCache: OnlineTranslationFetchCache,
) {
    /** 上一轮完整结果，重开弹窗时原样重发。 */
    val finalPayload = OnlineSourceFinalPayload()

    /**
     * 这一轮是不是「切换」：有待完成的翻译/发音切换请求（或刚改选了「Apple Music 原生」，[selectionChanged]），
     * 且该曲各来源的取词结果仍在有效期内。否则是首次检索（弹窗打开、还没有任何来源结果）。
     * 是否渐进发布只由这一处判定。
     */
    fun isSourceSwitchRound(songId: String?, selectionChanged: Boolean = false): Boolean =
        songId != null && (manual.hasPendingOnlineRequest(songId) || selectionChanged) &&
            fetchCache.hasLiveEntries(songId)

    /** 上一轮完整结果仍可原样重用时返回它：同一首歌、同一 attempt、同样歌词行、取词结果在有效期内、没有检索在进行。 */
    fun reusableFinalPayload(song: LocalSong, attemptKey: String): LocalSong? {
        val songId = song.id ?: return null
        val snapshot = request.snapshot()
        if (snapshot.running || snapshot.resultReady) return null
        if (!fetchCache.hasLiveEntries(songId)) return null
        return finalPayload.reusable(attemptKey, song)
    }
}

/**
 * 「应用内 Apple 歌曲」槽位：Apple Music 歌词页正在显示、但 Apple Music 此刻不是核心活动播放者
 * 时，该歌曲只供 Apple Music 应用内的来源弹窗使用。
 *
 * - 直连歌曲被活动播放者规则拒收时存入这里，不发布到岛端；
 * - Central 活动播放者切到非 Apple Music 时，原 Apple 歌曲及其本曲手动选择转入这里；
 * - Apple Music 之后成为活动播放者时，同一首歌的手动选择由 [claimFor] 交还正式状态，其他歌曲作废；
 * - 手动选择仍然只对本曲有效，切歌即清。
 *
 * 岛端与 AOD 显示谁的歌词的规则不在这里：本类不持有 sink，也不改动活动播放者相关状态。
 */
internal class AppleInAppSong(
    clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class Update {
        /** 传入为空，槽位已清空。 */
        CLEARED,
        NEW_TRACK,
        SAME_TRACK,
    }

    data class Accepted(
        val update: Update,
        /** 同一首歌的原名元数据变了：此前因缺原名而未命中的检索需要重新进行。 */
        val originalMetadataChanged: Boolean = false,
    )

    /** Apple Music 重新成为活动播放者时，槽位交还给正式状态的内容。 */
    data class Claim(
        val selection: AppleManualSourceRequestState.Selection?,
        /** 用户在应用内确认的歌词来源；为 [LyriconSource.APPLE_NATIVE_LYRICS_SOURCE] 时表示回到原生。 */
        val lyricsSelection: ConfirmedLyricsSourceSelection?,
        /** 已按所选歌词来源换成的补充歌曲（原生歌词为基准时为空）。 */
        val supplementSong: LocalSong?,
    )

    @Volatile
    private var song: LocalSong? = null
    /** 槽位里最近一次非补充的 Apple 歌曲，切回「Apple 原生」歌词时的基准。 */
    @Volatile
    private var nativeSong: LocalSong? = null
    @Volatile
    private var nativeLyricsConfirmed = false
    @Volatile
    private var published: LocalSong? = null
    @Volatile
    private var lyricsSelection: ConfirmedLyricsSourceSelection? = null
    private val manual = AppleManualSourceRequestState()
    private val request = OnlineTranslationRequest<LyriconSource.PendingOnlineTranslationCommit>()
    private val fallbackRequest = AppleFallbackRequest()

    val target = OnlineTranslationTarget(
        inAppOnly = true,
        manual = manual,
        request = request,
        fallback = fallbackRequest,
        fetchCache = OnlineTranslationFetchCache(clockMs),
    )

    fun song(): LocalSong? = song

    fun nativeSong(): LocalSong? = nativeSong

    fun hasNativeLyrics(): Boolean = nativeLyricsConfirmed

    fun lyricsSelection(): ConfirmedLyricsSourceSelection? = lyricsSelection

    /** 最近一次回传给 Apple Music 应用内的合成结果，供后续检索叠加。 */
    fun publishedSong(): LocalSong? = published

    fun recordPublished(song: LocalSong?) {
        published = song
    }

    /** 槽位里 id 为 [songId] 的歌曲；没有或不是这首歌时为 null。 */
    fun songFor(songId: String?): LocalSong? =
        song?.takeIf { !songId.isNullOrBlank() && it.id == songId }

    /**
     * 接收被活动播放者规则拒收的直连歌曲。
     * 同一首歌的更新：保留本曲手动选择、已回传结果与已确认的歌词来源；合并规则与正式路径同一份
     * （[mergeMissingLyricsSupplementMetadata]、[mergeRetainedSongDisplayMetadata]），
     * 所以用户刚选的歌词来源不会被同曲的原生回调回滚，空歌词也不会降级已有歌词。
     * 换了一首歌则清空它们，并作废进行中的检索。
     */
    fun accept(
        incoming: LocalSong?,
        hasNativeLyrics: (LocalSong?) -> Boolean,
        isSupplement: (LocalSong?) -> Boolean,
    ): Accepted {
        if (incoming == null) {
            clear()
            return Accepted(Update.CLEARED)
        }
        val previous = song
        if (previous == null || !sameTrack(previous, incoming)) {
            clear()
            song = incoming
            nativeSong = incoming.takeUnless(isSupplement)
            nativeLyricsConfirmed = hasNativeLyrics(incoming)
            return Accepted(Update.NEW_TRACK)
        }
        if (!isSupplement(incoming)) nativeSong = incoming
        val originalMetadataChanged =
            AppleOnlineTranslationRequestPolicy.originalMetadataChanged(previous, incoming)
        val authoritativeSource = lyricsSelection
            ?.takeIf { it.songId == previous.id && it.songId == incoming.id }
            ?.source
        val merged = mergeMissingLyricsSupplementMetadata(
            previousSong = previous,
            incomingSong = incoming,
            sameTrack = true,
            authoritativeSource = authoritativeSource,
            manualSourceReplacesNative = true,
        )
        val result = mergeRetainedSongDisplayMetadata(merged, previous, incoming) ?: incoming
        if (AppleSongUpdatePolicy.shouldPreserveCurrentLyrics(previous, result, sameTrack = true)) {
            song = AppleSongUpdatePolicy.refreshDisplayMetadata(previous, result) ?: previous
        } else {
            song = result
            nativeLyricsConfirmed = hasNativeLyrics(result)
        }
        return Accepted(Update.SAME_TRACK, originalMetadataChanged)
    }

    /** 用户在应用内切歌词来源成功：槽位歌曲换成补充歌曲，并记下确认的来源。 */
    fun acceptSupplement(supplement: LocalSong, selection: ConfirmedLyricsSourceSelection?) {
        song = supplement
        nativeLyricsConfirmed = false
        selection?.let { lyricsSelection = it }
    }

    /** 切回「Apple 原生」歌词：槽位歌曲回到原生基准，并记下选择。 */
    fun restoreNative(selection: ConfirmedLyricsSourceSelection) {
        val native = nativeSong ?: return
        song = native
        nativeLyricsConfirmed = true
        lyricsSelection = selection
    }

    fun confirmLyricsSource(selection: ConfirmedLyricsSourceSelection) {
        lyricsSelection = selection
    }

    /** 活动播放者离开 Apple Music：把原 Apple 歌曲及其本曲手动选择、歌词来源选择转入槽位。 */
    fun adopt(
        song: LocalSong,
        nativeSong: LocalSong?,
        hasNativeLyrics: Boolean,
        lyricsSelection: ConfirmedLyricsSourceSelection?,
        selection: AppleManualSourceRequestState.Selection,
    ) {
        clear()
        this.song = song
        this.nativeSong = nativeSong
        nativeLyricsConfirmed = hasNativeLyrics
        this.lyricsSelection = lyricsSelection
        manual.restoreSelection(selection)
    }

    /**
     * Apple Music 成为活动播放者后收到第一首歌 [incoming]：槽位里是同一首歌则交出它的手动选择与歌词来源选择，
     * 其他歌曲作废。无论哪种结果槽位都被清空，之后由正式状态接管。
     */
    fun claimFor(incoming: LocalSong?, isSupplement: (LocalSong?) -> Boolean): Claim? {
        val held = song ?: return null
        val same = incoming != null && sameTrack(held, incoming)
        val claim = if (same) {
            val chosen = lyricsSelection
            Claim(
                selection = manual.selection().takeUnless { it.isEmpty },
                lyricsSelection = chosen,
                supplementSong = held.takeIf {
                    isSupplement(it) && chosen != null &&
                        chosen.source != LyriconSource.APPLE_NATIVE_LYRICS_SOURCE
                },
            )
        } else {
            null
        }
        clear()
        return claim?.takeUnless {
            it.selection == null && it.lyricsSelection == null && it.supplementSong == null
        }
    }

    /** 「始终显示切换源按钮」关闭：撤销本曲手动选择、歌词来源选择与已回传结果，歌曲回到原生基准。 */
    fun clearManualSelection() {
        request.cancel(clearAttempt = true)
        target.finalPayload.clear()
        fallbackRequest.invalidate { }
        manual.clearForTrackChange()
        published = null
        lyricsSelection = null
        nativeSong?.let {
            song = it
            nativeLyricsConfirmed = !it.lyrics.isNullOrEmpty()
        }
    }

    fun clear() {
        request.cancel(clearAttempt = true)
        target.finalPayload.clear()
        fallbackRequest.invalidate { }
        manual.clearForTrackChange()
        song = null
        nativeSong = null
        nativeLyricsConfirmed = false
        published = null
        lyricsSelection = null
    }

    private fun sameTrack(first: LocalSong, second: LocalSong): Boolean =
        SourceTrackIdentity.of(first).matches(SourceTrackIdentity.of(second))
}
