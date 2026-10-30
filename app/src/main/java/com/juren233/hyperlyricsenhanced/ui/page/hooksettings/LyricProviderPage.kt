package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderAcquisitionLog
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHealthStore
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderInstaller
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderItem
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRepository
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderUiState
import com.juren233.hyperlyricsenhanced.root.utils.ShellUtils
import com.juren233.hyperlyricsenhanced.ui.component.ProComponent
import com.juren233.hyperlyricsenhanced.ui.component.SuperSwitchPreference
import com.juren233.hyperlyricsenhanced.ui.component.TagComponent
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.page.main.OneTapRefreshHost
import com.juren233.hyperlyricsenhanced.ui.page.main.rememberOneTapRefreshController
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
import top.yukonga.miuix.kmp.basic.FloatingActionButton
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
import top.yukonga.miuix.kmp.icon.extended.Refresh
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
    // 插件更新、添加、移除、修复完成后，右下角提供一键刷新入口（与主页顶栏同一组件）。
    val oneTapRefresh = rememberOneTapRefreshController(snackbarHostState)
    var showRefreshFab by remember { mutableStateOf(false) }
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
    val applyNowSuccessMessage = stringResource(R.string.provider_apply_now_success)
    val applyNowFailedMessage = stringResource(R.string.provider_apply_now_failed)
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
                OfficialProviderAcquisitionLog.info("开始更新插件: id=${item.catalog.id}")
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
                                        displayVersionName = manifest.versionName,
                                        enabled = true,
                                    )
                                } else {
                                    current
                                }
                            },
                            busyPluginIds = state.busyPluginIds - item.catalog.id,
                        )
                    }
                    showRefreshFab = true
                    snackbarHostState.showSnackbar(
                        message = if (item.usesSystemMediaRuntime()) {
                            updateSuccessSystemUiMessage
                        } else {
                            updateSuccessFormat.replace("%1\$s", item.catalog.displayName)
                        },
                        duration = SnackbarDuration.Custom(2500L),
                    )
                }.onFailure { error ->
                    OfficialProviderAcquisitionLog.error(
                        "插件更新失败: id=${item.catalog.id} " +
                            "error=${OfficialProviderAcquisitionLog.describe(error)}",
                    )
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
                OfficialProviderAcquisitionLog.info("开始修复插件: id=${item.catalog.id}")
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
                                        displayVersionName = manifest.versionName,
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
                    showRefreshFab = true
                    snackbarHostState.showSnackbar(
                        message = if (item.usesSystemMediaRuntime()) {
                            repairSuccessSystemUiMessage
                        } else {
                            repairSuccessFormat.replace("%1\$s", item.catalog.displayName)
                        },
                        duration = SnackbarDuration.Custom(2500L),
                    )
                }.onFailure { error ->
                    OfficialProviderAcquisitionLog.error(
                        "插件修复失败: id=${item.catalog.id} " +
                            "error=${OfficialProviderAcquisitionLog.describe(error)}",
                    )
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

    // 目标进程只在启动时加载插件；更新后目标应用仍在运行旧版本，这里提供
    // 强行停止目标应用让下次打开直接生效的入口（需要 root）。
    val applyNowOfficialProvider: (OfficialProviderItem) -> Unit = { item ->
        val targets = item.catalog.targetPackages.toList()
        coroutineScope.launch {
            val stopped = runCatching { ShellUtils.killAppProcesses(targets) }
                .getOrDefault(false)
            if (stopped) {
                // 强停发生在目标进程「健康宽限期」内时会被算作一次崩溃计数，
                // 趁手上有 root 把标记清掉，避免两次强停被误判成连续崩溃回退。
                OfficialProviderHealthStore.clearLoadMarkers(item.catalog.id, targets)
            }
            OfficialProviderAcquisitionLog.info(
                "插件立即生效${if (stopped) "完成" else "失败"}: id=${item.catalog.id} " +
                    "targets=${targets.joinToString()}",
            )
            snackbarHostState.showSnackbar(
                message = if (stopped) applyNowSuccessMessage else applyNowFailedMessage,
                duration = SnackbarDuration.Custom(2500L),
            )
        }
    }

    val removeOfficialProvider: (OfficialProviderItem) -> Unit = { item ->
        runCatching {
            OfficialProviderInstaller.delete(context, item.catalog.id)
            officialUiStateFlow.update { state ->
                state.copy(items = state.items.filterNot { it.catalog.id == item.catalog.id })
            }
        }.onSuccess {
            showRefreshFab = true
            // 有 root 时顺带清掉目标 App filesDir 里该插件的全部状态
            // （崩溃标记/坏版本/已加载版本），避免删除后重装继承旧的坏版本记录。
            coroutineScope.launch {
                OfficialProviderHealthStore.clearAllPluginState(
                    pluginId = item.catalog.id,
                    targetPackages = item.catalog.targetPackages,
                )
                snackbarHostState.showSnackbar(
                    message = removeSuccessMessage,
                    duration = SnackbarDuration.Custom(2500L),
                )
            }
        }.onFailure { error ->
            OfficialProviderAcquisitionLog.error(
                "插件移除失败: id=${item.catalog.id} " +
                    "error=${OfficialProviderAcquisitionLog.describe(error)}",
            )
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
        floatingActionButton = {
            AnimatedVisibility(
                visible = showRefreshFab,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                FloatingActionButton(onClick = { oneTapRefresh.open() }) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = stringResource(R.string.title_one_tap_refresh),
                        tint = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
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
                        onApplyNowOfficial = applyNowOfficialProvider,
                        legacyProviderReleaseHome = legacyProviderReleaseHome,
                    )
                }
            }
        }
    }

    OneTapRefreshHost(controller = oneTapRefresh)
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
    // 插件添加完成后同样提供一键刷新入口。
    val oneTapRefresh = rememberOneTapRefreshController(snackbarHostState)
    var showRefreshFab by remember { mutableStateOf(false) }
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
                OfficialProviderAcquisitionLog.info("开始添加插件: id=${item.catalog.id}")
                runCatching {
                    OfficialProviderRepository.downloadAndInstall(context, item)
                    refreshOfficialProviders(context, stateFlow)
                }.onSuccess {
                    showRefreshFab = true
                    snackbarHostState.showSnackbar(
                        message = if (item.usesSystemMediaRuntime()) {
                            addSuccessSystemUiMessage
                        } else {
                            addSuccessFormat.replace("%1\$s", item.catalog.displayName)
                        },
                        duration = SnackbarDuration.Custom(2500L),
                    )
                }.onFailure { error ->
                    OfficialProviderAcquisitionLog.error(
                        "插件添加失败: id=${item.catalog.id} " +
                            "error=${OfficialProviderAcquisitionLog.describe(error)}",
                    )
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
        floatingActionButton = {
            AnimatedVisibility(
                visible = showRefreshFab,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                FloatingActionButton(onClick = { oneTapRefresh.open() }) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = stringResource(R.string.title_one_tap_refresh),
                        tint = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
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
                val availableItems = state.value.items.filter { item ->
                    !item.installed &&
                        OfficialProviderCatalog.shouldShowInDownloadList(item.catalog.id)
                }
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
                            val downloadable = item.selectedRelease != null
                            val versionText = item.selectedRelease?.versionName
                                ?: item.catalog.versionName
                                ?: stringResource(R.string.unknown)
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
                                            if (downloadable) {
                                                stringResource(R.string.provider_status_available, versionText)
                                            } else {
                                                stringResource(R.string.provider_status_unavailable)
                                            },
                                        )
                                        if (item.catalog.id == "salt-player") {
                                            append("\n")
                                            append(stringResource(R.string.provider_salt_player_upgrade_hint))
                                        }
                                        append("\n")
                                        append(item.catalog.targetPackages.joinToString())
                                    },
                                    onClick = if (!busy && downloadable) {
                                        { installProvider(item) }
                                    } else {
                                        null
                                    },
                                    endActions = {
                                        Text(
                                            text = if (busy) {
                                                stringResource(R.string.provider_status_downloading)
                                            } else if (!downloadable) {
                                                stringResource(R.string.provider_status_unavailable)
                                            } else {
                                                stringResource(R.string.provider_action_download)
                                            },
                                            color = if (downloadable || busy) {
                                                MiuixTheme.colorScheme.primary
                                            } else {
                                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            },
                                           fontSize = 14.sp,
                                       )
                                   },
                                    enabled = !busy && downloadable,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    OneTapRefreshHost(controller = oneTapRefresh)
}
