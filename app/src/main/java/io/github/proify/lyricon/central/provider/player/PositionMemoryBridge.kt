/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.proify.lyricon.provider.ProviderInfo
import java.nio.ByteBuffer

/**
 * The 8-byte memory a provider writes its playback position into. The provider maps it once, when
 * it binds, and keeps writing to that mapping; a hot reload therefore hands this exact memory to the
 * next generation ([adopted]) instead of creating a new one the provider would never see.
 */
internal class PositionMemoryBridge(
    private val providerInfo: ProviderInfo,
    adopted: SharedMemory? = null,
) {

    private var readBuffer: ByteBuffer? = null
    @Volatile
    private var lastReadFailure: String? = null

    var sharedMemory: SharedMemory? = null
        private set

    init {
        if (adopted != null) adopt(adopted) else initialize()
    }

    fun readPosition(): Long {
        val buffer = readBuffer
        if (buffer == null) {
            lastReadFailure = "buffer_unavailable"
            return 0L
        }
        return try {
            buffer.getLong(POSITION_OFFSET).coerceAtLeast(0L).also {
                lastReadFailure = null
            }
        } catch (error: Throwable) {
            lastReadFailure = "${error.javaClass.simpleName}:${error.message}"
            0L
        }
    }

    fun diagnosticSummary(): String =
        "memoryAvailable=${sharedMemory != null}, bufferAvailable=${readBuffer != null}, " +
            "lastReadFailure=${lastReadFailure ?: "none"}"

    /** With [keepMemory] only this mapping is released; the memory itself passes to its next owner. */
    fun close(keepMemory: Boolean = false) {
        readBuffer?.let { runCatching { SharedMemory.unmap(it) } }
        if (!keepMemory) sharedMemory?.close()
        readBuffer = null
        sharedMemory = null
    }

    private fun adopt(memory: SharedMemory) {
        try {
            readBuffer = memory.mapReadOnly()
            sharedMemory = memory
        } catch (t: Throwable) {
            // A fresh memory would not help: the provider keeps writing to the inherited one.
            memory.close()
            lastReadFailure = "adopt_failed:${t.javaClass.simpleName}"
            Log.e(TAG, "Inherited SharedMemory could not be mapped", t)
        }
    }

    private fun initialize() {
        try {
            val hashHex = Integer.toHexString(
                "${providerInfo.providerPackageName}/${providerInfo.playerPackageName}/${providerInfo.processName}".hashCode()
            )
            sharedMemory = SharedMemory.create("lyricon_pos_$hashHex", Long.SIZE_BYTES).apply {
                setProtect(OsConstants.PROT_READ or OsConstants.PROT_WRITE)
                readBuffer = mapReadOnly()
            }
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "[LyricPositionDiag] stage=provider_shared_memory_init, result=success, " +
                        "provider=${providerInfo.providerPackageName}, " +
                        "player=${providerInfo.playerPackageName}, process=${providerInfo.processName}, " +
                        diagnosticSummary(),
                )
            }
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) {
                HookLogger.e(
                    TAG,
                    "[LyricPositionDiag] stage=provider_shared_memory_init, result=failed, " +
                        "provider=${providerInfo.providerPackageName}, " +
                        "player=${providerInfo.playerPackageName}, process=${providerInfo.processName}",
                    t,
                )
            } else {
                Log.e(TAG, "SharedMemory init failed", t)
            }
        }
    }

    private companion object {
        private const val TAG = "PositionMemoryBridge"
        private const val POSITION_OFFSET = 0
    }
}
