package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.createBitmap
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.view.line.LyricLineView
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

internal object IslandAlbumCoverStyleHooker {
    private const val TAG = "IslandAlbumCoverStyleHooker"
    private const val ICON_HOLDER_CLASS =
        "miui.systemui.dynamicisland.module.IslandIconViewHolder"
    private const val MEDIA_ALBUM_ICON = "miui_media_album_icon"
    private const val CAPTURE_MAX_DIMENSION = 512
    private const val REQUIRED_STABLE_FRAMES = 3
    private const val MAX_OBSERVATION_FRAMES = 360
    private const val LEFT_CONTENT_SHADOW_RADIUS_DP = 0.85f
    private const val LEFT_CONTENT_SHADOW_DY_DP = 0.5f
    private const val LEFT_CONTENT_SHADOW_ALPHA = 0x7C
    private val CAPTURE_DELAYS_MS = longArrayOf(0L, 120L, 500L, 1_500L)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val trackedHolders = WeakHashMap<Any, TrackedHolder>()
    private val captureGenerationByView = WeakHashMap<ImageView, Int>()
    private val gradientStates = WeakHashMap<ImageView, GradientCoverState>()
    private val fakeTransitionLogSignatures = WeakHashMap<ViewGroup, String>()
    private val artworkDiagnosticStates = WeakHashMap<ImageView, String>()
    private val artworkIdentityByView = WeakHashMap<ImageView, ArtworkIdentity>()
    private val restoringNative = ThreadLocal<Boolean>()
    @Volatile
    private var cachedBigVisual: CoverVisualSnapshot? = null
    @Volatile
    private var cachedSmallVisual: CoverVisualSnapshot? = null
    private val circleOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return
            outline.setOval(0, 0, w, h)
        }
    }
    private val leftRoundedCoverOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return
            val radius = h / 2f
            // Extend the rounded rect one height beyond the View's right edge. The
            // left corners remain inside the View and follow the capsule's round end;
            // the right corners fall outside the View, leaving a straight gradient edge.
            outline.setRoundRect(0, 0, w + h, h, radius)
        }
    }

    @Volatile
    private var module: XposedModule? = null

    private val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        module = xposedModule
        if (!hookedClassLoaders.add(classLoader)) return

        try {
            val holderClass = classLoader.loadClass(ICON_HOLDER_CLASS)
            val fixMethod = holderClass.declaredMethods.firstOrNull {
                it.name == "setFixIcon" && it.parameterTypes.size == 1
            } ?: run {
                hookedClassLoaders.remove(classLoader)
                HookLogger.w(TAG, "跳过封面样式 Hook: target=setFixIcon")
                return
            }
            fixMethod.isAccessible = true
            val setIconMethod = holderClass.declaredMethods.firstOrNull {
                it.name == "setIcon" && it.parameterTypes.contentEquals(fixMethod.parameterTypes)
            }?.apply { isAccessible = true }
            val setSmallIconMethod = holderClass.declaredMethods.firstOrNull {
                it.name == "setSmallIcon" && it.parameterTypes.contentEquals(fixMethod.parameterTypes)
            }?.apply { isAccessible = true }
            val accessor = CoverAccessor(
                setFixIconMethod = fixMethod,
                setIconMethod = setIconMethod,
                setSmallIconMethod = setSmallIconMethod,
                setAppIconMethod = holderClass.declaredMethods.firstOrNull {
                    it.name == "setAppIcon" && it.parameterTypes.contentEquals(fixMethod.parameterTypes)
                }?.apply { isAccessible = true },
                picInfoField = holderClass.getDeclaredField("picInfo").apply { isAccessible = true },
                fixIconField = holderClass.getDeclaredField("fixIcon").apply { isAccessible = true },
                smallIconField = holderClass.getDeclaredField("smallIcon").apply { isAccessible = true },
                appIconField = holderClass.getDeclaredField("appIcon").apply { isAccessible = true },
                iconContainerField = holderClass.getDeclaredField("iconContainer").apply { isAccessible = true }
            )

            xposedModule.deoptimize(fixMethod)
            xposedModule.hook(fixMethod).intercept(SetIconHook(accessor, accessor.fixIconField, "setFixIcon"))
            setIconMethod?.let {
                xposedModule.deoptimize(it)
                xposedModule.hook(it).intercept(SetIconHook(accessor, accessor.fixIconField, "setIcon"))
            }
            setSmallIconMethod?.let {
                xposedModule.deoptimize(it)
                xposedModule.hook(it).intercept(SetIconHook(accessor, accessor.smallIconField, "setSmallIcon"))
            }
            installTemplateGeometryHook(xposedModule, classLoader)
            HookLogger.i(
                TAG,
                "超级岛封面样式 Hook 已初始化: setFixIcon=true, setIcon=${setIconMethod != null}, setSmallIcon=${setSmallIconMethod != null}",
            )
        } catch (e: ClassNotFoundException) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "当前插件不支持超级岛封面样式: reason=${e.message}")
        } catch (e: NoSuchFieldException) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "超级岛封面字段不可用: reason=${e.message}")
        } catch (e: Throwable) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.e(TAG, "初始化超级岛封面样式 Hook 失败", e)
        }
    }

    private fun installTemplateGeometryHook(
        xposedModule: XposedModule,
        classLoader: ClassLoader,
    ) {
        runCatching {
            val helperClass = classLoader.loadClass(
                IslandGradientCoverRuntimeIdentifiers.PHONE_HELPER_CLASS
            )
            val holderClass = classLoader.loadClass(
                IslandGradientCoverRuntimeIdentifiers.CONTENT_VIEW_HOLDER_CLASS
            )
            val method = helperClass.declaredMethods.singleOrNull {
                it.name == IslandGradientCoverRuntimeIdentifiers.FIND_AND_INIT_VIEWS_METHOD &&
                    it.parameterTypes.contentEquals(arrayOf(View::class.java, View::class.java)) &&
                    it.returnType == holderClass
            } ?: run {
                HookLogger.w(TAG, "跳过首帧渐变几何 Hook: target=findAndInitViews")
                return
            }
            method.isAccessible = true
            xposedModule.deoptimize(method)
            xposedModule.hook(method).intercept(TemplateGeometryHook())
            HookLogger.i(TAG, "首帧渐变几何 Hook 已初始化: target=findAndInitViews")
        }.onFailure { error ->
            HookLogger.e(TAG, "初始化首帧渐变几何 Hook 失败", error)
        }
    }


    fun refresh() {
        runOnMain {
            val holders = synchronized(trackedHolders) {
                trackedHolders.mapNotNull { (holder, tracked) ->
                    tracked.dataRef.get()?.let { Triple(holder, it, tracked.accessor) }
                }
            }
            holders.forEach { (holder, data, accessor) ->
                runCatching { accessor.setFixIconMethod.invoke(holder, data) }
                    .onFailure { HookLogger.e(TAG, "刷新超级岛封面样式失败", it) }
            }
        }
    }

    fun onPlaybackStateChanged(isPlaying: Boolean) {
        IslandAlbumCoverRotationController.setPlaybackActive(isPlaying)
        EmbeddedIslandAlbumCoverController.setPlaybackActive(isPlaying)
    }

    fun applyFakeTransitionCover(
        fakeView: ViewGroup,
        realView: View? = null,
        source: String,
    ) {
        if (currentStyle() != RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT) return
        if (!isMediaFakeTransition(fakeView, realView)) return

        val stateClass = resolveFakeTransitionStateClass(fakeView, realView)
        val targetSmallIsland = IslandGradientCoverRuntimeIdentifiers.compactIslandRole(stateClass)
            ?: return
        val targets = collectCoverImageViews(fakeView)
        val selected = selectFakeCoverTarget(fakeView, targets, targetSmallIsland) ?: return
        val imageView = selected.first
        val resourceName = selected.second

        val sharedIdentity = synchronized(artworkIdentityByView) {
            artworkIdentityByView[imageView]
                ?: targets.firstNotNullOfOrNull { (target, _) -> artworkIdentityByView[target] }
        }
        if (sharedIdentity != null) {
            synchronized(artworkIdentityByView) {
                artworkIdentityByView.putIfAbsent(imageView, sharedIdentity)
            }
        }

        ensureArtworkContinuity(imageView, "fake:$source")
        val state = synchronized(gradientStates) {
            gradientStates[imageView]
        }
        state?.applyLeftContentTextShadow()
        val embeddedHost = resolveFakeEmbeddedHost(imageView, fakeView, targetSmallIsland)
        val embedded = embeddedHost != null &&
            EmbeddedIslandAlbumCoverController.apply(embeddedHost, imageView, targetSmallIsland)
        if (embedded) {
            state?.removePreDrawObserver()
            state?.restoreCoverVisuals()
        } else {
            EmbeddedIslandAlbumCoverController.restoreForSource(imageView)
            state?.removePreDrawObserver()
            state?.restoreCoverVisuals()
        }

        val realOwner = resolveFakeTransitionOwner(fakeView, realView)
        val realEmbedded = syncRealTransitionCover(
            realOwner = realOwner,
            smallIsland = targetSmallIsland,
            source = source,
        )

        if (BuildConfig.DEBUG) {
            val signature = "$source|$stateClass|$targetSmallIsland|${targets.size}|" +
                "$resourceName|${System.identityHashCode(imageView)}|$embedded|$realEmbedded"
            val shouldLog = synchronized(fakeTransitionLogSignatures) {
                if (fakeTransitionLogSignatures[fakeView] == signature) {
                    false
                } else {
                    fakeTransitionLogSignatures[fakeView] = signature
                    true
                }
            }
            if (shouldLog) {
                HookLogger.d(
                    TAG,
                    "fake 渐变封面已同步: source=$source, state=$stateClass, " +
                        "targetSmall=$targetSmallIsland, targets=${targets.size}, " +
                        "selected=$resourceName@${System.identityHashCode(imageView)}, " +
                        "embedded=$embedded, realEmbedded=$realEmbedded",
                )
            }
        }
    }

    fun releaseAll() {
        val holders = synchronized(trackedHolders) {
            trackedHolders.mapNotNull { (holder, tracked) ->
                tracked.dataRef.get()?.let { Triple(holder, it, tracked.accessor) }
            }
        }
        runOnMain {
            IslandAlbumCoverRotationController.cleanup()
            restoreAllGradientCovers()
            restoringNative.set(true)
            try {
                holders.forEach { (holder, data, accessor) ->
                    runCatching { accessor.setFixIconMethod.invoke(holder, data) }
                        .onFailure { HookLogger.e(TAG, "恢复原生超级岛封面失败", it) }
                }
            } finally {
                restoringNative.remove()
            }
        }
    }

    fun cleanup() {
        IslandAlbumCoverRotationController.cleanup()
        restoreAllGradientCovers()
        cachedBigVisual = null
        cachedSmallVisual = null
        synchronized(trackedHolders) {
            trackedHolders.clear()
        }
        synchronized(captureGenerationByView) {
            captureGenerationByView.clear()
        }
        synchronized(gradientStates) {
            gradientStates.clear()
        }
        synchronized(fakeTransitionLogSignatures) {
            fakeTransitionLogSignatures.clear()
        }
        synchronized(artworkDiagnosticStates) {
            artworkDiagnosticStates.clear()
        }
        synchronized(artworkIdentityByView) {
            artworkIdentityByView.clear()
        }
        EmbeddedIslandAlbumCoverController.cleanup()
    }

    private fun applyStyle(
        accessor: CoverAccessor,
        holder: Any,
        dynamicIslandData: Any,
        targetField: Field,
        targetMethodName: String,
    ) {
        if (!isMediaAlbum(accessor, holder)) return
        synchronized(trackedHolders) {
            trackedHolders[holder] = TrackedHolder(WeakReference(dynamicIslandData), accessor)
        }

        val fixIcon = targetField.get(holder) as? ImageView ?: return
        val style = currentStyle()
        val artworkIdentity = resolveArtworkIdentity(fixIcon, dynamicIslandData)
        synchronized(artworkIdentityByView) {
            if (artworkIdentity == null) {
                artworkIdentityByView.remove(fixIcon)
            } else {
                artworkIdentityByView[fixIcon] = artworkIdentity
            }
        }
        if (style == RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT) {
            ensureArtworkContinuity(fixIcon, "after $targetMethodName")
        }
        logArtworkBindingState(fixIcon, targetMethodName, style)
        val fakeContentView = findFakeContentView(fixIcon)
        if (style != RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT || fakeContentView == null) {
            scheduleNativeArtworkCapture(fixIcon, dynamicIslandData)
        }
        if (style != RootConstants.ISLAND_ALBUM_COVER_STYLE_ROTATING_CIRCLE) {
            IslandAlbumCoverRotationController.detach(fixIcon)
        }
        if (style != RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT) {
            restoreGradientCover(fixIcon)
        }

        when (style) {
            RootConstants.ISLAND_ALBUM_COVER_STYLE_CIRCLE -> {
                applyCircleOutline(fixIcon)
            }

            RootConstants.ISLAND_ALBUM_COVER_STYLE_APP_ICON -> {
                showAppIcon(accessor, holder, dynamicIslandData)
            }

            RootConstants.ISLAND_ALBUM_COVER_STYLE_ROTATING_CIRCLE -> {
                applyCircleOutline(fixIcon)
                IslandAlbumCoverRotationController.attach(fixIcon)
            }

            RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT -> {
                applyGradientCover(accessor, holder, fixIcon, fakeContentView)
            }
        }
        if (BuildConfig.DEBUG) {
            val state = gradientStates[fixIcon]
            HookLogger.d(
                TAG,
                "封面样式入口命中: method=$targetMethodName, style=$style, " +
                    "target=${fixIcon.javaClass.simpleName}@${System.identityHashCode(fixIcon)}, " +
                    "fake=${fakeContentView != null}, observing=${state?.preDrawListener != null}",
            )
        }
    }

    private data class ArtworkIdentity(
        val packageName: String,
        val title: String,
    )

    private fun resolveArtworkIdentity(
        fixIcon: ImageView,
        dynamicIslandData: Any,
    ): ArtworkIdentity? {
        val packageName = IslandProbeUtils.extractMediaIslandInfo(dynamicIslandData)
            ?.packageName
            ?.takeIf(String::isNotBlank)
            ?: return null
        val token = MediaMetadataHelper.currentArtworkCaptureToken(
            fixIcon.context,
            packageName,
        ) ?: return null
        val lyricSong = LyriconDataBridge.currentSong
        if (IslandSlotContentAssembler.shouldRejectArtworkForTitleMismatch(
                lyricTitle = lyricSong?.name ?: LyriconDataBridge.currentSongName,
                mediaTitle = token.title,
                lyricArtist = lyricSong?.artist,
                mediaArtist = token.artist,
                mediaAlbum = token.album,
            )
        ) {
            return null
        }
        return ArtworkIdentity(packageName = packageName, title = token.title)
    }

    private fun ensureArtworkContinuity(imageView: ImageView, source: String): Boolean {
        if (imageView.drawable.hasUsableArtworkPixels()) return false
        val identity = synchronized(artworkIdentityByView) {
            artworkIdentityByView[imageView]
        } ?: return false
        val bitmap = MediaMetadataHelper.currentCachedArtwork(
            context = imageView.context,
            packageName = identity.packageName,
            expectedTitle = identity.title,
        ) ?: return false
        imageView.setImageBitmap(bitmap)
        if (BuildConfig.DEBUG) {
            HookLogger.d(
                TAG,
                "渐变封面复用当前歌曲缓存: source=$source, package=${identity.packageName}, " +
                    "title=${identity.title}, size=${bitmap.width}x${bitmap.height}",
            )
        }
        return true
    }

    private fun Drawable?.hasUsableArtworkPixels(): Boolean {
        val drawable = this ?: return false
        if (drawable !is BitmapDrawable) {
            return drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0
        }
        val bitmap = drawable.bitmap ?: return false
        return hasVisibleArtworkPixels(bitmap)
    }

    private fun scheduleNativeArtworkCapture(fixIcon: ImageView, dynamicIslandData: Any) {
        val packageName = IslandProbeUtils.extractMediaIslandInfo(dynamicIslandData)
            ?.packageName
            ?.takeIf(String::isNotBlank)
            ?: return
        val generation = synchronized(captureGenerationByView) {
            ((captureGenerationByView[fixIcon] ?: 0) + 1).also {
                captureGenerationByView[fixIcon] = it
            }
        }
        CAPTURE_DELAYS_MS.forEach { delayMs ->
            fixIcon.postDelayed(
                {
                    val stillCurrent = synchronized(captureGenerationByView) {
                        captureGenerationByView[fixIcon] == generation
                    }
                    if (!stillCurrent) return@postDelayed
                    val token = MediaMetadataHelper.currentArtworkCaptureToken(
                        fixIcon.context,
                        packageName,
                    ) ?: return@postDelayed
                    val lyricSong = LyriconDataBridge.currentSong
                    if (IslandSlotContentAssembler.shouldRejectArtworkForTitleMismatch(
                            lyricTitle = lyricSong?.name
                                ?: LyriconDataBridge.currentSongName,
                            mediaTitle = token.title,
                            lyricArtist = lyricSong?.artist,
                            mediaArtist = token.artist,
                            mediaAlbum = token.album,
                        )
                    ) {
                        return@postDelayed
                    }
                    val capture = fixIcon.drawable.toCaptureBitmap(fixIcon) ?: return@postDelayed
                    val cached = MediaMetadataHelper.cacheCapturedArtwork(
                        context = fixIcon.context,
                        token = token,
                        bitmap = capture.bitmap,
                        logger = HookLogger,
                    )
                    if (!cached && capture.owned) capture.bitmap.recycle()
                    if (cached) {
                        synchronized(captureGenerationByView) {
                            if (captureGenerationByView[fixIcon] == generation) {
                                captureGenerationByView[fixIcon] = generation + 1
                            }
                        }
                    }
                },
                delayMs,
            )
        }
    }

    private fun Drawable?.toCaptureBitmap(view: ImageView): CapturedBitmap? {
        val drawable = this ?: return null
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
                ?.takeIf(::hasVisibleArtworkPixels)
                ?.let { CapturedBitmap(it, owned = false) }
        }
        val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 }
            ?: view.width.takeIf { it > 0 }
            ?: return null
        val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 }
            ?: view.height.takeIf { it > 0 }
            ?: return null
        val maxDimension = maxOf(sourceWidth, sourceHeight)
        val scale = if (maxDimension > CAPTURE_MAX_DIMENSION) {
            CAPTURE_MAX_DIMENSION.toFloat() / maxDimension.toFloat()
        } else {
            1f
        }
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val oldBounds = android.graphics.Rect(drawable.bounds)
        return runCatching {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            bitmap.takeIf(::hasVisibleArtworkPixels)?.let {
                CapturedBitmap(it, owned = true)
            }
        }.getOrNull().also {
            drawable.bounds = oldBounds
            if (it == null) bitmap.recycle()
        }
    }

    private fun hasVisibleArtworkPixels(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        return runCatching {
            val columns = minOf(bitmap.width, 4)
            val rows = minOf(bitmap.height, 4)
            repeat(rows) { row ->
                val y = if (rows == 1) 0 else row * (bitmap.height - 1) / (rows - 1)
                repeat(columns) { column ->
                    val x = if (columns == 1) 0 else column * (bitmap.width - 1) / (columns - 1)
                    if ((bitmap.getPixel(x, y) ushr 24) != 0) return true
                }
            }
            false
        }.getOrElse {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            try {
                hasVisibleArtworkPixels(copy)
            } finally {
                copy.recycle()
            }
        }
    }

    private data class CapturedBitmap(
        val bitmap: Bitmap,
        val owned: Boolean,
    )

    private data class CoverVisualSnapshot(
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
        val coverWidth: Int,
        val coverHeight: Int,
        val smallIsland: Boolean,
        val gradientBandFraction: Float,
        val islandColor: Int,
    )

    private data class TextShadowTargetSnapshot(
        val view: View,
        val paint: Paint,
        val radius: Float,
        val dx: Float,
        val dy: Float,
        val color: Int,
    )

    private fun applyGradientCover(
        accessor: CoverAccessor,
        holder: Any,
        fixIcon: ImageView,
        fakeContentView: ViewGroup?,
    ) {
        val iconContainer = accessor.iconContainerField.get(holder) as? View ?: return
        val existingState = gradientStates[fixIcon]
        if (existingState == null || existingState.iconContainer !== iconContainer) {
            gradientStates.remove(fixIcon)?.let { restoreGradientState(it) }
            gradientStates[fixIcon] = GradientCoverState.capture(fixIcon, iconContainer)
        }
        val state = gradientStates[fixIcon] ?: return
        state.applyLeftContentTextShadow()
        if (fakeContentView != null) {
            // Style the freshly rebound fake holder synchronously. setFixIcon returns on the
            // UI thread before the next frame, so this closes the default-style exposure window.
            state.removePreDrawObserver()
            applyFakeTransitionCover(
                fakeView = fakeContentView,
                source = "after fake holder bind",
            )
            return
        }

        ensureArtworkContinuity(fixIcon, "real")
        val smallIsland = state.isSmallIslandState()
        val embeddedHost = if (smallIsland) {
            (callViewGetter(holder, "getSmallContainer") as? ViewGroup)
                ?: (state.module as? android.widget.FrameLayout)
                    ?.takeIf(::isSmallIslandModule)
        } else {
            callViewGetter(holder, "getBigContainer") as? ViewGroup
        }
        if (embeddedHost != null &&
            EmbeddedIslandAlbumCoverController.apply(embeddedHost, fixIcon, smallIsland)
        ) {
            // The embedded child now owns rendering. Restore the native ImageView's visual
            // properties without changing its parent or measurement contract.
            state.removePreDrawObserver()
            state.restoreCoverVisuals()
            return
        }

        if (smallIsland) {
            // A cold small-island holder can expose its stable 104x104 module before
            // getSmallContainer(). If neither embedded host works, keep native visuals; the old
            // scale/translation fallback is the confirmed cause of the one-frame flash.
            state.removePreDrawObserver()
            state.restoreCoverVisuals()
            return
        }

        val localSnapshot = resolveLocalBigSnapshot(fixIcon, fixIcon.rootView)
        val snapshot = localSnapshot ?: cachedBigVisual
        if (snapshot != null && !snapshot.smallIsland) {
            state.applySnapshot(snapshot)
            state.removePreDrawObserver()
        } else {
            // Fallback for a host revision whose content containers are not discoverable yet.
            state.scheduleLayout()
        }
    }

    private fun restoreGradientCover(fixIcon: ImageView) {
        val state = gradientStates.remove(fixIcon) ?: return
        restoreGradientState(state)
    }

    private fun restoreAllGradientCovers() {
        gradientStates.values.toList().forEach { restoreGradientState(it) }
        gradientStates.clear()
    }

    private fun restoreGradientState(state: GradientCoverState) {
        state.removePreDrawObserver()
        state.restoreLeftContentTextShadow()
        EmbeddedIslandAlbumCoverController.restoreForSource(state.fixIcon)
        state.iconContainer.translationX = state.originalIconContainerTranslationX
        state.iconContainer.translationY = state.originalIconContainerTranslationY

        val fixLp = state.fixIcon.layoutParams
        if (fixLp != null) {
            fixLp.width = state.originalFixIconWidth
            fixLp.height = state.originalFixIconHeight
            if (fixLp is ViewGroup.MarginLayoutParams) {
                fixLp.marginStart = state.originalFixIconMarginStart
                fixLp.marginEnd = state.originalFixIconMarginEnd
                fixLp.topMargin = state.originalFixIconMarginTop
                fixLp.bottomMargin = state.originalFixIconMarginBottom
            }
            state.fixIcon.layoutParams = fixLp
        }
        state.restoreCoverVisuals()
        state.fixIcon.visibility = state.originalFixIconVisibility

        state.iconContainer.setPadding(
            state.originalIconContainerPaddingLeft,
            state.originalIconContainerPaddingTop,
            state.originalIconContainerPaddingRight,
            state.originalIconContainerPaddingBottom
        )
        state.module?.setPadding(
            state.originalModulePaddingLeft,
            state.originalModulePaddingTop,
            state.originalModulePaddingRight,
            state.originalModulePaddingBottom
        )
        (state.iconContainer as? ViewGroup)?.clipChildren = state.originalIconContainerClipChildren
        (state.module as? ViewGroup)?.clipChildren = state.originalModuleClipChildren
        (state.module as? ViewGroup)?.clipToPadding = state.originalModuleClipToPadding
        (state.moduleParent as? ViewGroup)?.clipChildren = state.originalModuleParentClipChildren
        (state.moduleParent as? ViewGroup)?.clipToPadding = state.originalModuleParentClipToPadding

        val iconLp = state.iconContainer.layoutParams
        if (iconLp != null) {
            iconLp.width = state.originalIconContainerWidth
            iconLp.height = state.originalIconContainerHeight
            state.originalIconContainerGravity?.let { setGravity(iconLp, it) }
            if (iconLp is ViewGroup.MarginLayoutParams) {
                iconLp.marginStart = state.originalIconContainerMarginStart
                iconLp.marginEnd = state.originalIconContainerMarginEnd
                iconLp.topMargin = state.originalIconContainerMarginTop
                iconLp.bottomMargin = state.originalIconContainerMarginBottom
            }
            state.iconContainer.layoutParams = iconLp
        }

    }

    private fun GradientCoverState.applyLayout(
        geometry: IslandGradientGeometryCandidate,
        logFinalPlacement: Boolean,
        smallIslandOverride: Boolean? = null,
    ) {
        val density = fixIcon.resources.displayMetrics.density
        val moduleView = module ?: ((fixIcon.parent as? View)?.parent as? View)
            ?: return
        val moduleWidth = moduleView.width.takeIf { it > 0 } ?: moduleView.measuredWidth
        val moduleHeight = moduleView.height.takeIf { it > 0 } ?: moduleView.measuredHeight
        val iconWidth = fixIcon.expectedLayoutWidth()
        val iconHeight = fixIcon.expectedLayoutHeight()
        val iconLocation = fixIcon.baseLocationInWindow()
        val smallIsland = smallIslandOverride ?: isSmallIslandModule(moduleView)
        val islandWindowX = geometry.left.toFloat()
        val placement = IslandGradientCoverLayout.resolve(
            moduleWidth = moduleWidth,
            moduleHeight = moduleHeight,
            moduleWindowY = geometry.top.toFloat(),
            islandWindowX = islandWindowX,
            iconWindowX = iconLocation.first,
            iconWindowY = iconLocation.second,
            iconWidth = iconWidth,
            iconHeight = iconHeight,
            isSmallIsland = smallIsland,
            density = density,
        ) ?: return

        (moduleView as? ViewGroup)?.clipChildren = false
        (moduleView as? ViewGroup)?.clipToPadding = false
        (moduleView.parent as? ViewGroup)?.clipChildren = false
        (moduleView.parent as? ViewGroup)?.clipToPadding = false
        (iconContainer as? ViewGroup)?.clipChildren = false

        if (smallIsland) {
            val sameSmallVisualShape = (appliedSmall == true) &&
                (appliedCoverWidth == placement.coverWidth) &&
                (appliedCoverHeight == placement.coverHeight) &&
                (fixIcon.scaleX == placement.iconScaleX) &&
                (fixIcon.scaleY == placement.iconScaleY) &&
                (fixIcon.scaleType == ImageView.ScaleType.CENTER_CROP) &&
                (fixIcon.outlineProvider === circleOutlineProvider) &&
                fixIcon.clipToOutline &&
                fixIcon.foreground == null
            if (sameSmallVisualShape) {
                if (fixIcon.translationX != placement.iconTranslationX) {
                    fixIcon.translationX = placement.iconTranslationX
                }
                if (fixIcon.translationY != placement.iconTranslationY) {
                    fixIcon.translationY = placement.iconTranslationY
                }
                if (logFinalPlacement) {
                    cacheCurrentVisual(smallIsland, placement)
                    logPlacement(moduleView, islandWindowX, smallIsland, placement)
                }
                return
            }

            val alreadyMatchesSmall = (appliedSmall == true) &&
                (appliedCoverWidth == placement.coverWidth) &&
                (appliedCoverHeight == placement.coverHeight) &&
                (fixIcon.scaleX == placement.iconScaleX) &&
                (fixIcon.scaleY == placement.iconScaleY) &&
                (fixIcon.translationX == placement.iconTranslationX) &&
                (fixIcon.translationY == placement.iconTranslationY) &&
                (fixIcon.scaleType == ImageView.ScaleType.CENTER_CROP) &&
                fixIcon.clipToOutline

            if (alreadyMatchesSmall) {
                if (logFinalPlacement) {
                    cacheCurrentVisual(smallIsland, placement)
                    logPlacement(moduleView, islandWindowX, smallIsland, placement)
                }
                return
            }

            fixIcon.pivotX = 0f
            fixIcon.pivotY = 0f
            fixIcon.scaleX = placement.iconScaleX
            fixIcon.scaleY = placement.iconScaleY
            fixIcon.translationX = placement.iconTranslationX
            fixIcon.translationY = placement.iconTranslationY
            fixIcon.scaleType = ImageView.ScaleType.CENTER_CROP
            fixIcon.foreground = null
            fixIcon.outlineProvider = circleOutlineProvider
            fixIcon.clipToOutline = true
            fixIcon.invalidateOutline()

            appliedSmall = true
            appliedCoverWidth = placement.coverWidth
            appliedCoverHeight = placement.coverHeight

            if (logFinalPlacement) {
                cacheCurrentVisual(smallIsland, placement)
                logPlacement(moduleView, islandWindowX, smallIsland, placement)
            }
            return
        }

        val sameBigVisualShape = (appliedSmall == false) &&
            (appliedCoverWidth == placement.coverWidth) &&
            (appliedCoverHeight == placement.coverHeight) &&
            (fixIcon.scaleX == placement.iconScaleX) &&
            (fixIcon.scaleY == placement.iconScaleY) &&
            (fixIcon.scaleType == ImageView.ScaleType.CENTER_CROP) &&
            (fixIcon.outlineProvider === leftRoundedCoverOutlineProvider) &&
            fixIcon.clipToOutline &&
            (fixIcon.foreground is RightEdgeGradientDrawable)
        if (sameBigVisualShape) {
            if (fixIcon.translationX != placement.iconTranslationX) {
                fixIcon.translationX = placement.iconTranslationX
            }
            if (fixIcon.translationY != placement.iconTranslationY) {
                fixIcon.translationY = placement.iconTranslationY
            }
            if (logFinalPlacement) {
                cacheCurrentVisual(smallIsland, placement)
                logPlacement(moduleView, islandWindowX, smallIsland, placement)
            }
            return
        }

        val alreadyMatchesBig = (appliedSmall == false) &&
            (appliedCoverWidth == placement.coverWidth) &&
            (appliedCoverHeight == placement.coverHeight) &&
            (fixIcon.scaleX == placement.iconScaleX) &&
            (fixIcon.scaleY == placement.iconScaleY) &&
            (fixIcon.translationX == placement.iconTranslationX) &&
            (fixIcon.translationY == placement.iconTranslationY) &&
            (fixIcon.scaleType == ImageView.ScaleType.CENTER_CROP) &&
            fixIcon.clipToOutline

        if (alreadyMatchesBig) {
            if (logFinalPlacement) {
                cacheCurrentVisual(smallIsland, placement)
                logPlacement(moduleView, islandWindowX, smallIsland, placement)
            }
            return
        }

        fixIcon.pivotX = 0f
        fixIcon.pivotY = 0f
        fixIcon.scaleX = placement.iconScaleX
        fixIcon.scaleY = placement.iconScaleY
        fixIcon.translationX = placement.iconTranslationX
        fixIcon.translationY = placement.iconTranslationY
        fixIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        fixIcon.outlineProvider = leftRoundedCoverOutlineProvider
        fixIcon.clipToOutline = true
        fixIcon.invalidateOutline()
        fixIcon.foreground = RightEdgeGradientDrawable(
            bandFraction = placement.gradientBandFraction,
            color = resolveIslandColor(fixIcon),
        )

        appliedSmall = false
        appliedCoverWidth = placement.coverWidth
        appliedCoverHeight = placement.coverHeight

        if (logFinalPlacement) {
            cacheCurrentVisual(smallIsland, placement)
            logPlacement(moduleView, islandWindowX, smallIsland, placement)
        }
    }

    private fun View.baseLocationInWindow(): Pair<Float, Float> {
        val location = IntArray(2)
        getLocationInWindow(location)
        return (location[0] - translationX) to (location[1] - translationY)
    }

    private fun View.baseLocationRelativeTo(root: View): Pair<Float, Float>? {
        var x = 0f
        var y = 0f
        var current: View = this
        while (current !== root) {
            x += current.left
            y += current.top
            if (current !== this) {
                x += current.translationX
                y += current.translationY
            }
            current = current.parent as? View ?: return null
        }
        return x to y
    }

    private fun View.baseLocationFor(root: View): Pair<Float, Float> {
        return baseLocationRelativeTo(root) ?: baseLocationInWindow()
    }

    private fun ImageView.expectedLayoutWidth(): Int {
        return IslandGradientCoverLayout.resolveIconDimension(
            actualSize = width,
            measuredSize = measuredWidth,
            layoutParamSize = layoutParams?.width ?: 0,
            minimumSize = minimumWidth,
            density = resources.displayMetrics.density,
        )
    }

    private fun ImageView.expectedLayoutHeight(): Int {
        return IslandGradientCoverLayout.resolveIconDimension(
            actualSize = height,
            measuredSize = measuredHeight,
            layoutParamSize = layoutParams?.height ?: 0,
            minimumSize = minimumHeight,
            density = resources.displayMetrics.density,
        )
    }

    private fun applyInitializedTemplateGeometry(holder: Any) {
        if (currentStyle() != RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT) return
        val bigContainer = callViewGetter(holder, "getBigContainer") as? ViewGroup
        val smallContainer = callViewGetter(holder, "getSmallContainer") as? ViewGroup
        val bigApplied = applyInitializedContainer(bigContainer, smallIsland = false)
        val smallApplied = applyInitializedContainer(smallContainer, smallIsland = true)
        if (BuildConfig.DEBUG && (bigApplied > 0 || smallApplied > 0)) {
            HookLogger.d(
                TAG,
                "首帧渐变已按最终容器同步: big=$bigApplied, small=$smallApplied, " +
                    "bigSize=${bigContainer?.width}x${bigContainer?.height}, " +
                    "smallSize=${smallContainer?.width}x${smallContainer?.height}",
            )
        }
    }

    private fun applyInitializedContainer(
        container: ViewGroup?,
        smallIsland: Boolean,
    ): Int {
        val target = container ?: return 0
        val width = target.width.takeIf { it > 0 } ?: target.measuredWidth
        val height = target.height.takeIf { it > 0 } ?: target.measuredHeight
        if (!target.isAttachedToWindow || width <= 0 || height <= 0) return 0

        val trackedTargets = collectCoverImageViews(target).mapNotNull { (imageView, _) ->
            val state = synchronized(gradientStates) {
                gradientStates[imageView]
            } ?: return@mapNotNull null
            imageView to state
        }
        if (trackedTargets.isEmpty()) return 0

        var applied = 0
        val embeddedViews = HashSet<ImageView>()
        trackedTargets.forEach { (imageView, state) ->
            state.applyLeftContentTextShadow()
            if (EmbeddedIslandAlbumCoverController.apply(target, imageView, smallIsland)) {
                state.removePreDrawObserver()
                state.restoreCoverVisuals()
                embeddedViews += imageView
                applied += 1
            }
        }
        if (embeddedViews.size == trackedTargets.size) return applied
        if (smallIsland) {
            trackedTargets.forEach { (imageView, state) ->
                if (imageView !in embeddedViews) {
                    state.removePreDrawObserver()
                    state.restoreCoverVisuals()
                }
            }
            return applied
        }

        val geometry = resolveInitializedContainerGeometry(target, width, height, smallIsland)
            ?: return applied
        trackedTargets.forEach { (imageView, state) ->
            if (imageView in embeddedViews) return@forEach
            logArtworkBindingState(
                imageView,
                if (smallIsland) "final_small_container_before" else "final_big_container_before",
                currentStyle(),
            )
            state.applyLayout(
                geometry = geometry,
                logFinalPlacement = true,
                smallIslandOverride = smallIsland,
            )
            state.removePreDrawObserver()
            logArtworkBindingState(
                imageView,
                if (smallIsland) "final_small_container_after" else "final_big_container_after",
                currentStyle(),
            )
            applied += 1
        }
        return applied
    }

    private fun resolveInitializedContainerGeometry(
        target: ViewGroup,
        width: Int,
        height: Int,
        smallIsland: Boolean,
    ): IslandGradientGeometryCandidate? {
        val associated = findAssociatedBackgroundView(target) ?: return null
        val background = associated.view
        if (!background.isAttachedToWindow ||
            background.visibility != View.VISIBLE ||
            !background.isShown
        ) {
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "跳过非法封面几何: small=$smallIsland, backgroundVisibility=${background.visibility}, " +
                        "attached=${background.isAttachedToWindow}, shown=${background.isShown}",
                )
            }
            return null
        }

        val actualLeft = getViewInt(background, "getActualLeft") ?: return null
        val actualTop = getViewInt(background, "getActualTop") ?: return null
        val actualRight = getViewInt(background, "getActualWidth") ?: return null
        val actualBottom = getViewInt(background, "getActualHeight") ?: return null
        if (actualLeft == 0 && actualTop == 0 && actualRight == 0 && actualBottom == 0) {
            if (BuildConfig.DEBUG) HookLogger.d(TAG, "跳过非法封面几何: actual=0,0,0,0")
            return null
        }
        val actual = IslandGradientCoverLayout.fromActualEdges(
            actualLeft,
            actualTop,
            actualRight,
            actualBottom,
        )
        val tolerance = maxOf(12, height / 10)
        if (actual.height <= 0 || kotlin.math.abs(actual.height - height) > tolerance) {
            if (BuildConfig.DEBUG) HookLogger.d(TAG, "跳过非法封面几何: actual=$actual module=${width}x$height")
            return null
        }
        if (smallIsland) {
            if (kotlin.math.abs(actual.width - width) <= tolerance) return actual
            val location = target.baseLocationInWindow()
            return IslandGradientGeometryCandidate(
                left = location.first.roundToInt(),
                top = location.second.roundToInt(),
                width = width,
                height = height,
            )
        }
        if (actual.width <= height * 1.5f) {
            if (BuildConfig.DEBUG) HookLogger.d(TAG, "跳过非法封面几何: 大岛宽度不足 actual=$actual")
            return null
        }
        return actual
    }

    private fun callViewGetter(receiver: Any, methodName: String): View? {
        return runCatching {
            receiver.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            }?.invoke(receiver) as? View
        }.getOrNull()
    }


    private fun captureLeftContentTextTargets(module: View?): List<TextShadowTargetSnapshot> {
        val moduleRoot = module as? ViewGroup ?: return emptyList()
        val textRoot = IslandViewHelper.findViewByName(
            moduleRoot,
            "island_container_module_text",
        ) ?: return emptyList()
        val result = ArrayList<TextShadowTargetSnapshot>()
        val queue = ArrayDeque<View>()
        queue.add(textRoot)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            when (current) {
                is TextView -> result += TextShadowTargetSnapshot(
                    view = current,
                    paint = current.paint,
                    radius = current.paint.getShadowLayerRadius(),
                    dx = current.paint.getShadowLayerDx(),
                    dy = current.paint.getShadowLayerDy(),
                    color = current.paint.getShadowLayerColor(),
                )
                is LyricLineView -> result += TextShadowTargetSnapshot(
                    view = current,
                    paint = current.textPaint,
                    radius = current.textPaint.getShadowLayerRadius(),
                    dx = current.textPaint.getShadowLayerDx(),
                    dy = current.textPaint.getShadowLayerDy(),
                    color = current.textPaint.getShadowLayerColor(),
                )
            }
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) {
                    queue.addLast(current.getChildAt(index))
                }
            }
        }
        return result.distinctBy { System.identityHashCode(it.view) }
    }

    private fun collectCoverImageViews(root: ViewGroup): List<Pair<ImageView, String>> {
        val result = ArrayList<Pair<ImageView, String>>()
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current is ImageView && current.id != View.NO_ID) {
                val name = runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull()
                if (name == "island_fix_icon" || name == "island_small_icon") {
                    result += current to name
                }
            }
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) {
                    queue.addLast(current.getChildAt(index))
                }
            }
        }
        return result
    }

    private fun hasNamedAncestor(view: View, stop: View, resourceName: String): Boolean {
        var current = view.parent as? View
        while (current != null) {
            if (current.id != View.NO_ID) {
                val name = runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull()
                if (name == resourceName) return true
            }
            if (current === stop) break
            current = current.parent as? View
        }
        return false
    }

    private fun findFakeContentView(view: View): ViewGroup? {
        var current: View? = view
        while (current != null) {
            if (current.javaClass.name == IslandGradientCoverRuntimeIdentifiers.FAKE_CONTENT_VIEW_CLASS) {
                return current as? ViewGroup
            }
            current = current.parent as? View
        }
        return null
    }

    private fun syncRealTransitionCover(
        realOwner: View?,
        smallIsland: Boolean,
        source: String,
    ): Boolean {
        val root = realOwner as? ViewGroup ?: return false
        val targets = collectCoverImageViews(root)
        val selected = selectFakeCoverTarget(root, targets, smallIsland) ?: return false
        val imageView = selected.first
        val sharedIdentity = synchronized(artworkIdentityByView) {
            artworkIdentityByView[imageView]
                ?: targets.firstNotNullOfOrNull { (target, _) -> artworkIdentityByView[target] }
        }
        if (sharedIdentity != null) {
            synchronized(artworkIdentityByView) {
                artworkIdentityByView.putIfAbsent(imageView, sharedIdentity)
            }
        }
        ensureArtworkContinuity(imageView, "real-state:$source")
        val host = findNamedContainer(
            root,
            if (smallIsland) "small_container" else "big_container",
        ) ?: return false
        val embedded = EmbeddedIslandAlbumCoverController.apply(host, imageView, smallIsland)
        if (embedded) {
            synchronized(gradientStates) { gradientStates[imageView] }?.let { state ->
                state.applyLeftContentTextShadow()
                state.removePreDrawObserver()
                state.restoreCoverVisuals()
            }
        }
        return embedded
    }

    private fun resolveFakeEmbeddedHost(
        imageView: ImageView,
        fakeView: ViewGroup,
        smallIsland: Boolean,
    ): ViewGroup? {
        // The fake hierarchy is rebuilt through Expanded/Big/Small stages. Only exact role
        // containers are stable enough to host the cover; geometry-based ancestors were proven
        // to select the wrong stage intermittently.
        val names = if (smallIsland) {
            arrayOf("small_container", "fake_small_container")
        } else {
            arrayOf("big_container", "fake_big_container")
        }
        return names.firstNotNullOfOrNull { name -> findNamedContainer(fakeView, name) }
    }

    private fun findNamedContainer(root: ViewGroup, name: String): ViewGroup? {
        val direct = if (root.id != View.NO_ID) {
            runCatching { root.resources.getResourceEntryName(root.id) == name }.getOrDefault(false)
        } else {
            false
        }
        if (direct) return root
        return (IslandViewHelper.findViewByName(root, name) as? ViewGroup)
    }

    private fun resolveFakeTransitionOwner(fakeView: ViewGroup, realView: View?): View? {
        return realView ?: runCatching {
            fakeView.javaClass.methods.firstOrNull {
                it.name == "getRealView" && it.parameterTypes.isEmpty()
            }?.invoke(fakeView) as? View
        }.getOrNull()
    }

    private fun isMediaFakeTransition(fakeView: ViewGroup, realView: View?): Boolean {
        val owner = resolveFakeTransitionOwner(fakeView, realView) ?: return false
        val data = IslandProbeUtils.getCurrentIslandData(owner) ?: return false
        return IslandProbeUtils.isMediaIsland(data)
    }

    private fun resolveFakeTransitionStateClass(fakeView: ViewGroup, realView: View?): String? {
        val owner = resolveFakeTransitionOwner(fakeView, realView)
        val state = owner?.let { target ->
            runCatching {
                target.javaClass.methods.firstOrNull {
                    it.name == "getState" && it.parameterTypes.isEmpty()
                }?.invoke(target)
            }.getOrNull()
        }
        return state?.javaClass?.name
    }

    private fun selectFakeCoverTarget(
        fakeView: ViewGroup,
        targets: List<Pair<ImageView, String>>,
        smallIsland: Boolean,
    ): Pair<ImageView, String>? {
        return if (smallIsland) {
            targets.firstOrNull { (imageView, resourceName) ->
                resourceName == "island_small_icon" ||
                    hasNamedAncestor(imageView, fakeView, "small_container") ||
                    hasNamedAncestor(imageView, fakeView, "fake_small_container")
            }
        } else {
            targets.firstOrNull { (imageView, resourceName) ->
                resourceName == "island_fix_icon" &&
                    !hasNamedAncestor(imageView, fakeView, "small_container") &&
                    !hasNamedAncestor(imageView, fakeView, "fake_small_container")
            }
        }
    }

    private fun resolveLocalSmallSnapshot(imageView: ImageView, stop: View): CoverVisualSnapshot? {
        var namedAncestor: View? = null
        var current = imageView.parent as? View
        while (current != null) {
            val name = if (current.id == View.NO_ID) null else {
                runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull()
            }
            if (name == "small_container") {
                namedAncestor = current
                break
            }
            if (current === stop) break
            current = current.parent as? View
        }
        val globalContainer = (stop as? ViewGroup)?.let {
            IslandViewHelper.findViewByName(it, "small_container")
        }
        val directParent = imageView.parent as? View
        val container = namedAncestor
            ?: globalContainer
            ?: ((directParent?.parent as? View) ?: directParent)
            ?: return null
        val width = container.width.takeIf { it > 0 } ?: container.measuredWidth
        val height = container.height.takeIf { it > 0 } ?: container.measuredHeight
        if (width <= 0 || height <= 0) return null
        val dimensionTolerance = maxOf(4, height / 10)
        if (abs(width - height) > dimensionTolerance) return null
        val diameter = minOf(width, height)
        val iconWidth = imageView.expectedLayoutWidth()
        val iconHeight = imageView.expectedLayoutHeight()
        val containerLocation = container.baseLocationFor(stop)
        val imageLocation = imageView.baseLocationFor(stop)
        return CoverVisualSnapshot(
            scaleX = diameter.toFloat() / iconWidth,
            scaleY = diameter.toFloat() / iconHeight,
            translationX = containerLocation.first - imageLocation.first,
            translationY = containerLocation.second - imageLocation.second,
            coverWidth = diameter,
            coverHeight = diameter,
            smallIsland = true,
            gradientBandFraction = 0f,
            islandColor = Color.BLACK,
        )
    }

    private fun resolveLocalBigSnapshot(imageView: ImageView, stop: View): CoverVisualSnapshot? {
        var namedAncestor: View? = null
        var nearestWideContainer: View? = null
        var current = imageView.parent as? View
        while (current != null) {
            val name = if (current.id == View.NO_ID) null else {
                runCatching { current.resources.getResourceEntryName(current.id) }.getOrNull()
            }
            if (name == "big_container") {
                namedAncestor = current
                break
            }
            if (nearestWideContainer == null && current is ViewGroup) {
                val width = current.width.takeIf { it > 0 } ?: current.measuredWidth
                val height = current.height.takeIf { it > 0 } ?: current.measuredHeight
                if (height in 60..200 && width > height * 1.5f) {
                    nearestWideContainer = current
                }
            }
            if (current === stop) break
            current = current.parent as? View
        }
        val globalContainer = (stop as? ViewGroup)?.let {
            IslandViewHelper.findViewByName(it, "big_container")
        }
        val host = namedAncestor ?: globalContainer ?: nearestWideContainer
        if (host == null) {
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "大岛本地快照无有效宿主: view=${System.identityHashCode(imageView)}",
                )
            }
            return null
        }
        val width = host.width.takeIf { it > 0 } ?: host.measuredWidth
        val height = host.height.takeIf { it > 0 } ?: host.measuredHeight
        if (width <= height * 1.5f || height <= 0) {
            if (BuildConfig.DEBUG) {
                HookLogger.d(
                    TAG,
                    "大岛本地快照尺寸无效: host=${System.identityHashCode(host)}, size=${width}x$height",
                )
            }
            return null
        }
        val iconWidth = imageView.expectedLayoutWidth()
        val iconHeight = imageView.expectedLayoutHeight()
        val coverWidth = height
        val containerLocation = host.baseLocationFor(stop)
        val imageLocation = imageView.baseLocationFor(stop)
        return CoverVisualSnapshot(
            scaleX = coverWidth.toFloat() / iconWidth,
            scaleY = height.toFloat() / iconHeight,
            translationX = containerLocation.first - imageLocation.first,
            translationY = containerLocation.second - imageLocation.second,
            coverWidth = coverWidth,
            coverHeight = height,
            smallIsland = false,
            gradientBandFraction = IslandGradientCoverLayout.gradientBandFraction(
                coverWidth = coverWidth,
                density = imageView.resources.displayMetrics.density,
            ),
            islandColor = resolveIslandColor(host),
        )
    }

    private fun applySnapshotToImage(
        imageView: ImageView,
        snapshot: CoverVisualSnapshot,
    ): Boolean {
        var changed = false
        if (!imageView.isPivotSet || imageView.pivotX != 0f || imageView.pivotY != 0f) {
            imageView.pivotX = 0f
            imageView.pivotY = 0f
            changed = true
        }
        if (imageView.scaleX != snapshot.scaleX) {
            imageView.scaleX = snapshot.scaleX
            changed = true
        }
        if (imageView.scaleY != snapshot.scaleY) {
            imageView.scaleY = snapshot.scaleY
            changed = true
        }
        if (imageView.translationX != snapshot.translationX) {
            imageView.translationX = snapshot.translationX
            changed = true
        }
        if (imageView.translationY != snapshot.translationY) {
            imageView.translationY = snapshot.translationY
            changed = true
        }
        if (imageView.scaleType != ImageView.ScaleType.CENTER_CROP) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            changed = true
        }
        val outlineProvider = if (snapshot.smallIsland) {
            circleOutlineProvider
        } else {
            leftRoundedCoverOutlineProvider
        }
        var outlineChanged = false
        if (imageView.outlineProvider !== outlineProvider) {
            imageView.outlineProvider = outlineProvider
            changed = true
            outlineChanged = true
        }
        if (!imageView.clipToOutline) {
            imageView.clipToOutline = true
            changed = true
            outlineChanged = true
        }
        if (snapshot.smallIsland) {
            if (imageView.foreground != null) {
                imageView.foreground = null
                changed = true
            }
        } else if ((imageView.foreground as? RightEdgeGradientDrawable)?.matches(snapshot) != true) {
            imageView.foreground = RightEdgeGradientDrawable(
                snapshot.gradientBandFraction,
                snapshot.islandColor,
            )
            changed = true
        }
        if (outlineChanged) {
            imageView.invalidateOutline()
        }
        return changed
    }

    private fun getViewInt(view: View, getterName: String): Int? {
        return runCatching {
            view.javaClass.methods.firstOrNull {
                it.name == getterName && it.parameterTypes.isEmpty()
            }?.invoke(view).let { it as? Number }?.toInt()
        }.getOrNull()
    }

    private data class AssociatedBackground(
        val view: View,
        val ownerClass: String,
    )

    private fun findAssociatedBackgroundView(view: View): AssociatedBackground? {
        var current: View? = view
        while (current != null) {
            if (current.javaClass.simpleName == "DynamicIslandBackgroundView") {
                return AssociatedBackground(current, current.javaClass.name)
            }
            val background = runCatching {
                current.javaClass.methods.firstOrNull {
                    it.name == "getBackgroundView" && it.parameterTypes.isEmpty()
                }?.invoke(current) as? View
            }.getOrNull()
            if (background != null) {
                return AssociatedBackground(background, current.javaClass.name)
            }
            current = current.parent as? View
        }
        return null
    }

    private fun isSmallIslandModule(module: View): Boolean {
        if (module.id == View.NO_ID) return false
        return runCatching {
            module.resources.getResourceEntryName(module.id) == "small_container"
        }.getOrDefault(false)
    }

    private fun setGravity(lp: ViewGroup.LayoutParams, gravity: Int) {
        runCatching { lp.javaClass.getField("gravity").setInt(lp, gravity) }
    }

    private fun getGravity(lp: ViewGroup.LayoutParams?): Int? {
        return lp?.let {
            runCatching { it.javaClass.getField("gravity").getInt(it) }.getOrNull()
        }
    }

    private class RightEdgeGradientDrawable(
        bandFraction: Float,
        private val color: Int,
    ) : Drawable() {
        private val bandFraction = bandFraction.coerceIn(0.01f, 1f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun matches(snapshot: CoverVisualSnapshot): Boolean {
            return !snapshot.smallIsland &&
                bandFraction == snapshot.gradientBandFraction.coerceIn(0.01f, 1f) &&
                color == snapshot.islandColor
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.isEmpty) {
                paint.shader = null
                return
            }
            val bandWidth = (bounds.width() * bandFraction).coerceAtLeast(1f)
            val startX = bounds.right - bandWidth
            val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            val opaque = Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
            paint.shader = LinearGradient(
                startX,
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.top.toFloat(),
                transparent,
                opaque,
                Shader.TileMode.CLAMP,
            )
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty || paint.shader == null) return
            val bandWidth = (bounds.width() * bandFraction).coerceAtLeast(1f)
            canvas.drawRect(
                bounds.right - bandWidth,
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                paint,
            )
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun resolveIslandColor(view: View): Int {
        val candidates = buildList {
            add(view)
            (view.parent as? View)?.let(::add)
            ((view.parent as? View)?.parent as? View)?.let(::add)
            add(view.rootView)
        }
        for (candidate in candidates) {
            runCatching {
                val solid = candidate.solidColor
                if (solid != 0 && Color.alpha(solid) == 255) return solid
            }
            val background = candidate.background
            if (background is ColorDrawable && Color.alpha(background.color) == 255) {
                return background.color
            }
        }
        return Color.BLACK
    }

    private class GradientCoverState(
        val fixIcon: ImageView,
        val iconContainer: View,
        val module: View?,
        val moduleParent: View?,
        val originalFixIconWidth: Int,
        val originalFixIconHeight: Int,
        val originalScaleType: ImageView.ScaleType,
        val originalFixIconScaleX: Float,
        val originalFixIconScaleY: Float,
        val originalFixIconTranslationX: Float,
        val originalFixIconTranslationY: Float,
        val originalFixIconPivotX: Float,
        val originalFixIconPivotY: Float,
        val originalFixIconPivotSet: Boolean,
        val originalFixIconVisibility: Int,
        val originalForeground: Drawable?,
        val originalOutlineProvider: ViewOutlineProvider?,
        val originalClipToOutline: Boolean,
        val originalIconContainerWidth: Int,
        val originalIconContainerHeight: Int,
        val originalIconContainerGravity: Int?,
        val originalIconContainerMarginStart: Int,
        val originalIconContainerMarginEnd: Int,
        val originalIconContainerMarginTop: Int,
        val originalIconContainerMarginBottom: Int,
        val originalIconContainerPaddingLeft: Int,
        val originalIconContainerPaddingTop: Int,
        val originalIconContainerPaddingRight: Int,
        val originalIconContainerPaddingBottom: Int,
        val originalIconContainerTranslationX: Float,
        val originalIconContainerTranslationY: Float,
        val originalFixIconMarginStart: Int,
        val originalFixIconMarginEnd: Int,
        val originalFixIconMarginTop: Int,
        val originalFixIconMarginBottom: Int,
        val originalModulePaddingLeft: Int,
        val originalModulePaddingTop: Int,
        val originalModulePaddingRight: Int,
        val originalModulePaddingBottom: Int,
        val originalIconContainerClipChildren: Boolean,
        val originalModuleClipChildren: Boolean,
        val originalModuleClipToPadding: Boolean,
        val originalModuleParentClipChildren: Boolean,
        val originalModuleParentClipToPadding: Boolean,
        val originalLeftContentTextShadows: MutableList<TextShadowTargetSnapshot>,
    ) {
        var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
        var observedRoot: View? = null
        var observationFrames: Int = 0
        var stableGeometry: IslandGradientGeometryCandidate? = null
        var stableGeometryFrames: Int = 0
        var appliedSmall: Boolean? = null
        var appliedCoverWidth: Int = -1
        var appliedCoverHeight: Int = -1
        private var lastDiagnosticSignature: String? = null
        private var lastGeometryDiagnostic: String? = null
        private val loggedGeometryDiagnosticCategories = HashSet<String>()

        fun scheduleLayout() {
            if (preDrawListener != null) return
            removePreDrawObserver()
            observationFrames = 0
            stableGeometry = null
            stableGeometryFrames = 0
            loggedGeometryDiagnosticCategories.clear()

            val root = fixIcon.rootView
            val listener = ViewTreeObserver.OnPreDrawListener {
                val stillTracked = synchronized(gradientStates) {
                    gradientStates[fixIcon] === this
                }
                if (!stillTracked || currentStyle() != RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT) {
                    removePreDrawObserver()
                    return@OnPreDrawListener true
                }

                observationFrames += 1
                val geometry = resolveStableIslandGeometry()
                if (geometry != null && geometry == stableGeometry) {
                    stableGeometryFrames += 1
                } else {
                    stableGeometry = geometry
                    stableGeometryFrames = if (geometry == null) 0 else 1
                }

                if (geometry != null) {
                    val finalPlacement = stableGeometryFrames >= REQUIRED_STABLE_FRAMES
                    if (finalPlacement) {
                        applyLayout(geometry, logFinalPlacement = true)
                        removePreDrawObserver()
                    } else if (observationFrames >= MAX_OBSERVATION_FRAMES) {
                        if (BuildConfig.DEBUG) {
                            HookLogger.w(TAG, "渐变封面坐标持续变化，不显示中间位移")
                        }
                        removePreDrawObserver()
                    }
                } else if (observationFrames >= MAX_OBSERVATION_FRAMES) {
                    if (BuildConfig.DEBUG) {
                        HookLogger.w(TAG, "渐变封面等待稳定岛坐标超时，保留原生封面")
                    }
                    removePreDrawObserver()
                }
                true
            }
            observedRoot = root
            preDrawListener = listener
            root.viewTreeObserver.takeIf { it.isAlive }?.addOnPreDrawListener(listener)
        }

        fun removePreDrawObserver() {
            val root = observedRoot
            val listener = preDrawListener
            if (root != null && listener != null) {
                root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            }
            observedRoot = null
            preDrawListener = null
        }

        fun applyLeftContentTextShadow() {
            val density = fixIcon.resources.displayMetrics.density
            val targets = IslandAlbumCoverStyleHooker.captureLeftContentTextTargets(module)
            targets.forEach { current ->
                if (originalLeftContentTextShadows.none { it.view === current.view }) {
                    originalLeftContentTextShadows += current
                }
                current.paint.setShadowLayer(
                    LEFT_CONTENT_SHADOW_RADIUS_DP * density,
                    0f,
                    LEFT_CONTENT_SHADOW_DY_DP * density,
                    Color.argb(LEFT_CONTENT_SHADOW_ALPHA, 0, 0, 0),
                )
                current.view.invalidate()
            }
        }

        fun restoreLeftContentTextShadow() {
            originalLeftContentTextShadows.forEach { original ->
                if (original.radius > 0f) {
                    original.paint.setShadowLayer(
                        original.radius,
                        original.dx,
                        original.dy,
                        original.color,
                    )
                } else {
                    original.paint.clearShadowLayer()
                }
                original.view.invalidate()
            }
        }

        fun restoreCoverVisuals() {
            fixIcon.scaleType = originalScaleType
            fixIcon.scaleX = originalFixIconScaleX
            fixIcon.scaleY = originalFixIconScaleY
            fixIcon.translationX = originalFixIconTranslationX
            fixIcon.translationY = originalFixIconTranslationY
            if (originalFixIconPivotSet) {
                fixIcon.pivotX = originalFixIconPivotX
                fixIcon.pivotY = originalFixIconPivotY
            } else {
                fixIcon.resetPivot()
            }
            fixIcon.foreground = originalForeground
            fixIcon.outlineProvider = originalOutlineProvider
            fixIcon.clipToOutline = originalClipToOutline
            fixIcon.invalidateOutline()
        }

        fun applySnapshot(snapshot: CoverVisualSnapshot): Boolean {
            (module as? ViewGroup)?.clipChildren = false
            (module as? ViewGroup)?.clipToPadding = false
            (moduleParent as? ViewGroup)?.clipChildren = false
            (moduleParent as? ViewGroup)?.clipToPadding = false
            (iconContainer as? ViewGroup)?.clipChildren = false

            val changed = applySnapshotToImage(fixIcon, snapshot)
            appliedSmall = snapshot.smallIsland
            appliedCoverWidth = snapshot.coverWidth
            appliedCoverHeight = snapshot.coverHeight
            return changed
        }


        fun isSmallIslandState(): Boolean {
            val moduleView = module ?: return false
            return isSmallIslandModule(moduleView)
        }

        fun cacheCurrentVisual(smallIsland: Boolean, placement: IslandGradientPlacement) {
            val snapshot = CoverVisualSnapshot(
                scaleX = placement.iconScaleX,
                scaleY = placement.iconScaleY,
                translationX = placement.iconTranslationX,
                translationY = placement.iconTranslationY,
                coverWidth = placement.coverWidth,
                coverHeight = placement.coverHeight,
                smallIsland = smallIsland,
                gradientBandFraction = placement.gradientBandFraction,
                islandColor = resolveIslandColor(fixIcon),
            )
            if (smallIsland) cachedSmallVisual = snapshot else cachedBigVisual = snapshot
        }

        fun resolveStableIslandGeometry(): IslandGradientGeometryCandidate? {
            val moduleView = module ?: return null
            val moduleWidth = moduleView.width.takeIf { it > 0 } ?: moduleView.measuredWidth
            val moduleHeight = moduleView.height.takeIf { it > 0 } ?: moduleView.measuredHeight
            val iconWidth = fixIcon.expectedLayoutWidth()
            val iconHeight = fixIcon.expectedLayoutHeight()
            if (moduleWidth <= 0 || moduleHeight <= 0 || iconWidth <= 0 || iconHeight <= 0) {
                logGeometryDiagnostic("invalid_sizes module=${moduleWidth}x${moduleHeight} icon=${iconWidth}x${iconHeight}")
                return null
            }

            val density = fixIcon.resources.displayMetrics.density
            val tolerance = maxOf((4f * density).roundToInt(), moduleHeight / 10)
            val smallIsland = isSmallIslandModule(moduleView)
            val associated = findAssociatedBackgroundView(fixIcon)
            if (associated == null) {
                if (smallIsland && moduleView.isShown) {
                    val moduleLocation = moduleView.baseLocationInWindow()
                    val geometry = IslandGradientGeometryCandidate(
                        left = moduleLocation.first.roundToInt(),
                        top = moduleLocation.second.roundToInt(),
                        width = moduleWidth,
                        height = moduleHeight,
                    )
                    logGeometryDiagnostic("small_module_fallback geometry=$geometry")
                    return geometry
                }
                logGeometryDiagnostic("no_associated_background parents=${parentChain(fixIcon)}")
                return null
            }
            val background = associated.view
            if (!background.isAttachedToWindow || background.visibility != View.VISIBLE) {
                logGeometryDiagnostic("background_not_visible owner=${associated.ownerClass} attached=${background.isAttachedToWindow} visibility=${background.visibility}")
                return null
            }
            val actualLeft = getViewInt(background, "getActualLeft")
            val actualTop = getViewInt(background, "getActualTop")
            val actualWidth = getViewInt(background, "getActualWidth")
            val actualHeight = getViewInt(background, "getActualHeight")
            if (actualLeft == null || actualTop == null || actualWidth == null || actualHeight == null) {
                logGeometryDiagnostic("missing_actual owner=${associated.ownerClass} methods=${background.javaClass.methods.filter { it.parameterTypes.isEmpty() && it.name.contains("Actual") }.joinToString { it.name }}")
                return null
            }
            val geometry = IslandGradientCoverLayout.fromActualEdges(
                actualLeft = actualLeft,
                actualTop = actualTop,
                actualRight = actualWidth,
                actualBottom = actualHeight,
            )
            if (geometry.width <= 0 || abs(geometry.height - moduleHeight) > tolerance) {
                logGeometryDiagnostic("unstable_actual owner=${associated.ownerClass} edges=${actualLeft},${actualTop},${actualWidth},${actualHeight} geometry=$geometry module=${moduleWidth}x${moduleHeight} tolerance=$tolerance")
                return null
            }
            if (smallIsland) {
                if (abs(geometry.width - moduleWidth) > tolerance) {
                    val moduleLocation = moduleView.baseLocationInWindow()
                    val fallback = IslandGradientGeometryCandidate(
                        left = moduleLocation.first.roundToInt(),
                        top = moduleLocation.second.roundToInt(),
                        width = moduleWidth,
                        height = moduleHeight,
                    )
                    logGeometryDiagnostic("small_module_fallback backgroundWidth=${geometry.width} moduleWidth=$moduleWidth geometry=$fallback")
                    return fallback
                }
            } else if (geometry.width <= moduleHeight * 1.5f) {
                logGeometryDiagnostic("big_width_too_small geometryWidth=${geometry.width} moduleHeight=${moduleHeight}")
                return null
            }
            logGeometryDiagnostic("accepted owner=${associated.ownerClass} geometry=$geometry module=${moduleWidth}x${moduleHeight} small=$smallIsland")
            return geometry
        }

        fun logGeometryDiagnostic(message: String) {
            if (!BuildConfig.DEBUG) return
            val category = message.substringBefore(' ')
            if (message == lastGeometryDiagnostic) return
            if (!loggedGeometryDiagnosticCategories.add(category)) return
            lastGeometryDiagnostic = message
            HookLogger.d(TAG, "渐变封面几何诊断: $message")
        }

        fun parentChain(view: View): String {
            val names = ArrayList<String>()
            var current: View? = view
            while (current != null && names.size < 12) {
                names += current.javaClass.name
                current = current.parent as? View
            }
            return names.joinToString(" -> ")
        }

        fun logPlacement(
            module: View,
            islandWindowX: Float,
            smallIsland: Boolean,
            placement: IslandGradientPlacement,
        ) {
            if (!BuildConfig.DEBUG) return
            val signature = "${module.width},${module.height},$islandWindowX|" +
                "$smallIsland,${placement.coverWidth},${placement.coverHeight}|" +
                "${placement.iconScaleX},${placement.iconScaleY}," +
                "${placement.iconTranslationX},${placement.iconTranslationY},${placement.gradientBandFraction}"
            if (signature == lastDiagnosticSignature) return
            lastDiagnosticSignature = signature
            HookLogger.d(
                TAG,
                "渐变封面布局: module=${module.width}x${module.height}, islandLeft=$islandWindowX, " +
                    "small=$smallIsland, cover=${placement.coverWidth}x${placement.coverHeight}, " +
                    "iconScale=${placement.iconScaleX},${placement.iconScaleY}, " +
                    "iconTranslation=${placement.iconTranslationX},${placement.iconTranslationY}, " +
                    "bandFraction=${placement.gradientBandFraction}",
            )
        }

        companion object {
            fun capture(fixIcon: ImageView, iconContainer: View): GradientCoverState {
                val fixLp = fixIcon.layoutParams
                val iconLp = iconContainer.layoutParams
                val module = (fixIcon.parent as? View)?.parent as? View
                val moduleParent = module?.parent as? View
                return GradientCoverState(
                    fixIcon = fixIcon,
                    iconContainer = iconContainer,
                    module = module,
                    moduleParent = moduleParent,
                    originalFixIconWidth = fixLp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    originalFixIconHeight = fixLp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    originalScaleType = fixIcon.scaleType,
                    originalFixIconScaleX = fixIcon.scaleX,
                    originalFixIconScaleY = fixIcon.scaleY,
                    originalFixIconTranslationX = fixIcon.translationX,
                    originalFixIconTranslationY = fixIcon.translationY,
                    originalFixIconPivotX = fixIcon.pivotX,
                    originalFixIconPivotY = fixIcon.pivotY,
                    originalFixIconPivotSet = fixIcon.isPivotSet,
                    originalFixIconVisibility = fixIcon.visibility,
                    originalForeground = fixIcon.foreground,
                    originalOutlineProvider = fixIcon.outlineProvider,
                    originalClipToOutline = fixIcon.clipToOutline,
                    originalIconContainerWidth = iconLp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    originalIconContainerHeight = iconLp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    originalIconContainerGravity = IslandAlbumCoverStyleHooker.getGravity(iconLp),
                    originalIconContainerMarginStart = (iconLp as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0,
                    originalIconContainerMarginEnd = (iconLp as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0,
                    originalIconContainerMarginTop = (iconLp as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0,
                    originalIconContainerMarginBottom = (iconLp as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0,
                    originalIconContainerPaddingLeft = iconContainer.paddingLeft,
                    originalIconContainerPaddingTop = iconContainer.paddingTop,
                    originalIconContainerPaddingRight = iconContainer.paddingRight,
                    originalIconContainerPaddingBottom = iconContainer.paddingBottom,
                    originalIconContainerTranslationX = iconContainer.translationX,
                    originalIconContainerTranslationY = iconContainer.translationY,
                    originalFixIconMarginStart = (fixLp as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0,
                    originalFixIconMarginEnd = (fixLp as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0,
                    originalFixIconMarginTop = (fixLp as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0,
                    originalFixIconMarginBottom = (fixLp as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0,
                    originalModulePaddingLeft = module?.paddingLeft ?: 0,
                    originalModulePaddingTop = module?.paddingTop ?: 0,
                    originalModulePaddingRight = module?.paddingRight ?: 0,
                    originalModulePaddingBottom = module?.paddingBottom ?: 0,
                    originalIconContainerClipChildren = (iconContainer as? ViewGroup)?.clipChildren ?: true,
                    originalModuleClipChildren = (module as? ViewGroup)?.clipChildren ?: true,
                    originalModuleClipToPadding = (module as? ViewGroup)?.clipToPadding ?: true,
                    originalModuleParentClipChildren = (moduleParent as? ViewGroup)?.clipChildren ?: true,
                    originalModuleParentClipToPadding = (moduleParent as? ViewGroup)?.clipToPadding ?: true,
                    originalLeftContentTextShadows =
                        IslandAlbumCoverStyleHooker.captureLeftContentTextTargets(module)
                            .toMutableList(),
                )
            }
        }
    }

    private fun applyCircleOutline(fixIcon: ImageView) {
        fixIcon.outlineProvider = circleOutlineProvider
        fixIcon.clipToOutline = true
        fixIcon.invalidateOutline()
    }

    private fun showAppIcon(accessor: CoverAccessor, holder: Any, dynamicIslandData: Any) {
        val fixIcon = accessor.fixIconField.get(holder) as? ImageView
        val method = accessor.setAppIconMethod
        if (method == null) {
            HookLogger.w(TAG, "应用图标接口不可用，保留原生封面")
            return
        }

        method.invoke(holder, dynamicIslandData)
        val appIcon = accessor.appIconField.get(holder) as? ImageView
        val iconContainer = accessor.iconContainerField.get(holder) as? View
        if (appIcon?.drawable == null ||
            appIcon.visibility != View.VISIBLE ||
            iconContainer?.visibility != View.VISIBLE
        ) {
            appIcon?.visibility = View.GONE
            fixIcon?.visibility = View.VISIBLE
            iconContainer?.visibility = View.VISIBLE
            HookLogger.w(TAG, "应用图标不可用，保留原生封面")
        }
    }

    private fun isMediaAlbum(accessor: CoverAccessor, holder: Any): Boolean {
        val picInfo = accessor.picInfoField.get(holder) ?: return false
        val pic = picInfo.javaClass.methods.firstOrNull {
            it.name == "getPic" && it.parameterTypes.isEmpty()
        }?.invoke(picInfo) as? String
        return pic == MEDIA_ALBUM_ICON
    }

    private fun currentStyle(): Int {
        if (!SystemUiEnhancementGate.isEnabled()) {
            return RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT
        }
        val sharedPrefs = prefs ?: return RootConstants.DEFAULT_HOOK_ISLAND_ALBUM_COVER_STYLE
        if (!sharedPrefs.getBoolean(
                RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM,
                RootConstants.DEFAULT_HOOK_ISLAND_LEFT_ALBUM
            )
        ) {
            return RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT
        }
        return sharedPrefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE,
            RootConstants.DEFAULT_HOOK_ISLAND_ALBUM_COVER_STYLE
        ).coerceIn(
            RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT,
            RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT
        )
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun logArtworkBindingState(imageView: ImageView, source: String, style: Int) {
        if (!BuildConfig.DEBUG) return
        val drawable = imageView.drawable
        val state = when (drawable) {
            null -> "drawable=null"
            is BitmapDrawable -> {
                val bitmap = drawable.bitmap
                if (bitmap == null || bitmap.isRecycled) {
                    "drawable=BitmapDrawable(bitmap=${if (bitmap == null) "null" else "recycled"})"
                } else {
                    "drawable=BitmapDrawable(${bitmap.width}x${bitmap.height})"
                }
            }
            else -> "drawable=${drawable.javaClass.simpleName}"
        } + ",visibility=${imageView.visibility},alpha=${imageView.alpha},size=${imageView.width}x${imageView.height}"
        val signature = "$source|$style|$state"
        val shouldLog = synchronized(artworkDiagnosticStates) {
            if (artworkDiagnosticStates[imageView] == signature) {
                false
            } else {
                artworkDiagnosticStates[imageView] = signature
                true
            }
        }
        if (shouldLog) {
            HookLogger.d(
                TAG,
                "超级岛封面资源诊断: source=$source, view=${System.identityHashCode(imageView)}, $state",
            )
        }
    }

    private class SetIconHook(
        private val accessor: CoverAccessor,
        private val targetField: Field,
        private val methodName: String,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (restoringNative.get() == true) return result
            runCatching {
                val holder = chain.thisObject ?: return@runCatching
                val data = chain.args.firstOrNull() ?: return@runCatching
                applyStyle(accessor, holder, data, targetField, methodName)
            }.onFailure { HookLogger.e(TAG, "应用超级岛封面样式失败", it) }
            return result
        }
    }

    private class TemplateGeometryHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            runCatching {
                val holder = result ?: return@runCatching
                applyInitializedTemplateGeometry(holder)
            }.onFailure { HookLogger.e(TAG, "同步首帧渐变最终几何失败", it) }
            return result
        }
    }

    private data class TrackedHolder(
        val dataRef: WeakReference<Any>,
        val accessor: CoverAccessor
    )

    private data class CoverAccessor(
        val setFixIconMethod: Method,
        val setIconMethod: Method?,
        val setSmallIconMethod: Method?,
        val setAppIconMethod: Method?,
        val picInfoField: Field,
        val fixIconField: Field,
        val smallIconField: Field,
        val appIconField: Field,
        val iconContainerField: Field
    )
}
