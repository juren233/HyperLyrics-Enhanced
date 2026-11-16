/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import android.content.Context
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.DataRevision
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheLimit
import com.juren233.hyperlyricsenhanced.ui.component.NumberInputDialog
import com.juren233.hyperlyricsenhanced.ui.component.SectionEnter
import com.juren233.hyperlyricsenhanced.ui.component.SectionExit
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import androidx.compose.runtime.mutableIntStateOf
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheIpc
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheLastUsed
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheManager
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSelection
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSnapshot
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSong
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSongSummary
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheSourceStats
import com.juren233.hyperlyricsenhanced.online.cache.OnlineLyricCacheUse
import com.juren233.hyperlyricsenhanced.online.model.Source
import com.juren233.hyperlyricsenhanced.ui.component.lazyCardRow
import com.juren233.hyperlyricsenhanced.ui.component.pinnedHeader
import com.juren233.hyperlyricsenhanced.ui.component.PinnedHeaderState
import com.juren233.hyperlyricsenhanced.ui.component.pinnedHeaderContent
import com.juren233.hyperlyricsenhanced.ui.component.pinnedHeaderFiller
import com.juren233.hyperlyricsenhanced.ui.component.rememberPinnedHeaderContentMinHeight
import com.juren233.hyperlyricsenhanced.ui.component.rememberPinnedHeaderState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.juren233.hyperlyricsenhanced.ui.component.SimpleDialog
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.juren233.hyperlyricsenhanced.ui.utils.LocalAppThemeController
import com.juren233.hyperlyricsenhanced.ui.utils.floatingBlurSurface
import com.juren233.hyperlyricsenhanced.ui.utils.LocaleUtils
import com.juren233.hyperlyricsenhanced.ui.utils.isMonet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowCascadingListPopup

/**
 * One song as the page shows it. The cache has two copies only because neither process can read
 * the other's data directory: SystemUI (the hooks) and this app (its own lyric service). A song in
 * both is one row, and every clear goes to both copies. Only sources holding lyrics for the song
 * are listed; miss records expire on their own and are not shown.
 */
internal data class CachedSongItem(
    val song: OnlineLyricCacheSong,
    val usedAtMs: Long,
    /** Sources with lyrics for this song; sizes cover every record of that source in both copies. */
    val sources: List<OnlineLyricCacheSourceStats>,
    /** The sources the song last showed online content from, each one only while it still has lyrics here. */
    val lastUsed: OnlineLyricCacheLastUsed = OnlineLyricCacheLastUsed(),
) {
    val id: String get() = song.tag

    fun sizeOf(source: Source): Long? = sources.firstOrNull { it.source == source.name }?.sizeBytes

    /** The song page's order: largest first, then the higher match score (sources without one last). */
    val sourcesBySize: List<OnlineLyricCacheSourceStats>
        get() = sources.sortedWith(
            compareByDescending<OnlineLyricCacheSourceStats> { it.sizeBytes }
                .thenByDescending { it.score ?: Int.MIN_VALUE },
        )

    companion object {
        /** Merges the copies of one song; null when no source has lyrics for it. */
        fun merge(copies: List<OnlineLyricCacheSongSummary>): CachedSongItem? {
            // Match details and the last shown source come from the most recently used copy that has them.
            val recentFirst = copies.sortedByDescending { it.usedAtMs }
            val sources = recentFirst.flatMap { it.sources }
                .groupBy { it.source }
                .filterValues { stats -> stats.any { it.hits > 0 } }
                .map { (name, stats) ->
                    OnlineLyricCacheSourceStats(
                        source = name,
                        hits = stats.sumOf { it.hits },
                        misses = stats.sumOf { it.misses },
                        sizeBytes = stats.sumOf { it.sizeBytes },
                        score = stats.firstNotNullOfOrNull { it.score },
                        wordTimed = stats.firstNotNullOfOrNull { it.wordTimed },
                    )
                }
                .sortedBy { stats -> Source.entries.indexOfFirst { it.name == stats.source }.takeIf { it >= 0 } ?: Int.MAX_VALUE }
            if (sources.isEmpty()) return null
            val latest = copies.maxBy { it.usedAtMs }
            val album = latest.song.album.ifBlank {
                copies.firstNotNullOfOrNull { copy -> copy.song.album.takeIf(String::isNotBlank) }.orEmpty()
            }
            val lastUsed = recentFirst.map { it.lastUsed }.firstOrNull { !it.isEmpty }
                ?.filter { name -> sources.any { it.source == name } }
                ?: OnlineLyricCacheLastUsed()
            return CachedSongItem(latest.song.copy(album = album), latest.usedAtMs, sources, lastUsed)
        }

        /** The badge naming what the song last showed from a source: 「上次歌词」「上次翻译」… or 「上次使用」 for all three. */
        @StringRes
        fun lastUsedLabel(uses: Set<OnlineLyricCacheUse>): Int? {
            val lyrics = OnlineLyricCacheUse.LYRICS in uses
            val translation = OnlineLyricCacheUse.TRANSLATION in uses
            val pronunciation = OnlineLyricCacheUse.PRONUNCIATION in uses
            return when {
                lyrics && translation && pronunciation -> R.string.online_lyric_cache_last_used
                lyrics && translation -> R.string.online_lyric_cache_last_lyrics_translation
                lyrics && pronunciation -> R.string.online_lyric_cache_last_lyrics_pronunciation
                translation && pronunciation -> R.string.online_lyric_cache_last_pronunciation_translation
                lyrics -> R.string.online_lyric_cache_last_lyrics
                translation -> R.string.online_lyric_cache_last_translation
                pronunciation -> R.string.online_lyric_cache_last_pronunciation
                else -> null
            }
        }
    }
}

/** Both copies of the cache; [systemUi] is null when SystemUI did not answer. */
internal data class OnlineLyricCacheState(
    val systemUi: OnlineLyricCacheSnapshot?,
    val app: OnlineLyricCacheSnapshot,
) {
    val songs: List<CachedSongItem> = (systemUi?.songs.orEmpty() + app.songs)
        .groupBy { it.song.tag }
        .values
        .mapNotNull(CachedSongItem::merge)
        .sortedByDescending { it.usedAtMs }

    /**
     * Whether [limit] would drop songs right away: a copy holds more songs with lyrics than it.
     * The limit applies to each copy on its own, so the merged list is not what counts.
     */
    fun dropsSongsAt(limit: Int): Boolean =
        listOfNotNull(systemUi, app).any { copy -> copy.songs.count { song -> song.sources.any { it.hits > 0 } } > limit }

    /** Space on disk of both copies, miss records included. */
    val totalBytes: Long get() = (systemUi?.totalBytes ?: 0L) + app.totalBytes

    /** Each source's share, computed once with the state (built off the main thread by [OnlineLyricCacheRepository.load]). */
    private val statsBySource: Map<Source, SourceCacheStats> = Source.entries.associateWith { source ->
        val withSource = songs.filter { it.sizeOf(source) != null }
        SourceCacheStats(source, withSource, withSource.sumOf { it.sizeOf(source) ?: 0L })
    }

    fun of(source: Source): SourceCacheStats = statsBySource.getValue(source)

    /** 「按来源分类」的来源行：占用从大到小；占用相同按缓存的歌曲数从多到少；仍相同按来源顺序。 */
    val bySource: List<SourceCacheStats> = Source.entries.map(::of).sortedWith(
        compareByDescending<SourceCacheStats> { it.bytes }.thenByDescending { it.songs.size },
    )
}

/** How the song list is shown: every song, or one row per artist / album leading to its songs. */
internal enum class CachedSongGrouping(@StringRes val label: Int, @StringRes private val unknown: Int) {
    All(R.string.online_lyric_cache_grouping_all, R.string.online_lyric_cache_unknown_song),
    Artist(R.string.online_lyric_cache_grouping_artist, R.string.online_lyric_cache_unknown_artist),
    Album(R.string.online_lyric_cache_grouping_album, R.string.online_lyric_cache_unknown_album);

    /** The group a song belongs to, taken as recorded (a blank value is its own group). */
    fun keyOf(song: OnlineLyricCacheSong): String = when (this) {
        All -> ""
        Artist -> song.artist.trim()
        Album -> song.album.trim()
    }

    @Composable
    fun displayName(key: String): String = key.ifBlank { stringResource(unknown) }
}

/** The player filter: none chosen means every player; 「全部播放器」 clears the choice, a player toggles. */
internal object PlayerFilter {
    fun toggle(chosen: Set<String>, player: String?): Set<String> = when {
        player == null -> emptySet()
        player in chosen -> chosen - player
        else -> chosen + player
    }
}

/** One artist's or album's songs and the space they take. */
internal data class CachedSongGroup(val name: String, val songs: List<CachedSongItem>, val bytes: Long)

/** Space a song takes: on a source page only that source's share, otherwise every source. */
internal fun CachedSongItem.bytesIn(source: Source?): Long =
    if (source == null) sources.sumOf { it.sizeBytes } else sizeOf(source) ?: 0L

/** Groups, ordered like the source rows: size, then song count (both descending), then name. */
internal fun List<CachedSongItem>.groupedBy(grouping: CachedSongGrouping, source: Source?): List<CachedSongGroup> =
    groupBy { grouping.keyOf(it.song) }
        .map { (name, songs) -> CachedSongGroup(name, songs, songs.sumOf { it.bytesIn(source) }) }
        .sortedWith(
            compareByDescending<CachedSongGroup> { it.bytes }.thenByDescending { it.songs.size }.thenBy { it.name },
        )

/** One source's share of the cache: the songs it holds lyrics for and the space they take. */
internal data class SourceCacheStats(
    val source: Source,
    val songs: List<CachedSongItem>,
    val bytes: Long,
)

internal object OnlineLyricCacheRepository {
    /** Bumped after every clear, so the entry, the list and the song page all show the new state. */
    val revision = DataRevision()

    /** Reads both copies; merging, sorting and per-source totals run off the main thread. */
    suspend fun load(context: Context): OnlineLyricCacheState {
        val systemUi = OnlineLyricCacheIpc.requestSnapshot(context)
        val app = OnlineLyricCacheManager.snapshot(context)
        return withContext(Dispatchers.Default) { OnlineLyricCacheState(systemUi, app) }
    }

    /** Applies [selection] to both copies. */
    suspend fun delete(context: Context, selection: OnlineLyricCacheSelection) {
        try {
            OnlineLyricCacheIpc.requestDelete(context, selection)
            OnlineLyricCacheManager.delete(context, selection)
        } finally {
            revision.bump()
        }
    }

    /** Saves the 「缓存上限」 choice and applies the resulting limit to both copies at once. */
    suspend fun applyLimit(context: Context, mode: Int, custom: Int) {
        PrefsBridge.putInt(OnlineLyricCacheLimit.KEY_MODE, mode)
        PrefsBridge.putInt(OnlineLyricCacheLimit.KEY_CUSTOM, custom)
        val limit = OnlineLyricCacheLimit.resolve(mode, custom)
        try {
            OnlineLyricCacheIpc.requestTrim(context, limit)
            OnlineLyricCacheManager.trim(context, limit)
        } finally {
            revision.bump()
        }
    }

    /** Deletes [items] from both copies; with [source], only what that source cached for them. */
    suspend fun deleteSongs(context: Context, items: Collection<CachedSongItem>, source: Source? = null) =
        delete(
            context,
            OnlineLyricCacheSelection(
                tags = items.mapTo(linkedSetOf()) { it.song.tag },
                sources = source?.let { setOf(it.name) },
            ),
        )
}

@Composable
private fun formatSize(bytes: Long): String {
    val context = LocalContext.current
    return remember(bytes) { Formatter.formatShortFileSize(context, bytes) }
}

/** 「三方在线源配置」页里「自动选择最优来源」下方的入口。 */
@Composable
internal fun OnlineLyricCacheEntry() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    var state by remember { mutableStateOf<OnlineLyricCacheState?>(null) }
    val revision by OnlineLyricCacheRepository.revision.value.collectAsState()
    LaunchedEffect(revision) { state = OnlineLyricCacheRepository.load(context) }
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth()
    ) {
        ArrowPreference(
            title = stringResource(R.string.title_online_lyric_cache),
            summary = state?.let { cacheSummary(it.songs.size, it.totalBytes) },
            onClick = { navigator.navigate(Route.OnlineLyricCache) },
        )
    }
}

@Composable
private fun cacheSummary(songs: Int, bytes: Long): String =
    stringResource(
        R.string.summary_online_lyric_cache,
        pluralStringResource(R.plurals.online_lyric_cache_songs, songs, songs),
        formatSize(bytes),
    )

private sealed interface PendingClear {
    data class OfSource(val source: Source) : PendingClear
    data object All : PendingClear
    data class Songs(val items: List<CachedSongItem>) : PendingClear
}

/** 「按来源分类」的来源行：缓存的歌曲数与占用。 */
@Composable
private fun sourceSummary(stats: SourceCacheStats): String =
    if (stats.songs.isEmpty()) stringResource(R.string.online_lyric_cache_empty) else cacheSummary(stats.songs.size, stats.bytes)

/**
 * 歌词缓存管理页。
 *
 * 给了 [sourceName] 时是该来源的页面：只列有它缓存的歌，删除也只删它的那部分。
 * 给了 [groupName] 时是某位歌手或某张专辑（[grouping]）的歌曲页，范围再叠加来源与 [initialPlayers]。
 */
@Composable
internal fun OnlineLyricCachePage(
    sourceName: String? = null,
    grouping: CachedSongGrouping = CachedSongGrouping.All,
    groupName: String? = null,
    initialPlayers: List<String> = emptyList(),
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val source = sourceName?.let { name -> Source.entries.firstOrNull { it.name == name } }
    val isGroupPage = groupName != null && grouping != CachedSongGrouping.All
    var state by remember { mutableStateOf<OnlineLyricCacheState?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var chosenPlayers by rememberSaveable { mutableStateOf(initialPlayers) }
    var listGrouping by rememberSaveable { mutableStateOf(CachedSongGrouping.All) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var pending by remember { mutableStateOf<PendingClear?>(null) }
    var appLabels by remember { mutableStateOf(emptyMap<String, String>()) }

    val revision by OnlineLyricCacheRepository.revision.value.collectAsState()
    LaunchedEffect(revision) {
        val loaded = OnlineLyricCacheRepository.load(context)
        state = loaded
        val ids = loaded.songs.mapTo(HashSet()) { it.id }
        selected = selected.filterTo(linkedSetOf()) { it in ids }
        appLabels = withContext(Dispatchers.IO) { loadAppLabels(context, loaded.songs) }
    }

    val current = state
    val sourceStats = source?.let { current?.of(it) }
    val scopeSongs = remember(current, source, grouping, groupName) {
        val inSource = if (source == null) current?.songs.orEmpty() else current?.of(source)?.songs.orEmpty()
        if (isGroupPage) inSource.filter { grouping.keyOf(it.song) == groupName } else inSource
    }
    val players = remember(scopeSongs) { scopeSongs.mapTo(linkedSetOf()) { it.song.packageName }.toList() }
    // A player whose songs are all gone no longer narrows the list; none chosen means every player.
    val activePlayers = remember(chosenPlayers, players) { chosenPlayers.filterTo(linkedSetOf()) { it in players } }
    val pageSongs = remember(scopeSongs, activePlayers) {
        if (activePlayers.isEmpty()) scopeSongs else scopeSongs.filter { it.song.packageName in activePlayers }
    }
    val text = query.trim()
    val visibleSongs = remember(pageSongs, text) {
        if (text.isEmpty()) {
            pageSongs
        } else {
            pageSongs.filter { item ->
                item.song.title.contains(text, ignoreCase = true) || item.song.artist.contains(text, ignoreCase = true)
            }
        }
    }
    val groups = remember(pageSongs, listGrouping, source, text) {
        if (listGrouping == CachedSongGrouping.All) {
            emptyList()
        } else {
            pageSongs.groupedBy(listGrouping, source).filter { group ->
                text.isEmpty() || group.name.contains(text, ignoreCase = true)
            }
        }
    }
    val showGroups = !isGroupPage && listGrouping != CachedSongGrouping.All
    val listState = rememberLazyListState()
    val songsHeader = rememberPinnedHeaderState(key = "songs_header")
    // 头之后的列表至少填满头以下的区域：搜索、筛选把列表变短时，头停住的位置不会被拉回。
    val songsMinHeight = rememberPinnedHeaderContentMinHeight(songsHeader, listState)

    XposedLyricSettingPage(
        listState = listState,
        pinnedHeader = songsHeader,
        title = when {
            isGroupPage -> grouping.displayName(groupName.orEmpty())
            else -> source?.displayName() ?: stringResource(R.string.title_online_lyric_cache)
        },
        subtitle = if (isGroupPage) source?.displayName().orEmpty() else "",
        trailingContent = {
            ClearAllButton {
                pending = when {
                    isGroupPage -> PendingClear.Songs(pageSongs)
                    source == null -> PendingClear.All
                    else -> PendingClear.OfSource(source)
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(visible = selected.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                SelectionBar(
                    count = selected.size,
                    allSelected = visibleSongs.isNotEmpty() && visibleSongs.all { it.id in selected },
                    onToggleAll = {
                        selected = if (visibleSongs.all { it.id in selected }) {
                            selected - visibleSongs.map { it.id }.toSet()
                        } else {
                            selected + visibleSongs.map { it.id }
                        }
                    },
                    onDelete = {
                        pending = PendingClear.Songs(pageSongs.filter { it.id in selected })
                    },
                )
            }
        },
    ) {
        item(key = "overview") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                val songCount = when {
                    isGroupPage -> current?.let { scopeSongs.size }
                    source == null -> current?.songs?.size
                    else -> sourceStats?.songs?.size
                }
                val bytes = when {
                    isGroupPage -> current?.let { scopeSongs.sumOf { it.bytesIn(source) } }
                    source == null -> current?.totalBytes
                    else -> sourceStats?.bytes
                }
                BasicComponent(
                    title = stringResource(R.string.title_online_lyric_cache_songs_count),
                    endActions = {
                        ValueText(
                            songCount?.let { pluralStringResource(R.plurals.online_lyric_cache_songs, it, it) }
                                ?: stringResource(R.string.online_lyric_cache_loading)
                        )
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.title_online_lyric_cache_size),
                    endActions = { ValueText(bytes?.let { formatSize(it) } ?: "") },
                )
                if (source == null && !isGroupPage && current != null && current.systemUi == null) {
                    BasicComponent(summary = stringResource(R.string.online_lyric_cache_system_ui_unavailable))
                }
            }
        }
        if (source == null && !isGroupPage) {
            item(key = "limit") { CacheLimitCard(current) }
        }
        if (source == null && !isGroupPage) {
            item(key = "categories_title") { SmallTitle(text = stringResource(R.string.title_online_lyric_cache_categories)) }
            item(key = "categories") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .fillMaxWidth()
                ) {
                    val rows = current?.bySource?.map { it.source to it } ?: Source.entries.map { it to null }
                    rows.forEach { (rowSource, stats) ->
                        ArrowPreference(
                            title = rowSource.displayName(),
                            summary = stats?.let { sourceSummary(it) },
                            onClick = { navigator.navigate(Route.OnlineLyricCacheSource(rowSource.name)) },
                        )
                    }
                }
            }
        }
        // 小标题、两个按钮与搜索框一起，滚动时固定在顶栏下方。
        pinnedHeader(songsHeader, listState) {
            SongListTitle(
                players = players,
                chosen = activePlayers,
                appLabels = appLabels,
                onPlayerToggle = { pkg -> chosenPlayers = PlayerFilter.toggle(activePlayers, pkg).toList() },
                grouping = listGrouping.takeUnless { isGroupPage },
                onGroupingChange = { grouping ->
                    listGrouping = grouping
                    selected = emptySet()
                },
            )
            AppSearchField(
                query = query,
                onQueryChange = { query = it },
                label = stringResource(
                    when {
                        !showGroups -> R.string.hint_online_lyric_cache_search
                        listGrouping == CachedSongGrouping.Artist -> R.string.hint_online_lyric_cache_search_artist
                        else -> R.string.hint_online_lyric_cache_search_album
                    },
                ),
            )
        }
        if (showGroups) {
            groupList(
                header = songsHeader,
                groups = groups,
                grouping = listGrouping,
                loading = current == null,
                onOpen = { group ->
                    navigator.navigate(
                        Route.OnlineLyricCacheGroup(
                            grouping = listGrouping.name,
                            name = group.name,
                            source = source?.name,
                            players = activePlayers.toList(),
                        ),
                    )
                },
            )
        } else {
            songList(
                header = songsHeader,
                songs = visibleSongs,
                loading = current == null,
                selected = selected,
                appLabels = appLabels,
                onToggle = { item ->
                    selected = if (item.id in selected) selected - item.id else selected + item.id
                },
            )
        }
        pinnedHeaderFiller(
            state = songsHeader,
            key = "bottom_space",
            contentKeys = if (showGroups) groupListKeys(groups) else songListKeys(visibleSongs),
            minContentHeight = songsMinHeight,
            baseHeight = 96.dp,
        )
    }

    pending?.let { clear ->
        SimpleDialog(
            show = true,
            title = when (clear) {
                is PendingClear.OfSource -> stringResource(R.string.confirm_online_lyric_cache_clear_source, clear.source.displayName())
                PendingClear.All -> stringResource(R.string.title_online_lyric_cache_clear_all)
                is PendingClear.Songs -> if (source == null) {
                    pluralStringResource(R.plurals.confirm_online_lyric_cache_delete_songs, clear.items.size, clear.items.size)
                } else {
                    pluralStringResource(
                        R.plurals.confirm_online_lyric_cache_delete_songs_of_source,
                        clear.items.size,
                        clear.items.size,
                        source.displayName(),
                    )
                }
            },
            summary = stringResource(R.string.summary_online_lyric_cache_refetch),
            onDismiss = { pending = null },
            onConfirm = {
                scope.launch {
                    when (clear) {
                        is PendingClear.OfSource ->
                            OnlineLyricCacheRepository.delete(context, OnlineLyricCacheSelection(sources = setOf(clear.source.name)))
                        PendingClear.All ->
                            OnlineLyricCacheRepository.delete(context, OnlineLyricCacheSelection())
                        is PendingClear.Songs -> {
                            OnlineLyricCacheRepository.deleteSongs(context, clear.items, source)
                            selected = emptySet()
                        }
                    }
                }
            },
        )
    }
}

/** 顶栏右侧的「清除全部缓存」：缓存页、来源页、歌曲页共用。 */
@Composable
private fun ClearAllButton(onClick: () -> Unit) {
    DeleteButton(contentDescription = stringResource(R.string.title_online_lyric_cache_clear_all), onClick = onClick)
}

/** 缓存管理里所有删除按钮共用的垃圾桶图标。 */
@Composable
private fun DeleteButton(contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = MiuixIcons.Delete, contentDescription = contentDescription)
    }
}

@Composable
private fun ValueText(text: String) {
    Text(
        text = text,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(modifier = Modifier.navigationBarsPadding().padding(start = 24.dp).floatingBlurSurface()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = pluralStringResource(R.plurals.online_lyric_cache_selected, count, count),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            TextButton(
                text = stringResource(
                    if (allSelected) R.string.online_lyric_cache_deselect_all else R.string.online_lyric_cache_select_all,
                ),
                onClick = onToggleAll,
            )
            TextButton(
                text = stringResource(R.string.online_lyric_cache_delete),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onDelete,
            )
        }
    }
}

private fun songListKeys(songs: List<CachedSongItem>): List<Any> =
    if (songs.isEmpty()) listOf(SONGS_EMPTY_KEY) else songs.map { it.id }

private const val SONGS_EMPTY_KEY = "songs_empty"

private fun LazyListScope.songList(
    header: PinnedHeaderState,
    songs: List<CachedSongItem>,
    loading: Boolean,
    selected: Set<String>,
    appLabels: Map<String, String>,
    onToggle: (CachedSongItem) -> Unit,
) {
    if (songs.isEmpty()) {
        item(key = SONGS_EMPTY_KEY) { Box(Modifier.pinnedHeaderContent(header, SONGS_EMPTY_KEY)) { EmptyListCard(loading) } }
        return
    }
    itemsIndexed(items = songs, key = { _, item -> item.id }, contentType = { _, _ -> "cached_song" }) { index, item ->
        Box(modifier = Modifier.pinnedHeaderContent(header, item.id).lazyCardRow(index, songs.size)) {
            CachedSongRow(
                item = item,
                appLabel = appLabels[item.song.packageName] ?: item.song.packageName,
                checked = item.id in selected,
                onToggle = { onToggle(item) },
            )
        }
    }
}

/**
 * 「缓存上限」：每份缓存最多保留的有歌词的歌曲数（只有未命中记录的歌不计入），超出时删去最久没用到的歌。
 * 选项为预设歌曲数与「自定义」，选「自定义」时下方出现可点开输入的数值行（与「提前预览下一句」的自定义写法相同）。
 * 新上限会立刻删歌时（任一份缓存的歌多于新上限，或尚未读到缓存而上限调低）先确认。
 */
@Composable
private fun CacheLimitCard(state: OnlineLyricCacheState?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PrefsBridge.getPrefs() }
    var mode by remember { mutableIntStateOf(OnlineLyricCacheLimit.mode(prefs)) }
    var custom by remember { mutableIntStateOf(OnlineLyricCacheLimit.custom(prefs)) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var pendingLimit by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    fun apply(newMode: Int, newCustom: Int) {
        mode = newMode
        custom = newCustom
        scope.launch { OnlineLyricCacheRepository.applyLimit(context, newMode, newCustom) }
    }
    fun request(newMode: Int, newCustom: Int) {
        val limit = OnlineLyricCacheLimit.resolve(newMode, newCustom)
        val drops = state?.dropsSongsAt(limit) ?: (limit < OnlineLyricCacheLimit.resolve(mode, custom))
        if (drops) pendingLimit = newMode to newCustom else apply(newMode, newCustom)
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth()
    ) {
        val presetLabels = OnlineLyricCacheLimit.PRESETS.map { songs ->
            pluralStringResource(R.plurals.online_lyric_cache_songs, songs, songs)
        }
        OverlayDropdownPreference(
            title = stringResource(R.string.title_online_lyric_cache_limit),
            items = presetLabels + stringResource(R.string.online_lyric_cache_limit_custom),
            selectedIndex = mode,
            onSelectedIndexChange = { request(it, custom) },
        )
        AnimatedVisibility(
            visible = mode == OnlineLyricCacheLimit.MODE_CUSTOM,
            enter = SectionEnter,
            exit = SectionExit,
        ) {
            ArrowPreference(
                title = stringResource(R.string.title_online_lyric_cache_limit_custom),
                endActions = { ValueText(pluralStringResource(R.plurals.online_lyric_cache_songs, custom, custom)) },
                onClick = { showCustomDialog = true },
            )
        }
    }
    NumberInputDialog(
        show = showCustomDialog,
        title = stringResource(R.string.title_online_lyric_cache_limit_custom),
        label = stringResource(R.string.label_online_lyric_cache_limit_songs),
        initialValue = custom,
        min = 1,
        max = Int.MAX_VALUE,
        onDismiss = { showCustomDialog = false },
        onConfirm = { request(OnlineLyricCacheLimit.MODE_CUSTOM, it) },
    )
    pendingLimit?.let { (newMode, newCustom) ->
        val limit = OnlineLyricCacheLimit.resolve(newMode, newCustom)
        SimpleDialog(
            show = true,
            title = stringResource(
                R.string.confirm_online_lyric_cache_limit,
                pluralStringResource(R.plurals.online_lyric_cache_songs, limit, limit),
            ),
            summary = stringResource(R.string.summary_online_lyric_cache_limit_trim),
            onDismiss = { pendingLimit = null },
            onConfirm = { apply(newMode, newCustom) },
        )
    }
}

/** 歌曲或分组列表为空时的卡片：读取中 / 暂无缓存。 */
@Composable
private fun EmptyListCard(loading: Boolean) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (loading) R.string.online_lyric_cache_loading else R.string.online_lyric_cache_empty,
                ),
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

private fun groupListKeys(groups: List<CachedSongGroup>): List<Any> =
    if (groups.isEmpty()) listOf(GROUPS_EMPTY_KEY) else groups.map(::groupKey)

private fun groupKey(group: CachedSongGroup): String = "group:${group.name}"

private const val GROUPS_EMPTY_KEY = "groups_empty"

private fun LazyListScope.groupList(
    header: PinnedHeaderState,
    groups: List<CachedSongGroup>,
    grouping: CachedSongGrouping,
    loading: Boolean,
    onOpen: (CachedSongGroup) -> Unit,
) {
    if (groups.isEmpty()) {
        item(key = GROUPS_EMPTY_KEY) { Box(Modifier.pinnedHeaderContent(header, GROUPS_EMPTY_KEY)) { EmptyListCard(loading) } }
        return
    }
    itemsIndexed(items = groups, key = { _, group -> groupKey(group) }, contentType = { _, _ -> "cached_song_group" }) { index, group ->
        Box(modifier = Modifier.pinnedHeaderContent(header, groupKey(group)).lazyCardRow(index, groups.size)) {
            ArrowPreference(
                title = grouping.displayName(group.name),
                summary = cacheSummary(group.songs.size, group.bytes),
                onClick = { onOpen(group) },
            )
        }
    }
}

/**
 * 「已缓存歌曲」小标题，右侧是播放器筛选与分类两个按钮，各自弹出选择列表。
 * [grouping] 为 null 时（歌手、专辑的歌曲页）不显示分类按钮。
 */
@Composable
private fun SongListTitle(
    players: List<String>,
    chosen: Set<String>,
    appLabels: Map<String, String>,
    onPlayerToggle: (String?) -> Unit,
    grouping: CachedSongGrouping?,
    onGroupingChange: (CachedSongGrouping) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallTitle(
            text = stringResource(R.string.title_online_lyric_cache_song_list),
            modifier = Modifier.weight(1f),
        )
        Row(modifier = Modifier.padding(end = 16.dp)) {
            val allPlayers = stringResource(R.string.online_lyric_cache_all_players)
            // 「全部播放器」单选，其余播放器可多选；多选时点完不收起列表。
            ChoiceButton(
                icon = MiuixIcons.Filter,
                contentDescription = stringResource(R.string.title_online_lyric_cache_filter_player),
                active = chosen.isNotEmpty(),
                options = listOf<Pair<String?, String>>(null to allPlayers) +
                    players.map { pkg -> pkg to (appLabels[pkg] ?: pkg) }.sortedBy { it.second },
                isSelected = { pkg -> if (pkg == null) chosen.isEmpty() else pkg in chosen },
                onSelect = onPlayerToggle,
                collapseOnSelection = false,
            )
            if (grouping != null) {
                ChoiceButton(
                    icon = MiuixIcons.Layers,
                    contentDescription = stringResource(R.string.title_online_lyric_cache_grouping),
                    active = grouping != CachedSongGrouping.All,
                    options = CachedSongGrouping.entries.map { it to stringResource(it.label) },
                    isSelected = { it == grouping },
                    onSelect = onGroupingChange,
                )
            }
        }
    }
}

/**
 * 点击后弹出选择列表的图标按钮；选了非默认项时图标用主题色。
 * [collapseOnSelection] 为 false 时点选后列表不收起（多选），点列表外收起。
 */
@Composable
private fun <T> ChoiceButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    collapseOnSelection: Boolean = true,
) {
    var show by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { show = true }, holdDownState = show) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
            )
        }
        WindowCascadingListPopup(
            show = show,
            entries = listOf(
                DropdownEntry(
                    items = options.map { (value, label) ->
                        DropdownItem(text = label, selected = isSelected(value), onClick = { onSelect(value) })
                    },
                ),
            ),
            alignment = PopupPositionProvider.Align.TopEnd,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            onDismissRequest = { show = false },
            collapseOnSelection = collapseOnSelection,
        )
    }
}

@Composable
private fun CachedSongRow(
    item: CachedSongItem,
    appLabel: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val navigator = LocalNavigator.current
    val song = item.song
    BasicComponent(
        title = song.displayName(),
        summary = song.artistAndAlbum(),
        onClick = {
            navigator.navigate(Route.OnlineLyricCacheSongDetail(tag = song.tag))
        },
        endActions = {
            BadgedEndActions {
                CacheBadge(appLabel)
                Checkbox(state = ToggleableState(checked), onClick = onToggle)
            }
        },
    )
}

/** 行右侧的方块与按钮/勾选框：歌曲行与歌曲页来源行同一间距。 */
@Composable
private fun BadgedEndActions(content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * 行右侧的文字方块：歌曲行的播放来源、歌曲页来源行的「上次使用」共用。
 *
 * 非莫奈配色下文字与行标题同色（BasicComponent 标题默认取 onBackground）；
 * 底色：非莫奈用 surfaceContainerHighest（深色下 surfaceContainerHigh 与卡片底色同为 #242424，看不出方块）；
 * 莫奈下 surfaceContainerHigh 与卡片底色太接近，改用 secondaryContainer 及其配对文字色。
 */
@Composable
private fun CacheBadge(label: String) {
    val colors = MiuixTheme.colorScheme
    val monet = LocalAppThemeController.current.isMonet
    Box(
        modifier = Modifier
            .background(
                if (monet) colors.secondaryContainer else colors.surfaceContainerHighest,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = if (monet) colors.onSecondaryContainer else colors.onBackground,
            maxLines = 1,
        )
    }
}

@Composable
private fun OnlineLyricCacheSong.displayName(): String =
    title.ifBlank { stringResource(R.string.online_lyric_cache_unknown_song) }

/** 「歌手名 · 专辑名」，缺哪项就不写哪项。 */
private fun OnlineLyricCacheSong.artistAndAlbum(): String? =
    listOf(artist, album).filter(String::isNotBlank).joinToString(" · ").ifEmpty { null }

private fun loadAppLabels(context: Context, songs: List<CachedSongItem>): Map<String, String> {
    val packageManager = LocaleUtils.systemLanguageContext(context).packageManager
    return songs.map { it.song.packageName }.distinct().associateWith { pkg ->
        runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString() }
            .getOrDefault(pkg)
    }
}

/** One cached song: per-source statistics, each source clearable on its own. */
@Composable
internal fun OnlineLyricCacheSongDetailPage(tag: String) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<CachedSongItem?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var pendingSource by remember { mutableStateOf<String?>(null) }
    var pendingAll by remember { mutableStateOf(false) }

    val revision by OnlineLyricCacheRepository.revision.value.collectAsState()
    LaunchedEffect(revision) {
        item = OnlineLyricCacheRepository.load(context).songs
            .firstOrNull { it.song.tag == tag }
        loaded = true
    }

    val current = item
    XposedLyricSettingPage(
        title = current?.song?.displayName() ?: stringResource(R.string.title_online_lyric_cache),
        subtitle = current?.song?.artistAndAlbum().orEmpty(),
        trailingContent = { if (current != null) ClearAllButton { pendingAll = true } },
    ) {
        item(key = "sources_title") { SmallTitle(text = stringResource(R.string.title_online_lyric_cache_song_sources)) }
        item(key = "sources") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                val sources = current?.sourcesBySize.orEmpty()
                if (sources.isEmpty()) {
                    BasicComponent(
                        title = stringResource(
                            if (loaded) R.string.online_lyric_cache_empty else R.string.online_lyric_cache_loading,
                        ),
                    )
                }
                sources.forEach { stats ->
                    val source = Source.entries.firstOrNull { it.name == stats.source }
                    BasicComponent(
                        title = source?.displayName() ?: stats.source,
                        summary = listOfNotNull(
                            formatSize(stats.sizeBytes),
                            stats.wordTimed?.let { wordTimed ->
                                stringResource(
                                    if (wordTimed) R.string.online_lyric_cache_word_timed
                                    else R.string.online_lyric_cache_line_timed,
                                )
                            },
                            stats.score?.let { stringResource(R.string.online_lyric_cache_score, it) },
                        ).joinToString(" · "),
                        endActions = {
                            BadgedEndActions {
                                current?.lastUsed?.usesOf(stats.source)
                                    ?.let(CachedSongItem::lastUsedLabel)
                                    ?.let { CacheBadge(stringResource(it)) }
                                DeleteButton(
                                    contentDescription = stringResource(R.string.online_lyric_cache_delete),
                                    onClick = { pendingSource = stats.source },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    pendingSource?.let { source ->
        SimpleDialog(
            show = true,
            title = stringResource(
                R.string.confirm_online_lyric_cache_clear_source,
                Source.entries.firstOrNull { it.name == source }?.displayName() ?: source,
            ),
            summary = stringResource(R.string.summary_online_lyric_cache_refetch),
            onDismiss = { pendingSource = null },
            onConfirm = {
                scope.launch {
                    OnlineLyricCacheRepository.delete(
                        context,
                        OnlineLyricCacheSelection(tags = setOf(tag), sources = setOf(source)),
                    )
                }
            },
        )
    }
    if (pendingAll) {
        SimpleDialog(
            show = true,
            title = stringResource(R.string.title_online_lyric_cache_delete_song),
            summary = stringResource(R.string.summary_online_lyric_cache_refetch),
            onDismiss = { pendingAll = false },
            onConfirm = {
                scope.launch {
                    OnlineLyricCacheRepository.delete(context, OnlineLyricCacheSelection(tags = setOf(tag)))
                    navigator.pop()
                }
            },
        )
    }
}
