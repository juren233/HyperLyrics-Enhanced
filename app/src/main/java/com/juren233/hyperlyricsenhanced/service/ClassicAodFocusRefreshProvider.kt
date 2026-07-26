package com.juren233.hyperlyricsenhanced.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.utils.LogManager

class ClassicAodFocusRefreshProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != RootConstants.CLASSIC_AOD_FOCUS_REFRESH_METHOD) {
            return super.call(method, arg, extras)
        }
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SYSTEM_UID) {
            LogManager.w(TAG, "拒绝非系统来源的 AOD 焦点通知刷新请求: uid=$callingUid")
            throw SecurityException("Only the system process can request an AOD focus refresh")
        }
        context?.let(LiveLyricService::requestClassicAodRefresh)
        return Bundle.EMPTY
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "ClassicAodFocusRefresh"
    }
}
