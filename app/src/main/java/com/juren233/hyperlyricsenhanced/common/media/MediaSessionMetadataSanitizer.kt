/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.media

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 一次 MediaSession 元数据采样。纯数据，不依赖 Android 类型，便于 JVM 单测，
 * 也便于同一份净化逻辑在多个进程里得到相同输入时给出相同结果。
 */
data class SessionMetadataSample(
    val packageName: String,
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long = 0L,
    /** 采样时刻的播放位置估计；未知时为 [UNKNOWN_POSITION]。 */
    val positionMs: Long = UNKNOWN_POSITION,
    val playing: Boolean = false,
    /** 单调时钟毫秒（elapsedRealtime），只用于推算两次采样之间位置应前进多少。 */
    val sampledAtMs: Long = 0L,
) {
    companion object {
        const val UNKNOWN_POSITION = -1L
    }
}

/**
 * 不受车载歌词改写影响的独立身份来源。枚举顺序即优先级，越靠前越可信。
 *
 * [LYRICS_SONG]（歌词源自带的歌名与歌手，来源是外部发布端或歌词数据）本身未必干净，
 * 所以只有能与会话歌手串交叉验证时才被采用，见 [MediaSessionMetadataSanitizer.sanitize]。
 */
enum class AnchorSource { PROVIDER, LYRICS_SONG, NOTIFICATION }

/**
 * 独立于 MediaSession 取得的真实曲目信息。锚点只在拿到污染证据后才会覆盖原始值：
 * Apple Music 原名恢复等功能会让 MediaSession 与通知/Provider 合法地不一致。
 */
data class TrackAnchor(
    val source: AnchorSource,
    val title: String?,
    val artist: String? = null,
)

data class TitleArtist(val title: String, val artist: String)

/**
 * 净化结果。[title]/[artist] 为 null 表示该字段无法确定（不会回填被污染的原始值）。
 * [identityTitle]/[identityArtist] 是曲目身份键应当使用的文本：被污染的曲目里标题会逐句漂移，
 * 因此身份只取在单曲内稳定的原始歌手字段，标题留空。
 */
data class SanitizedTrack(
    val packageName: String,
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val status: Status,
    val resolvedBy: Resolution,
    val identityTitle: String,
    val identityArtist: String,
    /**
     * 当前曲目段的编号，换曲判定（见 [MediaSessionMetadataSanitizer]）成立时才变。
     * 曲首的专辑补全、时长取整、歌手被拼成「歌手-歌名」都不会改变它，
     * 因此没有 mediaId 的会话用它作身份键，而不是拼接这些会变的字段。
     */
    val trackToken: String,
    /** 歌手字段是「A-B」组合串但顺序无法确定时的候选，按可能性从高到低排列。 */
    val candidates: List<TitleArtist> = emptyList(),
) {
    enum class Status {
        /** 没有污染证据，字段为原始值。 */
        CLEAN,

        /** 有污染证据且标题/歌手都已确定。 */
        RECOVERED,

        /** 有污染证据，但标题或歌手至少一项无法确定。 */
        UNRESOLVED,
    }

    enum class Resolution { RAW, ANCHOR, ARTIST_SPLIT, TITLE_SUFFIX, BASELINE_TITLE, NONE }

    val isPolluted: Boolean get() = status != Status.CLEAN

    /** 展示与比对用的标题：只有已恢复时才替换 [raw]，其余情况一律沿用原始值。 */
    fun titleOr(raw: String): String =
        if (status == Status.RECOVERED) title?.takeIf(String::isNotBlank) ?: raw else raw

    /** 展示与比对用的歌手：规则同 [titleOr]。 */
    fun artistOr(raw: String): String =
        if (status == Status.RECOVERED) artist?.takeIf(String::isNotBlank) ?: raw else raw
}

/**
 * 归一国内音乐 App 开启车载歌词（蓝牙歌词）后的 MediaSession 元数据污染。
 *
 * 已观测的污染形态：标题被替换为当前歌词行并逐句漂移；歌手字段被拼成「歌名-歌手」
 * （小米音乐）或「歌手 - 歌名」（Salt Player，2026-10-26 真机）；标题被拼成「歌名-歌手」。
 *
 * 设计原则：
 * 1. 改写必须有正向证据，无证据原样返回，`A-Lin`、`Jay-Z` 这类正常连字符歌手不受影响。
 * 2. 不靠 App 名称或分隔符顺序写死规则：顺序由独立锚点、专辑歌手或同包名已验证的顺序决定，
 *    都没有时返回 [SanitizedTrack.Status.UNRESOLVED] 与候选，而不是猜。
 * 3. 换曲判断只用车载歌词不会改写的字段（mediaId、时长、专辑、播放位置归零），并据此给每个
 *    曲目段一个编号（[SanitizedTrack.trackToken]）。酷狗在曲首 1.5 秒内会补全专辑、取整时长、
 *    再把歌手拼成「歌手-歌名」，这些都不是换曲，也不能让身份键跟着变。
 *
 * 污染证据（任一即可，单曲内粘性保持）：
 * - 同一曲内标题变化达到 [DRIFT_CHANGES_REQUIRED] 次，或变化后的标题等于当前进度处的歌词行；
 * - 歌手字段能按分隔符拆开，且其中一段等于独立锚点的标题；
 * - （无需粘性）标题以「-歌手」或「歌手-」形式带着歌手字段。
 *
 * 已知边界：同一曲、同时长、同专辑且无 mediaId 的连续两首歌，若播放位置采样不到归零，
 * 不会被识别为换曲。
 */
class MediaSessionMetadataSanitizer(maxSessions: Int = DEFAULT_MAX_SESSIONS) {
    private val lock = Any()
    private val sessions = LruMap<String, TrackState>(maxSessions)

    /** 按包名学到的歌手字段顺序：true=「歌手 - 歌名」，false=「歌名-歌手」。 */
    private val artistFirstByPackage = LruMap<String, Boolean>(MAX_FORMAT_HINTS)

    /**
     * @param anchors 独立来源的真实曲目信息，顺序不限，内部按 [AnchorSource] 优先级使用。
     * @param activeLyricLines 已加载歌词时，采样位置处正在显示的歌词行文本（含翻译/音译行）。
     */
    fun sanitize(
        sample: SessionMetadataSample,
        anchors: List<TrackAnchor> = emptyList(),
        activeLyricLines: List<String> = emptyList(),
    ): SanitizedTrack = sanitize(sample, activeLyricLines) { anchors }

    /**
     * 锚点按需读取：[anchorProvider] 可能是跨进程调用（如读取通知），只有歌手字段能拆分、
     * 标题带歌手或已有污染证据时才会被调用，正常曲目一次都不会调用。
     * 参数是本次采样所属曲目段的编号（与结果的 [SanitizedTrack.trackToken] 相同），供调用方按曲目段缓存。
     * 调用发生在净化器锁内，同一实例的调用彼此串行。
     */
    fun sanitize(
        sample: SessionMetadataSample,
        activeLyricLines: List<String> = emptyList(),
        anchorProvider: (trackToken: String) -> List<TrackAnchor>,
    ): SanitizedTrack = synchronized(lock) {
        val rawTitle = sample.title?.trim().orEmpty()
        val rawArtist = sample.artist?.trim().orEmpty()
        val titleNorm = normalizeForMatch(rawTitle)
        val state = sessions.getOrPut(sample.packageName) { TrackState() }
        // 惰性求值发生在下方换曲判定之后，读到的是本次采样所属曲目段的编号。
        val usableAnchors by lazy(LazyThreadSafetyMode.NONE) {
            anchorProvider(state.token.toString())
                .filter { !it.title.isNullOrBlank() }
                // 歌词源的歌名歌手可能自己就被污染：只有歌名加歌手恰好拼成会话的歌手串（与顺序、
                // 分隔符、名字里的连字符无关）才算独立证据；对不上就不采用，宁可留空也不填错。
                .filter { it.source != AnchorSource.LYRICS_SONG || combinedExplains(rawArtist, it.title, it.artist) }
                .sortedBy { it.source.ordinal }
        }

        val previous = state.last
        val previousTitleNorm = state.lastTitleNorm
        val boundary = if (previous == null) Boundary.NEW_TRACK else boundaryOf(state, previous, sample)
        state.rememberKnownFields(sample, reset = boundary != Boundary.NONE)
        if (boundary != Boundary.NONE) {
            state.beginTrack(newToken = boundary == Boundary.NEW_TRACK)
            if (sample.positionMs in 0..TRACK_START_POSITION_MAX_MS) {
                state.baselineTitle = rawTitle.ifEmpty { null }
            }
        } else if (previous != null && isSameDuration(previous, sample) &&
            titleNorm.isNotEmpty() && state.lastTitleNorm.isNotEmpty() &&
            titleNorm != state.lastTitleNorm
        ) {
            state.titleChanges++
            val matchesLyricNow = activeLyricLines.any { normalizeForMatch(it) == titleNorm }
            if (state.titleChanges >= DRIFT_CHANGES_REQUIRED || matchesLyricNow) {
                state.polluted = true
            }
        }
        if (titleNorm.isNotEmpty()) state.lastTitleNorm = titleNorm
        state.last = sample

        val combined = if (rawArtist.isEmpty()) {
            Combined.None
        } else {
            resolveCombinedArtist(
                rawArtist = rawArtist,
                albumArtist = sample.albumArtist,
                anchors = { usableAnchors },
                artistFirstHint = artistFirstByPackage[sample.packageName],
            )
        }
        if (combined is Combined.Resolved && combined.viaAnchor) state.polluted = true

        if (state.polluted) {
            if (combined is Combined.Resolved) {
                artistFirstByPackage[sample.packageName] = combined.artistFirst
            }
            // 与当前/上一条原始标题相同的锚点只是在复述会话内容，不是独立来源。
            val independentAnchors = usableAnchors.filter {
                val anchorNorm = normalizeForMatch(it.title)
                anchorNorm != titleNorm && anchorNorm != previousTitleNorm
            }
            return@synchronized recovered(sample, rawArtist, state, independentAnchors, combined)
        }

        val strippedTitle = if (splitCandidates(rawTitle).isEmpty()) {
            null
        } else {
            stripArtistFromTitle(rawTitle, listOf(rawArtist) + usableAnchors.mapNotNull { it.artist })
        }
        if (strippedTitle != null) {
            return@synchronized build(
                sample = sample,
                title = strippedTitle,
                artist = rawArtist.ifEmpty { null },
                status = SanitizedTrack.Status.RECOVERED,
                resolvedBy = SanitizedTrack.Resolution.TITLE_SUFFIX,
                identityTitle = strippedTitle,
                identityArtist = rawArtist,
                trackToken = state.token.toString(),
            )
        }
        build(
            sample = sample,
            title = rawTitle.ifEmpty { null },
            artist = rawArtist.ifEmpty { null },
            status = SanitizedTrack.Status.CLEAN,
            resolvedBy = SanitizedTrack.Resolution.RAW,
            identityTitle = rawTitle,
            identityArtist = rawArtist,
            trackToken = state.token.toString(),
        )
    }

    fun clear(packageName: String) = synchronized(lock) {
        sessions.remove(packageName)
        Unit
    }

    fun reset() = synchronized(lock) {
        sessions.clear()
        artistFirstByPackage.clear()
    }

    private fun recovered(
        sample: SessionMetadataSample,
        rawArtist: String,
        state: TrackState,
        anchors: List<TrackAnchor>,
        combined: Combined,
    ): SanitizedTrack {
        val anchor = anchors.firstOrNull()
        val anchorTitle = anchor?.title?.trim()
        val splitResult = combined as? Combined.Resolved
        val baseline = if (anchorTitle == null && splitResult == null) state.baselineTitle else null

        val title = anchorTitle ?: splitResult?.title ?: baseline
        val artist = anchor?.artist?.trim()?.takeIf(String::isNotEmpty)
            ?: splitResult?.artist
            ?: rawArtist.takeIf { combined is Combined.None && it.isNotEmpty() }
        val resolvedBy = when {
            anchorTitle != null -> SanitizedTrack.Resolution.ANCHOR
            splitResult != null -> SanitizedTrack.Resolution.ARTIST_SPLIT
            baseline != null -> SanitizedTrack.Resolution.BASELINE_TITLE
            else -> SanitizedTrack.Resolution.NONE
        }
        val complete = title != null && (artist != null || rawArtist.isEmpty())
        return build(
            sample = sample,
            title = title,
            artist = artist,
            status = if (complete) {
                SanitizedTrack.Status.RECOVERED
            } else {
                SanitizedTrack.Status.UNRESOLVED
            },
            resolvedBy = resolvedBy,
            identityTitle = "",
            identityArtist = rawArtist,
            trackToken = state.token.toString(),
            candidates = (combined as? Combined.Ambiguous)?.candidates.orEmpty(),
        )
    }

    private fun build(
        sample: SessionMetadataSample,
        title: String?,
        artist: String?,
        status: SanitizedTrack.Status,
        resolvedBy: SanitizedTrack.Resolution,
        identityTitle: String,
        identityArtist: String,
        trackToken: String,
        candidates: List<TitleArtist> = emptyList(),
    ) = SanitizedTrack(
        packageName = sample.packageName,
        mediaId = sample.mediaId?.trim()?.ifEmpty { null },
        title = title,
        artist = artist,
        album = sample.album?.trim()?.ifEmpty { null },
        durationMs = sample.durationMs,
        status = status,
        resolvedBy = resolvedBy,
        identityTitle = identityTitle,
        identityArtist = identityArtist,
        trackToken = trackToken,
        candidates = candidates,
    )

    private enum class Boundary {
        NONE,

        /** 同一首歌从头再来（单曲循环、拖回开头）：判定状态重置，曲目编号保持。 */
        RESTART,

        /** 换了一首歌：判定状态重置，曲目编号递增。 */
        NEW_TRACK,
    }

    /**
     * mediaId、时长、专辑与本曲目段最近一次已知的值比较，而不是只和上一次采样比：md3music 切歌时
     * 先发一份只有 MEDIA_URI 的空元数据，再发时长为 0、没有专辑的元数据，之后才补全（2026-11-17 真机）。
     * 只和相邻采样比时，补上的新时长是拿去和 0 比的，换曲永远判不出来。
     */
    private fun boundaryOf(
        state: TrackState,
        previous: SessionMetadataSample,
        current: SessionMetadataSample,
    ): Boundary {
        val currentId = current.mediaId?.trim().orEmpty()
        if (state.knownMediaId.isNotEmpty() && currentId.isNotEmpty() && state.knownMediaId != currentId) {
            return Boundary.NEW_TRACK
        }
        if (state.knownDurationMs > 0L && current.durationMs > 0L &&
            abs(state.knownDurationMs - current.durationMs) > DURATION_TOLERANCE_MS
        ) {
            return Boundary.NEW_TRACK
        }
        val currentAlbum = normalizeForMatch(current.album)
        // 专辑只在两侧都非空且不同才算换曲：酷狗曲首专辑先是空串，几百毫秒后才补全。
        if (state.knownAlbum.isNotEmpty() && currentAlbum.isNotEmpty() && state.knownAlbum != currentAlbum) {
            return Boundary.NEW_TRACK
        }
        if (!isPositionReset(previous, current)) return Boundary.NONE
        val previousAlbum = normalizeForMatch(previous.album)
        // 进度归零本身分不清「同一首重播」和「换了一首同时长同专辑的歌」：歌手与专辑都没变就当重播，
        // 与此前按歌手、专辑、时长拼键的判定一致；单曲循环与拖回开头不能清空已加载的歌词。
        val sameSong = normalizeForMatch(previous.artist) == normalizeForMatch(current.artist) &&
            previousAlbum == currentAlbum
        return if (sameSong) Boundary.RESTART else Boundary.NEW_TRACK
    }

    /** 新曲从头开始：当前位置落在曲首窗口内，且比按上次采样推算的位置落后很多。 */
    private fun isPositionReset(previous: SessionMetadataSample, current: SessionMetadataSample): Boolean {
        if (previous.positionMs < 0L || current.positionMs !in 0..TRACK_START_POSITION_MAX_MS) return false
        val elapsed = if (previous.playing) {
            (current.sampledAtMs - previous.sampledAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return previous.positionMs + elapsed - current.positionMs > POSITION_RESET_MIN_BACKTRACK_MS
    }

    /** 两侧时长都已知且相等才算“同一曲”；时长缺失时不做漂移判断，宁可漏判。 */
    private fun isSameDuration(previous: SessionMetadataSample, current: SessionMetadataSample): Boolean =
        previous.durationMs > 0L && current.durationMs > 0L &&
            abs(previous.durationMs - current.durationMs) <= DURATION_TOLERANCE_MS

    private sealed interface Combined {
        object None : Combined

        data class Resolved(
            val title: String,
            val artist: String,
            val artistFirst: Boolean,
            val viaAnchor: Boolean,
        ) : Combined

        data class Ambiguous(val candidates: List<TitleArtist>) : Combined
    }

    private fun resolveCombinedArtist(
        rawArtist: String,
        albumArtist: String?,
        anchors: () -> List<TrackAnchor>,
        artistFirstHint: Boolean?,
    ): Combined {
        val splits = splitCandidates(rawArtist)
        if (splits.isEmpty()) return Combined.None

        for (anchor in anchors()) matchAnchor(splits, anchor)?.let { return it }

        val albumArtistNorm = normalizeForMatch(albumArtist)
        if (albumArtistNorm.isNotEmpty()) {
            for ((left, right) in splits) {
                if (normalizeForMatch(left) == albumArtistNorm) {
                    return Combined.Resolved(right, left, artistFirst = true, viaAnchor = false)
                }
                if (normalizeForMatch(right) == albumArtistNorm) {
                    return Combined.Resolved(left, right, artistFirst = false, viaAnchor = false)
                }
            }
        }

        if (splits.size == 1 && artistFirstHint != null) {
            val (left, right) = splits.single()
            return if (artistFirstHint) {
                Combined.Resolved(right, left, artistFirst = true, viaAnchor = false)
            } else {
                Combined.Resolved(left, right, artistFirst = false, viaAnchor = false)
            }
        }

        return Combined.Ambiguous(
            splits.flatMap { (left, right) ->
                val titleFirst = TitleArtist(title = left, artist = right)
                val artistFirst = TitleArtist(title = right, artist = left)
                if (artistFirstHint == true) listOf(artistFirst, titleFirst) else listOf(titleFirst, artistFirst)
            },
        )
    }

    private fun matchAnchor(splits: List<Pair<String, String>>, anchor: TrackAnchor): Combined.Resolved? {
        val anchorTitle = normalizeForMatch(anchor.title)
        val anchorArtist = normalizeForMatch(anchor.artist)
        if (anchorTitle.isEmpty()) return null
        for ((left, right) in splits) {
            val leftNorm = normalizeForMatch(left)
            val rightNorm = normalizeForMatch(right)
            if (leftNorm == anchorTitle && (anchorArtist.isEmpty() || rightNorm == anchorArtist)) {
                return Combined.Resolved(left, right, artistFirst = false, viaAnchor = true)
            }
            if (rightNorm == anchorTitle && (anchorArtist.isEmpty() || leftNorm == anchorArtist)) {
                return Combined.Resolved(right, left, artistFirst = true, viaAnchor = true)
            }
        }
        return null
    }

    /** 标题形如「歌名-歌手」或「歌手-歌名」，且歌手一段等于已知歌手时，返回去掉歌手后的标题。 */
    private fun stripArtistFromTitle(title: String, artistReferences: List<String>): String? {
        if (title.isEmpty()) return null
        val artists = artistReferences.map(::normalizeForMatch).filter(String::isNotEmpty)
        if (artists.isEmpty()) return null
        for ((left, right) in splitCandidates(title)) {
            if (normalizeForMatch(right) in artists) return left
            if (normalizeForMatch(left) in artists) return right
        }
        return null
    }

    private class TrackState {
        var last: SessionMetadataSample? = null
        var lastTitleNorm: String = ""
        var titleChanges: Int = 0
        var baselineTitle: String? = null
        var polluted: Boolean = false
        var token: Long = 0L

        /** 本曲目段最近一次已知的换曲判据字段；缺失的采样字段不覆盖它们。 */
        var knownMediaId: String = ""
        var knownDurationMs: Long = 0L
        var knownAlbum: String = ""

        fun rememberKnownFields(sample: SessionMetadataSample, reset: Boolean) {
            if (reset) {
                knownMediaId = ""
                knownDurationMs = 0L
                knownAlbum = ""
            }
            sample.mediaId?.trim()?.takeIf(String::isNotEmpty)?.let { knownMediaId = it }
            if (sample.durationMs > 0L) knownDurationMs = sample.durationMs
            normalizeForMatch(sample.album).takeIf(String::isNotEmpty)?.let { knownAlbum = it }
        }

        fun beginTrack(newToken: Boolean) {
            lastTitleNorm = ""
            titleChanges = 0
            baselineTitle = null
            polluted = false
            if (newToken) token = TOKEN_SEQUENCE.incrementAndGet()
        }
    }

    private class LruMap<K, V>(private val maxSize: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
    }

    companion object {
        /**
         * 曲目编号全进程单调递增，净化器重置或会话被淘汰后重新开始也不会复用旧编号，
         * 否则新曲会撞上时间轴里按旧键缓存的内容。
         */
        private val TOKEN_SEQUENCE = AtomicLong()

        private const val DEFAULT_MAX_SESSIONS = 16
        private const val MAX_FORMAT_HINTS = 64
        private const val DURATION_TOLERANCE_MS = 1_000L
        private const val TRACK_START_POSITION_MAX_MS = 3_000L
        private const val POSITION_RESET_MIN_BACKTRACK_MS = 5_000L

        /** 同一曲内标题变化次数达到该值才判定漂移，避免“占位标题→真标题”的一次性更新误判。 */
        private const val DRIFT_CHANGES_REQUIRED = 2

        private val SEPARATORS = charArrayOf('-', '–', '—', '―', '－', '‐', '‑', '|', '｜')

        /**
         * 比较用的规范形：NFKC、小写、只保留字母数字，忽略空白、标点与全半角差异。
         * 注意这是“可能相等”的判断，歌词行恰好等于歌名（副歌）时也会相等，所以
         * 它只能作为证据的一部分，不能单独证明污染。
         */
        fun normalizeForMatch(value: String?): String {
            if (value.isNullOrBlank()) return ""
            return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .filter(Char::isLetterOrDigit)
        }

        /**
         * 组合串 [combined]（会话歌手字段）能否由已知的歌名与歌手拼成，与顺序、分隔符无关。
         * 用于判断一首已匹配到歌词的歌是否就是这个被污染的会话，而不必知道污染的格式。
         */
        fun combinedExplains(combined: String?, title: String?, artist: String?): Boolean {
            val combinedNorm = normalizeForMatch(combined)
            val titleNorm = normalizeForMatch(title)
            val artistNorm = normalizeForMatch(artist)
            if (combinedNorm.isEmpty() || titleNorm.isEmpty() || artistNorm.isEmpty()) return false
            return combinedNorm == titleNorm + artistNorm || combinedNorm == artistNorm + titleNorm
        }

        /**
         * 按分隔符把组合串拆成（左, 右）。两侧带空白的分隔符（" - "）优先：它们不会把
         * `A-Lin`、`Jay-Z` 这类歌手名内部的连字符当作分界。
         */
        internal fun splitCandidates(value: String): List<Pair<String, String>> {
            val spaced = ArrayList<Pair<String, String>>()
            val tight = ArrayList<Pair<String, String>>()
            value.forEachIndexed { index, char ->
                if (char !in SEPARATORS) return@forEachIndexed
                val left = value.substring(0, index).trim()
                val right = value.substring(index + 1).trim()
                if (left.isEmpty() || right.isEmpty()) return@forEachIndexed
                val isSpaced = index > 0 && value[index - 1].isWhitespace() &&
                    index < value.lastIndex && value[index + 1].isWhitespace()
                (if (isSpaced) spaced else tight).add(left to right)
            }
            return spaced.ifEmpty { tight }
        }
    }
}
