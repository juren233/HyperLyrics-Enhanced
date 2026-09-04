/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

internal enum class IslandExpandedMediaLightBackgroundMode {
    LEGACY_BLUR,
    NOTIFICATION_GLASS,
    DRAWABLE_FALLBACK,
}

/**
 * Selects the visible backing for the expanded island's forced-light theme.
 *
 * OS4's bionics path must mirror the notification media glass effect. The opaque drawable is only
 * a last-resort fallback when the platform glass/blur APIs are unavailable, never the OS4 primary
 * path. Older systems keep their original classic blur path when it is usable.
 */
internal object IslandExpandedMediaLightBackgroundPolicy {
    fun select(
        hasBionicsMaterialApi: Boolean,
        hasNotificationGlassApi: Boolean,
        blurOpened: Boolean,
        attached: Boolean,
    ): IslandExpandedMediaLightBackgroundMode {
        return when {
            hasBionicsMaterialApi && hasNotificationGlassApi && blurOpened ->
                IslandExpandedMediaLightBackgroundMode.NOTIFICATION_GLASS

            !hasBionicsMaterialApi && blurOpened && attached ->
                IslandExpandedMediaLightBackgroundMode.LEGACY_BLUR

            else -> IslandExpandedMediaLightBackgroundMode.DRAWABLE_FALLBACK
        }
    }
}
