/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.dexkit.DexMethodWatchdog
import com.juren233.hyperlyricsenhanced.common.dexkit.DexResolutionSource
import com.juren233.hyperlyricsenhanced.common.dexkit.DexWatchdogEvent
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Debug-only bridge between Apple's independent DexKit resolver and the common watchdog. */
internal object AppleMusicDexKitWatchdog {
    private const val TIMEOUT_MS = 30_000L
    private const val MAX_DETAIL_LENGTH = 256

    private val watchdog = if (BuildConfig.DEBUG) {
        DexMethodWatchdog(::logEvent)
    } else {
        null
    }
    private val scheduler: ScheduledExecutorService? = if (BuildConfig.DEBUG) {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "HLE-Apple-DexWatchdog").apply { isDaemon = true }
        }
    } else {
        null
    }
    private val executableKeys = ConcurrentHashMap<String, MutableSet<String>>()
    private val classKeys = ConcurrentHashMap<String, MutableSet<String>>()

    fun resolvedMethod(
        hookPoint: AppleMusicHookPoint,
        runtimeCacheKey: String,
        source: DexResolutionSource,
        cacheWritten: Boolean,
        method: Executable,
    ) {
        val cacheKey = "apple-method:$hookPoint"
        resolved(
            cacheKey = cacheKey,
            runtimeCacheKey = runtimeCacheKey,
            source = source,
            cacheWritten = cacheWritten,
            target = method.toGenericString(),
        )
        executableKeys.computeIfAbsent(methodKey(method)) { ConcurrentHashMap.newKeySet() }
            .add(cacheKey)
    }

    fun resolvedClass(
        hookPoint: AppleMusicHookPoint,
        templateClassName: String,
        runtimeCacheKey: String,
        source: DexResolutionSource,
        cacheWritten: Boolean,
        clazz: Class<*>,
    ) {
        val cacheKey = "apple-class:$hookPoint:$templateClassName"
        resolved(
            cacheKey = cacheKey,
            runtimeCacheKey = runtimeCacheKey,
            source = source,
            cacheWritten = cacheWritten,
            target = clazz.name,
        )
        classKeys.computeIfAbsent(clazz.name) { ConcurrentHashMap.newKeySet() }
            .add(cacheKey)
    }

    fun hookInstalled(executable: Executable) {
        keysFor(executable).forEach { key ->
            watchdog?.hookInstalled(key, executable.toGenericString())
        }
    }

    fun callback(executable: Executable) {
        keysFor(executable).forEach { key -> watchdog?.callback(key) }
    }

    fun validation(executable: Executable, valid: Boolean, detail: String?) {
        keysFor(executable).forEach { key ->
            watchdog?.validation(key, valid, detail?.take(MAX_DETAIL_LENGTH))
        }
    }

    private fun resolved(
        cacheKey: String,
        runtimeCacheKey: String,
        source: DexResolutionSource,
        cacheWritten: Boolean,
        target: String,
    ) {
        val currentWatchdog = watchdog ?: return
        currentWatchdog.register(cacheKey, runtimeCacheKey)
        currentWatchdog.resolved(cacheKey, source, cacheWritten, target)
        scheduler?.schedule(
            { currentWatchdog.timeout(cacheKey) },
            TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun keysFor(executable: Executable): Set<String> = buildSet {
        addAll(executableKeys[methodKey(executable)].orEmpty())
        addAll(classKeys[executable.declaringClass.name].orEmpty())
    }

    private fun methodKey(executable: Executable): String = executable.toGenericString()

    private fun logEvent(event: DexWatchdogEvent) {
        val detail = event.detail
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.take(MAX_DETAIL_LENGTH)
        ProviderLogger.diagnostic(
            "[AppleDexWatchdog] stage=${event.stage}, result=${event.result}, " +
                "key=${event.cacheKey}, runtimeKey=${event.runtimeCacheKey}, " +
                "source=${event.source?.name?.lowercase()}, " +
                "cacheWritten=${event.cacheWritten}, hookInstalled=${event.hookInstalled}, " +
                "callbackCount=${event.callbackCount}, validationCount=${event.validationCount}, " +
                "validObserved=${event.validObserved}, target=${event.target}, detail=$detail",
        )
    }
}
