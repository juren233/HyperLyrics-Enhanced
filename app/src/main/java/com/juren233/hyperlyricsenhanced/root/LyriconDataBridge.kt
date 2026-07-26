package com.juren233.hyperlyricsenhanced.root

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.lyric.source.StateResetter
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.extensions.TimingNavigator
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.InterludeTracker
import com.juren233.hyperlyricsenhanced.lyric.view.SongPreprocessor
import com.juren233.hyperlyricsenhanced.lyric.view.TimedLine
import com.juren233.hyperlyricsenhanced.lyric.view.TitleSlot
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf

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

    /** 是否处于纯文本模式（椒盐音乐等通过 onSendText 推送） */
    @Volatile
    var isTextMode: Boolean = false

    /** AI 翻译完成后的回调，由 LyriconSource 设置 */
    var onAiTranslationComplete: (() -> Unit)? = null

    fun updateLyricPackage(packageName: String?) {
        activePackageName = packageName
        currentLyricPackageName = packageName
    }

    private var timingNavigator: TimingNavigator<TimedLine> = TimingNavigator(emptyArray())
    private var unmergedTimingNavigator: TimingNavigator<TimedLine> = TimingNavigator(emptyArray())
    private var interludeTracker = InterludeTracker()
    private var currentInterlude: InterludeTracker.Interlude? = null
    private var currentInterludeLine: IRichLyricLine? = null

    fun updateSong(song: Song?) {
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
            val lines = SongPreprocessor(TitleSlot.NAME_ARTIST).prepare(song)
            val unmergedLines = SongPreprocessor(
                placeholder = TitleSlot.NAME_ARTIST,
                mergeOverlappingLyrics = false
            ).prepare(song)
            timingNavigator = TimingNavigator(lines.toTypedArray())
            unmergedTimingNavigator = TimingNavigator(unmergedLines.toTypedArray())
            interludeTracker = InterludeTracker(lines)
        } else {
            timingNavigator = TimingNavigator(emptyArray())
            unmergedTimingNavigator = TimingNavigator(emptyArray())
            interludeTracker = InterludeTracker()
        }
    }

    fun applyTranslation(translatedSong: Song) {
        currentSong = translatedSong
        val lines = SongPreprocessor(TitleSlot.NAME_ARTIST).prepare(translatedSong)
        val unmergedLines = SongPreprocessor(
            placeholder = TitleSlot.NAME_ARTIST,
            mergeOverlappingLyrics = false
        ).prepare(translatedSong)
        timingNavigator = TimingNavigator(lines.toTypedArray())
        unmergedTimingNavigator = TimingNavigator(unmergedLines.toTypedArray())
        interludeTracker = InterludeTracker(lines)
    }

    fun updatePosition(position: Long): Boolean {
        playbackPositionEstimator.update(position, monotonicTimeMs())
        return applyPosition(position)
    }

    fun updateEstimatedPosition(position: Long): Boolean = applyPosition(position)

    fun estimatedPosition(): Long? =
        playbackPositionEstimator.estimate(monotonicTimeMs())

    fun updatePlaybackState(isPlaying: Boolean) {
        currentPlaybackState = isPlaying
        playbackPositionEstimator.setPlaying(isPlaying, monotonicTimeMs())
    }

    private fun monotonicTimeMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        // Local JVM tests use android.jar stubs; Android uses the suspend-aware clock above.
        System.nanoTime() / 1_000_000L
    }

    private fun applyPosition(position: Long): Boolean {
        currentPosition = position
        if (isTextMode) return false
        val song = currentSong ?: return false
        val lyrics = song.lyrics
        if (lyrics.isNullOrEmpty()) return false

        val foundLine = timingNavigator.lineAtOrPrevious(position)
        currentUnmergedLyricLine = unmergedTimingNavigator.lineAtOrPrevious(position)

        val previousLine = currentLyricLine
        val previousInterlude = currentInterlude
        val interlude = interludeTracker.evaluate(position, foundLine, previousInterlude)
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
            foundLine
        }

        currentLyricLine = displayLine
        currentNextLyricLine = interlude?.next ?: foundLine?.next
        val newText = displayLine?.text ?: currentLyric ?: ""
        val changed = displayLine !== previousLine || newText != currentLyric

        currentLyric = newText
        return changed
    }

    fun updateLyric(text: String?) {
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
        isTextMode = false
        currentInterlude = null
        currentInterludeLine = null
        currentInterludeType = null
        val preparedLine = findPreparedLine(line)
        val expectedLine = timingNavigator.findPreviousEntry(currentPosition)
        val callbackLine = preparedLine ?: line
        if (expectedLine != null && callbackLine.begin < expectedLine.begin) return
        if (preparedLine != null && currentPosition >= preparedLine.end) return

        currentLyricLine = preparedLine ?: line
        currentUnmergedLyricLine = line
        currentNextLyricLine = preparedLine?.next
        currentLyric = currentLyricLine?.text
    }

    override fun clearState() {
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

        versionCounter.incrementAndGet()
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

    fun currentLyricLineForIsland(nextLyricLineEnabled: Boolean): IRichLyricLine? =
        if (nextLyricLineEnabled || currentInterlude != null) {
            currentLyricLine
        } else {
            currentUnmergedLyricLine ?: currentLyricLine
        }

    private fun TimingNavigator<TimedLine>.lineAtOrPrevious(position: Long): TimedLine? =
        findPreviousEntry(position)

}
