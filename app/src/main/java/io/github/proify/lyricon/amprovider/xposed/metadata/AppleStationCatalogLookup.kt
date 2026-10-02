/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap

internal data class AppleStationCatalogResult(
    val configuredTitle: String?,
    val artist: Any?,
    val identity: AppleStationArtistCandidate?,
)

/** Uses a private native search session; never changes the user's search/history/selection. */
internal class AppleStationCatalogLookup(
    private val runtime: AppleMusicProviderRuntime,
    private val catalog: AppleInternalCatalogResolver,
) {
    private data class Search(val complete: (List<Any>) -> Unit, val timeout: Runnable)
    private val searches = Collections.synchronizedMap(IdentityHashMap<Any, Search>())
    private lateinit var constructor: Constructor<*>
    private lateinit var start: Method
    private lateinit var cancel: Method
    private lateinit var scopeClass: Class<*>
    private lateinit var scopeContextName: String
    private lateinit var sessionKind: Any
    private lateinit var catalogKind: Any
    private lateinit var artistKind: Any

    fun install() {
        val resolver = runtime.hookResolver
        fun method(point: AppleMusicHookPoint) = resolver.resolveMethod(point).method
        val session = resolver.resolveClass(AppleMusicHookPoint.RADIO_SEARCH_SESSION)
        fun member(key: AppleMusicRuntimeMember) = session.target.runtimeMemberName(key)
        fun type(key: AppleMusicRuntimeMember) = runtime.classLoader.loadClass(member(key))
        val sessionType = type(AppleMusicRuntimeMember.RADIO_SEARCH_SESSION_KIND_CLASS)
        scopeClass = type(AppleMusicRuntimeMember.RADIO_SEARCH_SCOPE_CLASS)
        scopeContextName = member(AppleMusicRuntimeMember.RADIO_SEARCH_SCOPE_CONTEXT_METHOD)
        constructor = session.clazz.getDeclaredConstructor(
            sessionType, type(AppleMusicRuntimeMember.RADIO_SEARCH_MEDIA_API_CLASS), scopeClass,
        ).apply { isAccessible = true }
        start = method(AppleMusicHookPoint.RADIO_SEARCH_START)
        cancel = method(AppleMusicHookPoint.RADIO_SEARCH_CANCEL)
        sessionKind = requireNotNull(sessionType.getField(member(AppleMusicRuntimeMember.RADIO_SEARCH_SESSION_KIND_FIELD)).get(null))
        artistKind = requireNotNull(start.parameterTypes[1].getField(member(AppleMusicRuntimeMember.RADIO_SEARCH_ARTISTS_KIND_FIELD)).get(null))
        catalogKind = requireNotNull(start.parameterTypes[2].getField(member(AppleMusicRuntimeMember.RADIO_SEARCH_CATALOG_KIND_FIELD)).get(null))
        val results = method(AppleMusicHookPoint.RADIO_SEARCH_RESPONSE_RESULTS)
        val artists = method(AppleMusicHookPoint.RADIO_SEARCH_ARTISTS)
        val data = method(AppleMusicHookPoint.RADIO_SEARCH_ENTITIES)
        runtime.hookRegistrar.installHook(method(AppleMusicHookPoint.RADIO_SEARCH_RESULT), after = { chain, _ ->
            val owner = chain.thisObject ?: return@installHook
            val request = searches.remove(owner) ?: return@installHook
            runtime.mainHandler.removeCallbacks(request.timeout)
            val entities = runCatching {
                chain.args.firstOrNull()?.let { results.invoke(it) }?.let { artists.invoke(it) }
                    ?.let { data.invoke(it) as? List<*> }?.filterNotNull().orEmpty()
            }.getOrDefault(emptyList())
            runtime.mainHandler.post {
                runCatching { cancel.invoke(owner) }
                request.complete(entities)
            }
        })
    }

    fun lookup(stationId: String, title: String, selection: Int, original: Boolean, active: () -> Boolean, complete: (AppleStationCatalogResult) -> Unit) {
        fun identify(configured: String?) {
            if (!original || !active()) {
                complete(AppleStationCatalogResult(configured, null, null))
                return
            }
            searchArtists(title) { candidates ->
                if (!active()) { complete(AppleStationCatalogResult(null, null, null)); return@searchArtists }
                val ids = candidates.mapNotNull(::id).filter { it.all(Char::isDigit) }.distinct().take(5)
                if (ids.isEmpty()) {
                    complete(AppleStationCatalogResult(configured, null, null))
                    return@searchArtists
                }
                // Search rank and matching names are insufficient. Fetch the exact station relation.
                catalog.queryResponse(
                    storefrontForContentUiLanguage(selection), languageTagForContentUiLanguage(selection),
                    "station-artist-proof=$stationId", "artists",
                    mapOf("ids" to ids.joinToString(","), "include" to "station", "platform" to "android"),
                ) { response ->
                    val entities = responseEntities(response)
                    val proofs = entities.mapNotNull { artist ->
                        val artistId = id(artist) ?: return@mapNotNull null
                        if (artistId !in ids) return@mapNotNull null
                        AppleStationArtistCandidate(artistId, name(artist).orEmpty(), relationIds(artist, "station"))
                    }
                    val proof = verifiedStationArtist(stationId, proofs)
                    complete(AppleStationCatalogResult(configured, entities.firstOrNull { id(it) == proof?.artistId }, proof))
                }
            }
        }
        val storefront = storefrontForContentUiLanguage(selection)
        val language = languageTagForContentUiLanguage(selection)
        if (storefront == null || language == null) identify(null)
        else stationTitle(stationId, storefront, language) { identify(it) }
    }

    fun stationTitle(id: String, storefront: String, language: String, complete: (String?) -> Unit) {
        catalog.queryResponse(storefront, language, "station-title=$id", "stations", mapOf("ids" to id, "l" to language, "platform" to "android")) { response ->
            complete(responseEntities(response).singleOrNull { this.id(it) == id }?.let(::name))
        }
    }

    private fun searchArtists(title: String, complete: (List<Any>) -> Unit) {
        if (!::constructor.isInitialized) { complete(emptyList()); return }
        var session: Any? = null
        runCatching {
            val access = catalog.catalogAccess ?: catalog.createCatalogAccess().also { catalog.catalogAccess = it }
            val scope = Proxy.newProxyInstance(runtime.classLoader, arrayOf(scopeClass)) { proxy, method, args ->
                when (method.name) {
                    scopeContextName -> access.emptyCoroutineContext
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "AppleStationSearchScope"
                    else -> null
                }
            }
            val owner = constructor.newInstance(sessionKind, access.mediaApi, scope).also { session = it }
            val timeout = Runnable {
                searches.remove(owner)?.let {
                    runCatching { cancel.invoke(owner) }
                    it.complete(emptyList())
                }
            }
            searches[owner] = Search(complete, timeout)
            runtime.mainHandler.postDelayed(timeout, QUERY_TIMEOUT_MS)
            start.invoke(owner, title, artistKind, catalogKind, linkedMapOf("types" to "artists", "limit" to "5", "include[artists]" to "station"))
        }.onFailure {
            session?.let { owner ->
                searches.remove(owner)?.let { runtime.mainHandler.removeCallbacks(it.timeout) }
                runCatching { cancel.invoke(owner) }
            }
            ProviderLogger.error("Apple Music 电台歌手身份查询失败", it)
            complete(emptyList())
        }
    }

    fun clear() {
        val pending = synchronized(searches) { searches.toMap().also { searches.clear() } }
        pending.forEach { (owner, request) ->
            runtime.mainHandler.removeCallbacks(request.timeout)
            runCatching { cancel.invoke(owner) }
            // Complete the serial request so a settings change cannot strand the queue.
            request.complete(emptyList())
        }
    }

    private fun call(value: Any, member: AppleMusicRuntimeMember): Any? = runCatching {
        AppleReflection.call(value, catalog.catalogMember(member))
    }.getOrNull()

    private fun id(entity: Any): String? = (call(entity, AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD) as? String)?.takeIf(String::isNotBlank)

    private fun name(entity: Any): String? {
        val attributes = call(entity, AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD) ?: return null
        return (AppleMediaApiAttributeSnapshots.get(attributes)?.name
            ?: call(attributes, AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD) as? String)?.takeIf(String::isNotBlank)
    }

    private fun responseEntities(response: Any?): List<Any> = response?.let {
        catalog.collectionValues(call(it, AppleMusicRuntimeMember.CATALOG_RESPONSE_DATA_METHOD))
    }.orEmpty()

    private fun relationIds(entity: Any, key: String): Set<String> {
        val relationships = call(entity, AppleMusicRuntimeMember.CATALOG_ENTITY_RELATIONSHIPS_METHOD) as? Map<*, *> ?: return emptySet()
        val relation = relationships[key] ?: return emptySet()
        return catalog.collectionValues(call(relation, AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_ENTITIES_METHOD)
            ?: call(relation, AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_DATA_METHOD)).mapNotNull(::id).toSet()
    }
}
