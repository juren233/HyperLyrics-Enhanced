/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Observes the actual SystemUI tint, including the native inversion animation. */
internal object IslandStatusBarColorMonitor {
    private const val TAG = "IslandStatusBarColor"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nativeApi: NativeApi? = null
    private var dispatcher: Any? = null
    private var snapshot: Snapshot? = null
    private var firstHit = false
    private var failureLogged = false

    private data class Snapshot(val tint: Int, val areas: List<Rect>)

    private class NativeApi(loader: ClassLoader) {
        val dispatcherClass = loader.loadClass(IslandStatusBarColorProfile.DISPATCHER)
        val dispatcherInterface = loader.loadClass(IslandStatusBarColorProfile.DISPATCHER_INTERFACE)
        val applyTint = dispatcherClass.getDeclaredMethod(IslandStatusBarColorProfile.APPLY_TINT).apply {
            check(returnType == Void.TYPE && !Modifier.isStatic(modifiers))
        }
        val tint = dispatcherClass.field(IslandStatusBarColorProfile.TINT, Int::class.javaPrimitiveType!!)
        val areas = dispatcherClass.field(IslandStatusBarColorProfile.AREAS, ArrayList::class.java)
        val dumpName = dispatcherClass.field(IslandStatusBarColorProfile.DUMP_NAME, String::class.java)
        val getTint: Method = dispatcherInterface.getDeclaredMethod(
            IslandStatusBarColorProfile.GET_TINT,
            Collection::class.java, View::class.java, Int::class.javaPrimitiveType,
        ).apply { check(returnType == Int::class.javaPrimitiveType && Modifier.isStatic(modifiers)) }
        private val dependencyClass = loader.loadClass(IslandStatusBarColorProfile.DEPENDENCY)
        private val dependency = dependencyClass.field(IslandStatusBarColorProfile.DEPENDENCY_INSTANCE, dependencyClass)
        private val getDependency = dependencyClass.getDeclaredMethod(
            IslandStatusBarColorProfile.GET_DEPENDENCY, Any::class.java,
        ).apply { isAccessible = true }

        fun currentDispatcher(): Any? = dependency.get(null)?.let {
            getDependency.invoke(it, dispatcherInterface)
        }?.takeIf(dispatcherClass::isInstance)

        private fun Class<*>.field(name: String, type: Class<*>): Field = getDeclaredField(name).apply {
            check(this.type == type)
            isAccessible = true
        }
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        if (nativeApi != null) return
        runCatching {
            val api = NativeApi(loader)
            module.deoptimize(api.applyTint)
            module.hook(api.applyTint).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val result = chain.proceed()
                    val receiver = chain.thisObject ?: return result
                    onMain {
                        runCatching {
                            // Other displays have their own dispatcher and must not recolor this island.
                            if (api.dumpName.get(receiver) != IslandStatusBarColorProfile.DEFAULT_DISPLAY_DUMP_NAME) {
                                return@runCatching
                            }
                            dispatcher = receiver
                            if (BuildConfig.DEBUG && !firstHit) {
                                firstHit = true
                                HookLogger.i(TAG, "状态栏反色回调首次命中")
                            }
                            if (capture(api, receiver)) IslandSlotContentAssembler.refreshStatusBarColors()
                        }.onFailure(::logFailure)
                    }
                    return result
                }
            })
            nativeApi = api
            HookLogger.i(TAG, "已安装状态栏反色监听: applyIconTint()V")
        }.onFailure(::logFailure)
    }

    /** Called on the UI thread; seeds the current tint before the next native change. */
    fun colorFor(view: View): Int {
        val api = nativeApi ?: return Color.WHITE
        return runCatching {
            if (dispatcher == null) {
                api.currentDispatcher()?.let {
                    dispatcher = it
                    capture(api, it)
                }
            }
            val current = snapshot ?: return@runCatching Color.WHITE
            api.getTint.invoke(null, current.areas, view, current.tint) as Int
        }.getOrElse {
            logFailure(it)
            Color.WHITE
        }
    }

    private fun capture(api: NativeApi, receiver: Any): Boolean {
        val next = Snapshot(
            tint = api.tint.getInt(receiver),
            areas = (api.areas.get(receiver) as? Collection<*>)
                .orEmpty().filterIsInstance<Rect>().map(::Rect),
        )
        if (next == snapshot) return false
        snapshot = next
        return true
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private fun logFailure(error: Throwable) {
        if (failureLogged) return
        failureLogged = true
        HookLogger.w(TAG, "状态栏取色不可用: ${error.javaClass.simpleName}")
    }
}
