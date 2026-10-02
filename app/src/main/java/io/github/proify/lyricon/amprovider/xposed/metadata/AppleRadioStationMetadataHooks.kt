/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Station IDs stay separate from artist IDs and from the numeric song/album override store. */
internal class AppleRadioStationMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleArtistSurfaceHost,
    private val metadataStore: AppleMetadataOverrideStore,
    private val bindings: AppleDataBindingMetadataHooks,
    private val catalog: AppleInternalCatalogResolver,
    private val originalEnabled: () -> Boolean,
    private val invalidate: (String, String) -> Unit,
) {
    private class Station(val id: String, val nativeTitle: String, val generation: Long) {
        val key get() = "station:$id"
        @Volatile var configured: String? = null
        @Volatile var original: String? = null
        var identity: AppleStationArtistCandidate? = null
        var requestedLanguage: String? = null
        var nextLookupAt = 0L
        var nextOriginalAt = 0L
        var pending = false
        fun title() = stationDisplayTitle(nativeTitle, original, configured)
    }
    private data class Bound(val station: Station, val generation: Long)
    private data class Work(val station: Station, val run: (() -> Unit) -> Unit)
    private val stations = LinkedHashMap<String, Station>(16, 0.75f, true)
    private val entities = WeakIdentityMap<Any, Station>()
    private val bound = WeakHashMap<Any, Bound>()
    private val queue = ArrayDeque<Work>()
    private var running = false
    @Volatile private var generation = 0L
    private val lookup = AppleStationCatalogLookup(runtime, catalog)
    private var stationClass: Class<*>? = null
    private var entityId: Method? = null

    fun install() {
        val resolver = runtime.hookResolver
        stationClass = resolver.resolveClass(AppleMusicHookPoint.RADIO_STATION_CLASS).clazz
        entityId = resolver.resolveMethod(AppleMusicHookPoint.ALBUM_COMPOSE_ENTITY_ID).method
        // A search failure must not disable configured-region station titles or ordinary cards.
        runCatching { lookup.install() }.onFailure { ProviderLogger.error("Apple Music 电台身份查询 Hook 安装失败", it) }
        runtime.hookRegistrar.installResultOverrideHook(resolver.resolveMethod(AppleMusicHookPoint.ARTIST_COMPOSE_ENTITY_TITLE).method) { chain, original ->
            val station = chain.thisObject?.let { entities[it] }
            if (station != null && station.generation == generation) station.title() else original
        }
    }

    fun observe(entity: Any): String? {
        if (stationClass?.isInstance(entity) != true) return null
        val id = entityId?.invoke(entity) as? String ?: return null
        val attributes = host.mediaApiEntityAttributes(entity) ?: return null
        val title = host.mediaApiAttribute(attributes, AppleMediaApiTextAttribute.NAME)?.takeIf(String::isNotBlank) ?: return null
        val station = stations.getOrPut(id) { Station(id, title, generation) }
        entities[entity] = station
        while (stations.size > 128) stations.remove(stations.keys.first())
        if (!station.pending && station.identity == null && SystemClock.uptimeMillis() >= station.nextLookupAt &&
            (originalEnabled() || storefrontForContentUiLanguage(catalog.contentUiLanguageSelection) != null)) {
            enqueue(station) { done ->
                val selection = catalog.contentUiLanguageSelection
                lookup.lookup(station.id, station.nativeTitle, selection, originalEnabled(), { current(station) }) { result ->
                    if (current(station)) {
                        station.configured = result.configuredTitle
                        station.identity = result.identity
                        station.nextLookupAt = SystemClock.uptimeMillis() + 60_000L
                        val artist = result.artist
                        val artistId = result.identity?.artistId
                        if (artist != null && artistId != null) {
                            host.registerLibraryEntity(artistId, artist, InAppLibraryEntityKind.ARTIST)
                            host.enrichLibraryEntitiesForResolution(listOf(artistId))
                            host.markMetadataVisible(listOf(artistId))
                            host.scheduleMetadataResolution(listOf(artistId), RequestPriority.VISIBLE, InAppOriginalResolutionMode.ORIGINAL_FIRST)
                        }
                        publish(station)
                        diagnostic("station_identity") { "stationId=${station.id}, artistId=$artistId, verified=${artistId != null}" }
                    }
                    done()
                    if (current(station)) updateOriginal(station)
                }
            }
        } else if (station.identity != null) updateOriginal(station)
        return station.key
    }

    fun display(key: String): String? = stations[key.removePrefix("station:")]?.takeIf(::current)?.title()

    /** Called on every bound callback, including recycled bindings that now show a non-station. */
    fun bind(entity: Any, binding: Any) {
        bound.remove(binding)
        val key = observe(entity) ?: return
        val station = stations[key.removePrefix("station:")] ?: return
        bindings.capture(binding)
        bound[binding] = Bound(station, bindings.generation(binding))
        postBinding(binding, station)
    }

    fun artistChanged(id: String) {
        stations.values.toList().filter { it.identity?.artistId == id }.forEach(::updateOriginal)
    }

    private fun updateOriginal(station: Station) {
        if (!current(station) || !originalEnabled()) return
        val identity = station.identity ?: return
        // A configured alias is never an original-language witness.
        val original = metadataStore.originalMetadata(identity.artistId)
            ?.takeIf { metadataStore.isOriginalMetadataConfirmed(identity.artistId) }
            ?: metadataStore.sharedOriginalArtist(identity.artistId)
            ?: return
        val direct = stationOriginalTitle(station.nativeTitle, identity.artistName, original)
        if (direct != null) {
            if (station.original != direct) { station.original = direct; publish(station) }
            return
        }
        val language = supportedOriginalLanguageOrNull(original.language) ?: return
        val storefront = storefrontForOriginalLanguage(language) ?: return
        if (station.pending || station.requestedLanguage == language || SystemClock.uptimeMillis() < station.nextOriginalAt) return
        enqueue(station) { done ->
            lookup.stationTitle(station.id, storefront, language) { title ->
                if (current(station) && originalEnabled() && title != null) {
                    station.original = title
                    station.requestedLanguage = language
                    publish(station)
                }
                station.nextOriginalAt = SystemClock.uptimeMillis() + 60_000L
                done()
            }
        }
    }

    private fun current(station: Station): Boolean = station.generation == generation && stations[station.id] === station

    private fun enqueue(station: Station, run: (() -> Unit) -> Unit) {
        if (station.pending || queue.size >= 32) return
        station.pending = true
        queue += Work(station, run)
        runtime.mainHandler.post(::drain)
    }

    private fun drain() {
        if (running) return
        while (queue.isNotEmpty()) {
            val work = queue.removeFirst()
            if (!current(work.station)) { work.station.pending = false; continue }
            running = true
            var finished = false
            val done = {
                if (!finished) {
                    finished = true
                    work.station.pending = false
                    running = false
                    runtime.mainHandler.post(::drain)
                }
            }
            runCatching { work.run(done) }.onFailure {
                work.station.nextLookupAt = SystemClock.uptimeMillis() + 60_000L
                done()
                ProviderLogger.error("Apple Music 电台名称解析失败", it)
            }
            return
        }
    }

    private fun publish(station: Station) {
        if (!current(station)) return
        invalidate(station.key, station.title())
        bound.entries.toList().filter { it.value.station === station }.forEach { postBinding(it.key, station) }
        diagnostic("station_title") { "stationId=${station.id}, original=${station.original}, configured=${station.configured}, selected=${station.title()}" }
    }

    private fun postBinding(binding: Any, station: Station) {
        val weak = WeakReference(binding)
        val expected = bound[binding] ?: return
        runtime.mainHandler.post {
            val currentBinding = weak.get() ?: return@post
            if (!current(station) || bound[currentBinding] !== expected || bindings.generation(currentBinding) != expected.generation) return@post
            runCatching {
                // Only title belongs to the artist station; retain the native "Radio Station" subtitle.
                val applied = bindings.applyAliasVariables(currentBinding, DataBindingAliasValues(station.title(), null))
                if (applied.titleApplied) bindings.executePending(currentBinding)
                diagnostic("station_binding") { "stationId=${station.id}, titleApplied=${applied.titleApplied}, text=${station.title()}" }
            }.onFailure { ProviderLogger.error("Apple Music 电台卡片名称回绑失败", it) }
        }
    }

    fun clear() {
        generation++
        val previousBindings = bound.entries.toList()
        bound.clear()
        entities.clear()
        stations.clear()
        queue.clear()
        lookup.clear()
        previousBindings.forEach { (binding, ref) ->
            if (bindings.generation(binding) == ref.generation) runCatching {
                bindings.applyAliasVariables(binding, DataBindingAliasValues(ref.station.nativeTitle, null))
                bindings.executePending(binding)
            }
        }
    }

    private inline fun diagnostic(event: String, detail: () -> String) {
        if (BuildConfig.DEBUG) host.logMetadataIdentity(event, detail())
    }
}

internal fun stationDisplayTitle(native: String, original: String?, configured: String?): String =
    original?.takeIf(String::isNotBlank) ?: configured?.takeIf(String::isNotBlank) ?: native
