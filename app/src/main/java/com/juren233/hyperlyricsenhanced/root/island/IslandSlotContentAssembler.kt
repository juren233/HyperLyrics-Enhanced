package com.juren233.hyperlyricsenhanced.root.island

import android.content.Context
import android.content.SharedPreferences
import android.text.TextPaint
import android.view.View
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.RichLyricLineSplitter
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW_ALIGNED_RIGHT
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW_CENTERED
import com.juren233.hyperlyricsenhanced.lyric.view.RichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.YoYoPresets
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.animateUpdate
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.CoverColorHelper
import com.juren233.hyperlyricsenhanced.root.utils.LyricStyleHelper
import com.juren233.hyperlyricsenhanced.root.utils.TranslationHelper
import java.util.WeakHashMap

internal object IslandSlotContentAssembler {
    private val lastContentSignatures = WeakHashMap<View, String>()
    private val lastStyleSignatures = WeakHashMap<View, String>()

    fun invalidate(view: View? = null) {
        if (view == null) {
            synchronized(lastContentSignatures) { lastContentSignatures.clear() }
            synchronized(lastStyleSignatures) { lastStyleSignatures.clear() }
            return
        }
        synchronized(lastContentSignatures) { lastContentSignatures.remove(view) }
        synchronized(lastStyleSignatures) { lastStyleSignatures.remove(view) }
    }

    fun configureView(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        mediaInfo: MediaMetadataHelper.MediaInfo = currentMediaInfo(view.context),
        force: Boolean = false
    ) {
        val nextLinePreview = isNextLinePreviewEnabled(prefs, config)
        val disableAll = TranslationHelper.isTranslationDisabled(prefs) || nextLinePreview
        val translationOnly = TranslationHelper.isTranslationOnly(prefs)
        val lyricSong = LyriconDataBridge.currentSong
        val lyricTitle = lyricSong?.name?.takeIf { it.isNotBlank() }
            ?: LyriconDataBridge.currentSongName?.takeIf { it.isNotBlank() }
        val lyricArtist = lyricSong?.artist?.takeIf { it.isNotBlank() }
        val mediaColorKey = CoverColorHelper.updateMediaSession(
            packageName = LyriconDataBridge.currentLyricPackageName.orEmpty(),
            title = lyricTitle ?: mediaInfo.title,
            artist = lyricArtist ?: mediaInfo.artist,
            album = mediaInfo.album
        )
        val albumBitmap = mediaInfo.albumArt.takeUnless {
            lyricTitle != null &&
                mediaInfo.title.isNotBlank() &&
                !normalizeMediaText(lyricTitle).contains(normalizeMediaText(mediaInfo.title)) &&
                !normalizeMediaText(mediaInfo.title).contains(normalizeMediaText(lyricTitle))
        }
        val signature = listOf(
            config.styleSignature,
            mode,
            mediaColorKey,
            mediaInfo.title,
            mediaInfo.artist,
            mediaInfo.album,
            albumBitmap?.generationId ?: 0
        ).joinToString("|")

        if (!force && lastStyleSignatures[view] == signature) return
        val style = LyricStyleHelper.buildStyle(
            prefs = prefs,
            res = view.resources,
            mode = mode,
            albumBitmap = albumBitmap,
            mediaColorKey = mediaColorKey
        )
        when (view) {
            is RichLyricLineView -> {
                view.displayTranslation = !disableAll
                view.displayRoma = !disableAll && !translationOnly
                view.setStyle(style)
            }
            is SpaceGateRichLyricLineView -> {
                view.displayTranslation = !disableAll
                view.displayRoma = !disableAll && !translationOnly
                view.setStyle(style)
            }
        }
        lastStyleSignatures[view] = signature
    }

    private fun normalizeMediaText(value: String): String {
        return value.trim().lowercase().filterNot(Char::isWhitespace)
    }

    fun applySlotContent(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        lineOverride: IRichLyricLine? = null,
        force: Boolean = false,
        playbackActive: Boolean = true,
        suppressAnimation: Boolean = false,
        mediaInfo: MediaMetadataHelper.MediaInfo = currentMediaInfo(view.context)
    ): Boolean {
        configureView(view, prefs, config, mode, mediaInfo, force)
        return if (mode == 7) {
            applyLyricContent(view, prefs, config, lineOverride, force, playbackActive, suppressAnimation)
        } else {
            applyMetadataContent(view, config, mode, force, mediaInfo)
        }
    }

    fun applyLyricLineContent(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        lineOverride: IRichLyricLine?,
        playbackActive: Boolean = true
    ): Boolean = applyLyricContent(
        view = view,
        prefs = prefs,
        config = config,
        lineOverride = lineOverride,
        force = false,
        playbackActive = playbackActive,
        suppressAnimation = false
    )

    fun buildAdjacentTranslationLine(
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        isLeft: Boolean
    ): IRichLyricLine? {
        if (!config.adjacentBackgroundTranslation ||
            !config.supportsAdjacentBackgroundTranslation ||
            config.adjacentTranslationTargetIsLeft != isLeft
        ) {
            return null
        }
        if (TranslationHelper.isTranslationDisabled(prefs) ||
            TranslationHelper.isTranslationOnly(prefs) ||
            TranslationHelper.isSwapTranslation(prefs)
        ) {
            return null
        }

        val source = LyriconDataBridge.currentLyricLine ?: return null
        val mainTranslation = source.translation?.takeIf { it.isNotBlank() } ?: return null
        val backgroundTranslation = source.metadata
            ?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val hasBackgroundVocals = !source.secondary.isNullOrBlank() ||
            !source.secondaryWords.isNullOrEmpty()
        if (!hasBackgroundVocals) return null

        return RichLyricLine(
            begin = source.begin,
            end = source.end,
            duration = source.duration,
            isAlignedRight = source.isAlignedRight,
            metadata = source.metadata,
            text = mainTranslation,
            words = emptyList(),
            secondary = backgroundTranslation,
            secondaryWords = emptyList()
        )
    }

    fun buildSlotLyricLine(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        isLeft: Boolean
    ): IRichLyricLine? {
        val rawLine = processedRawLine(prefs, config)
        if (!config.isSplitMode || rawLine == null) return rawLine
        if (rawLine.text.isNullOrEmpty()) return rawLine

        val density = view.resources.displayMetrics.density
        val leftMaxPx = config.leftMaxWidthDp * density
        val centerCurrentLine = shouldCenterLine(config, rawLine)
        val textPaint = TextPaint().apply {
            textSize = config.textSizeSp.toFloat() * density
        }
        val splitPx = if (centerCurrentLine) {
            val textWidth = textPaint.measureText(rawLine.text ?: "")
            (textWidth / 2f).coerceAtMost(leftMaxPx)
        } else {
            leftMaxPx
        }
        val splitResult = RichLyricLineSplitter.split(
            rawLine,
            textPaint,
            splitPx,
            config.textSizeRatio,
            centerCurrentLine
        )
        return if (isLeft) splitResult.left else splitResult.right
    }

    fun processedRawLine(prefs: SharedPreferences, config: IslandSlotRuntimeConfig? = null): IRichLyricLine? {
        val songName = LyriconDataBridge.currentSongName?.takeIf { it.isNotEmpty() } ?: ""
        var rawLine = LyriconDataBridge.currentLyricLine
            ?: RichLyricLine(text = songName, words = emptyList())

        if (config != null && isNextLinePreviewEnabled(prefs, config, rawLine)) {
            val nextLine = LyriconDataBridge.currentNextLyricLine
            return rawLine.withNextLinePreview(
                nextLine = nextLine,
                centerNextLine = shouldCenterLine(config, nextLine)
            )
        }

        if (TranslationHelper.isTranslationOnly(prefs)) {
            rawLine = TranslationHelper.applyTranslationOnly(rawLine)
        } else if (TranslationHelper.isSwapTranslation(prefs)) {
            rawLine = TranslationHelper.swapTranslation(rawLine)
        }
        return rawLine
    }

    private fun applyLyricContent(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        lineOverride: IRichLyricLine?,
        force: Boolean,
        playbackActive: Boolean,
        suppressAnimation: Boolean
    ): Boolean {
        val targetLine = lineOverride ?: buildSlotLyricLine(
            view = view,
            prefs = prefs,
            config = config,
            isLeft = view.tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        )
        val centerCurrentLine = shouldCenterLine(config, targetLine)
        val isNextLinePreview = targetLine?.metadata?.getBoolean(
            METADATA_NEXT_LINE_PREVIEW
        ) == true
        val centerSecondaryLine = if (isNextLinePreview) {
            targetLine.metadata?.getBoolean(
                METADATA_NEXT_LINE_PREVIEW_CENTERED,
                centerCurrentLine
            ) ?: centerCurrentLine
        } else {
            centerCurrentLine
        }
        val signature = "lyric|${lineContentSignature(targetLine)}|${config.styleSignature}"
        if (!force && lastContentSignatures[view] == signature) {
            applyLineCentering(view, centerCurrentLine, centerSecondaryLine)
            applyPlaybackActive(view, playbackActive)
            return false
        }

        val applyLine: (View) -> Unit = { target ->
            applyLineCentering(target, centerCurrentLine, centerSecondaryLine)
            when (target) {
                is RichLyricLineView -> {
                    target.line = targetLine
                    target.setPlaybackActive(playbackActive)
                    if (config.lyricMarqueeEnabled) target.post { target.requestStartMarquee() }
                }
                is SpaceGateRichLyricLineView -> {
                    target.line = targetLine
                    target.setPlaybackActive(playbackActive)
                    if (config.lyricMarqueeEnabled) target.post { target.requestStartMarquee() }
                }
            }
        }

        val hasDisplayedNextLinePreview = when (view) {
            is RichLyricLineView -> view.isShowingNextLinePreview
            is SpaceGateRichLyricLineView -> view.isShowingNextLinePreview
            else -> false
        }
        val suppressContentAnimation = suppressAnimation ||
            isNextLinePreviewEnabled(prefs, config) ||
            hasDisplayedNextLinePreview ||
            view.parent == null ||
            !view.isAttachedToWindow
        if (config.lyricAnimationEnabled && !suppressContentAnimation) {
            val preset = YoYoPresets.getById(config.lyricAnimationId) ?: YoYoPresets.Default
            when (view) {
                is RichLyricLineView -> view.animateUpdate(preset) { applyLine(this) }
                is SpaceGateRichLyricLineView -> view.animateUpdate(preset) { applyLine(this) }
                else -> applyLine(view)
            }
        } else {
            applyLine(view)
        }
        lastContentSignatures[view] = signature
        return true
    }

    private fun applyMetadataContent(
        view: View,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        force: Boolean,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ): Boolean {
        val songName = LyriconDataBridge.currentSongName?.takeIf { it.isNotEmpty() } ?: mediaInfo.title
        val artistName = mediaInfo.artist
        val albumName = mediaInfo.album

        val signature = listOf(
            "metadata",
            mode,
            songName,
            artistName,
            albumName,
            config.metadataMarqueeEnabled,
            config.metadataMarqueeSpeed,
            config.metadataMarqueeDelay,
            config.metadataMarqueeLoopDelay,
            config.metadataMarqueeInfinite
        ).joinToString("|")
        if (!force && lastContentSignatures[view] == signature) return false

        val singleModeText = when (mode) {
            1 -> songName
            2 -> artistName
            3 -> albumName
            4 -> "$songName - $artistName"
            else -> ""
        }
        val newLine = when (mode) {
            1, 2, 3, 4 -> RichLyricLine(text = singleModeText, words = emptyList())
            5 -> RichLyricLine(text = songName, words = emptyList(), secondary = artistName, secondaryWords = emptyList())
            6 -> {
                val secondary = if (albumName.isEmpty()) artistName else "$artistName - $albumName"
                RichLyricLine(text = songName, words = emptyList(), secondary = secondary, secondaryWords = emptyList())
            }
            else -> null
        }

        applyLineCentering(view, config.centerLyric)

        when (view) {
            is RichLyricLineView -> {
                view.line = newLine
                applyMetadataMarquee(view, config)
            }
            is SpaceGateRichLyricLineView -> {
                view.line = newLine
                applyMetadataMarquee(view, config)
            }
        }
        lastContentSignatures[view] = signature
        return true
    }

    private fun applyPlaybackActive(view: View, playbackActive: Boolean) {
        when (view) {
            is RichLyricLineView -> view.setPlaybackActive(playbackActive)
            is SpaceGateRichLyricLineView -> view.setPlaybackActive(playbackActive)
        }
    }

    private fun applyMetadataMarquee(view: RichLyricLineView, config: IslandSlotRuntimeConfig) {
        if (!config.metadataMarqueeEnabled) return
        view.setMetadataMarqueeConfig(
            config.metadataMarqueeSpeed.toFloat(),
            config.metadataMarqueeDelay,
            config.metadataMarqueeLoopDelay,
            if (config.metadataMarqueeInfinite) -1 else 1,
            true
        )
        view.post { view.requestStartMarquee() }
    }

    private fun applyMetadataMarquee(view: SpaceGateRichLyricLineView, config: IslandSlotRuntimeConfig) {
        if (!config.metadataMarqueeEnabled) return
        view.setMetadataMarqueeConfig(
            config.metadataMarqueeSpeed.toFloat(),
            config.metadataMarqueeDelay,
            config.metadataMarqueeLoopDelay,
            if (config.metadataMarqueeInfinite) -1 else 1,
            true
        )
        view.post { view.requestStartMarquee() }
    }

    private fun currentMediaInfo(context: Context): MediaMetadataHelper.MediaInfo {
        val targetPkg = LyriconDataBridge.currentLyricPackageName ?: ""
        return MediaMetadataHelper.getMediaInfo(context, targetPkg, HookLogger)
    }

    private fun lineContentSignature(line: IRichLyricLine?): Int {
        if (line == null) return 0
        return listOf(
            line.begin,
            line.end,
            line.duration,
            line.text,
            line.words,
            line.secondary,
            line.secondaryWords,
            line.translation,
            line.translationWords,
            line.roma,
            line.isAlignedRight,
            line.metadata
        ).hashCode()
    }

    private fun shouldCenterLine(
        config: IslandSlotRuntimeConfig,
        line: IRichLyricLine?
    ): Boolean = config.centerLyric || (
        config.centerGroupVocals &&
            line?.metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true
        )

    private fun applyLineCentering(
        view: View,
        centerMain: Boolean,
        centerSecondary: Boolean = centerMain
    ) {
        when (view) {
            is RichLyricLineView -> {
                view.setLineCentering(centerMain, centerSecondary)
            }
            is SpaceGateRichLyricLineView -> {
                view.setLineCentering(centerMain, centerSecondary)
            }
        }
    }

    private fun isNextLinePreviewEnabled(
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        currentLine: IRichLyricLine? = LyriconDataBridge.currentLyricLine
    ): Boolean {
        if (!config.nextLyricLine || config.isSplitMode) return false
        if (LyriconDataBridge.isTextMode) return false
        val source = prefs.getString(RootConstants.KEY_HOOK_LYRIC_SOURCE, RootConstants.DEFAULT_HOOK_LYRIC_SOURCE)
        if (source != "lyricon" && source != "lyricinfo") return false
        return shouldUseNextLinePreview(config.autoSwitchTranslation, currentLine)
    }

    internal fun shouldUseNextLinePreview(
        autoSwitchTranslation: Boolean,
        currentLine: IRichLyricLine?
    ): Boolean {
        if (!autoSwitchTranslation) return true
        val hasTranslation = !currentLine?.translation.isNullOrBlank() ||
            !currentLine?.translationWords.isNullOrEmpty()
        val hasSecondary = !currentLine?.secondary.isNullOrBlank() ||
            !currentLine?.secondaryWords.isNullOrEmpty()
        return !hasTranslation && !hasSecondary
    }

    private fun IRichLyricLine.withNextLinePreview(
        nextLine: IRichLyricLine?,
        centerNextLine: Boolean
    ): IRichLyricLine {
        val nextText = nextLine?.text?.takeIf { it.isNotBlank() }
        return RichLyricLine(
            begin = begin,
            end = end,
            duration = duration,
            isAlignedRight = isAlignedRight,
            metadata = lyricMetadataOf(
                *(metadata?.entries?.map { it.key to it.value } ?: emptyList()).toTypedArray(),
                METADATA_NEXT_LINE_PREVIEW to "true",
                METADATA_NEXT_LINE_PREVIEW_ALIGNED_RIGHT to nextLine?.isAlignedRight.toString(),
                METADATA_NEXT_LINE_PREVIEW_CENTERED to centerNextLine.toString()
            ),
            text = text,
            words = words,
            secondary = nextText,
            secondaryWords = emptyList(),
            translation = null,
            translationWords = null,
            roma = null
        )
    }
}
