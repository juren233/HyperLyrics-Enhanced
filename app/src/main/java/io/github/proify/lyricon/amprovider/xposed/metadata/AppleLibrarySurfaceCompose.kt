/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
import android.view.Choreographer
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal fun AppleLibrarySurfaceHooks.recordComposeMediaId(mediaId: String) {
    activeComposeCapture.get()?.mediaIds?.add(mediaId)
}

internal fun AppleLibrarySurfaceHooks.registerController(mediaId: String, controller: Any) {
    val refs = controllerRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
    var registered = false
    refs.forEach { ref ->
        val target = ref.get()
        if (target == null) refs.remove(ref) else if (target === controller) registered = true
    }
    if (!registered) refs.add(WeakReference(controller))
}

internal fun AppleLibrarySurfaceHooks.recordControllerBuildAliases(
    controller: Any,
    mediaIds: Collection<String>,
    replace: Boolean,
) {
    val normalizedIds = host.normalizeMediaIds(mediaIds)
    synchronized(controllerAppliedAliases) {
        val appliedAliases = if (replace) {
            mutableMapOf<String, AppliedMetadataAlias>().also {
                controllerAppliedAliases[controller] = it
            }
        } else {
            controllerAppliedAliases.getOrPut(controller) { mutableMapOf() }
        }
        normalizedIds.forEach { mediaId ->
            val alias = host.effectiveAlias(mediaId)
            if (alias == null) appliedAliases.remove(mediaId)
            else appliedAliases[mediaId] = AppliedMetadataAlias(mediaId, alias)
        }
        if (appliedAliases.isEmpty()) controllerAppliedAliases.remove(controller)
    }
    if (BuildConfig.DEBUG && normalizedIds.isNotEmpty()) {
        host.logMetadataIdentity(
            event = "library_epoxy_build_aliases_recorded",
            details = "controller=${controller.javaClass.name}@" +
                "${System.identityHashCode(controller)}, contentIds=$normalizedIds, " +
                "replace=$replace",
        )
    }
}

internal fun AppleLibrarySurfaceHooks.refreshControllers(
    mediaId: String,
    alias: Alias? = null,
    hasDirectPlaylistRow: Boolean = false,
): Int {
    if (!host.isRefreshableMediaId(mediaId)) return 0
    val refs = controllerRefs[mediaId] ?: return 0
    val targets = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    var collectedRefs = 0
    var staleRefs = 0
    var alreadyAppliedRefs = 0
    refs.forEach { ref ->
        val controller = ref.get()
        if (controller == null) {
            refs.remove(ref)
            staleRefs += 1
        } else {
            val expectedAppliedAlias = alias?.let { resolvedAlias ->
                host.controllerAppliedAlias(controller, mediaId, resolvedAlias)
            }
            if (
                expectedAppliedAlias == null ||
                synchronized(controllerAppliedAliases) {
                    controllerAppliedAliases[controller]?.get(mediaId)
                } != expectedAppliedAlias
            ) {
                collectedRefs += 1
                targets.add(controller)
            } else {
                alreadyAppliedRefs += 1
            }
        }
    }
    if (BuildConfig.DEBUG) {
        host.logMetadataIdentity(
            event = "library_epoxy_refresh_decision",
            details = "contentId=$mediaId, refs=${refs.size}, collected=$collectedRefs, " +
                "stale=$staleRefs, alreadyApplied=$alreadyAppliedRefs, " +
                "targets=${targets.size}, alias=${alias?.title}/${alias?.artist}/${alias?.album}",
        )
    }
    var scheduledTargets = 0
    targets.forEach { controller ->
        val buildStrategy = host.controllerBuildStrategy(controller)
        if (shouldUsePlaylistDirectRowRefresh(buildStrategy, hasDirectPlaylistRow)) {
            alias?.let { directAlias ->
                synchronized(controllerAppliedAliases) {
                    controllerAppliedAliases.getOrPut(controller) {
                        mutableMapOf()
                    }[mediaId] = AppliedMetadataAlias(mediaId, directAlias)
                }
            }
            if (BuildConfig.DEBUG) {
                host.logMetadataIdentity(
                    event = "library_epoxy_refresh_skipped",
                    details = "contentId=$mediaId, controller=${controller.javaClass.name}, " +
                        "strategy=$buildStrategy, reason=playlist_direct_row",
                )
            }
            return@forEach
        }
        scheduledTargets += 1
        val dispatch = synchronized(controllerRefreshStates) {
            val state = controllerRefreshStates.getOrPut(controller) {
                InAppLibraryControllerRefreshState()
            }
            state.enqueue(
                mediaId = mediaId,
                strategy = buildStrategy,
                nowUptimeMillis = SystemClock.uptimeMillis(),
                albumDebounceMillis = AppleLibrarySurfaceHooks.ALBUM_CONTROLLER_REFRESH_DEBOUNCE_MS,
                playlistIntervalMillis = AppleLibrarySurfaceHooks.PLAYLIST_CONTROLLER_REFRESH_INTERVAL_MS,
            )
        }
        if (dispatch == null) {
            if (BuildConfig.DEBUG) {
                host.logMetadataIdentity(
                    event = "library_epoxy_refresh_coalesced",
                    details = "contentId=$mediaId, controller=${controller.javaClass.name}, " +
                        "strategy=$buildStrategy, reason=rebuild_scheduled",
                )
            }
            return@forEach
        }
        scheduleControllerRefresh(controller, dispatch)
    }
    return scheduledTargets
}

internal fun AppleLibrarySurfaceHooks.detachController(controller: Any): Int {
    var removed = 0
    controllerRefs.forEach { (mediaId, refs) ->
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null || target === controller) {
                if (refs.remove(ref) && target === controller) removed += 1
            }
        }
        if (refs.isEmpty()) controllerRefs.remove(mediaId, refs)
    }
    controllerRefreshStates.remove(controller)
    controllerAppliedAliases.remove(controller)
    return removed
}

internal fun AppleLibrarySurfaceHooks.hasControllerRefs(mediaId: String): Boolean =
    controllerRefs[mediaId]?.any { it.get() != null } == true

internal fun AppleLibrarySurfaceHooks.liveControllers(mediaId: String): Set<Any> {
    val refs = controllerRefs[mediaId] ?: return emptySet()
    val controllers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    refs.forEach { ref ->
        val controller = ref.get()
        if (controller == null) refs.remove(ref) else controllers.add(controller)
    }
    return controllers
}

internal fun AppleLibrarySurfaceHooks.controllerRefCount(mediaId: String): Int =
    controllerRefs[mediaId]?.count { it.get() != null } ?: 0

internal fun AppleLibrarySurfaceHooks.refreshComposeStates(
    mediaId: String,
    alias: Alias? = null,
): Int {
    if (!host.isRefreshableMediaId(mediaId)) return 0
    val refs = composeStateRefs[mediaId] ?: return 0
    val targets = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    val appliedAlias = alias?.let { AppliedMetadataAlias(mediaId, it) }
    refs.forEach { ref ->
        val state = ref.get()
        if (state == null) {
            refs.remove(ref)
        } else if (
            shouldRefreshInAppLibraryComposeAlias(
                appliedAliases = synchronized(composeAppliedAliases) {
                    composeAppliedAliases[state]?.toMap()
                },
                mediaId = mediaId,
                requestedAlias = appliedAlias,
            )
        ) {
            targets.add(state)
        }
    }
    targets.forEach { state ->
        val shouldPost = synchronized(composeRefreshPending) {
            val pendingAliases = composeRefreshPending.getOrPut(state) { mutableMapOf() }
            val wasEmpty = pendingAliases.isEmpty()
            pendingAliases[mediaId] = appliedAlias
            wasEmpty
        }
        if (!shouldPost) return@forEach
        runtime.mainHandler.post {
            val pendingAliases = synchronized(composeRefreshPending) {
                composeRefreshPending.remove(state).orEmpty()
            }
            val activeAliases = pendingAliases.filterKeys(host::isRefreshableMediaId)
            if (activeAliases.isEmpty()) return@post
            runCatching {
                val observeTarget = checkNotNull(composeObserveTarget) {
                    "Compose observeAsState target unavailable"
                }
                val policyField = observeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_POLICY_FIELD
                )
                val getValueMethod = observeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_GET_VALUE_METHOD
                )
                val setValueMethod = observeTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD
                )
                val originalPolicy = AppleReflection.field(state, policyField)
                val neverEqualPolicy = composeNeverEqualPolicy
                    ?: error("Compose NeverEqualPolicy unavailable")
                val value = AppleReflection.call(state, getValueMethod)
                AppleReflection.setField(state, policyField, neverEqualPolicy)
                try {
                    AppleReflection.call(state, setValueMethod, value)
                } finally {
                    AppleReflection.setField(state, policyField, originalPolicy)
                }
            }.onSuccess {
                synchronized(composeAppliedAliases) {
                    val stateAliases = composeAppliedAliases.getOrPut(state) { mutableMapOf() }
                    activeAliases.forEach { (activeMediaId, activeAlias) ->
                        if (activeAlias == null) stateAliases.remove(activeMediaId)
                        else stateAliases[activeMediaId] = activeAlias
                    }
                    if (stateAliases.isEmpty()) composeAppliedAliases.remove(state)
                }
                if (BuildConfig.DEBUG) {
                    ProviderLogger.info(
                        "Apple Music 元数据链路: " +
                            "seq=${host.nextMetadataTraceSequence()}, " +
                            "event=library_compose_invalidate, " +
                            "contentIds=${activeAliases.keys}, " +
                            "state=${state.javaClass.name}"
                    )
                }
            }.onFailure {
                ProviderLogger.error(
                    "Apple Music 资料库 Compose 局部刷新失败: " +
                        "ids=${activeAliases.keys}, state=${state.javaClass.name}",
                    it,
                )
            }
        }
    }
    return targets.size
}

internal fun AppleLibrarySurfaceHooks.hasComposeStateRefs(mediaId: String): Boolean =
    composeStateRefs[mediaId]?.any { it.get() != null } == true

internal fun AppleLibrarySurfaceHooks.composeStateRefCount(mediaId: String): Int =
    composeStateRefs[mediaId]?.count { it.get() != null } ?: 0

internal fun AppleLibrarySurfaceHooks.clearConfigurationState() {
    controllerRefreshStates.clear()
    controllerAppliedAliases.clear()
    composeAppliedAliases.clear()
}

internal fun AppleLibrarySurfaceHooks.scheduleControllerRefresh(
    controller: Any,
    dispatch: InAppLibraryControllerRefreshDispatch,
) {
    val refresh = Runnable { drainControllerRefresh(controller) }
    if (dispatch.delayMillis == 0L) {
        runtime.mainHandler.post(refresh)
    } else {
        runtime.mainHandler.postDelayed(refresh, dispatch.delayMillis)
    }
}

internal fun AppleLibrarySurfaceHooks.drainControllerRefresh(controller: Any) {
    val pendingMediaIds = synchronized(controllerRefreshStates) {
        val state = controllerRefreshStates[controller]
            ?: return@synchronized emptyList()
        state.takePendingMediaIds()
    }
    val buildStrategy = host.controllerBuildStrategy(controller)
    val pendingMediaIdSet = pendingMediaIds.toSet()
    val albumTrackMediaIds = host.controllerAlbumTrackMediaIds(controller)
    val candidateMediaIds = if (
        buildStrategy == InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA
    ) {
        pendingMediaIds + albumTrackMediaIds
    } else {
        pendingMediaIds
    }
    val activeMediaIds = candidateMediaIds.distinct()
        .filter(host::isRefreshableMediaId)
        .filter { mediaId ->
            val alias = host.effectiveAlias(mediaId)
            if (alias == null) {
                mediaId in pendingMediaIdSet
            } else {
                val appliedAlias = host.controllerAppliedAlias(
                    controller = controller,
                    mediaId = mediaId,
                    alias = alias,
                )
                synchronized(controllerAppliedAliases) {
                    controllerAppliedAliases[controller]?.get(mediaId)
                } != appliedAlias
            }
        }
    if (activeMediaIds.isNotEmpty()) {
        val traceMediaId = activeMediaIds.first()
        if (BuildConfig.DEBUG) {
            host.logMetadataIdentity(
                event = "library_epoxy_refresh_invoke",
                details = "contentIds=$activeMediaIds, " +
                    "controller=${controller.javaClass.name}, strategy=$buildStrategy",
            )
            debugLibraryModelRefreshMediaId.set(traceMediaId)
        }
        runCatching {
            host.requestControllerBuild(controller, buildStrategy)
        }.onSuccess {
            val rebuiltMediaIds = if (
                buildStrategy == InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA
            ) {
                (activeMediaIds + albumTrackMediaIds).distinct()
            } else {
                activeMediaIds
            }
            synchronized(controllerAppliedAliases) {
                val appliedAliases = controllerAppliedAliases.getOrPut(controller) {
                    mutableMapOf()
                }
                rebuiltMediaIds.forEach { mediaId ->
                    val alias = host.effectiveAlias(mediaId)
                    if (alias == null) {
                        appliedAliases.remove(mediaId)
                    } else {
                        appliedAliases[mediaId] = host.controllerAppliedAlias(
                            controller = controller,
                            mediaId = mediaId,
                            alias = alias,
                        )
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                ProviderLogger.info(
                    "Apple Music 元数据链路: " +
                        "seq=${host.nextMetadataTraceSequence()}, " +
                        "event=library_epoxy_rebuild, contentIds=$activeMediaIds, " +
                        "controller=${controller.javaClass.name}"
                )
                runtime.mainHandler.post {
                }
            }
        }.onFailure {
            ProviderLogger.error(
                "Apple Music 资料库 Epoxy 合并刷新失败: " +
                    "ids=$activeMediaIds, controller=${controller.javaClass.name}",
                it,
            )
        }
        synchronized(controllerRefreshStates) {
            controllerRefreshStates[controller]?.recordBuildAttempt(SystemClock.uptimeMillis())
        }
        if (BuildConfig.DEBUG) debugLibraryModelRefreshMediaId.remove()
    }
    val nextDispatch = synchronized(controllerRefreshStates) {
        val state = controllerRefreshStates[controller]
            ?: return@synchronized null
        val dispatch = state.finishDrain(
            strategy = buildStrategy,
            nowUptimeMillis = SystemClock.uptimeMillis(),
            albumDebounceMillis = AppleLibrarySurfaceHooks.ALBUM_CONTROLLER_REFRESH_DEBOUNCE_MS,
            playlistIntervalMillis = AppleLibrarySurfaceHooks.PLAYLIST_CONTROLLER_REFRESH_INTERVAL_MS,
        )
        if (
            !state.scheduled &&
            buildStrategy != InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD
        ) {
            controllerRefreshStates.remove(controller)
        }
        dispatch
    }
    if (nextDispatch != null) scheduleControllerRefresh(controller, nextDispatch)
}

internal fun AppleLibrarySurfaceHooks.registerComposeContent(
    fragment: Any,
    viewModelGetter: java.lang.reflect.Method,
    recentItemsMethod: String,
): List<String> {
    val state = composeStates[fragment]?.get() ?: return emptyList()
    val viewModel = runCatching { viewModelGetter.invoke(fragment) }.getOrNull()
        ?: return emptyList()
    val liveData = runCatching {
        AppleReflection.call(viewModel, recentItemsMethod)
    }.getOrNull() ?: return emptyList()
    val recentItems = runCatching {
        AppleReflection.call(liveData, "getValue") as? Iterable<*>
    }.getOrNull() ?: return emptyList()
    return buildList {
        recentItems.forEach { entity ->
            entity ?: return@forEach
            val mediaId = entityMediaId(entity) ?: return@forEach
            registerComposeState(mediaId, state)
            add(mediaId)
        }
    }.distinct()
}

internal fun AppleLibrarySurfaceHooks.scheduleVisibleResolution(
    fragment: Any,
    capturedMediaIds: Collection<String>,
    fallbackMediaIds: Collection<String>,
) {
    val state = composeStates[fragment]?.get() ?: return
    val mediaIds = composeVisibleMetadataResolutionIds(
        capturedMediaIds = capturedMediaIds,
        fallbackMediaIds = fallbackMediaIds,
        limit = AppleLibrarySurfaceHooks.MAX_VISIBLE_RESOLUTION_IDS,
    )
    if (mediaIds.isEmpty()) return
    mediaIds.forEach { mediaId -> registerComposeState(mediaId, state) }
    recordComposeBuildAliases(state, mediaIds)
    val shouldPost = synchronized(composeVisibleResolutionPending) {
        val pending = composeVisibleResolutionPending[state]
        if (pending != null) {
            pending.addAll(mediaIds)
            false
        } else {
            composeVisibleResolutionPending[state] = mediaIds.toCollection(linkedSetOf())
            true
        }
    }
    if (!shouldPost) return
    if (BuildConfig.DEBUG) {
        ProviderLogger.info(
            "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                "event=library_compose_visible_candidates, " +
                "source=${if (host.normalizeMediaIds(capturedMediaIds).isNotEmpty()) {
                    "render_capture"
                } else {
                    "first_items_fallback"
                }}, contentIds=$mediaIds"
        )
    }
    postVisibleResolution(state)
}

internal fun AppleLibrarySurfaceHooks.postVisibleResolution(state: Any) {
    runtime.mainHandler.post {
        Choreographer.getInstance().postFrameCallback {
            drainVisibleResolution(state)
        }
    }
}

internal fun AppleLibrarySurfaceHooks.drainVisibleResolution(state: Any) {
    val (mediaIds, hasMore) = synchronized(composeVisibleResolutionPending) {
        val pending = composeVisibleResolutionPending[state]
            ?: return@synchronized emptyList<String>() to false
        val batch = pending.take(AppleLibrarySurfaceHooks.MAX_VISIBLE_RESOLUTION_IDS)
        pending.removeAll(batch.toSet())
        val remaining = pending.isNotEmpty()
        if (!remaining) composeVisibleResolutionPending.remove(state)
        batch to remaining
    }
    resolveVisibleMediaIds(mediaIds)
    if (hasMore) postVisibleResolution(state)
}

internal fun AppleLibrarySurfaceHooks.resolveVisibleMediaIds(mediaIds: Collection<String>) {
    val normalizedIds = host.normalizeMediaIds(mediaIds)
    if (normalizedIds.isEmpty()) return
    val aliasesBeforeEnrichment = normalizedIds.associateWith(host::effectiveAlias)
    enrichEntitiesForResolution(normalizedIds)
    host.markMetadataVisible(normalizedIds)
    normalizedIds.forEach { mediaId ->
        val alias = host.effectiveAlias(mediaId) ?: return@forEach
        if (aliasesBeforeEnrichment[mediaId] != alias) {
            host.applyAliasToMetadataRefs(mediaId, alias)
        }
    }
    host.scheduleMetadataResolution(
        mediaIds = normalizedIds,
        priority = RequestPriority.VISIBLE,
    )
    if (BuildConfig.DEBUG) {
        ProviderLogger.info(
            "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                "event=library_compose_visible_request, contentIds=$normalizedIds, " +
                "afterFirstFrame=true"
        )
    }
}

internal fun AppleLibrarySurfaceHooks.registerComposeState(mediaId: String, state: Any) {
    val refs = composeStateRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
    var registered = false
    refs.forEach { ref ->
        val target = ref.get()
        if (target == null) refs.remove(ref) else if (target === state) registered = true
    }
    if (!registered) {
        refs.add(WeakReference(state))
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                    "event=library_compose_capture, contentId=$mediaId, " +
                    "state=${state.javaClass.name}"
            )
        }
    }
}

internal fun AppleLibrarySurfaceHooks.recordComposeBuildAliases(state: Any, mediaIds: Collection<String>) {
    val normalizedIds = host.normalizeMediaIds(mediaIds)
    synchronized(composeAppliedAliases) {
        val appliedAliases = composeAppliedAliases.getOrPut(state) { mutableMapOf() }
        normalizedIds.forEach { mediaId ->
            val alias = host.effectiveAlias(mediaId)
            if (alias == null) appliedAliases.remove(mediaId)
            else appliedAliases[mediaId] = AppliedMetadataAlias(mediaId, alias)
        }
        if (appliedAliases.isEmpty()) composeAppliedAliases.remove(state)
    }
}

