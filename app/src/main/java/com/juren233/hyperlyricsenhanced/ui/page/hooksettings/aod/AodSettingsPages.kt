package com.juren233.hyperlyricsenhanced.ui.page.hooksettings.aod

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.ClassicAodSongInfoConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.mediacard.notification.AodMediaLyricPolicy
import com.juren233.hyperlyricsenhanced.service.LiveLyricService
import com.juren233.hyperlyricsenhanced.ui.component.NumberInputDialog
import com.juren233.hyperlyricsenhanced.ui.component.SimpleDialog
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class AodSettingsSpec(
    val titleRes: Int,
    val mainTextSizeKey: String,
    val backingTextSizeKey: String,
    val translationTextSizeKey: String,
    val showNextLyricKey: String,
    val nextLyricStyleKey: String,
    val duetLyricsKey: String,
    val centerNonDuetSongKey: String,
    val centerGroupVocalsKey: String,
    val pauseStyleKey: String,
    val translationDisplayKey: String,
    val translationFallbackKey: String,
    val swapTranslationKey: String,
    val nextSongPreviewKey: String,
    val nextSongPreviewPositionKey: String,
    val songInfoDisplayStyleKey: String? = null,
    val songInfoFormatKey: String? = null,
    val songInfoTextSizeKey: String? = null,
    val songInfoPositionKey: String? = null,
    val songInfoShowIconKey: String? = null,
    val defaultMainTextSize: Int,
    val defaultBackingTextSize: Int,
    val defaultTranslationTextSize: Int,
)

private data class FocusNotificationPrerequisites(
    val missingPostNotificationPermission: Boolean,
    val missingNotificationListenerPermission: Boolean,
    val showWhitelistReminder: Boolean,
) {
    val hasMissingItems: Boolean
        get() = missingPostNotificationPermission ||
            missingNotificationListenerPermission
}

@Composable
fun LockScreenAodSettingsPage() {
    AodSettingsPage(
        spec = AodSettingsSpec(
            titleRes = R.string.title_lock_screen_aod,
            mainTextSizeKey = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE,
            backingTextSizeKey = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE,
            translationTextSizeKey =
                RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE,
            showNextLyricKey = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_SHOW_NEXT_LYRIC,
            nextLyricStyleKey = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_LYRIC_STYLE,
            duetLyricsKey = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_DUET_LYRICS,
            centerNonDuetSongKey =
                RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_CENTER_NON_DUET_SONG,
            centerGroupVocalsKey =
                RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_CENTER_GROUP_VOCALS,
            pauseStyleKey = RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_PAUSE_STYLE,
            translationDisplayKey =
                RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_DISPLAY,
            translationFallbackKey =
                RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_TRANSLATION_FALLBACK,
            swapTranslationKey =
                RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_SWAP_TRANSLATION,
            nextSongPreviewKey =
                RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_SONG_PREVIEW,
            nextSongPreviewPositionKey =
                RootConstants.KEY_HOOK_LOCK_SCREEN_AOD_NEXT_SONG_PREVIEW_POSITION,
            defaultMainTextSize = RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE,
            defaultBackingTextSize =
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE,
            defaultTranslationTextSize =
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE,
        )
    )
}

@Composable
fun ClassicAodSettingsPage() {
    AodSettingsPage(
        spec = AodSettingsSpec(
            titleRes = R.string.title_classic_aod,
            mainTextSizeKey = RootConstants.KEY_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE,
            backingTextSizeKey = RootConstants.KEY_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE,
            translationTextSizeKey = RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE,
            showNextLyricKey = RootConstants.KEY_HOOK_CLASSIC_AOD_SHOW_NEXT_LYRIC,
            nextLyricStyleKey = RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_LYRIC_STYLE,
            duetLyricsKey = RootConstants.KEY_HOOK_CLASSIC_AOD_DUET_LYRICS,
            centerNonDuetSongKey =
                RootConstants.KEY_HOOK_CLASSIC_AOD_CENTER_NON_DUET_SONG,
            centerGroupVocalsKey = RootConstants.KEY_HOOK_CLASSIC_AOD_CENTER_GROUP_VOCALS,
            pauseStyleKey = RootConstants.KEY_HOOK_CLASSIC_AOD_PAUSE_STYLE,
            translationDisplayKey =
                RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_DISPLAY,
            translationFallbackKey =
                RootConstants.KEY_HOOK_CLASSIC_AOD_TRANSLATION_FALLBACK,
            swapTranslationKey =
                RootConstants.KEY_HOOK_CLASSIC_AOD_SWAP_TRANSLATION,
            nextSongPreviewKey = RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_SONG_PREVIEW,
            nextSongPreviewPositionKey =
                RootConstants.KEY_HOOK_CLASSIC_AOD_NEXT_SONG_PREVIEW_POSITION,
            songInfoDisplayStyleKey =
                RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_DISPLAY_STYLE,
            songInfoFormatKey = RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_FORMAT,
            songInfoTextSizeKey = RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE,
            songInfoPositionKey = RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_POSITION,
            songInfoShowIconKey = RootConstants.KEY_HOOK_CLASSIC_AOD_SONG_INFO_SHOW_ICON,
            defaultMainTextSize = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE,
            defaultBackingTextSize = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE,
            defaultTranslationTextSize =
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE,
        )
    )
}

@Composable
private fun AodSettingsPage(spec: AodSettingsSpec) {
    val context = LocalContext.current
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)
    var focusNotificationPrerequisites by remember {
        mutableStateOf<FocusNotificationPrerequisites?>(null)
    }
    var requestNotificationListenerAfterPostPermission by remember {
        mutableStateOf(false)
    }
    val inspectFocusNotificationPrerequisites: (Boolean) -> Unit = { showWhitelistToast ->
        val showWhitelistReminder = !prefs.getBoolean(
            RootConstants.KEY_HOOK_REMOVE_FOCUS_WHITELIST,
            RootConstants.DEFAULT_HOOK_REMOVE_FOCUS_WHITELIST,
        )
        val prerequisites = FocusNotificationPrerequisites(
            missingPostNotificationPermission =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED,
            missingNotificationListenerPermission =
                !NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName),
            showWhitelistReminder = showWhitelistReminder,
        )
        if (prerequisites.hasMissingItems) {
            focusNotificationPrerequisites = prerequisites
        } else {
            focusNotificationPrerequisites = null
            LiveLyricService.ensureListenerBound(context)
            if (showWhitelistToast && showWhitelistReminder) {
                Toast.makeText(
                    context,
                    R.string.toast_aod_focus_check_whitelist,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val notificationListenerSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        inspectFocusNotificationPrerequisites(false)
    }
    val appNotificationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val notificationPermissionGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (
            notificationPermissionGranted &&
            requestNotificationListenerAfterPostPermission
        ) {
            requestNotificationListenerAfterPostPermission = false
            notificationListenerSettingsLauncher.launch(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            )
        } else {
            requestNotificationListenerAfterPostPermission = false
            inspectFocusNotificationPrerequisites(false)
        }
    }

    var mainTextSize by remember(spec.mainTextSizeKey) {
        mutableIntStateOf(
            prefs.getInt(spec.mainTextSizeKey, spec.defaultMainTextSize).coerceIn(
                RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
                RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
            )
        )
    }
    var backingTextSize by remember(spec.backingTextSizeKey) {
        mutableIntStateOf(
            prefs.getInt(spec.backingTextSizeKey, spec.defaultBackingTextSize).coerceIn(
                RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
                RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
            )
        )
    }
    var translationTextSize by remember(spec.translationTextSizeKey) {
        mutableIntStateOf(
            prefs.getInt(
                spec.translationTextSizeKey,
                spec.defaultTranslationTextSize,
            ).coerceIn(
                RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
                RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
            )
        )
    }
    var showNextLyric by remember(spec.showNextLyricKey) {
        mutableStateOf(
            prefs.getBoolean(
                spec.showNextLyricKey,
                RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
            )
        )
    }
    var nextLyricStyle by remember(spec.nextLyricStyleKey) {
        mutableIntStateOf(
            prefs.getInt(
                spec.nextLyricStyleKey,
                RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE,
            ).coerceIn(
                RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING,
                RootConstants.AOD_NEXT_LYRIC_STYLE_TRANSLATION,
            )
        )
    }
    var duetLyrics by remember(spec.duetLyricsKey) {
        mutableStateOf(
            prefs.getBoolean(
                spec.duetLyricsKey,
                RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
            )
        )
    }
    var centerGroupVocals by remember(spec.centerGroupVocalsKey) {
        mutableStateOf(
            prefs.getBoolean(
                spec.centerGroupVocalsKey,
                RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
            )
        )
    }
    var centerNonDuetSong by remember(spec.centerNonDuetSongKey) {
        mutableStateOf(
            prefs.getBoolean(
                spec.centerNonDuetSongKey,
                RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
            )
        )
    }
    var pauseStyle by remember(spec.pauseStyleKey) {
        mutableIntStateOf(
            prefs.getInt(
                spec.pauseStyleKey,
                RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE,
            ).coerceIn(
                RootConstants.AOD_PAUSE_STYLE_RESTORE,
                RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS,
            )
        )
    }
    var translationDisplayMode by remember(spec.translationDisplayKey) {
        mutableIntStateOf(
            AodMediaLyricPolicy.readTranslationPronunciationMode(
                prefs = prefs,
                key = spec.translationDisplayKey,
                defaultValue = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY_MODE,
            )
        )
    }
    var translationFallback by remember(spec.translationFallbackKey) {
        mutableStateOf(
            prefs.getBoolean(
                spec.translationFallbackKey,
                RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_FALLBACK,
            )
        )
    }
    var swapTranslation by remember(spec.swapTranslationKey) {
        mutableStateOf(
            prefs.getBoolean(
                spec.swapTranslationKey,
                RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
            )
        )
    }
    var nextSongPreview by remember(spec.nextSongPreviewKey) {
        mutableStateOf(
            prefs.getBoolean(
                spec.nextSongPreviewKey,
                RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
            )
        )
    }
    var nextSongPreviewPosition by remember(spec.nextSongPreviewPositionKey) {
        mutableIntStateOf(
            prefs.getInt(
                spec.nextSongPreviewPositionKey,
                RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION,
            ).coerceIn(
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_LEFT,
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_RIGHT,
            )
        )
    }
    var songInfoDisplayStyle by remember(spec.songInfoDisplayStyleKey) {
        mutableIntStateOf(
            spec.songInfoDisplayStyleKey?.let {
                ClassicAodSongInfoConfig.displayStyle(prefs)
            } ?: RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_NONE
        )
    }
    var songInfoFormat by remember(spec.songInfoFormatKey) {
        mutableIntStateOf(
            spec.songInfoFormatKey?.let {
                ClassicAodSongInfoConfig.format(prefs)
            } ?: RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_FORMAT
        )
    }
    var songInfoPosition by remember(spec.songInfoPositionKey) {
        mutableIntStateOf(
            spec.songInfoPositionKey?.let {
                ClassicAodSongInfoConfig.embeddedPosition(prefs)
            } ?: RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_POSITION
        )
    }
    var songInfoTextSize by remember(spec.songInfoTextSizeKey) {
        mutableIntStateOf(
            spec.songInfoTextSizeKey?.let {
                ClassicAodSongInfoConfig.embeddedTextSize(prefs)
            } ?: RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE
        )
    }
    var showSongInfoIcon by remember(spec.songInfoShowIconKey) {
        mutableStateOf(
            spec.songInfoShowIconKey?.let {
                ClassicAodSongInfoConfig.showsEmbeddedIcon(prefs)
            } ?: RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_SHOW_ICON
        )
    }
    var showMainTextSizeDialog by remember { mutableStateOf(false) }
    var showBackingTextSizeDialog by remember { mutableStateOf(false) }
    var showTranslationTextSizeDialog by remember { mutableStateOf(false) }
    var showSongInfoTextSizeDialog by remember { mutableStateOf(false) }

    NumberInputDialog(
        show = showMainTextSizeDialog,
        title = stringResource(R.string.title_aod_main_text_size),
        label = stringResource(
            R.string.format_aod_text_size_placeholder,
            spec.defaultMainTextSize,
            RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
            RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
        ),
        initialValue = mainTextSize,
        min = RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
        onDismiss = { showMainTextSizeDialog = false },
        onConfirm = {
            mainTextSize = it
            saveConfig(spec.mainTextSizeKey, it)
        },
    )
    NumberInputDialog(
        show = showBackingTextSizeDialog,
        title = stringResource(R.string.title_aod_backing_text_size),
        label = stringResource(
            R.string.format_aod_text_size_placeholder,
            spec.defaultBackingTextSize,
            RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
            RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
        ),
        initialValue = backingTextSize,
        min = RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
        onDismiss = { showBackingTextSizeDialog = false },
        onConfirm = {
            backingTextSize = it
            saveConfig(spec.backingTextSizeKey, it)
        },
    )
    NumberInputDialog(
        show = showTranslationTextSizeDialog,
        title = stringResource(R.string.title_aod_translation_text_size),
        label = stringResource(
            R.string.format_aod_text_size_placeholder,
            spec.defaultTranslationTextSize,
            RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
            RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        ),
        initialValue = translationTextSize,
        min = RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        onDismiss = { showTranslationTextSizeDialog = false },
        onConfirm = {
            translationTextSize = it
            saveConfig(spec.translationTextSizeKey, it)
        },
    )
    NumberInputDialog(
        show = showSongInfoTextSizeDialog,
        title = stringResource(R.string.title_aod_song_info_text_size),
        label = stringResource(
            R.string.format_aod_text_size_placeholder,
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE,
            RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
            RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        ),
        initialValue = songInfoTextSize,
        min = RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        max = RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        onDismiss = { showSongInfoTextSizeDialog = false },
        onConfirm = {
            songInfoTextSize = it
            spec.songInfoTextSizeKey?.let { key -> saveConfig(key, it) }
        },
    )
    val missingPostNotificationText =
        stringResource(R.string.dialog_aod_focus_missing_post_notification)
    val missingNotificationListenerText =
        stringResource(R.string.dialog_aod_focus_missing_notification_listener)
    val focusWhitelistReminderText =
        stringResource(R.string.dialog_aod_focus_whitelist_reminder)
    val focusNotificationDialogSummary = focusNotificationPrerequisites?.let { prerequisites ->
        buildList {
            if (prerequisites.missingPostNotificationPermission) {
                add(missingPostNotificationText)
            }
            if (prerequisites.missingNotificationListenerPermission) {
                add(missingNotificationListenerText)
            }
            if (prerequisites.showWhitelistReminder) {
                add(focusWhitelistReminderText)
            }
        }.joinToString(separator = "\n")
    }
    SimpleDialog(
        show = focusNotificationPrerequisites != null,
        title = stringResource(R.string.dialog_aod_focus_prerequisites_title),
        summary = focusNotificationDialogSummary,
        confirmText = stringResource(R.string.action_go_to_authorize),
        onDismiss = { focusNotificationPrerequisites = null },
        onConfirm = {
            val prerequisites = focusNotificationPrerequisites ?: return@SimpleDialog
            when {
                prerequisites.missingPostNotificationPermission -> {
                    requestNotificationListenerAfterPostPermission =
                        prerequisites.missingNotificationListenerPermission
                    appNotificationSettingsLauncher.launch(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
                prerequisites.missingNotificationListenerPermission -> {
                    notificationListenerSettingsLauncher.launch(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    )
                }
                else -> inspectFocusNotificationPrerequisites(false)
            }
        },
    )

    XposedLyricSettingPage(title = stringResource(spec.titleRes)) {
        item(key = "aod_text_style") {
            SmallTitle(text = stringResource(R.string.title_text))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.title_aod_main_text_size),
                        endActions = {
                            AodTextSizeValue(value = mainTextSize)
                        },
                        onClick = { showMainTextSizeDialog = true },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.title_aod_backing_text_size),
                        endActions = {
                            AodTextSizeValue(value = backingTextSize)
                        },
                        onClick = { showBackingTextSizeDialog = true },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.title_aod_translation_text_size),
                        endActions = {
                            AodTextSizeValue(value = translationTextSize)
                        },
                        onClick = { showTranslationTextSizeDialog = true },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.title_next_lyric_line),
                        summary = stringResource(R.string.summary_aod_show_next_lyric),
                        checked = showNextLyric,
                        onCheckedChange = {
                            showNextLyric = it
                            saveConfig(spec.showNextLyricKey, it)
                        },
                    )
                    AnimatedVisibility(visible = showNextLyric) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.title_aod_next_lyric_style),
                            items = listOf(
                                stringResource(R.string.option_aod_next_lyric_backing),
                                stringResource(R.string.option_aod_next_lyric_translation),
                            ),
                            selectedIndex = nextLyricStyle,
                            onSelectedIndexChange = {
                                nextLyricStyle = it
                                saveConfig(spec.nextLyricStyleKey, it)
                            },
                        )
                    }
                }
            }
        }
        item(key = "aod_display_style") {
            SmallTitle(text = stringResource(R.string.title_aod_display_style))
            spec.songInfoDisplayStyleKey?.let { songInfoDisplayStyleKey ->
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .fillMaxWidth()
                ) {
                    Column {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.title_aod_song_info),
                            summary = if (
                                songInfoDisplayStyle ==
                                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION
                            ) {
                                stringResource(R.string.summary_aod_song_info_focus_notification)
                            } else {
                                null
                            },
                            items = listOf(
                                stringResource(R.string.option_aod_song_info_none),
                                stringResource(R.string.option_aod_song_info_focus_notification),
                                stringResource(R.string.option_aod_song_info_text_embedded),
                            ),
                            selectedIndex = songInfoDisplayStyle,
                            onSelectedIndexChange = {
                                songInfoDisplayStyle = it
                                saveConfig(songInfoDisplayStyleKey, it)
                                if (it ==
                                    RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_FOCUS_NOTIFICATION
                                ) {
                                    inspectFocusNotificationPrerequisites(true)
                                }
                            },
                        )
                        AnimatedVisibility(
                            visible = songInfoDisplayStyle !=
                                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_NONE
                        ) {
                            spec.songInfoFormatKey?.let { songInfoFormatKey ->
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.title_aod_song_info_format),
                                    items = listOf(
                                        stringResource(R.string.option_aod_song_info_title),
                                        stringResource(R.string.option_aod_song_info_title_artist),
                                        stringResource(R.string.option_aod_song_info_artist_title),
                                    ),
                                    selectedIndex = songInfoFormat -
                                        RootConstants.AOD_SONG_INFO_FORMAT_TITLE,
                                    onSelectedIndexChange = {
                                        songInfoFormat = it +
                                            RootConstants.AOD_SONG_INFO_FORMAT_TITLE
                                        saveConfig(songInfoFormatKey, songInfoFormat)
                                    },
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = songInfoDisplayStyle ==
                                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_TEXT_EMBEDDED
                        ) {
                            Column {
                                spec.songInfoTextSizeKey?.let {
                                    ArrowPreference(
                                        title = stringResource(
                                            R.string.title_aod_song_info_text_size
                                        ),
                                        endActions = {
                                            AodTextSizeValue(value = songInfoTextSize)
                                        },
                                        onClick = { showSongInfoTextSizeDialog = true },
                                    )
                                }
                                spec.songInfoPositionKey?.let { songInfoPositionKey ->
                                    OverlayDropdownPreference(
                                        title = stringResource(
                                            R.string.title_aod_song_info_position
                                        ),
                                        items = listOf(
                                            stringResource(
                                                R.string.option_aod_song_info_position_left
                                            ),
                                            stringResource(
                                                R.string.option_aod_song_info_position_center
                                            ),
                                            stringResource(
                                                R.string.option_aod_song_info_position_right
                                            ),
                                        ),
                                        selectedIndex = songInfoPosition,
                                        onSelectedIndexChange = {
                                            songInfoPosition = it
                                            saveConfig(songInfoPositionKey, it)
                                        },
                                    )
                                }
                                spec.songInfoShowIconKey?.let { songInfoShowIconKey ->
                                    SwitchPreference(
                                        title = stringResource(
                                            R.string.title_aod_song_info_icon
                                        ),
                                        checked = showSongInfoIcon,
                                        onCheckedChange = {
                                            showSongInfoIcon = it
                                            saveConfig(songInfoShowIconKey, it)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.title_aod_pause_style),
                        items = listOf(
                            stringResource(R.string.option_aod_pause_style_restore),
                            stringResource(R.string.option_aod_pause_style_keep_lyrics),
                        ),
                        selectedIndex = pauseStyle,
                        onSelectedIndexChange = {
                            pauseStyle = it
                            saveConfig(spec.pauseStyleKey, it)
                        },
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.title_translation_pronunciation_display),
                        items = listOf(
                            stringResource(R.string.option_translation_pronunciation_off),
                            stringResource(R.string.option_translation_pronunciation_translation),
                            stringResource(R.string.option_translation_pronunciation_pronunciation),
                        ),
                        selectedIndex = translationDisplayMode,
                        onSelectedIndexChange = {
                            translationDisplayMode = it
                            saveConfig(spec.translationDisplayKey, it)
                        },
                    )
                    AnimatedVisibility(
                        visible = translationDisplayMode != RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        SwitchPreference(
                            title = stringResource(R.string.title_translation_pronunciation_fallback),
                            checked = translationFallback,
                            onCheckedChange = {
                                translationFallback = it
                                saveConfig(spec.translationFallbackKey, it)
                            },
                        )
                    }
                    SwitchPreference(
                        title = stringResource(R.string.title_swap_translation),
                        checked = swapTranslation,
                        onCheckedChange = {
                            swapTranslation = it
                            saveConfig(spec.swapTranslationKey, it)
                        },
                        enabled = translationDisplayMode != RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.title_aod_duet_lyrics),
                        summary = stringResource(R.string.summary_aod_duet_lyrics),
                        checked = duetLyrics,
                        onCheckedChange = {
                            duetLyrics = it
                            saveConfig(spec.duetLyricsKey, it)
                        },
                    )
                    AnimatedVisibility(visible = duetLyrics) {
                        Column {
                            SwitchPreference(
                                title = stringResource(
                                    R.string.title_aod_center_non_duet_song
                                ),
                                checked = centerNonDuetSong,
                                onCheckedChange = {
                                    centerNonDuetSong = it
                                    saveConfig(spec.centerNonDuetSongKey, it)
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.title_center_group_vocals),
                                checked = centerGroupVocals,
                                onCheckedChange = {
                                    centerGroupVocals = it
                                    saveConfig(spec.centerGroupVocalsKey, it)
                                },
                            )
                        }
                    }
                    SwitchPreference(
                        title = stringResource(R.string.title_aod_next_song_preview),
                        summary = stringResource(R.string.summary_island_next_song_preview),
                        checked = nextSongPreview,
                        onCheckedChange = {
                            nextSongPreview = it
                            saveConfig(spec.nextSongPreviewKey, it)
                        },
                    )
                    AnimatedVisibility(visible = nextSongPreview) {
                        OverlayDropdownPreference(
                            title = stringResource(
                                R.string.title_aod_next_song_preview_position
                            ),
                            items = listOf(
                                stringResource(R.string.option_aod_song_info_position_left),
                                stringResource(R.string.option_aod_song_info_position_center),
                                stringResource(R.string.option_aod_song_info_position_right),
                            ),
                            selectedIndex = nextSongPreviewPosition,
                            onSelectedIndexChange = {
                                nextSongPreviewPosition = it
                                saveConfig(spec.nextSongPreviewPositionKey, it)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AodTextSizeValue(
    value: Int,
) {
    Text(
        text = value.toString(),
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}
