/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package io.github.proify.lyricon.amprovider.xposed

/** Main-thread state: the native player signal has no replay before its view subscribes. */
internal class ApplePlayerLaunchState<Request : Any> {
    var viewReady = false
    private var pending: Request? = null

    fun request(request: Request?) {
        pending = request
    }

    fun cancel(): Request? = pending.also { pending = null }

    fun dispatch(enabled: Boolean, expand: (Request) -> Boolean): Boolean {
        if (!enabled) {
            cancel()
            return false
        }
        val request = pending ?: return false
        if (!viewReady || !expand(request)) return false
        // A callback may have supplied a newer request while the old one was handled.
        if (pending === request) pending = null
        return true
    }
}

internal fun shouldRepairAppleMediaSessionLaunch(
    serviceContext: Boolean,
    requestCode: Int,
    packageName: String?,
    className: String?,
    legacyClassName: String,
): Boolean = serviceContext && requestCode == 0 &&
    packageName == Constants.APPLE_MUSIC_PACKAGE_NAME && className == legacyClassName
