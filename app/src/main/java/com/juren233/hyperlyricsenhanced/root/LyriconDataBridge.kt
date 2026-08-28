package com.juren233.hyperlyricsenhanced.root

import android.os.SystemClock
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
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaLyricSnapshotDraft
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaLyricSnapshotStore
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaLyricLineSnapshot
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaLyricSongIdentity

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
    var currentNextNextLyricLine: IRichLyricLine? = null

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

    @Synchronized
    fun updateLyricPackage(packageName: String?) {
        activePackageName = packageName
        currentLyricPackageName = packageName
        publishMediaLyricSnapshot()
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = if (packageName.isNullOrBlank()) "skipped" else "accepted",
            reason = if (packageName.isNullOrBlank()) "package_missing" else "package_updated",
        )
    }

    private var timingNavigator: TimingNavigator<TimedLine> = TimingNavigator(emptyArray())
    private var unmergedTimingNavigator: TimingNavigator<TimedLine> = TimingNavigator(emptyArray())
    private var interludeTracker = InterludeTracker()
    private var currentInterlude: InterludeTracker.Interlude? = null
    private var currentInterludeLine: IRichLyricLine? = null

    @Synchronized
    fun updateSong(song: Song?) {
        HookLogger.d("LyriconDataBridge", "歌曲变更: ${song?.name}")
        isTextMode = false
        currentSong = song
        currentSongName = song?.name
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        currentNextNextLyricLine = null
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
        publishMediaLyricSnapshot()
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = if (song == null) "cleared" else "accepted",
            reason = if (song == null) "song_cleared" else if (song.lyrics.isNullOrEmpty()) {
                "song_without_lyrics"
            } else {
                "song_updated"
            },
        )
    }

    @Synchronized
    fun replaceSameSongContent(song: Song): Boolean {
        val previousSong = currentSong ?: return false
        if (!isSameSong(previousSong, song)) return false

        HookLogger.d("LyriconDataBridge", "同曲内容更新: ${song.name}")
        isTextMode = false
        currentSong = song
        currentSongName = song.name
        prepareSong(song)
        versionCounter.incrementAndGet()
        publishMediaLyricSnapshot()
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = "accepted",
            reason = "same_song_content_replaced",
        )
        return true
    }

    @Synchronized
    fun applyTranslation(translatedSong: Song) {
        currentSong = translatedSong
        currentSongName = translatedSong.name
        prepareSong(translatedSong)
        versionCounter.incrementAndGet()
        publishMediaLyricSnapshot()
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

    @Synchronized
    fun updatePosition(position: Long): Boolean {
        playbackPositionEstimator.update(position, monotonicTimeMs())
        val changed = applyPosition(position)
        publishMediaLyricSnapshot()
        return changed
    }

    @Synchronized
    fun updateEstimatedPosition(position: Long): Boolean {
        val changed = applyPosition(position)
        publishMediaLyricSnapshot()
        return changed
    }

    fun estimatedPosition(): Long? =
        playbackPositionEstimator.estimate(monotonicTimeMs())

    @Synchronized
    fun updatePlaybackState(isPlaying: Boolean) {
        currentPlaybackState = isPlaying
        playbackPositionEstimator.setPlaying(isPlaying, monotonicTimeMs())
        publishMediaLyricSnapshot()
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
        currentNextNextLyricLine = interlude?.next?.next ?: foundLine?.next?.next
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

    @Synchronized
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
        currentNextNextLyricLine = null
        publishMediaLyricSnapshot()
    }

    @Synchronized
    fun updateLyricLine(line: IRichLyricLine) {
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
            return
        }
        if (preparedLine != null && currentPosition >= preparedLine.end) {
            DisplayDiagnosticLogger.log(
                "BRIDGE",
                "skipped",
                "expired_callback_line",
                extra = "callbackEnd=${preparedLine.end}",
            )
            return
        }

        currentLyricLine = preparedLine ?: line
        currentUnmergedLyricLine = line
        currentNextLyricLine = preparedLine?.next
        currentLyric = currentLyricLine?.text
        currentNextNextLyricLine = preparedLine?.next?.next
        DisplayDiagnosticLogger.log(
            channel = "BRIDGE",
            result = "accepted",
            reason = if (preparedLine == null) "callback_line_unmatched" else "callback_line_matched",
        )
    }

    @Synchronized
    override fun clearState() {
        currentSong = null
        currentSongName = null
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        currentNextNextLyricLine = null
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
        publishMediaLyricSnapshot()
        DisplayDiagnosticLogger.clear("BRIDGE")

        versionCounter.incrementAndGet()
    }

    @Synchronized
    fun updateMetadataTitle(title: String?) {
        if (title == null) return
        currentSongName = title
        publishMediaLyricSnapshot()
    }

    private fun publishMediaLyricSnapshot() {
        MediaLyricSnapshotStore.global.publish(
            MediaLyricSnapshotDraft(
                song = MediaLyricSongIdentity.from(currentSong)?.copy(
                    title = currentSongName ?: currentSong?.name,
                ),
                packageName = currentLyricPackageName,
                positionMs = currentPosition,
                isPlaying = currentPlaybackState,
                isTextMode = isTextMode,
                songHasDuet = currentSong?.lyrics.orEmpty().any { it.isAlignedRight },
                current = MediaLyricLineSnapshot.from(currentLyricLine),
                next = MediaLyricLineSnapshot.from(currentNextLyricLine),
                nextNext = MediaLyricLineSnapshot.from(currentNextNextLyricLine),
            )
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

    fun currentLyricLineForIsland(nextLyricLineEnabled: Boolean): IRichLyricLine? =
        if (nextLyricLineEnabled || currentInterlude != null) {
            currentLyricLine
        } else {
            currentUnmergedLyricLine ?: currentLyricLine
        }

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
