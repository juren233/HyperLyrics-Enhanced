package com.lidesheng.hyperlyric.root.island

import android.app.PendingIntent
import android.os.Bundle
import android.view.ViewGroup
import com.lidesheng.hyperlyric.root.SystemUiEnhancementGate

internal object IslandProbeUtils {
    const val LEFT_PARENT_NAME = "island_container_module_image_text_1"
    const val RIGHT_PARENT_NAME = "island_container_module_image_text_2"
    const val TEXT_CONTAINER_NAME = "island_container_module_text"

    const val LEFT_TEST_VIEW_TAG = "HYPERLYRIC_LEFT_VIEW"
    const val RIGHT_TEST_VIEW_TAG = "HYPERLYRIC_RIGHT_VIEW"
    const val LEFT_TEST_WRAPPER_TAG = "HYPERLYRIC_LEFT_VIEW_WRAPPER"
    const val RIGHT_TEST_WRAPPER_TAG = "HYPERLYRIC_RIGHT_VIEW_WRAPPER"

    fun isSuperIslandEnabled(): Boolean {
        return SystemUiEnhancementGate.isEnabled()
    }

    fun extractMediaIslandInfo(data: Any?): MediaIslandInfo? {
        val extras = extractExtras(data) ?: return null
        val pkgName = extras.getString("miui.pkg.name").orEmpty()
        if (pkgName.isEmpty()) return null
        if (!hasMediaPendingIntent(extras)) return null

        return MediaIslandInfo(
            packageName = pkgName
        )
    }

    fun isMediaIsland(data: Any?): Boolean {
        val extras = extractExtras(data) ?: return false
        return hasMediaPendingIntent(extras)
    }

    fun getCurrentIslandData(contentView: Any?): Any? {
        return contentView.callGetter("getCurrentIslandData")
    }

    fun getHolder(adapter: Any?, moduleType: String?): Any? {
        if (adapter == null || moduleType == null) return null
        val holders = adapter.javaClass.findField("holders")?.let { field ->
            runCatching {
                field.isAccessible = true
                field.get(adapter) as? Map<*, *>
            }.getOrNull()
        }
        return holders?.get(moduleType)
    }

    fun getHolderRootView(holder: Any?): ViewGroup? {
        return runCatching {
            holder?.javaClass?.methods?.find {
                it.name == "getRootView" && it.parameterTypes.isEmpty()
            }?.invoke(holder) as? ViewGroup
        }.getOrNull()
    }

    private fun extractExtras(data: Any?): Bundle? {
        return runCatching {
            data.callGetter("getExtras") as? Bundle
        }.getOrNull()
    }

    private fun hasMediaPendingIntent(extras: Bundle): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelable("miui.pending.intent") as? PendingIntent
        }.getOrNull() != null
    }

    private fun Any?.callGetter(name: String): Any? {
        val receiver = this ?: return null
        return runCatching {
            receiver.javaClass.methods.find {
                it.name == name && it.parameterTypes.isEmpty()
            }?.invoke(receiver)
        }.getOrNull()
    }

    private fun Class<*>.findField(name: String): java.lang.reflect.Field? {
        var current: Class<*>? = this
        while (current != null) {
            val field = current.declaredFields.find { it.name == name }
            if (field != null) return field
            current = current.superclass
        }
        return null
    }

    data class MediaIslandInfo(val packageName: String)
}
