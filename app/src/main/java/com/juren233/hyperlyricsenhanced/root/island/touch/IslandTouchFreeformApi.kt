/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.app.PendingIntent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.view.View
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Requests the native HyperIsland freeform launch with the current island's own intent. */
internal class IslandTouchFreeformApi private constructor(
    private val getData: Method,
    private val getExtras: Method,
    private val getCoordinator: Method,
    private val getKeyguardShowing: Method,
    private val openFreeform: Method,
) {
    private var firstRequestLogged = false

    fun open(content: View, packageName: String): Boolean {
        val profile = IslandTouchFreeformProfile
        val data = getData.invoke(content) ?: return false
        val extras = getExtras.invoke(data) as? Bundle ?: return false
        val coordinator = getCoordinator.invoke(content) ?: return false
        val notification = extras.getParcelable(profile.NOTIFICATION, StatusBarNotification::class.java)
        // Match the host's selection exactly: an existing SBN with no contentIntent
        // must not accidentally fall through to a different PendingIntent.
        val intent = if (notification != null) notification.notification.contentIntent
            else extras.getParcelable(profile.PENDING_INTENT, PendingIntent::class.java)
        if (!profile.canOpen(packageName, extras.getString(profile.PACKAGE_NAME),
                intent?.isActivity == true, getKeyguardShowing.invoke(coordinator) == true)) return false

        // Preserve user/profile and notification data without mutating the live island Bundle.
        // false is the native drag-to-freeform value; true is a separate workbench branch.
        val request = Bundle(extras).apply { putBoolean(profile.WORKBENCH_MODE, false) }
        openFreeform.invoke(coordinator, request)
        if (BuildConfig.DEBUG && !firstRequestLogged) {
            firstRequestLogged = true
            HookLogger.d("IslandTouch", "小窗启动入口首次调用已返回")
        }
        return true
    }

    companion object {
        fun create(loader: ClassLoader): IslandTouchFreeformApi {
            val profile = IslandTouchFreeformProfile
            val base = loader.loadClass(profile.BASE_CONTENT)
            val data = loader.loadClass(profile.DATA)
            val coordinator = loader.loadClass(profile.COORDINATOR)
            return IslandTouchFreeformApi(
                method(base, profile.GET_DATA, data),
                method(data, profile.GET_EXTRAS, Bundle::class.java),
                method(base, profile.GET_COORDINATOR, coordinator),
                method(coordinator, profile.GET_KEYGUARD_SHOWING, Boolean::class.javaPrimitiveType!!),
                method(coordinator, profile.OPEN_FREEFORM, Void.TYPE, Bundle::class.java),
            )
        }

        private fun method(owner: Class<*>, name: String, result: Class<*>, vararg args: Class<*>): Method =
            owner.getDeclaredMethod(name, *args).apply {
                check(returnType == result && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
    }
}
