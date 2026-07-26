package com.juren233.hyperlyricsenhanced.root

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

object UnlockFocusWhitelist {
    private const val TARGET_CLASS = "miui.systemui.notification.NotificationSettingsManager"
    private const val AUTH_CALLBACK_CLASS =
        $$"miui.systemui.notification.auth.AuthManager$AuthServiceCallback$onAuthResult$1"
    private const val PLUGIN_INSTANCE_CLASS = "com.android.systemui.shared.plugins.PluginInstance"

    internal lateinit var module: XposedModule
    private val hookedClassLoaders =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())
    private val focusSettingsHookedClassLoaders =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())
    private val focusSettingsHandles = mutableListOf<HookHandle>()
    private val whitelistHandles = mutableListOf<HookHandle>()
    private val knownClassLoaders = mutableSetOf<ClassLoader>()
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun hook(xposedModule: XposedModule, defaultClassLoader: ClassLoader) {
        module = xposedModule
        val prefs = (module as HookEntry).prefs
        val prefKey = RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST

        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
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
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

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

    private fun doHookInClassLoader(cl: ClassLoader?) {
        if (cl == null || !hookedClassLoaders.add(cl)) return
        knownClassLoaders.add(cl)
        installFocusSettingsHooks(cl)

        val prefs = (module as? HookEntry)?.prefs ?: return
        if (
            prefs.getBoolean(
                RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST,
                RootConstants.DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST,
            )
        ) {
            installWhitelistHooks(cl)
        }
    }

    private fun installFocusSettingsHooks(cl: ClassLoader) {
        if (!focusSettingsHookedClassLoaders.add(cl)) return
        runCatching {
            val targetClass = cl.loadClass(TARGET_CLASS)
            val methods = targetClass.declaredMethods.filter {
                it.name == "canShowFocus" || it.name == "canCustomFocus"
            }
            methods.forEach { method ->
                module.deoptimize(method)
                focusSettingsHandles.add(
                    module.hook(method).intercept(FocusSettingsHooker())
                )
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

    private fun installWhitelistHooks(cl: ClassLoader) {
        runCatching {
            val authClass = cl.loadClass(AUTH_CALLBACK_CLASS)
            val method = authClass.declaredMethods.find { it.name == "invokeSuspend" }
            if (method != null) {
                module.deoptimize(method)
                whitelistHandles.add(
                    module.hook(method).intercept(AuthResultHooker())
                )
                HookLogger.i("UnlockFocusWhitelist", "焦点通知授权 Hook 已安装")
            }
        }.onFailure { e ->
            if (e !is ClassNotFoundException) {
                HookLogger.e(
                    "UnlockFocusWhitelist",
                    "注入焦点通知授权失败: classLoader=$cl",
                    e,
                )
            }
        }
    }

    private fun hookAllKnownClassLoaders() {
        knownClassLoaders.toList().forEach(::installWhitelistHooks)
    }

    private fun unhookWhitelist() {
        whitelistHandles.forEach { it.unhook() }
        whitelistHandles.clear()
        HookLogger.i("UnlockFocusWhitelist", "焦点通知授权 Hook 已移除")
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
            return if (isWhitelistRemovalEnabled()) {
                true
            } else {
                chain.proceed()
            }
        }
    }

    class AuthResultHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
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
