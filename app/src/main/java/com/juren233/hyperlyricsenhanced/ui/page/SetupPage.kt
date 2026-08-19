package com.juren233.hyperlyricsenhanced.ui.page

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.lyric.ConfigRepository
import com.juren233.hyperlyricsenhanced.lyric.commonMusicApps
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderItem
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRepository
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderUiState
import com.juren233.hyperlyricsenhanced.root.RootApplication
import com.juren233.hyperlyricsenhanced.ui.component.EnhancedVersionNotice
import com.juren233.hyperlyricsenhanced.ui.component.ProComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SetupPage(onNavigateToMain: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val prefs = remember { context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE) }
    val snackbarHostState = remember { SnackbarHostState() }
    val msgXposedNotActive = stringResource(R.string.toast_xposed_module_not_active)

    var workMode by remember {
        val initialMode = prefs.getInt(UIConstants.KEY_WORK_MODE, UIConstants.DEFAULT_WORK_MODE)
        mutableIntStateOf(initialMode)
    }

    var selectedSource by remember {
        mutableStateOf(prefs.getString(RootConstants.KEY_HOOK_LYRIC_SOURCE, RootConstants.DEFAULT_HOOK_LYRIC_SOURCE) ?: "lyricon")
    }

    val onFinish = {
        prefs.edit { putBoolean(UIConstants.KEY_SETUP_COMPLETED, true) }
        onNavigateToMain()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            TopAppBar(title = stringResource(R.string.title_setup))
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 20.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (pagerState.currentPage > 0) {
                    TextButton(
                        text = stringResource(R.string.back),
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                val isLastPage = pagerState.currentPage == 3

                TextButton(
                    text = if (isLastPage) stringResource(R.string.confirm) else stringResource(R.string.next),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        if (isLastPage) {
                            onFinish()
                        } else if (pagerState.currentPage == 0 && workMode == 0 && RootApplication.xposedService == null) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = msgXposedNotActive,
                                    duration = SnackbarDuration.Custom(2000L)
                                )
                            }
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            userScrollEnabled = false
        ) { page ->
            when (page) {
                0 -> ModeSelectionPage(
                    selectedMode = workMode,
                    onModeSelected = {
                        workMode = it
                        prefs.edit { putInt(UIConstants.KEY_WORK_MODE, it) }
                    }
                )
                1 -> if (workMode == 0) DisclaimerPage() else PermissionPage()
                2 -> if (workMode == 0) LyricSourceSelectionPage(
                    selectedSource = selectedSource,
                    snackbarHostState = snackbarHostState,
                    onSourceSelected = { source ->
                        selectedSource = source
                        prefs.edit { putString(RootConstants.KEY_HOOK_LYRIC_SOURCE, source) }
                    }
                ) else WhitelistPage()
                3 -> CompletionPage()
            }
        }
    }
}

@Composable
fun ModeSelectionPage(selectedMode: Int, onModeSelected: (Int) -> Unit) {
    val isModuleActive = RootApplication.xposedService != null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.title_select_work_mode),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onModeSelected(0) },
                pressFeedbackType = PressFeedbackType.Tilt,
                colors = CardDefaults.defaultColors(
                    color = if (selectedMode == 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.title_super_island_lyrics),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selectedMode == 0) Color.White else MiuixTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.summary_super_island_lyrics),
                        fontSize = 14.sp,
                        color = if (selectedMode == 0) Color.White.copy(alpha = 0.8f) else MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onModeSelected(1) },
                pressFeedbackType = PressFeedbackType.Tilt,
                colors = CardDefaults.defaultColors(
                    color = if (selectedMode == 1) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.title_dynamic_island_lyrics),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selectedMode == 1) Color.White else MiuixTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.summary_dynamic_island_lyrics),
                        fontSize = 14.sp,
                        color = if (selectedMode == 1) Color.White.copy(alpha = 0.8f) else MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionPage() {
    val context = LocalContext.current

    val isNotificationGranted = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (isActive) {
            isNotificationGranted.value = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            delay(1000.milliseconds)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.title_grant_permissions),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.title_permission_listener),
                        summary = if (isNotificationGranted.value) stringResource(R.string.toast_permission_granted) else stringResource(R.string.summary_permission_listener),
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.title_permission_post_notification),
                        summary = stringResource(R.string.summary_permission_post_notification),
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun WhitelistPage() {
    val context = LocalContext.current
    val whitelistSet by ConfigRepository.whitelistState.collectAsState()

    LaunchedEffect(Unit) {
        ConfigRepository.initWhitelist(context)
    }

    Scaffold(
        topBar = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.title_add_whitelist),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.summary_add_whitelist),
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
    ) { padding ->
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                commonMusicApps.forEach { (pkg: String, name: String) ->
                    item(key = pkg) {
                        val isChecked = whitelistSet.contains(pkg)
                        BasicComponent(
                            title = name,
                            summary = pkg,
                            onClick = {
                                if (isChecked) {
                                    ConfigRepository.removePackageFromWhitelist(context, pkg)
                                } else {
                                    ConfigRepository.addPackageToWhitelist(context, pkg)
                                }
                            },
                            endActions = {
                                Checkbox(
                                    state = ToggleableState(isChecked),
                                    onClick = {
                                        val checked = !isChecked
                                        if (checked) {
                                            ConfigRepository.addPackageToWhitelist(context, pkg)
                                        } else {
                                            ConfigRepository.removePackageFromWhitelist(context, pkg)
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LyricSourceSelectionPage(
    selectedSource: String,
    snackbarHostState: SnackbarHostState,
    onSourceSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val providerStateFlow = remember { MutableStateFlow(OfficialProviderUiState()) }
    val providerState = providerStateFlow.collectAsState()
    val sourceOptions = listOf(
        stringResource(R.string.lyric_source_lyricon),
        stringResource(R.string.lyric_source_superlyric),
        stringResource(R.string.lyric_source_lyricinfo),
    )
    val sourceIds = listOf("lyricon", "superlyric", "lyricinfo")
    val addSuccessFormat = stringResource(R.string.provider_add_success)
    val addFailedFormat = stringResource(R.string.provider_add_failed)
    val unknownText = stringResource(R.string.unknown)

    LaunchedEffect(selectedSource) {
        if (selectedSource == "lyricon" && providerStateFlow.value.items.isEmpty()) {
            refreshSetupProviders(context, providerStateFlow)
        }
    }

    val installProvider: (OfficialProviderItem) -> Unit = { item ->
        val pluginId = item.catalog.id
        if (
            !item.installed &&
            item.catalog.available &&
            pluginId !in providerStateFlow.value.busyPluginIds
        ) {
            providerStateFlow.update { state ->
                state.copy(busyPluginIds = state.busyPluginIds + pluginId)
            }
            coroutineScope.launch {
                runCatching {
                    OfficialProviderRepository.downloadAndInstall(context, item)
                }.onSuccess { manifest ->
                    providerStateFlow.update { state ->
                        state.copy(
                            items = state.items.map { current ->
                                if (current.catalog.id == pluginId) {
                                    current.copy(
                                        installedVersionCode = manifest.versionCode,
                                        installedVersionName = manifest.versionName,
                                        enabled = true,
                                    )
                                } else {
                                    current
                                }
                            },
                            busyPluginIds = state.busyPluginIds - pluginId,
                        )
                    }
                    snackbarHostState.showSnackbar(
                        message = addSuccessFormat.replace("%1\$s", item.catalog.displayName),
                        duration = SnackbarDuration.Custom(2500L),
                    )
                }.onFailure { error ->
                    providerStateFlow.update { state ->
                        state.copy(busyPluginIds = state.busyPluginIds - pluginId)
                    }
                    snackbarHostState.showSnackbar(
                        message = addFailedFormat.replace(
                            "%1\$s",
                            error.message ?: unknownText,
                        ),
                        duration = SnackbarDuration.Custom(3500L),
                    )
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.setup_select_lyric_source),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.title_lyric_source),
                    summary = stringResource(R.string.summary_lyric_source_apple_music_builtin),
                    items = sourceOptions,
                    selectedIndex = sourceIds.indexOf(selectedSource).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        onSourceSelected(sourceIds[index])
                    },
                )
            }
        }
        item {
            when (selectedSource) {
                "lyricon" -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = stringResource(R.string.setup_apple_music_ready),
                            summary = stringResource(R.string.setup_apple_music_ready_summary),
                        )
                    }
                }

                "superlyric" -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = stringResource(R.string.setup_download_superlyric),
                            summary = stringResource(R.string.setup_download_superlyric_summary),
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        "https://github.com/HChenX/SuperLyric".toUri(),
                                    ),
                                )
                            },
                        )
                    }
                }

                "lyricinfo" -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = stringResource(R.string.setup_no_dependency),
                            summary = stringResource(R.string.setup_no_dependency_summary),
                        )
                    }
                }
            }
        }
        if (selectedSource == "lyricon") {
            item {
                Text(
                    text = stringResource(R.string.setup_available_provider_plugins),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 4.dp),
                )
            }
            item(key = "setup_provider_apple_music") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ProComponent(
                        title = "Apple Music",
                        summary = stringResource(R.string.setup_apple_music_plugin_summary),
                        endActions = {
                            Text(
                                text = stringResource(R.string.setup_provider_added),
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                fontSize = 14.sp,
                            )
                        },
                        enabled = false,
                    )
                }
            }

            when {
                providerState.value.isLoading -> item(key = "setup_provider_loading") {
                    SetupProviderStateCard(
                        title = stringResource(R.string.provider_catalog_loading),
                    )
                }

                providerState.value.error != null -> item(key = "setup_provider_error") {
                    SetupProviderStateCard(
                        title = stringResource(R.string.provider_catalog_load_failed),
                        summary = providerState.value.error,
                        actionText = stringResource(R.string.setup_provider_retry),
                        onClick = {
                            coroutineScope.launch {
                                refreshSetupProviders(context, providerStateFlow)
                            }
                        },
                    )
                }

                else -> providerState.value.items
                    .filter { provider -> provider.catalog.available }
                    .forEach { provider ->
                    item(key = "setup_provider_${provider.catalog.id}") {
                        val busy = provider.catalog.id in providerState.value.busyPluginIds
                        val added = provider.installed
                        val available = provider.catalog.available
                        val versionText = provider.catalog.versionName
                            ?: stringResource(R.string.unknown)
                        val statusText = if (available) {
                            stringResource(R.string.provider_status_available, versionText)
                        } else {
                            stringResource(R.string.provider_status_unavailable)
                        }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            ProComponent(
                                title = provider.catalog.displayName,
                                summary = "$statusText\n${provider.catalog.targetPackages.joinToString()}",
                                onClick = if (!added && !busy && available) {
                                    { installProvider(provider) }
                                } else {
                                    null
                                },
                                endActions = {
                                    Text(
                                        text = when {
                                            added -> stringResource(R.string.setup_provider_added)
                                            busy -> stringResource(R.string.provider_status_downloading)
                                            available -> stringResource(R.string.provider_action_download)
                                            else -> stringResource(R.string.provider_status_unavailable)
                                        },
                                        color = if (!added && (available || busy)) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        },
                                        fontSize = 14.sp,
                                    )
                                },
                                enabled = !added && !busy && available,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompletionPage() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.title_setup_complete),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.setup_completion_restart_hint),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SetupProviderStateCard(
    title: String,
    summary: String? = null,
    actionText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ProComponent(
            title = title,
            summary = summary,
            onClick = onClick,
            endActions = if (actionText != null) {
                {
                    Text(
                        text = actionText,
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 14.sp,
                    )
                }
            } else {
                null
            },
            showIndication = onClick != null,
        )
    }
}

private suspend fun refreshSetupProviders(
    context: Context,
    stateFlow: MutableStateFlow<OfficialProviderUiState>,
) {
    stateFlow.update { state -> state.copy(isLoading = true, error = null) }
    runCatching { OfficialProviderRepository.loadItems(context) }
        .onSuccess { items ->
            stateFlow.value = OfficialProviderUiState(items = items)
        }
        .onFailure { error ->
            stateFlow.value = OfficialProviderUiState(error = error.message)
        }
}

@Composable
fun DisclaimerPage() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.title_disclaimer),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        }
        item {
            EnhancedVersionNotice(modifier = Modifier.fillMaxWidth())
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = stringResource(R.string.title_disclaimer_notice),
                    summary = stringResource(R.string.summary_disclaimer)
                )
            }
        }
    }
}
