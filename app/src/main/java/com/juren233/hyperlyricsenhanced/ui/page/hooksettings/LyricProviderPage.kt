package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderInstaller
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderItem
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRepository
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderUiState
import com.juren233.hyperlyricsenhanced.ui.component.ProComponent
import com.juren233.hyperlyricsenhanced.ui.component.SuperSwitchPreference
import com.juren233.hyperlyricsenhanced.ui.component.TagComponent
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBar
import com.juren233.hyperlyricsenhanced.ui.utils.pageScrollModifiers
import com.juren233.hyperlyricsenhanced.ui.utils.rememberBlurBackdrop
import com.juren233.hyperlyricsenhanced.utils.LyricModule
import com.juren233.hyperlyricsenhanced.utils.LyricProviderManager
import com.juren233.hyperlyricsenhanced.utils.ModuleCategory
import com.juren233.hyperlyricsenhanced.utils.ModuleTag
import com.juren233.hyperlyricsenhanced.utils.ProviderUiState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LyricProviderPage() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    val providerUiStateFlow = remember { MutableStateFlow(ProviderUiState()) }
    val providerUiState = providerUiStateFlow.collectAsState()
    val officialUiStateFlow = remember { MutableStateFlow(OfficialProviderUiState()) }
    val officialUiState = officialUiStateFlow.collectAsState()
    val pullToRefreshState = rememberPullToRefreshState()
    var isManualRefreshing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val changeAppliedFormat = stringResource(R.string.provider_change_applied)
    val changeAppliedSystemUiMessage = stringResource(
        R.string.provider_change_applied_system_ui,
    )
    val updateSuccessFormat = stringResource(R.string.provider_update_success)
    val updateSuccessSystemUiMessage = stringResource(
        R.string.provider_update_success_system_ui,
    )
    val repairSuccessFormat = stringResource(R.string.provider_repair_success)
    val repairSuccessSystemUiMessage = stringResource(
        R.string.provider_repair_success_system_ui,
    )
    val removeSuccessMessage = stringResource(R.string.provider_remove_success)
    val unknownText = stringResource(R.string.unknown)
    val updateFailedFormat = stringResource(R.string.provider_update_failed)
    val repairFailedFormat = stringResource(R.string.provider_repair_failed)
    val removeFailedFormat = stringResource(R.string.provider_remove_failed)
    val legacyProviderReleaseHome = stringResource(R.string.legacy_provider_release_home)
    val providerErrorTexts = rememberProviderErrorTexts()

    LaunchedEffect(Unit) {
        loadLocalProviderState(context, providerUiStateFlow, officialUiStateFlow)
    }

    val othersCategoryName = stringResource(id = R.string.category_others)
    val groupedModules = remember(providerUiState.value.modules) {
        LyricProviderManager.categorizeModules(providerUiState.value.modules, othersCategoryName)
    }
    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

    val setOfficialProviderEnabled: (OfficialProviderItem, Boolean) -> Unit = { item, enabled ->
        OfficialProviderRepository.setEnabled(item.catalog.id, enabled)
        officialUiStateFlow.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.catalog.id == item.catalog.id) it.copy(enabled = enabled) else it
                },
            )
        }
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                message = if (item.usesSystemMediaRuntime()) {
                    changeAppliedSystemUiMessage
                } else {
                    changeAppliedFormat.replace("%1\$s", item.catalog.displayName)
                },
                duration = SnackbarDuration.Custom(2500L),
            )
        }
    }

    val updateOfficialProvider: (OfficialProviderItem) -> Unit = { item ->
        if (item.updateAvailable && item.catalog.id !in officialUiStateFlow.value.busyPluginIds) {
            officialUiStateFlow.update {
                it.copy(busyPluginIds = it.busyPluginIds + item.catalog.id)
            }
            coroutineScope.launch {
                runCatching {
                    OfficialProviderRepository.downloadAndInstall(context, item)
                }.onSuccess { manifest ->
                    officialUiStateFlow.update { state ->
                        state.copy(
                            items = state.items.map { current ->
                                if (current.catalog.id == item.catalog.id) {
                                    current.copy(
                                        installedVersionCode = manifest.versionCode,
                                        installedVersionName = manifest.versionName,
                                        enabled = true,
                                    )
                                } else {
                                    current
                                }
                            },
                            busyPluginIds = state.busyPluginIds - item.catalog.id,
                        )
                    }
                    snackbarHostState.showSnackbar(
                        message = if (item.usesSystemMediaRuntime()) {
                            updateSuccessSystemUiMessage
                        } else {
                            updateSuccessFormat.replace("%1\$s", item.catalog.displayName)
                        },
                        duration = SnackbarDuration.Custom(2500L),
                    )
                }.onFailure { error ->
                    officialUiStateFlow.update {
                        it.copy(busyPluginIds = it.busyPluginIds - item.catalog.id)
                    }
                    snackbarHostState.showSnackbar(
                        message = updateFailedFormat.replace(
                            "%1\$s",
                            localizeProviderError(error.message, providerErrorTexts, unknownText),
                        ),
                        duration = SnackbarDuration.Custom(3500L),
                    )
                }
            }
        }
    }

    val repairOfficialProvider: (OfficialProviderItem) -> Unit = { item ->
        if (item.catalog.id !in officialUiStateFlow.value.busyPluginIds) {
            officialUiStateFlow.update {
                it.copy(busyPluginIds = it.busyPluginIds + item.catalog.id)
            }
            coroutineScope.launch {
                runCatching {
                    OfficialProviderRepository.repair(context, item)
                }.onSuccess { manifest ->
                    officialUiStateFlow.update { state ->
                        state.copy(
                            items = state.items.map { current ->
                                if (current.catalog.id == item.catalog.id) {
                                    current.copy(
                                        installedVersionCode = manifest.versionCode,
                                        installedVersionName = manifest.versionName,
                                        enabled = true,
                                        needsRepair = false,
                                    )
                                } else {
                                    current
                                }
                            },
                            busyPluginIds = state.busyPluginIds - item.catalog.id,
                        )
                    }
                    snackbarHostState.showSnackbar(
                        message = if (item.usesSystemMediaRuntime()) {
                            repairSuccessSystemUiMessage
                        } else {
                            repairSuccessFormat.replace("%1\$s", item.catalog.displayName)
                        },
                        duration = SnackbarDuration.Custom(2500L),
                    )
                }.onFailure { error ->
                    officialUiStateFlow.update {
                        it.copy(busyPluginIds = it.busyPluginIds - item.catalog.id)
                    }
                    snackbarHostState.showSnackbar(
                        message = repairFailedFormat.replace(
                            "%1\$s",
                            localizeProviderError(error.message, providerErrorTexts, unknownText),
                        ),
                        duration = SnackbarDuration.Custom(3500L),
                    )
                }
            }
        }
    }

    val removeOfficialProvider: (OfficialProviderItem) -> Unit = { item ->
        runCatching {
            OfficialProviderInstaller.delete(context, item.catalog.id)
            officialUiStateFlow.update { state ->
                state.copy(items = state.items.filterNot { it.catalog.id == item.catalog.id })
            }
        }.onSuccess {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = removeSuccessMessage,
                    duration = SnackbarDuration.Custom(2500L),
                )
            }
        }.onFailure { error ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = removeFailedFormat.replace(
                        "%1\$s",
                        localizeProviderError(error.message, providerErrorTexts, unknownText),
                    ),
                    duration = SnackbarDuration.Custom(3500L),
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(id = R.string.title_lyric_provider),
                    scrollBehavior = topAppBarScrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(id = R.string.back),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { navigator.navigate(Route.LyricProviderDownloads) }) {
                            Icon(
                                imageVector = MiuixIcons.Download,
                                contentDescription = stringResource(R.string.provider_action_download),
                                tint = MiuixTheme.colorScheme.onBackground,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            PullToRefresh(
                isRefreshing = isManualRefreshing,
                onRefresh = {
                    coroutineScope.launch {
                        isManualRefreshing = true
                        try {
                            loadLocalProviderState(context, providerUiStateFlow, officialUiStateFlow)
                        } finally {
                            isManualRefreshing = false
                        }
                    }
                },
                pullToRefreshState = pullToRefreshState,
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
                refreshTexts = listOf(
                    stringResource(id = R.string.refresh_pull_down),
                    stringResource(id = R.string.refresh_release),
                    stringResource(id = R.string.refreshing),
                    stringResource(id = R.string.refresh_success),
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                val lazyListState = rememberLazyListState()
                val top = innerPadding.calculateTopPadding()
                val bottom = innerPadding.calculateBottomPadding()
                val contentPadding = remember(top, bottom) {
                    PaddingValues(top = top, start = 0.dp, end = 0.dp, bottom = bottom)
                }
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.pageScrollModifiers(
                        enableScrollEndHaptic = true,
                        showTopAppBar = false,
                        topAppBarScrollBehavior = topAppBarScrollBehavior,
                    ),
                    contentPadding = contentPadding,
                ) {
                    providerSections(
                        officialUiState = officialUiState.value,
                        uiState = providerUiState.value,
                        groupedModules = groupedModules,
                        expandedStates = expandedStates,
                        onOfficialEnabledChange = setOfficialProviderEnabled,
                        onUpdateOfficial = updateOfficialProvider,
                        onRepairOfficial = repairOfficialProvider,
                        onRemoveOfficial = removeOfficialProvider,
                        legacyProviderReleaseHome = legacyProviderReleaseHome,
                    )
                }
            }
        }
    }
}

@Composable
fun OfficialProviderDownloadPage() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    val stateFlow = remember { MutableStateFlow(OfficialProviderUiState()) }
    val state = stateFlow.collectAsState()
    val pullToRefreshState = rememberPullToRefreshState()
    var isManualRefreshing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val addSuccessFormat = stringResource(R.string.provider_add_success)
    val addSuccessSystemUiMessage = stringResource(R.string.provider_add_success_system_ui)
    val unknownText = stringResource(R.string.unknown)
    val addFailedFormat = stringResource(R.string.provider_add_failed)
    val providerErrorTexts = rememberProviderErrorTexts()

    LaunchedEffect(Unit) {
        refreshOfficialProviders(context, stateFlow)
    }

    val installProvider: (OfficialProviderItem) -> Unit = { item ->
        if (item.catalog.id !in stateFlow.value.busyPluginIds) {
            stateFlow.update { it.copy(busyPluginIds = it.busyPluginIds + item.catalog.id) }
            coroutineScope.launch {
                runCatching {
                    OfficialProviderRepository.downloadAndInstall(context, item)
                    refreshOfficialProviders(context, stateFlow)
                }.onSuccess {
                    snackbarHostState.showSnackbar(
                        message = if (item.usesSystemMediaRuntime()) {
                            addSuccessSystemUiMessage
                        } else {
                            addSuccessFormat.replace("%1\$s", item.catalog.displayName)
                        },
                        duration = SnackbarDuration.Custom(2500L),
                    )
                }.onFailure { error ->
                    stateFlow.update {
                        it.copy(
                            busyPluginIds = it.busyPluginIds - item.catalog.id,
                            error = null,
                        )
                    }
                    snackbarHostState.showSnackbar(
                        message = addFailedFormat.replace(
                            "%1\$s",
                            localizeProviderError(error.message, providerErrorTexts, unknownText),
                        ),
                        duration = SnackbarDuration.Custom(3500L),
                    )
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.title_provider_downloads),
                    scrollBehavior = topAppBarScrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(id = R.string.back),
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            PullToRefresh(
                isRefreshing = isManualRefreshing,
                onRefresh = {
                    coroutineScope.launch {
                        isManualRefreshing = true
                        try {
                            refreshOfficialProviders(context, stateFlow)
                        } finally {
                            isManualRefreshing = false
                        }
                    }
                },
                pullToRefreshState = pullToRefreshState,
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
                refreshTexts = listOf(
                    stringResource(id = R.string.refresh_pull_down),
                    stringResource(id = R.string.refresh_release),
                    stringResource(id = R.string.refreshing),
                    stringResource(id = R.string.refresh_success),
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                val lazyListState = rememberLazyListState()
                val availableItems = state.value.items.filterNot { it.installed }
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.pageScrollModifiers(
                        enableScrollEndHaptic = true,
                        showTopAppBar = false,
                        topAppBarScrollBehavior = topAppBarScrollBehavior,
                    ),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding(),
                    ),
                ) {
                    if (state.value.isLoading || state.value.error != null || availableItems.isEmpty()) {
                        item(key = "download_state") {
                            Card(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp)
                                    .fillMaxWidth(),
                            ) {
                                ProComponent(
                                    title = when {
                                        state.value.isLoading -> stringResource(R.string.provider_catalog_loading)
                                        state.value.error != null -> stringResource(R.string.provider_catalog_load_failed)
                                        else -> stringResource(R.string.provider_no_available_plugins)
                                    },
                                    summary = state.value.error?.let {
                                        localizeProviderError(it, providerErrorTexts, unknownText)
                                    },
                                    showIndication = false,
                                )
                            }
                        }
                    } else {
                        items(
                            count = availableItems.size,
                            key = { "download_${availableItems[it].catalog.id}" },
                        ) { index ->
                            val item = availableItems[index]
                            val busy = item.catalog.id in state.value.busyPluginIds
                            val versionText = item.catalog.versionName ?: stringResource(R.string.unknown)
                            Card(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp)
                                    .fillMaxWidth(),
                            ) {
                                ProComponent(
                                    title = item.catalog.displayName,
                                    summary = buildString {
                                        append(
                                            if (item.catalog.available) {
                                                stringResource(R.string.provider_status_available, versionText)
                                            } else {
                                                stringResource(R.string.provider_status_unavailable)
                                            },
                                        )
                                        append("\n")
                                        append(item.catalog.targetPackages.joinToString())
                                    },
                                    onClick = if (!busy && item.catalog.available) {
                                        { installProvider(item) }
                                    } else {
                                        null
                                    },
                                    endActions = {
                                        Text(
                                            text = if (busy) {
                                                stringResource(R.string.provider_status_downloading)
                                            } else if (!item.catalog.available) {
                                                stringResource(R.string.provider_status_unavailable)
                                            } else {
                                                stringResource(R.string.provider_action_download)
                                            },
                                            color = if (item.catalog.available || busy) {
                                                MiuixTheme.colorScheme.primary
                                            } else {
                                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            },
                                            fontSize = 14.sp,
                                        )
                                    },
                                    enabled = !busy && item.catalog.available,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadLocalProviderState(
    context: android.content.Context,
    providerUiStateFlow: MutableStateFlow<ProviderUiState>,
    officialUiStateFlow: MutableStateFlow<OfficialProviderUiState>,
) {
    officialUiStateFlow.value = OfficialProviderUiState(
        items = OfficialProviderRepository.loadInstalledItems(context),
    )
    coroutineScope {
        launch {
            LyricProviderManager.loadProviders(context, providerUiStateFlow)
        }
        launch {
            refreshInstalledProviderUpdates(context, officialUiStateFlow)
        }
    }
}

private suspend fun refreshInstalledProviderUpdates(
    context: Context,
    stateFlow: MutableStateFlow<OfficialProviderUiState>,
) {
    val remoteItems = runCatching { OfficialProviderRepository.loadItems(context) }.getOrNull() ?: return
    val remoteById = remoteItems.associateBy { it.catalog.id }
    stateFlow.update { state ->
        state.copy(
            items = state.items.map { localItem ->
                val remoteItem = remoteById[localItem.catalog.id]
                remoteItem?.copy(
                    installedVersionCode = localItem.installedVersionCode,
                    installedVersionName = localItem.installedVersionName
                        ?: remoteItem.installedVersionName,
                    enabled = localItem.enabled,
                    needsRepair = localItem.needsRepair || remoteItem.needsRepair,
                ) ?: localItem
            },
        )
    }
}

internal class ProviderDelayEditorState(initialDelay: Int) {
    var currentDelay by mutableIntStateOf(initialDelay)
        private set

    var sliderPosition by mutableFloatStateOf(initialDelay.toFloat())
        private set

    fun updateSlider(sliderValue: Float) {
        sliderPosition = sliderValue
        currentDelay = quantizeProviderDelay(sliderValue)
    }

    fun finishSlider(): Int {
        val finalValue = quantizeProviderDelay(sliderPosition)
        sliderPosition = finalValue.toFloat()
        currentDelay = finalValue
        return finalValue
    }
}

internal fun quantizeProviderDelay(sliderValue: Float): Int =
    (sliderValue / 50f).roundToInt() * 50

internal fun formatProviderDelay(delay: Int): String =
    if (delay > 0) "+$delay ms" else "$delay ms"

@Composable
private fun rememberProviderDelayEditorState(delayKey: String): ProviderDelayEditorState =
    remember(delayKey) {
        ProviderDelayEditorState(
            PrefsBridge.getInt(delayKey, RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY),
        )
    }

private fun LazyListScope.providerSections(
    officialUiState: OfficialProviderUiState,
    uiState: ProviderUiState,
    groupedModules: List<ModuleCategory>,
    expandedStates: MutableMap<String, Boolean>,
    onOfficialEnabledChange: (OfficialProviderItem, Boolean) -> Unit,
    onUpdateOfficial: (OfficialProviderItem) -> Unit,
    onRepairOfficial: (OfficialProviderItem) -> Unit,
    onRemoveOfficial: (OfficialProviderItem) -> Unit,
    legacyProviderReleaseHome: String,
) {
    item(key = "official_header") {
        SmallTitle(text = stringResource(R.string.title_official_provider_plugins))
    }

    item(key = "builtin_apple_music") {
        val packageName = OfficialProviderCatalog.CORE_PACKAGE_NAME
        val isExpanded = expandedStates[packageName] ?: false
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth(),
            onClick = { expandedStates[packageName] = !isExpanded },
        ) {
            Column {
                ProComponent(
                    title = "Apple Music",
                    summary = stringResource(R.string.summary_builtin_apple_provider),
                    onClick = { expandedStates[packageName] = !isExpanded },
                    endActions = {
                        Text(
                            text = stringResource(R.string.provider_status_builtin),
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                        )
                    },
                    showIndication = false,
                )
                AnimatedVisibility(visible = isExpanded) {
                    ProviderDelayEditor(
                        delayKey = RootConstants.KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX + packageName,
                        onRemove = null,
                    )
                }
            }
        }
    }

    if (officialUiState.items.isEmpty()) {
        item(key = "official_empty") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth(),
            ) {
                ProComponent(
                    title = stringResource(R.string.provider_no_installed_plugins),
                    summary = stringResource(R.string.provider_no_installed_plugins_summary),
                    showIndication = false,
                )
            }
        }
    } else {
        items(
            count = officialUiState.items.size,
            key = { "official_${officialUiState.items[it].catalog.id}" },
        ) { index ->
            val item = officialUiState.items[index]
            val packageName = OfficialProviderCatalog.OFFICIAL_PROVIDER_PACKAGE_PREFIX + item.catalog.id
            val isExpanded = expandedStates[packageName] ?: false
            val busy = item.catalog.id in officialUiState.busyPluginIds
            val installedVersionName = item.installedVersionName ?: stringResource(R.string.unknown)
            val providerDescription = OfficialProviderCatalog
                .definitionForId(item.catalog.id)
                ?.description
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth(),
                onClick = { expandedStates[packageName] = !isExpanded },
            ) {
                Column {
                    SuperSwitchPreference(
                        checked = item.enabled,
                        onCheckedChange = { onOfficialEnabledChange(item, it) },
                        title = item.catalog.displayName,
                        summary = buildString {
                            append(
                                when {
                                    busy -> stringResource(R.string.provider_status_downloading)
                                    item.needsRepair -> stringResource(
                                        R.string.provider_status_needs_repair,
                                        installedVersionName,
                                    )
                                    item.enabled -> stringResource(
                                        R.string.provider_status_installed_enabled,
                                        installedVersionName,
                                    )
                                    else -> stringResource(
                                        R.string.provider_status_installed_disabled,
                                        installedVersionName,
                                    )
                                },
                            )
                            if (!busy && item.updateAvailable) {
                                append(" · ")
                                append(
                                    stringResource(
                                        R.string.provider_status_update_available,
                                        item.catalog.versionName ?: stringResource(R.string.unknown),
                                    ),
                                )
                            }
                            providerDescription?.takeIf(String::isNotBlank)?.let { description ->
                                append("\n")
                                append(description)
                            }
                            append("\n")
                            append(item.catalog.targetPackages.joinToString())
                        },
                        onClick = { expandedStates[packageName] = !isExpanded },
                        enabled = !busy,
                    )
                    AnimatedVisibility(visible = isExpanded) {
                        ProviderDelayEditor(
                            delayKey = RootConstants.KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX + packageName,
                            onUpdate = if (item.updateAvailable && !busy) {
                                { onUpdateOfficial(item) }
                            } else {
                                null
                            },
                            onRepair = if (item.needsRepair && !busy) {
                                { onRepairOfficial(item) }
                            } else {
                                null
                            },
                            onRemove = if (!busy) {
                                { onRemoveOfficial(item) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    item(key = "legacy_header") {
        SmallTitle(text = stringResource(R.string.title_legacy_provider_modules))
    }
    item(key = "legacy_repository") {
        val context = LocalContext.current
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth(),
        ) {
            ProComponent(
                title = stringResource(R.string.title_legacy_provider_repository),
                summary = stringResource(R.string.summary_legacy_provider_repository),
                startAction = {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = stringResource(R.string.github),
                        tint = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.size(40.dp),
                    )
                },
                onClick = {
                    runCatching {
                        context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                legacyProviderReleaseHome.toUri(),
                            ),
                        )
                    }
                },
            )
        }
    }

    if (!uiState.isLoading && uiState.modules.isEmpty()) {
        item(key = "no_legacy_provider") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth(),
            ) {
                ProComponent(
                    title = stringResource(id = R.string.title_no_legacy_provider),
                    summary = stringResource(id = R.string.summary_no_legacy_provider),
                    showIndication = false,
                )
            }
        }
    } else {
        groupedModules.forEach { category ->
            if (category.name.isNotBlank()) {
                item(key = "header_${category.name}") {
                    SmallTitle(text = category.name)
                }
            }
            items(
                count = category.items.size,
                key = { "provider_${category.items[it].packageInfo.packageName}" },
            ) { index ->
                val module = category.items[index]
                LegacyProviderCard(
                    module = module,
                    expandedStates = expandedStates,
                )
            }
        }
    }
}

@Composable
private fun LegacyProviderCard(
    module: LyricModule,
    expandedStates: MutableMap<String, Boolean>,
) {
    val packageName = module.packageInfo.packageName
    val isExpanded = expandedStates[packageName] ?: false
    val delayKey = RootConstants.KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX + packageName
    val delayState = rememberProviderDelayEditorState(delayKey)
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth(),
        onClick = { expandedStates[packageName] = !isExpanded },
    ) {
        Column {
            ProComponent(
                title = module.label,
                summary = stringResource(
                    id = R.string.format_version_author,
                    module.packageInfo.versionName ?: stringResource(id = R.string.unknown),
                    module.author ?: stringResource(id = R.string.unknown_author),
                ),
                onClick = { expandedStates[packageName] = !isExpanded },
                startAction = {
                    val pm = LocalContext.current.packageManager
                    val appInfo = module.packageInfo.applicationInfo
                    val icon = remember(packageName) { appInfo?.loadIcon(pm) }
                    if (icon != null) {
                        Box(modifier = Modifier.size(40.dp)) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { context ->
                                    android.widget.ImageView(context).apply { setImageDrawable(icon) }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                },
                endActions = {
                    Text(
                        text = formatProviderDelay(delayState.currentDelay),
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                    )
                },
                showIndication = false,
            )
            AnimatedVisibility(visible = isExpanded) {
                ProviderDelayEditor(
                    delayKey = delayKey,
                    state = delayState,
                    description = module.description,
                    tags = module.tags,
                    onRemove = null,
                )
            }
        }
    }
}

@Composable
private fun ProviderDelayEditor(
    delayKey: String,
    state: ProviderDelayEditorState? = null,
    description: String? = null,
    tags: List<ModuleTag> = emptyList(),
    onUpdate: (() -> Unit)? = null,
    onRepair: (() -> Unit)? = null,
    onRemove: (() -> Unit)?,
) {
    val editorState = if (state != null) {
        state
    } else {
        rememberProviderDelayEditorState(delayKey)
    }

    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        if (description != null) {
            ProComponent(
                summary = description,
                insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 0.dp),
            )
        }
        if (tags.isNotEmpty()) {
            ModuleTagsFlow(tags)
        }
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.title_lyric_delay),
                        color = MiuixTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.summary_lyric_delay),
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = formatProviderDelay(editorState.currentDelay),
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Slider(
                value = editorState.sliderPosition,
                onValueChange = editorState::updateSlider,
                onValueChangeFinished = {
                    val finalValue = editorState.finishSlider()
                    PrefsBridge.putInt(delayKey, finalValue)
                },
                valueRange = RootConstants.MIN_HOOK_LYRICON_PROVIDER_DELAY.toFloat()..RootConstants.MAX_HOOK_LYRICON_PROVIDER_DELAY.toFloat(),
                steps = 199,
                showKeyPoints = true,
                keyPoints = listOf(-5000f, -4000f, -3000f, -2000f, -1000f, 0f, 1000f, 2000f, 3000f, 4000f, 5000f),
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
            if (onUpdate != null || onRepair != null || onRemove != null) {
                Spacer(modifier = Modifier.height(16.dp))
            }
            if (onUpdate != null) {
                TextButton(
                    text = stringResource(R.string.provider_action_update),
                    onClick = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (onRepair != null) {
                if (onUpdate != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                TextButton(
                    text = stringResource(R.string.provider_action_repair),
                    onClick = onRepair,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (onRemove != null) {
                if (onUpdate != null || onRepair != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                TextButton(
                    text = stringResource(R.string.provider_remove),
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleTagsFlow(tags: List<ModuleTag>) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        tags.forEach { tag ->
            val title = if (tag.titleRes != -1) stringResource(tag.titleRes) else tag.title.orEmpty()
            TagComponent(
                text = title,
                iconRes = tag.iconRes,
                imageVector = tag.imageVector,
                isRainbow = tag.isRainbow,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
    }
}

private data class ProviderErrorTexts(
    val network: String,
    val http: String,
    val catalogSignature: String,
    val catalogData: String,
    val unavailable: String,
    val integrity: String,
    val incompatible: String,
    val storage: String,
    val generic: String,
)

@Composable
private fun rememberProviderErrorTexts() = ProviderErrorTexts(
    network = stringResource(R.string.provider_error_network),
    http = stringResource(R.string.provider_error_http),
    catalogSignature = stringResource(R.string.provider_error_catalog_signature),
    catalogData = stringResource(R.string.provider_error_catalog_data),
    unavailable = stringResource(R.string.provider_error_unavailable),
    integrity = stringResource(R.string.provider_error_integrity),
    incompatible = stringResource(R.string.provider_error_incompatible),
    storage = stringResource(R.string.provider_error_storage),
    generic = stringResource(R.string.provider_error_generic),
)

private fun localizeProviderError(
    rawMessage: String?,
    texts: ProviderErrorTexts,
    unknownText: String,
): String {
    val message = rawMessage?.trim().orEmpty()
    if (message.isEmpty()) {
        return texts.generic.replace("%1\$s", unknownText)
    }
    val lowerMessage = message.lowercase()
    return when {
        "http " in lowerMessage -> {
            val status = message.substringAfter("HTTP ", unknownText).substringBefore(' ')
            texts.http.replace("%1\$s", status)
        }
        listOf(
            "unable to resolve host",
            "failed to connect",
            "connection reset",
            "network is unreachable",
            "timeout",
            "timed out",
            "socket",
        ).any(lowerMessage::contains) -> texts.network
        "目录签名" in message -> texts.catalogSignature
        "Provider 目录" in message ||
            "插件目录" in message ||
            "下载地址" in message -> texts.catalogData
        "尚未发布" in message -> texts.unavailable
        "写入" in message ||
            "原子替换" in message ||
            "临时文件清理" in message ||
            "permission" in lowerMessage -> texts.storage
        "不兼容" in message ||
            "需要更新版本" in message ||
            "不支持的 Provider Pack 格式" in message ||
            "插件 API" in message -> texts.incompatible
        "摘要" in message ||
            "签名无效" in message ||
            "校验" in message ||
            "DEX" in message ||
            "文件集合" in message ||
            "包含不允许" in message ||
            "重复文件" in message ||
            "超过大小限制" in message ||
            "对应了不同内容" in message ||
            "与目录不一致" in message -> texts.integrity
        else -> texts.generic.replace("%1\$s", message)
    }
}

private fun OfficialProviderItem.usesSystemMediaRuntime(): Boolean =
    OfficialProviderCatalog.definitionForId(catalog.id)?.systemMediaRuntime == true

private suspend fun refreshOfficialProviders(
    context: Context,
    stateFlow: MutableStateFlow<OfficialProviderUiState>,
) {
    stateFlow.update { it.copy(isLoading = true, error = null) }
    runCatching {
        OfficialProviderRepository.loadItems(context)
    }.onSuccess { items ->
        stateFlow.update {
            it.copy(
                items = items,
                isLoading = false,
                busyPluginIds = emptySet(),
                error = null,
            )
        }
    }.onFailure { error ->
        stateFlow.update {
            it.copy(
                isLoading = false,
                busyPluginIds = emptySet(),
                error = error.message,
            )
        }
    }
}
