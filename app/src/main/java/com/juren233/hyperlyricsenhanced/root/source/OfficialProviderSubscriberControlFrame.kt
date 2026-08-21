/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderNextTrackFrame

internal data class OfficialProviderSubscriberControlFrameDecision(
    val consumed: Boolean,
    val frame: OfficialProviderNextTrackFrame?,
)

/**
 * Filters HLE control frames that an older standalone Lyricon Central forwards as plain text.
 *
 * Current embedded Central consumes the frame before it reaches Subscriber callbacks. Older
 * standalone Central versions do not understand the reserved protocol and can forward the raw
 * Base64 payload through `onReceiveText`, which replaces valid timed lyrics with gibberish.
 */
internal object OfficialProviderSubscriberControlFrame {
    private val printablePrefix = OfficialProviderControlProtocol.NEXT_TRACK_PREFIX.drop(1)

    fun inspect(text: String?): OfficialProviderSubscriberControlFrameDecision {
        val normalized = when {
            OfficialProviderControlProtocol.isReservedFrame(text) -> text
            text?.startsWith(printablePrefix) == true ->
                OfficialProviderControlProtocol.NEXT_TRACK_PREFIX.first() + text
            else -> null
        } ?: return OfficialProviderSubscriberControlFrameDecision(
            consumed = false,
            frame = null,
        )

        return OfficialProviderSubscriberControlFrameDecision(
            consumed = true,
            frame = OfficialProviderControlProtocol.decodeNextTrack(normalized),
        )
    }
}
