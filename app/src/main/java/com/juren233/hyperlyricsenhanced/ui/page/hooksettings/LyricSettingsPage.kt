package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.ui.component.TextInputDialog
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
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

@Composable
fun LyricSettingsPage() {
    val navigator = LocalNavigator.current
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)

    var lyricSource by remember {
        mutableStateOf(
            prefs.getString(
                RootConstants.KEY_HOOK_LYRIC_SOURCE,
                RootConstants.DEFAULT_HOOK_LYRIC_SOURCE,
            ) ?: RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
        )
    }
    var removeCjkLyricSpaces by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_REMOVE_CJK_LYRIC_SPACES,
                RootConstants.DEFAULT_HOOK_REMOVE_CJK_LYRIC_SPACES,
            )
        )
    }
    var aiTransEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_AI_TRANS_ENABLE,
                RootConstants.DEFAULT_HOOK_AI_TRANS_ENABLE,
            )
        )
    }
    var autoIgnoreChinese by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_AI_TRANS_AUTO_IGNORE_CHINESE,
                RootConstants.DEFAULT_HOOK_AI_TRANS_AUTO_IGNORE_CHINESE,
            )
        )
    }
    var skipExistingTranslation by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION,
                RootConstants.DEFAULT_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION,
            )
        )
    }
    var forceAiTranslation by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_AI_TRANS_FORCE_OVERRIDE,
                RootConstants.DEFAULT_HOOK_AI_TRANS_FORCE_OVERRIDE,
            )
        )
    }
    var apiKey by remember {
        mutableStateOf(prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_API_KEY, "").orEmpty())
    }
    var model by remember {
        mutableStateOf(
            prefs.getString(
                RootConstants.KEY_HOOK_AI_TRANS_MODEL,
                RootConstants.DEFAULT_HOOK_AI_TRANS_MODEL,
            ) ?: RootConstants.DEFAULT_HOOK_AI_TRANS_MODEL
        )
    }
    var baseUrl by remember {
        mutableStateOf(
            prefs.getString(
                RootConstants.KEY_HOOK_AI_TRANS_BASE_URL,
                RootConstants.DEFAULT_HOOK_AI_TRANS_BASE_URL,
            ) ?: RootConstants.DEFAULT_HOOK_AI_TRANS_BASE_URL
        )
    }
    var targetLang by remember {
        mutableStateOf(
            prefs.getString(
                RootConstants.KEY_HOOK_AI_TRANS_TARGET_LANG,
                RootConstants.DEFAULT_HOOK_AI_TRANS_TARGET_LANG,
            ) ?: RootConstants.DEFAULT_HOOK_AI_TRANS_TARGET_LANG
        )
    }
    var prompt by remember {
        mutableStateOf(
            prefs.getString(
                RootConstants.KEY_HOOK_AI_TRANS_PROMPT,
                RootConstants.DEFAULT_HOOK_AI_TRANS_PROMPT,
            ) ?: RootConstants.DEFAULT_HOOK_AI_TRANS_PROMPT
        )
    }

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showBaseUrlDialog by remember { mutableStateOf(false) }
    var showTargetLangDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }

    TextInputDialog(
        show = showApiKeyDialog,
        title = stringResource(R.string.label_ai_trans_api_key),
        initialValue = apiKey,
        onDismiss = { showApiKeyDialog = false },
        onConfirm = {
            apiKey = it
            saveConfig(RootConstants.KEY_HOOK_AI_TRANS_API_KEY, it)
        },
    )
    TextInputDialog(
        show = showModelDialog,
        title = stringResource(R.string.label_ai_trans_model),
        initialValue = model,
        onDismiss = { showModelDialog = false },
        onConfirm = {
            model = it
            saveConfig(RootConstants.KEY_HOOK_AI_TRANS_MODEL, it)
        },
    )
    TextInputDialog(
        show = showBaseUrlDialog,
        title = stringResource(R.string.label_ai_trans_base_url),
        initialValue = baseUrl,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        onDismiss = { showBaseUrlDialog = false },
        onConfirm = {
            baseUrl = it
            saveConfig(RootConstants.KEY_HOOK_AI_TRANS_BASE_URL, it)
        },
    )
    TextInputDialog(
        show = showTargetLangDialog,
        title = stringResource(R.string.label_ai_trans_target_lang),
        initialValue = targetLang,
        onDismiss = { showTargetLangDialog = false },
        onConfirm = {
            targetLang = it
            saveConfig(RootConstants.KEY_HOOK_AI_TRANS_TARGET_LANG, it)
        },
    )
    TextInputDialog(
        show = showPromptDialog,
        title = stringResource(R.string.title_custom_prompt),
        initialValue = prompt,
        onDismiss = { showPromptDialog = false },
        onConfirm = {
            prompt = it
            saveConfig(RootConstants.KEY_HOOK_AI_TRANS_PROMPT, it)
        },
    )

    val sourceOptions = listOf(
        stringResource(R.string.lyric_source_lyricon),
        stringResource(R.string.lyric_source_superlyric),
        stringResource(R.string.lyric_source_lyricinfo),
    )
    val sourceIds = listOf("lyricon", "superlyric", "lyricinfo")

    XposedLyricSettingPage(title = stringResource(R.string.title_lyric_settings)) {
        item(key = "source_title") {
            SmallTitle(text = stringResource(R.string.title_lyric_acquisition_sources))
        }
        item(key = "lyric_source") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.title_lyric_source),
                    summary = stringResource(R.string.summary_lyric_source_apple_music_builtin),
                    items = sourceOptions,
                    selectedIndex = sourceIds.indexOf(lyricSource).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        lyricSource = sourceIds[index]
                        saveConfig(RootConstants.KEY_HOOK_LYRIC_SOURCE, lyricSource)
                    },
                )
                AnimatedVisibility(visible = lyricSource == "lyricon") {
                    ArrowPreference(
                        title = stringResource(R.string.title_lyric_provider),
                        onClick = { navigator.navigate(Route.LyricProvider) },
                    )
                }
            }
        }

        item(key = "display_title") {
            SmallTitle(text = stringResource(R.string.title_lyric_display_group))
        }
        item(key = "remove_cjk_lyric_spaces") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                SwitchPreference(
                    title = stringResource(R.string.title_remove_cjk_lyric_spaces),
                    summary = stringResource(R.string.summary_remove_cjk_lyric_spaces),
                    checked = removeCjkLyricSpaces,
                    onCheckedChange = {
                        removeCjkLyricSpaces = it
                        saveConfig(RootConstants.KEY_HOOK_REMOVE_CJK_LYRIC_SPACES, it)
                    },
                )
            }
        }

        item(key = "translation_title") {
            SmallTitle(text = stringResource(R.string.title_translation))
        }
        item(key = "translation") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.title_ai_translation),
                        checked = aiTransEnabled,
                        onCheckedChange = {
                            aiTransEnabled = it
                            saveConfig(RootConstants.KEY_HOOK_AI_TRANS_ENABLE, it)
                        },
                    )
                    AnimatedVisibility(visible = aiTransEnabled) {
                        Column {
                            SwitchPreference(
                                title = stringResource(
                                    R.string.title_ai_trans_auto_ignore_chinese
                                ),
                                checked = autoIgnoreChinese,
                                onCheckedChange = {
                                    autoIgnoreChinese = it
                                    saveConfig(
                                        RootConstants.KEY_HOOK_AI_TRANS_AUTO_IGNORE_CHINESE,
                                        it,
                                    )
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.title_ai_trans_skip_existing),
                                checked = skipExistingTranslation,
                                onCheckedChange = {
                                    skipExistingTranslation = it
                                    saveConfig(
                                        RootConstants.KEY_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION,
                                        it,
                                    )
                                    if (it && forceAiTranslation) {
                                        forceAiTranslation = false
                                        saveConfig(
                                            RootConstants.KEY_HOOK_AI_TRANS_FORCE_OVERRIDE,
                                            false,
                                        )
                                    }
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.title_ai_trans_force_override),
                                summary = stringResource(
                                    R.string.summary_ai_trans_force_override
                                ),
                                checked = forceAiTranslation,
                                onCheckedChange = {
                                    forceAiTranslation = it
                                    saveConfig(
                                        RootConstants.KEY_HOOK_AI_TRANS_FORCE_OVERRIDE,
                                        it,
                                    )
                                    if (it && skipExistingTranslation) {
                                        skipExistingTranslation = false
                                        saveConfig(
                                            RootConstants.KEY_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION,
                                            false,
                                        )
                                    }
                                },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.label_ai_trans_target_lang),
                                endActions = {
                                    Text(
                                        targetLang,
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                },
                                onClick = { showTargetLangDialog = true },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.label_ai_trans_api_key),
                                endActions = {
                                    Text(
                                        if (apiKey.isNotEmpty()) {
                                            "***************"
                                        } else {
                                            stringResource(R.string.summary_not_configured)
                                        },
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                },
                                onClick = { showApiKeyDialog = true },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.label_ai_trans_model),
                                endActions = {
                                    Text(
                                        model,
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                },
                                onClick = { showModelDialog = true },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.label_ai_trans_base_url),
                                summary = baseUrl,
                                onClick = { showBaseUrlDialog = true },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.title_custom_prompt),
                                summary = if (prompt.lines().size > 3) {
                                    prompt.lines().take(2).joinToString("\n") + "..."
                                } else {
                                    prompt
                                },
                                onClick = { showPromptDialog = true },
                            )
                        }
                    }
                }
            }
        }
    }
}
