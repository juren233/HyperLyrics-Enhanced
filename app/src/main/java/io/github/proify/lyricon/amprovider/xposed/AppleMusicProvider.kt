/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import io.github.libxposed.api.XposedModule

/** Stable Apple Music provider entry point. */
object AppleMusicProvider {
    fun install(module: XposedModule, classLoader: ClassLoader) {
        AppleMusicProviderOrchestrator.install(module, classLoader)
    }

    internal fun hookModuleIdsForBuild(debug: Boolean): List<String> =
        AppleMusicProviderOrchestrator.hookModuleIdsForBuild(debug)
}
