/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.reload

/** Each module ClassLoader owns its own flag. Retired hooks only continue the native chain. */
internal object SystemUiHookLifetime {
    @Volatile var retired = false
}
