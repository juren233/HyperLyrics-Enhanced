package com.juren233.hyperlyricsenhanced.root.source

import kotlin.math.abs

internal object AppleCentralPositionPolicy {
    private const val DURATION_OVERRUN_TOLERANCE_MS = 2_000L
    private const val MEDIA_POSITION_DIVERGENCE_MS = 5_000L
    private const val MEDIA_REFERENCE_MAX_AGE_MS = 10_000L
    private const val DIRECT_REFERENCE_MAX_AGE_MS = 2_000L
    private const val SAME_TRACK_DURATION_TOLERANCE_MS = 2_000L

    enum class Reason {
        CENTRAL_ACCEPTED,
        EXPLICIT_SEEK_ACCEPTED,
        MEDIA_REPLACED_DIVERGENT,
        MEDIA_REPLACED_OUTSIDE_DURATION,
        DIRECT_PREFERRED,
        REJECTED_OUTSIDE_DURATION,
    }

    data class DirectReference(
        val songGeneration: Int,
        val position: Long,
        val observedAtMs: Long,
    )

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
        val directPosition: Long? = null,
    )

    fun resolve(
        centralPosition: Long,
        currentSongDuration: Long,
        currentSongGeneration: Int,
        mediaReference: MediaReference?,
        directReference: DirectReference? = null,
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
        val directPosition = directReference?.takeIf { reference ->
            reference.songGeneration == currentSongGeneration &&
                nowMs - reference.observedAtMs in 0L..DIRECT_REFERENCE_MAX_AGE_MS &&
                reference.position >= 0L
        }?.position
        val effectiveDuration = currentSongDuration.takeIf { it > 0L }
            ?: matchingReference?.duration?.takeIf { it > 0L }
            ?: 0L
        val centralOutsideDuration = effectiveDuration > 0L &&
            centralPosition > effectiveDuration + DURATION_OVERRUN_TOLERANCE_MS
        val validDirectPosition = directPosition?.takeIf { position ->
            effectiveDuration <= 0L ||
                position <= effectiveDuration + DURATION_OVERRUN_TOLERANCE_MS
        }

        if (!explicitSeek && validDirectPosition != null) {
            return Resolution(
                position = validDirectPosition,
                mediaPosition = mediaPosition,
                reason = Reason.DIRECT_PREFERRED,
                directPosition = validDirectPosition,
            )
        }

        if (centralOutsideDuration) {
            if (
                mediaPosition != null &&
                mediaPosition <= effectiveDuration + DURATION_OVERRUN_TOLERANCE_MS
            ) {
                return Resolution(
                    position = mediaPosition,
                    mediaPosition = mediaPosition,
                    reason = Reason.MEDIA_REPLACED_OUTSIDE_DURATION,
                    directPosition = directPosition,
                )
            }
            return Resolution(
                position = null,
                mediaPosition = mediaPosition,
                reason = Reason.REJECTED_OUTSIDE_DURATION,
                directPosition = directPosition,
            )
        }

        if (explicitSeek) {
            return Resolution(
                position = centralPosition,
                mediaPosition = mediaPosition,
                reason = Reason.EXPLICIT_SEEK_ACCEPTED,
                directPosition = directPosition,
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
                directPosition = directPosition,
            )
        }

        return Resolution(
            position = centralPosition,
            mediaPosition = mediaPosition,
            reason = Reason.CENTRAL_ACCEPTED,
            directPosition = directPosition,
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
