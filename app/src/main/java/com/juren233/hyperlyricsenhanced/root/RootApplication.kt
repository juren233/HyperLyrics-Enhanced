package com.juren233.hyperlyricsenhanced.root

import android.app.Application
import android.content.Context
import android.content.Intent
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.FeatureEntryConfig
import com.juren233.hyperlyricsenhanced.common.FeatureEntryInitializer
import com.juren233.hyperlyricsenhanced.common.IslandFontWeightMode
import com.juren233.hyperlyricsenhanced.common.LogLevelPolicy
import com.juren233.hyperlyricsenhanced.common.PreferenceDiagnostics
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.StorageKeyMigrator
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderScopeManager
import com.juren233.hyperlyricsenhanced.root.utils.RuntimePerfDiagnostics
import com.juren233.hyperlyricsenhanced.ui.utils.AppUtils
import com.juren233.hyperlyricsenhanced.ui.utils.LocaleUtils
import com.juren233.hyperlyricsenhanced.utils.LogManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class RootApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        IslandFontWeightMode.initialize(getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE))
        LocaleUtils.clearLegacyPlatformLocale(this)
        AppUtils.initPredictiveBackGesture(this)
        applyBuildDefaultLogLevel()
        LogManager.init(this)
        // debug 包专用：性能/功耗采样（含设置页窗口帧耗时），release 为空操作。
        RuntimePerfDiagnostics.start(
            app = this,
            scope = "module",
            attachActivityFrameMetrics = true,
        )
        PrefsBridge.init(this)
        StorageKeyMigrator.migrateLegacyIslandKeys(this)
        appContext = this
        initializeFeatureEntries()

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

    private fun applyBuildDefaultLogLevel() {
        val prefs = getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val currentBuildKind = LogLevelPolicy.buildKind(BuildConfig.DEBUG)
        if (prefs.getString(UIConstants.KEY_LOG_LEVEL_BUILD_KIND, null) == currentBuildKind) {
            return
        }
        prefs.edit()
            .putInt(
                UIConstants.KEY_LOG_LEVEL,
                LogLevelPolicy.defaultLevel(BuildConfig.DEBUG),
            )
            .putString(UIConstants.KEY_LOG_LEVEL_BUILD_KIND, currentBuildKind)
            .commit()
    }

    /**
     * 功能开关的一次性初始化。
     *
     * 仅在首次安装、或首次升级到带功能入口的版本时生效；已经存在的入口值（含用户手动开启的）
     * 不会被改写，因此后续版本更新不会再自动关闭这些入口。
     *
     * Apple Music 入口是例外：安装或卸载 Apple Music 时重新触发一次自动开关
     * （见 [FeatureEntryInitializer.syncAppleMusicEntryWithInstallState]），状态没变化时保持用户设置。
     * 写入后同步到宿主进程，使系统界面等进程立即读到新的入口与功能开关状态。
     */
    private fun initializeFeatureEntries() {
        val prefs = getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val appleMusicInstalled = FeatureEntryConfig.isAppleMusicInstalled(this)
        val writes = FeatureEntryInitializer.applyOnce(
            prefs = prefs,
            xiaomiDevice = FeatureEntryConfig.isXiaomiOrRedmiDevice(),
            appleMusicInstalled = appleMusicInstalled,
        ) + FeatureEntryInitializer.syncAppleMusicEntryWithInstallState(
            prefs = prefs,
            appleMusicInstalled = appleMusicInstalled,
        )
        writes.forEach { (key, value) -> PrefsBridge.putBoolean(key, value) }
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
            broadcastPreferenceChange(group, key, value)
        }

        private fun broadcastPreferenceChange(group: String, key: String, value: Any?) {
            if (group != UIConstants.PREF_NAME) return
            if (!LivePreferenceRefreshPolicy.contains(key)) return
            val intent = Intent(RootConstants.ACTION_REMOTE_PREFERENCE_CHANGED)
                .setPackage("com.android.systemui")
                .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_GROUP, group)
                .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_KEY, key)
            when (value) {
                null -> intent.putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "clear")
                is Boolean -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "boolean")
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_BOOLEAN, value)
                is Int -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "int")
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_INT, value)
                is Long -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "long")
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_LONG, value)
                is Float -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "float")
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_FLOAT, value)
                is String -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "string")
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_STRING, value)
                is Set<*> -> intent
                    .putExtra(RootConstants.EXTRA_REMOTE_PREFERENCE_TYPE, "string_set")
                    .putStringArrayListExtra(
                        RootConstants.EXTRA_REMOTE_PREFERENCE_STRING_SET,
                        ArrayList(value.filterIsInstance<String>()),
                    )
                else -> return
            }
            runCatching { appContext?.sendBroadcast(intent) }
                .onFailure { error ->
                    LogManager.w("PrefsBridge", "preference_broadcast_failed key=$key", error)
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
