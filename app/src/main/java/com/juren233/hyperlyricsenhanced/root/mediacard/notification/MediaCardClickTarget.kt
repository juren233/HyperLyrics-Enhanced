/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.mediacard.notification

internal data class MediaCardClickTarget<T>(
    val packageName: String?,
    val sessionToken: Any?,
    val target: T,
)

/** Reject another player/session and ambiguous transitional bindings; never guess a launcher. */
internal fun <T> selectMediaCardClickTarget(
    targets: List<MediaCardClickTarget<T>>,
    packageName: String,
    sessionToken: Any?,
): T? = targets.singleOrNull {
    it.packageName == packageName && it.sessionToken == sessionToken
}?.target
