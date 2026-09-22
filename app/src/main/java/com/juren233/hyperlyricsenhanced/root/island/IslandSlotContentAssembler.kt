package com.juren233.hyperlyricsenhanced.root.island

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.AppleMetadataFlowDiagnostics
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
import com.juren233.hyperlyricsenhanced.lyric.view.line.MixedTypefaceText
import com.juren233.hyperlyricsenhanced.root.island.view.MaxWidthFrameLayout
import com.juren233.hyperlyricsenhanced.lyric.view.LyricViewStyle
import com.juren233.hyperlyricsenhanced.lyric.view.isTitleLine
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.AnimConfig
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.YoYoPresets
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.animateEntrance
import com.juren233.hyperlyricsenhanced.lyric.view.yoyo.animateUpdate
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.utils.CoverColorHelper
import com.juren233.hyperlyricsenhanced.root.utils.CoverColorDiagnostics
import com.juren233.hyperlyricsenhanced.root.utils.FontHelper
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.LyricStyleHelper
import com.juren233.hyperlyricsenhanced.root.utils.TranslationHelper
import java.io.File
import java.util.WeakHashMap

internal object IslandSlotContentAssembler {
    private data class SplitFontKey(
        val weight: Int,
        val italic: Boolean,
        val customPath: String,
        val customModified: Long,
        val customLength: Long,
        val narrowLatin: Boolean,
    )

    private data class SplitFonts(
        val key: SplitFontKey,
        val base: Typeface,
        val narrow: Typeface?,
    )

    private data class SplitMeasureKey(
        val fontKey: SplitFontKey,
        val textSizePx: Float,
    )

    /** 分离模式测宽环境：Paint 持有原生资源，Rect 供 getTextBounds 反复写入，按字体+字号复用。 */
    private class SplitMeasureEnv(val paint: TextPaint, val bounds: Rect)

    private var splitFontCache: SplitFonts? = null
    private var splitMeasureEnvCache: Pair<SplitMeasureKey, SplitMeasureEnv>? = null

    private val lastContentSignatures = WeakHashMap<View, String>()
    private val lastStyleSignatures = WeakHashMap<View, String>()
    // Keep the applied value across global invalidations so equal refreshes do not rebind style.
    private val lastAppliedStyles = WeakHashMap<View, LyricViewStyle>()
    // Keep this across global refreshes so delayed fallback lyrics can detect the prior placeholder state.
    internal val lastLyricAvailability = WeakHashMap<View, LyricAvailability>()

    internal data class LyricAvailability(
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
            applyMetadataContent(view, prefs, config, mode, force, mediaInfo, suppressAnimation)
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

    /**
     * A content animation writes the target signature before its fade-out
     * callback writes the target line. A second layout/width pass can therefore
     * observe the old View line while the target signature is already pending.
     * The signature is the authoritative queued-content marker in that window.
     */
    internal fun shouldSkipContentRefresh(
        force: Boolean,
        lastSignature: String?,
        targetSignature: String,
        viewContentLost: Boolean = false,
    ): Boolean = !force && !viewContentLost && lastSignature == targetSignature

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
        val viewContentLost = isLyricViewContentLost(view, line)
        if (shouldSkipContentRefresh(false, lastContentSignatures[view], signature, viewContentLost)) return false

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
            // Adjacent translation is rendered as a lyric line. Give its main
            // translation the same source-word time range as the backing
            // translation so both translation rows progress with the current
            // Apple Music word-timed lyric instead of leaving the first row
            // static.
            words = buildTranslationProgressWords(source.words, mainTranslation),
            secondary = backgroundTranslation,
            secondaryWords = buildTranslationProgressWords(
                source.secondaryWords,
                backgroundTranslation
            )
        )
    }

    internal fun buildBackgroundTranslationWords(
        source: IRichLyricLine,
        translation: String
    ): List<LyricWord> = buildTranslationProgressWords(source.secondaryWords, translation)

    internal fun buildTranslationProgressWords(
        sourceWords: List<LyricWord>?,
        translation: String
    ): List<LyricWord> {
        val timedWords = sourceWords.orEmpty().mapNotNull { word ->
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
        if (!config.isSeparatedMode || rawLine == null || rawLine.text.isNullOrEmpty()) {
            // 全岛歌词：左右两槽绑定同一整行，由 SpaceGate 主从视口裁出连续文本带。
            // 单侧歌词：按槽位原样绑定当前行。
            return rawLine
        }
        if (isInterludeIndicatorLine(rawLine)) {
            // 间奏指示器不参与对半切分：整行同绑两槽，时间窗与全岛歌词一致；
            // 右槽由 hideInterludeIndicator 只保留测量宽度，不重复画第二组指示点。
            return rawLine
        }

        // 分离歌词：把当前行按视觉宽度均分为左右两段。词级 timing 由
        // RichLyricLineSplitter 保留，因此播放进度会先走完左段，再进入右段。
        val density = view.resources.displayMetrics.density
        val leftMaxPx = config.contentWidthPx(
            view.resources.displayMetrics.widthPixels,
            density,
            isLeft = true
        )?.toFloat() ?: 0f
        val fonts = splitFonts(prefs, config)
        val selector = MixedTypefaceText.typefaceSelector(fonts.base, fonts.narrow)
        val measureEnv = splitMeasureEnv(
            fonts,
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                config.textSizeSp.toFloat(),
                view.resources.displayMetrics,
            ),
        )
        val textPaint = measureEnv.paint
        val bounds = measureEnv.bounds
        val measureWidth: (Paint, String) -> Float = { paint, text ->
            if (text.isEmpty()) 0f
            else if (selector != null) MixedTypefaceText.measureText(paint, text, selector)
            else {
                val advance = paint.measureText(text)
                paint.getTextBounds(text, 0, text.length, bounds)
                maxOf(advance, bounds.right.toFloat())
            }
        }
        val splitPx = separatedSplitWidthPx(
            textWidthPx = measureWidth(textPaint, rawLine.text.orEmpty()),
            leftMaxWidthPx = leftMaxPx
        )
        val splitResult = RichLyricLineSplitter.split(
            rawLine,
            textPaint,
            splitPx,
            config.textSizeRatio,
            centerLyric = true,
            measureWidth = measureWidth,
        )
        return if (isLeft) splitResult.left else splitResult.right
    }

    internal fun separatedSplitWidthPx(textWidthPx: Float, leftMaxWidthPx: Float): Float =
        (textWidthPx / 2f).coerceAtMost(leftMaxWidthPx).coerceAtLeast(0f)

    private fun splitFonts(prefs: SharedPreferences, config: IslandSlotRuntimeConfig): SplitFonts {
        val customPath = config.customFontPath
        val customFile = customPath.takeIf { it.isNotBlank() }?.let(::File)
        val key = SplitFontKey(
            weight = config.fontWeight,
            italic = config.fontItalic,
            customPath = customPath,
            customModified = customFile?.lastModified() ?: 0L,
            customLength = customFile?.length() ?: 0L,
            narrowLatin = config.narrowLatinFont,
        )
        return synchronized(this) {
            splitFontCache?.takeIf { it.key == key }
                ?: SplitFonts(
                    key = key,
                    base = FontHelper.loadBaseTypeface(prefs),
                    narrow = FontHelper.loadNarrowTypeface(prefs),
                ).also { splitFontCache = it }
        }
    }

    /** 行绑定在主线程同步执行，测宽环境跨调用复用安全；仅字体或字号变化时重建。 */
    private fun splitMeasureEnv(fonts: SplitFonts, textSizePx: Float): SplitMeasureEnv {
        val key = SplitMeasureKey(fontKey = fonts.key, textSizePx = textSizePx)
        return synchronized(this) {
            splitMeasureEnvCache?.takeIf { it.first == key }?.second
                ?: SplitMeasureEnv(
                    paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = textSizePx
                        typeface = fonts.base
                    },
                    bounds = Rect(),
                ).also { splitMeasureEnvCache = key to it }
        }
    }

    internal fun isInterludeIndicatorLine(line: IRichLyricLine?): Boolean =
        line?.metadata?.getBoolean(LyricMetadataKeys.INSTRUMENTAL) == true

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

        return if (config != null) {
            applyTranslationPresentation(rawLine, config)
        } else {
            val mode = TranslationHelper.getTranslationDisplayMode(prefs)
            val fallback = TranslationHelper.isTranslationFallback(prefs)
            when {
                mode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF -> rawLine
                TranslationHelper.isTranslationOnly(prefs) ->
                    TranslationHelper.applyTranslationOnly(rawLine, mode, fallback)
                TranslationHelper.isSwapTranslation(prefs) ->
                    TranslationHelper.swapTranslation(rawLine, mode, fallback)
                else -> rawLine
            }
        }
    }

    /**
     * Applies the Island's lyric presentation preferences to every refresh
     * path. In particular, BaseIslandRenderer can provide a raw line through
     * lineOverride after the initial slot build; leaving that path untouched
     * makes swapped translation flash once and then revert to the original.
     */
    internal fun applyTranslationPresentation(
        line: IRichLyricLine,
        config: IslandSlotRuntimeConfig
    ): IRichLyricLine {
        // A next-line preview has its own secondary payload and must not be
        // interpreted as a normal current lyric during a later refresh.
        if (line.metadata?.getBoolean(METADATA_NEXT_LINE_PREVIEW) == true) return line
        if (config.translationDisplayMode == RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF) {
            return line
        }
        return when {
            config.translationOnly -> TranslationHelper.applyTranslationOnly(
                line,
                config.translationDisplayMode,
                config.translationFallback
            )
            config.swapTranslation -> TranslationHelper.swapTranslation(
                line,
                config.translationDisplayMode,
                config.translationFallback
            )
            else -> line
        }
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
        val sourceLine = lineOverride ?: buildSlotLyricLine(
            view = view,
            prefs = prefs,
            config = config,
            isLeft = view.tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        )
        val targetLine = displayLyricLine(
            prefs,
            sourceLine?.let { line ->
                if (lineOverride != null) applyTranslationPresentation(line, config) else line
            }
        )
        val isLeft = view.tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        // 全岛歌词下两槽画的是同一条文本带，居中/靠右必须两侧一致，
        // 统一采用左槽的位置偏好（带的原点是岛左缘），否则两个视口错位。
        val alignmentIsLeft = if (config.isFullIslandMode) true else isLeft
        val centerCurrentLine = shouldCenterLine(config, targetLine, alignmentIsLeft)
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
        val viewContentLost = isLyricViewContentLost(view, targetLine)
        if (shouldSkipContentRefresh(force, lastContentSignatures[view], signature, viewContentLost)) {
            // The target signature is recorded when its exit animation starts, while the
            // View still draws the previous line until the animation callback. A position or
            // width refresh in that window must not put the target line's alignment on the
            // old text. The callback below applies alignment and content together at alpha 0.
            // 预览提升窗口例外：rawLine 在动画开始时即已写入，contentChanged 恒为
            // false，但旧句仍在淡出——对齐已暂存，落地前不得在此提前套用。
            val promotionRunning = when (view) {
                is RichLyricLineView -> view.isNextLinePromotionRunning
                is SpaceGateRichLyricLineView -> view.isNextLinePromotionRunning
                else -> false
            }
            if (!contentChanged && !promotionRunning) {
                applyLineCentering(view, centerCurrentLine, centerSecondaryLine)
                applyLineRightAlignment(
                    view,
                    alignMainRight = config.rightAlignLyric(alignmentIsLeft) && !centerCurrentLine,
                    alignSecondaryRight = config.rightAlignLyric(alignmentIsLeft) && !centerSecondaryLine
                )
            }
            applyPlaybackActive(view, playbackActive)
            return false
        }

        val willAnimateNextLinePromotion = when (view) {
            is RichLyricLineView -> view.willAnimateNextLinePromotion(targetLine)
            is SpaceGateRichLyricLineView -> view.willAnimateNextLinePromotion(targetLine)
            else -> false
        }
        // 预览提升窗口：line 写入即启动动画，旧句要完整淡出，行级对齐不能像
        // 普通换句那样等到落地回调才与内容同点套用——写入本身就是同步的，
        // 提前套用会让合唱居中旧句先按下一句方向重渲染再换字。暂存到提升
        // 落地（finishNextLinePromotion）再生效，与 160164 淡出窗口契约同源。
        val deferAlignmentToPromotionLanding = willAnimateNextLinePromotion &&
            !lyricsJustBecameAvailable &&
            view.parent != null &&
            view.isAttachedToWindow
        val applyLine: (View) -> Unit = { target ->
            if (deferAlignmentToPromotionLanding) {
                when (target) {
                    is RichLyricLineView -> target.stagePromotionLandingAlignment(
                        centerMain = centerCurrentLine,
                        centerSecondary = centerSecondaryLine,
                        alignMainRight = config.rightAlignLyric(alignmentIsLeft) && !centerCurrentLine,
                        alignSecondaryRight = config.rightAlignLyric(alignmentIsLeft) && !centerSecondaryLine
                    )
                    is SpaceGateRichLyricLineView -> target.stagePromotionLandingAlignment(
                        centerMain = centerCurrentLine,
                        centerSecondary = centerSecondaryLine,
                        alignMainRight = config.rightAlignLyric(alignmentIsLeft) && !centerCurrentLine,
                        alignSecondaryRight = config.rightAlignLyric(alignmentIsLeft) && !centerSecondaryLine
                    )
                }
            } else {
                applyLineCentering(target, centerCurrentLine, centerSecondaryLine)
                applyLineRightAlignment(
                    target,
                    alignMainRight = config.rightAlignLyric(alignmentIsLeft) && !centerCurrentLine,
                    alignSecondaryRight = config.rightAlignLyric(alignmentIsLeft) && !centerSecondaryLine
                )
            }
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
            IslandLyricTextInjector.syncDuetGravityAfterContentLanding(target, config)
            if (deferAlignmentToPromotionLanding) {
                // 视图侧未真正起跑提升（如首帧高度为 0）时当场消费暂存，避免标志滞留。
                when (target) {
                    is RichLyricLineView -> target.settlePromotionLandingAlignment()
                    is SpaceGateRichLyricLineView -> target.settlePromotionLandingAlignment()
                }
            }
        }
        val suppressContentAnimation = suppressAnimation ||
            (willAnimateNextLinePromotion && !lyricsJustBecameAvailable) ||
            view.parent == null ||
            !view.isAttachedToWindow
        // 动态长度：预览提升会延迟内容落地。提前提供目标宽度；长句上浮时
        // 视图只把它作为岛宽预算，子行仍以旧宽绘制。内容落地后实测兜底。
        if (config.dynamicWidthEnabled) {
            val deferredByPromotion = deferAlignmentToPromotionLanding
            when (view) {
                is RichLyricLineView -> view.beginDeferredContentWidth(targetLine.takeIf { deferredByPromotion })
                is SpaceGateRichLyricLineView -> view.beginDeferredContentWidth(targetLine.takeIf { deferredByPromotion })
            }
        }
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
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        force: Boolean,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        suppressAnimation: Boolean
    ): Boolean {
        val preferSessionMetadata = shouldPreferMediaSessionMetadata(
            packageName = LyriconDataBridge.currentLyricPackageName,
            restoreOriginalMetadata = prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
            ),
        )
        val songName = resolveMetadataSongName(
            lyricSongName = LyriconDataBridge.currentSong?.name,
            currentSongName = LyriconDataBridge.currentSongName,
            mediaTitle = mediaInfo.title,
            preferSessionMetadata = preferSessionMetadata,
        )
        val artistName = resolveMetadataArtistName(
            lyricArtist = LyriconDataBridge.currentSong?.artist,
            mediaArtist = mediaInfo.artist,
            preferSessionMetadata = preferSessionMetadata,
        )
        val albumName = mediaInfo.album
        if (BuildConfig.DEBUG) AppleMetadataFlowDiagnostics.record("island_choice", changedOnly = true) {
            "id=${AppleMetadataFlowDiagnostics.text(LyriconDataBridge.currentSong?.id)} " +
                "providerArtist=${AppleMetadataFlowDiagnostics.text(LyriconDataBridge.currentSong?.artist)} " +
                "mediaArtist=${AppleMetadataFlowDiagnostics.text(mediaInfo.artist)} " +
                "selectedArtist=${AppleMetadataFlowDiagnostics.text(artistName)} " +
                "selectedTitle=${AppleMetadataFlowDiagnostics.text(songName)} " +
                "preferSessionMetadata=$preferSessionMetadata"
        }

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
        val viewContentLost = isLyricViewContentLost(view, newLine)
        if (shouldSkipContentRefresh(force, lastContentSignatures[view], signature, viewContentLost)) return false

        applyContentUpdate(view, config, suppressAnimation, contentChanged) { target ->
            val isLeft = view.tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
            applyLineCentering(target, config.centerLyric(isLeft))
            applyLineRightAlignment(target, config.rightAlignLyric(isLeft))
            when (target) {
                is RichLyricLineView -> {
                    if (contentChanged || viewContentLost) target.line = newLine
                    applyMetadataMarquee(target, config)
                }
                is SpaceGateRichLyricLineView -> {
                    if (contentChanged || viewContentLost) target.line = newLine
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
        mediaTitle: String,
        preferSessionMetadata: Boolean = false,
    ): String {
        val anchorTitle = currentSongName?.takeIf {
            it.isNotBlank() && it != UNKNOWN_MEDIA_TITLE
        }
        val sessionTitle = anchorTitle ?: mediaTitle.takeIf { it.isNotBlank() }
        return if (preferSessionMetadata) {
            sessionTitle
                ?: lyricSongName?.takeIf { it.isNotBlank() }
                ?: currentSongName?.takeIf { it.isNotBlank() }
                ?: mediaTitle
        } else {
            lyricSongName?.takeIf { it.isNotBlank() }
                ?: currentSongName?.takeIf { it.isNotBlank() }
                ?: mediaTitle
        }
    }

    /**
     * 默认由 Provider 的干净歌手字段优先，避免国内音乐 App 的车载歌词把
     * 「歌名-歌手」组合串写进 MediaSession。仅 Apple 原地区原名模式反转该优先级，
     * 因为该模式已把最终原名写入 MediaSession，而 Provider 仍可能保留地区英文别名。
     */
    internal fun resolveMetadataArtistName(
        lyricArtist: String?,
        mediaArtist: String,
        preferSessionMetadata: Boolean = false,
    ): String = if (preferSessionMetadata) {
        mediaArtist.takeIf { it.isNotBlank() }
            ?: lyricArtist?.takeIf { it.isNotBlank() }
            ?: mediaArtist
    } else {
        lyricArtist?.takeIf { it.isNotBlank() }
            ?: mediaArtist
    }

    internal fun shouldPreferMediaSessionMetadata(
        packageName: String?,
        restoreOriginalMetadata: Boolean,
    ): Boolean = restoreOriginalMetadata &&
        packageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME

    private const val UNKNOWN_MEDIA_TITLE = "Playing~"

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
        // 动态长度下换句/预览提升动画会把内容更新延迟 220~300ms 才落地，
        // 落地点必须补一次岛宽重算（relayoutAfterDeferredContent），
        // 否则岛宽恒定按上一行计算。动画本身原样保留。
        val animatedUpdate: (View) -> Unit = { target ->
            update(target)
            relayoutAfterDeferredContent(target, config)
        }
        // 动画速率只作用于歌词切换动画：以所选样式内置时长为 1x 缩放出/入段；
        // 优雅(1x)保持原样。间奏动画、第二行(下一句预览)上浮动画、入场揭示均不参与。
        val switchPreset = if (config.switchAnimRateFactor != RootConstants.SWITCH_ANIM_RATE_ELEGANT_FACTOR) {
            preset.scaleDurations(config.switchAnimRateFactor)
        } else {
            preset
        }
        when (view) {
            is RichLyricLineView -> if (entranceOnly) {
                view.animateEntrance(preset) { update(this) }
            } else {
                // 动态长度：淡出期间冻结组宽，旧句对唱位置保持到新内容落地，
                // 避免“旧句先移到另一侧再换字”。
                view.beginContentSwitchFreeze()
                view.animateUpdate(switchPreset) { animatedUpdate(this) }
            }
            is SpaceGateRichLyricLineView -> if (entranceOnly) {
                view.animateEntrance(preset) { update(this) }
            } else {
                view.beginContentSwitchFreeze()
                view.animateUpdate(switchPreset) { animatedUpdate(this) }
            }
            else -> update(view)
        }
    }

    /**
     * 样式出/入两段时长等比缩放到速率倍率（各样式内置时长为 1x）；
     * 下限 1ms，防止极小倍率时零时长动画回调路径异常。
     */
    private fun Pair<AnimConfig, AnimConfig>.scaleDurations(factor: Float): Pair<AnimConfig, AnimConfig> {
        fun scale(config: AnimConfig) = AnimConfig(
            technique = config.technique,
            duration = (config.duration * factor).toLong().coerceAtLeast(1L),
            interpolator = config.interpolator
        )
        return scale(first) to scale(second)
    }

    /**
     * 动态长度：延迟落地（换句动画回调、预览提升动画结束）后的第二次岛宽重算。
     * 同步内容路径不经过这里，由调用方在内容应用后自行重算一次。
     */
    private fun relayoutAfterDeferredContent(view: View, config: IslandSlotRuntimeConfig) {
        if (!config.dynamicWidthEnabled) return
        IslandViewHelper.triggerSystemRelayoutForDescendant(view)
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
        // 对唱固定长度：仅在动态长度开启时消费开关；歌曲含对唱行才抬宽到全曲最长行。
        val duetSongLyrics = if (config.dynamicWidthEnabled && config.duetFixedLengthEnabled) {
            LyriconDataBridge.currentSong?.lyrics
        } else {
            null
        }
        when (view) {
            is RichLyricLineView -> {
                view.setSecondaryTextUnitProgress(config.isSeparatedMode)
                // 分离歌词右槽不画间奏指示器：整岛只显示左槽（条带起点）的一组，
                // 与全岛歌词一致；内容绑定与 hug 测量保持原样，避免间奏期间岛宽抖动。
                view.hideInterludeIndicator = config.isSeparatedMode &&
                    view.tag != IslandProbeUtils.LEFT_TEST_VIEW_TAG
                view.setDisplayOptions(
                    options.displayMode,
                    options.fallback,
                    options.hideSecondaryContent
                )
                view.hugContentWidth = config.dynamicWidthEnabled
                view.applyDuetFixedLength(duetSongLyrics, duetWidthCapOf(view))
                view.onDeferredContentApplied = {
                    IslandViewHelper.triggerSystemRelayoutForDescendant(view)
                }
            }
            is SpaceGateRichLyricLineView -> {
                view.setSecondaryTextUnitProgress(config.isFullIslandMode)
                view.setDisplayOptions(
                    options.displayMode,
                    options.fallback,
                    options.hideSecondaryContent
                )
                // 全岛歌词两槽共享同一整行，hug 收缩会让两侧都量出整行宽、
                // 把岛宽计算撑大一倍；整带几何要求每槽恒占满自己的槽宽。
                view.hugContentWidth = config.dynamicWidthEnabled && !config.isFullIslandMode
                view.applyDuetFixedLength(duetSongLyrics, duetWidthCapOf(view))
                view.onDeferredContentApplied = {
                    IslandViewHelper.triggerSystemRelayoutForDescendant(view)
                }
            }
        }
    }

    /**
     * 对唱固定长度的宽度上限：所在注入 wrapper 的内容最大宽度。
     * 视图尚未挂到 wrapper（首次注入）时为 null，此时不截断。
     */
    private fun duetWidthCapOf(view: View): Int? =
        (view.parent as? MaxWidthFrameLayout)?.maxWidthPx?.takeIf { it > 0 }

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

}
