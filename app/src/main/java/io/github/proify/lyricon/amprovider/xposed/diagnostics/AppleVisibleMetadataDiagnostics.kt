/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal interface AppleVisibleMetadataDiagnosticsHost {
    fun activePlaybackIdentity(): ActivePlaybackMediaIdentity

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun activeMetadataValues(mediaId: String): Set<String>

    fun nextTraceSequence(): Long
}

internal class AppleVisibleMetadataDiagnostics(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleVisibleMetadataDiagnosticsHost,
) {
    private companion object {
        const val MAX_VIEW_TRACE_KEYS = 1_024
        const val MAX_VIEWS_PER_SCAN = 160
    }

    private val viewTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val foregroundActivities =
        Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

    fun installHooks() {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
                .apply { isAccessible = true }
            val onPause = Activity::class.java.getDeclaredMethod("onPause")
                .apply { isAccessible = true }
            val onWindowFocusChanged = Activity::class.java.getDeclaredMethod(
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            runtime.hookRegistrar.installHook(onResume, after = { chain, _ ->
                val activity = chain.thisObject as? Activity ?: return@installHook
                foregroundActivities[activity] = true
                runtime.mainHandler.postDelayed({ scan("activity_resumed") }, 250L)
            })
            runtime.hookRegistrar.installHook(onPause, before = { chain ->
                val activity = chain.thisObject as? Activity ?: return@installHook
                foregroundActivities.remove(activity)
            })
            runtime.hookRegistrar.installHook(onWindowFocusChanged, after = { chain, _ ->
                val hasFocus = chain.args.firstOrNull() as? Boolean ?: false
                if (!hasFocus) return@installHook
                val activity = chain.thisObject as? Activity ?: return@installHook
                foregroundActivities[activity] = true
                runtime.mainHandler.postDelayed({ scan("window_focus") }, 250L)
            })
            ProviderLogger.info("Apple Music debug 可见元数据取证 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music debug 可见元数据取证 Hook 安装失败", it)
        }
    }

    fun scan(trigger: String) {
        if (!BuildConfig.DEBUG) return
        val identity = host.activePlaybackIdentity()
        val mediaId = identity.mediaId ?: return
        val alias = host.effectiveAlias(mediaId)
        val metadataValues = host.activeMetadataValues(mediaId)
        val activities = synchronized(foregroundActivities) {
            foregroundActivities.keys.toList()
        }
        activities.forEach { activity ->
            val root = runCatching { activity.window?.decorView }.getOrNull() ?: return@forEach
            var visited = 0
            var textViews = 0
            var logged = 0
            val pending = ArrayDeque<View>()
            pending.add(root)
            while (pending.isNotEmpty() && visited < 2_000) {
                val view = pending.removeFirst()
                visited += 1
                if (view is TextView) {
                    textViews += 1
                    val text = view.text?.toString()?.trim().orEmpty()
                    if (
                        text.isNotEmpty() &&
                        text in metadataValues &&
                        view.isShown &&
                        logged < MAX_VIEWS_PER_SCAN
                    ) {
                        val traceKey =
                            "view:${System.identityHashCode(view)}:$text:${activity.javaClass.name}"
                        if (
                            viewTraceKeys.size < MAX_VIEW_TRACE_KEYS &&
                            viewTraceKeys.add(traceKey)
                        ) {
                            logged += 1
                            ProviderLogger.info(
                                "Apple Music 元数据链路: " +
                                    "seq=${host.nextTraceSequence()}, " +
                                    "event=debug_visible_view, trigger=$trigger, " +
                                    "selected=$mediaId, alias=${alias?.title}/${alias?.artist}, " +
                                    "text=${text.take(120)}, " +
                                    "view=${viewDescription(view)}, " +
                                    "parents=${viewParentChain(view)}"
                            )
                        }
                    }
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        view.getChildAt(index)?.let(pending::addLast)
                    }
                }
            }
            ProviderLogger.info(
                "Apple Music 元数据链路: " +
                    "seq=${host.nextTraceSequence()}, " +
                    "event=debug_view_scan, trigger=$trigger, selected=$mediaId, " +
                    "activity=${activity.javaClass.name}, root=${root.javaClass.name}, " +
                    "visited=$visited, textViews=$textViews, logged=$logged"
            )
        }
    }

    fun viewDescription(view: View): String {
        val id = view.id
        val resourceName = if (id == View.NO_ID) {
            "no-id"
        } else {
            runCatching { view.resources.getResourceName(id) }
                .getOrElse { "0x${id.toString(16)}" }
        }
        return "${view.javaClass.name}@${System.identityHashCode(view)}" +
            "[id=$resourceName,shown=${view.isShown},attached=${view.isAttachedToWindow}," +
            "visibility=${view.visibility},alpha=${view.alpha}]"
    }

    fun stackSummary(): String = Thread.currentThread().stackTrace
        .asSequence()
        .filterNot { frame ->
            frame.className.startsWith("java.lang.Thread") ||
                frame.className.contains("AppleMusicProvider")
        }
        .take(10)
        .joinToString(" <- ") { frame ->
            "${frame.className}#${frame.methodName}:${frame.lineNumber}"
        }

    private fun viewParentChain(view: View): String {
        val parents = mutableListOf<String>()
        var current = view.parent
        while (current is View && parents.size < 8) {
            val id = current.id
            val resourceName = if (id == View.NO_ID) {
                "no-id"
            } else {
                runCatching { current.resources.getResourceName(id) }
                    .getOrElse { "0x${id.toString(16)}" }
            }
            parents += "${current.javaClass.name}[$resourceName]"
            current = current.parent
        }
        return parents.joinToString(" <- ")
    }
}
