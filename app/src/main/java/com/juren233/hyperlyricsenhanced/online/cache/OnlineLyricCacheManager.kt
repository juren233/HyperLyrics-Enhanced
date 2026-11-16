/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.cache

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SharedMemory
import android.system.OsConstants
import com.juren233.hyperlyricsenhanced.common.bridge.sendBroadcastSharingIdentity
import com.juren233.hyperlyricsenhanced.online.source.ttml.CatalogPlatform
import com.juren233.hyperlyricsenhanced.online.source.ttml.TtmlCatalogSources
import com.juren233.hyperlyricsenhanced.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/** Everything the management page shows for one process's cache. */
@Serializable
data class OnlineLyricCacheSnapshot(
    val songs: List<OnlineLyricCacheSongSummary>,
    /** Bytes on disk: the database plus the TTML catalog files. */
    val totalBytes: Long,
)

/**
 * Reads and clears the online lyric cache of the calling process: the response database and the
 * TTML catalog files. Clearing a catalog lookup also removes its file and remembered miss.
 */
internal object OnlineLyricCacheManager {
    suspend fun snapshot(context: Context): OnlineLyricCacheSnapshot = withContext(Dispatchers.IO) {
        val songs = CachedSearchSource.cacheFor(context).summaries()
        val databaseBytes = CachedSearchSource.storeFor(context).databaseFiles.sumOf { it.length() }
        val catalogBytes = TtmlCatalogSources.all.sumOf { runCatching { it.cacheBytes(context) }.getOrDefault(0L) }
        OnlineLyricCacheSnapshot(songs = songs, totalBytes = databaseBytes + catalogBytes)
    }

    suspend fun delete(context: Context, selection: OnlineLyricCacheSelection) = withContext(Dispatchers.IO) {
        val refs = CachedSearchSource.cacheFor(context).delete(selection)
        clearCatalogFiles(context, refs)
        // Files written before the index existed are only reachable through a catalog-wide clear.
        if (selection.tags == null) {
            TtmlCatalogSources.all
                .filter { catalog -> selection.sources?.contains(catalog.source.name) != false }
                .forEach { catalog ->
                    runCatching { catalog.clearAll(context) }.onFailure { LogManager.w(TAG, "TTML 缓存清理失败: ${catalog.source}", it) }
                }
        }
        LogManager.i(TAG, "在线歌词缓存已清理: selection=$selection, catalogFiles=${refs.size}")
    }

    /** Applies a new 「缓存上限」 to this process's copy at once. */
    suspend fun trim(context: Context, maxSongs: Int) = withContext(Dispatchers.IO) {
        val refs = CachedSearchSource.cacheFor(context).trim(maxSongs)
        clearCatalogFiles(context, refs)
        LogManager.i(TAG, "在线歌词缓存已按上限裁剪: maxSongs=$maxSongs, catalogFiles=${refs.size}")
    }

    /** Clears, in the background, the files of catalog lookups a trim after a store dropped. */
    fun clearTrimmedInBackground(context: Context, refs: List<CatalogCacheRef>) {
        val appContext = context.applicationContext
        backgroundScope.launch { clearCatalogFiles(appContext, refs) }
    }

    private suspend fun clearCatalogFiles(context: Context, refs: List<CatalogCacheRef>) {
        refs.forEach { ref ->
            val platform = CatalogPlatform.entries.firstOrNull { it.name == ref.platform } ?: return@forEach
            runCatching { TtmlCatalogSources.of(ref.source)?.clear(context, platform, ref.songId) }
                .onFailure { LogManager.w(TAG, "TTML 缓存清理失败: ${ref.source}/${ref.songId}", it) }
        }
    }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val TAG = "OnlineLyricCache"
}

/**
 * The app reads and clears the SystemUI copy of the cache through a broadcast that only the app
 * can send ([PERMISSION], signature level) and SystemUI answers through a [ResultReceiver].
 *
 * Neither direction may carry a payload that grows with the cache inline: [ResultReceiver.send]
 * is a oneway Binder call, limited to half the receiver's ~1 MB Binder buffer, and a Parcel
 * stores strings as UTF-16. The snapshot therefore travels in a read-only [SharedMemory]
 * (SELinux: `binder_call(appdomain, appdomain)` grants `fd use` between apps, and every app's
 * memfd is `appdomain_tmpfs`, which all apps may map), and a delete sends its tags in batches of
 * [DELETE_BATCH_TAGS] (an Intent may not carry file descriptors).
 */
object OnlineLyricCacheIpc {
    const val ACTION = "com.juren233.hyperlyricsenhanced.action.ONLINE_LYRIC_CACHE"
    const val PERMISSION = "com.juren233.hyperlyricsenhanced.permission.MANAGE_ONLINE_LYRIC_CACHE"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val APP_PACKAGE = "com.juren233.hyperlyricsenhanced"

    private const val EXTRA_OPERATION = "operation"
    private const val EXTRA_SELECTION = "selection"
    private const val EXTRA_RECEIVER = "receiver"
    private const val RESULT_SNAPSHOT = "snapshot_memory"
    private const val OPERATION_SNAPSHOT = "snapshot"
    private const val OPERATION_DELETE = "delete"
    private const val OPERATION_TRIM = "trim"
    private const val EXTRA_MAX_SONGS = "max_songs"
    private const val RESULT_OK = 1
    private const val TIMEOUT_MS = 5_000L
    private const val TAG = "OnlineLyricCacheIpc"

    /** Tags per delete broadcast: about 200 × 100 UTF-16 characters, far below the Binder limit. */
    internal const val DELETE_BATCH_TAGS = 200

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** SystemUI's snapshot, or null when SystemUI does not answer (module not loaded there). */
    suspend fun requestSnapshot(context: Context): OnlineLyricCacheSnapshot? {
        val result = request(context, OPERATION_SNAPSHOT, null) ?: return null
        @Suppress("DEPRECATION")
        val memory = result.getParcelable<SharedMemory>(RESULT_SNAPSHOT) ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { readSnapshot(memory) }
                .onFailure { LogManager.w(TAG, "SystemUI 缓存快照读取失败", it) }
                .getOrNull()
        }
    }

    /** True once SystemUI has applied the new 「缓存上限」 to its copy. */
    suspend fun requestTrim(context: Context, maxSongs: Int): Boolean =
        request(context, OPERATION_TRIM, null) { putExtra(EXTRA_MAX_SONGS, maxSongs) } != null

    /** True once SystemUI confirms every batch of the clear. */
    suspend fun requestDelete(context: Context, selection: OnlineLyricCacheSelection): Boolean =
        deleteBatches(selection).all { batch -> request(context, OPERATION_DELETE, batch) != null }

    /** [selection] split so each broadcast carries at most [DELETE_BATCH_TAGS] tags. */
    internal fun deleteBatches(selection: OnlineLyricCacheSelection): List<OnlineLyricCacheSelection> {
        val tags = selection.tags ?: return listOf(selection)
        if (tags.size <= DELETE_BATCH_TAGS) return listOf(selection)
        return tags.chunked(DELETE_BATCH_TAGS).map { chunk -> selection.copy(tags = chunk.toSet()) }
    }

    /** The wire form of a snapshot inside the shared memory region: UTF-8 JSON. */
    internal fun encodeSnapshot(snapshot: OnlineLyricCacheSnapshot): ByteArray =
        json.encodeToString(OnlineLyricCacheSnapshot.serializer(), snapshot).encodeToByteArray()

    internal fun decodeSnapshot(bytes: ByteArray): OnlineLyricCacheSnapshot =
        json.decodeFromString(OnlineLyricCacheSnapshot.serializer(), bytes.decodeToString())

    /** [encodeSnapshot] in a read-only shared memory region; the caller closes it. */
    internal fun writeSnapshot(snapshot: OnlineLyricCacheSnapshot): SharedMemory {
        val bytes = encodeSnapshot(snapshot)
        val memory = SharedMemory.create(RESULT_SNAPSHOT, bytes.size)
        try {
            val buffer = memory.mapReadWrite()
            try {
                buffer.put(bytes)
            } finally {
                SharedMemory.unmap(buffer)
            }
            memory.setProtect(OsConstants.PROT_READ)
            return memory
        } catch (error: Throwable) {
            memory.close()
            throw error
        }
    }

    /** Reads and closes a region written by [writeSnapshot]. */
    internal fun readSnapshot(memory: SharedMemory): OnlineLyricCacheSnapshot = memory.use {
        val buffer = it.mapReadOnly()
        val bytes = try {
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            SharedMemory.unmap(buffer)
        }
        decodeSnapshot(bytes)
    }

    private suspend fun request(
        context: Context,
        operation: String,
        selection: OnlineLyricCacheSelection?,
        extras: Intent.() -> Unit = {},
    ): Bundle? = withTimeoutOrNull(TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (continuation.isActive) {
                        continuation.resume(if (resultCode == RESULT_OK) resultData ?: Bundle() else null)
                    }
                }
            }
            val intent = Intent(ACTION)
                .setPackage(SYSTEM_UI_PACKAGE)
                .putExtra(EXTRA_OPERATION, operation)
                .putExtra(EXTRA_RECEIVER, receiver.forIpc())
                .apply(extras)
            selection?.let {
                intent.putExtra(EXTRA_SELECTION, json.encodeToString(OnlineLyricCacheSelection.serializer(), it))
            }
            runCatching { context.sendBroadcastSharingIdentity(intent) }.onFailure { error ->
                LogManager.w(TAG, "缓存请求发送失败: $operation", error)
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /**
     * SystemUI side. Registered with [PERMISSION] as the broadcast permission, so only the app can
     * send it. On API 34+ the app shares its identity ([sendBroadcastSharingIdentity]) and the sender
     * UID must also be the app's; on API 33 the platform does not report it and the permission alone decides.
     */
    fun createReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val expectedUid = runCatching {
                context.packageManager.getApplicationInfo(APP_PACKAGE, 0).uid
            }.getOrDefault(-1)
            val senderUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromUid else -1
            if (expectedUid >= 0 && senderUid >= 0 && senderUid != expectedUid) {
                LogManager.w(TAG, "拒绝非 HyperLyrics 缓存请求: senderUid=$senderUid")
                return
            }
            @Suppress("DEPRECATION")
            val reply = intent.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER) ?: return
            val operation = intent.getStringExtra(EXTRA_OPERATION)
            val selection = intent.getStringExtra(EXTRA_SELECTION)?.let { payload ->
                runCatching { json.decodeFromString(OnlineLyricCacheSelection.serializer(), payload) }.getOrNull()
            }
            val maxSongs = intent.getIntExtra(EXTRA_MAX_SONGS, 0)
            val appContext = context.applicationContext
            scope.launch {
                var memory: SharedMemory? = null
                val result = runCatching {
                    when (operation) {
                        OPERATION_SNAPSHOT -> Bundle().apply {
                            memory = writeSnapshot(OnlineLyricCacheManager.snapshot(appContext))
                            putParcelable(RESULT_SNAPSHOT, memory)
                        }
                        OPERATION_DELETE -> selection?.let {
                            OnlineLyricCacheManager.delete(appContext, it)
                            Bundle()
                        }
                        OPERATION_TRIM -> maxSongs.takeIf { it > 0 }?.let {
                            OnlineLyricCacheManager.trim(appContext, it)
                            Bundle()
                        }
                        else -> null
                    }
                }.onFailure { LogManager.w(TAG, "缓存请求处理失败: $operation", it) }.getOrNull()
                try {
                    reply.send(if (result != null) RESULT_OK else 0, result)
                } finally {
                    // The Binder driver duplicated the descriptor into the app while sending.
                    memory?.close()
                }
            }
        }
    }

    /**
     * A [ResultReceiver] subclass cannot be unparcelled in another process; sending a plain one
     * that forwards to it keeps only the framework class on the wire.
     */
    private fun ResultReceiver.forIpc(): ResultReceiver {
        val parcel = android.os.Parcel.obtain()
        return try {
            writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
