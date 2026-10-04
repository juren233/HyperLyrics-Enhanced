/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.juren233.hyperlyricsenhanced.root.utils.LyricRuntimeDiagnostics

/** Both registration and original-metadata replies must explicitly share SystemUI's identity. */
internal fun Context.sendAppleDirectBroadcast(intent: Intent) {
    LyricRuntimeDiagnostics.record("direct_broadcast_sending") {
        "sdk=${Build.VERSION.SDK_INT} shareIdentityEnabled=${Build.VERSION.SDK_INT >= 34} " +
            "action=${intent.action} target=${intent.`package`}"
    }
    if (Build.VERSION.SDK_INT >= 34) {
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        sendBroadcast(intent, null, options)
    } else {
        sendBroadcast(intent)
    }
}

internal fun BroadcastReceiver.appleDirectSenderIdentity(
    context: Context,
    expectedPackage: String,
): AppleDirectIdentityPolicy.Result = AppleDirectIdentityPolicy.evaluate(
    sdkInt = Build.VERSION.SDK_INT,
    expectedPackage = expectedPackage,
    readSender = {
        // Keep API calls (including eagerly evaluated diagnostic strings) in the SDK branch.
        if (Build.VERSION.SDK_INT >= 34) {
            AppleDirectIdentityPolicy.Sender(sentFromUid, sentFromPackage)
        } else {
            AppleDirectIdentityPolicy.Sender()
        }
    },
    lookupExpectedUid = {
        // Query at receipt, including after a previous failure. Compare the full user-scoped UID.
        runCatching { context.packageManager.getApplicationInfo(expectedPackage, 0).uid }.getOrNull()
    },
)
