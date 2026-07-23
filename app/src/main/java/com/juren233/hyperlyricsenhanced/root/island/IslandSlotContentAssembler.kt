package com.juren233.hyperlyricsenhanced.root.island

import android.content.Context
import android.content.SharedPreferences
import android.text.TextPaint
import android.view.View
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.RichLyricLineSplitter
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWord
import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.lyric.model.interfaces.IRichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW_ALIGNED_RIGHT
import com.juren233.hyperlyricsenhanced.lyric.view.METADATA_NEXT_LINE_PREVIEW_CENTERED
import com.juren233.hyperlyricsenhanced.lyric.view.RichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.isTitleLine
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.YoYoPresets
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.animateEntrance
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
    // Keep this across global refreshes so delayed fallback lyrics can detect the prior placeholder state.
    private val lastLyricAvailability = WeakHashMap<View, LyricAvailability>()

    private data class LyricAvailability(
        val songVersion: Int,
        val hasLyrics: Boolean
    )

    internal data class LyricDisplayOptions(
        val showTranslation: Boolean,
        val showRoma: Boolean
    )

    fun invalidate(view: View? = null) {
        if (view == null) {
            synchronized(lastContentSignatures) { lastContentSignatures.clear() }
            synchronized(lastStyleSignatures) { lastStyleSignatures.clear() }
            return
        }
        synchronized(lastContentSignatures) { lastContentSignatures.remove(view) }
        synchronized(lastStyleSignatures) { lastStyleSignatures.remove(view) }
        synchronized(lastLyricAvailability) { lastLyricAvailability.remove(view) }
    }

    fun configureView(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        mediaInfo: MediaMetadataHelper.MediaInfo = currentMediaInfo(view.context),
        force: Boolean = false
    ) {
        applyDynamicDisplayOptions(view, prefs, config)
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
            albumBitmap?.let(CoverColorHelper::artworkContentKey) ?: 0
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
                view.setStyle(style)
            }
            is SpaceGateRichLyricLineView -> {
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
            applyMetadataContent(view, config, mode, force, mediaInfo, suppressAnimation)
        }
    }

    fun applyLyricLineContent(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        lineOverride: IRichLyricLine?,
        playbackActive: Boolean = true
    ): Boolean {
        applyDynamicDisplayOptions(view, prefs, config)
        return applyLyricContent(
            view = view,
            prefs = prefs,
            config = config,
            lineOverride = lineOverride,
            force = false,
            playbackActive = playbackActive,
            suppressAnimation = false
        )
    }

    fun applyNextSongPreviewContent(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        isLeft: Boolean,
        nextSong: MediaMetadataHelper.MediaInfo,
        label: String,
        playbackActive: Boolean = true
    ): Boolean {
        configureView(view, prefs, config, mode = 5, force = true)
        val line = if (isLeft) {
            RichLyricLine(text = label, words = emptyList())
        } else {
            RichLyricLine(
                text = nextSong.title,
                words = emptyList(),
                secondary = nextSong.artist,
                secondaryWords = emptyList()
            )
        }
        val signature = listOf(
            "next-song",
            isLeft,
            nextSong.title,
            nextSong.artist,
            config.styleSignature
        ).joinToString("|")
        val contentChanged = hasViewLineContentChanged(view, line)
        if (lastContentSignatures[view] == signature && !contentChanged) return false

        applyContentUpdate(view, config, contentChanged = contentChanged) { target ->
            applyLineCentering(target, config.centerLyric)
            when (target) {
                is RichLyricLineView -> {
                    target.line = line
                    target.setPlaybackActive(playbackActive)
                    if (!isLeft) applyMetadataMarquee(target, config, force = true)
                }
                is SpaceGateRichLyricLineView -> {
                    target.line = line
                    target.setPlaybackActive(playbackActive)
                    if (!isLeft) applyMetadataMarquee(target, config, force = true)
                }
            }
        }
        lastContentSignatures[view] = signature
        return true
    }

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
            secondaryWords = buildBackgroundTranslationWords(source, backgroundTranslation)
        )
    }

    internal fun buildBackgroundTranslationWords(
        source: IRichLyricLine,
        translation: String
    ): List<LyricWord> {
        val timedWords = source.secondaryWords.orEmpty().mapNotNull { word ->
            val end = when {
                word.end > word.begin -> word.end
                word.duration > 0L -> word.begin + word.duration
                else -> return@mapNotNull null
            }
            if (word.begin < 0L || end <= word.begin) return@mapNotNull null
            word.begin to end
        }
        if (timedWords.isEmpty()) return emptyList()
        val begin = timedWords.minOf { it.first }
        val end = timedWords.maxOf { it.second }
        return listOf(
            LyricWord(
                text = translation,
                begin = begin,
                end = end,
                duration = end - begin
            )
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
        val contentChanged = hasViewLineContentChanged(view, targetLine)
        val lyricsJustBecameAvailable = recordLyricAvailability(view, targetLine)
        if (!force && lastContentSignatures[view] == signature && !contentChanged) {
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

        val willAnimateNextLinePromotion = when (view) {
            is RichLyricLineView -> view.willAnimateNextLinePromotion(targetLine)
            is SpaceGateRichLyricLineView -> view.willAnimateNextLinePromotion(targetLine)
            else -> false
        }
        val suppressContentAnimation = suppressAnimation ||
            (willAnimateNextLinePromotion && !lyricsJustBecameAvailable) ||
            view.parent == null ||
            !view.isAttachedToWindow
        applyContentUpdate(
            view = view,
            config = config,
            suppressAnimation = suppressContentAnimation,
            contentChanged = contentChanged,
            entranceOnly = lyricsJustBecameAvailable,
            update = applyLine
        )
        lastContentSignatures[view] = signature
        return true
    }

    private fun applyMetadataContent(
        view: View,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        force: Boolean,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        suppressAnimation: Boolean
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
        val contentChanged = hasViewLineContentChanged(view, newLine)
        if (!force && lastContentSignatures[view] == signature && !contentChanged) return false

        applyContentUpdate(view, config, suppressAnimation, contentChanged) { target ->
            applyLineCentering(target, config.centerLyric)
            when (target) {
                is RichLyricLineView -> {
                    target.line = newLine
                    applyMetadataMarquee(target, config)
                }
                is SpaceGateRichLyricLineView -> {
                    target.line = newLine
                    applyMetadataMarquee(target, config)
                }
            }
        }
        lastContentSignatures[view] = signature
        return true
    }

    private fun applyContentUpdate(
        view: View,
        config: IslandSlotRuntimeConfig,
        suppressAnimation: Boolean = false,
        contentChanged: Boolean = true,
        entranceOnly: Boolean = false,
        update: (View) -> Unit
    ) {
        val shouldAnimate = shouldAnimateContentUpdate(
            animationEnabled = config.lyricAnimationEnabled,
            suppressAnimation = suppressAnimation,
            contentChanged = contentChanged,
            attached = view.parent != null && view.isAttachedToWindow
        )
        if (!shouldAnimate) {
            update(view)
            return
        }
        val preset = YoYoPresets.getById(config.lyricAnimationId) ?: YoYoPresets.Default
        when (view) {
            is RichLyricLineView -> if (entranceOnly) {
                view.animateEntrance(preset) { update(this) }
            } else {
                view.animateUpdate(preset) { update(this) }
            }
            is SpaceGateRichLyricLineView -> if (entranceOnly) {
                view.animateEntrance(preset) { update(this) }
            } else {
                view.animateUpdate(preset) { update(this) }
            }
            else -> update(view)
        }
    }

    internal fun shouldAnimateContentUpdate(
        animationEnabled: Boolean,
        suppressAnimation: Boolean,
        contentChanged: Boolean,
        attached: Boolean
    ): Boolean = animationEnabled && !suppressAnimation && contentChanged && attached

    private fun applyPlaybackActive(view: View, playbackActive: Boolean) {
        when (view) {
            is RichLyricLineView -> view.setPlaybackActive(playbackActive)
            is SpaceGateRichLyricLineView -> view.setPlaybackActive(playbackActive)
        }
    }

    private fun applyDynamicDisplayOptions(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig
    ) {
        val options = resolveLyricDisplayOptions(
            translationDisabled = TranslationHelper.isTranslationDisabled(prefs),
            translationOnly = TranslationHelper.isTranslationOnly(prefs),
            nextLinePreview = isNextLinePreviewEnabled(prefs, config)
        )
        when (view) {
            is RichLyricLineView -> view.setDisplayOptions(options.showTranslation, options.showRoma)
            is SpaceGateRichLyricLineView -> view.setDisplayOptions(options.showTranslation, options.showRoma)
        }
    }

    internal fun resolveLyricDisplayOptions(
        translationDisabled: Boolean,
        translationOnly: Boolean,
        nextLinePreview: Boolean
    ): LyricDisplayOptions {
        val hideSecondaryContent = translationDisabled || nextLinePreview
        return LyricDisplayOptions(
            showTranslation = !hideSecondaryContent,
            showRoma = !hideSecondaryContent && !translationOnly
        )
    }

    private fun applyMetadataMarquee(
        view: RichLyricLineView,
        config: IslandSlotRuntimeConfig,
        force: Boolean = false
    ) {
        if (!force && !config.metadataMarqueeEnabled) return
        view.setMetadataMarqueeConfig(
            config.metadataMarqueeSpeed.toFloat(),
            config.metadataMarqueeDelay,
            config.metadataMarqueeLoopDelay,
            if (config.metadataMarqueeInfinite) -1 else 1,
            true
        )
        view.post { view.requestStartMarquee() }
    }

    private fun applyMetadataMarquee(
        view: SpaceGateRichLyricLineView,
        config: IslandSlotRuntimeConfig,
        force: Boolean = false
    ) {
        if (!force && !config.metadataMarqueeEnabled) return
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

    private fun hasViewLineContentChanged(view: View, targetLine: IRichLyricLine?): Boolean {
        val currentLine = when (view) {
            is RichLyricLineView -> view.line
            is SpaceGateRichLyricLineView -> view.line
            else -> null
        }
        return hasLineContentChanged(currentLine, targetLine)
    }

    private fun recordLyricAvailability(view: View, targetLine: IRichLyricLine?): Boolean {
        val songVersion = LyriconDataBridge.versionCounter.get()
        val hasLyrics = isActualLyricAvailable(
            sourceLine = LyriconDataBridge.currentLyricLine,
            targetLine = targetLine
        )
        val previousAvailability = synchronized(lastLyricAvailability) {
            val previous = lastLyricAvailability[view]
            val availability = when {
                previous?.songVersion == songVersion -> previous.hasLyrics
                previous?.hasLyrics == false -> false
                else -> null
            }
            lastLyricAvailability[view] = LyricAvailability(songVersion, hasLyrics)
            availability
        }
        return previousAvailability == false && hasLyrics
    }

    internal fun isActualLyricAvailable(
        sourceLine: IRichLyricLine?,
        targetLine: IRichLyricLine?
    ): Boolean = hasVisibleLyricContent(sourceLine) && hasVisibleLyricContent(targetLine)

    internal fun hasLineContentChanged(
        currentLine: IRichLyricLine?,
        targetLine: IRichLyricLine?
    ): Boolean = lineContentSignature(currentLine) != lineContentSignature(targetLine)

    internal fun isEmptyToPopulatedLyricTransition(
        currentLine: IRichLyricLine?,
        targetLine: IRichLyricLine?
    ): Boolean = !hasVisibleLyricContent(currentLine) && hasVisibleLyricContent(targetLine)

    private fun hasVisibleLyricContent(line: IRichLyricLine?): Boolean =
        line?.isTitleLine() != true && (
            !line?.text.isNullOrBlank() ||
            !line?.words.isNullOrEmpty() ||
            !line?.secondary.isNullOrBlank() ||
            !line?.secondaryWords.isNullOrEmpty() ||
            !line?.translation.isNullOrBlank() ||
            !line?.translationWords.isNullOrEmpty() ||
            !line?.roma.isNullOrBlank()
        )

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
