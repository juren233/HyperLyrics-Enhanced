/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.os.Bundle
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal interface AppleQueueMetadataHost {
    fun activePlaybackIdentity(): ActivePlaybackMediaIdentity

    fun logMetadataIdentity(
        event: String,
        identity: ActivePlaybackMediaIdentity,
        details: String,
    )

    fun media3MetadataId(metadata: Any, fallback: String?, trustedFallback: Boolean): String?

    fun media3MetadataDetails(metadata: Any): String

    fun registerMetadata(
        mediaId: String,
        metadata: Any,
        requestResolution: Boolean,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
    )

    fun markPlaybackItemHistory(playbackItem: Any)

    fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean,
        analyzeMetadata: Boolean,
    )

    fun contentItemMediaId(contentItem: Any, refresh: Boolean): String?

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias?

    fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean,
    )

    fun shouldRequestOverride(mediaId: String): Boolean

    fun ensureOverride(
        mediaId: String,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
    )

    fun ensureOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean,
        originalResolutionLimit: Int,
    )

    fun readPlaybackItemValue(
        playbackItem: Any,
        field: InAppPlaybackItemField,
        contract: InAppPlaybackItemContract,
    ): String?

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun isCurrentMetadataSurfaceMediaId(mediaId: String): Boolean

    fun hasLivePlaybackItem(mediaId: String): Boolean
}

internal class AppleQueueMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val host: AppleQueueMetadataHost,
) {
    @Volatile
    private var currentNowPlayingRefresh: InAppNowPlayingRefresh? = null

    @Volatile
    private var currentDispatcherRefresh: InAppMetadataDispatcherRefresh? = null

    @Volatile
    private var queueRefresh: InAppQueueRefresh? = null

    @Volatile
    private var historyRefresh: InAppQueueRefresh? = null

    private val queueAdapterRefs = ConcurrentLinkedQueue<WeakReference<RecyclerView.Adapter<*>>>()
    private val debugQueueBindTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private lateinit var queueAdapterTarget: AppleMusicHookTarget
    private lateinit var historyTarget: AppleMusicHookTarget

    fun installHooks() {
        val global = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER
        )
        val nowPlaying = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER
        )
        val queue = runtime.hookResolver.resolveMethod(AppleMusicHookPoint.IN_APP_QUEUE_UPDATE)
        val history = runtime.hookResolver.resolveMethod(AppleMusicHookPoint.IN_APP_HISTORY_UPDATE)
        val adapterSubmit = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT
        )
        val adapterBind = runtime.hookResolver.resolveMethod(
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND
        )
        queueAdapterTarget = adapterSubmit.target
        historyTarget = history.target

        installGlobalMetadataCapture(global)
        installNowPlayingMetadata(nowPlaying)
        installQueueAndHistory(queue, history)
        installQueueAdapter(adapterSubmit, adapterBind)
    }

    fun currentNowPlayingRefresh(): InAppNowPlayingRefresh? = currentNowPlayingRefresh

    fun currentDispatcherRefresh(): InAppMetadataDispatcherRefresh? = currentDispatcherRefresh

    fun isQueueAdapter(adapter: Any): Boolean = queueAdapterRefs.any { it.get() === adapter }

    fun hasLiveAdapter(): Boolean = queueAdapterRefs.any { it.get() != null }

    fun hasCapturedMediaId(mediaId: String): Boolean =
        queueRefresh?.mediaIds?.contains(mediaId) == true ||
            historyRefresh?.mediaIds?.contains(mediaId) == true

    fun refreshAdapters(mediaId: String): Int {
        if (!host.isCurrentMetadataSurfaceMediaId(mediaId)) return 0
        val isCaptured = hasCapturedMediaId(mediaId) || host.hasLivePlaybackItem(mediaId)
        if (!isCaptured) return 0
        var targets = 0
        queueAdapterRefs.forEach { ref ->
            val adapter = ref.get()
            if (adapter == null) {
                queueAdapterRefs.remove(ref)
                return@forEach
            }
            val itemCount = runCatching { adapter.itemCount }.getOrDefault(0)
            val matchingPositions = (0 until itemCount).filter { position ->
                val entry = queueEntryAt(adapter, position).entry
                registerQueueEntry(
                    entry = entry,
                    requestResolution = false,
                    preBind = true,
                ) == mediaId
            }
            if (matchingPositions.isEmpty()) return@forEach
            targets += 1
            runtime.mainHandler.post {
                if (!host.isCurrentMetadataSurfaceMediaId(mediaId)) return@post
                matchingPositions.forEach(adapter::notifyItemChanged)
            }
        }
        return targets
    }

    private fun installGlobalMetadataCapture(resolved: ResolvedAppleMusicHookMethod) {
        runtime.hookRegistrar.installHook(resolved.method, before = { chain ->
            val dispatcher = chain.thisObject ?: return@installHook
            val metadata = chain.args.firstOrNull() ?: return@installHook
            val identityBefore = host.activePlaybackIdentity()
            val mediaId = host.media3MetadataId(metadata, null, false)
            if (mediaId == null) {
                host.logMetadataIdentity(
                    event = "in_app_global_unresolved",
                    identity = identityBefore,
                    details = host.media3MetadataDetails(metadata),
                )
                return@installHook
            }
            currentDispatcherRefresh = InAppMetadataDispatcherRefresh(
                mediaId = mediaId,
                dispatcher = WeakReference(dispatcher),
                method = resolved.method,
                metadata = WeakReference(metadata),
            )
            host.logMetadataIdentity(
                event = "in_app_global_capture",
                identity = identityBefore,
                details = "resolvedId=$mediaId, ${host.media3MetadataDetails(metadata)}",
            )
            host.registerMetadata(
                mediaId = mediaId,
                metadata = metadata,
                requestResolution = true,
                preBind = false,
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            )
        })
        ProviderLogger.info(
            "Apple Music App 全局元数据捕获 Hook 已安装: " +
                "${resolved.target.className}#${resolved.target.methodName}, " +
                "fallback=${resolved.compatibilityFallback}"
        )
    }

    private fun installNowPlayingMetadata(resolved: ResolvedAppleMusicHookMethod) {
        runtime.hookRegistrar.installHook(resolved.method, before = { chain ->
            val listener = chain.thisObject ?: return@installHook
            val metadata = chain.args.firstOrNull() ?: return@installHook
            val identityBefore = host.activePlaybackIdentity()
            val mediaId = host.media3MetadataId(metadata, null, false)
            if (mediaId == null) {
                host.logMetadataIdentity(
                    event = "in_app_now_playing_unresolved",
                    identity = identityBefore,
                    details = host.media3MetadataDetails(metadata),
                )
                return@installHook
            }
            currentNowPlayingRefresh = InAppNowPlayingRefresh(
                mediaId = mediaId,
                listener = WeakReference(listener),
                method = resolved.method,
                metadata = WeakReference(metadata),
            )
            host.logMetadataIdentity(
                event = "in_app_now_playing_capture",
                identity = host.activePlaybackIdentity(),
                details = "resolvedId=$mediaId, ${host.media3MetadataDetails(metadata)}, " +
                    "aliasHit=${metadataStore.hasConfiguredMetadata(mediaId)}",
            )
            host.registerMetadata(
                mediaId = mediaId,
                metadata = metadata,
                requestResolution = true,
                preBind = false,
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            )
        })
        ProviderLogger.info(
            "Apple Music App 播放页元数据 Hook 已安装: " +
                "${resolved.target.className}#${resolved.target.methodName}, " +
                "fallback=${resolved.compatibilityFallback}"
        )
    }

    private fun installQueueAndHistory(
        queue: ResolvedAppleMusicHookMethod,
        history: ResolvedAppleMusicHookMethod,
    ) {
        runtime.hookRegistrar.installHook(queue.method, before = { chain ->
            chain.thisObject ?: return@installHook
            val items = chain.args.firstOrNull() as? Iterable<*> ?: return@installHook
            val mediaIds = registerQueueEntries(items, preBind = true)
            val changed = queueRefresh?.mediaIds != mediaIds
            if (changed) queueRefresh = InAppQueueRefresh(mediaIds)
            if (BuildConfig.DEBUG && changed) {
                ProviderLogger.info(
                    "Apple Music 继续播放捕获: ids=${mediaIds.size}, " +
                        "unresolved=${mediaIds.count { !metadataStore.hasConfiguredMetadata(it) }}"
                )
            }
        })
        runtime.hookRegistrar.installHook(history.method, before = { chain ->
            chain.thisObject ?: return@installHook
            val items = chain.args.firstOrNull() as? Iterable<*> ?: return@installHook
            val mediaIds = registerQueueEntries(
                items = items,
                preBind = true,
                maxEntries = MAX_QUEUE_LOCALIZED_PREFETCH_ENTRIES,
                originalResolutionLimit = MAX_QUEUE_PREBIND_ENTRIES,
                historyEntries = true,
            )
            val changed = historyRefresh?.mediaIds != mediaIds
            if (changed) historyRefresh = InAppQueueRefresh(mediaIds)
            if (BuildConfig.DEBUG && changed) {
                ProviderLogger.info(
                    "Apple Music 历史记录捕获: ids=${mediaIds.size}, " +
                        "unresolved=${mediaIds.count { !metadataStore.hasConfiguredMetadata(it) }}"
                )
            }
        })
        ProviderLogger.info(
            "Apple Music App 播放列表/历史记录元数据 Hook 已安装: " +
                "queue=${queue.target.className}#${queue.target.methodName}, " +
                "history=${history.target.className}#${history.target.methodName}"
        )
    }

    private fun installQueueAdapter(
        submit: ResolvedAppleMusicHookMethod,
        bind: ResolvedAppleMusicHookMethod,
    ) {
        runtime.hookRegistrar.installHook(submit.method, before = { chain ->
            val adapterObject = chain.thisObject ?: return@installHook
            (adapterObject as? RecyclerView.Adapter<*>)?.let(::registerQueueAdapter)
            val items = chain.args.firstOrNull() as? Iterable<*> ?: return@installHook
            registerQueueEntries(
                items = items,
                preBind = true,
                maxEntries = MAX_QUEUE_LOCALIZED_PREFETCH_ENTRIES,
                originalResolutionLimit = MAX_QUEUE_PREBIND_ENTRIES,
            )
            if (BuildConfig.DEBUG) {
                debugQueueBindTraceKeys.clear()
                items.take(MAX_DEBUG_QUEUE_SUBMIT_TRACE_ENTRIES).forEachIndexed {
                        position, entry ->
                    captureQueueEntry(
                        position = position,
                        entry = entry,
                        entrySource = "adapter_submit",
                        requestResolution = false,
                        preBind = true,
                    )
                }
            }
        })
        runtime.hookRegistrar.installHook(bind.method, before = { chain ->
            val adapter = chain.thisObject ?: return@installHook
            (adapter as? RecyclerView.Adapter<*>)?.let(::registerQueueAdapter)
            val position = chain.args.getOrNull(1) as? Int ?: return@installHook
            val lookup = queueEntryAt(adapter, position)
            captureQueueEntry(
                position = position,
                entry = lookup.entry,
                entrySource = lookup.source,
                requestResolution = true,
                preBind = true,
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            )
        })
        ProviderLogger.info(
            "Apple Music App 播放列表 Adapter 捕获 Hook 已安装: " +
                "submit=${submit.target.className}#${submit.target.methodName}, " +
                "bind=${bind.target.className}#${bind.target.methodName}"
        )
    }

    private fun registerQueueAdapter(adapter: RecyclerView.Adapter<*>) {
        var registered = false
        queueAdapterRefs.forEach { ref ->
            val target = ref.get()
            if (target == null) {
                queueAdapterRefs.remove(ref)
            } else if (target === adapter) {
                registered = true
            }
        }
        if (!registered) queueAdapterRefs.add(WeakReference(adapter))
    }

    private fun queueEntryAt(adapter: Any, position: Int): InAppQueueEntryLookup {
        val displayedEntry = runCatching {
            AppleReflection.call(
                adapter,
                queueAdapterTarget.runtimeMemberName(
                    AppleMusicRuntimeMember.QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD
                ),
                position,
            )
        }.getOrNull()
        if (displayedEntry != null) {
            return InAppQueueEntryLookup(displayedEntry, "displayed_list")
        }
        val submittedEntry = runCatching {
            (
                AppleReflection.field(
                    adapter,
                    queueAdapterTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.QUEUE_ADAPTER_SUBMITTED_ENTRIES_FIELD
                    ),
                ) as? List<*>
                )?.getOrNull(position)
        }.getOrNull()
        return InAppQueueEntryLookup(submittedEntry, "submitted_list_fallback")
    }

    private fun registerQueueEntries(
        items: Iterable<*>,
        preBind: Boolean = false,
        maxEntries: Int = Int.MAX_VALUE,
        originalResolutionLimit: Int = MAX_QUEUE_PREBIND_ENTRIES,
        historyEntries: Boolean = false,
    ): Set<String> = buildSet {
        val iterator = items.iterator()
        var processed = 0
        while (iterator.hasNext() && processed < maxEntries) {
            val entry = iterator.next()
            registerQueueEntry(
                entry = entry,
                requestResolution = false,
                preBind = preBind,
                historyEntry = historyEntries || isHistoryQueueEntry(entry),
            )?.let(::add)
            processed += 1
        }
        host.ensureOverrides(
            mediaIds = this,
            preBind = preBind,
            originalResolutionLimit = originalResolutionLimit,
        )
    }

    private fun isHistoryQueueEntry(entry: Any?): Boolean =
        entry != null && isInAppHistoryQueueEntryClassName(
            className = entry.javaClass.name,
            historyEntryClassName = historyTarget.runtimeMemberName(
                AppleMusicRuntimeMember.QUEUE_HISTORY_ENTRY_CLASS_NAME,
            ),
        )

    private fun registerQueueEntry(
        entry: Any?,
        requestResolution: Boolean = true,
        preBind: Boolean = false,
        historyEntry: Boolean = entry?.let(::isHistoryQueueEntry) == true,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
    ): String? {
        entry ?: return null
        val item = runCatching {
            AppleReflection.field(
                entry,
                queueAdapterTarget.runtimeMemberName(AppleMusicRuntimeMember.QUEUE_ENTRY_ITEM_FIELD),
            )
        }.getOrNull() ?: return null
        if (historyEntry) host.markPlaybackItemHistory(item)
        val metadata = runCatching {
            AppleReflection.field(
                item,
                queueAdapterTarget.runtimeMemberName(AppleMusicRuntimeMember.QUEUE_ITEM_METADATA_FIELD),
            )
        }.getOrNull()
        if (metadata != null) {
            val itemId = runCatching {
                AppleReflection.field(
                    item,
                    queueAdapterTarget.runtimeMemberName(AppleMusicRuntimeMember.QUEUE_ITEM_ID_FIELD),
                ) as? String
            }.getOrNull()
            val mediaId = host.media3MetadataId(metadata, itemId, true) ?: return null
            host.registerMetadata(
                mediaId = mediaId,
                metadata = metadata,
                requestResolution = requestResolution,
                preBind = preBind,
                priority = priority,
            )
            return mediaId
        }

        val mediaId = host.contentItemMediaId(item, historyEntry) ?: return null
        val existingAlias = host.effectiveAlias(mediaId)
        existingAlias?.let { alias ->
            host.registerPlaybackItem(
                mediaId = mediaId,
                playbackItem = item,
                notifyChange = false,
                analyzeMetadata = host.shouldRequestOverride(mediaId),
            )
            host.applyAliasToPlaybackItem(item, alias, notifyChange = !preBind)
            if (requestResolution && host.shouldRequestOverride(mediaId)) {
                host.ensureOverride(mediaId, preBind, priority)
            }
            return mediaId
        }
        host.registerPlaybackItem(
            mediaId = mediaId,
            playbackItem = item,
            notifyChange = !preBind,
            analyzeMetadata = true,
        )
        host.effectiveAlias(mediaId)?.let { alias ->
            host.applyAliasToPlaybackItem(item, alias, notifyChange = !preBind)
        }
        if (requestResolution && host.shouldRequestOverride(mediaId)) {
            host.ensureOverride(mediaId, preBind, priority)
        }
        return mediaId
    }

    private fun captureQueueEntry(
        position: Int,
        entry: Any?,
        entrySource: String,
        requestResolution: Boolean,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
    ): String? {
        val historyEntry = isHistoryQueueEntry(entry)
        val item = entry?.let {
            runCatching {
                AppleReflection.field(
                    it,
                    queueAdapterTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.QUEUE_ENTRY_ITEM_FIELD
                    ),
                )
            }.getOrNull()
        }
        val metadata = item?.takeUnless { historyEntry }?.let {
            runCatching {
                AppleReflection.field(
                    it,
                    queueAdapterTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.QUEUE_ITEM_METADATA_FIELD
                    ),
                )
            }.getOrNull()
        }
        val contract = if (historyEntry) {
            InAppPlaybackItemContract.HISTORY
        } else {
            InAppPlaybackItemContract.STANDARD
        }
        val itemId = when {
            item == null -> null
            historyEntry -> host.contentItemMediaId(item, true)
            else -> runCatching {
                AppleReflection.field(
                    item,
                    queueAdapterTarget.runtimeMemberName(AppleMusicRuntimeMember.QUEUE_ITEM_ID_FIELD),
                ) as? String
            }.getOrNull()
        }
        val bundleId = metadata?.let {
            runCatching {
                (AppleReflection.field(
                    it,
                    queueAdapterTarget.runtimeMemberName(
                        AppleMusicRuntimeMember.MEDIA3_METADATA_BUNDLE_FIELD
                    ),
                ) as? Bundle)?.getString(MEDIA3_METADATA_ID_KEY)
            }.getOrNull()
        }
        val rawTitle = playbackItemText(item, metadata, historyEntry, contract, title = true)
        val rawSubtitle = playbackItemText(item, metadata, historyEntry, contract, title = false)
        val mediaId = registerQueueEntry(
            entry = entry,
            requestResolution = requestResolution,
            preBind = preBind,
            historyEntry = historyEntry,
            priority = priority,
        )
        if (mediaId != null && priority == AppleInternalCatalogResolver.RequestPriority.VISIBLE) {
            host.markMetadataVisible(listOf(mediaId))
        }
        if (BuildConfig.DEBUG) {
            logQueueBind(
                position = position,
                entry = entry,
                entrySource = entrySource,
                item = item,
                metadata = metadata,
                mediaId = mediaId,
                itemId = itemId,
                bundleId = bundleId,
                rawTitle = rawTitle,
                rawSubtitle = rawSubtitle,
                finalTitle = playbackItemText(item, metadata, historyEntry, contract, title = true),
                finalSubtitle = playbackItemText(
                    item,
                    metadata,
                    historyEntry,
                    contract,
                    title = false,
                ),
                historyEntry = historyEntry,
                resolutionRequested = requestResolution,
            )
        }
        return mediaId
    }

    private fun playbackItemText(
        item: Any?,
        metadata: Any?,
        historyEntry: Boolean,
        contract: InAppPlaybackItemContract,
        title: Boolean,
    ): String? = when {
        item == null -> null
        historyEntry -> host.readPlaybackItemValue(
            item,
            if (title) InAppPlaybackItemField.TITLE else InAppPlaybackItemField.ARTIST,
            contract,
        )
        else -> metadata?.let {
            runCatching {
                AppleReflection.field(
                    it,
                    queueAdapterTarget.runtimeMemberName(
                        if (title) {
                            AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD
                        } else {
                            AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD
                        }
                    ),
                )
            }.getOrNull()?.toString()
        }
    }

    private fun logQueueBind(
        position: Int,
        entry: Any?,
        entrySource: String,
        item: Any?,
        metadata: Any?,
        mediaId: String?,
        itemId: String?,
        bundleId: String?,
        rawTitle: String?,
        rawSubtitle: String?,
        finalTitle: String?,
        finalSubtitle: String?,
        historyEntry: Boolean,
        resolutionRequested: Boolean,
    ) {
        val event = if (historyEntry) "history_bind" else "queue_bind"
        val traceKey =
            "$event:$entrySource:$position:$mediaId:$rawTitle:$rawSubtitle:$finalTitle:$finalSubtitle"
        if (
            traceKey !in debugQueueBindTraceKeys &&
            debugQueueBindTraceKeys.size >= MAX_DEBUG_QUEUE_BIND_TRACE_KEYS
        ) return
        if (!debugQueueBindTraceKeys.add(traceKey)) return
        val alias = mediaId?.let(host::effectiveAlias)
        val shouldRequest = mediaId?.let(host::shouldRequestOverride)
        ProviderLogger.info(
            "Apple Music 队列绑定: event=$event, source=$entrySource, position=$position, " +
                "entryClass=${entry?.javaClass?.name}, itemClass=${item?.javaClass?.name}, " +
                "metadataClass=${metadata?.javaClass?.name}, itemId=$itemId, bundleId=$bundleId, " +
                "registeredId=$mediaId, rawTitle=$rawTitle, rawSubtitle=$rawSubtitle, " +
                "alias=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                "resolutionRequested=$resolutionRequested, shouldRequest=$shouldRequest, " +
                "originalResolved=${mediaId != null && metadataStore.isOriginalResolved(mediaId)}, " +
                "originalPending=${mediaId != null && metadataStore.isOriginalPending(mediaId)}, " +
                "localizedResolved=${mediaId != null &&
                    metadataStore.hasConfiguredMetadata(mediaId)}, " +
                "writeTarget=${metadata?.javaClass?.name ?: item?.javaClass?.name}, " +
                "finalTitle=$finalTitle, finalSubtitle=$finalSubtitle"
        )
    }

    private companion object {
        const val MAX_QUEUE_PREBIND_ENTRIES = 24
        const val MAX_QUEUE_LOCALIZED_PREFETCH_ENTRIES = 128
        const val MAX_DEBUG_QUEUE_BIND_TRACE_KEYS = 512
        const val MAX_DEBUG_QUEUE_SUBMIT_TRACE_ENTRIES = 64
        const val MEDIA3_METADATA_ID_KEY = Constants.APPLE_MEDIA3_METADATA_ID_KEY
    }
}
