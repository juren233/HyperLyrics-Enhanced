package com.juren233.hyperlyricsenhanced.root.island

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.graphics.createBitmap
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.media.MediaMetadataHelper
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.LyriconDataBridge
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

internal object IslandAlbumCoverStyleHooker {
    private const val TAG = "IslandAlbumCoverStyleHooker"
    private const val ICON_HOLDER_CLASS =
        "miui.systemui.dynamicisland.module.IslandIconViewHolder"
    private const val MEDIA_ALBUM_ICON = "miui_media_album_icon"
    private const val CAPTURE_MAX_DIMENSION = 512
    private val CAPTURE_DELAYS_MS = longArrayOf(0L, 120L, 500L, 1_500L)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val trackedHolders = WeakHashMap<Any, TrackedHolder>()
    private val captureGenerationByView = WeakHashMap<ImageView, Int>()
    private val restoringNative = ThreadLocal<Boolean>()
    private val circleOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
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
            val accessor = CoverAccessor(
                setFixIconMethod = fixMethod,
                setAppIconMethod = holderClass.declaredMethods.firstOrNull {
                    it.name == "setAppIcon" && it.parameterTypes.contentEquals(fixMethod.parameterTypes)
                }?.apply { isAccessible = true },
                picInfoField = holderClass.getDeclaredField("picInfo").apply { isAccessible = true },
                fixIconField = holderClass.getDeclaredField("fixIcon").apply { isAccessible = true },
                appIconField = holderClass.getDeclaredField("appIcon").apply { isAccessible = true },
                iconContainerField = holderClass.getDeclaredField("iconContainer").apply { isAccessible = true }
            )

            xposedModule.deoptimize(fixMethod)
            xposedModule.hook(fixMethod).intercept(SetFixIconHook(accessor))
            HookLogger.i(TAG, "超级岛封面样式 Hook 已初始化")
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
    }

    fun releaseAll() {
        val holders = synchronized(trackedHolders) {
            trackedHolders.mapNotNull { (holder, tracked) ->
                tracked.dataRef.get()?.let { Triple(holder, it, tracked.accessor) }
            }
        }
        runOnMain {
            IslandAlbumCoverRotationController.cleanup()
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
        synchronized(trackedHolders) {
            trackedHolders.clear()
        }
        synchronized(captureGenerationByView) {
            captureGenerationByView.clear()
        }
    }

    private fun applyStyle(accessor: CoverAccessor, holder: Any, dynamicIslandData: Any) {
        if (!isMediaAlbum(accessor, holder)) return
        synchronized(trackedHolders) {
            trackedHolders[holder] = TrackedHolder(WeakReference(dynamicIslandData), accessor)
        }

        val fixIcon = accessor.fixIconField.get(holder) as? ImageView ?: return
        scheduleNativeArtworkCapture(fixIcon, dynamicIslandData)
        val style = currentStyle()
        if (style != RootConstants.ISLAND_ALBUM_COVER_STYLE_ROTATING_CIRCLE) {
            IslandAlbumCoverRotationController.detach(fixIcon)
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
        }
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
            RootConstants.ISLAND_ALBUM_COVER_STYLE_ROTATING_CIRCLE
        )
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private class SetFixIconHook(
        private val accessor: CoverAccessor
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (restoringNative.get() == true) return result
            runCatching {
                val holder = chain.thisObject ?: return@runCatching
                val data = chain.args.firstOrNull() ?: return@runCatching
                applyStyle(accessor, holder, data)
            }.onFailure { HookLogger.e(TAG, "应用超级岛封面样式失败", it) }
            return result
        }
    }

    private data class TrackedHolder(
        val dataRef: WeakReference<Any>,
        val accessor: CoverAccessor
    )

    private data class CoverAccessor(
        val setFixIconMethod: Method,
        val setAppIconMethod: Method?,
        val picInfoField: Field,
        val fixIconField: Field,
        val appIconField: Field,
        val iconContainerField: Field
    )
}
