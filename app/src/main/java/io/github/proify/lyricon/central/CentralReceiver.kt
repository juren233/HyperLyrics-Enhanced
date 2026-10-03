/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.juren233.hyperlyricsenhanced.root.utils.LyricRuntimeDiagnostics

internal object CentralReceiver : BroadcastReceiver() {

    private const val TAG = "CentralReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        LyricRuntimeDiagnostics.record("central_registration_received") {
            val sender = if (Build.VERSION.SDK_INT >= 34) {
                "senderUid=$sentFromUid senderPackage=$sentFromPackage"
            } else {
                "senderUid=unknown senderPackage=unknown"
            }
            "action=$action $sender"
        }
        Log.d(TAG, "Received intent: $action")

        BridgeCentral.handleRegistration(intent)
    }
}
