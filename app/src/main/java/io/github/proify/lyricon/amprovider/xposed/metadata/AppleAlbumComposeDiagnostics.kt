/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap

/** Constructed only in Debug: inspect the first native input and retained UI row independently. */
internal class AppleAlbumComposeDiagnostics(private val host: AppleArtistSurfaceHost) {
    private val seen = WeakIdentityMap<Any, MutableSet<String>>()

    @Synchronized
    fun once(owner: Any, key: String, event: String, details: () -> String) {
        if (!BuildConfig.DEBUG) return
        val keys = seen[owner] ?: mutableSetOf<String>().also { seen[owner] = it }
        if (keys.size >= 16 || !keys.add(key)) return
        runCatching { host.logMetadataIdentity(event, details()) }
            .onFailure { ProviderLogger.diagnostic("Apple Music 专辑链路诊断失败: event=$event, error=${it.javaClass.simpleName}") }
    }
}
