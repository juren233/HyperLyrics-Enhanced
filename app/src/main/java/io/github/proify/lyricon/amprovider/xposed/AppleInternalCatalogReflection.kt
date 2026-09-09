/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun AppleInternalCatalogResolver.createCatalogAccess(): CatalogAccess {
    val resolvedHolder = resolvedCatalogHolder
    val holderClass = resolvedHolder.clazz
    val companionField = holderClass.declaredFields.firstOrNull { field ->
        Modifier.isStatic(field.modifiers) &&
            field.type.name == "${holderClass.name}\$Companion"
    } ?: error("MediaApiRepositoryHolder companion unavailable")
    companionField.isAccessible = true
    val companion = requireNotNull(companionField.get(null))
    val mediaApi = AppleReflection.call(
        companion,
        resolvedHolder.target.runtimeMemberName(
            AppleMusicRuntimeMember.MEDIA_API_HOLDER_GET_MEDIA_API_METHOD
        ),
    )
        ?: error("Apple MediaApi without HTTP cache unavailable")
    val storefrontField = findField(
        mediaApi,
        resolvedHolder.target.runtimeMemberName(
            AppleMusicRuntimeMember.MEDIA_API_STOREFRONT_FIELD
        ),
    ).also { field ->
        if (field.type != String::class.java) {
            error("Apple MediaApi storefront field has unexpected type")
        }
    }
    val directQueryMethod = findDirectCatalogQueryMethod(
        clazz = mediaApi.javaClass,
        methodName = resolvedHolder.target.runtimeMemberName(
            AppleMusicRuntimeMember.MEDIA_API_DIRECT_QUERY_METHOD
        ),
    )
    val continuationType = directQueryMethod.parameterTypes[2]
    val coroutineContextType = continuationType.methods.firstOrNull { method ->
        method.name == "getContext" && method.parameterCount == 0
    }?.returnType ?: error("Apple Continuation context type unavailable")
    val emptyCoroutineContext = createEmptyCoroutineContext(coroutineContextType)
    return CatalogAccess(
        mediaApi = mediaApi,
        storefrontField = storefrontField,
        directQueryMethod = directQueryMethod,
        continuationType = continuationType,
        emptyCoroutineContext = emptyCoroutineContext,
    ).also(::captureAccountStorefront)
}

internal fun AppleInternalCatalogResolver.findDirectCatalogQueryMethod(clazz: Class<*>, methodName: String): Method {
    var current: Class<*>? = clazz
    while (current != null) {
        current.declaredMethods.firstOrNull { method ->
            method.name == methodName &&
                method.parameterTypes.let { types ->
                    types.size == 3 &&
                        types[0] == String::class.java &&
                        Map::class.java.isAssignableFrom(types[1]) &&
                        types[2].name == "kotlin.coroutines.Continuation"
                }
        }?.let { method ->
            method.isAccessible = true
            return method
        }
        current = current.superclass
    }
    throw NoSuchMethodException("${clazz.name}#$methodName(String,Map,Continuation)")
}

internal fun AppleInternalCatalogResolver.createEmptyCoroutineContext(contextType: Class<*>): Any =
    Proxy.newProxyInstance(
        contextType.classLoader ?: classLoader,
        arrayOf(contextType),
    ) { proxy, method, args ->
        when (method.name) {
            "fold" -> args?.firstOrNull()
            "get" -> null
            "minusKey" -> proxy
            "plus" -> args?.firstOrNull()
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> 0
            "toString" -> "EmptyCoroutineContext"
            else -> null
        }
    }

internal fun AppleInternalCatalogResolver.findField(instance: Any, name: String): Field {
    var current: Class<*>? = instance.javaClass
    while (current != null) {
        current.declaredFields.firstOrNull { field -> field.name == name }?.let { field ->
            field.isAccessible = true
            return field
        }
        current = current.superclass
    }
    error("${instance.javaClass.name}#$name unavailable")
}

internal fun AppleInternalCatalogResolver.logCatalogRequestDiagnostic(
    requestId: String,
    event: String,
    description: String,
    storefront: String?,
    language: String?,
    elapsedMs: Long,
    requestToken: String? = null,
    detail: String? = null,
) {
    if (!BuildConfig.DEBUG) return
    val localizedState = synchronized(localizedPending) {
        "${localizedPending.size}/$localizedBatchesRunning"
    }
    val originalState = synchronized(originalEntityPending) {
        "${originalEntityPending.size}/$originalEntityBatchesRunning"
    }
    ProviderLogger.diagnostic(
        "AppleCatalogRequest: id=$requestId, token=${requestToken ?: "none"}, " +
            "event=$event, description=$description, storefront=$storefront, " +
            "language=$language, elapsedMs=$elapsedMs, " +
            "localizedPendingRunning=$localizedState, " +
            "originalPendingRunning=$originalState" +
            detail?.let { ", $it" }.orEmpty()
    )
}

internal fun AppleInternalCatalogResolver.catalogResponseDiagnostic(response: Any?): String {
    if (response == null) return "value=null"
    val data = runCatching {
        AppleReflection.call(response, catalogMember(AppleMusicRuntimeMember.CATALOG_RESPONSE_DATA_METHOD))
    }
        .getOrElse { error ->
            return "valueClass=${response.javaClass.name}, " +
                "dataError=${error.javaClass.name}:${error.message}"
        }
    val dataSize = when (data) {
        null -> "null"
        is Array<*> -> data.size.toString()
        is Collection<*> -> data.size.toString()
        is Iterable<*> -> data.count().toString()
        else -> "unknown:${data.javaClass.name}"
    }
    return "valueClass=${response.javaClass.name}, dataSize=$dataSize"
}

internal fun AppleInternalCatalogResolver.storefrontForLanguage(language: String): String =
    storefrontForOriginalLanguage(language)
        ?: error("Unsupported Apple storefront language: $language")

internal fun AppleInternalCatalogResolver.parseCatalogSong(response: Any, language: String): CatalogSong? {
    return parseCatalogSongs(response, language).firstOrNull()
}

internal fun AppleInternalCatalogResolver.parseCatalogSongs(response: Any, language: String): List<CatalogSong> =
    parseCatalogEntities(response, language, LocalizedEntityType.SONG)

internal fun AppleInternalCatalogResolver.parseCatalogEntities(
    response: Any,
    language: String,
    entityType: LocalizedEntityType,
): List<CatalogSong> =
    collectionValues(
        AppleReflection.call(
            response,
            catalogMember(AppleMusicRuntimeMember.CATALOG_RESPONSE_DATA_METHOD),
        )
    ).mapNotNull { entity ->
        parseCatalogEntity(entity, language, entityType)
    }

internal fun AppleInternalCatalogResolver.parseCatalogEntity(
    entity: Any,
    language: String,
    entityType: LocalizedEntityType,
): CatalogSong? {
    val id = (AppleReflection.call(
        entity,
        catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD),
    ) as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    val attributes = AppleReflection.call(
        entity,
        catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
    ) ?: return null
    val rawAttributes = AppleMediaApiAttributeSnapshots.get(attributes)
    val title = if (rawAttributes != null) {
        rawAttributes.name?.trim().orEmpty()
    } else {
        (AppleReflection.call(
            attributes,
            catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD),
        ) as? String)?.trim().orEmpty()
    }
    val attributeArtist = if (rawAttributes != null) {
        rawAttributes.artistName?.trim().orEmpty()
    } else runCatching {
        (AppleReflection.call(
            attributes,
            catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_NAME_METHOD),
        ) as? String)?.trim().orEmpty()
    }.getOrDefault("")
    val album = when (entityType) {
        LocalizedEntityType.SONG -> if (rawAttributes != null) {
            rawAttributes.albumName?.trim().orEmpty()
        } else runCatching {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ALBUM_NAME_METHOD),
            ) as? String)?.trim().orEmpty()
        }.getOrDefault("")
        LocalizedEntityType.ALBUM -> title
        LocalizedEntityType.ARTIST -> ""
    }
    val relationshipArtistEntities = if (entityType == LocalizedEntityType.ARTIST) {
        emptyList()
    } else runCatching {
        @Suppress("UNCHECKED_CAST")
        val relationships = AppleReflection.call(
            entity,
            catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_RELATIONSHIPS_METHOD),
        )
            as? Map<String, Any?>
        val artistRelationship = relationships?.get("artists")
            ?: relationships?.get("artist")
        val artistEntities = collectionValues(
            artistRelationship?.let {
                AppleReflection.call(
                    it,
                    catalogMember(
                        AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_ENTITIES_METHOD
                    ),
                ) ?: AppleReflection.call(
                    it,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_DATA_METHOD),
                )
            }
        )
        artistEntities.mapNotNull { artistEntity ->
            val artistAttributes = AppleReflection.call(
                artistEntity,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
            )
            val rawArtistAttributes = artistAttributes?.let(
                AppleMediaApiAttributeSnapshots::get
            )
            val artistName = if (rawArtistAttributes != null) {
                rawArtistAttributes.name
            } else artistAttributes?.let {
                AppleReflection.call(
                    it,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD),
                ) as? String
            }
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            val artistId = (AppleReflection.call(
                artistEntity,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD),
            ) as? String)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            if (artistName == null && artistId == null) null
            else CatalogArtist(id = artistId, name = artistName)
        }
    }.getOrDefault(emptyList())
    val relationshipArtists = relationshipArtistEntities.mapNotNull(CatalogArtist::name)
    val relationshipArtistIds = relationshipArtistEntities.mapNotNull(CatalogArtist::id)
        .distinct()
    val artist = when (entityType) {
        LocalizedEntityType.ARTIST -> title
        else -> selectLocalizedArtistName(
            attributeArtist = attributeArtist,
            relationshipArtists = relationshipArtists,
            language = language,
        )
    }
    if (relationshipArtists.isNotEmpty() && artist != attributeArtist) {
        ProviderLogger.info(
            "Apple 歌手关系名称已优先: attributes=$attributeArtist, relationship=$artist"
        )
    }
    val isrc = if (entityType == LocalizedEntityType.SONG) {
        runCatching {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ISRC_METHOD),
            ) as? String)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.getOrNull()
    } else {
        null
    }
    val genres = if (entityType != LocalizedEntityType.ARTIST) {
        runCatching {
            collectionValues(
                AppleReflection.call(
                    attributes,
                    catalogMember(
                        AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAMES_METHOD
                    ),
                )
            )
                .mapNotNull { value ->
                    value.toString().trim().takeIf(String::isNotEmpty)
                }
        }.getOrDefault(emptyList()).ifEmpty {
            runCatching {
                (AppleReflection.call(
                    attributes,
                    catalogMember(
                        AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAME_METHOD
                    ),
                ) as? String)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(::listOf)
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
    } else {
        emptyList()
    }
    if (title.isEmpty() && artist.isEmpty() && isrc == null) return null
    return CatalogSong(
        id = id,
        alias = Alias(title, artist, language, album),
        isrc = isrc,
        genres = genres,
        artistIds = relationshipArtistIds,
    )
}

internal fun AppleInternalCatalogResolver.collectionValues(value: Any?): List<Any> = when (value) {
    is Array<*> -> value.filterNotNull()
    is Iterable<*> -> value.filterNotNull()
    is Map<*, *> -> value.values.filterNotNull()
    else -> emptyList()
}

internal fun AppleInternalCatalogResolver.catalogMember(member: AppleMusicRuntimeMember): String =
    resolvedCatalogHolder.target.runtimeMemberName(member)
