/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import io.github.proify.lyricon.provider.ProviderInfo

internal enum class ProviderSourcePriority(val rank: Int) {
    LEGACY_APK(0),
    OFFICIAL_PLUGIN(1),
    BUILT_IN(2),
}

internal object ProviderSourcePriorityResolver {
    fun resolve(providerInfo: ProviderInfo): ProviderSourcePriority =
        resolve(providerInfo.providerPackageName, providerInfo.playerPackageName)

    fun resolve(
        providerPackageName: String,
        playerPackageName: String,
    ): ProviderSourcePriority = when {
        providerPackageName == OfficialProviderCatalog.CORE_PACKAGE_NAME &&
            playerPackageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME ->
            ProviderSourcePriority.BUILT_IN
        OfficialProviderCatalog.isOfficialProviderPair(providerPackageName, playerPackageName) ->
            ProviderSourcePriority.OFFICIAL_PLUGIN
        else -> ProviderSourcePriority.LEGACY_APK
    }
}
