/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

/** Decides whether Apple Direct may fill a Central connection whose Song snapshot is still empty. */
internal object AppleDirectSongRecoveryPolicy {
    fun shouldAccept(
        activePlayerPackage: String?,
        activeProviderPackage: String?,
        centralSongAvailable: Boolean,
        appleMusicPackage: String,
        builtInProviderPackage: String,
    ): Boolean {
        if (activePlayerPackage == null) return true
        return activePlayerPackage == appleMusicPackage &&
            activeProviderPackage == builtInProviderPackage &&
            !centralSongAvailable
    }
}
