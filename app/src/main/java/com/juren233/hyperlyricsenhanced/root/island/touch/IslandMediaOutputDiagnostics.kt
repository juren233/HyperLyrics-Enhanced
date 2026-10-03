/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.os.Handler
import android.os.Looper
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicLong
import java.lang.reflect.Method

/** All callers are guarded by BuildConfig.DEBUG; release never initializes this object. */
internal object IslandMediaOutputDiagnostics {
    private const val TAG = "IslandMediaOutput"
    private val sequence = AtomicLong()
    private val active = ThreadLocal<Request?>()
    private val handles = mutableListOf<HookHandle>()
    private val probes = mutableSetOf<DrawProbe>()
    private val ctaHits = mutableSetOf<Method>()
    private val handler = Handler(Looper.getMainLooper())
    private class Request(val id: Long, var panelCallback: Boolean = false)

    fun install(module: XposedModule, api: IslandMediaOutputApi) {
        if (!BuildConfig.DEBUG) return
        release()
        val p = IslandMediaOutputProfile
        val targets = listOf(
            "native_click" to { api.method(p.LISTENER_TYPE, p.ON_CLICK, "void", "android.view.View") },
            "panel_show" to { api.method(p.MODAL_VIEW, p.SHOW, "void", "boolean", "android.view.View",
                "java.lang.String", p.PLUGIN_MANAGER) },
            "panel_detail" to { api.method(p.DETAIL, p.DETAIL_SHOW, "void", p.DETAIL_ADAPTER,
                "android.view.View", "java.lang.String") },
        )
        for ((stage, resolve) in targets) {
            runCatching {
                val method = resolve()
                module.deoptimize(method)
                handles += module.hook(method).intercept(TraceHook(stage))
                log(0, "hook_installed", "target=$stage")
            }.onFailure { log(0, "hook_unavailable", "target=$stage error=${it.javaClass.simpleName}") }
        }
    }

    fun request(packageName: String, source: View?): Long = sequence.incrementAndGet().also { id ->
        log(id, "request", "package=$packageName source=${source?.javaClass?.name ?: "missing"}")
        runCatching {
            val context = source?.context
            log(id, "source_context", "context=${describe(context)} application=${describe(context?.applicationContext)}")
        }.onFailure { log(id, "context_unavailable", "error=${it.javaClass.simpleName}") }
    }

    fun ctaHookInstalled(method: Method) = log(active.get()?.id ?: 0, "hook_installed",
        "target=cta_check owner=${method.declaringClass.name}")

    fun ctaCheck(method: Method, original: Context?, context: Context?, result: Any?) {
        val request = active.get()
        if (ctaHits.add(method)) log(request?.id ?: 0, "hook_first_hit", "target=cta_check")
        if (request == null) return
        // Diagnostics must never change a successfully completed native call.
        runCatching {
            val consent = context?.let { Settings.Secure.getInt(it.contentResolver,
                IslandMediaOutputProfile.CTA_STATE_KEY, -1) }
            log(request.id, "cta_check", "argumentNull=${original == null} repaired=${original !== context} " +
                "context=${describe(context)} consent=$consent nativeResult=$result")
        }.onFailure { log(request.id, "cta_trace_unavailable", "error=${it.javaClass.simpleName}") }
    }

    private fun describe(context: Context?): String = context?.let {
        "${it.javaClass.name}(package=${it.packageName},uid=${it.applicationInfo.uid})"
    } ?: "null"

    fun rejected(id: Long, reason: String, error: String) = log(id, "rejected", "reason=$reason error=$error")

    fun invokeNative(id: Long, call: () -> Unit) {
        val previous = active.get()
        val request = Request(id)
        active.set(request)
        try {
            log(id, "native_dispatch", "source=${IslandMediaOutputProfile.ISLAND_SOURCE}")
            call()
            log(id, "native_return", "panelCallback=${request.panelCallback}")
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    fun release() {
        handles.forEach { runCatching { it.unhook() } }
        releaseForReload()
    }

    fun releaseForReload() {
        // Main-thread teardown must not acquire the framework's old-module monitor.
        // onHotReloaded receives and removes these hooks through oldHookHandles.
        probes.toList().forEach(DrawProbe::cancel)
        handles.clear()
        ctaHits.clear()
        active.remove()
    }

    private class TraceHook(private val stage: String) : Hooker {
        private var firstHit = true
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val request = active.get()
            if (firstHit) {
                firstHit = false
                log(request?.id ?: 0, "hook_first_hit", "target=$stage")
            }
            if (request != null) {
                val source = if (stage == "native_click") "view" else sourceName(chain.args.getOrNull(2))
                log(request.id, stage, "source=$source")
                if (stage == "panel_show" && chain.args.firstOrNull() == true) request.panelCallback = true
            }
            val result = chain.proceed()
            if (request != null && stage == "panel_detail" && chain.args.firstOrNull() != null &&
                chain.args.getOrNull(2) == IslandMediaOutputProfile.ISLAND_SOURCE) {
                (chain.thisObject as? View)?.let { view ->
                    // handleShowingDetail runs before the window is shown. Observe actual
                    // drawing rather than declaring its successful return a visible panel.
                    runCatching { DrawProbe(view, request.id).start() }
                        .onFailure { log(request.id, "draw_probe_unavailable", "error=${it.javaClass.simpleName}") }
                }
            }
            return result
        }
    }

    private class DrawProbe(private val view: View, private val id: Long) : ViewTreeObserver.OnDrawListener {
        private val observer = view.viewTreeObserver
        private var complete = false
        private val timeout = Runnable {
            if (!complete) log(id, "panel_draw_timeout",
                "attached=${view.isAttachedToWindow} shown=${view.isShown} size=${view.width}x${view.height}")
            cancel()
        }

        fun start() {
            observer.addOnDrawListener(this)
            probes += this
            handler.postDelayed(timeout, 5_000L)
        }

        override fun onDraw() {
            if (complete || !view.isShown || view.alpha <= 0f || view.width <= 0 || view.height <= 0) return
            complete = true
            log(id, "panel_first_visible_draw", "source=${IslandMediaOutputProfile.ISLAND_SOURCE} size=${view.width}x${view.height}")
            // Android forbids removing an OnDrawListener from inside its callback.
            handler.post { cancel() }
        }

        fun cancel() {
            complete = true
            handler.removeCallbacks(timeout)
            if (observer.isAlive) runCatching { observer.removeOnDrawListener(this) }
            val current = view.viewTreeObserver
            if (current !== observer && current.isAlive) runCatching { current.removeOnDrawListener(this) }
            probes -= this
        }
    }

    private fun sourceName(value: Any?): String = when (value) {
        "dynamic_island", "notification", "keyguard" -> value as String
        else -> "other"
    }

    private fun log(id: Long, stage: String, details: String) {
        if (BuildConfig.DEBUG) runCatching { HookLogger.i(TAG, "request=$id stage=$stage $details") }
    }
}
