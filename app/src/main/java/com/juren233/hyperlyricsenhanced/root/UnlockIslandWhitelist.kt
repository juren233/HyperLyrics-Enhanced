package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import com.juren233.hyperlyricsenhanced.common.RootConstants
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object UnlockIslandWhitelist {
    private const val TARGET_CLASS = "miui.systemui.notification.NotificationSettingsManager"
    private const val TARGET_METHOD = "mediaIslandSupportMiniWindow"

    internal lateinit var module: XposedModule
    private val hookedClassLoaders = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())
    )
    private val hookHandles = ConcurrentHashMap<Method, HookHandle>()
    private val targetLoaders = DefiningClassLoaderTracker(TARGET_CLASS)
    private var registeredPrefs: SharedPreferences? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val loggedFirstHit = java.util.concurrent.atomic.AtomicBoolean(false)

    fun hook(xposedModule: XposedModule, defaultClassLoader: ClassLoader) {
        module = xposedModule
        val prefs = (module as HookEntry).prefs
        val prefKey = RootConstants.KEY_HOOK_REMOVE_ISLAND_WHITELIST

        unregisterPreferenceListener()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == prefKey) {
                val enabled = prefs.getBoolean(prefKey, RootConstants.DEFAULT_HOOK_REMOVE_ISLAND_WHITELIST)
                if (enabled) {
                    hookAllKnownClassLoaders()
                } else {
                    unhookAll()
                }
            }
        }
        prefsListener = listener
        registeredPrefs = prefs
        prefs.registerOnSharedPreferenceChangeListener(listener)

        doHookInClassLoader(defaultClassLoader)
    }

    fun doHookInClassLoader(cl: ClassLoader?) {
        if (cl == null || !hookedClassLoaders.add(cl)) return
        // 开关关闭时也要记下定义目标类的 loader，重新开启时才能给它补装。
        val targetClass = runCatching { targetLoaders.resolve(cl) }
            .onFailure { e -> HookLogger.e("UnlockIslandWhitelist", "媒体超级岛下拉小窗白名单注入失败", e) }
            .getOrNull() ?: return

        val prefs = (module as? HookEntry)?.prefs ?: return
        val enabled = prefs.getBoolean(RootConstants.KEY_HOOK_REMOVE_ISLAND_WHITELIST, RootConstants.DEFAULT_HOOK_REMOVE_ISLAND_WHITELIST)
        if (!enabled) return

        installHook(targetClass)
    }

    private fun installHook(targetClass: Class<*>) {
        runCatching {
            val method = targetClass.declaredMethods.find { it.name == TARGET_METHOD }

            if (method != null && !hookHandles.containsKey(method)) {
                module.deoptimize(method)
                val handle = module.hook(method).intercept(ReturnTrueHooker())
                // 并发补装时只保留一份 Hook。
                if (hookHandles.putIfAbsent(method, handle) != null) {
                    handle.unhook()
                    return@runCatching
                }
            HookLogger.i(
                "UnlockIslandWhitelist",
                "超级岛下拉小窗白名单 Hook 已安装: method=$TARGET_METHOD"
            )
            } else if (method == null) {
                HookLogger.d(
                    "UnlockIslandWhitelist",
                    "$TARGET_CLASS 存在但未找到方法 $TARGET_METHOD"
                )
            }
        }.onFailure { e ->
            HookLogger.e("UnlockIslandWhitelist", "媒体超级岛下拉小窗白名单注入失败", e)
        }
    }

    private fun hookAllKnownClassLoaders() {
        targetLoaders.knownClasses().forEach(::installHook)
    }

    private fun unhookAll() {
        hookHandles.values.forEach { it.unhook() }
        hookHandles.clear()
            HookLogger.i("UnlockIslandWhitelist", "超级岛下拉小窗白名单 Hook 已移除")
    }

    private fun unregisterPreferenceListener() {
        val listener = prefsListener
        val prefs = registeredPrefs
        prefsListener = null
        registeredPrefs = null
        if (listener != null) prefs?.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 旧模块代退出时释放偏好监听和全部 loader/Hook 记录。
     * 不在这里 unhook：旧一代的 Hook 由框架交给新一代统一移除，旧一代在交接时调用
     * unhook 会与框架持有的模块锁互等（见 docs/knowledge-base/systemui-hot-reload.md）。
     */
    internal fun releaseForReload() {
        unregisterPreferenceListener()
        hookHandles.clear()
        hookedClassLoaders.clear()
        targetLoaders.clear()
    }

    class ReturnTrueHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            // hook 存在即代表功能开启，无需读取偏好
            if (UnlockIslandWhitelist.loggedFirstHit.compareAndSet(false, true)) {
                HookLogger.i("UnlockIslandWhitelist", "下拉小窗白名单 Hook 首次命中")
            }
            return true
        }
    }
}
