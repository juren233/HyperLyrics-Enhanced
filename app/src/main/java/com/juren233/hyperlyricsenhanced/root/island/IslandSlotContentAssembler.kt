package com.juren233.hyperlyricsenhanced.root.island

import android.content.Context
import android.content.SharedPreferences
import android.text.TextPaint
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.IslandLyricPosition
import com.juren233.hyperlyricsenhanced.common.lyric.CjkLyricWhitespacePolicy
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
import com.juren233.hyperlyricsenhanced.lyric.view.LyricViewStyle
import com.juren233.hyperlyricsenhanced.lyric.view.isTitleLine
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.YoYoPresets
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.animateEntrance
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.animateUpdate
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.CoverColorHelper
import com.juren233.hyperlyricsenhanced.root.utils.CoverColorDiagnostics
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.LyricStyleHelper
import com.juren233.hyperlyricsenhanced.root.utils.TranslationHelper
import java.util.WeakHashMap

internal object IslandSlotContentAssembler {
    private val lastContentSignatures = WeakHashMap<View, String>()
    private val lastStyleSignatures = WeakHashMap<View, String>()
    // Keep the applied value across global invalidations so equal refreshes do not rebind style.
    private val lastAppliedStyles = WeakHashMap<View, LyricViewStyle>()
    // Keep this across global refreshes so delayed fallback lyrics can detect the prior placeholder state.
    private val lastLyricAvailability = WeakHashMap<View, LyricAvailability>()

    private data class LyricAvailability(
        val songVersion: Int,
        val hasLyrics: Boolean
    )

    internal data class LyricDisplayOptions(
        val displayMode: Int = RootConstants.DEFAULT_HOOK_TRANSLATION_PRONUNCIATION_DISPLAY,
        val fallback: Boolean = RootConstants.DEFAULT_HOOK_TRANSLATION_PRONUNCIATION_FALLBACK,
        val hideSecondaryContent: Boolean = false,
        val showTranslation: Boolean = false,
        val showRoma: Boolean = false
    )

    fun invalidate(view: View? = null) {
        if (view == null) {
            synchronized(lastContentSignatures) { lastContentSignatures.clear() }
            synchronized(lastStyleSignatures) { lastStyleSignatures.clear() }
            return
        }
        synchronized(lastContentSignatures) { lastContentSignatures.remove(view) }
        synchronized(lastStyleSignatures) { lastStyleSignatures.remove(view) }
        synchronized(lastAppliedStyles) { lastAppliedStyles.remove(view) }
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
            title = mediaInfo.title,
            artist = mediaInfo.artist,
            album = mediaInfo.album,
            stableTitle = lyricTitle,
            stableArtist = lyricArtist,
            diagnosticSource = "island_text"
        )
        val artworkRejectedForTitleMismatch = mediaInfo.albumArt != null &&
            shouldRejectArtworkForTitleMismatch(
                lyricTitle = lyricTitle,
                mediaTitle = mediaInfo.title,
                lyricArtist = lyricArtist,
                mediaArtist = mediaInfo.artist,
                mediaAlbum = mediaInfo.album
            )
        val albumBitmap = mediaInfo.albumArt.takeUnless {
            artworkRejectedForTitleMismatch
        }
        val artworkContentKey = albumBitmap?.let(CoverColorHelper::artworkContentKey) ?: 0
        val signature = buildStyleCacheSignature(
            styleSignature = config.styleSignature,
            mode = mode,
            mediaColorKey = mediaColorKey,
            artworkContentKey = artworkContentKey
        )

        val previousSignature = lastStyleSignatures[view]
        val diagnosticInput = if (BuildConfig.DEBUG) {
            CoverColorDiagnostics.StyleInput(
                force = force,
                mode = mode,
                songVersion = LyriconDataBridge.versionCounter.get(),
                lyricSongId = lyricSong?.id,
                lyricTitle = lyricTitle,
                lyricArtist = lyricArtist,
                packageName = LyriconDataBridge.currentLyricPackageName.orEmpty(),
                mediaTitle = mediaInfo.title,
                mediaArtist = mediaInfo.artist,
                mediaAlbum = mediaInfo.album,
                mediaKey = mediaColorKey,
                artworkState = when {
                    mediaInfo.albumArt == null -> "missing"
                    artworkRejectedForTitleMismatch -> "rejected_title_mismatch"
                    else -> "accepted"
                },
                artworkDescription = mediaInfo.albumArt?.let { artwork ->
                    "size=${artwork.width}x${artwork.height},generation=${artwork.generationId}," +
                        "identity=${System.identityHashCode(artwork).toUInt().toString(16)}," +
                        "acceptedContent=${artworkContentKey.toUInt().toString(16)}," +
                        "recycled=${artwork.isRecycled},source=${mediaInfo.artworkSource}"
                } ?: "none",
                signature = signature,
                previousSignature = previousSignature
            )
        } else {
            null
        }

        if (!force && previousSignature == signature) {
            diagnosticInput?.let { CoverColorDiagnostics.logStyleUnchanged(view, it) }
            return
        }
        val buildResult = LyricStyleHelper.buildStyleWithDiagnostics(
            prefs = prefs,
            res = view.resources,
            mode = mode,
            albumBitmap = albumBitmap,
            mediaColorKey = mediaColorKey
        )
        val style = buildResult.style
        val styleChanged = synchronized(lastAppliedStyles) {
            lastAppliedStyles[view] != style
        }
        if (styleChanged) {
            when (view) {
                is RichLyricLineView -> {
                    view.setStyle(style)
                }
                is SpaceGateRichLyricLineView -> {
                    view.setStyle(style)
                }
            }
        }
        synchronized(lastAppliedStyles) { lastAppliedStyles[view] = style }
        lastStyleSignatures[view] = signature
        diagnosticInput?.let { input ->
            if (styleChanged) {
                CoverColorDiagnostics.logStyleApplied(view, input, buildResult.colorResolution)
            } else {
                CoverColorDiagnostics.logStyleUnchanged(view, input)
            }
        }
    }

    internal fun buildStyleCacheSignature(
        styleSignature: String,
        mode: Int,
        mediaColorKey: String,
        artworkContentKey: Int
    ): String = listOf(
        styleSignature,
        mode,
        mediaColorKey,
        artworkContentKey
    ).joinToString("|")

    private fun normalizeMediaText(value: String): String {
        return value.trim().lowercase().filterNot(Char::isWhitespace)
    }

    internal fun shouldRejectArtworkForTitleMismatch(
        lyricTitle: String?,
        mediaTitle: String,
        lyricArtist: String? = null,
        mediaArtist: String = "",
        mediaAlbum: String = ""
    ): Boolean {
        if (lyricTitle.isNullOrBlank() || mediaTitle.isBlank()) return false
        val normalizedLyricTitle = normalizeMediaText(lyricTitle)
        val normalizedMediaTitle = normalizeMediaText(mediaTitle)
        if (normalizedLyricTitle.contains(normalizedMediaTitle) ||
            normalizedMediaTitle.contains(normalizedLyricTitle)
        ) {
            return false
        }

        // Some players publish the current lyric sentence as MediaSession.title. The
        // stable artist/album metadata still identifies the same track in that case.
        val normalizedLyricArtist = lyricArtist?.let(::normalizeMediaText).orEmpty()
        val normalizedMediaArtist = normalizeMediaText(mediaArtist)
        if (normalizedLyricArtist.isNotEmpty() && normalizedMediaArtist.isNotEmpty() &&
            (normalizedMediaArtist.contains(normalizedLyricArtist) ||
                normalizedLyricArtist.contains(normalizedMediaArtist))
        ) {
            return false
        }
        val normalizedMediaAlbum = normalizeMediaText(mediaAlbum)
        if (normalizedMediaAlbum.isNotEmpty() &&
            (normalizedMediaAlbum.contains(normalizedLyricTitle) ||
                normalizedLyricTitle.contains(normalizedMediaAlbum))
        ) {
            return false
        }
        return true
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

    fun applyFullNextSongPreviewContent(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        isLeft: Boolean,
        nextSong: MediaMetadataHelper.MediaInfo,
        label: String,
        playbackActive: Boolean = true
    ): Boolean {
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
        return applyNextSongPreviewLine(
            view = view,
            prefs = prefs,
            config = config,
            line = line,
            signaturePrefix = "next-song-full",
            signatureParts = listOf(isLeft, nextSong.title, nextSong.artist),
            marquee = !isLeft,
            playbackActive = playbackActive
        )
    }

    fun applyHalfNextSongPreviewContent(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        nextSong: MediaMetadataHelper.MediaInfo,
        label: String,
        playbackActive: Boolean = true
    ): Boolean {
        val line = buildHalfNextSongPreviewLine(
            title = nextSong.title,
            artist = nextSong.artist,
            label = label,
            weight = config.nextSongPreviewWeight
        )
        return applyNextSongPreviewLine(
            view = view,
            prefs = prefs,
            config = config,
            line = line,
            signaturePrefix = "next-song-half",
            signatureParts = listOf(
                nextSong.title,
                nextSong.artist,
                config.nextSongPreviewWeight
            ),
            marquee = true,
            playbackActive = playbackActive
        )
    }

    internal fun buildHalfNextSongPreviewLine(
        title: String,
        artist: String,
        label: String,
        weight: Int
    ): RichLyricLine {
        val songInfo = when {
            title.isBlank() -> artist
            artist.isBlank() -> title
            else -> "$title-$artist"
        }
        return if (weight == RootConstants.ISLAND_NEXT_SONG_PREVIEW_WEIGHT_BOTTOM) {
            RichLyricLine(
                text = label,
                words = emptyList(),
                secondary = songInfo,
                secondaryWords = emptyList()
            )
        } else {
            RichLyricLine(
                text = songInfo,
                words = emptyList(),
                secondary = label,
                secondaryWords = emptyList()
            )
        }
    }

    private fun applyNextSongPreviewLine(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        line: RichLyricLine,
        signaturePrefix: String,
        signatureParts: List<Any>,
        marquee: Boolean,
        playbackActive: Boolean
    ): Boolean {
        configureView(view, prefs, config, mode = 5)
        val signature = listOf(
            signaturePrefix,
            *signatureParts.toTypedArray(),
            config.styleSignature
        ).joinToString("|")
        val contentChanged = hasViewLineContentChanged(view, line)
        if (lastContentSignatures[view] == signature && !contentChanged) return false

        applyContentUpdate(view, config, contentChanged = contentChanged) { target ->
            val isLeft = view.tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
            applyLineCentering(target, config.centerLyric(isLeft))
            applyLineRightAlignment(target, config.rightAlignLyric(isLeft))
            when (target) {
                is RichLyricLineView -> {
                    target.line = line
                    target.setPlaybackActive(playbackActive)
                    if (marquee) applyMetadataMarquee(target, config, force = true)
                }
                is SpaceGateRichLyricLineView -> {
                    target.line = line
                    target.setPlaybackActive(playbackActive)
                    if (marquee) applyMetadataMarquee(target, config, force = true)
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
        if (config.translationDisplayMode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF ||
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
        val rawLine = displayLyricLine(prefs, processedRawLine(prefs, config, isLeft))
        if (!config.isSplitMode || rawLine == null) return rawLine
        if (rawLine.text.isNullOrEmpty()) return rawLine

        val density = view.resources.displayMetrics.density
        val leftMaxPx = config.leftMaxWidthDp * density
        val centerCurrentLine = shouldCenterLine(config, rawLine, isLeft)
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

    fun processedRawLine(
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig? = null,
        isLeft: Boolean? = null
    ): IRichLyricLine? {
        val songName = LyriconDataBridge.currentSongName?.takeIf { it.isNotEmpty() } ?: ""
        var rawLine = LyriconDataBridge.currentLyricLineForIsland(
            nextLyricLineEnabled = config?.nextLyricLine != false
        )
            ?: RichLyricLine(text = songName, words = emptyList())

        if (config != null && isNextLinePreviewEnabled(prefs, config, rawLine)) {
            val nextLine = LyriconDataBridge.currentNextLyricLine
            return rawLine.withNextLinePreview(
                nextLine = nextLine,
                centerNextLine = shouldCenterLine(config, nextLine, isLeft)
            )
        }

        val mode = config?.translationDisplayMode ?: TranslationHelper.getTranslationDisplayMode(prefs)
        val fallback = config?.translationFallback ?: TranslationHelper.isTranslationFallback(prefs)
        if (mode != RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF) {
            if (TranslationHelper.isTranslationOnly(prefs)) {
                rawLine = TranslationHelper.applyTranslationOnly(rawLine, mode, fallback)
            } else if (TranslationHelper.isSwapTranslation(prefs)) {
                rawLine = TranslationHelper.swapTranslation(rawLine, mode, fallback)
            }
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
        val targetLine = displayLyricLine(
            prefs,
            lineOverride ?: buildSlotLyricLine(
                view = view,
                prefs = prefs,
                config = config,
                isLeft = view.tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
            )
        )
        val isLeft = view.tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        val centerCurrentLine = shouldCenterLine(config, targetLine, isLeft)
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
            applyLineRightAlignment(
                view,
                alignMainRight = config.rightAlignLyric(isLeft) && !centerCurrentLine,
                alignSecondaryRight = config.rightAlignLyric(isLeft) && !centerSecondaryLine
            )
            applyPlaybackActive(view, playbackActive)
            return false
        }

        val applyLine: (View) -> Unit = { target ->
            applyLineCentering(target, centerCurrentLine, centerSecondaryLine)
            applyLineRightAlignment(
                target,
                alignMainRight = config.rightAlignLyric(isLeft) && !centerCurrentLine,
                alignSecondaryRight = config.rightAlignLyric(isLeft) && !centerSecondaryLine
            )
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
        val songName = resolveMetadataSongName(
            lyricSongName = LyriconDataBridge.currentSong?.name,
            currentSongName = LyriconDataBridge.currentSongName,
            mediaTitle = mediaInfo.title
        )
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
        val newLine = buildMetadataLine(mode, songName, artistName, albumName)
        val contentChanged = hasViewLineContentChanged(view, newLine)
        if (!force && lastContentSignatures[view] == signature && !contentChanged) return false

        applyContentUpdate(view, config, suppressAnimation, contentChanged) { target ->
            val isLeft = view.tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
            applyLineCentering(target, config.centerLyric(isLeft))
            applyLineRightAlignment(target, config.rightAlignLyric(isLeft))
            when (target) {
                is RichLyricLineView -> {
                    if (contentChanged) target.line = newLine
                    applyMetadataMarquee(target, config)
                }
                is SpaceGateRichLyricLineView -> {
                    if (contentChanged) target.line = newLine
                    applyMetadataMarquee(target, config)
                }
            }
        }
        lastContentSignatures[view] = signature
        return true
    }

    internal fun resolveMetadataSongName(
        lyricSongName: String?,
        currentSongName: String?,
        mediaTitle: String
    ): String = lyricSongName?.takeIf { it.isNotBlank() }
        ?: currentSongName?.takeIf { it.isNotBlank() }
        ?: mediaTitle

    internal fun buildMetadataLine(
        mode: Int,
        songName: String,
        artistName: String,
        albumName: String
    ): IRichLyricLine? {
        val singleModeText = when (mode) {
            1 -> songName
            2 -> artistName
            3 -> albumName
            4 -> "$songName - $artistName"
            else -> ""
        }
        return when (mode) {
            1, 2, 3, 4 -> RichLyricLine(text = singleModeText, words = emptyList())
            5 -> RichLyricLine(
                text = songName,
                words = emptyList(),
                secondary = artistName,
                secondaryWords = emptyList()
            )
            6 -> {
                val secondary = if (albumName.isEmpty()) artistName else "$artistName - $albumName"
                RichLyricLine(
                    text = songName,
                    words = emptyList(),
                    secondary = secondary,
                    secondaryWords = emptyList()
                )
            }
            else -> null
        }
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
            translationDisplayMode = config.translationDisplayMode,
            translationFallback = config.translationFallback,
            translationOnly = TranslationHelper.isTranslationOnly(prefs),
            nextLinePreview = isNextLinePreviewEnabled(prefs, config)
        )
        when (view) {
            is RichLyricLineView -> view.setDisplayOptions(
                options.displayMode,
                options.fallback,
                options.hideSecondaryContent
            )
            is SpaceGateRichLyricLineView -> view.setDisplayOptions(
                options.displayMode,
                options.fallback,
                options.hideSecondaryContent
            )
        }
    }

    internal fun resolveLyricDisplayOptions(
        translationDisplayMode: Int,
        translationFallback: Boolean,
        translationOnly: Boolean,
        nextLinePreview: Boolean
    ): LyricDisplayOptions {
        val hideSecondaryContent =
            translationDisplayMode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF || nextLinePreview
        val showTranslation = !hideSecondaryContent &&
            (translationDisplayMode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION ||
                (translationFallback && translationDisplayMode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION))
        val showRoma = !hideSecondaryContent && !translationOnly &&
            (translationDisplayMode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION ||
                (translationFallback && translationDisplayMode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION))

        return LyricDisplayOptions(
            displayMode = translationDisplayMode,
            fallback = translationFallback,
            hideSecondaryContent = hideSecondaryContent,
            showTranslation = showTranslation,
            showRoma = showRoma
        )
    }

    internal fun resolveLyricDisplayOptions(
        translationDisplayed: Boolean,
        translationOnly: Boolean,
        nextLinePreview: Boolean
    ): LyricDisplayOptions {
        val mode = if (translationDisplayed) RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
        else RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
        return resolveLyricDisplayOptions(
            translationDisplayMode = mode,
            translationFallback = false,
            translationOnly = translationOnly,
            nextLinePreview = nextLinePreview
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

    /**
     * 按偏好生成超级岛/实时动态通知的最终歌词副本，不反写歌词源状态。
     */
    private fun displayLyricLine(
        prefs: SharedPreferences,
        line: IRichLyricLine?
    ): IRichLyricLine? {
        line ?: return null
        if (line.isTitleLine() || (
                LyriconDataBridge.currentLyricLine == null &&
                    line.text == LyriconDataBridge.currentSongName
                )) {
            return line
        }
        val removeSpaces = prefs.getBoolean(
            RootConstants.KEY_HOOK_REMOVE_CJK_LYRIC_SPACES,
            RootConstants.DEFAULT_HOOK_REMOVE_CJK_LYRIC_SPACES,
        )
        return if (removeSpaces) CjkLyricWhitespacePolicy.transformLine(line) else line
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
        line: IRichLyricLine?,
        isLeft: Boolean?
    ): Boolean = isLeft != null && config.centerLyric(isLeft) || (
        config.groupVocalCenteringEnabled &&
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

    private fun applyLineRightAlignment(
        view: View,
        alignMainRight: Boolean,
        alignSecondaryRight: Boolean = alignMainRight
    ) {
        when (view) {
            is RichLyricLineView -> view.setLineAlignmentRight(
                alignMainRight,
                alignSecondaryRight
            )
            is SpaceGateRichLyricLineView -> view.setLineAlignmentRight(
                alignMainRight,
                alignSecondaryRight
            )
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
        return shouldUseNextLinePreview(
            config.translationDisplayMode,
            config.translationFallback,
            currentLine
        )
    }

    internal fun shouldUseNextLinePreview(
        translationDisplayMode: Int,
        translationFallback: Boolean,
        currentLine: IRichLyricLine?
    ): Boolean {
        val hasSecondary = !currentLine?.secondary.isNullOrBlank() ||
            !currentLine?.secondaryWords.isNullOrEmpty()
        val hasTranslation = !currentLine?.translation.isNullOrBlank() ||
            !currentLine?.translationWords.isNullOrEmpty()
        val hasRoma = !currentLine?.roma.isNullOrBlank()

        val hasDisplayedExtra = when (translationDisplayMode) {
            RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION ->
                hasTranslation || (translationFallback && hasRoma)
            RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION ->
                hasRoma || (translationFallback && hasTranslation)
            else -> false
        }
        return !hasDisplayedExtra && !hasSecondary
    }

    internal fun shouldUseNextLinePreview(
        translationDisplayed: Boolean,
        currentLine: IRichLyricLine?
    ): Boolean {
        val mode = if (translationDisplayed) RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
        else RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
        return shouldUseNextLinePreview(mode, false, currentLine)
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
