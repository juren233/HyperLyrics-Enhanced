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
import com.juren233.hyperlyricsenhanced.common.IslandAlbumCoverWhitelist
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.mediacard.island.IslandExpandedMediaAmbientFlowHooker
import com.juren233.hyperlyricsenhanced.root.mediacard.island.onIslandAlbumIconUpdated
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.lyric.view.line.LyricTextPaintOwner
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
    internal const val TAG = "IslandAlbumCoverStyleHooker"
    private const val ICON_HOLDER_CLASS =
        "miui.systemui.dynamicisland.module.IslandIconViewHolder"
    private const val MEDIA_ALBUM_ICON = "miui_media_album_icon"
    internal const val CAPTURE_MAX_DIMENSION = 512
    internal const val REQUIRED_STABLE_FRAMES = 3
    internal const val MAX_OBSERVATION_FRAMES = 360
    internal const val LEFT_CONTENT_SHADOW_RADIUS_DP = 0.85f
    internal const val LEFT_CONTENT_SHADOW_DY_DP = 0.5f
    internal const val LEFT_CONTENT_SHADOW_ALPHA = 0x7C
    internal val CAPTURE_DELAYS_MS = longArrayOf(0L, 120L, 500L, 1_500L)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    internal val trackedHolders = WeakHashMap<Any, TrackedHolder>()
    internal val artworkCaptureRequests = NativeArtworkCaptureRequests<ImageView>()
    internal val gradientStates = WeakHashMap<ImageView, GradientCoverState>()
    private val fakeTransitionLogSignatures = WeakHashMap<ViewGroup, String>()
    private val artworkDiagnosticStates = WeakHashMap<ImageView, String>()
    internal val artworkIdentityByView = WeakHashMap<ImageView, ArtworkIdentity>()
    private val restoringNative = ThreadLocal<Boolean>()
    @Volatile
    internal var cachedBigVisual: CoverVisualSnapshot? = null
    @Volatile
    internal var cachedSmallVisual: CoverVisualSnapshot? = null
    internal val circleOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return
            outline.setOval(0, 0, w, h)
        }
    }
    internal val leftRoundedCoverOutlineProvider = object : ViewOutlineProvider() {
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
    internal var module: XposedModule? = null

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

    fun refreshLeftContentTextShadows() {
        runOnMain {
            val states = synchronized(gradientStates) { gradientStates.values.toList() }
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "[GradientShadowDiag] refresh source=injected_targets, " +
                        "gradientStates=${states.size}, style=${currentStyle()}",
                )
            }
            states.forEach { it.applyLeftContentTextShadow(source = "injected_targets") }
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
        val realOwner = resolveFakeTransitionOwner(fakeView, realView)
        val currentData = IslandProbeUtils.getCurrentIslandData(realOwner)
        if (currentStyle(currentData) != RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT) return
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
        artworkCaptureRequests.clear()
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
        val style = currentStyle(dynamicIslandData)
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
                applyGradientCover(
                    accessor = accessor,
                    holder = holder,
                    fixIcon = fixIcon,
                    fakeContentView = fakeContentView,
                    packageName = IslandProbeUtils.extractMediaIslandInfo(dynamicIslandData)?.packageName,
                )
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

    internal fun currentStyle(dynamicIslandData: Any? = null): Int {
        val packageName = dynamicIslandData?.let {
            IslandProbeUtils.extractMediaIslandInfo(it)?.packageName
        }
        return currentStyleForPackage(packageName)
    }

    internal fun currentStyleForPackage(packageName: String?): Int {
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
        val configuredStyle = sharedPrefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE,
            RootConstants.DEFAULT_HOOK_ISLAND_ALBUM_COVER_STYLE
        ).coerceIn(
            RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT,
            RootConstants.ISLAND_ALBUM_COVER_STYLE_GRADIENT
        )
        if (configuredStyle == RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT) {
            return configuredStyle
        }
        val enabledPackages = IslandRuntimePreferenceReader.getStringSet(
            sharedPrefs,
            RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE_APP_WHITELIST,
            null,
        )
        return if (IslandAlbumCoverWhitelist.isEnabled(enabledPackages, packageName)) {
            configuredStyle
        } else {
            RootConstants.ISLAND_ALBUM_COVER_STYLE_DEFAULT
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    internal fun logArtworkBindingState(imageView: ImageView, source: String, style: Int) {
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
        internal val accessor: CoverAccessor,
        private val targetField: Field,
        private val methodName: String,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (restoringNative.get() == true) return result
            runCatching {
                val holder = chain.thisObject ?: return@runCatching
                val data = chain.args.firstOrNull() ?: return@runCatching
                val mediaAlbum = isMediaAlbum(accessor, holder)
                if (BuildConfig.DEBUG && mediaAlbum) {
                    HookLogger.d(
                        TAG,
                        "流光探测: 封面图标更新 method=$methodName " +
                            "data=${data?.javaClass?.simpleName}@${System.identityHashCode(data)}"
                    )
                }
                // 在封面样式替换 Drawable 之前，把原生封面图标交给流光取色快速路径。
                if (mediaAlbum) {
                    val iconDrawable = (targetField.get(holder) as? ImageView)?.drawable
                    IslandExpandedMediaAmbientFlowHooker.onIslandAlbumIconUpdated(iconDrawable)
                }
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

    internal data class TrackedHolder(
        val dataRef: WeakReference<Any>,
        val accessor: CoverAccessor
    )

    internal data class CoverAccessor(
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
