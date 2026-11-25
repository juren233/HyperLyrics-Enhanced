package com.juren233.hyperlyricsenhanced.ui.utils

import android.app.Application
import android.content.Context
import android.os.Build
import com.juren233.hyperlyricsenhanced.common.MoreSettingsPreferences
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.utils.LogManager

object AppUtils {
    private const val LAUNCHER_ALIAS_CLASS = ".ui.MainLauncherAlias"

    @JvmStatic
    fun applyLauncherIconVisibility(context: Context, hidden: Boolean) {
        try {
            val pm = context.packageManager
            // ComponentName 不会展开点号前缀，必须用 createRelative 拼出全限定类名，
            // 否则 setComponentEnabledSetting 找不到组件直接抛 Unknown component
            val alias = android.content.ComponentName.createRelative(context.packageName, LAUNCHER_ALIAS_CLASS)
            val desiredState = if (hidden) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            if (pm.getComponentEnabledSetting(alias) != desiredState) {
                pm.setComponentEnabledSetting(alias, desiredState, android.content.pm.PackageManager.DONT_KILL_APP)
            }
        } catch (e: Exception) {
            LogManager.e("AppUtils", "切换桌面图标可见性失败", e)
        }
    }

    @JvmStatic
    fun initPredictiveBackGesture(application: Application) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val prefs = application.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
            val enablePredictiveBack = MoreSettingsPreferences.predictiveBackGesture(prefs)
            runCatching {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
                val method = android.content.pm.ApplicationInfo::class.java.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(application.applicationInfo, enablePredictiveBack)
            }
        }
    }
}
