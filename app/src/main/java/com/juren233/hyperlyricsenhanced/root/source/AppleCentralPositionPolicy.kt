package com.juren233.hyperlyricsenhanced.root.source

import kotlin.math.abs

internal object AppleCentralPositionPolicy {
    private const val DURATION_OVERRUN_TOLERANCE_MS = 2_000L
    private const val MEDIA_POSITION_DIVERGENCE_MS = 5_000L
    private const val MEDIA_REFERENCE_MAX_AGE_MS = 10_000L
    private const val SAME_TRACK_DURATION_TOLERANCE_MS = 2_000L

    enum class Reason {
        CENTRAL_ACCEPTED,
        EXPLICIT_SEEK_ACCEPTED,
        MEDIA_REPLACED_DIVERGENT,
        MEDIA_REPLACED_OUTSIDE_DURATION,
        REJECTED_OUTSIDE_DURATION,
    }

    data class MediaReference(
        val songGeneration: Int,
        val title: String,
        val artist: String,
        val duration: Long,
        val position: Long,
        val isPlaying: Boolean,
        val playbackSpeed: Float,
        val observedAtMs: Long,
    ) {
        fun estimatedPosition(nowMs: Long): Long {
            val elapsedMs = (nowMs - observedAtMs).coerceAtLeast(0L)
            val estimated = if (isPlaying && playbackSpeed > 0f) {
                position + (elapsedMs * playbackSpeed).toLong()
            } else {
                position
            }
            return estimated.coerceAtLeast(0L).let { value ->
                if (duration > 0L) value.coerceAtMost(duration) else value
            }
        }
    }

    data class Resolution(
        val position: Long?,
        val mediaPosition: Long?,
        val reason: Reason,
    )

    fun resolve(
        centralPosition: Long,
        currentSongDuration: Long,
        currentSongGeneration: Int,
        mediaReference: MediaReference?,
        providerDelayMs: Int,
        nowMs: Long,
        explicitSeek: Boolean,
    ): Resolution {
        val matchingReference = mediaReference?.takeIf { reference ->
            reference.songGeneration == currentSongGeneration &&
                nowMs - reference.observedAtMs in 0L..MEDIA_REFERENCE_MAX_AGE_MS &&
                reference.position >= 0L
        }
        val mediaPosition = matchingReference
            ?.estimatedPosition(nowMs)
            ?.minus(providerDelayMs.toLong())
            ?.coerceAtLeast(0L)
        val effectiveDuration = currentSongDuration.takeIf { it > 0L }
            ?: matchingReference?.duration?.takeIf { it > 0L }
            ?: 0L
        val centralOutsideDuration = effectiveDuration > 0L &&
            centralPosition > effectiveDuration + DURATION_OVERRUN_TOLERANCE_MS

        if (centralOutsideDuration) {
            if (
                mediaPosition != null &&
                mediaPosition <= effectiveDuration + DURATION_OVERRUN_TOLERANCE_MS
            ) {
                return Resolution(
                    position = mediaPosition,
                    mediaPosition = mediaPosition,
                    reason = Reason.MEDIA_REPLACED_OUTSIDE_DURATION,
                )
            }
            return Resolution(
                position = null,
                mediaPosition = mediaPosition,
                reason = Reason.REJECTED_OUTSIDE_DURATION,
            )
        }

        if (explicitSeek) {
            return Resolution(
                position = centralPosition,
                mediaPosition = mediaPosition,
                reason = Reason.EXPLICIT_SEEK_ACCEPTED,
            )
        }

        if (
            mediaPosition != null &&
            abs(centralPosition - mediaPosition) > MEDIA_POSITION_DIVERGENCE_MS
        ) {
            return Resolution(
                position = mediaPosition,
                mediaPosition = mediaPosition,
                reason = Reason.MEDIA_REPLACED_DIVERGENT,
            )
        }

        return Resolution(
            position = centralPosition,
            mediaPosition = mediaPosition,
            reason = Reason.CENTRAL_ACCEPTED,
        )
    }

    fun matchesTrack(
        firstTitle: String?,
        firstArtist: String?,
        firstDuration: Long,
        secondTitle: String?,
        secondArtist: String?,
        secondDuration: Long,
    ): Boolean {
        if (normalize(firstTitle) != normalize(secondTitle)) return false
        if (normalize(firstArtist) != normalize(secondArtist)) return false
        return firstDuration <= 0L || secondDuration <= 0L ||
            abs(firstDuration - secondDuration) <= SAME_TRACK_DURATION_TOLERANCE_MS
    }

    fun restorablePosition(previousPosition: Long, resolution: Resolution): Long =
        resolution.position ?: previousPosition

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase()
}
