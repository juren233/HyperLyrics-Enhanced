/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.subscriber

import android.os.SharedMemory
import android.os.SystemClock
import android.os.Parcel
import android.system.OsConstants
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.central.json
import io.github.proify.lyricon.central.provider.player.ActivePlayerListener
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.subscriber.IActivePlayerListener
import io.github.proify.lyricon.subscriber.SubscriberInfo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal class ActivePlayerSubscription(
    subscriberInfo: SubscriberInfo
) : ActivePlayerListener {

    private val subscriberPackageName = subscriberInfo.packageName
    private val subscriberProcessName = subscriberInfo.processName

    @Volatile
    var remoteListener: IActivePlayerListener? = null

    var positionMemory: SharedMemory? = null
        private set

    private var positionBuffer: ByteBuffer? = null
    private var lastPositionDiagnosticAtMs = 0L

    init {
        initializePositionMemory(subscriberInfo)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        if (providerInfo == null) {
            remoteListener?.onActiveProviderChanged(null)
            return
        }

        val out = ByteArrayOutputStream()
        json.encodeToStream(providerInfo, out)
        remoteListener?.onActiveProviderChanged(out.toByteArray())
    }

    override fun onSongChanged(song: Song?) {
        val bytes = song?.let { json.encodeToString(it).toByteArray() } ?: byteArrayOf()
        remoteListener?.onSongChanged(bytes)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        remoteListener?.onPlaybackStateChanged(isPlaying)
    }

    override fun onPositionChanged(position: Long) {
        try {
            val buffer = positionBuffer
            buffer?.putLong(0, position)
            logPositionDiagnostic(
                position = position,
                bufferAvailable = buffer != null,
                storedPosition = buffer?.getLong(0),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Position write failed", e)
        }
    }

    override fun onSeekTo(position: Long) {
        remoteListener?.onSeekTo(position)
    }

    override fun onSendText(text: String?) {
        remoteListener?.onReceiveText(text)
    }

    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
        remoteListener?.onDisplayTranslationChanged(isDisplayTranslation)
    }

    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
        remoteListener?.onDisplayRomaChanged(isDisplayRoma)
    }

    /**
     * Returns a caller-owned shared-memory handle.
     *
     * The embedded Central and its subscriber both run inside SystemUI, so a local Binder call
     * would otherwise return this exact [SharedMemory] object. The subscriber SDK closes handles
     * that it owns while rebinding; handing it Central's object can therefore leave its read side
     * unmapped while Central's already-created write mapping keeps accepting new positions.
     * Parceling the handle mirrors cross-process Binder ownership and gives the caller an
     * independent descriptor to map and close.
     */
    fun acquirePositionMemory(): SharedMemory? {
        val memory = positionMemory ?: return null
        val parcel = Parcel.obtain()
        return try {
            memory.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            SharedMemory.CREATOR.createFromParcel(parcel).also { duplicate ->
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "[LyricPositionDiag] stage=shared_memory_acquire, " +
                            "subscriber=$subscriberPackageName/$subscriberProcessName, " +
                            "sourceIdentity=${System.identityHashCode(memory)}, " +
                            "duplicateIdentity=${System.identityHashCode(duplicate)}, " +
                            "sameObject=${memory === duplicate}"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Position memory duplication failed", t)
            null
        } finally {
            parcel.recycle()
        }
    }

    fun close() {
        positionBuffer = null
        positionMemory?.close()
        positionMemory = null
        remoteListener = null
    }

    private fun initializePositionMemory(info: SubscriberInfo) {
        try {
            val hashHex = Integer.toHexString("${info.packageName}/${info.processName}".hashCode())
            positionMemory =
                SharedMemory.create("lyricon_subscriber_pos_$hashHex", Long.SIZE_BYTES).apply {
                    setProtect(OsConstants.PROT_READ or OsConstants.PROT_WRITE)
                    positionBuffer = mapReadWrite()
                }
        } catch (t: Throwable) {
            Log.e(TAG, "SharedMemory mapping failed", t)
        }
    }

    private fun logPositionDiagnostic(
        position: Long,
        bufferAvailable: Boolean,
        storedPosition: Long?,
    ) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPositionDiagnosticAtMs < POSITION_DIAGNOSTIC_INTERVAL_MS) return
        lastPositionDiagnosticAtMs = now
        Log.i(
            TAG,
            "[LyricPositionDiag] stage=shared_memory_write, " +
                "subscriber=$subscriberPackageName/$subscriberProcessName, " +
                "position=$position, stored=$storedPosition, " +
                "bufferAvailable=$bufferAvailable, listenerAvailable=${remoteListener != null}"
        )
    }

    private companion object {
        private const val TAG = "ActivePlayerSubscription"
        private const val POSITION_DIAGNOSTIC_INTERVAL_MS = 5_000L
    }
}
