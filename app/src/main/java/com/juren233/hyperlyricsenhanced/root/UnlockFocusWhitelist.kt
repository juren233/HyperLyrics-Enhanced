package com.juren233.hyperlyricsenhanced.root

import com.juren233.hyperlyricsenhanced.root.reload.SystemUiHookLifetime
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object UnlockFocusWhitelist {
    private const val TARGET_CLASS = "miui.systemui.notification.NotificationSettingsManager"
    private const val AUTH_CALLBACK_CLASS =
        $$"miui.systemui.notification.auth.AuthManager$AuthServiceCallback$onAuthResult$1"
    private const val PLUGIN_INSTANCE_CLASS = "com.android.systemui.shared.plugins.PluginInstance"

    internal lateinit var module: XposedModule
    private val hookedClassLoaders = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())
    )
    // 按目标方法去重：多个子 loader 委托到同一个定义 loader 时，同一方法只装一份 Hook。
    private val focusSettingsHandles = ConcurrentHashMap<Method, HookHandle>()
    private val whitelistHandles = ConcurrentHashMap<Method, HookHandle>()
    private val authCallbackLoaders = DefiningClassLoaderTracker(AUTH_CALLBACK_CLASS)
    private var registeredPrefs: SharedPreferences? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val loggedFirstFocusHit = java.util.concurrent.atomic.AtomicBoolean(false)

    fun hook(xposedModule: XposedModule, defaultClassLoader: ClassLoader) {
        module = xposedModule
        val prefs = (module as HookEntry).prefs
        val prefKey = RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST

        unregisterPreferenceListener()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == prefKey) {
                val enabled = prefs.getBoolean(
                    prefKey,
                    RootConstants.DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST,
                )
                if (enabled) {
                    hookAllKnownClassLoaders()
                } else {
                    unhookWhitelist()
                }
            }
        }
        prefsListener = listener
        registeredPrefs = prefs
        prefs.registerOnSharedPreferenceChangeListener(listener)

        runCatching {
            val pluginInstanceClass = defaultClassLoader.loadClass(PLUGIN_INSTANCE_CLASS)
            val method = pluginInstanceClass.declaredMethods.find { it.name == "loadPlugin" }
            if (method != null) {
                module.deoptimize(method)
                module.hook(method).intercept(PluginLoadHooker())
                HookLogger.d(
                    "UnlockFocusWhitelist",
                    "安装插件加载 Hook: target=PluginInstance.loadPlugin",
                )
            } else {
                HookLogger.w("UnlockFocusWhitelist", "未找到 PluginInstance.loadPlugin")
            }
        }.onFailure { e ->
            if (e is ClassNotFoundException) {
                HookLogger.w("UnlockFocusWhitelist", "$PLUGIN_INSTANCE_CLASS 未找到")
            } else {
                HookLogger.e("UnlockFocusWhitelist", "安装插件加载 Hook 失败", e)
            }
        }

        doHookInClassLoader(defaultClassLoader)
    }

    internal fun doHookInClassLoader(cl: ClassLoader?) {
        if (cl == null || !hookedClassLoaders.add(cl)) return
        installFocusSettingsHooks(cl)

        // 开关关闭时也要记下定义授权回调类的 loader，重新开启时才能给它补装。
        val authClass = runCatching { authCallbackLoaders.resolve(cl) }
            .onFailure { e ->
                HookLogger.e("UnlockFocusWhitelist", "注入焦点通知授权失败: classLoader=$cl", e)
            }
            .getOrNull() ?: return

        val prefs = (module as? HookEntry)?.prefs ?: return
        if (
            prefs.getBoolean(
                RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST,
                RootConstants.DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST,
            )
        ) {
            installWhitelistHooks(authClass)
        }
    }

    private fun installFocusSettingsHooks(cl: ClassLoader) {
        runCatching {
            val targetClass = cl.loadClass(TARGET_CLASS)
            val methods = targetClass.declaredMethods.filter {
                (it.name == "canShowFocus" || it.name == "canCustomFocus") &&
                    !focusSettingsHandles.containsKey(it)
            }
            methods.forEach { method ->
                module.deoptimize(method)
                installOnce(focusSettingsHandles, method, FocusSettingsHooker())
            }
            if (methods.isNotEmpty()) {
                HookLogger.i(
                    "UnlockFocusWhitelist",
                    "焦点通知白名单 Hook 已安装: methods=${methods.joinToString { it.name }}",
                )
            }
        }.onFailure { e ->
            if (e !is ClassNotFoundException) {
                HookLogger.e(
                    "UnlockFocusWhitelist",
                    "注入焦点通知白名单失败: classLoader=$cl",
                    e,
                )
            }
        }
    }

    private fun installWhitelistHooks(authClass: Class<*>) {
        runCatching {
            val method = authClass.declaredMethods.find { it.name == "invokeSuspend" }
            if (method != null && !whitelistHandles.containsKey(method)) {
                module.deoptimize(method)
                if (installOnce(whitelistHandles, method, AuthResultHooker())) {
                    HookLogger.i("UnlockFocusWhitelist", "焦点通知授权 Hook 已安装")
                }
            }
        }.onFailure { e ->
            HookLogger.e(
                "UnlockFocusWhitelist",
                "注入焦点通知授权失败: classLoader=${authClass.classLoader}",
                e,
            )
        }
    }

    /** 并发补装同一方法时只保留一份 Hook；返回本次是否装上。 */
    private fun installOnce(
        handles: ConcurrentHashMap<Method, HookHandle>,
        method: Method,
        hooker: Hooker,
    ): Boolean {
        val handle = module.hook(method).intercept(hooker)
        if (handles.putIfAbsent(method, handle) != null) {
            handle.unhook()
            return false
        }
        return true
    }

    private fun hookAllKnownClassLoaders() {
        authCallbackLoaders.knownClasses().forEach(::installWhitelistHooks)
    }

    private fun unhookWhitelist() {
        whitelistHandles.values.forEach { it.unhook() }
        whitelistHandles.clear()
        HookLogger.i("UnlockFocusWhitelist", "焦点通知授权 Hook 已移除")
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
        focusSettingsHandles.clear()
        whitelistHandles.clear()
        hookedClassLoaders.clear()
        authCallbackLoaders.clear()
    }

    private fun isWhitelistRemovalEnabled(): Boolean {
        return HookEntry.instance?.prefs?.getBoolean(
            RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST,
            RootConstants.DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST,
        ) == true
    }

    internal fun replacementHooker(executable: Executable): Hooker? {
        val method = executable as? Method ?: return null
        return when {
            method.declaringClass.name == TARGET_CLASS &&
                (method.name == "canShowFocus" || method.name == "canCustomFocus") ->
                FocusSettingsHooker()
            method.declaringClass.name == AUTH_CALLBACK_CLASS &&
                method.name == "invokeSuspend" -> AuthResultHooker()
            method.declaringClass.name == PLUGIN_INSTANCE_CLASS &&
                method.name == "loadPlugin" -> PluginLoadHooker()
            else -> null
        }
    }

    class PluginLoadHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            val result = chain.proceed()
            runCatching {
                val thisObj = chain.thisObject ?: return result
                thisObj.javaClass.declaredFields.forEach { field ->
                    if (field.name == "mPluginContext" || field.name == "mContext") {
                        field.isAccessible = true
                        (field.get(thisObj) as? Context)?.let { context ->
                            doHookInClassLoader(context.classLoader)
                            UnlockIslandWhitelist.doHookInClassLoader(context.classLoader)
                        }
                    }
                }
            }
            return result
        }
    }

    class FocusSettingsHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            if (isWhitelistRemovalEnabled()) {
                if (UnlockFocusWhitelist.loggedFirstFocusHit.compareAndSet(false, true)) {
                    HookLogger.i("UnlockFocusWhitelist", "焦点通知白名单 Hook 首次命中")
                }
                return true
            }
            return chain.proceed()
        }
    }

    class AuthResultHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (SystemUiHookLifetime.retired) return chain.proceed()
            if (!isWhitelistRemovalEnabled()) return chain.proceed()

            val thisObj = chain.thisObject
            thisObj.javaClass.declaredFields.firstNotNullOfOrNull { field ->
                if (!field.name.contains("authBundle")) return@firstNotNullOfOrNull null
                runCatching {
                    field.isAccessible = true
                    field.get(thisObj) as? Bundle
                }.getOrNull()
            }?.putInt("result_code", 0)
            return chain.proceed()
        }
    }
}
