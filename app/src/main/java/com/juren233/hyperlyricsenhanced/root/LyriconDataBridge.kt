package com.juren233.hyperlyricsenhanced.root

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
    internal var currentInterludeType: InterludeTracker.Type? = null
        private set

    @Volatile
    var currentPosition: Long = 0L

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
        currentInterludeType = null
        currentInterlude = null
        currentInterludeLine = null

        versionCounter.incrementAndGet()

        if (song != null) {
            val processor = SongPreprocessor(TitleSlot.NAME_ARTIST)
            val lines = processor.prepare(song)
            timingNavigator = TimingNavigator(lines.toTypedArray())
            interludeTracker = InterludeTracker(lines)
        } else {
            timingNavigator = TimingNavigator(emptyArray())
            interludeTracker = InterludeTracker()
        }
    }

    fun applyTranslation(translatedSong: Song) {
        currentSong = translatedSong
        val processor = SongPreprocessor(TitleSlot.NAME_ARTIST)
        val lines = processor.prepare(translatedSong)
        timingNavigator = TimingNavigator(lines.toTypedArray())
        interludeTracker = InterludeTracker(lines)
    }

    fun updatePosition(position: Long): Boolean {
        currentPosition = position
        if (isTextMode) return false
        val song = currentSong ?: return false
        val lyrics = song.lyrics
        if (lyrics.isNullOrEmpty()) return false

        // 使用 TimingNavigator 高效定位当前歌词行
        var foundLine: TimedLine? = null
        timingNavigator.forEachAtOrPrevious(position) { timedLine ->
            foundLine = timedLine
        }

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
        currentNextLyricLine = null
    }

    fun updateLyricLine(line: IRichLyricLine) {
        isTextMode = false
        currentInterlude = null
        currentInterludeLine = null
        currentInterludeType = null
        currentLyricLine = line
        currentNextLyricLine = null
        currentLyric = line.text
    }

    override fun clearState() {
        currentSong = null
        currentSongName = null
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        currentInterludeType = null
        currentInterlude = null
        currentInterludeLine = null
        currentPosition = 0L
        activePackageName = null
        currentLyricPackageName = null
        isTextMode = false
        timingNavigator = TimingNavigator(emptyArray())
        interludeTracker = InterludeTracker()

        versionCounter.incrementAndGet()
    }

}
