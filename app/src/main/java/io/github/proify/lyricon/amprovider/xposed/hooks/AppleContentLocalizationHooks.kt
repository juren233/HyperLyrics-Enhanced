/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import io.github.proify.lyricon.amprovider.xposed.AppleContentHttpTimingTracker
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.isAppleLyricsRequestPath
import java.util.concurrent.ConcurrentHashMap

internal class AppleContentLocalizationHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> SharedPreferences?,
    private val catalogResolver: () -> AppleInternalCatalogResolver,
) {
    @Volatile
    private var lastLoggedContentLanguage: String? = null
    private val contentRequestTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val contentRequestDecisionTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val contentRequestHeaderTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val mediaApiLocalizationTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val contentHttpTimingTracker by lazy {
        AppleContentHttpTimingTracker(clock = SystemClock::elapsedRealtime)
    }
    private lateinit var contentHttpTarget: AppleMusicHookTarget

    fun installMediaApiLocalization() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.MEDIA_API_LOCALIZATION
            )
            val method = resolved.method
            runtime.hookRegistrar.installHook(method, after = { _, result ->
                @Suppress("UNCHECKED_CAST")
                val params = result as? MutableMap<Any?, Any?> ?: return@installHook
                val prefs = preferences() ?: return@installHook
                val selection = prefs.getInt(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
                )
                val resolver = catalogResolver()
                resolver.applyContentUiLanguage(selection)
                val requestToken = params[
                    AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
                ]?.toString()
                val requestLocalization = resolver.catalogRequestLocalization(requestToken)
                val language = requestLocalization?.language
                    ?: resolver.languageTagForCurrentRequest(selection)
                language?.let {
                    params["l"] = language
                    if (lastLoggedContentLanguage != language) {
                        lastLoggedContentLanguage = language
                        ProviderLogger.info(
                            "Apple Music 内容本地化参数已覆盖: language=$language"
                        )
                    }
                }
                if (
                    BuildConfig.DEBUG &&
                    requestToken != null &&
                    mediaApiLocalizationTraceKeys.add(requestToken)
                ) {
                    ProviderLogger.diagnostic(
                        "AppleCatalogLocalizationParams: token=$requestToken, " +
                            "resolved=${requestLocalization != null}, " +
                            "storefront=${requestLocalization?.storefront ?: "fallback"}, " +
                            "language=${requestLocalization?.language ?: language ?: "unset"}"
                    )
                }
            })
            ProviderLogger.info(
                "Apple Music 内容本地化参数 Hook 已安装: " +
                    "${resolved.target.className}#${method.name}, " +
                    "fallback=${resolved.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 内容本地化参数 Hook 安装失败", it)
        }
    }

    fun installContentHttpLocalization() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION
            )
            contentHttpTarget = resolved.target
            runtime.hookRegistrar.installHook(
                resolved.method,
                before = { chain ->
                    val prefs = preferences() ?: return@installHook
                    val selection = prefs.getInt(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
                    )
                    val httpChain = chain.args.firstOrNull() ?: return@installHook
                    val request = AppleReflection.field(
                        httpChain,
                        member(AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD),
                    ) ?: return@installHook
                    val requestUrl = AppleReflection.field(
                        request,
                        member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD),
                    )?.toString().orEmpty()
                    val requestUri = Uri.parse(requestUrl)
                    startContentHttpTiming(httpChain, requestUri)
                    val pathSegments = requestUri.pathSegments
                    val isLyricsRequest = isAppleLyricsRequestPath(pathSegments)
                    val resolver = catalogResolver()
                    if (
                        AppleInternalCatalogResolver.isAccountScopedPlaybackPath(pathSegments) ||
                        isLyricsRequest
                    ) {
                        val accountStorefront = resolver.accountStorefrontForPlaybackRequest()
                            ?: return@installHook
                        val rewritten = rewriteContentRequestStorefrontOnly(
                            request = request,
                            storefront = accountStorefront,
                        )
                        rewritten?.let {
                            AppleReflection.setField(
                                httpChain,
                                member(
                                    AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD
                                ),
                                it,
                            )
                        }
                        if (BuildConfig.DEBUG && isLyricsRequest) {
                            val sourceStorefront =
                                AppleInternalCatalogResolver.storefrontFromContentPath(pathSegments)
                            ProviderLogger.info(
                                "Apple 歌词请求使用账号 storefront: " +
                                    "${sourceStorefront ?: "none"}->$accountStorefront"
                            )
                        }
                        return@installHook
                    }
                    val requestToken = requestUri.getQueryParameter(
                        AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
                    )
                    val requestLocalization = resolver.catalogRequestLocalization(requestToken)
                    val configuredStorefront =
                        AppleInternalCatalogResolver.storefrontForContentUiLanguage(selection)
                    val storefront = requestLocalization?.storefront
                        ?: configuredStorefront
                        ?: return@installHook
                    val language = requestLocalization?.language
                        ?: resolver.languageTagForCurrentRequest(selection)
                        ?: return@installHook
                    logContentRequestLocalizationDecision(
                        uri = requestUri,
                        requestToken = requestToken,
                        requestLocalization = requestLocalization,
                        targetStorefront = storefront,
                        targetLanguage = language,
                    )
                    val rewritten = rewriteContentRequest(
                        request = request,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                    ) ?: return@installHook
                    AppleReflection.setField(
                        httpChain,
                        member(AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD),
                        rewritten,
                    )
                },
                after = { chain, result ->
                    finishContentHttpTiming(
                        httpChain = chain.args.firstOrNull(),
                        response = result,
                    )
                },
            )
            ProviderLogger.info("Apple 内容 HTTP 本地化 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple 内容 HTTP 本地化 Hook 安装失败", it)
        }
    }

    private fun startContentHttpTiming(httpChain: Any, uri: Uri) {
        if (!BuildConfig.DEBUG || !uri.host.orEmpty().contains("apple", ignoreCase = true)) return
        val requestToken = uri.getQueryParameter(
            AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
        )
        val source = if (requestToken == null) {
            AppleContentHttpTimingTracker.Source.NATIVE
        } else {
            AppleContentHttpTimingTracker.Source.MODULE
        }
        val start = contentHttpTimingTracker.start(
            requestKey = httpChain,
            descriptor = AppleContentHttpTimingTracker.RequestDescriptor(
                source = source,
                category = contentHttpRequestCategory(uri.pathSegments),
                storefront = AppleInternalCatalogResolver.storefrontFromContentPath(
                    uri.pathSegments
                ),
                pendingModuleRequests = catalogResolver().pendingCatalogRequestCount(),
            ),
        )
        if (start.sourceInFlight == 1) {
            ProviderLogger.diagnostic(
                "AppleContentHttpTiming: event=source_active, " +
                    "source=${source.name.lowercase()}, " +
                    "category=${start.descriptor.category}, " +
                    "storefront=${start.descriptor.storefront ?: "none"}, " +
                    "pendingModule=${start.descriptor.pendingModuleRequests}, " +
                    "totalInFlight=${start.totalInFlight}"
            )
        }
    }

    private fun finishContentHttpTiming(httpChain: Any?, response: Any?) {
        if (!BuildConfig.DEBUG || httpChain == null) return
        val statusCode = response?.let {
            runCatching {
                AppleReflection.intField(
                    it,
                    member(AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_STATUS_FIELD),
                )
            }.getOrNull()
        }
        val completion = contentHttpTimingTracker.finish(httpChain, statusCode) ?: return
        if (completion.isSlow) {
            ProviderLogger.diagnostic(
                "AppleContentHttpTiming: event=slow, " +
                    "source=${completion.descriptor.source.name.lowercase()}, " +
                    "category=${completion.descriptor.category}, " +
                    "storefront=${completion.descriptor.storefront ?: "none"}, " +
                    "elapsedMs=${completion.elapsedMs}, code=${completion.statusCode ?: "unknown"}, " +
                    "pendingModuleAtStart=${completion.descriptor.pendingModuleRequests}, " +
                    "sourceInFlight=${completion.sourceInFlight}, " +
                    "totalInFlight=${completion.totalInFlight}"
            )
        }
        completion.summary?.let { summary ->
            ProviderLogger.diagnostic(
                "AppleContentHttpTiming: event=summary, windowMs=${summary.windowMs}, " +
                    "native=${contentHttpTimingStats(summary.native)}, " +
                    "module=${contentHttpTimingStats(summary.module)}, " +
                    "totalInFlight=${summary.totalInFlight}"
            )
        }
    }

    private fun contentHttpTimingStats(
        stats: AppleContentHttpTimingTracker.SourceStats,
    ): String = "{completed=${stats.completed}, avgMs=${stats.averageElapsedMs}, " +
        "maxMs=${stats.maxElapsedMs}, slow=${stats.slowRequests}, " +
        "inFlight=${stats.inFlight}, categories=${stats.categories}}"

    private fun contentHttpRequestCategory(pathSegments: List<String>): String {
        if (isAppleLyricsRequestPath(pathSegments)) return "lyrics"
        val knownCategories = listOf(
            "artists",
            "albums",
            "songs",
            "music-videos",
            "playlists",
            "search",
            "charts",
            "views",
            "recommendations",
        )
        return pathSegments.firstOrNull(knownCategories::contains) ?: "other"
    }

    private fun rewriteContentRequest(
        request: Any,
        storefront: String,
        language: String,
        requestToken: String?,
    ): Any? {
        val url = AppleReflection.field(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD),
        )?.toString().orEmpty()
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty()
        if (!host.contains("apple", ignoreCase = true)) return null

        val segments = uri.pathSegments.toMutableList()
        val pathStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(segments)
        val isPersonalizedContent = segments.take(3) == listOf("v1", "me", "recommendations")
        val isLyricsRequest = isAppleLyricsRequestPath(segments)
        if (pathStorefront == null && !isPersonalizedContent) return null
        if (isLyricsRequest) return null
        if (pathStorefront != null) segments[2] = storefront

        val builder = uri.buildUpon()
        builder.encodedPath(
            segments.joinToString(separator = "/", prefix = "/") { Uri.encode(it) }
        )
        builder.clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (
                name != "l" &&
                name != AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
            ) {
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
        }
        builder.appendQueryParameter("l", language)
        val rewrittenUrl = builder.build().toString()
        val sourceAcceptLanguage = requestHeader(request, "Accept-Language")
        val sourceStorefrontHeader = requestHeader(request, "X-Apple-Store-Front")
        val sourceRequestStorefrontHeader =
            requestHeader(request, "X-Apple-Request-Store-Front")
        val targetStorefrontHeader =
            AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                storefront = storefront,
                currentValue = sourceStorefrontHeader,
            )
        val targetRequestStorefrontHeader =
            AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                storefront = storefront,
                currentValue = sourceRequestStorefrontHeader,
            )
        val hasHeaderChanges =
            sourceAcceptLanguage != language ||
                targetStorefrontHeader != sourceStorefrontHeader ||
                targetRequestStorefrontHeader != sourceRequestStorefrontHeader
        if (rewrittenUrl == url && !hasHeaderChanges) return null

        if (rewrittenUrl != url) {
            logContentRequestRewrite(
                uri = uri,
                pathStorefront = pathStorefront,
                targetStorefront = storefront,
                targetLanguage = language,
                personalized = isPersonalizedContent,
            )
        }

        val requestBuilder = AppleReflection.call(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_NEW_BUILDER_METHOD),
        ) ?: return null
        if (rewrittenUrl != url) {
            AppleReflection.call(
                requestBuilder,
                member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD),
                rewrittenUrl,
            )
        }
        val headerMethod =
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_HEADER_METHOD)
        AppleReflection.call(requestBuilder, headerMethod, "Accept-Language", language)
        targetStorefrontHeader?.let { value ->
            AppleReflection.call(requestBuilder, headerMethod, "X-Apple-Store-Front", value)
        }
        targetRequestStorefrontHeader?.let { value ->
            AppleReflection.call(
                requestBuilder,
                headerMethod,
                "X-Apple-Request-Store-Front",
                value,
            )
        }
        logContentRequestHeaders(
            requestToken = requestToken,
            sourceAcceptLanguage = sourceAcceptLanguage,
            targetAcceptLanguage = language,
            sourceStorefrontHeader = sourceStorefrontHeader,
            targetStorefrontHeader = targetStorefrontHeader,
            sourceRequestStorefrontHeader = sourceRequestStorefrontHeader,
            targetRequestStorefrontHeader = targetRequestStorefrontHeader,
        )
        return AppleReflection.call(
            requestBuilder,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_BUILD_METHOD),
        )
    }

    private fun requestHeader(request: Any, name: String): String? = runCatching {
        val headers = AppleReflection.field(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_HEADERS_FIELD),
        ) ?: return@runCatching null
        (AppleReflection.call(
            headers,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_HEADERS_GET_METHOD),
            name,
        ) as? String)?.trim()?.takeIf(String::isNotEmpty)
    }.getOrNull()

    private fun rewriteContentRequestStorefrontOnly(
        request: Any,
        storefront: String,
    ): Any? {
        val url = AppleReflection.field(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD),
        )?.toString().orEmpty()
        val uri = Uri.parse(url)
        if (!uri.host.orEmpty().contains("apple", ignoreCase = true)) return null
        val segments = uri.pathSegments.toMutableList()
        val pathStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(segments)
            ?: return null
        if (pathStorefront == storefront) return null
        segments[2] = storefront
        val rewrittenUrl = uri.buildUpon()
            .encodedPath(
                segments.joinToString(separator = "/", prefix = "/") { Uri.encode(it) }
            )
            .build()
            .toString()
        val requestBuilder = AppleReflection.call(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_NEW_BUILDER_METHOD),
        ) ?: return null
        AppleReflection.call(
            requestBuilder,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD),
            rewrittenUrl,
        )
        return AppleReflection.call(
            requestBuilder,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_BUILD_METHOD),
        )
    }

    private fun logContentRequestRewrite(
        uri: Uri,
        pathStorefront: String?,
        targetStorefront: String,
        targetLanguage: String,
        personalized: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val segments = uri.pathSegments
        val category = when {
            personalized -> "recommendations"
            "library" in segments -> "library"
            "recent" in segments || "history" in segments -> "recent"
            "radio" in segments || "stations" in segments -> "radio"
            "playlists" in segments -> "playlists"
            "albums" in segments -> "albums"
            "artists" in segments -> "artists"
            "songs" in segments -> "songs"
            segments.getOrNull(1) == "me" -> "me"
            else -> "other"
        }
        val safeSegments = setOf(
            "v1", "catalog", "me", "recommendations", "library", "recent", "history",
            "radio", "stations", "playlists", "albums", "artists", "songs", "search",
            "charts", "views", "relationships", "personal-recommendation",
        )
        val pathShape = segments.mapIndexed { index, segment ->
            when {
                index == 2 && pathStorefront != null -> "{storefront}"
                segment in safeSegments -> segment
                else -> "{value}"
            }
        }.joinToString(separator = "/", prefix = "/")
        val sourceLanguage = uri.getQueryParameter("l") ?: "unset"
        val traceKey = "$category:$pathShape:$pathStorefront:$targetStorefront:" +
            "$sourceLanguage:$targetLanguage"
        if (!contentRequestTraceKeys.add(traceKey)) return
        ProviderLogger.info(
            "Apple 内容请求路径改写: host=${uri.host.orEmpty()}, category=$category, " +
                "path=$pathShape, storefront=${pathStorefront ?: "none"}->$targetStorefront, " +
                "language=$sourceLanguage->$targetLanguage"
        )
    }

    private fun logContentRequestLocalizationDecision(
        uri: Uri,
        requestToken: String?,
        requestLocalization: AppleInternalCatalogResolver.CatalogRequestLocalization?,
        targetStorefront: String,
        targetLanguage: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val segments = uri.pathSegments
        if (segments.getOrNull(3) != "songs") return
        val pendingCount = catalogResolver().pendingCatalogRequestCount()
        if (requestToken == null && pendingCount == 0) return
        val sourceStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(segments)
        val sourceLanguage = uri.getQueryParameter("l") ?: "unset"
        val requestKey = uri.getQueryParameter("ids")
            ?: uri.getQueryParameter("filter[isrc]")
            ?: segments.getOrNull(4)
            ?: "none"
        val traceKey = "$requestToken:$requestKey:$sourceStorefront:$sourceLanguage:" +
            "$targetStorefront:$targetLanguage:${requestLocalization != null}"
        if (!contentRequestDecisionTraceKeys.add(traceKey)) return
        ProviderLogger.diagnostic(
            "AppleContentHttpLocalization: token=${requestToken ?: "none"}, " +
                "resolved=${requestLocalization != null}, pending=$pendingCount, " +
                "request=$requestKey, storefront=${sourceStorefront ?: "none"}" +
                "->$targetStorefront, language=$sourceLanguage->$targetLanguage"
        )
    }

    private fun logContentRequestHeaders(
        requestToken: String?,
        sourceAcceptLanguage: String?,
        targetAcceptLanguage: String,
        sourceStorefrontHeader: String?,
        targetStorefrontHeader: String?,
        sourceRequestStorefrontHeader: String?,
        targetRequestStorefrontHeader: String?,
    ) {
        if (!BuildConfig.DEBUG || requestToken == null) return
        if (!contentRequestHeaderTraceKeys.add(requestToken)) return
        ProviderLogger.diagnostic(
            "AppleContentHttpHeaders: token=$requestToken, " +
                "acceptLanguage=${sourceAcceptLanguage ?: "unset"}->$targetAcceptLanguage, " +
                "storefrontHeader=${sourceStorefrontHeader ?: "unset"}" +
                "->${targetStorefrontHeader ?: "unset"}, " +
                "requestStorefrontHeader=${sourceRequestStorefrontHeader ?: "unset"}" +
                "->${targetRequestStorefrontHeader ?: "unset"}"
        )
    }

    private fun member(member: AppleMusicRuntimeMember): String =
        contentHttpTarget.runtimeMemberName(member)
}
