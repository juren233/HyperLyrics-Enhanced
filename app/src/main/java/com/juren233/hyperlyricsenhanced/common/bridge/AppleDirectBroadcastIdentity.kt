/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hyperlyricsenhanced.common.bridge

import com.juren233.hyperlyricsenhanced.root.reload.ModuleGeneration
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.juren233.hyperlyricsenhanced.contract.AppleSourceSwitchPerformanceDiagnostics

/** Both registration and original-metadata replies must explicitly share SystemUI's identity. */
internal fun Context.sendAppleDirectBroadcast(intent: Intent) {
    if (AppleSourceSwitchPerformanceDiagnostics.enabled) {
        Log.i(
            "AppleDirectBridge",
            "stage=direct_broadcast_sending sdk=${Build.VERSION.SDK_INT} " +
                "shareIdentityEnabled=${Build.VERSION.SDK_INT >= 34} " +
                "action=${intent.action} target=${intent.`package`}",
        )
    }
    sendBroadcastSharingIdentity(intent)
}

/**
 * Sends [intent] so its receivers can read the sender's UID and package
 * ([BroadcastReceiver.getSentFromUid] / [BroadcastReceiver.getSentFromPackage]); without
 * [BroadcastOptions.setShareIdentityEnabled] they read `INVALID_UID` / null. Both are API 34+; on
 * API 33 the broadcast goes without it. Every broadcast whose receiver checks the sender goes through here.
 */
internal fun Context.sendBroadcastSharingIdentity(intent: Intent) {
    if (!ModuleGeneration.mayPublish("broadcast ${intent.action}")) return
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
