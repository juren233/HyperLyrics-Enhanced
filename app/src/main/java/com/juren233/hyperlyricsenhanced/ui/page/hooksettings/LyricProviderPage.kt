package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderItem
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRepository
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderUiState
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator

import com.juren233.hyperlyricsenhanced.ui.component.ProComponent
import com.juren233.hyperlyricsenhanced.ui.component.SuperSwitchPreference
import com.juren233.hyperlyricsenhanced.ui.component.TagComponent
import com.juren233.hyperlyricsenhanced.utils.LyricProviderManager
import com.juren233.hyperlyricsenhanced.utils.ModuleCategory
import com.juren233.hyperlyricsenhanced.utils.ModuleTag
import com.juren233.hyperlyricsenhanced.utils.ProviderUiState
import com.juren233.hyperlyricsenhanced.ui.utils.BlurredBar
import com.juren233.hyperlyricsenhanced.ui.utils.pageScrollModifiers
import com.juren233.hyperlyricsenhanced.ui.utils.rememberBlurBackdrop
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
import top.yukonga.miuix.kmp.theme.MiuixTheme


import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

@Composable
fun LyricProviderPage() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val providerReleaseHome = stringResource(R.string.provider_release_home)
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
    val snackbarHostState = remember { SnackbarHostState() }
    val installSuccessMessage = stringResource(R.string.provider_install_success)
    val restartRequiredMessage = stringResource(R.string.provider_restart_required)

    LaunchedEffect(Unit) {
        LyricProviderManager.loadProviders(context, providerUiStateFlow)
        refreshOfficialProviders(officialUiStateFlow)
    }

    val othersCategoryName = stringResource(id = R.string.category_others)
    val groupedModules = remember(providerUiState.value.modules) {
        LyricProviderManager.categorizeModules(providerUiState.value.modules, othersCategoryName)
    }

    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }
    val installOfficialProvider: (OfficialProviderItem) -> Unit = { item ->
        if (item.catalog.id !in officialUiStateFlow.value.busyPluginIds) {
            officialUiStateFlow.update {
                it.copy(busyPluginIds = it.busyPluginIds + item.catalog.id)
            }
            coroutineScope.launch {
                runCatching {
                    OfficialProviderRepository.downloadAndInstall(context, item)
                    refreshOfficialProviders(officialUiStateFlow)
                }.onSuccess {
                    snackbarHostState.showSnackbar(
                        message = installSuccessMessage,
                        duration = SnackbarDuration.Custom(2500L),
                    )
                }.onFailure { error ->
                    officialUiStateFlow.update {
                        it.copy(
                            busyPluginIds = it.busyPluginIds - item.catalog.id,
                            error = error.message,
                        )
                    }
                    snackbarHostState.showSnackbar(
                        message = context.getString(
                            R.string.provider_install_failed,
                            error.message ?: context.getString(R.string.unknown),
                        ),
                        duration = SnackbarDuration.Custom(3500L),
                    )
                }
            }
        }
    }
    val setOfficialProviderEnabled: (OfficialProviderItem, Boolean) -> Unit =
        { item, enabled ->
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
                    message = restartRequiredMessage,
                    duration = SnackbarDuration.Custom(2500L),
                )
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
                            Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(id = R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, providerReleaseHome.toUri())) } catch (_: Exception) {} }) {
                            Icon(painter = painterResource(id = R.drawable.ic_github), contentDescription = stringResource(id = R.string.github), tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(26.dp))
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            PullToRefresh(
                isRefreshing = providerUiState.value.isLoading || officialUiState.value.isLoading,
                onRefresh = {
                    coroutineScope.launch {
                        LyricProviderManager.loadProviders(context, providerUiStateFlow)
                        refreshOfficialProviders(officialUiStateFlow)
                    }
                },
                pullToRefreshState = pullToRefreshState,
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
                refreshTexts = listOf(stringResource(id = R.string.refresh_pull_down), stringResource(id = R.string.refresh_release), stringResource(id = R.string.refreshing), stringResource(id = R.string.refresh_success)),
                modifier = Modifier.fillMaxSize()
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
                        topAppBarScrollBehavior = topAppBarScrollBehavior
                    ),
                    contentPadding = contentPadding,
                ) {
                    providerSections(
                        officialUiState = officialUiState.value,
                        uiState = providerUiState.value,
                        groupedModules = groupedModules,
                        expandedStates = expandedStates,
                        onInstallOfficial = installOfficialProvider,
                        onOfficialEnabledChange = setOfficialProviderEnabled,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.providerSections(
    officialUiState: OfficialProviderUiState,
    uiState: ProviderUiState,
    groupedModules: List<ModuleCategory>,
    expandedStates: MutableMap<String, Boolean>,
    onInstallOfficial: (OfficialProviderItem) -> Unit,
    onOfficialEnabledChange: (OfficialProviderItem, Boolean) -> Unit,
) {
    item(key = "builtin_header") {
        SmallTitle(
            text = stringResource(R.string.title_builtin_provider),
            insideMargin = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    item(key = "builtin_apple_music") {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth(),
        ) {
            ProComponent(
                title = "Apple Music",
                summary = stringResource(R.string.summary_builtin_apple_provider),
                endActions = {
                    Text(
                        text = stringResource(R.string.provider_status_builtin),
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                    )
                },
                showIndication = false,
            )
        }
    }

    item(key = "official_header") {
        SmallTitle(
            text = stringResource(R.string.title_official_provider_plugins),
            insideMargin = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
        )
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
                    title = stringResource(
                        if (officialUiState.isLoading) {
                            R.string.provider_catalog_loading
                        } else {
                            R.string.provider_catalog_load_failed
                        }
                    ),
                    summary = officialUiState.error,
                    showIndication = false,
                )
            }
        }
    } else {
        items(
            officialUiState.items.size,
            key = { "official_${officialUiState.items[it].catalog.id}" },
        ) { index ->
            val item = officialUiState.items[index]
            val busy = item.catalog.id in officialUiState.busyPluginIds
            val versionText = item.catalog.versionName ?: stringResource(R.string.unknown)
            val status = when {
                busy -> stringResource(R.string.provider_status_processing)
                item.updateAvailable -> stringResource(R.string.provider_status_update_available, versionText)
                item.installed && item.enabled ->
                    stringResource(R.string.provider_status_installed_enabled, item.installedVersionCode)
                item.installed ->
                    stringResource(R.string.provider_status_installed_disabled, item.installedVersionCode)
                item.catalog.available ->
                    stringResource(R.string.provider_status_available, versionText)
                else -> stringResource(R.string.provider_status_migrating)
            }
            val summary = buildString {
                append(status)
                append("\n")
                append(item.catalog.targetPackages.joinToString())
            }
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth(),
            ) {
                if (item.installed) {
                    SuperSwitchPreference(
                        checked = item.enabled,
                        onCheckedChange = { onOfficialEnabledChange(item, it) },
                        title = item.catalog.displayName,
                        summary = summary,
                        onClick = if (item.updateAvailable && !busy) {
                            { onInstallOfficial(item) }
                        } else {
                            null
                        },
                        endActions = {
                            if (item.updateAvailable) {
                                Text(
                                    text = stringResource(R.string.provider_action_update),
                                    color = MiuixTheme.colorScheme.primary,
                                    fontSize = 14.sp,
                                )
                            }
                        },
                        enabled = !busy,
                    )
                } else {
                    ProComponent(
                        title = item.catalog.displayName,
                        summary = summary,
                        onClick = if (item.catalog.available && !busy) {
                            { onInstallOfficial(item) }
                        } else {
                            null
                        },
                        endActions = {
                            if (item.catalog.available) {
                                Text(
                                    text = stringResource(R.string.provider_action_download),
                                    color = MiuixTheme.colorScheme.primary,
                                    fontSize = 14.sp,
                                )
                            }
                        },
                        enabled = !busy && item.catalog.available,
                    )
                }
            }
        }
    }

    item(key = "legacy_header") {
        SmallTitle(
            text = stringResource(R.string.title_legacy_provider_modules),
            insideMargin = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    if (!uiState.isLoading && uiState.modules.isEmpty()) {
        item(key = "no_legacy_provider") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
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
                    SmallTitle(text = category.name, insideMargin = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp))
                }
            }
            items(category.items.size, key = { "provider_${category.items[it].packageInfo.packageName}" }) { index ->
                val module = category.items[index]
                val packageName = module.packageInfo.packageName
                val isExpanded = expandedStates[packageName] ?: false

                val delayKey = RootConstants.KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX + packageName
                val initialDelay = remember(packageName) {
                    PrefsBridge.getInt(delayKey, RootConstants.DEFAULT_HOOK_LYRICON_PROVIDER_DELAY)
                }
                var currentDelay by remember(packageName) { mutableIntStateOf(initialDelay) }
                var sliderPosition by remember(packageName) { mutableFloatStateOf(initialDelay.toFloat()) }

                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .fillMaxWidth(),
                    onClick = { expandedStates[packageName] = !isExpanded }
                ) {

                    Column {
                        ProComponent(
                            title = module.label,
                            summary = stringResource(
                                id = R.string.format_version_author,
                                module.packageInfo.versionName ?: stringResource(id = R.string.unknown),
                                module.author ?: stringResource(id = R.string.unknown_author)
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
                                                android.widget.ImageView(context).apply {
                                                    setImageDrawable(icon)
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            },
                            endActions = {
                                Text(
                                    text = if (currentDelay > 0) "+$currentDelay ms" else "$currentDelay ms",
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    fontSize = 14.sp
                                )
                            },
                            showIndication = false
                        )

                        AnimatedVisibility(visible = isExpanded) {
                            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                ProComponent(
                                    summary = module.description,
                                    insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 0.dp)
                                )
                                if (module.tags.isNotEmpty()) {
                                    ModuleTagsFlow(module.tags)
                                }
                                
                                Column(modifier = Modifier.padding(PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp))) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = stringResource(R.string.title_lyric_delay),
                                            color = Color.Black,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.summary_lyric_delay),
                                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            fontSize = 12.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Slider(
                                        value = sliderPosition,
                                        onValueChange = { sliderValue ->
                                            sliderPosition = sliderValue
                                            currentDelay = (sliderValue / 50f).roundToInt() * 50
                                        },
                                        onValueChangeFinished = {
                                            val finalValue = (sliderPosition / 50f).roundToInt() * 50
                                            sliderPosition = finalValue.toFloat()
                                            currentDelay = finalValue
                                            PrefsBridge.putInt(delayKey, finalValue)
                                        },
                                        valueRange = RootConstants.MIN_HOOK_LYRICON_PROVIDER_DELAY.toFloat()..RootConstants.MAX_HOOK_LYRICON_PROVIDER_DELAY.toFloat(),
                                        steps = 199,
                                        showKeyPoints = true,
                                        keyPoints = listOf(-5000f, -4000f, -3000f, -2000f, -1000f, 0f, 1000f, 2000f, 3000f, 4000f, 5000f),
                                        hapticEffect = SliderDefaults.SliderHapticEffect.Step
                                    )
                                }
                            }
                        }
                    }


                }
            }
        }
    }
}

private suspend fun refreshOfficialProviders(
    stateFlow: MutableStateFlow<OfficialProviderUiState>,
) {
    stateFlow.update { it.copy(isLoading = true, error = null) }
    runCatching {
        OfficialProviderRepository.loadItems()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleTagsFlow(tags: List<ModuleTag>) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        tags.forEach { tag ->
            val title = if (tag.titleRes != -1) stringResource(tag.titleRes) else tag.title.orEmpty()
            TagComponent(
                text = title,
                iconRes = tag.iconRes,
                imageVector = tag.imageVector,
                isRainbow = tag.isRainbow,
                modifier = Modifier.padding(end = 10.dp)
            )
        }
    }
}
