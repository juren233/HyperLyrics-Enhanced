package com.juren233.hyperlyricsenhanced.common.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.HyperLogger
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * 媒体元数据辅助类。
 * 负责从 MediaSession 中提取歌曲信息及封面图片，提供多级兜底逻辑。
 */
object MediaMetadataHelper {

    private const val ARTWORK_CACHE_SIZE = 6
    private const val ARTWORK_MAX_DIMENSION = 512
    private const val ARTWORK_MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
    private const val ARTWORK_FAILURE_RETRY_MS = 60_000L
    private const val ARTWORK_CONNECT_TIMEOUT_MS = 2_500
    private const val ARTWORK_READ_TIMEOUT_MS = 4_000
    private const val ARTWORK_LOG_TAG = "MediaArtworkResolver"

    private val sessionLock = Any()
    private val artworkLock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val artworkExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HyperLyrics-MediaArtwork").apply { isDaemon = true }
    }
    private val artworkGeneration = AtomicInteger(0)
    private val artworkCache = LinkedHashMap<String, ArtworkCacheEntry>(8, 0.75f, true)
    private val pendingArtworkRequests = HashSet<String>()
    private val failedArtworkRequests = HashMap<String, Long>()
    private val artworkDiagnosticStateByPackage = HashMap<String, String>()

    @Volatile
    private var artworkResolvedListener: (() -> Unit)? = null

    @Volatile
    private var mediaSessionManager: MediaSessionManager? = null

    @Volatile
    private var activeControllers: List<MediaController> = emptyList()

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        activeControllers = controllers.orEmpty()
    }

    data class MediaInfo(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArt: Bitmap? = null,
        val duration: Long = -1L,
        val artworkSource: ArtworkSource = ArtworkSource.NONE,
    )

    enum class ArtworkSource(internal val priority: Int) {
        NONE(0),
        NATIVE_ISLAND_DRAWABLE(1),
        MEDIA_URI(2),
        DESCRIPTION_BITMAP(3),
        MEDIA_METADATA_BITMAP(4),
    }

    class ArtworkCaptureToken internal constructor(
        val packageName: String,
        val cacheKey: String,
        val title: String,
        val artist: String,
        val album: String,
    )

    data class PlaybackProgress(
        val position: Long = -1L,
        val duration: Long = -1L,
        val isPlaying: Boolean = false,
        val playbackSpeed: Float = 0f
    ) {
        val fraction: Float
            get() = if (position >= 0L && duration > 0L) {
                (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                -1f
            }
    }

    enum class NextMediaSource {
        OFFICIAL_PROVIDER,
        MEDIA_SESSION_QUEUE,
        NONE,
    }

    data class NextMediaLookup(
        val mediaInfo: MediaInfo = MediaInfo(),
        val source: NextMediaSource = NextMediaSource.NONE,
        val reason: String = "",
    )

    /**
     * 获取指定包名的当前媒体信息
     */
    fun getMediaInfo(context: Context, packageName: String, logger: HyperLogger? = null): MediaInfo {
        if (packageName.isEmpty()) return MediaInfo()

        return try {
            findController(context, packageName)?.metadata?.toMediaInfo(
                context = context.applicationContext ?: context,
                packageName = packageName,
                logger = logger,
            ) ?: MediaInfo()
        } catch (e: Exception) {
            logger?.e("MediaMetadataHelper", "获取媒体信息失败 ($packageName)", e)
            MediaInfo()
        }
    }

    fun setArtworkResolvedListener(listener: (() -> Unit)?) {
        artworkResolvedListener = listener
    }

    fun clearArtworkResolution() {
        artworkGeneration.incrementAndGet()
        artworkResolvedListener = null
        synchronized(artworkLock) {
            artworkCache.clear()
            pendingArtworkRequests.clear()
            failedArtworkRequests.clear()
            artworkDiagnosticStateByPackage.clear()
        }
    }

    fun currentArtworkCaptureToken(
        context: Context,
        packageName: String,
    ): ArtworkCaptureToken? {
        if (packageName.isBlank()) return null
        return runCatching {
            val metadata = findController(context, packageName)?.metadata ?: return null
            val snapshot = metadata.snapshot()
            val cacheKey = buildArtworkCacheKey(
                packageName = packageName,
                mediaId = snapshot.mediaId,
                title = snapshot.title,
                artist = snapshot.artist,
                album = snapshot.album,
                duration = snapshot.duration,
            ) ?: return null
            ArtworkCaptureToken(
                packageName = packageName,
                cacheKey = cacheKey,
                title = snapshot.title,
                artist = snapshot.artist,
                album = snapshot.album,
            )
        }.getOrNull()
    }

    /**
     * Return only the artwork cached for the currently active media identity.
     * The title check prevents a rebuilt island from borrowing the previous track's cover
     * while MediaSession and lyric callbacks are crossing during a track change.
     */
    fun currentCachedArtwork(
        context: Context,
        packageName: String,
        expectedTitle: String?,
    ): Bitmap? {
        val expected = expectedTitle?.takeIf(String::isNotBlank) ?: return null
        val token = currentArtworkCaptureToken(context, packageName) ?: return null
        if (!artworkTitlesMatch(token.title, expected)) return null
        return cachedArtwork(token.cacheKey)?.bitmap
    }

    internal fun artworkTitlesMatch(actualTitle: String, expectedTitle: String): Boolean {
        val actual = normalizeArtworkIdentityText(actualTitle)
        val expected = normalizeArtworkIdentityText(expectedTitle)
        return actual.isNotEmpty() && expected.isNotEmpty() && actual == expected
    }

    fun cacheCapturedArtwork(
        context: Context,
        token: ArtworkCaptureToken,
        bitmap: Bitmap,
        logger: HyperLogger? = null,
    ): Boolean {
        if (!isUsableArtwork(bitmap)) return false
        val currentToken = currentArtworkCaptureToken(context, token.packageName) ?: return false
        if (currentToken.cacheKey != token.cacheKey) return false
        val changed = cacheArtwork(
            cacheKey = token.cacheKey,
            bitmap = bitmap,
            source = ArtworkSource.NATIVE_ISLAND_DRAWABLE,
        )
        if (changed) {
            debugLog(
                logger,
                "原生超级岛封面已缓存: package=${token.packageName}, " +
                    "key=${token.cacheKey.hashCode().toUInt().toString(16)}, " +
                    "size=${bitmap.width}x${bitmap.height}",
            )
            notifyArtworkResolved()
        }
        return changed
    }

    /**
     * 优先读取官方 Provider 上报的下一首，再回退到当前 MediaSession 播放队列。
     * Apple Music 将队列项目写入 QueueItem.description，因此仍走系统队列回退。
     */
    fun getNextMediaInfo(
        context: Context,
        packageName: String,
        current: MediaInfo = getMediaInfo(context, packageName)
    ): MediaInfo = getNextMediaLookup(context, packageName, current).mediaInfo

    fun getNextMediaLookup(
        context: Context,
        packageName: String,
        current: MediaInfo = getMediaInfo(context, packageName)
    ): NextMediaLookup {
        if (packageName.isEmpty()) return NextMediaLookup(reason = "package_empty")
        if (!OfficialProviderCatalog.supportsNextTrackPreview(packageName)) {
            return NextMediaLookup(reason = "next_track_unsupported")
        }
        return try {
            val controller = findController(context, packageName)
                ?: return NextMediaLookup(reason = "controller_missing")
            val currentMediaId = controller.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            NextTrackMetadataCache.find(
                playerPackageName = packageName,
                currentId = currentMediaId,
                currentTitle = current.title,
                currentArtist = current.artist,
            )?.let { next ->
                return NextMediaLookup(
                    mediaInfo = MediaInfo(
                        title = next.title,
                        artist = next.artist,
                        album = next.album,
                        duration = next.durationMs,
                    ),
                    source = NextMediaSource.OFFICIAL_PROVIDER,
                    reason = "provider_cache_hit",
                )
            }
            val queue = controller.queue.orEmpty()
            if (queue.isEmpty()) return NextMediaLookup(reason = "provider_miss_queue_empty")
            val activeQueueItemId = controller.playbackState?.activeQueueItemId ?: -1L
            val queueIdIndex = if (activeQueueItemId >= 0L) {
                queue.indexOfFirst { it.queueId == activeQueueItemId }
            } else {
                -1
            }
            val currentIndex = queueIdIndex.takeIf { it >= 0 } ?: queue.indexOfFirst { item ->
                (currentMediaId != null && item.description.mediaId == currentMediaId) ||
                    (current.title.isNotBlank() && item.description.title?.toString() == current.title)
            }
            if (currentIndex < 0) return NextMediaLookup(reason = "queue_current_missing")
            val nextItem = queue.getOrNull(currentIndex + 1)
                ?: return NextMediaLookup(reason = "queue_next_missing")
            val description = nextItem.description
            NextMediaLookup(
                mediaInfo = MediaInfo(
                    title = description.title?.toString().orEmpty(),
                    artist = description.subtitle?.toString().orEmpty(),
                    album = description.description?.toString().orEmpty(),
                    albumArt = description.iconBitmap,
                ),
                source = NextMediaSource.MEDIA_SESSION_QUEUE,
                reason = "queue_hit",
            )
        } catch (_: Exception) {
            NextMediaLookup(reason = "lookup_exception")
        }
    }

    /**
     * 获取指定包名的当前播放位置（毫秒）。未播放或无控制器时返回 -1。
     */
    fun getPlaybackPosition(context: Context, packageName: String): Long {
        if (packageName.isEmpty()) return -1
        return try {
            estimatePlaybackPosition(findController(context, packageName)?.playbackState)
        } catch (_: Exception) {
            -1
        }
    }

    fun getPlaybackProgress(context: Context, packageName: String): PlaybackProgress {
        if (packageName.isEmpty()) return PlaybackProgress()
        return try {
            val controller = findController(context, packageName) ?: return PlaybackProgress()
            val state = controller.playbackState
            val duration = controller.metadata?.extractDuration() ?: -1L
            PlaybackProgress(
                position = estimatePlaybackPosition(state),
                duration = duration,
                isPlaying = state?.state == PlaybackState.STATE_PLAYING,
                playbackSpeed = state?.playbackSpeed ?: 0f
            )
        } catch (_: Exception) {
            PlaybackProgress()
        }
    }

    /**
     * 通过系统 MediaSession 判断指定包名是否正在播放。
     */
    fun isPackagePlaying(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        return try {
            ensureSessionSnapshot(context)
            activeControllers.any {
                it.packageName == packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (_: Exception) {
            false
        }
    }

    fun estimatePlaybackPosition(state: PlaybackState?): Long {
        state ?: return -1L
        val basePosition = state.position
        if (basePosition < 0L) return -1L
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0L) {
            return basePosition
        }
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
        return (basePosition + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0L)
    }

    private fun findController(context: Context, packageName: String): MediaController? {
        ensureSessionSnapshot(context)
        selectController(activeControllers, packageName)?.let { return it }
        refreshSessionSnapshot()
        return selectController(activeControllers, packageName)
    }

    private fun selectController(
        controllers: List<MediaController>,
        packageName: String
    ): MediaController? {
        var latestController: MediaController? = null
        var latestUpdateTime = Long.MIN_VALUE
        controllers.forEach { controller ->
            if (controller.packageName != packageName) return@forEach
            val state = controller.playbackState
            if (state?.state == PlaybackState.STATE_PLAYING) return controller
            val updateTime = state?.lastPositionUpdateTime ?: 0L
            if (latestController == null || updateTime > latestUpdateTime) {
                latestController = controller
                latestUpdateTime = updateTime
            }
        }
        return latestController
    }

    private fun ensureSessionSnapshot(context: Context) {
        if (mediaSessionManager != null) return
        synchronized(sessionLock) {
            if (mediaSessionManager != null) return
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mediaSessionManager = manager
            activeControllers = runCatching { manager.getActiveSessions(null) }.getOrDefault(emptyList())
            val registerListener: () -> Unit = {
                runCatching {
                    manager.addOnActiveSessionsChangedListener(activeSessionsListener, null)
                }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                registerListener()
            } else {
                mainHandler.post(registerListener)
            }
        }
    }

    private fun refreshSessionSnapshot() {
        val manager = mediaSessionManager ?: return
        activeControllers = runCatching { manager.getActiveSessions(null) }.getOrDefault(activeControllers)
    }

    private fun MediaMetadata.toMediaInfo(
        context: Context,
        packageName: String,
        logger: HyperLogger?,
    ): MediaInfo {
        val snapshot = snapshot()
        val cacheKey = buildArtworkCacheKey(
            packageName = packageName,
            mediaId = snapshot.mediaId,
            title = snapshot.title,
            artist = snapshot.artist,
            album = snapshot.album,
            duration = snapshot.duration,
        )
        val artwork = resolveArtwork(
            context = context,
            packageName = packageName,
            cacheKey = cacheKey,
            directArtwork = snapshot.directArtwork,
            artworkUri = snapshot.artworkUri,
            logger = logger,
        )
        return MediaInfo(
            title = snapshot.title,
            artist = snapshot.artist,
            album = snapshot.album,
            albumArt = artwork?.bitmap,
            duration = snapshot.duration,
            artworkSource = artwork?.source ?: ArtworkSource.NONE,
        )
    }

    private fun MediaMetadata.snapshot(): MetadataSnapshot {
        val mediaDescription = description
        return MetadataSnapshot(
            mediaId = getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            title = getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: mediaDescription.title?.toString().orEmpty(),
            artist = getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: mediaDescription.subtitle?.toString().orEmpty(),
            album = getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
                ?: mediaDescription.description?.toString().orEmpty(),
            duration = extractDuration(),
            directArtwork = extractDirectArtwork(mediaDescription.iconBitmap),
            artworkUri = extractArtworkUri(mediaDescription.iconUri),
        )
    }

    private fun MediaMetadata.extractDirectArtwork(descriptionBitmap: Bitmap?): ResolvedArtwork? {
        val metadataBitmap = sequenceOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        ).mapNotNull { key -> runCatching { getBitmap(key) }.getOrNull() }
            .firstOrNull(::isUsableArtwork)
        if (metadataBitmap != null) {
            return ResolvedArtwork(metadataBitmap, ArtworkSource.MEDIA_METADATA_BITMAP)
        }
        return descriptionBitmap
            ?.takeIf(::isUsableArtwork)
            ?.let { ResolvedArtwork(it, ArtworkSource.DESCRIPTION_BITMAP) }
    }

    private fun MediaMetadata.extractArtworkUri(descriptionUri: Uri?): String? {
        val metadataUri = sequenceOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).mapNotNull { key -> runCatching { getString(key) }.getOrNull() }
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
        return metadataUri ?: descriptionUri?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun resolveArtwork(
        context: Context,
        packageName: String,
        cacheKey: String?,
        directArtwork: ResolvedArtwork?,
        artworkUri: String?,
        logger: HyperLogger?,
    ): ResolvedArtwork? {
        if (directArtwork != null) {
            cacheKey?.let {
                cacheArtwork(it, directArtwork.bitmap, directArtwork.source)
            }
            logArtworkState(
                logger = logger,
                packageName = packageName,
                state = "source=${directArtwork.source},uri=none,cache=${cacheKey != null}",
            )
            return directArtwork
        }

        val cached = cacheKey?.let(::cachedArtwork)
        if (cacheKey != null && !artworkUri.isNullOrBlank()) {
            scheduleArtworkUriLoad(
                context = context,
                packageName = packageName,
                cacheKey = cacheKey,
                artworkUri = artworkUri,
                logger = logger,
            )
        }
        logArtworkState(
            logger = logger,
            packageName = packageName,
            state = "source=${cached?.source ?: ArtworkSource.NONE}," +
                "uri=${artworkUri?.let { runCatching { Uri.parse(it).scheme }.getOrNull() } ?: "none"}," +
                "cache=${cached != null}",
        )
        return cached
    }

    private fun scheduleArtworkUriLoad(
        context: Context,
        packageName: String,
        cacheKey: String,
        artworkUri: String,
        logger: HyperLogger?,
    ) {
        val requestKey = "$cacheKey\u001F$artworkUri"
        val now = SystemClock.elapsedRealtime()
        val shouldSchedule = synchronized(artworkLock) {
            if (requestKey in pendingArtworkRequests) return@synchronized false
            val failedAt = failedArtworkRequests[requestKey]
            if (failedAt != null && now - failedAt < ARTWORK_FAILURE_RETRY_MS) {
                return@synchronized false
            }
            pendingArtworkRequests += requestKey
            true
        }
        if (!shouldSchedule) return
        val generation = artworkGeneration.get()
        val appContext = context.applicationContext ?: context
        debugLog(
            logger,
            "封面 URI 解析已调度: package=$packageName, " +
                "scheme=${runCatching { Uri.parse(artworkUri).scheme }.getOrNull()}, " +
                "key=${cacheKey.hashCode().toUInt().toString(16)}",
        )
        artworkExecutor.execute {
            val decoded = runCatching {
                decodeArtworkUri(appContext, artworkUri)
            }.onFailure { error ->
                debugLog(
                    logger,
                    "封面 URI 解析失败: package=$packageName, " +
                        "scheme=${runCatching { Uri.parse(artworkUri).scheme }.getOrNull()}, " +
                        "reason=${error.javaClass.simpleName}:${error.message}",
                )
            }.getOrNull()
            synchronized(artworkLock) {
                pendingArtworkRequests.remove(requestKey)
                if (decoded == null) {
                    failedArtworkRequests[requestKey] = SystemClock.elapsedRealtime()
                } else {
                    failedArtworkRequests.remove(requestKey)
                }
            }
            if (decoded == null) return@execute
            if (generation != artworkGeneration.get()) {
                decoded.recycle()
                return@execute
            }
            val changed = cacheArtwork(cacheKey, decoded, ArtworkSource.MEDIA_URI)
            if (changed) {
                debugLog(
                    logger,
                    "封面 URI 解析成功: package=$packageName, " +
                        "key=${cacheKey.hashCode().toUInt().toString(16)}, " +
                        "size=${decoded.width}x${decoded.height}",
                )
                notifyArtworkResolved()
            } else {
                decoded.recycle()
            }
        }
    }

    private fun decodeArtworkUri(context: Context, rawUri: String): Bitmap? {
        val uri = Uri.parse(rawUri)
        val source = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content", "android.resource" -> ImageDecoder.createSource(context.contentResolver, uri)
            "file" -> ImageDecoder.createSource(File(requireNotNull(uri.path)))
            "http", "https" -> ImageDecoder.createSource(
                ByteBuffer.wrap(downloadArtwork(rawUri))
            )
            null, "" -> ImageDecoder.createSource(File(rawUri))
            else -> return null
        }
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val maxDimension = maxOf(width, height)
            if (maxDimension > ARTWORK_MAX_DIMENSION) {
                val scale = ARTWORK_MAX_DIMENSION.toFloat() / maxDimension.toFloat()
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }.takeIf(::isUsableArtwork)
    }

    private fun downloadArtwork(rawUri: String): ByteArray {
        val connection = (URL(rawUri).openConnection() as HttpURLConnection).apply {
            connectTimeout = ARTWORK_CONNECT_TIMEOUT_MS
            readTimeout = ARTWORK_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = true
        }
        try {
            val declaredLength = connection.contentLengthLong
            if (declaredLength > ARTWORK_MAX_DOWNLOAD_BYTES) {
                throw IOException("artwork response too large: $declaredLength")
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    declaredLength.takeIf {
                        it in 1L..ARTWORK_MAX_DOWNLOAD_BYTES.toLong()
                    }
                        ?.toInt()
                        ?: 32 * 1024
                )
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > ARTWORK_MAX_DOWNLOAD_BYTES) {
                        throw IOException("artwork response exceeded limit")
                    }
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheArtwork(
        cacheKey: String,
        bitmap: Bitmap,
        source: ArtworkSource,
    ): Boolean {
        if (!isUsableArtwork(bitmap)) return false
        val fingerprint = artworkFingerprint(bitmap)
        return synchronized(artworkLock) {
            val previous = artworkCache[cacheKey]
            if (previous != null && previous.source.priority > source.priority) {
                return@synchronized false
            }
            if (previous != null && previous.fingerprint == fingerprint) {
                if (source.priority > previous.source.priority) {
                    artworkCache[cacheKey] = previous.copy(source = source)
                }
                return@synchronized false
            }
            artworkCache[cacheKey] = ArtworkCacheEntry(bitmap, source, fingerprint)
            trimArtworkCache()
            true
        }
    }

    private fun cachedArtwork(cacheKey: String): ResolvedArtwork? {
        return synchronized(artworkLock) {
            val entry = artworkCache[cacheKey] ?: return@synchronized null
            if (!isUsableArtwork(entry.bitmap)) {
                artworkCache.remove(cacheKey)
                return@synchronized null
            }
            ResolvedArtwork(entry.bitmap, entry.source)
        }
    }

    private fun trimArtworkCache() {
        while (artworkCache.size > ARTWORK_CACHE_SIZE) {
            val oldest = artworkCache.entries.firstOrNull() ?: return
            artworkCache.remove(oldest.key)
        }
    }

    private fun notifyArtworkResolved() {
        val listener = artworkResolvedListener ?: return
        mainHandler.post {
            runCatching(listener)
        }
    }

    private fun logArtworkState(
        logger: HyperLogger?,
        packageName: String,
        state: String,
    ) {
        if (!BuildConfig.DEBUG || logger == null) return
        val changed = synchronized(artworkLock) {
            if (artworkDiagnosticStateByPackage[packageName] == state) {
                false
            } else {
                artworkDiagnosticStateByPackage[packageName] = state
                true
            }
        }
        if (changed) logger.i(ARTWORK_LOG_TAG, "封面来源状态: package=$packageName,$state")
    }

    private fun debugLog(logger: HyperLogger?, message: String) {
        if (BuildConfig.DEBUG) logger?.i(ARTWORK_LOG_TAG, message)
    }

    private fun artworkFingerprint(bitmap: Bitmap): Int {
        return runCatching {
            sampledArtworkFingerprint(bitmap)
        }.getOrElse {
            val softwareCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            try {
                sampledArtworkFingerprint(softwareCopy)
            } finally {
                softwareCopy.recycle()
            }
        }
    }

    private fun sampledArtworkFingerprint(bitmap: Bitmap): Int {
        var hash = 17
        val columns = minOf(bitmap.width, 6)
        val rows = minOf(bitmap.height, 6)
        for (row in 0 until rows) {
            val y = if (rows == 1) 0 else row * (bitmap.height - 1) / (rows - 1)
            for (column in 0 until columns) {
                val x = if (columns == 1) 0 else column * (bitmap.width - 1) / (columns - 1)
                hash = 31 * hash + bitmap.getPixel(x, y)
            }
        }
        return 31 * (31 * hash + bitmap.width) + bitmap.height
    }

    private fun isUsableArtwork(bitmap: Bitmap): Boolean {
        return !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
    }

    internal fun buildArtworkCacheKey(
        packageName: String,
        mediaId: String,
        title: String,
        artist: String,
        album: String,
        duration: Long,
    ): String? {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isEmpty()) return null
        val normalizedId = mediaId.trim()
        if (normalizedId.isNotEmpty()) {
            return "$normalizedPackage\u001Fid\u001F$normalizedId"
        }
        val normalizedTitle = normalizeArtworkIdentityText(title)
        val normalizedArtist = normalizeArtworkIdentityText(artist)
        val normalizedAlbum = normalizeArtworkIdentityText(album)
        if (normalizedTitle.isEmpty() && normalizedArtist.isEmpty()) return null
        return if (duration > 0L && normalizedArtist.isNotEmpty() && normalizedAlbum.isNotEmpty()) {
            listOf(normalizedPackage, normalizedArtist, normalizedAlbum, duration.toString())
                .joinToString("\u001F")
        } else {
            listOf(
                normalizedPackage,
                normalizedTitle,
                normalizedArtist,
                normalizedAlbum,
                duration.takeIf { it > 0L }?.toString().orEmpty(),
            ).joinToString("\u001F")
        }
    }

    private fun normalizeArtworkIdentityText(value: String): String {
        return value.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
    }

    private fun MediaMetadata.extractDuration(): Long {
        return try {
            getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private data class MetadataSnapshot(
        val mediaId: String,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val directArtwork: ResolvedArtwork?,
        val artworkUri: String?,
    )

    private data class ResolvedArtwork(
        val bitmap: Bitmap,
        val source: ArtworkSource,
    )

    private data class ArtworkCacheEntry(
        val bitmap: Bitmap,
        val source: ArtworkSource,
        val fingerprint: Int,
    )

}
