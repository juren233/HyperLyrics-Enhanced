package com.lidesheng.hyperlyric.utils

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.lidesheng.hyperlyric.R
import com.lidesheng.hyperlyric.root.RootApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.ServiceConstants
import com.lidesheng.hyperlyric.common.UIConstants

object BackupRestoreManager {
    suspend fun buildBackupJson(context: Context): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        val config = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (key == RootConstants.KEY_HOOK_AI_TRANS_API_KEY) return@forEach
            when (value) {
                is Boolean -> config.put(key, value)
                is Int -> config.put(key, value)
                is Float -> config.put(key, value.toDouble())
                is Long -> config.put(key, value)
                is String -> config.put(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    config.put(key, (value as Set<String>).joinToString(","))
                }
            }
        }
        JSONObject().apply {
            put("version", 1)
            put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            put("config", config)
        }.toString(2)
    }

    suspend fun restoreFromJson(context: Context, json: String): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        try {
            val root = JSONObject(json)
            if (root.optInt("version", -1) < 1) return@withContext false
            val config = root.getJSONObject("config")
            prefs.edit {
                val keys = config.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = config.get(key)
                    if (key == "key_send_normal_notification" || key == "key_send_focus_notification" || key == "key_persistent_foreground"
                        || key == RootConstants.KEY_HOOK_AI_TRANS_API_KEY) continue
                    if (key == ServiceConstants.KEY_NOTIFICATION_WHITELIST) {
                        val raw = value.toString()
                        val set = if (raw.isBlank()) emptySet() else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        putStringSet(key, set)
                        continue
                    }
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Double, is Float -> putFloat(key, (value as Number).toFloat())
                        is Long -> putLong(key, value)
                        is String -> putString(key, value)
                    }
                }
            }
            true
        } catch (_: Exception) { false }
    }
}

class BackupRestoreHelper(
    private val backupLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    private val restoreLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    fun launchBackup() {
        val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        backupLauncher.launch("hyperlyric_backup_$dateTime.json")
    }

    fun launchRestore() {
        restoreLauncher.launch(arrayOf("application/json"))
    }
}

@Composable
fun rememberBackupRestoreHelper(snackbarHostState: SnackbarHostState): BackupRestoreHelper {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val msgBackupSuccess = stringResource(R.string.toast_backup_success)
    val fmtBackupFailed = stringResource(R.string.toast_backup_failed)
    val msgRestoreEmpty = stringResource(R.string.toast_restore_empty)
    val msgRestoreSuccess = stringResource(R.string.toast_restore_success)
    val msgRestoreInvalid = stringResource(R.string.toast_restore_invalid)
    val msgRestoreFailed = stringResource(R.string.toast_restore_failed)

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                try {
                    val jsonBytes = BackupRestoreManager.buildBackupJson(context).toByteArray(Charsets.UTF_8)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(jsonBytes)
                            it.flush()
                        }
                    }
                    snackbarHostState.showSnackbar(
                        message = msgBackupSuccess,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(
                        message = fmtBackupFailed.format(e.message),
                        duration = SnackbarDuration.Custom(2000L)
                    )
                }
            }
        }
    )

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.bufferedReader(Charsets.UTF_8).readText()
                        } ?: ""
                    }
                    if (json.isBlank()) {
                        snackbarHostState.showSnackbar(
                            message = msgRestoreEmpty,
                            duration = SnackbarDuration.Custom(2000L)
                        )
                        return@launch
                    }
                    val success = BackupRestoreManager.restoreFromJson(context, json)
                    if (success) RootApplication.syncAllPreferences()
                    snackbarHostState.showSnackbar(
                        message = if (success) msgRestoreSuccess else msgRestoreInvalid,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                } catch (_: Exception) {
                    snackbarHostState.showSnackbar(
                        message = msgRestoreFailed,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                }
            }
        }
    )

    return remember(backupLauncher, restoreLauncher) {
        BackupRestoreHelper(backupLauncher, restoreLauncher)
    }
}
