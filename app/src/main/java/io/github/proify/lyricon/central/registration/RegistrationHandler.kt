/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.registration

import android.content.Intent
import android.util.Log
import com.juren233.hyperlyricsenhanced.root.utils.LyricRuntimeDiagnostics
import io.github.proify.lyricon.central.Constants
import io.github.proify.lyricon.central.ProviderFlowDiagnostics
import io.github.proify.lyricon.central.json
import io.github.proify.lyricon.central.provider.ProviderConnection
import io.github.proify.lyricon.central.provider.ProviderDirectory
import io.github.proify.lyricon.central.subscriber.SubscriberConnection
import io.github.proify.lyricon.central.subscriber.SubscriberDirectory
import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.subscriber.ISubscriberBinder
import io.github.proify.lyricon.subscriber.SubscriberInfo

internal class RegistrationHandler(
    private val providers: ProviderDirectory,
    private val subscribers: SubscriberDirectory
) {

    fun handle(intent: Intent) {
        LyricRuntimeDiagnostics.record("central_registration_dispatch") { "action=${intent.action}" }
        when (intent.action) {
            Constants.ACTION_REGISTER_PROVIDER -> registerProvider(intent)
            Constants.ACTION_REGISTER_SUBSCRIBER -> registerSubscriber(intent)
        }
    }

    private fun registerProvider(intent: Intent) {
        val binder = getBinder<IProviderBinder>(intent) ?: return
        var connection: ProviderConnection? = null

        try {
            val info = binder.providerInfo
                ?.toString(Charsets.UTF_8)
                ?.let { json.decodeFromString(ProviderInfo.serializer(), it) }

            if (info?.providerPackageName.isNullOrBlank() || info.playerPackageName.isBlank()) {
                LyricRuntimeDiagnostics.record("central_provider_rejected") { "reason=invalid_info" }
                Log.e(TAG, "Provider info is invalid: $info")
                return
            }

            connection = providers.getOrCreate(binder, info)
            Log.d(TAG, "Provider registered: $info")
            ProviderFlowDiagnostics.log("registration_callback_begin", info) {
                connection.diagnosticState()
            }
            binder.onRegistrationCallback(connection.service)
            ProviderFlowDiagnostics.log("registration_callback_returned", info) {
                connection.diagnosticState()
            }
        } catch (e: Exception) {
            ProviderFlowDiagnostics.log("registration_failed", connection?.providerInfo) {
                "connection=${ProviderFlowDiagnostics.id(connection)}, error=${e.javaClass.name}"
            }
            Log.e(TAG, "Provider registration failed", e)
            connection?.let { providers.unregister(it) }
        }
    }

    private fun registerSubscriber(intent: Intent) {
        val binder = getBinder<ISubscriberBinder>(intent) ?: return
        var connection: SubscriberConnection? = null

        try {
            val info = binder.subscriberInfo
                ?.toString(Charsets.UTF_8)
                ?.let { json.decodeFromString(SubscriberInfo.serializer(), it) }

            if (info?.packageName.isNullOrBlank() || info.processName.isBlank()) {
                LyricRuntimeDiagnostics.record("central_subscriber_rejected") { "reason=invalid_info" }
                Log.e(TAG, "Subscriber info is invalid: $info")
                return
            }

            connection = subscribers.getOrCreate(binder, info)
            Log.d(TAG, "Subscriber registered: $info")
            LyricRuntimeDiagnostics.record("central_subscriber_callback_begin") {
                "package=${info.packageName} process=${info.processName}"
            }
            binder.onRegistrationCallback(connection.service)
            LyricRuntimeDiagnostics.record("central_subscriber_callback_returned") {
                "package=${info.packageName} process=${info.processName}"
            }
        } catch (e: Exception) {
            LyricRuntimeDiagnostics.record("central_subscriber_failed") { "error=${e.javaClass.name}" }
            Log.e(TAG, "Subscriber registration failed", e)
            connection?.let { subscribers.unregister(it) }
        }
    }

    private inline fun <reified T> getBinder(intent: Intent): T? = runCatching {
        val binder = intent.getBundleExtra(Constants.EXTRA_BUNDLE)
            ?.getBinder(Constants.EXTRA_BINDER) ?: run {
            LyricRuntimeDiagnostics.record("central_registration_missing_binder") {
                "action=${intent.action} type=${T::class.java.simpleName}"
            }
            return null
        }

        when (T::class) {
            IProviderBinder::class -> IProviderBinder.Stub.asInterface(binder) as? T
            ISubscriberBinder::class -> ISubscriberBinder.Stub.asInterface(binder) as? T
            else -> {
                Log.e(TAG, "Unknown binder type: ${T::class.java.simpleName}")
                null
            }
        }
    }.onFailure {
        LyricRuntimeDiagnostics.record("central_registration_binder_failed") { "error=${it.javaClass.name}" }
        Log.e(TAG, "Failed to get binder from intent", it)
    }.getOrNull()

    private companion object {
        private const val TAG = "RegistrationHandler"
    }
}
