/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleHookRegistrar

/** Stable process dependencies shared by Apple Music Hook modules. */
internal class AppleMusicProviderRuntime(
    val module: XposedModule,
    initialClassLoader: ClassLoader,
) {
    private var attachedApplication: Application? = null
    private var attachedHookResolver: AppleMusicHookResolver? = null

    var classLoader: ClassLoader = initialClassLoader
        private set

    val application: Application
        get() = checkNotNull(attachedApplication) {
            "AppleMusicProviderRuntime is not attached to Application"
        }

    val hookResolver: AppleMusicHookResolver
        get() = checkNotNull(attachedHookResolver) {
            "AppleMusicProviderRuntime has no Hook resolver"
        }

    val isAttached: Boolean
        get() = attachedApplication != null && attachedHookResolver != null

    val mainHandler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    val hookRegistrar = AppleHookRegistrar(module)

    fun attach(
        application: Application,
        hookResolver: AppleMusicHookResolver,
    ) {
        attachedApplication = application
        classLoader = application.classLoader
        attachedHookResolver = hookResolver
    }
}
