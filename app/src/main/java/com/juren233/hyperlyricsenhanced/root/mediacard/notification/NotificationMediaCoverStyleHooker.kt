package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.SystemUiEnhancementGate
import com.juren233.hyperlyricsenhanced.root.mediacard.MediaCoverRotationController
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.root.utils.MediaCardDiagnosticLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

object NotificationMediaCoverStyleHooker {
    private const val TAG = "NotificationMediaCoverStyleHooker"
    private const val VIEW_CONTROLLER_CLASS =
        NotificationMediaHookMethodProfile.VIEW_CONTROLLER_CLASS
    private const val LAYOUT_CONTROLLER_CLASS =
        NotificationMediaHookMethodProfile.LAYOUT_CONTROLLER_CLASS
    private const val HOLDER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder"
    private const val MEDIA_DATA_CLASS =
        "com.android.systemui.media.controls.shared.model.MediaData"
    private const val CONSTRAINT_START = 6
    private const val CONSTRAINT_END = 7
    private const val CONSTRAINT_TOP = 3

    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val activeControllers = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    private val layoutControllers = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    private val viewStates = Collections.synchronizedMap(WeakHashMap<View, CoverViewState>())
    private val nativeApis = Collections.synchronizedMap(WeakHashMap<ClassLoader, NativeApi>())
    private val restoringNativeLayout = ThreadLocal<Boolean>()
    private val circleOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    @Volatile
    private var module: XposedModule? = null

    private val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    fun initialize(xposedModule: XposedModule) {
        module = xposedModule
    }

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        initialize(xposedModule)
        if (!hookedClassLoaders.add(classLoader)) return

        val api = resolveApi(classLoader) ?: run {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "跳过通知中心媒体封面 Hook: reason=native_api_unavailable")
            return
        }
        val handles = mutableListOf<HookHandle>()
        api.hookMethods.forEach { method ->
            runCatching {
                xposedModule.deoptimize(method)
                val hooker = hookerFor(method)
                    ?: error("No hooker for ${method.declaringClass.name}.${method.name}")
                handles += xposedModule.hook(method).intercept(hooker)
            }.onFailure { error ->
                    HookLogger.e(
                        TAG,
                        "安装通知中心媒体封面 Hook 失败: " +
                            "method=${method.declaringClass.simpleName}.${method.name}",
                        error
                    )
            }
        }

        if (handles.size != api.hookMethods.size) {
            handles.forEach(HookHandle::unhook)
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "通知中心媒体封面 Hook 安装不完整")
        } else {
            HookLogger.i(
                TAG,
                "通知中心媒体封面 Hook 已初始化: methods=${api.hookMethods.joinToString { it.name }}"
            )
        }
    }

    fun isTargetMethod(method: Method): Boolean {
        return when (method.declaringClass.name) {
            VIEW_CONTROLLER_CLASS -> when (method.name) {
                "attach", "bindMediaData", "setSeamless" -> method.parameterCount == 1
                "detach" -> method.parameterCount == 0
                else -> false
            }
            LAYOUT_CONTROLLER_CLASS -> isLayoutRefreshMethod(method)
            else -> false
        }
    }

    private fun isLayoutRefreshMethod(method: Method): Boolean {
        return NotificationMediaHookMethodProfile.isLayoutRefresh(method)
    }

    private fun findLayoutRefreshMethod(type: Class<*>): Method {
        return NotificationMediaHookMethodProfile.layoutRefreshMethodNames.firstNotNullOfOrNull { name ->
            findNearestMethods(type, name).firstOrNull { method ->
                method.parameterCount == 0 && method.returnType == Void.TYPE
            }
        }?.apply { isAccessible = true }
            ?: error("No compatible media layout refresh method in ${type.name}")
    }

    private fun findNearestMethods(type: Class<*>, name: String): List<Method> {
        var current: Class<*>? = type
        while (current != null) {
            val methods = current.declaredMethods.filter { method ->
                method.name == name &&
                    !method.isBridge &&
                    !method.isSynthetic &&
                    !java.lang.reflect.Modifier.isAbstract(method.modifiers)
            }
            if (methods.isNotEmpty()) return methods
            current = current.superclass
        }
        return emptyList()
    }

    fun hookerFor(executable: Executable): Hooker? {
        val method = executable as? Method ?: return null
        if (!isTargetMethod(method)) return null
        return when (method.declaringClass.name) {
            VIEW_CONTROLLER_CLASS -> ControllerHook(method.name)
            LAYOUT_CONTROLLER_CLASS -> LayoutLoadHook()
            else -> null
        }
    }

    fun refresh() {
        val refresh = Runnable {
            val layouts = synchronized(layoutControllers) { layoutControllers.toList() }
            layouts.forEach { controller ->
                runCatching {
                    resolveApi(controller.javaClass.classLoader)?.reloadAndApplyLayout(controller)
                    }.onFailure { HookLogger.e(TAG, "刷新通知中心媒体封面布局失败", it) }
            }
            val controllers = synchronized(activeControllers) { activeControllers.toList() }
            controllers.forEach { controller ->
                runCatching { applyStyle(controller, null) }
                    .onFailure { HookLogger.e(TAG, "刷新通知中心媒体封面样式失败", it) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) refresh.run()
        else Handler(Looper.getMainLooper()).post(refresh)
    }

    fun releaseAll() {
        val layouts = synchronized(layoutControllers) { layoutControllers.toList() }
        val controllers = synchronized(activeControllers) { activeControllers.toList() }
        val cleanup = Runnable {
            layouts.forEach { controller ->
                try {
                    restoringNativeLayout.set(true)
                    resolveApi(controller.javaClass.classLoader)?.reloadAndApplyLayout(controller)
                } catch (error: Throwable) {
                HookLogger.e(TAG, "恢复通知中心原生媒体封面布局失败", error)
                } finally {
                    restoringNativeLayout.remove()
                }
            }
            controllers.forEach(::restoreStyle)
            MediaCoverRotationController.cleanup()
            layoutControllers.clear()
            activeControllers.clear()
            viewStates.clear()
            nativeApis.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else Handler(Looper.getMainLooper()).post(cleanup)
    }

    private class ControllerHook(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val controller = chain.thisObject ?: return chain.proceed()
            MediaCardDiagnosticLogger.log(
                stage = "notification_cover",
                event = "controller_callback_begin",
                details = "method=$methodName,controller=${MediaCardDiagnosticLogger.identity(controller)},arg0=${MediaCardDiagnosticLogger.identity(chain.args.firstOrNull())},enabled=${SystemUiEnhancementGate.isEnabled()}",
            )
            if (!SystemUiEnhancementGate.isEnabled()) {
                if (methodName == "detach") {
                    activeControllers.remove(controller)
                    restoreStyle(controller)
                }
                val result = chain.proceed()
                if (methodName == "attach" || methodName == "bindMediaData") {
                    activeControllers.add(controller)
                }
                MediaCardDiagnosticLogger.log(
                    stage = "notification_cover",
                    event = "controller_callback_complete",
                    details = "method=$methodName,controller=${MediaCardDiagnosticLogger.identity(controller)},enabled=false",
                )
                return result
            }
            if (methodName == "setSeamless" && hideDeviceSwitch()) {
                MediaCardDiagnosticLogger.log(
                    stage = "notification_cover",
                    event = "device_switch_callback_blocked",
                    reason = "preference_enabled",
                    details = "method=$methodName,controller=${MediaCardDiagnosticLogger.identity(controller)}",
                )
                return null
            }
            if (methodName == "detach") {
                activeControllers.remove(controller)
                restoreStyle(controller)
            }
            val result = chain.proceed()
            if (methodName == "attach" || methodName == "bindMediaData") {
                runCatching {
                    activeControllers.add(controller)
                    val mediaData = if (methodName == "bindMediaData") {
                        chain.args.firstOrNull()
                    } else {
                        null
                    }
                    applyStyle(controller, mediaData)
                    MediaCardDiagnosticLogger.log(
                        stage = "notification_cover",
                        event = "style_applied_after_controller_callback",
                        details = "method=$methodName,controller=${MediaCardDiagnosticLogger.identity(controller)},style=${currentStyle()},hideCover=${hideCoverSource()},hideDevice=${hideDeviceSwitch()}",
                    )
                }.onFailure { error ->
                    MediaCardDiagnosticLogger.log(
                        stage = "notification_cover",
                        event = "style_apply_failed",
                        reason = "exception",
                        details = "method=$methodName,controller=${MediaCardDiagnosticLogger.identity(controller)},error=${MediaCardDiagnosticLogger.sanitize(error.message)}",
                    )
                    HookLogger.e(TAG, "应用通知中心媒体封面样式失败", error)
                }
            }
            MediaCardDiagnosticLogger.log(
                stage = "notification_cover",
                event = "controller_callback_complete",
                details = "method=$methodName,controller=${MediaCardDiagnosticLogger.identity(controller)},style=${currentStyle()},hideCover=${hideCoverSource()},hideDevice=${hideDeviceSwitch()}",
            )
            return result
        }
    }

    private class LayoutLoadHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val controller = chain.thisObject ?: return result
            layoutControllers.add(controller)
            if (SystemUiEnhancementGate.isEnabled() && restoringNativeLayout.get() != true) {
                runCatching {
                    resolveApi(controller.javaClass.classLoader)?.applyLoadedLayout(
                        controller,
                        currentStyle()
                    )
                }.onFailure { HookLogger.e(TAG, "应用通知中心媒体封面约束失败", it) }
            }
            return result
        }
    }

    private fun applyStyle(controller: Any, mediaData: Any?) {
        val api = resolveApi(controller.javaClass.classLoader) ?: run {
            MediaCardDiagnosticLogger.log(
                stage = "notification_cover",
                event = "style_apply_skipped",
                reason = "native_api_unavailable",
                details = "controller=${MediaCardDiagnosticLogger.identity(controller)}",
            )
            return
        }
        val holder = api.getHolder(controller) ?: return
        val albumView = api.getAlbumView(holder)
        val albumImage = api.getAlbumImage(holder)
        val style = currentStyle()
        val hideSource = hideCoverSource()
        val hideDevice = hideDeviceSwitch()
        MediaCardDiagnosticLogger.log(
            stage = "notification_cover",
            event = "style_apply_begin",
            details = "controller=${MediaCardDiagnosticLogger.identity(controller)},holder=${MediaCardDiagnosticLogger.identity(holder)},mediaData=${MediaCardDiagnosticLogger.identity(mediaData)},style=$style,hideCover=$hideSource,hideDevice=$hideDevice,albumView=${MediaCardDiagnosticLogger.view(albumView)},albumImage=${MediaCardDiagnosticLogger.view(albumImage)}",
        )
        if (style == RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT) {
            restoreStyle(controller)
            return
        }
        val state = viewStates.getOrPut(albumView) {
            CoverViewState(
                albumView = albumView,
                albumImage = albumImage,
                albumOutlineProvider = albumView.outlineProvider,
                albumClipToOutline = albumView.clipToOutline,
                imageOutlineProvider = albumImage.outlineProvider,
                imageClipToOutline = albumImage.clipToOutline
            )
        }
        val isPlaying = api.isPlaying(mediaData ?: api.getMediaData(controller))

        when (style) {
            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_CIRCLE -> {
                MediaCoverRotationController.detach(albumImage)
                state.applyCircle()
            }
            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_ROTATING_CIRCLE -> {
                state.applyCircle()
                MediaCoverRotationController.attach(albumImage, isPlaying)
            }
            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_HIDDEN -> {
                MediaCoverRotationController.detach(albumImage)
                state.restoreOutlines()
                if (albumView.visibility != View.GONE) albumView.visibility = View.GONE
            }
            else -> restoreStyle(controller)
        }
        MediaCardDiagnosticLogger.log(
            stage = "notification_cover",
            event = "style_apply_complete",
            details = "controller=${MediaCardDiagnosticLogger.identity(controller)},style=$style,hideCover=$hideSource,hideDevice=$hideDevice,albumView=${MediaCardDiagnosticLogger.view(albumView)},albumImage=${MediaCardDiagnosticLogger.view(albumImage)}",
        )
    }

    private fun restoreStyle(controller: Any) {
        val api = resolveApi(controller.javaClass.classLoader) ?: return
        val holder = api.getHolder(controller) ?: return
        val albumView = api.getAlbumView(holder)
        val state = viewStates.remove(albumView) ?: return
        MediaCoverRotationController.detach(state.albumImage)
        state.restoreOutlines()
        state.albumView.visibility = View.VISIBLE
    }

    private fun currentStyle(): Int {
        if (!SystemUiEnhancementGate.isEnabled()) {
            return RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT
        }
        return prefs?.getInt(
            RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_COVER_STYLE,
            RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_COVER_STYLE
        )?.coerceIn(
            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT,
            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_HIDDEN
        ) ?: RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_COVER_STYLE
    }

    private fun hideCoverSource(): Boolean {
        if (!SystemUiEnhancementGate.isEnabled()) return false
        return prefs?.getBoolean(
            RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE,
            RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE
        ) ?: RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE
    }

    private fun hideDeviceSwitch(): Boolean {
        if (!SystemUiEnhancementGate.isEnabled()) return false
        return prefs?.getBoolean(
            RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH,
            RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH
        ) ?: RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH
    }

    private fun resolveApi(classLoader: ClassLoader?): NativeApi? {
        classLoader ?: return null
        nativeApis[classLoader]?.let { return it }
        return runCatching { NativeApi.create(classLoader) }
            .onSuccess { nativeApis[classLoader] = it }
            .onFailure { HookLogger.w(TAG, "通知中心媒体封面接口不可用: reason=${it.message}") }
            .getOrNull()
    }

    private data class CoverViewState(
        val albumView: View,
        val albumImage: ImageView,
        val albumOutlineProvider: ViewOutlineProvider?,
        val albumClipToOutline: Boolean,
        val imageOutlineProvider: ViewOutlineProvider?,
        val imageClipToOutline: Boolean,
        var coverOutlined: Boolean = false
    ) {
        fun applyCircle() {
            if (albumView.visibility != View.VISIBLE) albumView.visibility = View.VISIBLE
            if (
                coverOutlined &&
                albumView.outlineProvider === circleOutlineProvider &&
                !albumView.clipToOutline &&
                albumImage.outlineProvider === circleOutlineProvider &&
                albumImage.clipToOutline
            ) {
                return
            }
            albumView.outlineProvider = circleOutlineProvider
            albumView.clipToOutline = false
            albumImage.outlineProvider = circleOutlineProvider
            albumImage.clipToOutline = true
            albumView.invalidateOutline()
            albumImage.invalidateOutline()
            coverOutlined = true
        }

        fun restoreOutlines() {
            if (!coverOutlined) return
            albumView.outlineProvider = albumOutlineProvider
            albumView.clipToOutline = albumClipToOutline
            albumImage.outlineProvider = imageOutlineProvider
            albumImage.clipToOutline = imageClipToOutline
            albumView.invalidateOutline()
            albumImage.invalidateOutline()
            coverOutlined = false
        }
    }

    private class NativeApi private constructor(
        val hookMethods: List<Method>,
        private val holderField: Field,
        private val controllerMediaDataField: Field,
        private val albumViewField: Field,
        private val albumImageField: Field,
        private val mediaDataIsPlayingField: Field,
        private val layoutContextField: Field,
        private val normalLayoutField: Field,
        private val normalAlbumLayoutField: Field,
        private val loadLayoutMethod: Method,
        private val updateLayoutMethod: Method,
        private val setVisibilityMethod: Method,
        private val setGoneMarginMethod: Method
    ) {
        fun getHolder(controller: Any): Any? = holderField.get(controller)

        fun getMediaData(controller: Any): Any? = controllerMediaDataField.get(controller)

        fun getAlbumView(holder: Any): View = albumViewField.get(holder) as View

        fun getAlbumImage(holder: Any): ImageView = albumImageField.get(holder) as ImageView

        fun isPlaying(mediaData: Any?): Boolean {
            return mediaData?.let { mediaDataIsPlayingField.get(it) == true } ?: false
        }

        fun applyLoadedLayout(controller: Any, style: Int) {
            val hideSource = hideCoverSource()
            val hideDevice = hideDeviceSwitch()
            MediaCardDiagnosticLogger.log(
                stage = "notification_cover",
                event = "layout_apply_begin",
                details = "controller=${MediaCardDiagnosticLogger.identity(controller)},style=$style,hideCover=$hideSource,hideDevice=$hideDevice",
            )
            if (
                style != RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_HIDDEN &&
                !hideSource &&
                !hideDevice
            ) {
                MediaCardDiagnosticLogger.log(
                    stage = "notification_cover",
                    event = "layout_apply_skipped",
                    reason = "no_layout_override",
                    details = "controller=${MediaCardDiagnosticLogger.identity(controller)}",
                )
                return
            }
            val normalLayout = normalLayoutField.get(controller)
            val context = layoutContextField.get(controller) as Context
            val ids = LayoutResourceIds.from(context)
            if (style == RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_HIDDEN) {
                setGoneMarginMethod.invoke(
                    normalLayout,
                    ids.headerTitle,
                    CONSTRAINT_START,
                    context.dp(26f)
                )
                setGoneMarginMethod.invoke(
                    normalLayout,
                    ids.headerArtist,
                    CONSTRAINT_START,
                    context.dp(26f)
                )
                setGoneMarginMethod.invoke(
                    normalLayout,
                    ids.actions,
                    CONSTRAINT_TOP,
                    context.dp(67.5f)
                )
                setGoneMarginMethod.invoke(
                    normalLayout,
                    ids.action0,
                    CONSTRAINT_TOP,
                    context.dp(78.5f)
                )
                setVisibilityMethod.invoke(normalLayout, ids.albumArt, View.GONE)
            }
            if (hideSource) {
                val normalAlbumLayout = normalAlbumLayoutField.get(controller)
                setVisibilityMethod.invoke(normalAlbumLayout, ids.coverSource, View.GONE)
            }
            if (hideDevice) {
                setVisibilityMethod.invoke(normalLayout, ids.mediaSeamless, View.GONE)
                setGoneMarginMethod.invoke(
                    normalLayout,
                    ids.headerTitle,
                    CONSTRAINT_END,
                    context.dp(26f)
                )
                setGoneMarginMethod.invoke(
                    normalLayout,
                    ids.headerArtist,
                    CONSTRAINT_END,
                    context.dp(26f)
                )
            }
            val normalRoot = normalLayout as? View
            val albumRoot = normalAlbumLayoutField.get(controller) as? View
            MediaCardDiagnosticLogger.log(
                stage = "notification_cover",
                event = "layout_apply_complete",
                details = "controller=${MediaCardDiagnosticLogger.identity(controller)},style=$style,hideCover=$hideSource,hideDevice=$hideDevice,albumArt=${MediaCardDiagnosticLogger.view(normalRoot?.findViewById(ids.albumArt))},coverSource=${MediaCardDiagnosticLogger.view(albumRoot?.findViewById(ids.coverSource))},deviceSwitch=${MediaCardDiagnosticLogger.view(normalRoot?.findViewById(ids.mediaSeamless))}",
            )
        }

        fun reloadAndApplyLayout(controller: Any) {
            loadLayoutMethod.invoke(controller)
            updateLayoutMethod.invoke(controller)
        }

        private fun Context.dp(value: Float): Int {
            return (value * resources.displayMetrics.density).roundToInt()
        }

        private data class LayoutResourceIds(
            val albumArt: Int,
            val headerTitle: Int,
            val headerArtist: Int,
            val actions: Int,
            val action0: Int,
            val coverSource: Int,
            val mediaSeamless: Int
        ) {
            companion object {
                fun from(context: Context): LayoutResourceIds {
                    return LayoutResourceIds(
                        albumArt = context.requireId("album_art"),
                        headerTitle = context.requireId("header_title"),
                        headerArtist = context.requireId("header_artist"),
                        actions = context.requireId("actions"),
                        action0 = context.requireId("action0"),
                        coverSource = context.requireId("icon"),
                        mediaSeamless = context.requireId("media_seamless")
                    )
                }

                @Suppress("DiscouragedApi")
                private fun Context.requireId(name: String): Int {
                    val id = resources.getIdentifier(name, "id", packageName)
                    require(id != 0) { "Missing SystemUI id resource: $name" }
                    return id
                }
            }
        }

        companion object {
            fun create(classLoader: ClassLoader): NativeApi {
                val viewControllerClass = classLoader.loadClass(VIEW_CONTROLLER_CLASS)
                val layoutControllerClass = classLoader.loadClass(LAYOUT_CONTROLLER_CLASS)
                val holderClass = classLoader.loadClass(HOLDER_CLASS)
                val mediaDataClass = classLoader.loadClass(MEDIA_DATA_CLASS)
                val constraintSetClass = classLoader.loadClass(
                    "androidx.constraintlayout.widget.ConstraintSet"
                )

                val attach = viewControllerClass.getDeclaredMethod(
                    "attach",
                    holderClass
                ).apply { isAccessible = true }
                val bind = viewControllerClass.getDeclaredMethod(
                    "bindMediaData",
                    mediaDataClass
                ).apply { isAccessible = true }
                val detach = viewControllerClass.getDeclaredMethod("detach").apply {
                    isAccessible = true
                }
                val setSeamless = viewControllerClass.getDeclaredMethod(
                    "setSeamless",
                    mediaDataClass
                ).apply { isAccessible = true }
                val loadLayout = findLayoutRefreshMethod(layoutControllerClass)
                val updateLayout = NotificationMediaHookMethodProfile.layoutRefreshMethodNames
                    .asSequence()
                    .mapNotNull { name ->
                        findNearestMethods(layoutControllerClass, name).firstOrNull { method ->
                            method.parameterCount == 0 && method.returnType == Void.TYPE &&
                                method !== loadLayout
                        }
                    }
                    .firstOrNull()
                    ?.apply { isAccessible = true }
                    ?: error("No second compatible media layout refresh method in ${layoutControllerClass.name}")

                return NativeApi(
                    hookMethods = listOf(attach, bind, detach, setSeamless, loadLayout, updateLayout),
                    holderField = viewControllerClass.getDeclaredField("holder").apply {
                        isAccessible = true
                    },
                    controllerMediaDataField = viewControllerClass.getDeclaredField("mediaData").apply {
                        isAccessible = true
                    },
                    albumViewField = holderClass.getDeclaredField("albumView").apply {
                        isAccessible = true
                    },
                    albumImageField = holderClass.getDeclaredField("albumImageView").apply {
                        isAccessible = true
                    },
                    mediaDataIsPlayingField = mediaDataClass.getDeclaredField("isPlaying").apply {
                        isAccessible = true
                    },
                    layoutContextField = layoutControllerClass.getDeclaredField("context").apply {
                        isAccessible = true
                    },
                    normalLayoutField = layoutControllerClass.getDeclaredField("normalLayout").apply {
                        isAccessible = true
                    },
                    normalAlbumLayoutField = layoutControllerClass.getDeclaredField(
                        "normalAlbumLayout"
                    ).apply { isAccessible = true },
                    loadLayoutMethod = loadLayout,
                    updateLayoutMethod = updateLayout,
                    setVisibilityMethod = constraintSetClass.getDeclaredMethod(
                        "setVisibility",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true },
                    setGoneMarginMethod = constraintSetClass.getDeclaredMethod(
                        "setGoneMargin",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                )
            }
        }
    }
}
