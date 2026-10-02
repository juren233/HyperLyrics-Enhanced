/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package io.github.proify.lyricon.amprovider.xposed.hooks

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.ApplePlayerLaunchState
import io.github.proify.lyricon.amprovider.xposed.Constants
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.shouldRepairAppleMediaSessionLaunch
import java.util.WeakHashMap

/** Versioned app launch and native player-sheet handoff, independent of media metadata. */
internal class AppleMediaLaunchHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val openFullPlayerEnabled: () -> Boolean,
) {
    private val requests = WeakHashMap<Any, ApplePlayerLaunchState<Intent>>()

    fun notificationIntent(): Intent? = launchComponent()?.let { target ->
        Intent().apply {
            component = target
            putExtra(SHOW_FULL_PLAYER_EXTRA, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    fun install() {
        val resolver = runtime.hookResolver
        // No new lifecycle hooks or native-session changes on the verified 6.x profiles.
        if (resolver.profile?.targets(AppleMusicHookPoint.APPLE_MEDIA_SESSION_SERVICE).isNullOrEmpty()) return
        runCatching { installNativeSessionRepair() }
            .onFailure { ProviderLogger.error("Apple Music 媒体会话启动入口适配失败", it) }
        runCatching { installPlayerSheetHandoff() }
            .onFailure { ProviderLogger.error("Apple Music 通知播放页入口适配失败", it) }
    }

    private fun installNativeSessionRepair() {
        val resolver = runtime.hookResolver
        val serviceCreate = resolver.resolveMethod(AppleMusicHookPoint.APPLE_MEDIA_SESSION_SERVICE).method
        val service = serviceCreate.declaringClass
        // Also deoptimize the verified caller: the short framework getActivity overload
        // can otherwise be inlined into precompiled service code and bypass its Hook.
        runtime.module.deoptimize(serviceCreate)
        val legacy = resolver.configuredClassNames(AppleMusicHookPoint.APPLE_MEDIA_LEGACY_ACTIVITY).single()
        // 1606 original DEX calls exactly this overload from MediaPlaybackService.onCreate.
        val factory = PendingIntent::class.java.getDeclaredMethod(
            "getActivity", Context::class.java, Int::class.javaPrimitiveType,
            Intent::class.java, Int::class.javaPrimitiveType,
        )
        runtime.hookRegistrar.installArgumentRewriteHook(factory) { chain ->
            val original = chain.args[2] as? Intent ?: return@installArgumentRewriteHook null
            val component = original.component
            if (!shouldRepairAppleMediaSessionLaunch(
                    service.isInstance(chain.args[0]), chain.args[1] as Int,
                    component?.packageName, component?.className, legacy,
                ) || isLaunchable(component)) return@installArgumentRewriteHook null
            val target = launchComponent() ?: return@installArgumentRewriteHook null
            // Keep request code, action, data, extras, activity flags and PendingIntent flags.
            val corrected = Intent(original).apply { this.component = target }
            if (BuildConfig.DEBUG) ProviderLogger.diagnostic(
                "AppleMediaLaunch session_target_repaired target=${target.className}"
            )
            arrayOf(chain.args[0], chain.args[1], corrected, chain.args[3])
        }
        ProviderLogger.info("Apple Music 媒体会话启动入口 Hook 已安装: profile=${resolver.profile?.id}")
    }

    private fun installPlayerSheetHandoff() {
        val resolver = runtime.hookResolver
        // Resolve the complete set before installing any lifecycle callbacks.
        val activityModel = resolver.resolveMethod(AppleMusicHookPoint.APPLE_MEDIA_MAIN_VIEW_MODEL).method
        val fragmentModel = resolver.resolveMethod(AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_MODEL).method
        val expand = resolver.resolveMethod(AppleMusicHookPoint.APPLE_MEDIA_PLAYER_EXPAND).method
        val newIntent = resolver.resolveMethod(AppleMusicHookPoint.APPLE_MEDIA_MAIN_NEW_INTENT).method
        val resumed = resolver.resolveMethod(AppleMusicHookPoint.APPLE_MEDIA_MAIN_POST_RESUME).method
        val created = resolver.resolveMethod(AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_CREATED).method
        val destroyed = resolver.resolveMethod(AppleMusicHookPoint.APPLE_MEDIA_PLAYER_VIEW_DESTROYED).method

        fun dispatch(model: Any) {
            val state = requests[model] ?: return
            if (!openFullPlayerEnabled()) {
                state.cancel()?.removeExtra(SHOW_FULL_PLAYER_EXTRA)
                return
            }
            state.dispatch(true) expandRequest@ { intent ->
                if (!intent.getBooleanExtra(SHOW_FULL_PLAYER_EXTRA, false)) return@expandRequest true
                if (runCatching { expand.invoke(model) }.onFailure {
                        ProviderLogger.error("Apple Music 原生播放页展开失败", it)
                    }.isFailure) return@expandRequest false
                intent.removeExtra(SHOW_FULL_PLAYER_EXTRA)
                if (BuildConfig.DEBUG) ProviderLogger.diagnostic("AppleMediaLaunch player_sheet_requested")
                true
            }
        }

        fun receive(activity: Any, intent: Intent?, replace: Boolean) {
            val model = activityModel.invoke(activity) ?: return
            val state = requests.getOrPut(model) { ApplePlayerLaunchState() }
            val requested = intent?.takeIf { it.getBooleanExtra(SHOW_FULL_PLAYER_EXTRA, false) }
            if (replace || requested != null) state.request(requested)
            dispatch(model)
        }

        runtime.hookRegistrar.installHook(newIntent, after = { chain, _ ->
            // Activity.getIntent can still refer to its original cold-start intent.
            // A newer launch supersedes that marker, including ordinary launcher opens.
            val previous = (chain.thisObject as? Activity)?.intent
            if (previous !== chain.args[0]) previous?.removeExtra(SHOW_FULL_PLAYER_EXTRA)
            receive(chain.thisObject!!, chain.args[0] as? Intent, replace = true)
        })
        runtime.hookRegistrar.installHook(resumed, after = { chain, _ ->
            val activity = chain.thisObject as? Activity ?: return@installHook
            receive(activity, activity.intent, replace = false)
        })
        runtime.hookRegistrar.installHook(created, after = { chain, _ ->
            val model = fragmentModel.invoke(chain.thisObject) ?: return@installHook
            requests.getOrPut(model) { ApplePlayerLaunchState() }.viewReady = true
            dispatch(model)
        })
        runtime.hookRegistrar.installHook(destroyed, before = { chain ->
            val model = fragmentModel.invoke(chain.thisObject) ?: return@installHook
            requests[model]?.viewReady = false
        })
        ProviderLogger.info("Apple Music 原生播放页展开 Hook 已安装: profile=${resolver.profile?.id}")
    }

    private fun launchComponent(): ComponentName? = runtime.hookResolver
        .configuredClassNames(AppleMusicHookPoint.APPLE_MAIN_CONTENT_ACTIVITY)
        .asSequence()
        .map { ComponentName(Constants.APPLE_MUSIC_PACKAGE_NAME, it) }
        .firstOrNull(::isLaunchable)

    private fun isLaunchable(component: ComponentName?): Boolean = component != null && runCatching {
        val info = runtime.application.packageManager.resolveActivity(
            Intent().setComponent(component), 0,
        )?.activityInfo
        info?.enabled == true && info.applicationInfo.enabled
    }.getOrDefault(false)

    companion object {
        const val SHOW_FULL_PLAYER_EXTRA = "com.apple.android.music.intent.showfullplayer"
    }
}
