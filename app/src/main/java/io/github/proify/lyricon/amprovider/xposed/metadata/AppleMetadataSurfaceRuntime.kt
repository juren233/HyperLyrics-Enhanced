/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Activity
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

internal interface AppleMetadataSurfaceHost {
    fun catalogResolver(): AppleInternalCatalogResolver?

    fun associatedArtistIds(mediaId: String): Collection<String>

    fun hasVisibleExactConsumer(mediaId: String): Boolean

    fun hasGenericRecyclerConsumer(mediaId: String): Boolean

    fun detachController(owner: Any): Int

    fun logMetadataIdentity(event: String, details: String)

    fun describeView(view: View): String
}

internal class AppleMetadataSurfaceRuntime(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleMetadataSurfaceHost,
) {
    private val coordinator = AppleMetadataSurfaceCoordinator(
        clock = SystemClock::elapsedRealtime,
    )
    private val visibleResolutionLeases = AppleVisibleMetadataResolutionLeases(
        clock = SystemClock::elapsedRealtime,
    )
    private val syncLock = Any()
    private val dispatchRevision = AtomicLong(0L)
    private var lastSyncedSignature: MetadataSurfaceSignature? = null

    @Volatile
    private var activePageOwner = WeakReference<Any>(null)

    fun installLifecycleHooks() {
        val activityInstalled = runCatching {
            val activityResume = Activity::class.java.getDeclaredMethod("onResume")
                .apply { isAccessible = true }
            val activityPause = Activity::class.java.getDeclaredMethod("onPause")
                .apply { isAccessible = true }
            runtime.hookRegistrar.installHook(activityResume, after = { chain, _ ->
                chain.thisObject?.let(::onSurfaceResumed)
            })
            runtime.hookRegistrar.installHook(activityPause, before = { chain ->
                chain.thisObject?.let(::onSurfacePaused)
            })
        }.onFailure {
            ProviderLogger.error("Apple Music Activity 元数据生命周期 Hook 安装失败", it)
        }.isSuccess
        val fragmentInstalled = runCatching {
            val fragmentClass = runtime.classLoader.loadClass("androidx.fragment.app.Fragment")
            val fragmentResume = AppleReflection.findMethod(
                fragmentClass,
                "onResume",
                parameterCount = 0,
            )
            val fragmentPause = AppleReflection.findMethod(
                fragmentClass,
                "onPause",
                parameterCount = 0,
            )
            runtime.hookRegistrar.installHook(fragmentResume, after = { chain, _ ->
                chain.thisObject?.let(::onSurfaceResumed)
            })
            runtime.hookRegistrar.installHook(fragmentPause, before = { chain ->
                chain.thisObject?.let(::onSurfacePaused)
            })
        }.onFailure {
            ProviderLogger.info(
                "Apple Music 未提供标准 Fragment 生命周期类，改用页面控制器边界"
            )
        }.isSuccess
        ProviderLogger.info(
            "Apple Music 元数据页面生命周期 Hook 已安装: " +
                "activity=$activityInstalled, fragment=$fragmentInstalled"
        )
    }

    fun activePageOwner(): Any? = activePageOwner.get()

    fun onPageAttached(owner: Any, recycler: RecyclerView) {
        activePageOwner = WeakReference(owner)
        onSurfaceResumed(owner)
        if (BuildConfig.DEBUG) {
            host.logMetadataIdentity(
                event = "metadata_page_controller_attached",
                details = "controller=${owner.javaClass.name}@${System.identityHashCode(owner)}, " +
                    "recycler=${host.describeView(recycler)}",
            )
        }
    }

    fun onPageDetached(owner: Any) {
        val removedControllerRefs = host.detachController(owner)
        if (BuildConfig.DEBUG) {
            host.logMetadataIdentity(
                event = "metadata_page_controller_detached",
                details = "controller=${owner.javaClass.name}@${System.identityHashCode(owner)}, " +
                    "removedControllerRefs=$removedControllerRefs",
            )
        }
        if (activePageOwner.get() === owner) activePageOwner = WeakReference(null)
        onSurfacePaused(owner)
    }

    fun markVisible(
        mediaIds: Collection<String>,
    ): AppleMetadataSurfaceCoordinator.SurfaceSnapshot {
        visibleResolutionLeases.mark(mediaIds)
        coordinator.markCurrentPage(mediaIds)
        val snapshot = coordinator.markVisible(mediaIds)
        syncRequestScope(snapshot)
        return snapshot
    }

    fun setPlaybackMediaId(
        mediaId: String?,
    ): AppleMetadataSurfaceCoordinator.SurfaceSnapshot {
        val snapshot = coordinator.setPlaybackMediaId(mediaId)
        syncRequestScope(snapshot)
        return snapshot
    }

    fun requestContext(mediaId: String): AppleMetadataSurfaceCoordinator.RequestContext =
        coordinator.requestContext(mediaId)

    fun isCurrentMediaId(mediaId: String): Boolean =
        requestContext(mediaId).priority !=
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND

    fun hasVisibleConsumer(mediaId: String): Boolean =
        host.hasVisibleExactConsumer(mediaId) || host.hasGenericRecyclerConsumer(mediaId)

    fun isRefreshable(mediaId: String): Boolean = shouldRefreshInAppSurface(
        surfaceRelevant = isCurrentMediaId(mediaId),
        hasVisibleExactConsumer = hasVisibleConsumer(mediaId),
        hasActiveVisibleLease = visibleResolutionLeases.contains(mediaId),
    )

    fun hasVisibleResolutionLease(mediaId: String): Boolean =
        visibleResolutionLeases.contains(mediaId)

    fun refreshRequestScope() {
        syncRequestScope()
    }

    private fun onSurfaceResumed(owner: Any) {
        syncRequestScope(coordinator.onSurfaceResumed(owner))
    }

    private fun onSurfacePaused(owner: Any) {
        syncRequestScope(coordinator.onSurfacePaused(owner))
    }

    private fun syncRequestScope(
        snapshot: AppleMetadataSurfaceCoordinator.SurfaceSnapshot = coordinator.snapshot(),
    ) {
        val resolver = host.catalogResolver() ?: return
        val visible = expandScopeIds(snapshot.visibleMediaIds)
        val activePage = expandScopeIds(snapshot.activePageMediaIds) - visible
        val signature = MetadataSurfaceSignature(
            coordinatorRevision = snapshot.scopeRevision,
            visibleMediaIds = visible,
            activePageMediaIds = activePage,
        )
        val revision = synchronized(syncLock) {
            if (signature == lastSyncedSignature) return
            lastSyncedSignature = signature
            dispatchRevision.incrementAndGet()
        }
        resolver.updateRequestScope(
            revision = revision,
            visibleMediaIds = visible,
            activePageMediaIds = activePage,
        )
    }

    private fun expandScopeIds(mediaIds: Collection<String>): Set<String> = buildSet {
        mediaIds.forEach { mediaId ->
            add(mediaId)
            addAll(host.associatedArtistIds(mediaId))
        }
    }
}
