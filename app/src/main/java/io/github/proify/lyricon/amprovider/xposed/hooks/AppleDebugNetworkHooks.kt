/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal class AppleDebugNetworkHooks(
    private val runtime: AppleMusicProviderRuntime,
) {
    private val pendingLyricsRequestSources =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()

    fun recordLyricsRequestSource(requestId: String, source: String) {
        if (!BuildConfig.DEBUG) return
        pendingLyricsRequestSources
            .computeIfAbsent(requestId) { ConcurrentLinkedQueue() }
            .add(source)
    }

    fun installLyricsNetworkRequest() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_NETWORK_REQUEST
            )
            runtime.hookRegistrar.installHook(resolved.method, before = { chain ->
                @Suppress("UNCHECKED_CAST")
                val query = chain.args.getOrNull(5) as? MutableMap<String, String>
                    ?: return@installHook
                val id = chain.args.getOrNull(4)?.toString().orEmpty()
                val sourceQueue = pendingLyricsRequestSources[id]
                val source = sourceQueue?.poll() ?: "unknown"
                if (sourceQueue?.isEmpty() == true) {
                    pendingLyricsRequestSources.remove(id, sourceQueue)
                }
                ProviderLogger.debug(
                    "Lyrics network request: source=$source, id=$id, " +
                        "dsid=${sensitiveSummary(chain.args.getOrNull(0))}, " +
                        "userAgent=${sensitiveSummary(chain.args.getOrNull(1))}, " +
                        "authorization=${sensitiveSummary(chain.args.getOrNull(2))}, " +
                        "storefront=${chain.args.getOrNull(3)}, localizationQuery=$query"
                )
            })
        }.onFailure { ProviderLogger.error("Lyrics network request Hook 安装失败", it) }
    }

    fun installLyricsCookies() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(AppleMusicHookPoint.LYRICS_COOKIE_JAR)
            val target = resolved.target
            runtime.hookRegistrar.installHook(resolved.method, after = { chain, result ->
                val url = chain.args.firstOrNull()?.toString().orEmpty()
                if (!url.contains("/syllable-lyrics")) return@installHook
                val cookies = (result as? Iterable<*>)?.mapNotNull { cookie ->
                    cookie ?: return@mapNotNull null
                    val name = AppleReflection.field(
                        cookie,
                        target.runtimeMemberName(
                            AppleMusicRuntimeMember.LYRICS_COOKIE_NAME_FIELD
                        ),
                    ) as? String ?: return@mapNotNull null
                    val value = AppleReflection.field(
                        cookie,
                        target.runtimeMemberName(
                            AppleMusicRuntimeMember.LYRICS_COOKIE_VALUE_FIELD
                        ),
                    )
                    "$name(${sensitiveSummary(value)})"
                }.orEmpty()
                ProviderLogger.debug("Lyrics CookieJar: url=$url, cookies=$cookies")
            })
        }.onFailure { ProviderLogger.error("Lyrics CookieJar Hook 安装失败", it) }
    }

    fun installFinalLyricsHttp() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION
            )
            val target = resolved.target
            runtime.hookRegistrar.installHook(
                resolved.method,
                before = { chain ->
                    val interceptor = chain.args.firstOrNull() ?: return@installHook
                    val request = AppleReflection.field(
                        interceptor,
                        target.runtimeMemberName(
                            AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD
                        ),
                    ) ?: return@installHook
                    val url = AppleReflection.field(
                        request,
                        target.runtimeMemberName(
                            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD
                        ),
                    )?.toString().orEmpty()
                    if (!url.contains("/syllable-lyrics")) return@installHook
                    val headers = AppleReflection.field(
                        request,
                        target.runtimeMemberName(
                            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_HEADERS_FIELD
                        ),
                    )
                    ProviderLogger.debug(
                        "Lyrics HTTP network request: url=$url, " +
                            "headers=${summarizeHeaders(headers, response = false, target)}"
                    )
                },
                after = { _, result ->
                    val response = result ?: return@installHook
                    val request = AppleReflection.field(
                        response,
                        target.runtimeMemberName(
                            AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_REQUEST_FIELD
                        ),
                    ) ?: return@installHook
                    val url = AppleReflection.field(
                        request,
                        target.runtimeMemberName(
                            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD
                        ),
                    )?.toString().orEmpty()
                    if (!url.contains("/syllable-lyrics")) return@installHook
                    val code = AppleReflection.intField(
                        response,
                        target.runtimeMemberName(
                            AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_STATUS_FIELD
                        ),
                    )
                    val headers = AppleReflection.field(
                        response,
                        target.runtimeMemberName(
                            AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_HEADERS_FIELD
                        ),
                    )
                    ProviderLogger.debug(
                        "Lyrics HTTP network response: url=$url, code=$code, " +
                            "headers=${summarizeHeaders(headers, response = true, target)}"
                    )
                },
            )
        }.onFailure { ProviderLogger.error("Lyrics HTTP network Hook 安装失败", it) }
    }

    private fun sensitiveSummary(value: Any?): String {
        if (value == null) return "null"
        val text = value.toString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return "len=${text.length},sha256=$digest"
    }

    private fun summarizeHeaders(
        headers: Any?,
        response: Boolean,
        target: AppleMusicHookTarget,
    ): List<String> {
        if (headers == null) return emptyList()
        val values = AppleReflection.field(
            headers,
            target.runtimeMemberName(AppleMusicRuntimeMember.CONTENT_HTTP_HEADERS_VALUES_FIELD),
        ) as? Array<*> ?: return emptyList()
        val safeNames = if (response) {
            setOf(
                "age", "cache-control", "content-length", "date", "etag", "expires",
                "last-modified", "via", "x-cache", "x-cache-hits",
            )
        } else {
            setOf(
                "accept", "accept-language", "cache-control", "content-type",
                "if-modified-since", "if-none-match", "pragma",
            )
        }
        return values.toList().chunked(2).mapNotNull { pair ->
            val name = pair.getOrNull(0)?.toString() ?: return@mapNotNull null
            val value = pair.getOrNull(1)?.toString().orEmpty()
            val rendered = if (name.lowercase() in safeNames) value else sensitiveSummary(value)
            "$name=$rendered"
        }
    }
}
