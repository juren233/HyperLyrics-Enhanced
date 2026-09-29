package com.juren233.hyperlyricsenhanced.root

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.extensions.TimingNavigator
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.source.StateResetter
import com.juren233.hyperlyricsenhanced.lyric.view.InterludeTracker
import com.juren233.hyperlyricsenhanced.lyric.view.SongPreprocessor
import com.juren233.hyperlyricsenhanced.lyric.view.TimedLine
import com.juren233.hyperlyricsenhanced.lyric.view.TitleSlot
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import com.juren233.hyperlyricsenhanced.root.utils.DisplayDiagnosticLogger
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger

object LyriconDataBridge : StateResetter {

    private val playbackPositionEstimator = PlaybackPositionEstimator()

    val versionCounter = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var currentSong: Song? = null

    @Volatile
    var currentSongName: String? = null

    @Volatile
    var currentLyric: String? = null

    @Volatile
    var currentLyricLine: IRichLyricLine? = null

    @Volatile
    var currentNextLyricLine: IRichLyricLine? = null

    @Volatile
    private var currentUnmergedLyricLine: IRichLyricLine? = null

    @Volatile
    internal var currentInterludeType: InterludeTracker.Type? = null
        private set

    @Volatile
    var currentPosition: Long = 0L

    @Volatile
    var currentPlaybackState: Boolean? = null
        private set

    @Volatile
    var activePackageName: String? = null

    @Volatile
    var currentLyricPackageName: String? = null

    /** 是否处于纯文本模式（部分 Provider 通过 onSendText 推送） */
    @Volatile
    var isTextMode: Boolean = false

    /** AI 翻译完成后的回调，由 LyriconSource 设置 */
    var onAiTranslationComplete: (() -> Unit)? = null

    private val songChangedListeners =
        java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addSongChangedListener(listener: () -> Unit) {
        songChangedListeners.add(listener)
    }

    fun removeSongChangedListener(listener: () -> Unit) {
        songChangedListeners.remove(listener)
    }

    fun updateLyricPackage(packageName: String?) {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "package_update_begin",
            details = "incomingPackage=${MediaCardDiagnosticLogger.sanitize(packageName)}",
        )
        activePackageName = packageName
        currentLyricPackageName = packageName
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = if (packageName.isNullOrBlank()) "skipped" else "accepted",
            reason = if (packageName.isNullOrBlank()) "package_missing" else "package_updated",
        )
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "package_update_complete",
            details = "package=${MediaCardDiagnosticLogger.sanitize(packageName)}",
        )
    }

    @Volatile
    private var earlyNextLinePreviewMs: Long? = null

    fun configureEarlyNextLinePreview(mode: Int, customMs: Int) {
        earlyNextLinePreviewMs = when (mode) {
            1 -> 0L
            2 -> 100L
            3 -> 200L
            4 -> 300L
            5 -> 400L
            RootConstants.EARLY_NEXT_LINE_PREVIEW_CUSTOM -> customMs.coerceAtLeast(0).toLong()
            else -> null
        }
    }

    /** Select only the immediate successor; keep its original word and translation timing. */
    private fun earlyNextLinePreview(line: TimedLine?, position: Long): TimedLine? {
        val advanceMs = earlyNextLinePreviewMs ?: return null
        line ?: return null
        if (line.metadata?.getBoolean(SongPreprocessor.KEY_TITLE_LINE) == true ||
            line.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) == true ||
            line.end <= line.begin
        ) return null
        val next = line.next ?: return null
        if (next.begin <= line.begin || next.text.isNullOrBlank() ||
            next.metadata?.getBoolean(SongPreprocessor.KEY_TITLE_LINE) == true ||
            next.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) == true
        ) return null
        val previewStart = (line.end - advanceMs).coerceAtLeast(line.begin)
        return next.takeIf { position >= previewStart && position < next.begin }
    }

    private var timingNavigator: TimingNavigator<TimedLine> = TimingNavigator(emptyArray())
    private var unmergedTimingNavigator: TimingNavigator<TimedLine> = TimingNavigator(emptyArray())
    private var interludeTracker = InterludeTracker()
    private var currentInterlude: InterludeTracker.Interlude? = null
    private var currentInterludeLine: IRichLyricLine? = null

    fun updateSong(song: Song?) {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "song_update_begin",
            details = "incomingId=${MediaCardDiagnosticLogger.sanitize(song?.id)},incomingTitle=${MediaCardDiagnosticLogger.sanitize(song?.name)},incomingLines=${song?.lyrics.orEmpty().size}",
        )
        HookLogger.d("LyriconDataBridge", "歌曲变更: ${song?.name}")
        isTextMode = false
        currentSong = song
        currentSongName = song?.name
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        currentUnmergedLyricLine = null
        currentPosition = 0L
        playbackPositionEstimator.reset()
        currentInterludeType = null
        currentInterlude = null
        currentInterludeLine = null

        versionCounter.incrementAndGet()

        if (song != null) {
            prepareSong(song)
        } else {
            timingNavigator = TimingNavigator(emptyArray())
            unmergedTimingNavigator = TimingNavigator(emptyArray())
            interludeTracker = InterludeTracker()
        }
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = if (song == null) "cleared" else "accepted",
            reason = if (song == null) "song_cleared" else if (song.lyrics.isNullOrEmpty()) {
                "song_without_lyrics"
            } else {
                "song_updated"
            },
        )
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "song_update_complete",
            reason = if (song == null) "cleared" else "prepared",
            details = "incomingId=${MediaCardDiagnosticLogger.sanitize(song?.id)},version=${versionCounter.get()}",
        )
        if (song != null) {
            songChangedListeners.forEach { listener -> listener() }
        }
    }

    fun replaceSameSongContent(song: Song): Boolean {
        val previousSong = currentSong ?: return false
        if (!isSameSong(previousSong, song)) return false

        HookLogger.d("LyriconDataBridge", "同曲内容更新: ${song.name}")
        isTextMode = false
        currentSong = song
        currentSongName = song.name
        prepareSong(song)
        versionCounter.incrementAndGet()
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = "accepted",
            reason = "same_song_content_replaced",
        )
        return true
    }

    fun applyTranslation(translatedSong: Song) {
        currentSong = translatedSong
        prepareSong(translatedSong)
    }

    private fun prepareSong(song: Song) {
        val mergeOverlappingLyrics =
            currentLyricPackageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME
        val lines = SongPreprocessor(
            placeholder = TitleSlot.NAME_ARTIST,
            mergeOverlappingLyrics = mergeOverlappingLyrics,
        ).prepare(song)
        val unmergedLines = if (mergeOverlappingLyrics) {
            SongPreprocessor(
                placeholder = TitleSlot.NAME_ARTIST,
                mergeOverlappingLyrics = false,
            ).prepare(song)
        } else {
            lines
        }
        timingNavigator = TimingNavigator(lines.toTypedArray())
        unmergedTimingNavigator = TimingNavigator(unmergedLines.toTypedArray())
        interludeTracker = InterludeTracker(lines)
    }

    fun updatePosition(position: Long): Boolean {
        playbackPositionEstimator.update(position, monotonicTimeMs())
        val changed = applyPosition(position)
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "position_applied",
            details = "position=$position,lyricChanged=$changed",
            positionSample = true,
        )
        return changed
    }

    fun updateEstimatedPosition(position: Long): Boolean = applyPosition(position)

    fun estimatedPosition(): Long? =
        playbackPositionEstimator.estimate(monotonicTimeMs())

    /**
     * 预计显示行下一次可能变化的最早时刻（歌词时间轴毫秒），供位置轮询「到期唤醒」。
     * 行进、提前预览、间奏的切换点全部落在「当前行结束、下一行起点」及其预览提前量
     * 附近；这里保守取四者的最小值——宁可早醒一次空转再重排，不可晚醒漏切句。
     * 无歌词/纯文本模式返回 null，由调用方退回各自的慢档节拍。
     */
    fun nextDisplayChangeMs(position: Long): Long? {
        if (isTextMode) return null
        if (timingNavigator.size == 0) return null
        val found = timingNavigator.lineAtOrPrevious(position)
        val advanceMs = earlyNextLinePreviewMs ?: 0L
        var best: Long? = null
        fun consider(candidateMs: Long) {
            if (candidateMs > position && (best == null || candidateMs < best!!)) {
                best = candidateMs
            }
        }
        if (found == null) {
            val first = timingNavigator.source.firstOrNull() ?: return null
            consider(first.begin)
            if (advanceMs > 0) consider(first.begin - advanceMs)
        } else {
            consider(found.end)
            if (advanceMs > 0) consider(found.end - advanceMs)
            val next = found.next ?: return best
            consider(next.begin)
            if (advanceMs > 0) consider(next.begin - advanceMs)
        }
        return best
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "playback_state_update",
            details = "isPlaying=$isPlaying,previous=$currentPlaybackState",
        )
        currentPlaybackState = isPlaying
        playbackPositionEstimator.setPlaying(isPlaying, monotonicTimeMs())
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = "accepted",
            reason = "playback_state_updated",
        )
    }

    private fun monotonicTimeMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        // Local JVM tests use android.jar stubs; Android uses the suspend-aware clock above.
        System.nanoTime() / 1_000_000L
    }

    private fun applyPosition(position: Long): Boolean {
        currentPosition = position
        if (isTextMode) {
            DisplayDiagnosticLogger.log("BRIDGE", "skipped", "text_mode")
            return false
        }
        val song = currentSong ?: run {
            DisplayDiagnosticLogger.log("BRIDGE", "skipped", "no_song")
            return false
        }
        val lyrics = song.lyrics
        if (lyrics.isNullOrEmpty()) {
            DisplayDiagnosticLogger.log("BRIDGE", "skipped", "no_lyrics")
            return false
        }

        val foundLine = timingNavigator.lineAtOrPrevious(position)
        currentUnmergedLyricLine = unmergedTimingNavigator.lineAtOrPrevious(position)

        val previousLine = currentLyricLine
        val previousInterlude = currentInterlude
        val interlude = interludeTracker.evaluate(
            position,
            foundLine,
            previousInterlude,
            earlyNextLinePreviewMs,
        )
        // 长间奏（>=7s）整段归间奏指示器；提前预览只在间奏之外生效——
        // 短间隙直接预览，间奏尾部按提前量提前切到下一句。
        val previewLine = if (interlude == null) earlyNextLinePreview(foundLine, position) else null
        currentInterlude = interlude
        currentInterludeType = interlude?.type

        val displayLine = if (interlude != null) {
            if (interlude == previousInterlude) {
                currentInterludeLine
            } else {
                RichLyricLine(
                    begin = interlude.start,
                    end = interlude.end - 1L,
                    duration = interlude.duration,
                    metadata = lyricMetadataOf(
                        LyricMetadataKeys.INSTRUMENTAL to "true",
                        LyricMetadataKeys.INSTRUMENTAL_TYPE to interlude.type.name.lowercase()
                    ),
                    text = "•••",
                    words = emptyList()
                ).also { currentInterludeLine = it }
            }
        } else {
            currentInterludeLine = null
            previewLine ?: foundLine
        }

        currentLyricLine = displayLine
        currentNextLyricLine = interlude?.next ?: (previewLine ?: foundLine)?.next
        val newText = displayLine?.text ?: currentLyric ?: ""
        val changed = displayLine !== previousLine || newText != currentLyric

        currentLyric = newText
        if (changed) {
            DisplayDiagnosticLogger.log(
                channel = "BRIDGE",
                result = if (displayLine == null) "skipped" else "accepted",
                reason = if (displayLine == null) "no_line_for_position" else "line_changed",
            )
        }
        return changed
    }

    fun updateLyric(text: String?) {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "plain_text_update",
            details = "textLen=${text?.length ?: 0}",
        )
        isTextMode = true
        currentInterlude = null
        currentInterludeLine = null
        currentInterludeType = null
        currentLyric = text
        currentLyricLine = if (!text.isNullOrBlank()) {
            val lines = text.lines()
            RichLyricLine(
                text = lines.first(),
                translation = lines.getOrNull(1)
            )
        } else {
            null
        }
        currentUnmergedLyricLine = currentLyricLine
        currentNextLyricLine = null
    }

    fun updateLyricLine(line: IRichLyricLine) {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "lyric_line_update_begin",
            details = "line=${MediaCardDiagnosticLogger.identity(line)},begin=${line.begin},end=${line.end},textLen=${line.text?.length ?: 0}",
        )
        isTextMode = false
        currentInterlude = null
        currentInterludeLine = null
        currentInterludeType = null
        val preparedLine = findPreparedLine(line)
        val expectedLine = timingNavigator.findPreviousEntry(currentPosition)
        val callbackLine = preparedLine ?: line
        if (expectedLine != null && callbackLine.begin < expectedLine.begin) {
            DisplayDiagnosticLogger.log(
                "BRIDGE",
                "skipped",
                "stale_callback_line",
                extra = "callbackBegin=${callbackLine.begin}, expectedBegin=${expectedLine.begin}",
            )
            MediaCardDiagnosticLogger.log(
                stage = "bridge",
                event = "lyric_line_update_dropped",
                reason = "stale_callback_line",
                details = "callbackBegin=${callbackLine.begin},expectedBegin=${expectedLine.begin}",
            )
            return
        }
        if (preparedLine != null && currentPosition >= preparedLine.end) {
            DisplayDiagnosticLogger.log(
                "BRIDGE",
                "skipped",
                "expired_callback_line",
                extra = "callbackEnd=${preparedLine.end}",
            )
            MediaCardDiagnosticLogger.log(
                stage = "bridge",
                event = "lyric_line_update_dropped",
                reason = "expired_callback_line",
                details = "callbackEnd=${preparedLine.end},currentPosition=$currentPosition",
            )
            return
        }

        currentLyricLine = preparedLine ?: line
        currentUnmergedLyricLine = line
        currentNextLyricLine = preparedLine?.next
        currentLyric = currentLyricLine?.text
        // Provider callbacks can repeat the still-singing line after the preview boundary.
        // Resolve from the playback position again so a callback cannot undo the preview.
        if (earlyNextLinePreviewMs != null && preparedLine != null) {
            applyPosition(currentPosition)
        }
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = "accepted",
            reason = if (preparedLine == null) "callback_line_unmatched" else "callback_line_matched",
        )
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "lyric_line_update_complete",
            reason = if (preparedLine == null) "callback_line_unmatched" else "callback_line_matched",
            details = "currentBegin=${currentLyricLine?.begin},currentEnd=${currentLyricLine?.end}",
        )
    }

    override fun clearState() {
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "clear_begin",
            details = "oldSongId=${MediaCardDiagnosticLogger.sanitize(currentSong?.id)},oldPosition=$currentPosition,oldPlaying=$currentPlaybackState",
        )
        currentSong = null
        currentSongName = null
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        currentUnmergedLyricLine = null
        currentInterludeType = null
        currentInterlude = null
        currentInterludeLine = null
        currentPosition = 0L
        currentPlaybackState = null
        activePackageName = null
        currentLyricPackageName = null
        isTextMode = false
        timingNavigator = TimingNavigator(emptyArray())
        unmergedTimingNavigator = TimingNavigator(emptyArray())
        interludeTracker = InterludeTracker()
        playbackPositionEstimator.reset()
        DisplayDiagnosticLogger.clear("BRIDGE")

        versionCounter.incrementAndGet()
        MediaCardDiagnosticLogger.log(
            stage = "bridge",
            event = "clear_complete",
            details = "version=${versionCounter.get()}",
        )
    }

    private fun findPreparedLine(line: IRichLyricLine): TimedLine? {
        var matched: TimedLine? = null
        timingNavigator.forEachAt(line.begin) { candidate ->
            if (
                candidate.text == line.text ||
                    candidate.secondary == line.text
            ) {
                matched = candidate
            }
        }
        return matched
    }

    /**
     * Returns the current display group for the Island.
     *
     * The next-line preference controls only the optional preview rendered by
     * IslandSlotContentAssembler. It must not replace an Apple Music merged
     * current group with the unmerged line, otherwise concurrent vocals and
     * overlapping lines disappear when the preference is disabled.
     */
    fun currentLyricLineForIsland(nextLyricLineEnabled: Boolean): IRichLyricLine? =
        currentLyricLine

    private fun TimingNavigator<TimedLine>.lineAtOrPrevious(position: Long): TimedLine? =
        findPreviousEntry(position)

    private fun isSameSong(first: Song, second: Song): Boolean {
        val firstId = first.id?.takeIf { it.isNotBlank() }
        val secondId = second.id?.takeIf { it.isNotBlank() }
        if (firstId != null || secondId != null) {
            return firstId != null && secondId != null && firstId == secondId
        }
        return first.name?.trim()?.equals(second.name?.trim(), ignoreCase = true) == true &&
            first.artist?.trim()?.equals(second.artist?.trim(), ignoreCase = true) == true
    }

}
