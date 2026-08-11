/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger

/**
 * Answers whether another application is verifiably producing media audio.
 *
 * A null result is intentionally different from false: Android can return an empty list while
 * audio is starting, and hidden framework fields may be inaccessible to reflection. Those cases
 * must preserve the existing Provider behaviour instead of stopping playback speculatively.
 */
internal fun interface ActiveAudioPlaybackMonitor {
    fun conflictFor(playerPackageName: String): Boolean?
}

/**
 * Reads actual audio output from the system process. Provider MediaSession state alone is not
 * sufficient because a paused app can leave a stale PLAYING state behind while another app plays.
 */
internal class SystemActiveAudioPlaybackMonitor(context: Context) : ActiveAudioPlaybackMonitor {

    private val applicationContext = context.applicationContext
    private val audioManager =
        applicationContext.getSystemService(AudioManager::class.java)
    private val packageManager = applicationContext.packageManager

    // These methods are hidden SDK/TEST-API methods in the original Android framework binary.
    // Keep the exact binary names here and fail closed to an unknown result if they disappear.
    private val clientUidMethod = runCatching {
        Class.forName(AUDIO_PLAYBACK_CONFIGURATION_CLASS)
            .getDeclaredMethod("getClientUid")
            .apply { isAccessible = true }
    }.getOrNull()
    private val playerStateMethod = runCatching {
        Class.forName(AUDIO_PLAYBACK_CONFIGURATION_CLASS)
            .getDeclaredMethod("getPlayerState")
            .apply { isAccessible = true }
    }.getOrNull()

    @Volatile
    private var lastDiagnostic: Diagnostic? = null
    @Volatile
    private var cachedConflict: CachedConflict? = null

    override fun conflictFor(playerPackageName: String): Boolean? {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedConflict?.takeIf {
            it.playerPackageName == playerPackageName && now - it.readAtMs < CACHE_DURATION_MS
        }?.let { return it.result }

        val result = readConflict(playerPackageName)
        cachedConflict = CachedConflict(playerPackageName, result, now)
        return result
    }

    private fun readConflict(playerPackageName: String): Boolean? {
        val manager = audioManager ?: return unknown(playerPackageName, "audio_manager_unavailable")
        val uidMethod = clientUidMethod
            ?: return unknown(playerPackageName, "client_uid_method_unavailable")
        val stateMethod = playerStateMethod
            ?: return unknown(playerPackageName, "player_state_method_unavailable")

        val configurations = runCatching {
            manager.activePlaybackConfigurations
        }.getOrElse {
            return unknown(
                playerPackageName,
                "active_configurations_failed:${it.javaClass.simpleName}",
            )
        }

        val activePackages = LinkedHashSet<String>()
        var hasRelevantStartedPlayback = false
        var hasUnresolvedStartedPlayback = false
        for (configuration in configurations) {
            val usage = runCatching { configuration.audioAttributes.usage }
                .getOrElse {
                    return unknown(
                        playerPackageName,
                        "audio_attributes_failed:${it.javaClass.simpleName}",
                    )
                }
            if (usage != AudioAttributes.USAGE_MEDIA && usage != AudioAttributes.USAGE_GAME) continue

            val state = runCatching { stateMethod.invoke(configuration) as? Int }
                .getOrNull() ?: return unknown(playerPackageName, "player_state_read_failed")
            if (state != PLAYER_STATE_STARTED) continue
            hasRelevantStartedPlayback = true

            val uid = runCatching { uidMethod.invoke(configuration) as? Int }
                .getOrNull() ?: return unknown(playerPackageName, "client_uid_read_failed")
            val packages = packageManager.getPackagesForUid(uid)
            if (packages.isNullOrEmpty()) {
                hasUnresolvedStartedPlayback = true
            } else {
                activePackages += packages
            }
        }

        val conflict = resolveActiveAudioConflict(
            playerPackageName = playerPackageName,
            activePackages = activePackages,
            hasRelevantStartedPlayback = hasRelevantStartedPlayback,
            hasUnresolvedStartedPlayback = hasUnresolvedStartedPlayback,
        ) ?: return unknown(
            playerPackageName = playerPackageName,
            reason = if (hasUnresolvedStartedPlayback) {
                "started_media_uid_unresolved"
            } else {
                "no_started_media_playback"
            },
        )
        diagnostic(
            playerPackageName = playerPackageName,
            result = conflict,
            activePackages = activePackages,
            reason = "started_media_playback",
        )
        return conflict
    }

    private fun unknown(playerPackageName: String, reason: String): Boolean? {
        diagnostic(
            playerPackageName = playerPackageName,
            result = null,
            activePackages = emptySet(),
            reason = reason,
        )
        return null
    }

    private fun diagnostic(
        playerPackageName: String?,
        result: Boolean?,
        activePackages: Set<String>,
        reason: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val next = Diagnostic(playerPackageName, result, activePackages, reason)
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastDiagnostic
        if (previous == next && now - lastDiagnosticAtMs < DIAGNOSTIC_INTERVAL_MS) return
        lastDiagnostic = next
        lastDiagnosticAtMs = now
        HookLogger.i(
            TAG,
            "[LyricPositionDiag] stage=active_audio_conflict, " +
                "player=$playerPackageName, result=$result, activePackages=${activePackages.sorted()}, " +
                "reason=$reason",
        )
    }

    @Volatile
    private var lastDiagnosticAtMs = 0L

    private data class Diagnostic(
        val playerPackageName: String?,
        val result: Boolean?,
        val activePackages: Set<String>,
        val reason: String,
    )

    private data class CachedConflict(
        val playerPackageName: String,
        val result: Boolean?,
        val readAtMs: Long,
    )

    private companion object {
        private const val TAG = "ActiveAudioPlayback"
        private const val AUDIO_PLAYBACK_CONFIGURATION_CLASS =
            "android.media.AudioPlaybackConfiguration"
        private const val PLAYER_STATE_STARTED = 2
        private const val CACHE_DURATION_MS = 100L
        private const val DIAGNOSTIC_INTERVAL_MS = 5_000L
    }
}

internal fun resolveActiveAudioConflict(
    playerPackageName: String,
    activePackages: Set<String>,
    hasRelevantStartedPlayback: Boolean,
    hasUnresolvedStartedPlayback: Boolean,
): Boolean? = when {
    !hasRelevantStartedPlayback -> null
    hasUnresolvedStartedPlayback -> null
    else -> playerPackageName !in activePackages
}
