/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.common.net

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.LogManager

/** Builds a client owned by this module generation; see [ModuleHttpClients]. */
internal fun OkHttpClient.Builder.buildOwned(): OkHttpClient = ModuleHttpClients.build(this)

/**
 * A client that puts on the wire what Android's `HttpURLConnection` did for the call sites moved
 * off it: HTTP/1.1 only, no redirect that switches between http and https, no write timeout. A
 * call site that set no User-Agent sends [platformUserAgent], as `HttpURLConnection` did.
 *
 * Those call sites ran on this generation's coroutine workers, and the platform connection pool
 * thread they started inherited the worker's context ClassLoader, i.e. this generation's loader,
 * for as long as the pool lived. A module-owned client is released by [ModuleHttpClients].
 */
internal fun httpUrlConnectionEquivalent(connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient.Builder =
    OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .followSslRedirects(false)
        .protocols(listOf(Protocol.HTTP_1_1))

/**
 * The User-Agent `HttpURLConnection` sends when none is set: `http.agent` with anything but
 * printable ASCII replaced by `?` (AOSP `HttpURLConnectionImpl.defaultUserAgent`). Null when the
 * property is unset; the platform then sent its own OkHttp version, so the caller sets nothing.
 */
internal fun platformUserAgent(): String? =
    System.getProperty("http.agent")?.let { agent ->
        buildString { agent.codePoints().forEach { appendCodePoint(if (it in 0x20..0x7e) it else '?'.code) } }
    }

/** Sets [platformUserAgent] unless the platform would have sent none of its own. */
internal fun Request.Builder.withPlatformUserAgent(): Request.Builder =
    apply { platformUserAgent()?.let { header("User-Agent", it) } }

/**
 * Every [OkHttpClient] this module generation builds, so a hot reload can release what they own.
 *
 * OkHttp is bundled in the module, so each generation has its own TaskRunner threads, connection
 * pools and HTTP/2 reader threads, all reachable from GC roots while a pooled connection is open
 * (idle ones stay up to the pool's five-minute keep-alive). Its Android logging also adds this
 * generation's `AndroidLogHandler` to process-global `java.util.logging` loggers and never removes it.
 */
internal object ModuleHttpClients {
    private val clients = Collections.newSetFromMap(IdentityHashMap<OkHttpClient, Boolean>())

    /** Use instead of [OkHttpClient.Builder.build] for every client this module creates. */
    fun build(builder: OkHttpClient.Builder): OkHttpClient =
        builder.build().also { client -> synchronized(clients) { clients += client } }

    /**
     * Retiring generation. Closes every idle pooled connection now; a connection still serving an
     * asynchronous call is closed as soon as that client's dispatcher has no running call left.
     * Synchronous calls run on [com.juren233.hyperlyricsenhanced.common.coroutines.ModuleDispatchers],
     * which keep running in-flight work to its end: [evictIdleConnections] runs after each of their
     * tasks, closing what such a call returned to the pool. Then detaches this generation's handlers
     * from the global JUL loggers.
     */
    fun releaseForReload() {
        val owned = synchronized(clients) { clients.toList() }
        owned.forEach { client ->
            client.dispatcher.idleCallback = Runnable { client.connectionPool.evictAll() }
            client.connectionPool.evictAll()
        }
        removeOwnLogHandlers()
    }

    /** Closes the idle pooled connections of every client; connections in use are left alone. */
    fun evictIdleConnections() {
        val owned = synchronized(clients) { clients.toList() }
        owned.forEach { it.connectionPool.evictAll() }
    }

    internal fun removeOwnLogHandlers(loader: ClassLoader? = ModuleHttpClients::class.java.classLoader) {
        val manager = LogManager.getLogManager()
        val names = manager.loggerNames
        while (names.hasMoreElements()) {
            val logger = manager.getLogger(names.nextElement()) ?: continue
            logger.handlers
                .filter { it.javaClass.classLoader === loader }
                .forEach(logger::removeHandler)
        }
    }
}
