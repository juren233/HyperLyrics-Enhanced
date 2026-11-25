package com.juren233.hyperlyricsenhanced.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.SetupFlowVersion
import com.juren233.hyperlyricsenhanced.common.MoreSettingsPreferences
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.provider.auto.ProviderAutoUpdateScheduler
import com.juren233.hyperlyricsenhanced.ui.navigation.AppNavigation
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.page.main.OneTapRefreshRequest
import com.juren233.hyperlyricsenhanced.ui.utils.AppUtils
import com.juren233.hyperlyricsenhanced.ui.utils.LocaleUtils
import com.juren233.hyperlyricsenhanced.ui.utils.ThemeUtils
import com.juren233.hyperlyricsenhanced.utils.UpdateData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val updateCheckScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateCheckJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(UIConstants.PREF_NAME, MODE_PRIVATE)
        val themeMode = prefs.getInt(UIConstants.KEY_THEME_MODE, UIConstants.DEFAULT_THEME_MODE)
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = when (themeMode) {
            1, 4 -> false
            2, 5 -> true
            else -> systemDark
        }
        window.setBackgroundDrawable(ColorDrawable(if (isDark) Color.BLACK else 0xFFF7F7F7.toInt()))

        val setupCompleted = prefs.getBoolean(UIConstants.KEY_SETUP_COMPLETED, UIConstants.DEFAULT_SETUP_COMPLETED) &&
            SetupFlowVersion.isCompleted(prefs)
        
        val excludeFromRecents = MoreSettingsPreferences.excludeFromRecents(prefs)
        if (excludeFromRecents) {
            try {
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                am.appTasks?.forEach { it.setExcludeFromRecents(true) }
            } catch (_: Exception) { }
        }

        // 隐藏图标状态持久保存在 PackageManager，这里按偏好收敛一次（如备份恢复后的漂移）
        AppUtils.applyLauncherIconVisibility(this, MoreSettingsPreferences.hideAppIcon(prefs))

        // 后台插件自动更新：注册周期任务，并按节流触发一次打开 App 的检查。
        ProviderAutoUpdateScheduler.onAppOpened(this)

        // 从插件更新通知进入：首页据此打开一键刷新面板（重建时不重复处理）。
        if (savedInstanceState == null) OneTapRefreshRequest.handle(intent)

        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false

        setContent {
            LocaleUtils.ProvideAppLocale {
                ThemeUtils.MiuixThemeWrapper {
                    AppNavigation(startRoute = if (setupCompleted) Route.Main else Route.Setup)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        OneTapRefreshRequest.handle(intent)
    }

    override fun onStart() {
        super.onStart()
        updateCheckJob?.cancel()
        updateCheckJob = updateCheckScope.launch {
            UpdateData.refresh(
                currentVersionName = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            )
        }
    }

    override fun onDestroy() {
        updateCheckScope.cancel()
        super.onDestroy()
    }
}
