/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Apple Music Lyricon provider hosted directly by the HyperLyrics Enhanced module. */
object AppleMusicProvider {
    private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"

    private val initialized = AtomicBoolean(false)
    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader
    private lateinit var module: XposedModule

    private var isPlaying = false
    @Volatile
    private var playbackPositionSource: PlaybackPositionSource? = null
    @Volatile
    private var activePlaybackPlayer: Any? = null
    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    private var progressJob: Job? = null
    private var player: RemotePlayer? = null
    private var zeroPositionReadCount = 0
    private var hasLoggedNonZeroPosition = false
    private lateinit var lyricRequester: LyricRequester
    private lateinit var internalCatalogResolver: AppleInternalCatalogResolver
    private var contentUiLanguagePrefs: android.content.SharedPreferences? = null
    private var contentUiLanguageListener:
        android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile
    private var lastLoggedContentLanguage: String? = null
    private val pendingLyricsRequestSources =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        this.module = module
        this.classLoader = classLoader
        val onCreate = Application::class.java.getDeclaredMethod("onCreate")
        installHook(onCreate, after = { chain, _ ->
            (chain.thisObject as? Application)?.let(::onAppCreate)
        })
        ProviderLogger.info("Apple Music 内置歌词提供器生命周期 Hook 已安装")
    }

    private fun onAppCreate(app: Application) {
        if (!initialized.compareAndSet(false, true)) return
        application = app
        classLoader = app.classLoader

        runCatching {
            PreferencesMonitor.initialize(application)
            PreferencesMonitor.listener = object : PreferencesMonitor.Listener {
                override fun onTranslationSelectedChanged(selected: Boolean) {
                    player?.setDisplayTranslation(selected)
                }
            }
            DiskSongManager.initialize(application)
            internalCatalogResolver = AppleInternalCatalogResolver(
                classLoader = classLoader,
                mainHandler = Handler(Looper.getMainLooper())
            )
            initializeContentUiLanguage()
            initScreenStateMonitor()
            initProvider()
            startHooks()
            ProviderLogger.info("Apple Music 内置歌词提供器初始化完成")
        }.onFailure {
            initialized.set(false)
            ProviderLogger.error("Apple Music 内置歌词提供器初始化失败", it)
        }
    }

    private fun initializeContentUiLanguage() {
        val prefs = runCatching {
            module.getRemotePreferences(UIConstants.PREF_NAME)
        }.getOrNull() ?: return
        contentUiLanguagePrefs = prefs
        Handler(Looper.getMainLooper()).post {
            applyConfiguredContentUiLanguage(prefs)
        }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE) {
                applyConfiguredContentUiLanguage(changed)
            }
        }
        contentUiLanguageListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun applyConfiguredContentUiLanguage(
        prefs: android.content.SharedPreferences? = contentUiLanguagePrefs
    ) {
        prefs ?: return
        val selection = prefs.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
        )
        internalCatalogResolver.applyContentUiLanguage(selection)
    }

    private fun initProvider() {
        val directPlayer = AppleDirectPlayer(application).also { it.start() }
        val helper = runCatching {
            LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = APPLE_MUSIC_PACKAGE,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            ).also { it.register() }
        }.onFailure {
            ProviderLogger.error("Lyricon Central 提供器注册失败，使用内置直连", it)
        }.getOrNull()
        val activePlayer = helper?.player?.let { CompositeRemotePlayer(it, directPlayer) }
            ?: directPlayer
        lyricRequester = LyricRequester(classLoader, application)
        PlaybackManager.init(activePlayer, lyricRequester)
        activePlayer.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
        player = activePlayer
    }

    private fun startHooks() {
        hookTranslationPreference()
        hookMediaApiLocalization()
        hookContentHttpLocalization()
        hookExoMediaPlayer()
        hookMediaMetadataChange()
        hookLyricBuildMethod()
        if (BuildConfig.DEBUG) {
            hookLyricsNetworkRequest()
            hookLyricsCookies()
            hookFinalLyricsHttp()
        }
    }

    /** Apple Music overwrites every MediaApi query's `l` parameter from the system locale. */
    private fun hookMediaApiLocalization() {
        runCatching {
            val mediaApiClass = classLoader.loadClass("s8.E")
            val method = AppleReflection.findMethod(mediaApiClass, "c0", parameterCount = 1)
            installHook(method, after = { _, result ->
                @Suppress("UNCHECKED_CAST")
                val params = result as? MutableMap<Any?, Any?> ?: return@installHook
                val prefs = contentUiLanguagePrefs ?: return@installHook
                val selection = prefs.getInt(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
                )
                internalCatalogResolver.applyContentUiLanguage(selection)
                internalCatalogResolver.languageTagForCurrentRequest(selection)?.let { language ->
                    params["l"] = language
                    if (lastLoggedContentLanguage != language) {
                        lastLoggedContentLanguage = language
                        ProviderLogger.info(
                            "Apple Music 内容本地化参数已覆盖: language=$language"
                        )
                    }
                }
            })
            ProviderLogger.info("Apple Music 内容本地化参数 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music 内容本地化参数 Hook 安装失败", it)
        }
    }

    private fun hookContentHttpLocalization() {
        runCatching {
            val method = AppleReflection.findMethod(
                classLoader.loadClass("u8.a"),
                "a",
                parameterCount = 1
            )
            installHook(method, before = { chain ->
                val prefs = contentUiLanguagePrefs ?: return@installHook
                val selection = prefs.getInt(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
                )
                val storefront =
                    AppleInternalCatalogResolver.storefrontForContentUiLanguage(selection)
                        ?: return@installHook
                val language = internalCatalogResolver.languageTagForCurrentRequest(selection)
                    ?: return@installHook
                val httpChain = chain.args.firstOrNull() ?: return@installHook
                val request = AppleReflection.field(httpChain, "e") ?: return@installHook
                val rewritten = rewriteContentRequest(request, storefront, language)
                    ?: return@installHook
                AppleReflection.setField(httpChain, "e", rewritten)
            })
            ProviderLogger.info("Apple 内容 HTTP 本地化 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple 内容 HTTP 本地化 Hook 安装失败", it)
        }
    }

    private fun rewriteContentRequest(
        request: Any,
        storefront: String,
        language: String
    ): Any? {
        val url = AppleReflection.field(request, "a")?.toString().orEmpty()
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty()
        if (!host.contains("apple", ignoreCase = true)) return null

        val segments = uri.pathSegments.toMutableList()
        val pathStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(segments)
        val entityType = segments.getOrNull(3)
        val isPersonalizedContent = segments.take(3) == listOf("v1", "me", "recommendations")
        val isLyricsRequest = entityType == "songs" &&
            segments.lastOrNull()?.contains("lyrics") == true
        if (pathStorefront == null && !isPersonalizedContent) return null
        if (isLyricsRequest) return null
        if (
            pathStorefront != null &&
            !internalCatalogResolver.shouldPreserveCatalogStorefront(
                pathStorefront,
                entityType
            )
        ) {
            segments[2] = storefront
        }

        val builder = uri.buildUpon()
        builder.encodedPath(
            segments.joinToString(separator = "/", prefix = "/") { Uri.encode(it) }
        )
        builder.clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (name != "l") {
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
        }
        builder.appendQueryParameter("l", language)
        val rewrittenUrl = builder.build().toString()
        if (rewrittenUrl == url) return null

        val requestBuilder = AppleReflection.call(request, "b") ?: return null
        AppleReflection.call(requestBuilder, "h", rewrittenUrl)
        AppleReflection.call(requestBuilder, "d", "Accept-Language", language)
        return AppleReflection.call(requestBuilder, "b")
    }

    private fun hookTranslationPreference() {
        val preferencesClass =
            classLoader.loadClass("com.apple.android.music.utils.AppSharedPreferences")
        val method = AppleReflection.findMethod(
            preferencesClass,
            "setLyricsTranslationSelected",
            parameterCount = 1
        )
        installHook(method, after = { chain, _ ->
            (chain.args.firstOrNull() as? Boolean)?.let {
                PreferencesMonitor.notifyTranslationSelectedChanged(it)
            }
        })
    }

    private fun hookMediaMetadataChange() {
        val controller =
            classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
        val metadataMethod = AppleReflection.findMethod(
            controller,
            "onMetadataUpdated",
            parameterCount = 2
        )
        installHook(metadataMethod, after = { chain, _ ->
            val mediaPlayer = chain.args.firstOrNull()
            if (!isActivePlaybackCallback(mediaPlayer, activePlaybackPlayer)) {
                ProviderLogger.debug(
                    "忽略非活动播放器的歌曲元数据：source=onMetadataUpdated, " +
                        "callback=${mediaPlayer?.let(System::identityHashCode)}, " +
                        "active=${activePlaybackPlayer?.let(System::identityHashCode)}"
                )
                return@installHook
            }
            val callbackPlayer = mediaPlayer ?: return@installHook
            val changedItem = chain.args.getOrNull(1)
            val currentItem = runCatching {
                AppleReflection.call(callbackPlayer, "getCurrentItem")
            }.getOrNull()
            handleQueueItem(
                queueItem = changedItem,
                source = "onMetadataUpdated",
                publishAsCurrent = isCurrentQueueItem(changedItem, currentItem)
            )
        })

        val indexMethod = AppleReflection.findMethod(
            controller,
            "onPlaybackIndexChanged",
            parameterCount = 3
        )
        installHook(indexMethod, after = { chain, _ ->
            refreshCurrentQueueItemIfActive(
                chain.args.firstOrNull(),
                "onPlaybackIndexChanged"
            )
        })
        ProviderLogger.debug("歌曲元数据 Hook 已安装")
    }

    private fun refreshCurrentQueueItemIfActive(mediaPlayer: Any?, source: String) {
        if (!isActivePlaybackCallback(mediaPlayer, activePlaybackPlayer)) {
            ProviderLogger.debug(
                "忽略非活动播放器的歌曲元数据：source=$source, " +
                    "callback=${mediaPlayer?.let(System::identityHashCode)}, " +
                    "active=${activePlaybackPlayer?.let(System::identityHashCode)}"
            )
            return
        }
        refreshCurrentQueueItem(mediaPlayer, source)
    }

    private fun refreshCurrentQueueItem(mediaPlayer: Any?, source: String) {
        if (mediaPlayer == null) {
            ProviderLogger.debug("歌曲元数据刷新失败：$source 的 MediaPlayer 为空")
            return
        }
        runCatching {
            handleQueueItem(AppleReflection.call(mediaPlayer, "getCurrentItem"), source)
        }.onFailure {
            ProviderLogger.error("歌曲元数据刷新异常：source=$source", it)
        }
    }

    private fun handleQueueItem(
        queueItem: Any?,
        source: String,
        publishAsCurrent: Boolean = true
    ) {
        if (queueItem == null) {
            ProviderLogger.debug("歌曲元数据更新失败：$source 的 PlayerQueueItem 为空")
            return
        }
        runCatching {
            val mediaItem = AppleReflection.call(queueItem, "getItem") ?: return@runCatching
            val subscriptionStoreId =
                AppleReflection.call(mediaItem, "getSubscriptionStoreId") as? String
            val persistentId =
                AppleReflection.call(mediaItem, "getPersistentId") as? Long ?: 0L
            val mediaId = subscriptionStoreId
                ?.takeIf { it.isNotBlank() }
                ?: persistentId.takeIf { it > 0L }?.toString()
                ?: return@runCatching

            val metadata = MediaMetadataCache.Metadata(
                id = mediaId,
                title = AppleReflection.call(mediaItem, "getTitle") as? String,
                artist = AppleReflection.call(mediaItem, "getArtistName") as? String,
                genre = AppleReflection.call(mediaItem, "getGenreName") as? String,
                duration = AppleReflection.call(mediaItem, "getDuration") as? Long ?: 0L,
                queueId = AppleReflection.call(queueItem, "getPlaybackQueueId") as? Long ?: 0L
            )
            MediaMetadataCache.put(metadata)
            ProviderLogger.debug(
                "歌曲元数据已更新：source=$source, id=${metadata.id}, " +
                    "queueId=${metadata.queueId}, 标题=${metadata.title}"
            )
            if (publishAsCurrent) {
                PlaybackManager.onSongChanged(metadata.id)
                internalCatalogResolver.resolve(metadata) { alias ->
                    if (alias == null) return@resolve
                    MediaMetadataCache.updateOriginalMetadata(
                        mediaId = metadata.id,
                        title = alias.title,
                        artist = alias.artist
                    )
                    PlaybackManager.onCatalogMetadataResolved(metadata.id)
                }
            }
        }.onFailure {
            ProviderLogger.error("歌曲元数据解析异常：source=$source", it)
        }
    }

    private fun isCurrentQueueItem(candidate: Any?, current: Any?): Boolean {
        if (candidate == null || current == null) return false
        if (candidate === current) return true
        val candidateQueueId = AppleReflection.call(candidate, "getPlaybackQueueId") as? Long ?: 0L
        val currentQueueId = AppleReflection.call(current, "getPlaybackQueueId") as? Long ?: 0L
        if (candidateQueueId > 0L && currentQueueId > 0L) {
            return candidateQueueId == currentQueueId
        }
        val candidateMediaId = queueItemMediaId(candidate)
        val currentMediaId = queueItemMediaId(current)
        return candidateMediaId != null && candidateMediaId == currentMediaId
    }

    private fun queueItemMediaId(queueItem: Any): String? {
        val mediaItem = AppleReflection.call(queueItem, "getItem") ?: return null
        val subscriptionStoreId =
            AppleReflection.call(mediaItem, "getSubscriptionStoreId") as? String
        if (!subscriptionStoreId.isNullOrBlank()) return subscriptionStoreId
        val persistentId = AppleReflection.call(mediaItem, "getPersistentId") as? Long ?: 0L
        return persistentId.takeIf { it > 0L }?.toString()
    }

    private fun hookLyricBuildMethod() {
        val viewModelClass =
            classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
        val loadMethod = AppleReflection.findMethod(viewModelClass, "loadLyrics", parameterCount = 1)
        installHook(loadMethod, before = { chain ->
            val item = chain.args.firstOrNull() ?: return@installHook
            val source = if (lyricRequester.ownsViewModel(chain.thisObject)) "module" else "apple"
            val id = runCatching { AppleReflection.call(item, "getId") }.getOrNull()
            id?.toString()?.let { requestId ->
                pendingLyricsRequestSources
                    .computeIfAbsent(requestId) { ConcurrentLinkedQueue() }
                    .add(source)
            }
            val queueId = runCatching { AppleReflection.call(item, "getQueueId") }.getOrNull()
            val language = runCatching {
                chain.thisObject?.let {
                    AppleReflection.call(it, "getCurrentSystemLyricsLanguage")
                }
            }.getOrNull()
            ProviderLogger.debug(
                "loadLyrics：source=$source, id=$id, queueId=$queueId, language=$language"
            )
        })

        val buildMethod = AppleReflection.findMethod(
            viewModelClass,
            "buildTimeRangeToLyricsMap",
            parameterCount = 1
        )
        installHook(buildMethod, after = { chain, _ ->
            val pointer = chain.args.firstOrNull() ?: return@installHook
            val songNative = AppleReflection.call(pointer, "get") ?: return@installHook

            if (PreferencesMonitor.isTranslationSelected()) {
                val language = runCatching {
                    chain.thisObject?.let {
                        AppleReflection.call(it, "getCurrentSystemLyricsLanguage") as? String
                    }
                }.getOrNull()
                if (!language.isNullOrBlank()) {
                    val selected = runCatching {
                        AppleReflection.call(songNative, "setTranslation", language) as? Boolean
                    }.onFailure {
                        ProviderLogger.error("Apple 歌词翻译轨道选择失败：language=$language", it)
                    }.getOrNull()
                    ProviderLogger.debug(
                        "Apple 歌词翻译轨道选择：language=$language, selected=$selected"
                    )
                }
            }

            val source = if (lyricRequester.ownsViewModel(chain.thisObject)) "module" else "apple"
            PlaybackManager.onLyricsBuilt(songNative, source)
            applyConfiguredContentUiLanguage()
        })
        ProviderLogger.debug("歌词构建 Hook 已安装")
    }

    private fun hookExoMediaPlayer() {
        val exoPlayerClass =
            classLoader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")
        exoPlayerClass.declaredConstructors.forEach { constructor ->
            installHook(constructor, after = { chain, _ ->
                capturePlaybackPositionSource(
                    mediaPlayer = chain.thisObject,
                    source = "ExoMediaPlayer.<init>",
                    replace = false
                )
            })
        }
        hookExoPlaybackLifecycle(exoPlayerClass)

        val seekMethod = AppleReflection.findMethod(
            exoPlayerClass,
            "seekToPosition",
            parameterCount = 1
        )
        installHook(seekMethod, after = { chain, _ ->
            val position = chain.args.firstOrNull() as? Long ?: 0L
            if (isPlaying) player?.seekTo(position)
        })

        val controller =
            classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
        val stateMethod = AppleReflection.findMethod(
            controller,
            "onPlaybackStateChanged",
            parameterCount = 3
        )
        installHook(stateMethod, after = { chain, _ ->
            val activeMediaPlayer = chain.args.firstOrNull()
            when (PlaybackState.of(chain.args.getOrNull(2) as? Int ?: -1)) {
                PlaybackState.PLAYING -> {
                    activatePlaybackPlayer(
                        mediaPlayer = activeMediaPlayer,
                        source = "LocalMediaPlayerController.onPlaybackStateChanged"
                    )
                    refreshCurrentQueueItem(activeMediaPlayer, "onPlaybackStateChanged")
                    startSyncAction()
                }
                else -> {
                    if (activePlaybackPlayer === activeMediaPlayer) stopSyncAction()
                }
            }
        })
    }

    private fun startSyncAction() {
        if (isPlaying) return
        isPlaying = true
        player?.setPlaybackState(true)
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        isPlaying = false
        player?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive && isPlaying) {
                runCatching {
                    playbackPositionSource?.readPosition()?.let { position ->
                        logPositionSyncState(position)
                        player?.setPosition(position)
                    }
                }.onFailure {
                    ProviderLogger.error("读取 Apple Music 当前播放进度失败", it)
                }
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun hookExoPlaybackLifecycle(exoPlayerClass: Class<*>) {
        val playMethod = AppleReflection.findMethod(exoPlayerClass, "play", parameterCount = 0)
        installHook(playMethod, after = { chain, _ ->
            activatePlaybackPlayer(
                mediaPlayer = chain.thisObject,
                source = "ExoMediaPlayer.play"
            )
            refreshCurrentQueueItem(chain.thisObject, "ExoMediaPlayer.play")
            startSyncAction()
        })

        listOf("pause", "stop", "release").forEach { methodName ->
            val method = AppleReflection.findMethod(exoPlayerClass, methodName, parameterCount = 0)
            installHook(method, after = { chain, _ ->
                if (playbackPositionSource?.player === chain.thisObject) {
                    stopSyncAction()
                    if (methodName == "release") {
                        playbackPositionSource = null
                        if (activePlaybackPlayer === chain.thisObject) {
                            activePlaybackPlayer = null
                        }
                    }
                }
            })
        }
        ProviderLogger.info("Apple Music 播放生命周期 Hook 已安装")
    }

    private fun activatePlaybackPlayer(mediaPlayer: Any?, source: String) {
        if (mediaPlayer == null) return
        activePlaybackPlayer = mediaPlayer
        capturePlaybackPositionSource(
            mediaPlayer = mediaPlayer,
            source = source,
            replace = true
        )
    }

    private fun capturePlaybackPositionSource(
        mediaPlayer: Any?,
        source: String,
        replace: Boolean
    ) {
        if (mediaPlayer == null || (!replace && playbackPositionSource != null)) return
        val resolved = resolvePlaybackPositionSource(mediaPlayer)
        if (resolved == null) {
            ProviderLogger.error(
                "Apple Music 播放器缺少 getCurrentPosition：class=${mediaPlayer.javaClass.name}"
            )
            return
        }
        val previous = playbackPositionSource
        playbackPositionSource = resolved
        if (previous?.player !== mediaPlayer) {
            zeroPositionReadCount = 0
            hasLoggedNonZeroPosition = false
            ProviderLogger.info(
                "播放进度源已绑定：source=$source, class=${mediaPlayer.javaClass.name}, " +
                    "instance=${System.identityHashCode(mediaPlayer)}"
            )
        }
    }

    private fun logPositionSyncState(position: Long) {
        if (position > 0L) {
            if (!hasLoggedNonZeroPosition) {
                hasLoggedNonZeroPosition = true
                ProviderLogger.info("播放进度同步已启动：position=$position")
            }
            zeroPositionReadCount = 0
            return
        }
        zeroPositionReadCount += 1
        if (zeroPositionReadCount == 10) {
            val source = playbackPositionSource
            ProviderLogger.info(
                "播放进度连续为 0：class=${source?.player?.javaClass?.name}, " +
                    "instance=${source?.player?.let(System::identityHashCode)}"
            )
        }
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun initScreenStateMonitor() {
        ScreenStateMonitor.initialize(application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (isPlaying) resumeCoroutineTask()
            }

            override fun onScreenOff() = pauseCoroutineTask()

            override fun onScreenUnlocked() {
                if (isPlaying && progressJob == null) resumeCoroutineTask()
            }
        })
    }

    /** Debug-only request diagnostics; sensitive values are represented by length and hash. */
    private fun hookLyricsNetworkRequest() {
        runCatching {
            val method = AppleReflection.findMethod(
                classLoader.loadClass("t8.N0"),
                "z"
            )
            installHook(method, before = { chain ->
                @Suppress("UNCHECKED_CAST")
                val query = chain.args.getOrNull(5) as? MutableMap<String, String>
                    ?: return@installHook
                val id = chain.args.getOrNull(4)?.toString().orEmpty()
                val sourceQueue = pendingLyricsRequestSources[id]
                val source = sourceQueue?.poll() ?: "unknown"
                if (sourceQueue?.isEmpty() == true) {
                    pendingLyricsRequestSources.remove(id, sourceQueue)
                }
                ProviderLogger.debug(
                    "Lyrics network request: source=$source, id=$id, " +
                        "dsid=${sensitiveSummary(chain.args.getOrNull(0))}, " +
                        "userAgent=${sensitiveSummary(chain.args.getOrNull(1))}, " +
                        "authorization=${sensitiveSummary(chain.args.getOrNull(2))}, " +
                        "storefront=${chain.args.getOrNull(3)}, localizationQuery=$query"
                )
            })
        }.onFailure { ProviderLogger.error("Lyrics network request Hook 安装失败", it) }
    }

    private fun hookLyricsCookies() {
        runCatching {
            val method = AppleReflection.findMethod(
                classLoader.loadClass("s8.b"),
                "d",
                parameterCount = 1
            )
            installHook(method, after = { chain, result ->
                val url = chain.args.firstOrNull()?.toString().orEmpty()
                if (!url.contains("/syllable-lyrics")) return@installHook
                val cookies = (result as? Iterable<*>)?.mapNotNull { cookie ->
                    cookie ?: return@mapNotNull null
                    val name = AppleReflection.field(cookie, "a") as? String
                        ?: return@mapNotNull null
                    "$name(${sensitiveSummary(AppleReflection.field(cookie, "b"))})"
                }.orEmpty()
                ProviderLogger.debug("Lyrics CookieJar: url=$url, cookies=$cookies")
            })
        }.onFailure { ProviderLogger.error("Lyrics CookieJar Hook 安装失败", it) }
    }

    private fun hookFinalLyricsHttp() {
        runCatching {
            val method = AppleReflection.findMethod(
                classLoader.loadClass("u8.a"),
                "a",
                parameterCount = 1
            )
            installHook(
                method,
                before = { chain ->
                    val interceptor = chain.args.firstOrNull() ?: return@installHook
                    val request = AppleReflection.field(interceptor, "e") ?: return@installHook
                    val url = AppleReflection.field(request, "a")?.toString().orEmpty()
                    if (!url.contains("/syllable-lyrics")) return@installHook
                    val headers = AppleReflection.field(request, "c")
                    ProviderLogger.debug(
                        "Lyrics HTTP network request: url=$url, " +
                            "headers=${summarizeHeaders(headers, response = false)}"
                    )
                },
                after = { _, result ->
                    val response = result ?: return@installHook
                    val request = AppleReflection.field(response, "a") ?: return@installHook
                    val url = AppleReflection.field(request, "a")?.toString().orEmpty()
                    if (!url.contains("/syllable-lyrics")) return@installHook
                    val code = AppleReflection.intField(response, "d")
                    val headers = AppleReflection.field(response, "f")
                    ProviderLogger.debug(
                        "Lyrics HTTP network response: url=$url, code=$code, " +
                            "headers=${summarizeHeaders(headers, response = true)}"
                    )
                }
            )
        }.onFailure { ProviderLogger.error("Lyrics HTTP network Hook 安装失败", it) }
    }

    private fun sensitiveSummary(value: Any?): String {
        if (value == null) return "null"
        val text = value.toString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return "len=${text.length},sha256=$digest"
    }

    private fun summarizeHeaders(headers: Any?, response: Boolean): List<String> {
        if (headers == null) return emptyList()
        val values = AppleReflection.field(headers, "a") as? Array<*> ?: return emptyList()
        val safeNames = if (response) {
            setOf(
                "age", "cache-control", "content-length", "date", "etag", "expires",
                "last-modified", "via", "x-cache", "x-cache-hits"
            )
        } else {
            setOf(
                "accept", "accept-language", "cache-control", "content-type",
                "if-modified-since", "if-none-match", "pragma"
            )
        }
        return values.toList().chunked(2).mapNotNull { pair ->
            val name = pair.getOrNull(0)?.toString() ?: return@mapNotNull null
            val value = pair.getOrNull(1)?.toString().orEmpty()
            val rendered = if (name.lowercase() in safeNames) value else sensitiveSummary(value)
            "$name=$rendered"
        }
    }

    private fun installHook(
        executable: Executable,
        before: ((Chain) -> Unit)? = null,
        after: ((Chain, Any?) -> Unit)? = null
    ) {
        runCatching { module.deoptimize(executable) }
        module.hook(executable).intercept(CallbackHook(before, after))
    }

    private class CallbackHook(
        private val before: ((Chain) -> Unit)?,
        private val after: ((Chain, Any?) -> Unit)?
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            runCatching { before?.invoke(chain) }
                .onFailure { ProviderLogger.error("Apple Music Hook 前置回调失败", it) }
            val result = chain.proceed()
            runCatching { after?.invoke(chain, result) }
                .onFailure { ProviderLogger.error("Apple Music Hook 后置回调失败", it) }
            return result
        }
    }
}

internal data class PlaybackPositionSource(
    val player: Any,
    val getCurrentPosition: Method
) {
    fun readPosition(): Long? = getCurrentPosition.invoke(player) as? Long
}

internal fun resolvePlaybackPositionSource(mediaPlayer: Any?): PlaybackPositionSource? {
    mediaPlayer ?: return null
    val method = runCatching {
        AppleReflection.findMethod(
            mediaPlayer.javaClass,
            "getCurrentPosition",
            parameterCount = 0
        )
    }.getOrNull() ?: return null
    return PlaybackPositionSource(mediaPlayer, method)
}

internal fun isActivePlaybackCallback(callbackPlayer: Any?, activePlayer: Any?): Boolean =
    callbackPlayer != null && callbackPlayer === activePlayer
