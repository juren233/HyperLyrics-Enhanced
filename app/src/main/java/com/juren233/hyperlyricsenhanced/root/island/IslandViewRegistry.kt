package com.juren233.hyperlyricsenhanced.root.island

import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.lyric.view.RichLyricLineView
import com.juren233.hyperlyricsenhanced.lyric.view.SpaceGateRichLyricLineView
import com.juren233.hyperlyricsenhanced.root.island.renderer.BaseIslandRenderer
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.WeakHashMap

internal object IslandViewRegistry {
    private const val TAG = "IslandViewRegistry"

    private val lock = Any()
    private val activeIslandPkgNames = WeakHashMap<ViewGroup, String>()
    private val injectedViewsByRoot = WeakHashMap<ViewGroup, MutableMap<View, Unit>>()
    private val publishedAttachedPkgNames = WeakHashMap<ViewGroup, String>()

    /**
     * 岛根视图候选：任何岛 hook 见过的 contentView 都登记在此。媒体切换瞬间
     * 原生只发一次岛更新，若恰好撞上歌词包名未更新的窗口会被硬清注销；部分
     * 播放器（通知无逐秒进度重发）此后原生不再调用被 hook 的更新入口，注册表
     * 就永久为空。重挂扫描用这些候选找回视图。
     */
    private val candidateRoots = WeakHashMap<ViewGroup, Unit>()
    private val attachStateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            val root = view as? ViewGroup ?: return
            publishAttached(root, "attach")
        }

        override fun onViewDetachedFromWindow(view: View) {
            val root = view as? ViewGroup ?: return
            synchronized(lock) {
                publishedAttachedPkgNames.remove(root)
            }
        }
    }

    fun register(view: ViewGroup, packageName: String) {
        synchronized(lock) { candidateRoots[view] = Unit }
        var shouldPublishAttached = false
        synchronized(lock) {
            activeIslandPkgNames[view] = packageName
            view.removeOnAttachStateChangeListener(attachStateListener)
            view.addOnAttachStateChangeListener(attachStateListener)
            if (
                view.isAttachedToWindow &&
                publishedAttachedPkgNames[view] != packageName
            ) {
                publishedAttachedPkgNames[view] = packageName
                shouldPublishAttached = true
            }
        }
        if (shouldPublishAttached) {
            scheduleRefreshAfterAttach(view, packageName, "register_attached")
        }
    }

    fun unregister(view: ViewGroup): Boolean {
        return synchronized(lock) {
            val wasRegistered = activeIslandPkgNames.remove(view) != null
            injectedViewsByRoot.remove(view)
            publishedAttachedPkgNames.remove(view)
            view.removeOnAttachStateChangeListener(attachStateListener)
            wasRegistered
        }
    }

    fun refreshInjectedViews(root: ViewGroup) {
        val indexedViews = WeakHashMap<View, Unit>()
        collectInjectedViews(root, indexedViews)
        synchronized(lock) {
            if (activeIslandPkgNames.containsKey(root)) {
                injectedViewsByRoot[root] = indexedViews
            }
        }
    }

    /** 岛 hook 每次见到 contentView 都应调用；不判断歌词状态，只留候选引用。 */
    fun rememberCandidate(view: ViewGroup) {
        synchronized(lock) { candidateRoots[view] = Unit }
    }

    /** 当前仍附着的候选岛根视图（弱引用快照）。 */
    fun snapshotCandidates(): List<ViewGroup> = synchronized(lock) {
        candidateRoots.keys.filter { it.isAttachedToWindow }
    }

    fun snapshotAttached(packageName: String? = null): List<Pair<ViewGroup, String>> {
        val result = mutableListOf<Pair<ViewGroup, String>>()
        synchronized(lock) {
            activeIslandPkgNames.entries.forEach { entry ->
                val viewGroup = entry.key
                if (viewGroup.isAttachedToWindow) {
                    if (packageName == null || entry.value == packageName) {
                        result += viewGroup to entry.value
                    }
                }
            }
        }
        return result
    }

    fun snapshotAttachedInjectedViews(
        packageName: String? = null
    ): List<Pair<ViewGroup, List<View>>> {
        val result = mutableListOf<Pair<ViewGroup, List<View>>>()
        synchronized(lock) {
            activeIslandPkgNames.entries.forEach { entry ->
                val root = entry.key
                if (!root.isAttachedToWindow) return@forEach
                if (packageName != null && entry.value != packageName) return@forEach

                val views = injectedViewsByRoot[root]
                    ?.keys
                    ?.filter { it.isAttachedToWindow }
                    .orEmpty()
                result += root to views
            }
        }
        return result
    }

    private fun publishAttached(root: ViewGroup, source: String) {
        val packageName = synchronized(lock) {
            val registeredPackage = activeIslandPkgNames[root] ?: return
            if (publishedAttachedPkgNames[root] == registeredPackage) return
            publishedAttachedPkgNames[root] = registeredPackage
            registeredPackage
        }
        scheduleRefreshAfterAttach(root, packageName, source)
    }

    private fun scheduleRefreshAfterAttach(
        root: ViewGroup,
        packageName: String,
        source: String
    ) {
        root.post {
            val stillRegistered = synchronized(lock) {
                activeIslandPkgNames[root] == packageName
            }
            if (!stillRegistered || !root.isAttachedToWindow) return@post

            refreshInjectedViews(root)
            BaseIslandRenderer.refreshActiveIsland()
            HookLogger.d(
                TAG,
                "超级岛根视图附着后已补发刷新: source=$source, package=$packageName"
            )
        }
    }

    private fun collectInjectedViews(view: View, result: MutableMap<View, Unit>) {
        when (view) {
            is RichLyricLineView,
            is SpaceGateRichLyricLineView -> result[view] = Unit
            is ViewGroup -> {
                for (index in 0 until view.childCount) {
                    collectInjectedViews(view.getChildAt(index), result)
                }
            }
        }
    }
}
