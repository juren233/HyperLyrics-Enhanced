/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Owns weak App-internal metadata model references and their identity contracts. */
internal class AppleInAppMetadataRegistry {
    private val metadataRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<InAppMetadataRef>>()
    private val metadataIds = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val playbackItemRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<InAppPlaybackItemRef>>()
    private val playbackItemIds = WeakIdentityMap<Any, String>()
    private val playbackItemContracts = WeakIdentityMap<Any, InAppPlaybackItemContract>()
    private val containerItemRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<InAppContainerItemRef>>()
    private val containerItemIds = WeakIdentityMap<Any, String>()
    private val containerNavigationRefs = ConcurrentLinkedQueue<InAppContainerNavigationRef>()

    fun registerMetadata(
        mediaId: String,
        metadata: Any,
        originalTitle: Any?,
        originalArtist: Any?,
    ): Boolean {
        val alreadyRegistered = synchronized(metadataIds) {
            if (metadataIds[metadata] == mediaId) {
                true
            } else {
                metadataIds[metadata] = mediaId
                false
            }
        }
        if (!alreadyRegistered) {
            metadataRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }.add(
                InAppMetadataRef(
                    metadata = WeakReference(metadata),
                    originalTitle = originalTitle,
                    originalArtist = originalArtist,
                )
            )
        }
        return !alreadyRegistered
    }

    fun metadataId(metadata: Any): String? = synchronized(metadataIds) {
        metadataIds[metadata]
    }

    fun liveMetadataRefs(mediaId: String): List<InAppMetadataRef> {
        val refs = metadataRefs[mediaId] ?: return emptyList()
        return refs.mapNotNull { ref ->
            val metadata = ref.metadata.get()
            if (metadata == null || metadataId(metadata) != mediaId) {
                refs.remove(ref)
                null
            } else {
                ref
            }
        }
    }

    fun allLiveMetadataRefs(): List<InAppMetadataRef> =
        metadataRefs.keys.flatMap(::liveMetadataRefs)

    fun markPlaybackItemContract(
        playbackItem: Any,
        contract: InAppPlaybackItemContract,
    ) {
        playbackItemContracts[playbackItem] = contract
    }

    fun playbackItemContract(playbackItem: Any): InAppPlaybackItemContract =
        playbackItemContracts[playbackItem] ?: InAppPlaybackItemContract.STANDARD

    fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        originalTitle: Any?,
        originalArtist: Any?,
        originalCollectionName: String?,
        contract: InAppPlaybackItemContract,
    ): Boolean {
        val alreadyRegistered = synchronized(playbackItemIds) {
            if (playbackItemIds[playbackItem] == mediaId) {
                true
            } else {
                playbackItemIds[playbackItem] = mediaId
                false
            }
        }
        if (!alreadyRegistered) {
            playbackItemRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }.add(
                InAppPlaybackItemRef(
                    playbackItem = WeakReference(playbackItem),
                    originalTitle = originalTitle,
                    originalArtist = originalArtist,
                    originalCollectionName = originalCollectionName,
                    contract = contract,
                )
            )
        }
        return !alreadyRegistered
    }

    fun playbackItemId(playbackItem: Any): String? = synchronized(playbackItemIds) {
        playbackItemIds[playbackItem]
    }

    fun livePlaybackItemRefs(mediaId: String): List<InAppPlaybackItemRef> {
        val refs = playbackItemRefs[mediaId] ?: return emptyList()
        return refs.mapNotNull { ref ->
            val playbackItem = ref.playbackItem.get()
            if (playbackItem == null || playbackItemId(playbackItem) != mediaId) {
                refs.remove(ref)
                null
            } else {
                ref
            }
        }
    }

    fun livePlaybackItems(mediaId: String): List<Any> =
        livePlaybackItemRefs(mediaId).mapNotNull { it.playbackItem.get() }

    fun allLivePlaybackItemRefs(): Map<String, List<InAppPlaybackItemRef>> =
        playbackItemRefs.keys.associateWith(::livePlaybackItemRefs)

    fun hasLivePlaybackItem(mediaId: String): Boolean =
        livePlaybackItemRefs(mediaId).isNotEmpty()

    fun registerContainerItem(
        mediaId: String,
        containerItem: Any,
        kind: InAppContainerKind,
        originalTitle: String?,
    ): Boolean {
        containerItemIds[containerItem] = mediaId
        val refs = containerItemRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
        val alreadyRegistered = refs.any { ref ->
            val target = ref.containerItem.get()
            if (target == null) {
                refs.remove(ref)
                false
            } else {
                target === containerItem
            }
        }
        if (!alreadyRegistered) {
            refs.add(
                InAppContainerItemRef(
                    containerItem = WeakReference(containerItem),
                    kind = kind,
                    originalTitle = originalTitle,
                )
            )
        }
        return !alreadyRegistered
    }

    fun liveContainerItemRefs(mediaId: String): List<InAppContainerItemRef> {
        val refs = containerItemRefs[mediaId] ?: return emptyList()
        return refs.mapNotNull { ref ->
            val containerItem = ref.containerItem.get()
            if (containerItem == null || containerItemIds[containerItem] != mediaId) {
                refs.remove(ref)
                null
            } else {
                ref
            }
        }
    }

    fun allLiveContainerItemRefs(): List<InAppContainerItemRef> =
        containerItemRefs.keys.flatMap(::liveContainerItemRefs)

    fun markContainerNavigationItem(
        containerItem: Any,
        kind: InAppContainerKind,
        mediaId: String,
    ) {
        var registered = false
        containerNavigationRefs.forEach { ref ->
            val target = ref.containerItem.get()
            if (target == null) {
                containerNavigationRefs.remove(ref)
            } else if (target === containerItem) {
                if (ref.kind == kind && ref.mediaId == mediaId) {
                    registered = true
                } else {
                    containerNavigationRefs.remove(ref)
                }
            }
        }
        if (!registered) {
            containerNavigationRefs.add(
                InAppContainerNavigationRef(
                    containerItem = WeakReference(containerItem),
                    kind = kind,
                    mediaId = mediaId,
                )
            )
        }
    }

    fun containerNavigationBinding(containerItem: Any): InAppContainerNavigationRef? {
        containerNavigationRefs.forEach { ref ->
            val target = ref.containerItem.get()
            if (target == null) {
                containerNavigationRefs.remove(ref)
            } else if (target === containerItem) {
                return ref
            }
        }
        return null
    }

    fun hasLiveModelTarget(mediaId: String): Boolean =
        liveMetadataRefs(mediaId).isNotEmpty() ||
            livePlaybackItemRefs(mediaId).isNotEmpty() ||
            liveContainerItemRefs(mediaId).isNotEmpty()

    fun refCounts(mediaId: String): Triple<Int, Int, Int> = Triple(
        liveMetadataRefs(mediaId).size,
        livePlaybackItemRefs(mediaId).size,
        liveContainerItemRefs(mediaId).size,
    )
}
