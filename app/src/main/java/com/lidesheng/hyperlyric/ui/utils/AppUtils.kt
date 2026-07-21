package com.lidesheng.hyperlyric.ui.utils

import android.app.Application
import android.content.Context
import android.os.Build
import com.lidesheng.hyperlyric.common.UIConstants

object AppUtils {
    @JvmStatic
    fun initPredictiveBackGesture(application: Application) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val prefs = application.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
            val enablePredictiveBack = prefs.getBoolean(UIConstants.KEY_PREDICTIVE_BACK_GESTURE, UIConstants.DEFAULT_PREDICTIVE_BACK_GESTURE)
            runCatching {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
                val method = android.content.pm.ApplicationInfo::class.java.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(application.applicationInfo, enablePredictiveBack)
            }
        }
    }
}
