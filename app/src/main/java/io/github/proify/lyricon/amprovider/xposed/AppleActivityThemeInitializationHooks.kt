/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Activity
import android.content.res.Configuration
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.lang.reflect.Modifier

/** Completes each initial snapshot when the native theme Flow finishes applying its first value. */
internal class AppleActivityThemeInitializationHooks(private val runtime: AppleMusicProviderRuntime) {
    private val snapshots = AppleInitialThemeSnapshot()

    fun install() {
        val version = runtime.hookResolver.version
        if (AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.ACTIVITY_THEME_CREATE).isEmpty()) return
        runCatching {
            val resolver = AppleMusicHookResolver(version, runtime.classLoader)
            val create = resolver.resolveMethod(AppleMusicHookPoint.ACTIVITY_THEME_CREATE)
            val emit = resolver.resolveMethod(AppleMusicHookPoint.THEME_MODE_EMIT)
            val state = resolver.resolveClass(AppleMusicHookPoint.APP_COMPAT_THEME_STATE)
            check(!create.compatibilityFallback && !emit.compatibilityFallback && !state.compatibilityFallback)
            val captured = create.method.declaringClass.getDeclaredField(
                create.target.runtimeMemberName(AppleMusicRuntimeMember.ACTIVITY_THEME_MODE_FIELD),
            ).apply { isAccessible = true }
            val global = state.clazz.getDeclaredField(
                state.target.runtimeMemberName(AppleMusicRuntimeMember.APP_COMPAT_THEME_MODE_FIELD),
            ).apply { isAccessible = true }
            check(captured.type == Int::class.javaPrimitiveType && !Modifier.isStatic(captured.modifiers))
            check(global.type == Int::class.javaPrimitiveType && Modifier.isStatic(global.modifiers))
            fun synchronizeInitialSnapshot(activity: Activity, globalMode: Int, phase: String) {
                if (activity.isDestroyed || activity.isFinishing) {
                    snapshots.forget(activity)
                    return
                }
                val oldMode = captured.getInt(activity)
                val nightMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                val resolvedMode = snapshots.onThemeResolved(activity, oldMode, globalMode, nightMode) ?: return
                captured.setInt(activity, resolvedMode)
                if (BuildConfig.DEBUG) ProviderLogger.diagnostic(
                    "[AM_THEME_INIT] initial_snapshot_synchronized phase=$phase " +
                        "activity=${activity.javaClass.name}@${System.identityHashCode(activity)} " +
                        "old=$oldMode global=$globalMode saved=$resolvedMode nightBits=$nightMode",
                )
            }
            // No static field reads during installation: let Apple initialize its own theme.
            runtime.hookRegistrar.installHook(create.method, after = { chain, _ ->
                val activity = chain.thisObject as? Activity ?: return@installHook
                runCatching {
                    snapshots.onCreated(activity, captured.getInt(activity),
                        activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    // Covers a resolved value arriving before this creation callback completes.
                    synchronizeInitialSnapshot(activity, global.getInt(null), "create_after")
                }.onFailure { ProviderLogger.error("Apple Music 初始主题记录失败", it) }
            })
            runtime.hookRegistrar.installHook(emit.method, after = { _, _ ->
                runCatching {
                    // Original emit first updates the global value and applies AppCompat's
                    // real theme/configuration changes. Only then complete equivalent initial
                    // follow-system snapshots. Native onRestart and recreate are not hooked here.
                    val globalMode = global.getInt(null)
                    snapshots.awaitingActivities().filterIsInstance<Activity>().forEach { activity ->
                        synchronizeInitialSnapshot(activity, globalMode, "theme_emit")
                    }
                }.onFailure { ProviderLogger.error("Apple Music 初始主题同步失败", it) }
            })
            ProviderLogger.info("Apple Music 初始主题状态同步 Hook 已安装: profile=${resolver.profile?.id}")
        }.onFailure { ProviderLogger.error("Apple Music 初始主题状态同步 Hook 安装失败", it) }
    }
}
