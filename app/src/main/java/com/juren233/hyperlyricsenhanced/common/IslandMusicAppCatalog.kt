/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog

/** Existing music-app targets that can show the Super Island album-cover customization. */
object IslandMusicAppCatalog {
    data class App(
        val packageName: String,
        val displayName: String,
    )

    val apps: List<App> = buildList {
        add(
            App(
                packageName = OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME,
                displayName = "Apple Music",
            )
        )
        OfficialProviderCatalog.definitions.forEach { definition ->
            definition.targetPackages.forEach { packageName ->
                add(
                    App(
                        packageName = packageName,
                        displayName = definition.displayNameForPackage(packageName),
                    )
                )
            }
        }
    }.distinctBy(App::packageName)

    val packageNames: Set<String> = apps.mapTo(linkedSetOf(), App::packageName)

    fun isSupported(packageName: String?): Boolean = packageName in packageNames
}
