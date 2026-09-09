/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.dexkit.DexMethodWatchdog
import com.juren233.hyperlyricsenhanced.common.dexkit.DexResolutionSource
import com.juren233.hyperlyricsenhanced.common.dexkit.DexWatchdogEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.AnnotationElementMatcher
import org.luckypray.dexkit.query.matchers.AnnotationMatcher
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object OfficialProviderDexMethodSemanticFilter {
    fun accepts(
        invokedMethodDescriptors: Collection<String>,
        forbiddenInvokedMethodDescriptors: Collection<String>,
    ): Boolean {
        if (forbiddenInvokedMethodDescriptors.isEmpty()) return true
        val forbidden = forbiddenInvokedMethodDescriptors.toHashSet()
        return invokedMethodDescriptors.none(forbidden::contains)
    }
}

internal class OfficialProviderDexRepairGate {
    private val started = AtomicBoolean(false)

    fun tryStart(): Boolean = started.compareAndSet(false, true)
}

internal class OfficialProviderDexHookActivation {
    private val generation = AtomicLong(0L)

    fun current(): Long = generation.get()

    fun replace(): Long = generation.incrementAndGet()

    fun isActive(candidate: Long): Boolean = generation.get() == candidate
}

/**
 * Tracks whether a Provider has already received Metadata for each MediaSession.
 *
 * Some players create and populate their MediaSession before the Provider Pack finishes
 * installing its hooks. The first observed callback can therefore be setPlaybackState(), while
 * the current Metadata is already available through MediaSession.controller. Missing snapshots
 * remain retryable; a delivered snapshot or a real setMetadata() callback suppresses duplicates.
 */
internal class OfficialProviderMediaSessionMetadataGate {
    enum class SnapshotDecision {
        DELIVER,
        MISSING_FIRST,
        MISSING_REPEATED,
        ALREADY_DELIVERED,
        NO_SESSION,
    }

    private val deliveredSessions = WeakHashMap<Any, Unit>()
    private val missingSessions = WeakHashMap<Any, Unit>()

    @Synchronized
    fun recordExplicit(session: Any?) {
        session ?: return
        deliveredSessions[session] = Unit
        missingSessions.remove(session)
    }

    @Synchronized
    fun release(session: Any?) {
        session ?: return
        deliveredSessions.remove(session)
    }

    @Synchronized
    fun claimSnapshot(session: Any?, hasMetadata: Boolean): SnapshotDecision {
        session ?: return SnapshotDecision.NO_SESSION
        if (deliveredSessions.containsKey(session)) {
            return SnapshotDecision.ALREADY_DELIVERED
        }
        if (!hasMetadata) {
            return if (missingSessions.put(session, Unit) == null) {
                SnapshotDecision.MISSING_FIRST
            } else {
                SnapshotDecision.MISSING_REPEATED
            }
        }
        deliveredSessions[session] = Unit
        missingSessions.remove(session)
        return SnapshotDecision.DELIVER
    }
}

/**
 * Static host for official Provider Packs.
 *
 * Only this class touches libxposed. Pack callbacks receive ordinary Android
 * values and never receive [XposedModule] or [XposedInterface.Chain].
 */
