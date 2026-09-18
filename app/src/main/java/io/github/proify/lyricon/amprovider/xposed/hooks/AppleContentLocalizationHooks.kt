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
import io.github.libxposed.api.XposedInterface.Chain
import io.github.proify.lyricon.amprovider.xposed.AppleContentHttpTimingTracker
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.CatalogRequestLocalization
import io.github.proify.lyricon.amprovider.xposed.applyContentUiLanguage
import io.github.proify.lyricon.amprovider.xposed.accountStorefrontForPlaybackRequest
import io.github.proify.lyricon.amprovider.xposed.isAccountScopedPlaybackPath
import io.github.proify.lyricon.amprovider.xposed.storefrontFromContentPath
import io.github.proify.lyricon.amprovider.xposed.localizedStorefrontHeaderValue
import io.github.proify.lyricon.amprovider.xposed.storefrontForContentUiLanguage
import io.github.proify.lyricon.amprovider.xposed.CATALOG_REQUEST_TOKEN_PARAM
import io.github.proify.lyricon.amprovider.xposed.AMP_HTTP_MODULE_MARKER_PARAM
import io.github.proify.lyricon.amprovider.xposed.AMP_HTTP_MODULE_MARKER_VALUE
import io.github.proify.lyricon.amprovider.xposed.catalogRequestLocalization
import io.github.proify.lyricon.amprovider.xposed.languageTagForCurrentRequest
import io.github.proify.lyricon.amprovider.xposed.pendingCatalogRequestCount
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.isAppleLyricsRequestPath
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

private const val APPLE_REQUEST_SHAPE_TRACE_LIMIT = 300

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
    private val contentRequestShapeTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val contentHttpTimingTracker by lazy {
        AppleContentHttpTimingTracker(clock = SystemClock::elapsedRealtime)
    }
    private lateinit var contentHttpTarget: AppleMusicHookTarget

    fun installCatalogRequestLocalization() {
        val resolvedClasses = runCatching {
            runtime.hookResolver.resolveClasses(
                AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR
            )
        }.getOrNull()
        if (resolvedClasses.isNullOrEmpty()) {
            ProviderLogger.info(
                "Apple Music 目录直连请求 storefront Hook 未安装: 本版本无已验证执行器目标"
            )
            return
        }
        // resolveClasses 按类去重（v8.D 的 d 和 b 共用一个类条目），必须按档案目标
        // 逐个匹配方法安装，漏一个目标就是一条请求通道（如批量加载 v8.D#b）。
        val classesByTarget = resolvedClasses.associateBy { it.target.className }
        val exactTargets = io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
            .exactTargets(
                runtime.hookResolver.version,
                AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR,
            )
        var installed = 0
        exactTargets.forEach { target ->
            val resolved = classesByTarget[target.className]
            if (resolved == null) {
                ProviderLogger.info(
                    "Apple Music 目录直连请求 storefront Hook 目标类缺失: ${target.className}"
                )
                return@forEach
            }
            runCatching {
                val method = resolved.clazz.declaredMethods
                    .filter { candidate ->
                        candidate.name == target.methodName &&
                            candidate.parameterCount == target.parameterCount
                    }
                    .filter { candidate ->
                        val expected = target.parameterTypeNames
                        expected == null || expected.indices.all { index ->
                            expected[index] == null ||
                                expected[index] == candidate.parameterTypes[index].name
                        }
                    }
                    .single()
                method.isAccessible = true
                val queryArgIndex = AppleCatalogExecutorArgs.queryArgIndex(method)
                runtime.hookRegistrar.installArgumentRewriteHook(method) { chain ->
                    val prefs = preferences()
                    val configuredStorefront = prefs?.getInt(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
                    )?.let { selection -> storefrontForContentUiLanguage(selection) }
                    val result = AppleCatalogExecutorArgs.rewrite(
                        chain.args,
                        queryArgIndex,
                        configuredStorefront,
                    ) { token ->
                        catalogResolver().catalogRequestLocalization(token)
                    }
                    if (
                        result != null &&
                        BuildConfig.DEBUG &&
                        mediaApiLocalizationTraceKeys.add(
                            "${resolved.target.className}#${target.methodName}:" +
                                (result.token ?: "native:${result.storefront}")
                        )
                    ) {
                        ProviderLogger.diagnostic(
                            "AppleCatalogExecutorLocalization: " +
                                "executor=${resolved.target.className}#${target.methodName}, " +
                                "token=${result.token ?: "none"}, " +
                                "storefront=${result.storefront ?: "unchanged"}"
                        )
                    }
                    result?.args
                }
                installed += 1
            }.onFailure {
                ProviderLogger.error(
                    "Apple Music 目录直连请求 storefront Hook 安装失败: " +
                        "${target.className}#${target.methodName}", it
                )
            }
        }
        ProviderLogger.info(
            "Apple Music 目录直连请求 storefront Hook 已安装: executors=" +
                exactTargets.joinToString("/") { "${it.className}#${it.methodName}" } +
                ", installed=$installed"
        )
    }

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
                    CATALOG_REQUEST_TOKEN_PARAM
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
                before = ::contentHttpLocalizationBefore,
                after = ::contentHttpLocalizationAfter,
            )
            ProviderLogger.info("Apple 内容 HTTP 本地化 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple 内容 HTTP 本地化 Hook 安装失败", it)
        }
    }

    /**
     * amp-api 媒体客户端的网络拦截器（6.5.3=w8.d#a）：所有内容请求的最终形态都在此
     * 执行，覆盖不经 repository executor 的浏览/编辑页请求（新发现、广播等独立体系）。
     * executor 标记过的请求（hle_catalog_module）已按条改写，这里直接放行。
     */
    fun installAmpApiHttpLocalization() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.MEDIA_API_AMP_HTTP_INTERCEPTOR
            )
            runtime.hookRegistrar.installHook(
                resolved.method,
                before = ::contentHttpLocalizationBefore,
                after = ::contentHttpLocalizationAfter,
            )
            ProviderLogger.info(
                "Apple amp-api 内容请求网络拦截 Hook 已安装: " +
                    "target=${resolved.target.className}#${resolved.target.methodName}"
            )
        }.onFailure {
            ProviderLogger.error("Apple amp-api 内容请求网络拦截 Hook 安装失败", it)
        }
    }

    private fun contentHttpLocalizationBefore(chain: Chain) {
        val prefs = preferences() ?: return
        val selection = prefs.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
        )
        val httpChain = chain.args.firstOrNull() ?: return
        val request = AppleReflection.field(
            httpChain,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD),
        ) ?: return
        val requestUrl = AppleReflection.field(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD),
        )?.toString().orEmpty()
        val requestUri = Uri.parse(requestUrl)
        startContentHttpTiming(httpChain, requestUri)
        logAppleHostRequestShape(requestUri)
        if (requestUri.getQueryParameter(AMP_HTTP_MODULE_MARKER_PARAM) != null) {
            logModuleMarkedRequestSkip(requestUri)
            return
        }
        val pathSegments = requestUri.pathSegments
        val isLyricsRequest = isAppleLyricsRequestPath(pathSegments)
        val resolver = catalogResolver()
        if (
            isAccountScopedPlaybackPath(pathSegments) ||
            isLyricsRequest
        ) {
            val accountStorefront = resolver.accountStorefrontForPlaybackRequest()
                ?: return
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
                    storefrontFromContentPath(pathSegments)
                ProviderLogger.info(
                    "Apple 歌词请求使用账号 storefront: " +
                        "${sourceStorefront ?: "none"}->$accountStorefront"
                )
            }
            return
        }
        val requestToken = requestUri.getQueryParameter(
            CATALOG_REQUEST_TOKEN_PARAM
        )
        val requestLocalization = resolver.catalogRequestLocalization(requestToken)
        val configuredStorefront =
            storefrontForContentUiLanguage(selection)
        val storefront = requestLocalization?.storefront
            ?: configuredStorefront
            ?: return
        val language = requestLocalization?.language
            ?: resolver.languageTagForCurrentRequest(selection)
            ?: return
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
        ) ?: return
        AppleReflection.setField(
            httpChain,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD),
            rewritten,
        )
    }

    private fun contentHttpLocalizationAfter(chain: Chain, result: Any?) {
        finishContentHttpTiming(
            httpChain = chain.args.firstOrNull(),
            response = result,
        )
    }

    private fun logModuleMarkedRequestSkip(requestUri: Uri) {
        if (!BuildConfig.DEBUG) return
        val key = requestUri.host.orEmpty() + requestUri.path
        if (!contentRequestShapeTraceKeys.add("marked:$key")) return
        val tokenPresent =
            requestUri.getQueryParameter(CATALOG_REQUEST_TOKEN_PARAM) != null
        ProviderLogger.diagnostic(
            "AppleAmpHttpMarkedSkip: ${requestUri.host.orEmpty()}${requestUri.path} " +
                "tokenPresent=$tokenPresent"
        )
    }

    private fun startContentHttpTiming(httpChain: Any, uri: Uri) {
        if (!BuildConfig.DEBUG || !uri.host.orEmpty().contains("apple", ignoreCase = true)) return
        val requestToken = uri.getQueryParameter(
            CATALOG_REQUEST_TOKEN_PARAM
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
                storefront = storefrontFromContentPath(
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

    /**
     * 6.5.3 取证诊断：ka.a 拦截器可见的全部 Apple host 请求路径形状（debug-only，
     * 按 host+path 去重，query 只保留 l 与模块 token 的存在性，上限防刷屏）。用于
     * 定位「新发现/广播」等页面请求的真实 storefront 段与版本前缀。
     */
    private fun logAppleHostRequestShape(uri: Uri) {
        if (!BuildConfig.DEBUG) return
        val host = uri.host.orEmpty()
        if (!host.contains("apple", ignoreCase = true)) return
        if (contentRequestShapeTraceKeys.size >= APPLE_REQUEST_SHAPE_TRACE_LIMIT) return
        val path = "/" + uri.pathSegments.joinToString("/")
        val queryShape = uri.queryParameterNames.sorted().joinToString("&") { name ->
            when (name) {
                "l" -> "l=${uri.getQueryParameter("l")}"
                CATALOG_REQUEST_TOKEN_PARAM -> "$name=present"
                else -> name
            }
        }
        val shape = "$host$path?$queryShape"
        if (!contentRequestShapeTraceKeys.add(shape)) return
        ProviderLogger.diagnostic(
            "AppleContentHttpRequestShape: $shape"
        )
    }

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
        val pathStorefront = storefrontFromContentPath(segments)
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
                name != CATALOG_REQUEST_TOKEN_PARAM
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
            localizedStorefrontHeaderValue(
                storefront = storefront,
                currentValue = sourceStorefrontHeader,
            )
        val targetRequestStorefrontHeader =
            localizedStorefrontHeaderValue(
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
        val pathStorefront = storefrontFromContentPath(segments)
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
        requestLocalization: CatalogRequestLocalization?,
        targetStorefront: String,
        targetLanguage: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val segments = uri.pathSegments
        if (segments.getOrNull(3) != "songs") return
        val pendingCount = catalogResolver().pendingCatalogRequestCount()
        if (requestToken == null && pendingCount == 0) return
        val sourceStorefront = storefrontFromContentPath(segments)
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

/**
 * 目录直连执行器（6.5.3 v8.D/A5.l/Ic.n 请求方法）参数级本地化改写：
 * storefront 恒为参数 index 3（"/v1/catalog/{arg3}/"、"/v1/editorial/{arg3}/" 路径段，
 * 各方法字节码逐一验证）；查询表索引随方法形状不同（d/e 在 5，b/c 在 4——v8.D.b 的
 * arg4 是 query、arg5 是 headers），由安装器从 Method 签名取「第一个 Map 参数」得出。
 * 识别模块请求的唯一切入点是查询表里的 hle_catalog_request token；无论能否解析出目标
 * storefront，该 token 都必须从出网请求中移除。原生请求无 token：配置了内容地区时按
 * 配置值改写 storefront（替代 6.5.3 上已失联的 HTTP 层 URL 改写），未配置则不动。
 */
internal object AppleCatalogExecutorArgs {
    const val STOREFRONT_ARG_INDEX = 3

    internal class Result(
        val token: String?,
        val storefront: String?,
        val args: Array<Any?>,
    )

    /** 查询表参数位：Method 签名里第一个 Map 类型参数（d/e=arg5，b/c=arg4）。 */
    fun queryArgIndex(method: Method): Int =
        method.parameterTypes.indexOfFirst { Map::class.java.isAssignableFrom(it) }

    fun rewrite(
        args: List<Any?>,
        queryArgIndex: Int,
        configuredStorefront: String?,
        localizationForToken: (String) -> CatalogRequestLocalization?,
    ): Result? {
        if (args.size <= queryArgIndex || queryArgIndex <= STOREFRONT_ARG_INDEX) return null
        @Suppress("UNCHECKED_CAST")
        val query = args[queryArgIndex] as? MutableMap<Any?, Any?> ?: return null
        val token = query[CATALOG_REQUEST_TOKEN_PARAM] as? String
        if (token == null) {
            if (configuredStorefront.isNullOrEmpty()) return null
            query[AMP_HTTP_MODULE_MARKER_PARAM] = AMP_HTTP_MODULE_MARKER_VALUE
            val rewritten = args.toTypedArray()
            rewritten[STOREFRONT_ARG_INDEX] = configuredStorefront
            return Result(null, configuredStorefront, rewritten)
        }
        query.remove(CATALOG_REQUEST_TOKEN_PARAM)
        query[AMP_HTTP_MODULE_MARKER_PARAM] = AMP_HTTP_MODULE_MARKER_VALUE
        val localization = localizationForToken(token)
        val target = localization?.storefront
        val rewritten = args.toTypedArray()
        if (target != null) {
            rewritten[STOREFRONT_ARG_INDEX] = target
        }
        return Result(token, target, rewritten)
    }
}
