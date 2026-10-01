/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger

/** Restore the native Data category and optionally force Apple cellular availability. */
internal class AppleCellularDataSettingsHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> SharedPreferences?,
) {
    private val scope = AppleCellularDataSettingsScope()

    fun installCellularAvailability() {
        val method = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.CELLULAR_AVAILABILITY,
        ).method
        runtime.hookRegistrar.installResultOverrideHook(method) { _, result ->
            forceAppleCellularAvailability(
                enabled = preferences()?.getBoolean(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY,
                ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY,
                original = result,
            )
        }
        ProviderLogger.info("Apple Music 蜂窝可用性 Hook 已安装: target=$method")
    }

    fun install() {
        if (runtime.hookResolver.profile?.settingsDataCategoryHasNoSimGate == true) {
            ProviderLogger.info("Apple Music 原生设置入口无 SIM 门禁；登录及偏好就绪条件保留")
            return
        }
        val build = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.SETTINGS_DATA_CATEGORY_BUILD,
        ).method
        val simCheck = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.SETTINGS_CELLULAR_SIM_CHECK,
        ).method
        // Resolve both targets before installing either hook. The original DEX places the
        // category gate before x1(); consume only that first check so nested data-saver
        // initialization and all later network checks keep their original result.
        runtime.hookRegistrar.installResultOverrideHook(simCheck) { _, result ->
            if (scope.consume()) {
                if (BuildConfig.DEBUG) {
                    ProviderLogger.diagnostic("Apple Music 数据分组 SIM 检查已绕过: original=$result")
                }
                true
            } else {
                result
            }
        }
        runtime.hookRegistrar.installScopedHook(
            build,
            enter = {
                scope.enter(
                    preferences()?.getBoolean(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FORCE_CELLULAR_DATA_ENTRY,
                )
                true
            },
            after = { _, _ -> },
            exit = scope::exit,
        )
        ProviderLogger.info("Apple Music 数据分组 Hook 已安装: build=$build, simCheck=$simCheck")
    }
}

/** One allowance per settings rebuild, isolated by thread and cleared even on failure. */
internal class AppleCellularDataSettingsScope {
    private val frames = ThreadLocal<ArrayDeque<Boolean>>()

    fun enter(enabled: Boolean) {
        val stack = frames.get() ?: ArrayDeque<Boolean>().also(frames::set)
        stack.addLast(enabled)
    }

    fun consume(): Boolean {
        val stack = frames.get() ?: return false
        if (stack.isEmpty() || !stack.last()) return false
        stack.removeLast()
        stack.addLast(false)
        return true
    }

    fun exit() {
        val stack = frames.get() ?: return
        stack.removeLastOrNull()
        if (stack.isEmpty()) frames.remove()
    }
}

/** No settings-page scope: every availability query follows the current user toggle. */
internal fun forceAppleCellularAvailability(enabled: Boolean, original: Any?): Any? =
    if (enabled) true else original
