package com.juren233.hyperlyricsenhanced.root.utils

import android.content.SharedPreferences
import android.util.Log
import com.juren233.hyperlyricsenhanced.common.HyperLogger
import com.juren233.hyperlyricsenhanced.common.UIConstants
import io.github.libxposed.api.XposedModule

private const val TAG = "HyperLyrics Enhanced"

object HookLogger : HyperLogger {
    @Volatile
    private var logPrefs: SharedPreferences? = null

    @Volatile
    private var logPrefsResolved = false

    var module: XposedModule? = null
        set(value) {
            field = value
            logPrefs = null
            logPrefsResolved = false
        }

    override fun d(tag: String, msg: String) {
        if (readLogLevel() < 1) return
        val finalMsg = format(tag, msg)
        Log.d(TAG, finalMsg)
        module?.log(Log.DEBUG, TAG, finalMsg)
    }

    override fun i(tag: String, msg: String) {
        val finalMsg = format(tag, msg)
        Log.i(TAG, finalMsg)
        module?.log(Log.INFO, TAG, finalMsg)
    }

    override fun w(tag: String, msg: String, e: Throwable?) {
        val finalMsg = format(tag, msg)
        Log.w(TAG, finalMsg, e)
        module?.log(Log.WARN, TAG, finalMsg, e)
    }

    override fun e(tag: String, msg: String, e: Throwable?) {
        val finalMsg = format(tag, msg)
        Log.e(TAG, finalMsg, e)
        module?.log(Log.ERROR, TAG, finalMsg, e)
    }

    private fun readLogLevel(): Int {
        val prefs = resolveLogPrefs()
        return prefs?.getInt(UIConstants.KEY_LOG_LEVEL, UIConstants.DEFAULT_LOG_LEVEL)
            ?: UIConstants.DEFAULT_LOG_LEVEL
    }

    private fun resolveLogPrefs(): SharedPreferences? {
        if (logPrefsResolved) return logPrefs
        synchronized(this) {
            if (!logPrefsResolved) {
                logPrefs = runCatching {
                    module?.getRemotePreferences(UIConstants.PREF_NAME)
                }.getOrNull()
                logPrefsResolved = true
            }
        }
        return logPrefs
    }

    private fun format(tag: String, msg: String): String {
        return "[${tag.trim()}] ${msg.trim()}"
    }
}
