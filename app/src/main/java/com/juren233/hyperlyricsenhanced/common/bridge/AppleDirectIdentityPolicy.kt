/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

/** Unknown identity is a compatibility path, never proof of the sender's identity. */
internal object AppleDirectIdentityPolicy {
    data class Sender(val uid: Int = -1, val packageName: String? = null)

    enum class Decision(val accepted: Boolean) {
        AUTHENTICATED(true),
        LEGACY_UNKNOWN_IDENTITY(true),
        REJECTED_PACKAGE(false),
        REJECTED_UID(false),
        REJECTED_UID_LOOKUP_UNAVAILABLE(false),
    }

    data class Result(val sender: Sender, val expectedUid: Int?, val decision: Decision)

    /** No identity API or package lookup is called on Android 13. Lookups are never cached. */
    fun evaluate(
        sdkInt: Int,
        expectedPackage: String,
        readSender: () -> Sender,
        lookupExpectedUid: () -> Int?,
    ): Result {
        if (sdkInt < 34) return Result(Sender(), null, Decision.LEGACY_UNKNOWN_IDENTITY)
        val sender = readSender()
        val expectedUid = lookupExpectedUid()?.takeIf { it >= 0 }
        val decision = when {
            sender.packageName != null && sender.packageName != expectedPackage ->
                Decision.REJECTED_PACKAGE
            sender.uid >= 0 && expectedUid == null -> Decision.REJECTED_UID_LOOKUP_UNAVAILABLE
            sender.uid >= 0 && sender.uid != expectedUid -> Decision.REJECTED_UID
            sender.uid < 0 -> Decision.LEGACY_UNKNOWN_IDENTITY
            else -> Decision.AUTHENTICATED
        }
        return Result(sender, expectedUid, decision)
    }
}
