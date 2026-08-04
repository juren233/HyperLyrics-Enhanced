package com.juren233.hyperlyricsenhanced.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.utils.LogManager

class ClassicAodFocusRefreshProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == RootConstants.DEBUG_APPLE_PRONUNCIATION_DIAGNOSTIC_METHOD) {
            if (!BuildConfig.DEBUG) {
                throw SecurityException("Apple pronunciation diagnostics are debug-only")
            }
            val callingUid = Binder.getCallingUid()
            val callerPackages = context
                ?.packageManager
                ?.getPackagesForUid(callingUid)
                .orEmpty()
            if (APPLE_MUSIC_PACKAGE !in callerPackages) {
                LogManager.w(
                    TAG,
                    "拒绝非 Apple Music 的发音诊断请求: uid=$callingUid, " +
                        "packages=${callerPackages.toList()}"
                )
                throw SecurityException("Only Apple Music can report pronunciation diagnostics")
            }
            LogManager.i(
                APPLE_PRONUNCIATION_DIAGNOSTIC_TAG,
                arg.orEmpty().take(MAX_DIAGNOSTIC_CHARS),
            )
            return Bundle.EMPTY
        }
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
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        const val APPLE_PRONUNCIATION_DIAGNOSTIC_TAG = "ApplePronunciationDiag"
        const val MAX_DIAGNOSTIC_CHARS = 4_000
    }
}
