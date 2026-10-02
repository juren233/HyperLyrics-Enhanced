/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Activity
import android.os.SystemClock
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Read-only, bounded cold-start/restart tracing; absent from the release source set. */
internal object AppleActivityRestartDiagnostics {
    private val events = AtomicInteger()
    private var installed = false

    fun install(runtime: AppleMusicProviderRuntime) {
        val version = runtime.hookResolver.version
        if (AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.ACTIVITY_THEME_CREATE).isEmpty()) return
        runCatching {
            // Exact profiles only; do not let a diagnostic repair or broaden a runtime target.
            val resolver = AppleMusicHookResolver(version, runtime.classLoader)
            val create = resolver.resolveMethod(AppleMusicHookPoint.ACTIVITY_THEME_CREATE)
            val restart = resolver.resolveMethod(AppleMusicHookPoint.ACTIVITY_THEME_RESTART)
            val emit = resolver.resolveMethod(AppleMusicHookPoint.THEME_MODE_EMIT)
            val state = resolver.resolveClass(AppleMusicHookPoint.APP_COMPAT_THEME_STATE)
            check(!create.compatibilityFallback && !restart.compatibilityFallback &&
                !emit.compatibilityFallback && !state.compatibilityFallback)
            val captured = create.method.declaringClass.getDeclaredField(
                create.target.runtimeMemberName(AppleMusicRuntimeMember.ACTIVITY_THEME_MODE_FIELD),
            ).apply { isAccessible = true }
            val global = state.clazz.getDeclaredField(
                state.target.runtimeMemberName(AppleMusicRuntimeMember.APP_COMPAT_THEME_MODE_FIELD),
            ).apply { isAccessible = true }
            check(captured.type == Int::class.javaPrimitiveType && !Modifier.isStatic(captured.modifiers))
            check(global.type == Int::class.javaPrimitiveType && Modifier.isStatic(global.modifiers))
            // Looking up fields must not read/initialize the native theme state during installation.
            val session = Session(captured, global)
            runtime.hookRegistrar.withModule("debug-activity-restart") {
                runtime.hookRegistrar.installHook(create.method,
                    before = { chain -> session.creating(chain.thisObject as? Activity) },
                    after = { chain, _ -> session.created(chain.thisObject as? Activity) },
                    gated = false,
                )
                runtime.hookRegistrar.installHook(restart.method,
                    before = { chain -> session.snapshot("restart_before", chain.thisObject as? Activity) },
                    after = { chain, _ -> session.snapshot("restart_after", chain.thisObject as? Activity) },
                    gated = false,
                )
                runtime.hookRegistrar.installHook(emit.method,
                    before = { chain -> trace("theme_emit_before", "requested=${chain.args.firstOrNull() as? Int}") },
                    after = { chain, _ -> session.themeChanged(chain.args.firstOrNull() as? Int) },
                    gated = false,
                )
                runtime.hookRegistrar.installHook(Activity::class.java.getDeclaredMethod("recreate"),
                    before = { chain ->
                        val activity = chain.thisObject as? Activity
                        if (activity != null && create.method.declaringClass.isInstance(activity)) {
                            session.snapshot("recreate", activity, includeStack = true)
                        }
                    },
                    gated = false,
                )
            }
            installed = true
            trace("installed", "profile=${resolver.profile?.id}, observational=true")
        }.onFailure { ProviderLogger.error("Apple Music 页面重建诊断安装失败", it) }
    }

    fun stage(stage: String) {
        if (installed) trace(stage, "")
    }

    private fun trace(stage: String, details: String) {
        if (events.get() >= 64) return
        val sequence = events.incrementAndGet()
        if (sequence <= 64) ProviderLogger.diagnostic(
            "[AM_RESTART_DIAG] seq=$sequence elapsedMs=${SystemClock.elapsedRealtime()} " +
                "stage=$stage thread=${Thread.currentThread().name} $details",
        )
    }

    private class Session(private val captured: Field, private val global: Field) {
        private val activities = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

        fun creating(activity: Activity?) {
            // BaseActivity has not yet initialized its theme; intentionally read neither field.
            if (activity != null) trace("create_before", "activity=${identity(activity)} theme=not_read")
        }

        fun created(activity: Activity?) {
            if (activity == null) return
            activities[activity] = true
            snapshot("create_after", activity)
        }

        fun themeChanged(requested: Int?) {
            if (events.get() >= 64) return
            runCatching {
                trace("theme_emit_after", "requested=$requested global=${global.getInt(null)}")
                synchronized(activities) { activities.keys.toList() }.forEach {
                    snapshot("activity_at_theme_emit", it)
                }
            }.onFailure { trace("read_failed", "stage=theme_emit_after error=${it.javaClass.simpleName}") }
        }

        fun snapshot(stage: String, activity: Activity?, includeStack: Boolean = false) {
            if (activity == null || events.get() >= 64) return
            runCatching {
                val savedMode = captured.getInt(activity)
                val globalMode = global.getInt(null)
                val config = activity.resources.configuration
                val stack = if (includeStack) Throwable().stackTrace.take(40).joinToString(" <- ") else ""
                trace(stage, "activity=${identity(activity)} captured=$savedMode global=$globalMode " +
                    "mismatch=${savedMode != globalMode} uiMode=${config.uiMode} " +
                    "nightBits=${config.uiMode and 0x30} locales=${config.locales.toLanguageTags()} " +
                    "destroyed=${activity.isDestroyed} finishing=${activity.isFinishing}" +
                    if (includeStack) " stack=$stack" else "")
            }.onFailure { trace("read_failed", "stage=$stage error=${it.javaClass.simpleName}") }
        }

        private fun identity(activity: Activity) = "${activity.javaClass.name}@${System.identityHashCode(activity)}"
    }
}
