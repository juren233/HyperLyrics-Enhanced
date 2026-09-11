/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

/**
 * Debug-only：小窗恢复/回岛阶段逐帧采样超级岛背景胶囊几何与注入歌词视图位置。
 *
 * 目的：区分"胶囊宽度落后内容"与"胶囊整体缺失"，并把模块动作与原生几何变化对齐
 * （ISLAND-MINIWINDOW-RESTORE-BLANK-001，2026-09-11）。
 * `DynamicIslandBackgroundView.onDraw` 只用 actualLeft/actualTop/actualWidth/actualHeight
 * 设置 drawable bounds，采样这四个字段即可还原胶囊几何时间线。
 *
 * 所有入口在 Release 构建直接返回，不安装任何监听。
 */
internal object IslandBackgroundTraceDiagnostics {
    private const val TAG = "岛背景追踪"
    private const val BACKGROUND_VIEW_NAME = "DynamicIslandBackgroundView"
    private const val BACKGROUND_VIEW_CLASS = "miui.systemui.dynamicisland.DynamicIslandBackgroundView"
    private const val FAKE_VIEW_NAME = "DynamicIslandContentFakeView"
    private const val CONTENT_VIEW_NAME = "DynamicIslandContentView"
    private const val MAX_FRAMES = 300
    private const val MAX_DURATION_MS = 6000L
    private const val MIN_LOG_INTERVAL_MS = 40L
    private const val MAX_SEARCH_DEPTH = 10
    private const val MAX_BACKGROUND_INSTANCES = 4
    private val FAKE_PART_NAMES = listOf(
        "fake_container",
        "fake_expanded_view",
        "fake_big_island_view",
        "fake_small_island_view",
        "mini_window_bar",
        "fake_island_mask",
    )
    private val SYSTEMUI_PACKAGE_NAMES = listOf("miui.systemui.plugin", "com.android.systemui")

    private val intGetters = ConcurrentHashMap<String, java.lang.reflect.Method>()
    private val floatFields = ConcurrentHashMap<String, java.lang.reflect.Field>()
    private val fakePartIds = WeakHashMap<View, Map<String, Int>>()

    private var listener: ViewTreeObserver.OnPreDrawListener? = null
    private var observedTree: ViewTreeObserver? = null
    private var rootRef: WeakReference<View>? = null
    private var backgroundRef: WeakReference<View>? = null
    private var fakeRef: WeakReference<View>? = null
    private var contentRef: WeakReference<View>? = null
    private var frames = 0
    private var missingFrames = 0
    private var startedAt = 0L
    private var lastLogAt = 0L
    private var lastSummary = ""
    private var startReason = ""
    private var boundBackgroundRef: WeakReference<View>? = null
    @Volatile
    private var firstDrawLogged = false
    private var lastDrawState = ""
    private var lastDrawAt = 0L

    /**
     * 记录一次模块动作，并在需要时顺带开始（或复用）采样。
     * [background] 传入模块自己持有的背景视图引用（如恢复路径的 backgroundView），
     * 采样时直接跟踪该实例，不再只依赖按类名搜索的结果。
     */
    fun event(reason: String, view: View?, detail: String? = null, background: View? = null) {
        if (!BuildConfig.DEBUG) return
        if (background != null) {
            boundBackgroundRef = WeakReference(background)
            lastSummary = ""
        }
        val suffix = buildString {
            if (view != null) append(" view=@").append(System.identityHashCode(view))
            if (!detail.isNullOrEmpty()) append(' ').append(detail)
        }
        HookLogger.d(TAG, "模块动作: $reason$suffix")
        view?.let { attach(reason, it) }
    }

    /** Debug-only：追踪胶囊实际绘制真值（onDraw 命中即说明该实例真的在画）。 */
    fun installDrawTracing(module: XposedModule, cl: ClassLoader) {
        if (!BuildConfig.DEBUG) return
        val capsuleClass = runCatching { cl.loadClass(BACKGROUND_VIEW_CLASS) }.getOrNull()
        if (capsuleClass == null) {
            HookLogger.w(TAG, "跳过胶囊绘制追踪: 未找到 $BACKGROUND_VIEW_CLASS")
            return
        }
        val methods = capsuleClass.declaredMethods.filter {
            it.name == "onDraw" && it.parameterTypes.contentEquals(arrayOf(android.graphics.Canvas::class.java))
        }
        if (methods.isEmpty()) {
            HookLogger.w(TAG, "跳过胶囊绘制追踪: 未找到 onDraw(Canvas)")
            return
        }
        methods.forEach { method ->
            method.isAccessible = true
            module.deoptimize(method)
            module.hook(method).intercept(DrawTraceHook())
        }
        HookLogger.i(TAG, "已安装胶囊绘制追踪: methods=${methods.size}")
    }

    private class DrawTraceHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            runCatching { traceDraw(chain.thisObject as? View) }
            return chain.proceed()
        }
    }

    private fun traceDraw(view: View?) {
        if (!BuildConfig.DEBUG || view == null) return
        val state = instanceTag(view) + " " + capsuleState(view)
        val now = SystemClock.uptimeMillis()
        if (state == lastDrawState || now - lastDrawAt < MIN_LOG_INTERVAL_MS) return
        lastDrawAt = now
        lastDrawState = state
        if (!firstDrawLogged) {
            firstDrawLogged = true
            HookLogger.i(TAG, "胶囊绘制追踪首次命中: $state")
        }
        HookLogger.d(TAG, "胶囊绘制真值: $state")
    }

    /** 开始（或复用）对给定视图所在窗口的逐帧采样。 */
    fun attach(reason: String, view: View?) {
        if (!BuildConfig.DEBUG) return
        val target = view ?: return
        runCatching { startSampling(target, reason) }
            .onFailure { HookLogger.d(TAG, "采样启动失败: reason=$reason error=${it.message}") }
    }

    private fun startSampling(view: View, reason: String) {
        val windowRoot = topMostParent(view)
        val observer = windowRoot.viewTreeObserver ?: return
        if (listener != null && observedTree === observer && observer.isAlive) {
            startReason = reason
            return
        }
        stop("替换旧采样")
        frames = 0
        missingFrames = 0
        startedAt = SystemClock.uptimeMillis()
        lastLogAt = 0L
        lastSummary = ""
        startReason = reason
        rootRef = WeakReference(windowRoot)
        val background = findBackgroundView(windowRoot)
        backgroundRef = WeakReference(background)
        val fake = findViewBySimpleName(windowRoot, FAKE_VIEW_NAME)
        fakeRef = WeakReference(fake)
        val content = findViewBySimpleName(windowRoot, CONTENT_VIEW_NAME)
        contentRef = WeakReference(content)
        val created = ViewTreeObserver.OnPreDrawListener { onFrame() }
        listener = created
        observedTree = observer
        observer.addOnPreDrawListener(created)
        HookLogger.d(
            TAG,
            "采样开始: reason=$reason root=${windowRoot.javaClass.simpleName}@${System.identityHashCode(windowRoot)} " +
                "background=${background?.javaClass?.simpleName ?: "未找到"}",
        )
    }

    private fun onFrame(): Boolean {
        frames += 1
        val root = rootRef?.get()
        if (root == null || !root.isAttachedToWindow) {
            HookLogger.d(TAG, "采样结束: 根视图脱离 reason=$startReason frames=$frames 最后=$lastSummary")
            stop(null)
            return true
        }
        val background = resolveAttached(backgroundRef, root) { findBackgroundView(root) }
        backgroundRef = WeakReference(background)
        val fake = resolveAttached(fakeRef, root) { findViewBySimpleName(root, FAKE_VIEW_NAME) }
        fakeRef = WeakReference(fake)
        val content = resolveAttached(contentRef, root) { findViewBySimpleName(root, CONTENT_VIEW_NAME) }
        contentRef = WeakReference(content)
        val now = SystemClock.uptimeMillis()
        if (background == null) {
            missingFrames += 1
            if (missingFrames == 1 || missingFrames == 30) {
                HookLogger.d(
                    TAG,
                    "采样中未找到背景视图: reason=$startReason frame=$frames root=@${System.identityHashCode(root)}",
                )
            }
        } else {
            missingFrames = 0
        }
        val summary = summarize(background, fake, content)
        if (summary != lastSummary && (now - lastLogAt >= MIN_LOG_INTERVAL_MS || frames <= 3)) {
            lastLogAt = now
            lastSummary = summary
            HookLogger.d(
                TAG,
                "背景几何: t=${now - startedAt}ms frame=$frames reason=$startReason $summary",
            )
        }
        if (frames >= MAX_FRAMES || now - startedAt >= MAX_DURATION_MS) {
            HookLogger.d(
                TAG,
                "采样结束: reason=$startReason frames=$frames elapsed=${now - startedAt}ms 最后=$lastSummary",
            )
            stop(null)
        }
        return true
    }

    private fun summarize(background: View?, fake: View?, content: View?): String = buildString {
        if (background == null) {
            append("capsule=无")
        } else {
            append("capsule=").append(instanceTag(background)).append(' ').append(capsuleState(background))
        }
        append(" bound=").append(boundBackgroundState())
        append(" all=").append(allBackgroundStates())
        append(" | fake=").append(viewState(fake))
        append(" | content=").append(viewState(content))
    }

    private fun instanceTag(view: View): String = "@" + System.identityHashCode(view)

    /** 胶囊状态：几何、visibility、View.alpha、transitionAlpha、drawable alpha 与私有 backgroundAlpha。 */
    private fun capsuleState(view: View): String = buildString {
        append(intGetter(view, "getActualLeft"))
            .append(',')
            .append(intGetter(view, "getActualTop"))
            .append(' ')
            .append(intGetter(view, "getActualWidth"))
            .append('x')
            .append(intGetter(view, "getActualHeight"))
        append(" vis=").append(view.visibility)
        append(" a=").append(view.alpha)
        append(" t=").append(transitionAlpha(view))
        append(" bg=").append(floatField(view, "backgroundAlpha"))
        append(" dw=").append(privateDrawableState(view))
        append(" w=").append(windowAlpha(view))
    }

    /** 原生 onDraw 实际绘制的私有 drawable（不是 view.background 的透明 ColorDrawable）。 */
    private fun privateDrawableState(view: View): String = runCatching {
        val field = drawableFields.getOrPut(view.javaClass.name) {
            view.javaClass.getDeclaredField("drawable").apply { isAccessible = true }
        }
        val drawable = field.get(view) as? android.graphics.drawable.Drawable ?: return@runCatching "空"
        val bounds = drawable.bounds
        "${drawable.javaClass.simpleName}@${System.identityHashCode(drawable)}" +
            " a=${drawable.alpha} bounds=${bounds.left},${bounds.top} ${bounds.width()}x${bounds.height()}"
    }.getOrDefault("读取失败")

    /** 窗口级 alpha（ViewRootImpl.mWindowAttributes.alpha），用于排除整窗淡出。 */
    private fun windowAlpha(view: View): Float = runCatching {
        var parent: android.view.ViewParent? = view.rootView.parent
        while (parent != null) {
            val field = windowAlphaFields.getOrPut(parent.javaClass.name) {
                parent.javaClass.getDeclaredField("mWindowAttributes").apply { isAccessible = true }
            }
            val attributes = field.get(parent) as? android.view.WindowManager.LayoutParams
                ?: return@runCatching -1f
            return@runCatching attributes.alpha
        }
        -1f
    }.getOrDefault(-1f)

    private fun boundBackgroundState(): String {
        val view = boundBackgroundRef?.get() ?: return "无"
        if (!view.isAttachedToWindow) return instanceTag(view) + " 已脱离窗口"
        return instanceTag(view) + " " + capsuleState(view)
    }

    private fun allBackgroundStates(): String {
        val root = rootRef?.get() ?: return "无"
        val found = ArrayList<View>(2)
        collectBackgrounds(root, 0, found)
        if (found.isEmpty()) return "无"
        return found.joinToString(separator = "; ", prefix = "[", postfix = "]") {
            instanceTag(it) + " " + capsuleState(it)
        }
    }

    private fun collectBackgrounds(view: View, depth: Int, out: MutableList<View>) {
        if (out.size >= MAX_BACKGROUND_INSTANCES) return
        if (view.javaClass.simpleName == BACKGROUND_VIEW_NAME) out.add(view)
        if (depth >= MAX_SEARCH_DEPTH || view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            collectBackgrounds(view.getChildAt(index), depth + 1, out)
        }
    }

    private fun transitionAlpha(view: View): Float = runCatching {
        val key = view.javaClass.name + "#getTransitionAlpha"
        val method = transitionAlphaMethods.getOrPut(key) {
            view.javaClass.getMethod("getTransitionAlpha")
        }
        (method.invoke(view) as? Number)?.toFloat() ?: -1f
    }.getOrDefault(-1f)

    private val transitionAlphaMethods = ConcurrentHashMap<String, java.lang.reflect.Method>()
    private val drawableFields = ConcurrentHashMap<String, java.lang.reflect.Field>()
    private val windowAlphaFields = ConcurrentHashMap<String, java.lang.reflect.Field>()

    private fun viewState(view: View?): String {
        if (view == null) return "无"
        val location = IntArray(2).also(view::getLocationInWindow)
        val backgroundBounds = view.background?.bounds
        return buildString {
            append('@').append(System.identityHashCode(view)).append(' ')
            append(location[0]).append(',').append(location[1])
            append(' ').append(view.width).append('x').append(view.height)
            append(" vis=").append(view.visibility)
            append(" alpha=").append(view.alpha)
            if (backgroundBounds != null) {
                append(" bg=")
                    .append(backgroundBounds.left).append(',')
                    .append(backgroundBounds.top).append(' ')
                    .append(backgroundBounds.width()).append('x')
                    .append(backgroundBounds.height())
            }
            append(" left=").append(injectedBounds(view, IslandProbeUtils.LEFT_TEST_VIEW_TAG))
            append(" right=").append(injectedBounds(view, IslandProbeUtils.RIGHT_TEST_VIEW_TAG))
            if (view.javaClass.simpleName == FAKE_VIEW_NAME) {
                append(" parts=").append(fakeParts(view))
            }
        }
    }

    private fun fakeParts(fakeView: View): String {
        val ids = synchronized(fakePartIds) {
            fakePartIds.getOrPut(fakeView) { resolveFakePartIds(fakeView) }
        }
        return buildString {
            FAKE_PART_NAMES.forEachIndexed { index, name ->
                val id = ids[name]
                val part: View? = id?.takeIf { it != 0 }?.let { fakeView.findViewById<View>(it) }
                if (index > 0) append(' ')
                append(name).append('=')
                if (part == null) {
                    append("无")
                    return@forEachIndexed
                }
                val location = IntArray(2).also(part::getLocationInWindow)
                append(location[0]).append(',').append(location[1])
                append(' ').append(part.width).append('x').append(part.height)
                append("/v").append(part.visibility)
                append("/a").append(part.alpha)
                part.background?.bounds?.let { bounds ->
                    append("/bg").append(bounds.left).append(',').append(bounds.top)
                    append(' ').append(bounds.width()).append('x').append(bounds.height())
                }
                append("/l").append(injectedBounds(part, IslandProbeUtils.LEFT_TEST_VIEW_TAG))
                append("/r").append(injectedBounds(part, IslandProbeUtils.RIGHT_TEST_VIEW_TAG))
            }
        }
    }

    private fun resolveFakePartIds(fakeView: View): Map<String, Int> {
        val resources = fakeView.resources
        return FAKE_PART_NAMES.associateWith { name ->
            SYSTEMUI_PACKAGE_NAMES.firstNotNullOfOrNull { pkg ->
                resources.getIdentifier(name, "id", pkg).takeIf { it != 0 }
            } ?: 0
        }
    }

    private fun injectedBounds(root: View, tag: String): String {
        val view = root.findViewWithTag<View>(tag) ?: return "无"
        val location = IntArray(2).also(view::getLocationInWindow)
        return "x=${location[0]} w=${view.width} vis=${view.visibility}"
    }

    private fun resolveAttached(
        ref: WeakReference<View>?,
        root: View,
        find: () -> View?,
    ): View? {
        val cached = ref?.get()
        if (cached != null && cached.isAttachedToWindow) return cached
        return find().also { if (it !== cached) lastSummary = "" }
    }

    private fun stop(reason: String?) {
        listener?.let { created ->
            observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(created)
        }
        listener = null
        observedTree = null
        rootRef = null
        backgroundRef = null
        if (reason != null) {
            HookLogger.d(TAG, "采样中止: $reason")
        }
    }

    private fun topMostParent(view: View): View {
        var current: View = view
        while (true) {
            current = current.parent as? View ?: return current
        }
    }

    private fun findBackgroundView(root: View): View? = searchBackground(root, 0)

    private fun findViewBySimpleName(root: View, name: String): View? =
        searchBySimpleName(root, name, 0)

    private fun searchBySimpleName(view: View, name: String, depth: Int): View? {
        if (view.javaClass.simpleName == name) return view
        if (depth >= MAX_SEARCH_DEPTH || view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            searchBySimpleName(view.getChildAt(index), name, depth + 1)?.let { return it }
        }
        return null
    }

    private fun searchBackground(view: View, depth: Int): View? {
        if (view.javaClass.simpleName == BACKGROUND_VIEW_NAME) return view
        if (depth >= MAX_SEARCH_DEPTH || view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            searchBackground(view.getChildAt(index), depth + 1)?.let { return it }
        }
        return null
    }

    private fun intGetter(target: Any, name: String): Int = runCatching {
        val key = target.javaClass.name + '#' + name
        val method = intGetters.getOrPut(key) {
            target.javaClass.methods.first { it.name == name && it.parameterTypes.isEmpty() }
        }
        (method.invoke(target) as? Number)?.toInt() ?: -1
    }.getOrDefault(-1)

    private fun floatField(target: Any, name: String): Float = runCatching {
        val key = target.javaClass.name + '#' + name
        val field = floatFields.getOrPut(key) {
            var current: Class<*>? = target.javaClass
            var found: java.lang.reflect.Field? = null
            while (current != null && found == null) {
                found = current.declaredFields.firstOrNull { it.name == name }
                current = current.superclass
            }
            val resolved = found ?: error("字段不存在: $name")
            resolved.isAccessible = true
            resolved
        }
        (field.get(target) as? Number)?.toFloat() ?: -1f
    }.getOrDefault(-1f)
}
