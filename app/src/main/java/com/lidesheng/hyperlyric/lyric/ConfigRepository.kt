package com.lidesheng.hyperlyric.lyric

import android.content.Context
import androidx.core.content.edit
import com.lidesheng.hyperlyric.common.PrefsBridge
import com.lidesheng.hyperlyric.common.ServiceConstants
import com.lidesheng.hyperlyric.common.UIConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConfigRepository {

    private val _whitelistState = MutableStateFlow<Set<String>>(emptySet())
    val whitelistState = _whitelistState.asStateFlow()

    fun initWhitelist(context: Context) {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, emptySet())?.toSet() ?: emptySet()
        _whitelistState.value = savedSet
    }

    fun addPackageToWhitelist(context: Context, packageName: String): Boolean {
        val currentSet = _whitelistState.value.toMutableSet()
        if (!currentSet.add(packageName)) return false
        saveWhitelist(context, currentSet)
        return true
    }

    fun removePackageFromWhitelist(context: Context, packageName: String) {
        val currentSet = _whitelistState.value.toMutableSet()
        if (currentSet.remove(packageName)) {
            saveWhitelist(context, currentSet)
        }
    }

    private fun saveWhitelist(context: Context, set: Set<String>) {
        _whitelistState.value = set
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE).edit {
            putStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, set)
        }
        PrefsBridge.putStringSet(ServiceConstants.KEY_NOTIFICATION_WHITELIST, set)
    }
}
