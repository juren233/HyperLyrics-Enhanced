/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.app.Instrumentation
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Static host for official Provider Packs.
 *
 * Only this class touches libxposed. Pack callbacks receive ordinary Android
 * values and never receive [XposedModule] or [XposedInterface.Chain].
 */
internal class OfficialProviderHookHost(
    private val module: XposedModule,
    private val targetClassLoader: ClassLoader,
    override val packageName: String,
) : OfficialProviderHost {
    private val tag = "OfficialProviderHookHost"

    override fun hookApplication(callback: OfficialProviderApplicationCallback) {
        val method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate",
            Application::class.java,
        )
        module.hook(method).intercept(ApplicationCreatedHooker(packageName, callback))
    }

    override fun hookMediaSession(
        playbackStateCallback: OfficialProviderPlaybackStateCallback,
        metadataCallback: OfficialProviderMetadataCallback,
    ) {
        val mediaSessionClass = Class.forName(
            MediaSession::class.java.name,
            false,
            targetClassLoader,
        )
        val setPlaybackState = mediaSessionClass.getDeclaredMethod(
            "setPlaybackState",
            PlaybackState::class.java,
        )
        module.hook(setPlaybackState).intercept(
            PlaybackStateHooker(playbackStateCallback),
        )

        val setMetadata = mediaSessionClass.getDeclaredMethod(
            "setMetadata",
            MediaMetadata::class.java,
        )
        module.hook(setMetadata).intercept(MetadataHooker(metadataCallback))
    }

    private class ApplicationCreatedHooker(
        private val expectedPackageName: String,
        private val callback: OfficialProviderApplicationCallback,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val application = chain.args.firstOrNull() as? Application
            if (application?.packageName == expectedPackageName) {
                runCatching { callback.onApplicationCreated(application) }
            }
            return result
        }
    }

    private class PlaybackStateHooker(
        private val callback: OfficialProviderPlaybackStateCallback,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                callback.onPlaybackStateChanged(chain.args.firstOrNull() as? PlaybackState)
            }
            return result
        }
    }

    private class MetadataHooker(
        private val callback: OfficialProviderMetadataCallback,
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                callback.onMetadataChanged(chain.args.firstOrNull() as? MediaMetadata)
            }
            return result
        }
    }

    fun logInstalled(pluginId: String) {
        module.log(
            Log.INFO,
            tag,
            "官方 Provider Hook 已安装: id=$pluginId package=$packageName",
        )
    }
}
