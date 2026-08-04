package com.juren233.hyperlyricsenhanced.root.utils

import android.os.SystemClock
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only event chain for diagnosing delayed cover-color fallback in injected island views.
 * Events are emitted at INFO so they remain present when the user exports normal module logs.
 */
internal object CoverColorDiagnostics {
    private const val TAG = "CoverColorDiagnostic"
    private const val HEARTBEAT_INTERVAL_MS = 5_000L
    private const val MAX_TEXT_LENGTH = 160
    private val sequence = AtomicLong()
    private val styleStates = WeakHashMap<View, StyleState>()
    private val edgeProgressStates = WeakHashMap<View, EdgeProgressState>()

    internal data class StyleInput(
        val force: Boolean,
        val mode: Int,
        val songVersion: Int,
        val lyricSongId: String?,
        val lyricTitle: String?,
        val lyricArtist: String?,
        val packageName: String,
        val mediaTitle: String,
        val mediaArtist: String,
        val mediaAlbum: String,
        val mediaKey: String,
        val artworkState: String,
        val artworkDescription: String,
        val signature: String,
        val previousSignature: String?
    )

    private data class StyleState(
        val mediaKey: String,
        val resolution: LyricStyleHelper.ColorResolution,
        val appliedAtMs: Long,
        var lastHeartbeatAtMs: Long
    )

    private data class EdgeProgressState(
        val fingerprint: String,
        val mediaKey: String?,
        val usesDefaultColors: Boolean,
        val paletteSource: CoverColorHelper.PaletteSource?,
        val fallbackReason: String?,
        val appliedAtMs: Long,
        var lastHeartbeatAtMs: Long
    )

    fun logMediaKeyChange(
        source: String,
        previousMediaKey: String?,
        mediaKey: String,
        previousCachedKey: String?,
        hadActivePalette: Boolean,
        keyedCacheSize: Int
    ) {
        if (!BuildConfig.DEBUG) return
        HookLogger.i(
            TAG,
            event("media_key_change") +
                " source=${safe(source)}" +
                " previous=${key(previousMediaKey)}" +
                " current=${key(mediaKey)}" +
                " previousCached=${key(previousCachedKey)}" +
                " hadActivePalette=$hadActivePalette" +
                " keyedCacheSize=$keyedCacheSize"
        )
    }

    fun logStyleApplied(
        view: View,
        input: StyleInput,
        resolution: LyricStyleHelper.ColorResolution
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val previous = synchronized(styleStates) {
            styleStates.put(
                view,
                StyleState(
                    mediaKey = input.mediaKey,
                    resolution = resolution,
                    appliedAtMs = now,
                    lastHeartbeatAtMs = now
                )
            )
        }
        val target = target(view)
        HookLogger.i(
            TAG,
            event("style_input") +
                " target=$target" +
                " force=${input.force}" +
                " mode=${input.mode}" +
                " songVersion=${input.songVersion}" +
                " lyricSongId=${quoted(input.lyricSongId)}" +
                " lyricTitle=${quoted(input.lyricTitle)}" +
                " lyricArtist=${quoted(input.lyricArtist)}" +
                " package=${quoted(input.packageName)}" +
                " mediaTitle=${quoted(input.mediaTitle)}" +
                " mediaArtist=${quoted(input.mediaArtist)}" +
                " mediaAlbum=${quoted(input.mediaAlbum)}" +
                " mediaKey=${key(input.mediaKey)}" +
                " artworkState=${safe(input.artworkState)}" +
                " artwork=${safe(input.artworkDescription)}" +
                " signature=${hash(input.signature)}" +
                " previousSignature=${hash(input.previousSignature)}"
        )
        HookLogger.i(
            TAG,
            event("style_apply") +
                " target=$target" +
                " coverEnabled=${resolution.useCoverColor}" +
                " coverGradient=${resolution.useCoverGradient}" +
                " paletteSource=${resolution.paletteSource?.name ?: "NONE"}" +
                " fallback=${resolution.fallbackReason?.name ?: "NONE"}" +
                " requested=${key(resolution.requestedKey)}" +
                " resolved=${key(resolution.resolvedKey)}" +
                " artworkSignature=${resolution.artworkSignature?.toUInt()?.toString(16) ?: "none"}" +
                " primary=${colors(resolution.primaryColors)}" +
                " background=${colors(resolution.backgroundColors)}" +
                " highlight=${colors(resolution.highlightColors)}"
        )

        val transition = classifyTransition(
            previousUsesDefault = previous?.resolution?.usesDefaultColors,
            usesDefault = resolution.usesDefaultColors,
            previousMediaKey = previous?.mediaKey,
            mediaKey = input.mediaKey
        )
        if (transition != null) {
            HookLogger.w(
                TAG,
                event(transition) +
                    " surface=island_text" +
                    " target=$target" +
                    " elapsedSincePreviousMs=${previous?.let { now - it.appliedAtMs } ?: -1L}" +
                    " previousSource=${previous?.resolution?.paletteSource?.name ?: "NONE"}" +
                    " currentSource=${resolution.paletteSource?.name ?: "NONE"}" +
                    " previousFallback=${previous?.resolution?.fallbackReason?.name ?: "NONE"}" +
                    " currentFallback=${resolution.fallbackReason?.name ?: "NONE"}" +
                    " previousKey=${key(previous?.mediaKey)}" +
                    " currentKey=${key(input.mediaKey)}"
            )
        }
    }

    fun logStyleUnchanged(view: View, input: StyleInput) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val state = synchronized(styleStates) {
            val current = styleStates[view] ?: return
            if (now - current.lastHeartbeatAtMs < HEARTBEAT_INTERVAL_MS) return
            current.lastHeartbeatAtMs = now
            current
        }
        HookLogger.i(
            TAG,
            event("style_unchanged") +
                " target=${target(view)}" +
                " elapsedSinceApplyMs=${now - state.appliedAtMs}" +
                " trackedSource=${state.resolution.paletteSource?.name ?: "NONE"}" +
                " trackedFallback=${state.resolution.fallbackReason?.name ?: "NONE"}" +
                " trackedKey=${key(state.mediaKey)}" +
                " currentKey=${key(input.mediaKey)}" +
                " artworkState=${safe(input.artworkState)}" +
                " signature=${hash(input.signature)}"
        )
    }

    fun logEdgeProgress(
        rootView: View,
        packageName: String,
        coverEnabled: Boolean,
        coverGradient: Boolean,
        mediaKey: String?,
        artworkState: String,
        paletteSource: CoverColorHelper.PaletteSource?,
        fallbackReason: String?,
        requestedKey: String?,
        resolvedKey: String?,
        artworkSignature: Int?,
        progressStart: Int,
        progressEnd: Int,
        track: Int
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val fingerprint = listOf(
            packageName,
            coverEnabled,
            coverGradient,
            mediaKey,
            artworkState,
            paletteSource,
            fallbackReason,
            requestedKey,
            resolvedKey,
            artworkSignature,
            progressStart,
            progressEnd,
            track
        ).joinToString("|")
        val usesDefaultColors = fallbackReason != null
        var changed = false
        val previous = synchronized(edgeProgressStates) {
            val current = edgeProgressStates[rootView]
            if (current?.fingerprint == fingerprint) {
                if (now - current.lastHeartbeatAtMs < HEARTBEAT_INTERVAL_MS) return
                current.lastHeartbeatAtMs = now
                current
            } else {
                changed = true
                edgeProgressStates[rootView] = EdgeProgressState(
                    fingerprint = fingerprint,
                    mediaKey = mediaKey,
                    usesDefaultColors = usesDefaultColors,
                    paletteSource = paletteSource,
                    fallbackReason = fallbackReason,
                    appliedAtMs = now,
                    lastHeartbeatAtMs = now
                )
                current
            }
        }
        HookLogger.i(
            TAG,
            event(if (changed) "edge_progress_apply" else "edge_progress_unchanged") +
                " target=${target(rootView)}" +
                " elapsedSinceApplyMs=${if (changed) 0L else now - previous!!.appliedAtMs}" +
                " package=${quoted(packageName)}" +
                " coverEnabled=$coverEnabled" +
                " coverGradient=$coverGradient" +
                " mediaKey=${key(mediaKey)}" +
                " artworkState=${safe(artworkState)}" +
                " paletteSource=${paletteSource?.name ?: "NONE"}" +
                " fallback=${fallbackReason ?: "NONE"}" +
                " requested=${key(requestedKey)}" +
                " resolved=${key(resolvedKey)}" +
                " artworkSignature=${artworkSignature?.toUInt()?.toString(16) ?: "none"}" +
                " progressStart=${color(progressStart)}" +
                " progressEnd=${color(progressEnd)}" +
                " track=${color(track)}"
        )
        if (changed) {
            val transition = classifyTransition(
                previousUsesDefault = previous?.usesDefaultColors,
                usesDefault = usesDefaultColors,
                previousMediaKey = previous?.mediaKey,
                mediaKey = mediaKey
            )
            if (transition != null) {
                HookLogger.w(
                    TAG,
                    event(transition) +
                        " surface=edge_progress" +
                        " target=${target(rootView)}" +
                        " elapsedSincePreviousMs=${previous?.let { now - it.appliedAtMs } ?: -1L}" +
                        " previousSource=${previous?.paletteSource?.name ?: "NONE"}" +
                        " currentSource=${paletteSource?.name ?: "NONE"}" +
                        " previousFallback=${previous?.fallbackReason ?: "NONE"}" +
                        " currentFallback=${fallbackReason ?: "NONE"}" +
                        " previousKey=${key(previous?.mediaKey)}" +
                        " currentKey=${key(mediaKey)}"
                )
            }
        }
    }

    internal fun classifyTransition(
        previousUsesDefault: Boolean?,
        usesDefault: Boolean,
        previousMediaKey: String?,
        mediaKey: String?
    ): String? {
        if (previousUsesDefault == null) return null
        if (!previousUsesDefault && usesDefault) {
            return if (previousMediaKey != mediaKey) {
                "cover_to_default_after_key_change"
            } else {
                "cover_to_default_same_key"
            }
        }
        if (previousUsesDefault && !usesDefault) return "default_to_cover"
        return null
    }

    private fun event(name: String): String = "seq=${sequence.incrementAndGet()} event=$name"

    private fun target(view: View): String {
        return "${safe(view.tag?.toString() ?: view.javaClass.simpleName)}@" +
            System.identityHashCode(view).toUInt().toString(16)
    }

    private fun key(value: String?): String {
        if (value == null) return "none"
        return "${hash(value)}:${quoted(value.replace('\u001F', '|'))}"
    }

    private fun hash(value: String?): String {
        return value?.hashCode()?.toUInt()?.toString(16) ?: "none"
    }

    private fun colors(values: IntArray): String {
        return values.joinToString(prefix = "[", postfix = "]") { color(it) }
    }

    private fun color(value: Int): String = "#" + value.toUInt().toString(16).padStart(8, '0')

    private fun quoted(value: String?): String {
        if (value == null) return "null"
        return "\"${safe(value)}\""
    }

    private fun safe(value: String): String {
        val compact = buildString(value.length.coerceAtMost(MAX_TEXT_LENGTH)) {
            value.forEach { char ->
                if (length >= MAX_TEXT_LENGTH) return@forEach
                append(
                    when (char) {
                        '\n', '\r', '\t' -> ' '
                        '"' -> '\''
                        else -> char
                    }
                )
            }
        }.trim()
        return if (value.length > MAX_TEXT_LENGTH) "$compact..." else compact
    }
}
