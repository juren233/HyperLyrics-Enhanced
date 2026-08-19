package com.juren233.hyperlyricsenhanced.root.salt

import android.app.Application
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHookHost
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderMetadata
import java.util.concurrent.atomic.AtomicBoolean

internal object SaltPlayerNextTrackHooker {
    private const val TAG = "SaltPlayerNextTrack"
    private const val PLAYER_PACKAGE = "com.salt.music"
    private const val BUILT_IN_PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced"
    private const val POLL_INTERVAL_MS = 1_500L
    private const val HEARTBEAT_MS = 5_000L

    private val installed = AtomicBoolean(false)

    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        packageName: String,
        processName: String,
    ) {
        if (packageName != PLAYER_PACKAGE || processName != PLAYER_PACKAGE) return
        if (!installed.compareAndSet(false, true)) return

        val host = OfficialProviderHookHost(module, classLoader, packageName, processName)
        host.hookApplication { app ->
            runCatching {
                val versionName = app.packageManager
                    .getPackageInfo(PLAYER_PACKAGE, 0)
                    .versionName
                    .orEmpty()
                val profile = SaltPlayerNextTrackHookProfiles.resolve(versionName)
                SaltPlayerNextTrackRuntime(app, host, profile).start()
                HookLogger.i(
                    TAG,
                    "椒盐模块本体下首 Hook 已启动: version=$versionName, " +
                        "nativeLyricon=${SaltPlayerNextTrackHookProfiles.usesNativeLyricon(versionName)}",
                )
            }.onFailure {
                installed.set(false)
                HookLogger.e(TAG, "椒盐原生 Lyricon 下首 Hook 初始化失败", it)
            }
        }
        HookLogger.i(TAG, "椒盐模块本体下首生命周期 Hook 已安装")
    }

    private class SaltPlayerNextTrackRuntime(
        private val application: Application,
        private val host: OfficialProviderHookHost,
        private val profile: SaltPlayerNextTrackProfile,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var provider: LyriconProvider? = null
        private var resolver: SaltPlayerNextTrackResolver? = null
        private var current = SaltPlayerCurrentTrack("", "", "", "", -1L)
        private var lastFrame: String? = null
        private var lastSentAt = 0L

        private val ticker = object : Runnable {
            override fun run() {
                capture()
                if (resolver != null) handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

        fun start() {
            check(Looper.myLooper() == Looper.getMainLooper())
            provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = BUILT_IN_PROVIDER_PACKAGE,
                playerPackageName = PLAYER_PACKAGE,
                metadata = ProviderMetadata(
                    mapOf(OfficialProviderControlProtocol.CONTROL_ONLY_METADATA_KEY to "true"),
                ),
            ).also { it.register() }
            host.hookMediaSession(
                playbackStateCallback = { /* Native Salt Lyricon owns playback state. */ },
                metadataCallback = { metadata -> onMetadata(metadata) },
            )
            host.resolveDexMethods(
                application = application,
                queries = listOf(SaltPlayerNextTrackHookProfiles.controllerQuery(profile)),
                callback = OfficialProviderDexMethodsCallback { targets ->
                    val target = targets.singleOrNull()
                    if (target == null) {
                        host.reportDexMethodValidation(
                            SaltPlayerNextTrackHookProfiles.CACHE_KEY,
                            false,
                            "resolved_targets=${targets.size}",
                        )
                        return@OfficialProviderDexMethodsCallback
                    }
                    handler.post {
                        runCatching {
                            resolver = SaltPlayerNextTrackResolver.create(application, profile, target)
                            handler.removeCallbacks(ticker)
                            handler.post(ticker)
                            HookLogger.i(TAG, "椒盐下首 DexKit 解析器已就绪: class=${target.className}")
                        }.onFailure {
                            host.reportDexMethodValidation(
                                SaltPlayerNextTrackHookProfiles.CACHE_KEY,
                                false,
                                "setup_${it.javaClass.simpleName}:${it.message}",
                            )
                            HookLogger.e(TAG, "椒盐下首 DexKit 解析器初始化失败", it)
                        }
                    }
                },
            )
        }

        private fun onMetadata(value: MediaMetadata?) {
            current = SaltPlayerCurrentTrack(
                id = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
                title = value?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = value?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                album = value?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L,
            )
            handler.post { capture() }
        }

        private fun capture() {
            val provider = provider ?: return
            val resolver = resolver ?: return
            val result = runCatching { resolver.resolve(current) }.getOrElse {
                host.reportDexMethodValidation(
                    SaltPlayerNextTrackHookProfiles.CACHE_KEY,
                    false,
                    "runtime_${it.javaClass.simpleName}:${it.message}",
                )
                return
            }
            host.reportDexMethodValidation(
                SaltPlayerNextTrackHookProfiles.CACHE_KEY,
                result.decoded,
                result.detail,
            )
            val frame = if (current.title.isBlank()) {
                OfficialProviderControlProtocol.encodeNextTrackClear()
            } else if (result.next == null) {
                OfficialProviderControlProtocol.encodeNextTrackClear(
                    currentId = current.id,
                    currentTitle = current.title,
                    currentArtist = current.artist,
                )
            } else {
                OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = current.id,
                    currentTitle = current.title,
                    currentArtist = current.artist,
                    nextId = result.next.id,
                    nextTitle = result.next.title,
                    nextArtist = result.next.artist,
                    nextAlbum = result.next.album,
                    nextDurationMs = result.next.durationMs,
                )
            }
            val now = SystemClock.elapsedRealtime()
            if (frame == lastFrame && now - lastSentAt < HEARTBEAT_MS) return
            if (provider.player.sendText(frame)) {
                lastFrame = frame
                lastSentAt = now
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "椒盐原生下首控制帧已发送: current=${current.id}, next=${result.next?.id}")
                }
            }
        }
    }
}
