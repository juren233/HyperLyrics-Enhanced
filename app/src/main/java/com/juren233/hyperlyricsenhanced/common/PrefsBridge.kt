package com.juren233.hyperlyricsenhanced.common

import android.content.Context
import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.root.RootApplication
import com.juren233.hyperlyricsenhanced.utils.LogManager

object PrefsBridge {
    private const val TAG = "PrefsBridge"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PreferenceKeys.PREF_NAME, Context.MODE_PRIVATE)
        PreferenceDiagnostics.logSnapshot("app_local_init", requirePrefs(), ::log)
    }

    fun getPrefs(): SharedPreferences {
        return prefs ?: throw IllegalStateException("PrefsBridge not initialized. Call init() first.")
    }

    fun getBoolean(key: String, default: Boolean): Boolean = getPrefs().getBoolean(key, default)
    fun getInt(key: String, default: Int): Int = getPrefs().getInt(key, default)
    fun getString(key: String, default: String? = null): String? = getPrefs().getString(key, default)
    fun getLong(key: String, default: Long): Long = getPrefs().getLong(key, default)
    fun getFloat(key: String, default: Float): Float = getPrefs().getFloat(key, default)
    fun getStringSet(key: String, default: Set<String>? = null): Set<String>? = getPrefs().getStringSet(key, default)

    fun putBoolean(key: String, value: Boolean) {
        getPrefs().edit().putBoolean(key, value).apply()
        logWrite(key, value)
        RootApplication.syncPreference(PreferenceKeys.PREF_NAME, key, value)
    }

    fun putInt(key: String, value: Int) {
        getPrefs().edit().putInt(key, value).apply()
        logWrite(key, value)
        RootApplication.syncPreference(PreferenceKeys.PREF_NAME, key, value)
    }

    fun putString(key: String, value: String?) {
        getPrefs().edit().putString(key, value).apply()
        logWrite(key, value)
        RootApplication.syncPreference(PreferenceKeys.PREF_NAME, key, value)
    }

    fun putLong(key: String, value: Long) {
        getPrefs().edit().putLong(key, value).apply()
        logWrite(key, value)
        RootApplication.syncPreference(PreferenceKeys.PREF_NAME, key, value)
    }

    fun putFloat(key: String, value: Float) {
        getPrefs().edit().putFloat(key, value).apply()
        logWrite(key, value)
        RootApplication.syncPreference(PreferenceKeys.PREF_NAME, key, value)
    }

    fun putStringSet(key: String, value: Set<String>?) {
        getPrefs().edit().putStringSet(key, value).apply()
        logWrite(key, value)
        RootApplication.syncPreference(PreferenceKeys.PREF_NAME, key, value)
    }

    fun syncAllToRemote() {
        log("manual_sync_all_requested count=${requirePrefs().all.size}")
        RootApplication.syncAllPreferences()
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("PrefsBridge not initialized. Call init() first.")

    private fun logWrite(key: String, value: Any?) {
        log(
            "local_write key=$key type=${PreferenceDiagnostics.typeName(value)} " +
                "value=${PreferenceDiagnostics.formatValue(key, value)}",
        )
    }

    private fun log(message: String) {
        LogManager.i(TAG, message)
    }
}
