/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.lyricon.central

internal object EmbeddedLyriconCentralPolicy {

    val knownStandalonePackages: Set<String> = setOf(
        "io.github.proify.lyricon.core",
        "io.github.proify.lyricon.app",
    )

    fun shouldStartImmediately(installedPackages: Set<String>): Boolean =
        knownStandalonePackages.none(installedPackages::contains)
}
