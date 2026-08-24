/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.notification

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.ClassicAodSongInfoConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

internal data class AodRuntimeDiagnosticState(
    val surface: String,
    val fullAod: Boolean? = null,
    val aodPanelShown: Boolean? = null,
    val playerShown: Boolean? = null,
    val playing: Boolean? = null,
    val pauseStyle: Int? = null,
    val pauseAllowed: Boolean? = null,
    val hasContent: Boolean? = null,
    val packageMatches: Boolean? = null,
    val showDecision: Boolean? = null,
    val decisionReason: String? = null,
    val overlayPresent: Boolean? = null,
    val overlayShown: Boolean? = null,
)

internal object AodEnvironmentPolicy {
    const val SHOW_STYLE_TEMPORARY = 0
    const val SHOW_STYLE_SCHEDULED = 1
    const val SHOW_STYLE_ALWAYS = 2
    const val SHOW_STYLE_SMART = 3

    /**
     * Verified from the original HyperOS 3 MIUIAod.apk DEX, not JADX aliases:
     * Lcom/miui/aod/settings/ShowModeFragment;->onPreferenceClick(
     * Landroidx/preference/Preference;)Z writes aod_show_style as
     * 0=aod_temporary_style, 1=aod_scheduled_style, 2=aod_always_style,
     * 3=aod_smart_style.
     */
    fun showStyleName(value: Int?): String = when (value) {
        SHOW_STYLE_TEMPORARY -> "temporary_10s_after_tap"
        SHOW_STYLE_SCHEDULED -> "scheduled"
        SHOW_STYLE_ALWAYS -> "always"
        SHOW_STYLE_SMART -> "smart_attention"
        null -> "missing"
        else -> "unknown"
    }

    fun enabledName(value: Int?): String = when (value) {
        1 -> "enabled"
        0 -> "disabled"
        null -> "missing"
        else -> "unknown"
    }

    fun pauseStyleName(value: Int?): String = when (value) {
        RootConstants.AOD_PAUSE_STYLE_RESTORE -> "restore_native"
        RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS -> "keep_lyrics"
        null -> "missing"
        else -> "unknown"
    }

    fun songInfoDisplayStyleName(value: Int?): String = when (value) {
        RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_NONE -> "none"
        RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION -> "focus_notification"
        RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_TEXT_EMBEDDED -> "embedded_text"
        null -> "missing"
        else -> "unknown"
    }

    fun formatMinuteOfDay(value: Int?): String {
        if (value == null) return "missing"
        if (value !in 0 until 24 * 60) return "invalid($value)"
        return "%02d:%02d".format(value / 60, value % 60)
    }
}

/**
 * Emits AOD environment and decision evidence at INFO in Debug builds so the default
 * "export all logs" path retains it even when the user has not enabled verbose logging.
 */
internal object AodEnvironmentDiagnostics {
    private const val TAG = "AOD_ENV"
    private const val AOD_PACKAGE = "com.miui.aod"
    private const val MAX_SIGNATURES = 96
    private const val SETTINGS_CACHE_MS = 2_000L
    private val lastSignatures = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedSettings: CachedAodSystemSettings? = null

    @Volatile
    private var cachedPackageVersion: String? = null

    fun log(
        context: Context,
        stage: String,
        modulePrefs: SharedPreferences?,
        view: View? = null,
        runtime: AodRuntimeDiagnosticState? = null,
        dedupeKey: String = stage,
    ) {
        if (!BuildConfig.DEBUG) return

        val moduleEnabled = modulePrefs?.getBoolean(
            RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
            RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS,
        )
        val classicPauseStyle = modulePrefs?.getInt(
            RootConstants.KEY_HOOK_CLASSIC_AOD_PAUSE_STYLE,
            RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE,
        )
        val lockScreenPauseStyle = modulePrefs?.getInt(
            RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_PAUSE_STYLE,
            RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE,
        )
        val songInfoStyle = modulePrefs?.let(ClassicAodSongInfoConfig::displayStyle)
        val settings = readSystemSettings(context)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val displayState = runCatching { context.display.state }.getOrNull()
        val packageVersion = aodPackageVersion(context)
        val signatureKey = "$stage/$dedupeKey"
        val signature = listOf(
            settings.dozeAlwaysOn,
            settings.userSet,
            settings.showStyle,
            settings.startMinute,
            settings.endMinute,
            settings.modeTime,
            settings.styleState,
            settings.fullScreenAod,
            settings.fullScreenNotification,
            settings.usingSuperWallpaper,
            settings.category,
            settings.ambientEnabled,
            moduleEnabled,
            classicPauseStyle,
            lockScreenPauseStyle,
            songInfoStyle,
            powerManager?.isInteractive,
            powerManager?.isPowerSaveMode,
            keyguardManager?.isKeyguardLocked,
            displayState,
            view?.isAttachedToWindow,
            view?.isShown,
            view?.visibility,
            view?.windowVisibility,
            view?.alpha,
            view?.width,
            view?.height,
            runtime,
        ).joinToString("|")
        if (lastSignatures.put(signatureKey, signature) == signature) return
        if (lastSignatures.size > MAX_SIGNATURES) {
            lastSignatures.clear()
            lastSignatures[signatureKey] = signature
        }

        val message = buildString {
            append("stage=").append(stage)
            append(", moduleEnabled=").append(moduleEnabled)
            append(", systemEnabled=").append(AodEnvironmentPolicy.enabledName(settings.dozeAlwaysOn))
                .append("(raw=").append(settings.dozeAlwaysOn).append(')')
            append(", userSet=").append(settings.userSet)
            append(", showStyle=").append(AodEnvironmentPolicy.showStyleName(settings.showStyle))
                .append("(raw=").append(settings.showStyle).append(')')
            append(", schedule=")
                .append(AodEnvironmentPolicy.formatMinuteOfDay(settings.startMinute))
                .append('-')
                .append(AodEnvironmentPolicy.formatMinuteOfDay(settings.endMinute))
                .append("(now=")
                .append(AodEnvironmentPolicy.formatMinuteOfDay(settings.currentMinute))
                .append(')')
            append(", modeTime=").append(settings.modeTime)
            append(", styleState=").append(settings.styleState)
            append(", fullScreenSetting=")
                .append(AodEnvironmentPolicy.enabledName(settings.fullScreenAod))
                .append("(raw=").append(settings.fullScreenAod).append(')')
            append(", fullScreenNotification=")
                .append(AodEnvironmentPolicy.enabledName(settings.fullScreenNotification))
                .append("(raw=").append(settings.fullScreenNotification).append(')')
            append(", ambientEnabled=").append(settings.ambientEnabled)
            append(", category=").append(settings.category ?: "missing")
            append(", superWallpaper=").append(settings.usingSuperWallpaper)
            append(", classicPause=")
                .append(AodEnvironmentPolicy.pauseStyleName(classicPauseStyle))
            append(", lockScreenPause=")
                .append(AodEnvironmentPolicy.pauseStyleName(lockScreenPauseStyle))
            append(", songInfoStyle=")
                .append(AodEnvironmentPolicy.songInfoDisplayStyleName(songInfoStyle))
            append(", interactive=").append(powerManager?.isInteractive)
            append(", powerSave=").append(powerManager?.isPowerSaveMode)
            append(", keyguardLocked=").append(keyguardManager?.isKeyguardLocked)
            append(", displayState=").append(displayStateName(displayState))
            append(", aodPackage=").append(packageVersion)
            if (view != null) {
                append(", view=").append(view.javaClass.name)
                append('@').append(System.identityHashCode(view).toString(16))
                append("(attached=").append(view.isAttachedToWindow)
                append(",shown=").append(view.isShown)
                append(",visibility=").append(view.visibility)
                append(",windowVisibility=").append(view.windowVisibility)
                append(",alpha=").append(view.alpha)
                append(",size=").append(view.width).append('x').append(view.height)
                append(')')
            }
            runtime?.let {
                append(", surface=").append(it.surface)
                append(", fullAod=").append(it.fullAod)
                append(", aodPanelShown=").append(it.aodPanelShown)
                append(", playerShown=").append(it.playerShown)
                append(", playing=").append(it.playing)
                append(", pauseStyle=").append(AodEnvironmentPolicy.pauseStyleName(it.pauseStyle))
                append(", pauseAllowed=").append(it.pauseAllowed)
                append(", hasContent=").append(it.hasContent)
                append(", packageMatches=").append(it.packageMatches)
                append(", showDecision=").append(it.showDecision)
                append(", reason=").append(it.decisionReason ?: "none")
                append(", overlayPresent=").append(it.overlayPresent)
                append(", overlayShown=").append(it.overlayShown)
            }
        }
        HookLogger.i(TAG, message)
    }

    private data class CachedAodSystemSettings(
        val capturedAtElapsedRealtime: Long,
        val dozeAlwaysOn: Int?,
        val userSet: Int?,
        val showStyle: Int?,
        val startMinute: Int?,
        val endMinute: Int?,
        val currentMinute: Int,
        val modeTime: Int?,
        val styleState: Int?,
        val fullScreenAod: Int?,
        val fullScreenNotification: Int?,
        val usingSuperWallpaper: Int?,
        val category: String?,
        val ambientEnabled: Int?,
    )

    private fun readSystemSettings(context: Context): CachedAodSystemSettings {
        val now = SystemClock.elapsedRealtime()
        cachedSettings?.takeIf { now - it.capturedAtElapsedRealtime < SETTINGS_CACHE_MS }
            ?.let { return it }
        return synchronized(this) {
            cachedSettings?.takeIf { now - it.capturedAtElapsedRealtime < SETTINGS_CACHE_MS }
                ?: context.contentResolver.let { resolver ->
                    CachedAodSystemSettings(
                        capturedAtElapsedRealtime = now,
                        dozeAlwaysOn = secureInt(resolver, "doze_always_on"),
                        userSet = secureInt(resolver, "aod_mode_user_set"),
                        showStyle = secureInt(resolver, "aod_show_style"),
                        startMinute = secureInt(resolver, "aod_start"),
                        endMinute = secureInt(resolver, "aod_end"),
                        currentMinute = Calendar.getInstance().let {
                            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
                        },
                        modeTime = secureInt(resolver, "aod_mode_time"),
                        styleState = secureInt(resolver, "aod_style_state"),
                        fullScreenAod = secureInt(resolver, "full_screen_aod_on"),
                        fullScreenNotification = secureInt(
                            resolver,
                            "full_screen_aod_notification",
                        ),
                        usingSuperWallpaper = secureInt(
                            resolver,
                            "aod_using_super_wallpaper",
                        ),
                        category = secureString(resolver, "aod_category_name"),
                        ambientEnabled = globalInt(resolver, "ambient_enabled"),
                    ).also { cachedSettings = it }
                }
        }
    }

    private fun aodPackageVersion(context: Context): String {
        cachedPackageVersion?.let { return it }
        return synchronized(this) {
            cachedPackageVersion ?: runCatching {
                val info = context.packageManager.getPackageInfo(AOD_PACKAGE, 0)
                "${info.versionName.orEmpty()}(${info.longVersionCode})"
            }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
                .also { cachedPackageVersion = it }
        }
    }

    private fun secureInt(resolver: android.content.ContentResolver, key: String): Int? =
        runCatching { Settings.Secure.getString(resolver, key)?.toIntOrNull() }.getOrNull()

    private fun secureString(resolver: android.content.ContentResolver, key: String): String? =
        runCatching { Settings.Secure.getString(resolver, key) }
            .getOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(80)

    private fun globalInt(resolver: android.content.ContentResolver, key: String): Int? =
        runCatching { Settings.Global.getString(resolver, key)?.toIntOrNull() }.getOrNull()

    private fun displayStateName(state: Int?): String = when (state) {
        Display.STATE_UNKNOWN -> "unknown(0)"
        Display.STATE_OFF -> "off(1)"
        Display.STATE_ON -> "on(2)"
        Display.STATE_DOZE -> "doze(3)"
        Display.STATE_DOZE_SUSPEND -> "doze_suspend(4)"
        Display.STATE_VR -> "vr(5)"
        Display.STATE_ON_SUSPEND -> "on_suspend(6)"
        null -> "missing"
        else -> "unknown($state)"
    }
}
