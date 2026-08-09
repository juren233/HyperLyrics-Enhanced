package com.juren233.hyperlyricsenhanced.root

import android.app.Application
import android.content.Context
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.PreferenceDiagnostics
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderScopeManager
import com.juren233.hyperlyricsenhanced.ui.utils.AppUtils
import com.juren233.hyperlyricsenhanced.ui.utils.LocaleUtils
import com.juren233.hyperlyricsenhanced.utils.LogManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class RootApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        LocaleUtils.clearLegacyPlatformLocale(this)
        AppUtils.initPredictiveBackGesture(this)
        LogManager.init(this)
        PrefsBridge.init(this)
        appContext = this

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                LogManager.i("PrefsBridge", "xposed_service_bound")
                syncAllPreferences(this@RootApplication)
                OfficialProviderScopeManager.requestConfiguredScopes(service)
            }
            override fun onServiceDied(service: XposedService) {
                xposedService = null
                LogManager.w("PrefsBridge", "xposed_service_died")
                OfficialProviderScopeManager.onServiceDied()
            }
        })
    }

    companion object {
        
        @JvmStatic
        var xposedService: XposedService? = null
            private set

        @JvmStatic
        fun syncPreference(group: String, key: String, value: Any?) {
            if (BuildConfig.DEBUG) {
                LogManager.i(
                    "PrefsBridge",
                    "sync_request group=$group key=$key " +
                        "type=${PreferenceDiagnostics.typeName(value)} " +
                        "value=${PreferenceDiagnostics.formatValue(key, value)}",
                )
            }
            val remotePrefs = try {
                xposedService?.getRemotePreferences(group)
            } catch (error: Exception) {
                LogManager.w("PrefsBridge", "remote_preferences_failed group=$group", error)
                null
            }
            if (remotePrefs == null) {
                LogManager.w("PrefsBridge", "sync_skipped reason=remote_unavailable group=$group key=$key")
                return
            }

            remotePrefs.edit().apply {
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                }
                apply()
            }
            if (BuildConfig.DEBUG) {
                val readBack = runCatching { remotePrefs.all[key] }
                    .getOrElse { error -> "<readback_failed:${error.javaClass.simpleName}>" }
                LogManager.i(
                    "PrefsBridge",
                    "sync_queued group=$group key=$key " +
                        "remote_readback=${PreferenceDiagnostics.formatValue(key, readBack)}",
                )
            }
        }

        @JvmStatic
        private fun syncAllPreferences(context: Context) {
            val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, MODE_PRIVATE)
            val allEntries = prefs.all
            LogManager.i("PrefsBridge", "sync_all_begin count=${allEntries.size}")
            if (allEntries.isEmpty()) {
                LogManager.i("PrefsBridge", "sync_all_end count=0")
                return
            }

            allEntries.forEach { (key, value) ->
                syncPreference(UIConstants.PREF_NAME, key, value)
            }
            LogManager.i("PrefsBridge", "sync_all_end count=${allEntries.size}")
        }

        @JvmStatic
        fun syncAllPreferences() {
            val context = appContext ?: return
            syncAllPreferences(context)
        }

        @JvmStatic
        internal fun currentContext(): Context? = appContext

        private var appContext: Context? = null
    }
}
