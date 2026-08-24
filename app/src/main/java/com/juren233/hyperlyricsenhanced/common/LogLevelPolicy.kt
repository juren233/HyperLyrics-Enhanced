/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

object LogLevelPolicy {
    const val LEVEL_NORMAL = 0
    const val LEVEL_DEBUG = 1
    const val BUILD_KIND_DEBUG = "debug"
    const val BUILD_KIND_RELEASE = "release"

    fun buildKind(debugBuild: Boolean): String =
        if (debugBuild) BUILD_KIND_DEBUG else BUILD_KIND_RELEASE

    fun defaultLevel(debugBuild: Boolean): Int =
        if (debugBuild) LEVEL_DEBUG else LEVEL_NORMAL

    /**
     * A package replacement preserves SharedPreferences. When switching build types, use the
     * new build's default before the app process has had a chance to migrate the stored value.
     * Once the marker matches, preserve the user's explicit selection for that build type.
     */
    fun effectiveLevel(
        storedLevel: Int?,
        storedBuildKind: String?,
        debugBuild: Boolean,
    ): Int {
        val currentBuildKind = buildKind(debugBuild)
        if (storedBuildKind != currentBuildKind) return defaultLevel(debugBuild)
        return when (storedLevel) {
            LEVEL_DEBUG -> LEVEL_DEBUG
            LEVEL_NORMAL -> LEVEL_NORMAL
            else -> defaultLevel(debugBuild)
        }
    }
}
