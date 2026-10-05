package com.juren233.hyperlyricsenhanced.lyric.view.line

/** Numeric diagnostic whitelist, not text/content identities. Keep IDs stable for S0 comparison. */
internal object GeometryReason {
    const val OTHER = 0
    const val TEXT_SIZE = 1
    const val BIND = 2
    const val CONFIGURE = 3
    const val RESET = 4
    const val RESIZE = 5
    const val ROLE_SNAPSHOT = 6
    const val PROMOTION_SNAPSHOT = 7
}
