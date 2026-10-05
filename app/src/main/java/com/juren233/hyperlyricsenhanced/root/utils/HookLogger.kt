package com.juren233.hyperlyricsenhanced.root.utils

import android.content.SharedPreferences
import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.lyric.view.line.GeometryDiagnostics
import com.juren233.hyperlyricsenhanced.common.HyperLogger
import com.juren233.hyperlyricsenhanced.common.LogLevelPolicy
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
            if (BuildConfig.DEBUG) {
                // libxposed's framework tag is required for modules*.log / Export All Logs.
                runCatching {
                    GeometryDiagnostics.setHostLogger(
                        if (value == null) null else { message -> i("HLEGeomS0", message) }
                    )
                }
            }
        }

    override fun d(tag: String, msg: String) {
        if (readLogLevel() < 1) return
        val finalMsg = format(tag, msg)
        // Local JVM tests use Android stubs where Log.d throws; logging must never break logic.
        runCatching { Log.d(TAG, finalMsg) }
        // Let libxposed choose its module tag. Vector 2.2 only persists framework tags;
        // a custom tag reaches logcat but is silently omitted from modules*.log.
        runCatching { module?.log(Log.DEBUG, null, finalMsg) }
    }

    override fun i(tag: String, msg: String) {
        val finalMsg = format(tag, msg)
        runCatching { Log.i(TAG, finalMsg) }
        runCatching { module?.log(Log.INFO, null, finalMsg) }
    }

    override fun w(tag: String, msg: String, e: Throwable?) {
        val finalMsg = format(tag, msg)
        runCatching { Log.w(TAG, finalMsg, e) }
        runCatching { module?.log(Log.WARN, null, finalMsg, e) }
    }

    override fun e(tag: String, msg: String, e: Throwable?) {
        val finalMsg = format(tag, msg)
        runCatching { Log.e(TAG, finalMsg, e) }
        runCatching { module?.log(Log.ERROR, null, finalMsg, e) }
    }

    private fun readLogLevel(): Int = runCatching {
        val prefs = resolveLogPrefs()
        val storedLevel = prefs?.takeIf { it.contains(UIConstants.KEY_LOG_LEVEL) }
            ?.getInt(UIConstants.KEY_LOG_LEVEL, UIConstants.DEFAULT_LOG_LEVEL)
        val storedBuildKind = prefs?.getString(UIConstants.KEY_LOG_LEVEL_BUILD_KIND, null)
        LogLevelPolicy.effectiveLevel(
            storedLevel = storedLevel,
            storedBuildKind = storedBuildKind,
            debugBuild = BuildConfig.DEBUG,
        )
    }.getOrElse { LogLevelPolicy.defaultLevel(BuildConfig.DEBUG) }

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
