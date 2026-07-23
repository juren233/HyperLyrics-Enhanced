package io.github.proify.lyricon.amprovider.xposed

import android.os.Handler
import com.juren233.hyperlyricsenhanced.common.RootConstants
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

internal class AppleInternalCatalogResolver(
    private val classLoader: ClassLoader,
    private val mainHandler: Handler
) {
    private val cache = object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias>?): Boolean =
            size > CACHE_SIZE
    }
    private val inFlight = mutableSetOf<String>()
    private var catalogAccess: CatalogAccess? = null
    @Volatile
    private var accountStorefront: String? = null
    @Volatile
    private var accountStorefrontCaptured = false
    @Volatile
    private var lastAppliedConfiguredStorefront: String? = null
    @Volatile
    private var activeCatalogQueryStorefront: String? = null
    @Volatile
    private var activeCatalogQueryLanguage: String? = null
    @Volatile
    private var contentUiLanguageSelection =
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE

    /**
     * Applies the content storefront without changing the account's original value.
     * The MediaApi storefront is also used by Apple Music for localized content, so
     * keeping this value in one place covers both startup and post-query recovery.
     */
    fun applyContentUiLanguage(selection: Int) {
        contentUiLanguageSelection = selection
        runCatching {
            val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
            restoreConfiguredStorefront(access)
        }.onFailure {
            ProviderLogger.error("Apple 内容 UI storefront 应用失败: selection=$selection", it)
        }
    }

    fun languageTagForCurrentRequest(selection: Int): String? =
        activeCatalogQueryLanguage ?: languageTagForContentUiLanguage(selection)

    fun shouldPreserveCatalogStorefront(
        storefront: String?,
        entityType: String?
    ): Boolean = storefront != null &&
        storefront == activeCatalogQueryStorefront &&
        entityType == "songs"

    private fun restoreConfiguredStorefront(access: CatalogAccess) {
        captureAccountStorefront(access)
        val selection = contentUiLanguageSelection
        val configuredStorefront = storefrontForContentUiLanguage(selection)
        if (configuredStorefront == null && !accountStorefrontCaptured) return
        val target = configuredStorefront ?: accountStorefront
        val previous = access.storefrontField.get(access.mediaApi) as? String
        access.storefrontField.set(access.mediaApi, target)
        lastAppliedConfiguredStorefront = configuredStorefront
        if (previous != target) {
            ProviderLogger.info(
                "Apple 内容 UI storefront 已应用: selection=$selection, " +
                    "previous=${previous ?: "unset"}, " +
                    "storefront=${target ?: "account-default"}, " +
                    "accountStorefront=${accountStorefront ?: "account-default"}"
            )
        }
    }

    private fun captureAccountStorefront(access: CatalogAccess) {
        val current = access.storefrontField.get(access.mediaApi) as? String ?: return
        if (current == activeCatalogQueryStorefront) return
        if (!accountStorefrontCaptured || current != lastAppliedConfiguredStorefront) {
            accountStorefront = current
            accountStorefrontCaptured = true
        }
    }

    fun resolve(metadata: MediaMetadataCache.Metadata, onResolved: (Alias?) -> Unit) {
        if (!shouldResolve(metadata)) {
            onResolved(null)
            return
        }
        synchronized(cache) {
            cache[metadata.id]?.let { alias ->
                onResolved(alias)
                return
            }
        }
        synchronized(inFlight) {
            if (!inFlight.add(metadata.id)) return
        }

        val languages = languageTagsForGenre(metadata.genre)
        resolveCatalogIdentity(metadata.id, languages) { identity ->
            val results = identity.fallbackAliases.toMutableList()
            val isrc = identity.isrc
            if (isrc == null) {
                finishResolve(metadata, languages, results, onResolved)
                return@resolveCatalogIdentity
            }
            fun queryNext(index: Int) {
                if (index >= languages.size) {
                    finishResolve(metadata, languages, results, onResolved)
                    return
                }
                val language = languages[index]
                queryByIsrc(isrc, language) { song ->
                    song?.alias?.let(results::add)
                    queryNext(index + 1)
                }
            }
            queryNext(0)
        }
    }

    private fun finishResolve(
        metadata: MediaMetadataCache.Metadata,
        languages: List<String>,
        results: List<Alias>,
        onResolved: (Alias?) -> Unit
    ) {
        val selected = selectOriginalAlias(
            variants = results,
            localizedTitle = metadata.title.orEmpty(),
            localizedArtist = metadata.artist.orEmpty()
        )
        if (selected != null && isOriginalTitle(selected, metadata.title.orEmpty())) {
            synchronized(cache) { cache[metadata.id] = selected }
        }
        synchronized(inFlight) { inFlight.remove(metadata.id) }
        ProviderLogger.info(
            "Apple 内部原名查询完成: id=${metadata.id}, genre=${metadata.genre}, " +
                "languages=$languages, selected=${selected?.title}/${selected?.artist}"
        )
        onResolved(selected)
    }

    private fun resolveCatalogIdentity(
        mediaId: String,
        languages: List<String>,
        onResult: (CatalogIdentity) -> Unit
    ) {
        queryById(mediaId, null) { currentSong ->
            currentSong?.isrc?.let { isrc ->
                ProviderLogger.info("Apple 内部歌曲 ISRC: id=$mediaId, isrc=$isrc")
                onResult(CatalogIdentity(isrc, emptyList()))
                return@queryById
            }

            val fallbackAliases = mutableListOf<Alias>()
            fun queryNext(index: Int) {
                if (index >= languages.size) {
                    onResult(CatalogIdentity(null, fallbackAliases))
                    return
                }
                val language = languages[index]
                queryById(mediaId, language) { song ->
                    song?.alias?.let(fallbackAliases::add)
                    val isrc = song?.isrc
                    if (isrc != null) {
                        ProviderLogger.info(
                            "Apple 内部歌曲 ISRC: id=$mediaId, language=$language, isrc=$isrc"
                        )
                        onResult(CatalogIdentity(isrc, fallbackAliases))
                    } else {
                        queryNext(index + 1)
                    }
                }
            }
            queryNext(0)
        }
    }

    private fun queryById(
        mediaId: String,
        language: String?,
        onResult: (CatalogSong?) -> Unit
    ) {
        query(
            storefrontLanguage = language,
            description = "id=$mediaId",
            createLiveData = { access ->
                val queryParams = linkedMapOf(
                    "platform" to "android",
                    "include[songs]" to "artists"
                )
                language?.let { queryParams["l"] = it }
                AppleReflection.call(
                    access.repository,
                    "getEntity",
                    "songs",
                    mediaId,
                    queryParams
                ) ?: error("Isolated MediaApiRepository.getEntity returned null")
            },
            onResult = onResult
        )
    }

    private fun queryByIsrc(
        isrc: String,
        language: String,
        onResult: (CatalogSong?) -> Unit
    ) {
        query(
            storefrontLanguage = language,
            description = "isrc=$isrc",
            createLiveData = { access ->
                val queryParams = linkedMapOf(
                    "filter[isrc]" to isrc,
                    "l" to language,
                    "platform" to "android",
                    "include[songs]" to "artists",
                    "limit" to "1"
                )
                val queryCommand = createQueryCommand("songs", queryParams)
                AppleReflection.call(access.repository, "queryEntity", queryCommand)
                    ?: error("Isolated MediaApiRepository.queryEntity returned null")
            },
            onResult = onResult
        )
    }

    private fun query(
        storefrontLanguage: String?,
        description: String,
        createLiveData: (CatalogAccess) -> Any,
        onResult: (CatalogSong?) -> Unit
    ) {
        mainHandler.post {
            var restoreStorefront: (() -> Unit)? = null
            runCatching {
                val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
                val storefront = storefrontLanguage?.let(::storefrontForLanguage)
                if (storefront != null) {
                    captureAccountStorefront(access)
                    activeCatalogQueryStorefront = storefront
                    activeCatalogQueryLanguage = storefrontLanguage
                    access.storefrontField.set(access.mediaApi, storefront)
                    restoreStorefront = {
                        runCatching {
                            restoreConfiguredStorefront(access)
                        }
                        activeCatalogQueryStorefront = null
                        activeCatalogQueryLanguage = null
                    }
                }
                val liveData = createLiveData(access)
                observeOnce(liveData) { response ->
                    restoreStorefront?.invoke()
                    restoreStorefront = null
                    val song = runCatching {
                        response?.let {
                            parseCatalogSong(it, storefrontLanguage ?: CURRENT_LANGUAGE)
                        }
                    }.onFailure { error ->
                        ProviderLogger.error(
                            "Apple 内部原名响应解析失败: $description, " +
                                "language=$storefrontLanguage",
                            error
                        )
                    }.getOrNull()
                    ProviderLogger.info(
                        "Apple 内部原名候选: $description, storefront=$storefront, " +
                            "language=$storefrontLanguage, " +
                            "value=${song?.alias?.title}/${song?.alias?.artist}, " +
                            "isrc=${song?.isrc}"
                    )
                    onResult(song)
                }
            }.onFailure { error ->
                restoreStorefront?.invoke()
                ProviderLogger.error(
                    "Apple 内部原名查询启动失败: $description, " +
                        "language=$storefrontLanguage",
                    error
                )
                onResult(null)
            }
        }
    }

    private fun createQueryCommand(path: String, queryParams: Map<String, String>): Any {
        val builderClass = classLoader.loadClass("$MEDIA_API_QUERY_CMD\$Builder")
        val builder = AppleReflection.newInstance(builderClass)
        AppleReflection.call(builder, "withPath", path)
        AppleReflection.call(builder, "withUrlQueryParams", queryParams)
        AppleReflection.call(builder, "forceRefresh", true)
        return AppleReflection.call(builder, "build")
            ?: error("MediaApiQueryCmd.Builder.build returned null")
    }

    private fun createCatalogAccess(): CatalogAccess {
        val holderClass = classLoader.loadClass(MEDIA_API_REPOSITORY_HOLDER)
        val companionField = holderClass.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) &&
                field.type.name == "$MEDIA_API_REPOSITORY_HOLDER\$Companion"
        } ?: error("MediaApiRepositoryHolder companion unavailable")
        companionField.isAccessible = true
        val companion = requireNotNull(companionField.get(null))
        val sourceRepository = AppleReflection.call(companion, "getInstance")
            ?: error("MediaApiRepository unavailable")
        val mediaApi = AppleReflection.call(companion, "getMediaApiWithHTTPCache")
            ?: error("Apple MediaApi with HTTP cache unavailable")
        val cache = findField(sourceRepository, "cache").get(sourceRepository)
            ?: error("MediaApiRepository cache unavailable")
        val errorReporter = findField(sourceRepository, "errorReporter").get(sourceRepository)
            ?: error("MediaApiRepository error reporter unavailable")
        val maxCacheSize = runCatching {
            findField(sourceRepository, "maxCacheSizeInBytes").getInt(sourceRepository)
        }.getOrDefault(DEFAULT_CACHE_SIZE_BYTES)
        val constructor = sourceRepository.javaClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount == 4 &&
                candidate.parameterTypes[0].isInstance(mediaApi) &&
                candidate.parameterTypes[1].isInstance(cache) &&
                candidate.parameterTypes[2] == Int::class.javaPrimitiveType &&
                candidate.parameterTypes[3].isInstance(errorReporter)
        } ?: error("MediaApiRepositoryImpl constructor unavailable")
        constructor.isAccessible = true
        val repository = constructor.newInstance(mediaApi, cache, maxCacheSize, errorReporter)
        val storefrontField = findField(mediaApi, STOREFRONT_FIELD_NAME).also { field ->
            if (field.type != String::class.java) {
                error("Apple MediaApi storefront field has unexpected type")
            }
        }
        return CatalogAccess(repository, mediaApi, storefrontField).also(::captureAccountStorefront)
    }

    private fun findField(instance: Any, name: String): Field {
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

    private fun observeOnce(liveData: Any, onResult: (Any?) -> Unit) {
        val observeMethod = liveData.javaClass.methods.firstOrNull { method ->
            method.name == "observeForever" && method.parameterCount == 1
        } ?: error("LiveData.observeForever unavailable")
        val removeMethod = liveData.javaClass.methods.firstOrNull { method ->
            method.name == "removeObserver" && method.parameterCount == 1
        } ?: error("LiveData.removeObserver unavailable")
        val observerType = observeMethod.parameterTypes.single()
        val completed = AtomicBoolean(false)
        lateinit var observer: Any
        val timeout = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            runCatching { removeMethod.invoke(liveData, observer) }
            onResult(null)
        }
        observer = Proxy.newProxyInstance(
            observerType.classLoader ?: classLoader,
            arrayOf(observerType)
        ) { proxy, method, args ->
            when (method.name) {
                "onChanged" -> {
                    val value = args?.firstOrNull()
                    if (value != null && completed.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout)
                        runCatching { removeMethod.invoke(liveData, observer) }
                        onResult(value)
                    }
                    null
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "AppleCatalogObserver"
                else -> null
            }
        }
        observeMethod.invoke(liveData, observer)
        mainHandler.postDelayed(timeout, QUERY_TIMEOUT_MS)
    }

    private fun storefrontForLanguage(language: String): String = when (language) {
        "ja-JP" -> "jp"
        "ko-KR" -> "kr"
        "zh-Hans-CN" -> "cn"
        "zh-Hant-HK" -> "hk"
        "zh-Hant-TW" -> "tw"
        "th-TH" -> "th"
        "ru-RU" -> "ru"
        "uk-UA" -> "ua"
        "ar-SA" -> "sa"
        "he-IL" -> "il"
        "hi-IN" -> "in"
        "el-GR" -> "gr"
        "bg-BG" -> "bg"
        else -> error("Unsupported Apple storefront language: $language")
    }

    private fun parseCatalogSong(response: Any, language: String): CatalogSong? {
        val data = AppleReflection.call(response, "getData") as? Array<*> ?: return null
        val entity = data.firstOrNull() ?: return null
        val attributes = AppleReflection.call(entity, "getAttributes") ?: return null
        val title = (AppleReflection.call(attributes, "getName") as? String)?.trim().orEmpty()
        val artist = (AppleReflection.call(attributes, "getArtistName") as? String)
            ?.trim()
            .orEmpty()
        val isrc = (AppleReflection.call(attributes, "getIsrc") as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (title.isEmpty() && artist.isEmpty() && isrc == null) return null
        return CatalogSong(Alias(title, artist, language), isrc)
    }

    private data class CatalogAccess(
        val repository: Any,
        val mediaApi: Any,
        val storefrontField: Field
    )

    private data class CatalogIdentity(
        val isrc: String?,
        val fallbackAliases: List<Alias>
    )

    private data class CatalogSong(
        val alias: Alias,
        val isrc: String?
    )

    data class Alias(
        val title: String,
        val artist: String,
        val language: String
    )

    companion object {
        private const val MEDIA_API_REPOSITORY_HOLDER =
            "com.apple.android.music.mediaapi.repository.MediaApiRepositoryHolder"
        private const val MEDIA_API_QUERY_CMD =
            "com.apple.android.music.mediaapi.repository.cmd.MediaApiQueryCmd"
        private const val STOREFRONT_FIELD_NAME = "s"
        private const val CURRENT_LANGUAGE = "current"
        private const val DEFAULT_CACHE_SIZE_BYTES = 10 * 1024 * 1024
        private const val CACHE_SIZE = 64
        private const val QUERY_TIMEOUT_MS = 6_000L

        internal fun languageTagsForGenre(genre: String?): List<String> {
            val normalized = genre.orEmpty().trim().lowercase()
            return when {
                "j-pop" in normalized || "japanese" in normalized -> listOf("ja-JP")
                "k-pop" in normalized || "korean" in normalized -> listOf("ko-KR")
                "mandopop" in normalized || "chinese" in normalized ->
                    listOf("zh-Hans-CN", "zh-Hant-TW")
                "cantopop" in normalized || "hong kong" in normalized ->
                    listOf("zh-Hant-HK", "zh-Hant-TW")
                "thai" in normalized -> listOf("th-TH")
                "russian" in normalized -> listOf("ru-RU")
                "ukrain" in normalized -> listOf("uk-UA")
                "arab" in normalized -> listOf("ar-SA")
                "israel" in normalized || "hebrew" in normalized -> listOf("he-IL")
                "indian" in normalized || "bollywood" in normalized -> listOf("hi-IN")
                "greek" in normalized -> listOf("el-GR")
                "bulgar" in normalized -> listOf("bg-BG")
                else -> listOf("ja-JP", "ko-KR", "zh-Hans-CN", "zh-Hant-TW")
            }
        }

        internal fun storefrontForContentUiLanguage(selection: Int): String? = when (selection) {
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN -> "cn"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US -> "us"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK -> "hk"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW -> "tw"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR -> "kr"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP -> "jp"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE -> null
            else -> null
        }

        internal fun languageTagForContentUiLanguage(selection: Int): String? = when (selection) {
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN -> "zh-CN"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US -> "zh-CN"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK -> "zh-HK"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW -> "zh-TW"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR -> "ko-KR"
            RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP -> "ja-JP"
            else -> null
        }

        internal fun storefrontFromContentPath(pathSegments: List<String>): String? = when {
            pathSegments.size >= 3 && pathSegments[0] == "v1" &&
                pathSegments[1] in setOf("catalog", "editorial", "recommendations") ->
                pathSegments[2].takeIf { it.length == 2 }
            else -> null
        }

        internal fun selectOriginalAlias(
            variants: List<Alias>,
            localizedTitle: String,
            localizedArtist: String
        ): Alias? {
            val localizedKey = "${normalize(localizedTitle)}|${normalize(localizedArtist)}"
            val candidates = variants
                .filter { alias ->
                    val aliasKey = "${normalize(alias.title)}|${normalize(alias.artist)}"
                    aliasKey != localizedKey &&
                        nonLatinLetterCount(alias.title) + nonLatinLetterCount(alias.artist) > 0
                }
                .distinctBy { alias -> "${normalize(alias.title)}|${normalize(alias.artist)}" }
            val score = compareBy<Alias> { alias -> nonLatinLetterCount(alias.title) }
                .thenBy { alias -> nonLatinLetterCount(alias.artist) }
            return candidates
                .filter { alias -> isOriginalTitle(alias, localizedTitle) }
                .maxWithOrNull(score)
                ?: candidates.maxWithOrNull(score)
        }

        internal fun isOriginalTitle(alias: Alias, localizedTitle: String): Boolean =
            normalize(alias.title) != normalize(localizedTitle) &&
                nonLatinLetterCount(alias.title) > 0

        internal fun shouldResolve(metadata: MediaMetadataCache.Metadata): Boolean =
            metadata.id.all(Char::isDigit) &&
                metadata.title?.isNotBlank() == true &&
                nonLatinLetterCount(metadata.title) == 0

        private fun nonLatinLetterCount(value: String): Int {
            var count = 0
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                if (Character.isLetter(codePoint) &&
                    Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
                ) {
                    count++
                }
                index += Character.charCount(codePoint)
            }
            return count
        }

        private fun normalize(value: String): String = value
            .trim()
            .lowercase()
            .replace(Regex("[\\s\\p{Punct}]+"), "")
    }
}
