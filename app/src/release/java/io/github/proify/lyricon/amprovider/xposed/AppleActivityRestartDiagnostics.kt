/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

/** Release installs no lifecycle/theme diagnostic hooks and keeps no trace state. */
internal object AppleActivityRestartDiagnostics {
    fun install(@Suppress("UNUSED_PARAMETER") runtime: AppleMusicProviderRuntime) = Unit
    fun stage(@Suppress("UNUSED_PARAMETER") stage: String) = Unit
}
