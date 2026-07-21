package com.lidesheng.hyperlyric.root

import android.app.Application
import android.content.Context
import com.lidesheng.hyperlyric.common.PrefsBridge
import com.lidesheng.hyperlyric.common.UIConstants
import com.lidesheng.hyperlyric.ui.utils.AppUtils
import com.lidesheng.hyperlyric.ui.utils.LocaleUtils
import com.lidesheng.hyperlyric.utils.LogManager
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
                syncAllPreferences(this@RootApplication)
            }
            override fun onServiceDied(service: XposedService) {
                xposedService = null
            }
        })
    }

    companion object {
        
        @JvmStatic
        var xposedService: XposedService? = null
            private set

        @JvmStatic
        fun syncPreference(group: String, key: String, value: Any?) {
            val remotePrefs = try {
                xposedService?.getRemotePreferences(group)
            } catch (_: Exception) {
                null
            } ?: return

            remotePrefs.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                }
                apply()
            }
        }

        @JvmStatic
        private fun syncAllPreferences(context: Context) {
            val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, MODE_PRIVATE)
            val allEntries = prefs.all
            if (allEntries.isEmpty()) return

            allEntries.forEach { (key, value) ->
                syncPreference(UIConstants.PREF_NAME, key, value)
            }
        }

        @JvmStatic
        fun syncAllPreferences() {
            val context = appContext ?: return
            syncAllPreferences(context)
        }

        private var appContext: Context? = null
    }
}
