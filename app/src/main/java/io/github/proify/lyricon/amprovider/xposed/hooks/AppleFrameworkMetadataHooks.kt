/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import com.juren233.hyperlyricsenhanced.common.RootConstants
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMetadataOverrideStore
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.ActivePlaybackMediaIdentity
import io.github.proify.lyricon.amprovider.xposed.Constants
import io.github.proify.lyricon.amprovider.xposed.FrameworkMediaQueueRefresh
import io.github.proify.lyricon.amprovider.xposed.FrameworkMediaSessionRefresh
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.selectTrustworthyMediaId
import io.github.proify.lyricon.amprovider.xposed.shouldOpenFullPlayerFromNotification
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

internal class AppleFrameworkMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> SharedPreferences?,
    private val metadataStore: AppleMetadataOverrideStore,
    private val effectiveMetadataAlias: (String) -> AppleInternalCatalogResolver.Alias?,
    private val activePlaybackIdentity: () -> ActivePlaybackMediaIdentity,
    private val logMetadataIdentity: (
        event: String,
        identity: ActivePlaybackMediaIdentity,
        details: String,
    ) -> Unit,
) {
    @Volatile
    private var currentMediaSessionRefresh: FrameworkMediaSessionRefresh? = null
    @Volatile
    private var currentMediaQueueRefresh: FrameworkMediaQueueRefresh? = null
    private val mediaQueueRefreshInProgress = AtomicBoolean(false)
    private val mainContentActivityClassName by lazy {
        runtime.hookResolver.configuredClassNames(
            AppleMusicHookPoint.APPLE_MAIN_CONTENT_ACTIVITY
        ).single()
    }

    fun installMediaSessionMetadata() {
        runCatching {
            val method = MediaSession::class.java.getDeclaredMethod(
                "setMetadata",
                MediaMetadata::class.java,
            ).also { it.isAccessible = true }
            runtime.hookRegistrar.installArgumentRewriteHook(method) { chain ->
                val metadata = chain.args.firstOrNull() as? MediaMetadata
                    ?: return@installArgumentRewriteHook null
                val session = chain.thisObject as? MediaSession
                    ?: return@installArgumentRewriteHook null
                val identityBefore = activePlaybackIdentity()
                val explicitId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                val mediaId = mediaMetadataId(metadata)
                if (mediaId == null) {
                    logMetadataIdentity(
                        "framework_capture_unresolved",
                        identityBefore,
                        "explicitId=$explicitId, " +
                            "title=${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}, " +
                            "artist=${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}",
                    )
                    return@installArgumentRewriteHook null
                }
                val previous = currentMediaSessionRefresh
                val alias = effectiveMetadataAlias(mediaId)
                val baseMetadata = if (
                    previous?.mediaId == mediaId &&
                    alias != null &&
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == alias.title &&
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) == alias.artist
                ) {
                    previous.metadata
                } else {
                    metadata
                }
                currentMediaSessionRefresh = FrameworkMediaSessionRefresh(
                    mediaId = mediaId,
                    session = WeakReference(session),
                    metadata = baseMetadata,
                )
                logMetadataIdentity(
                    "framework_capture",
                    activePlaybackIdentity(),
                    "explicitId=$explicitId, resolvedId=$mediaId, " +
                        "aliasHit=${alias != null}, " +
                        "title=${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}, " +
                        "artist=${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}",
                )
                if (alias == null) return@installArgumentRewriteHook null
                val rewritten = rewriteMediaMetadata(metadata, alias)
                    ?: return@installArgumentRewriteHook null
                ProviderLogger.info(
                    "Apple MediaSession 元数据已覆盖: " +
                        "id=$mediaId, title=${alias.title}, artist=${alias.artist}"
                )
                arrayOf(rewritten)
            }
            ProviderLogger.info("Apple MediaSession 元数据 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple MediaSession 元数据 Hook 安装失败", it)
        }
    }

    fun installMediaSessionQueue() {
        runCatching {
            val method = MediaSession::class.java.getDeclaredMethod(
                "setQueue",
                List::class.java,
            ).also { it.isAccessible = true }
            runtime.hookRegistrar.installArgumentRewriteHook(method) { chain ->
                if (mediaQueueRefreshInProgress.get()) {
                    return@installArgumentRewriteHook null
                }
                val session = chain.thisObject as? MediaSession
                    ?: return@installArgumentRewriteHook null
                @Suppress("UNCHECKED_CAST")
                val queue = chain.args.firstOrNull() as? List<MediaSession.QueueItem>
                    ?: return@installArgumentRewriteHook null
                val mediaIds = queue.mapNotNullTo(mutableSetOf(), ::queueItemMediaId)
                currentMediaQueueRefresh = FrameworkMediaQueueRefresh(
                    session = WeakReference(session),
                    queue = queue.toList(),
                    mediaIds = mediaIds,
                )
                val rewritten = rewriteMediaQueue(queue)
                    ?: return@installArgumentRewriteHook null
                ProviderLogger.info("Apple MediaSession 队列已覆盖: items=${rewritten.size}")
                arrayOf(rewritten)
            }
            ProviderLogger.info("Apple MediaSession 队列 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple MediaSession 队列 Hook 安装失败", it)
        }
    }

    fun installPlaybackNotificationMetadata() {
        runCatching {
            listOf(
                "setContentTitle" to true,
                "setContentText" to false,
            ).forEach { (methodName, title) ->
                val method = Notification.Builder::class.java.getDeclaredMethod(
                    methodName,
                    CharSequence::class.java,
                ).also { it.isAccessible = true }
                runtime.hookRegistrar.installArgumentRewriteHook(method) { chain ->
                    val value = chain.args.firstOrNull() as? CharSequence
                        ?: return@installArgumentRewriteHook null
                    val rewritten = rewritePlaybackNotificationText(value, title)
                    if (rewritten == value) null else arrayOf(rewritten)
                }
            }
            val buildMethod = Notification.Builder::class.java.getDeclaredMethod("build")
                .also { it.isAccessible = true }
            runtime.hookRegistrar.installResultOverrideHook(buildMethod) { _, original ->
                (original as? Notification)?.let(::rewriteMediaNotificationContentIntent)
                original
            }
            ProviderLogger.info("Apple Music 媒体通知元数据及点击入口 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music 媒体通知元数据及点击入口 Hook 安装失败", it)
        }
    }

    fun refreshMediaSessionMetadata(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        val refresh = currentMediaSessionRefresh?.takeIf { it.mediaId == mediaId } ?: return
        val session = refresh.session.get() ?: return
        val rewritten = rewriteMediaMetadata(refresh.metadata, alias) ?: refresh.metadata
        runtime.mainHandler.post {
            runCatching { session.setMetadata(rewritten) }
                .onFailure { ProviderLogger.error("Apple MediaSession 元数据刷新失败", it) }
        }
    }

    fun refreshMediaSessionQueue(mediaId: String) {
        val refresh = currentMediaQueueRefresh ?: return
        if (refresh.mediaIds.isNotEmpty() && mediaId !in refresh.mediaIds) return
        val session = refresh.session.get() ?: return
        val rewritten = rewriteMediaQueue(refresh.queue) ?: refresh.queue
        runtime.mainHandler.post {
            mediaQueueRefreshInProgress.set(true)
            try {
                session.setQueue(rewritten)
            } catch (throwable: Throwable) {
                ProviderLogger.error("Apple MediaSession 队列刷新失败", throwable)
            } finally {
                mediaQueueRefreshInProgress.set(false)
            }
        }
    }

    fun restoreMediaSessionMetadata() {
        val refresh = currentMediaSessionRefresh ?: return
        val session = refresh.session.get() ?: return
        runCatching { session.setMetadata(refresh.metadata) }
            .onFailure { ProviderLogger.error("Apple MediaSession 原始元数据恢复失败", it) }
    }

    fun restoreMediaSessionQueue() {
        val refresh = currentMediaQueueRefresh ?: return
        val session = refresh.session.get() ?: return
        mediaQueueRefreshInProgress.set(true)
        try {
            session.setQueue(refresh.queue)
        } catch (throwable: Throwable) {
            ProviderLogger.error("Apple MediaSession 原始队列恢复失败", throwable)
        } finally {
            mediaQueueRefreshInProgress.set(false)
        }
    }

    fun currentMediaId(): String? = currentMediaSessionRefresh?.mediaId

    fun originalMetadata(mediaId: String): MediaMetadata? =
        currentMediaSessionRefresh?.takeIf { it.mediaId == mediaId }?.metadata

    private fun rewriteMediaNotificationContentIntent(notification: Notification) {
        if (!shouldOpenFullPlayerFromMediaNotification()) return
        val hasMediaSession = notification.extras?.let { extras ->
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                extras.containsKey("androidx.media3.session")
        } == true
        if (!shouldOpenFullPlayerFromNotification(notification.category, hasMediaSession)) return
        val intent = Intent().apply {
            component = ComponentName(
                Constants.APPLE_MUSIC_PACKAGE_NAME,
                mainContentActivityClassName,
            )
            putExtra(APPLE_MUSIC_SHOW_FULL_PLAYER_EXTRA, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        notification.contentIntent = PendingIntent.getActivity(
            runtime.application,
            MEDIA_NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun shouldOpenFullPlayerFromMediaNotification(): Boolean =
        preferences()?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER,
        ) == true

    private fun mediaMetadataId(metadata: MediaMetadata): String? {
        val metadataId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val matchingIds = metadataStore.accountMetadataSnapshot().entries.mapNotNull {
                (mediaId, account) ->
            val alias = effectiveMetadataAlias(mediaId)
            val titleMatches = title != null &&
                (title == account.title || title == alias?.title)
            val artistMatches = artist != null &&
                (artist == account.artist || artist == alias?.artist)
            mediaId.takeIf { titleMatches && artistMatches }
        }
        return selectTrustworthyMediaId(metadataId, matchingIds)
    }

    private fun rewriteMediaMetadata(
        metadata: MediaMetadata,
        alias: AppleInternalCatalogResolver.Alias,
    ): MediaMetadata? {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        if (
            title == alias.title &&
            artist == alias.artist &&
            (alias.album.isBlank() || album == alias.album)
        ) return null
        return MediaMetadata.Builder(metadata).apply {
            alias.title.takeIf(String::isNotBlank)?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            alias.artist.takeIf(String::isNotBlank)?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            alias.album.takeIf(String::isNotBlank)?.let {
                putString(MediaMetadata.METADATA_KEY_ALBUM, it)
            }
        }.build()
    }

    private fun rewriteMediaQueue(
        queue: List<MediaSession.QueueItem>,
    ): List<MediaSession.QueueItem>? {
        var changed = false
        val rewritten = queue.map { queueItem ->
            val mediaId = queueItemMediaId(queueItem) ?: return@map queueItem
            val alias = effectiveMetadataAlias(mediaId) ?: return@map queueItem
            val description = queueItem.description
            val title = alias.title.takeIf(String::isNotBlank) ?: description.title
            val artist = alias.artist.takeIf(String::isNotBlank) ?: description.subtitle
            if (title == description.title && artist == description.subtitle) return@map queueItem
            changed = true
            val rewrittenDescription = MediaDescription.Builder().apply {
                setMediaId(description.mediaId)
                setTitle(title)
                setSubtitle(artist)
                setDescription(description.description)
                setIconBitmap(description.iconBitmap)
                setIconUri(description.iconUri)
                setExtras(description.extras)
                setMediaUri(description.mediaUri)
            }.build()
            MediaSession.QueueItem(rewrittenDescription, queueItem.queueId)
        }
        return rewritten.takeIf { changed }
    }

    private fun queueItemMediaId(queueItem: MediaSession.QueueItem): String? {
        val description = queueItem.description
        val extras = description.extras
        val candidates = sequenceOf(
            description.mediaId,
            extras?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            extras?.getString(
                Constants.APPLE_MEDIA3_METADATA_ID_KEY
            ),
        )
        candidates.firstOrNull { candidate ->
            !candidate.isNullOrBlank() && candidate.all(Char::isDigit)
        }?.let { return it }

        val title = description.title?.toString()
        val artist = description.subtitle?.toString()
        val matchingIds = metadataStore.accountMetadataSnapshot().entries.mapNotNull {
                (mediaId, account) ->
            mediaId.takeIf { account.title == title && account.artist == artist }
        }
        return selectTrustworthyMediaId(
            explicitMediaId = null,
            inferredMediaIds = matchingIds,
        )
    }

    private fun rewritePlaybackNotificationText(
        value: CharSequence,
        title: Boolean,
    ): CharSequence {
        val identity = activePlaybackIdentity()
        val mediaId = identity.mediaId
        if (mediaId == null) {
            logMetadataIdentity(
                "notification_unresolved",
                identity,
                "field=${if (title) "title" else "artist"}, value=$value",
            )
            return value
        }
        val alias = effectiveMetadataAlias(mediaId)
        if (alias == null) {
            logMetadataIdentity(
                "notification_alias_miss",
                identity,
                "field=${if (title) "title" else "artist"}, value=$value",
            )
            return value
        }
        val account = metadataStore.accountMetadata(mediaId)
        val frameworkMetadata = originalMetadata(mediaId)
        val originalValues = if (title) {
            sequenceOf(
                account?.title,
                frameworkMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                frameworkMetadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            )
        } else {
            sequenceOf(
                account?.artist,
                frameworkMetadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                frameworkMetadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            )
        }.filterNotNull().filter(String::isNotBlank).toSet()
        if (value.toString() !in originalValues) {
            logMetadataIdentity(
                "notification_value_miss",
                identity,
                "field=${if (title) "title" else "artist"}, value=$value, " +
                    "expected=$originalValues",
            )
            return value
        }
        val rewritten = if (title) {
            alias.title.takeIf(String::isNotBlank) ?: value
        } else {
            alias.artist.takeIf(String::isNotBlank) ?: value
        }
        logMetadataIdentity(
            "notification_rewrite",
            identity,
            "field=${if (title) "title" else "artist"}, value=$value, after=$rewritten",
        )
        return rewritten
    }

    private companion object {
        const val APPLE_MUSIC_SHOW_FULL_PLAYER_EXTRA =
            "com.apple.android.music.intent.showfullplayer"
        const val MEDIA_NOTIFICATION_REQUEST_CODE = 0x484C
    }
}
