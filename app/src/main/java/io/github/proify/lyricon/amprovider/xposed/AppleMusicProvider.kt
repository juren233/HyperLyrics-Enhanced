/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.extensions.inflate
import io.github.proify.extensions.json
import io.github.proify.lyricon.amprovider.xposed.hooks.FunctionalAppleMusicHookModule
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalReentryGuard
import io.github.proify.lyricon.amprovider.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/** Apple Music Lyricon provider hosted directly by the HyperLyrics Enhanced module. */
object AppleMusicProvider {
    private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    private const val APPLE_MUSIC_PLAYLIST_PAGE_CONTROLLER =
        "com.apple.android.music.collection.mediaapi.controller.PlaylistPageController"
    private const val APPLE_MUSIC_MAIN_CONTENT_ACTIVITY =
        "com.apple.android.music.common.MainContentActivity"
    private const val APPLE_MUSIC_SHOW_FULL_PLAYER_EXTRA =
        "com.apple.android.music.intent.showfullplayer"
    private const val MEDIA_NOTIFICATION_REQUEST_CODE = 0x484C
    private const val MAX_QUEUE_PREBIND_ENTRIES = 24
    private const val MAX_QUEUE_LOCALIZED_PREFETCH_ENTRIES = 128
    private const val MAX_GENERIC_RECYCLER_MEDIA_IDS = 512
    private const val MAX_LIBRARY_COMPOSE_VISIBLE_RESOLUTION_IDS = 12
    private const val MAX_IN_APP_ARTWORK_CONTINUITY_ENTRIES = 1_024
    private const val MAX_APPLE_SYSTEM_FONT_VARIATION_CACHE_ENTRIES = 64
    private const val IN_APP_ARTWORK_CONTINUITY_TTL_MS = 10 * 60 * 1_000L
    private const val ORIGINAL_METADATA_CACHE_MISS_RETRY_MS = 750L
    private const val MAX_DEBUG_VISIBLE_VIEW_TRACE_KEYS = 1_024
    private const val MAX_DEBUG_VISIBLE_VIEWS_PER_SCAN = 160
    private const val MAX_DEBUG_RECYCLER_VIEWS_PER_SCAN = 16
    private const val MAX_DEBUG_QUEUE_BIND_TRACE_KEYS = 512
    private const val MAX_DEBUG_QUEUE_SUBMIT_TRACE_ENTRIES = 64
    private const val ALBUM_CONTROLLER_REFRESH_DEBOUNCE_MS = 180L
    private const val PLAYLIST_CONTROLLER_REFRESH_INTERVAL_MS = 500L
    private const val ONLINE_SOURCE_MENU_ITEM_TAG =
        "hyperlyrics_enhanced_online_lyrics_source"
    private const val ONLINE_SOURCE_SWITCH_TIMEOUT_MS = 15_000L
    private const val ONLINE_SOURCE_SWITCH_FAILURE_FEEDBACK_MS = 2_000L
    private const val APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION = 0.22f
    private const val APPLE_LYRICS_SCROLL_STATE_IDLE = 0
    private const val APPLE_LYRICS_IDLE_RECHECK_DELAY_MS = 96L
    private const val APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS = 16L
    private const val APPLE_LYRICS_BEFORE_FIRST_LINE_RECHECK_MAX_MS = 250L
    private const val APPLE_LYRICS_HYPER_OS_SELF_BLUR_TYPE = 0
    private val initialized = AtomicBoolean(false)
    private lateinit var runtime: AppleMusicProviderRuntime
    private val application: Application
        get() = runtime.application
    private val classLoader: ClassLoader
        get() = runtime.classLoader
    private val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    private val module: XposedModule
        get() = runtime.module
    private val hookRegistrar
        get() = runtime.hookRegistrar

    private var isPlaying = false
    @Volatile
    private var playbackPositionSource: PlaybackPositionSource? = null
    @Volatile
    private var activePlaybackPlayer: Any? = null
    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    private var progressJob: Job? = null
    private var player: RemotePlayer? = null
    private var directPlayer: AppleDirectPlayer? = null
    private var zeroPositionReadCount = 0
    private var hasLoggedNonZeroPosition = false
    private var lastTimingSamplePosition = -1L
    private var lastTimingSampleAtMs = 0L
    private var lastTimingTraceAtMs = 0L
    private var lastTimingStateSignature: String? = null
    private var lastExplicitSeekAtMs = 0L
    private var lastExplicitSeekPosition = -1L
    private lateinit var lyricRequester: LyricRequester
    private lateinit var internalCatalogResolver: AppleInternalCatalogResolver
    private val metadataSurfaceCoordinator = AppleMetadataSurfaceCoordinator(
        clock = SystemClock::elapsedRealtime,
    )
    private val visibleMetadataResolutionLeases = AppleVisibleMetadataResolutionLeases(
        clock = SystemClock::elapsedRealtime,
    )
    private val metadataSurfaceSyncLock = Any()
    private val metadataSurfaceDispatchRevision = AtomicLong(0L)
    private var lastSyncedMetadataSurfaceSignature: MetadataSurfaceSignature? = null
    private var contentUiLanguagePrefs: android.content.SharedPreferences? = null
    private var contentUiLanguageListener:
        android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val lyricDisplayTextHookedMethods = ConcurrentHashMap.newKeySet<Executable>()
    private val nativeOnlineTranslationHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    private val applePronunciationRenderHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    private val appleOfficialTranslationProbeGuard = ThreadLocalReentryGuard()
    private val applePronunciationDiagnosticsLoggedSongIds =
        ConcurrentHashMap.newKeySet<String>()
    private val applePronunciationRuntimeDiagnosticKeys =
        ConcurrentHashMap.newKeySet<String>()
    private val applePronunciationBindingDiagnosticKeys =
        ConcurrentHashMap.newKeySet<String>()
    private val appleLyricsAdapterBindingDiagnosticMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    private val applePronunciationLanguagesBySongId =
        ConcurrentHashMap<String, List<String>>()
    private val applePronunciationContextByLyricObject =
        WeakIdentityMap<Any, ApplePronunciationContext>()
    private val nativeOnlineTranslationStore = AppleNativeOnlineTranslationStore()
    private var activeOnlineSourceMenu: ActiveOnlineSourceMenu? = null
    private var deferredNativeTranslationRefreshSongId: String? = null
    private var deferredNativeTranslationRefreshRevision: Long? = null
    private var deferredNativeTranslationRefreshScheduled = false
    private val pendingOnlineSourceMenuSwitches = mutableMapOf<String, PendingOnlineSourceSwitch>()
    private val failedOnlineSourceMenuSwitches = mutableMapOf<String, FailedOnlineSourceSwitch>()
    private val confirmedOnlineSourceMenuSelections =
        mutableMapOf<String, ConfirmedOnlineSourceSelection>()
    private var onlineSourceMenuRequestSequence = 0L
    private val pendingApplePronunciationRenderPlans = Collections.synchronizedMap(
        IdentityHashMap<Any, ApplePronunciationRenderPlan>()
    )
    private val applePronunciationWordRenderContexts =
        ThreadLocalStack<ApplePronunciationWordRenderContext>()
    private val appleLyricsBindingDiagnosticContexts =
        ThreadLocalStack<AppleLyricsBindingDiagnosticContext>()
    @Volatile
    private var appleLyricsLoadMethod: Method? = null
    @Volatile
    private var appleLyricsViewModelRef: WeakReference<Any>? = null
    @Volatile
    private var appleLyricsItemRef: WeakReference<Any>? = null
    @Volatile
    private var appleLyricsPresentationMethod: Method? = null
    @Volatile
    private var appleLyricsFragmentRef: WeakReference<Any>? = null
    @Volatile
    private var appleLyricsSongPointerRef: WeakReference<Any>? = null
    @Volatile
    private var currentAppleLyricsSongId: String? = null
    private val appleLyricsBlurRuntimeStates = Collections.synchronizedMap(
        WeakHashMap<View, AppleLyricsBlurRuntimeState>()
    )
    private val appleLyricsBlurredViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    )
    private val appleLyricsRecyclerViewsByAdapter = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<View>>()
    )
    private val appleLyricsRecyclerViewClassifications = WeakIdentityMap<View, Boolean>()
    private val appleLyricsRecyclerAdapterClassNames by lazy {
        hookResolver.configuredClassNames(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER).toSet()
    }
    private val appleLyricsChildAdapterPositionMethods =
        ConcurrentHashMap<Class<*>, Method>()
    private val appleLyricsHyperOsMethods = Collections.synchronizedMap(
        WeakHashMap<Class<*>, AppleLyricsHyperOsMethods>()
    )
    private val appleSystemFontManagedTypefaces = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Typeface, Boolean>())
    )
    private val appleSystemFontOriginalTypefacesByReplacement =
        Collections.synchronizedMap(WeakHashMap<Typeface, Typeface>())
    private val appleSystemFontSignaturesByReplacement =
        Collections.synchronizedMap(WeakHashMap<Typeface, AppleSystemFontReplacementSignature>())
    private val appleSystemFontCompositeCache = ConcurrentHashMap<String, Typeface>()
    private val appleSystemFontVariationCache =
        AppleSystemFontVariationCache<Typeface, Typeface>(
            MAX_APPLE_SYSTEM_FONT_VARIATION_CACHE_ENTRIES,
        )
    private val appleSystemFontTrackedTextViews =
        Collections.synchronizedMap(WeakHashMap<TextView, AppleSystemFontTextViewState>())
    private val appleSystemFontLyricsRenderHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    private val appleSystemFontLyricsTemplateFieldPaths =
        ConcurrentHashMap<Class<*>, List<AppleSystemFontTemplateFieldPath>>()
    private val appleSystemFontLyricsMeasurementTexts = ThreadLocalStack<String>()
    private val appleSystemFontApplyGuard = ThreadLocalReentryGuard()
    private val appleSystemFontLyricsMeasureDiagnosticGuard = ThreadLocalReentryGuard()
    private val appleSystemFontLyricsMeasureDiagnosticKeys =
        ConcurrentHashMap.newKeySet<String>()
    private val appleSystemFontLyricsMeasureBaselineKeys =
        ConcurrentHashMap.newKeySet<String>()
    private val appleLyricsGradientAnimatorSample =
        ThreadLocal<AppleLyricsGradientAnimatorSample?>()
    private val appleLyricsGradientLastLogAt = ConcurrentHashMap<Int, Long>()
    private val appleSystemFontDebugTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val appleSystemFontScaleLock = Any()
    @Volatile
    private var appleSystemFontScaleCache = 50
    @Volatile
    private var appleSystemFontScaleLastReadUptimeMillis = -1L
    @Volatile
    private var hyperOsFontSettingsLastSyncedScale = -1
    private val appleSystemFontVariationMethods: AppleSystemFontVariationMethods? by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        resolveAppleSystemFontVariationMethods()
    }
    private val hyperOsFontWeightMethods: HyperOsFontWeightMethods? by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        resolveHyperOsFontWeightMethods()
    }
    @Volatile
    private var lastLoggedContentLanguage: String? = null
    private val playbackMetadataOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val originalMetadataOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val confirmedOriginalMetadataIds = ConcurrentHashMap.newKeySet<String>()
    private val playbackArtistOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val originalArtistOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val sharedLocalizedArtistOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val sharedOriginalArtistOverrides =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
    private val originalArtistResolvedIds = ConcurrentHashMap.newKeySet<String>()
    private val playbackMetadataAccountValues =
        ConcurrentHashMap<String, AccountMetadata>()
    private val playbackMetadataLookupIds =
        ConcurrentHashMap<String, Set<String>>()
    private val playbackMetadataEntityTypes =
        ConcurrentHashMap<String, AppleInternalCatalogResolver.LocalizedEntityType>()
    private val playbackMetadataArtistKeys = ConcurrentHashMap<String, Set<String>>()
    private val playbackMetadataAssociatedArtistIds =
        ConcurrentHashMap<String, List<String>>()
    private val associatedMediaIdsByArtistKey =
        ConcurrentHashMap<String, MutableSet<String>>()
    private val nonCatalogContentItemIds = ConcurrentHashMap.newKeySet<String>()
    private val playbackMetadataHookedMethods = ConcurrentHashMap.newKeySet<Executable>()
    private val metadataPageControllerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val metadataPageLifecycleHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    private val inAppMetadataRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<InAppMetadataRef>>()
    private val inAppMetadataIds =
        Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val inAppPlaybackItemRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<InAppPlaybackItemRef>>()
    private val inAppPlaybackItemIds = WeakIdentityMap<Any, String>()
    private val inAppPlaybackItemContracts =
        WeakIdentityMap<Any, InAppPlaybackItemContract>()
    private val contentItemMediaIds = WeakIdentityMap<Any, String>()
    private val inAppLibraryEntityRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<InAppLibraryEntityRef>>()
    private val inAppLibraryEntityIds = WeakIdentityMap<Any, String>()
    private val inAppLibraryEntityAttributes = WeakIdentityMap<Any, Any>()
    private val inAppLibraryEntityEnrichedIds = WeakIdentityMap<Any, String>()
    private val inAppMediaApiAttributeBindings =
        WeakIdentityMap<Any, InAppMediaApiAttributeBinding>()
    private val inAppMediaApiAttributeHookedMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    private val inAppLibraryControllerRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    private val inAppLibraryControllerRefreshStates =
        Collections.synchronizedMap(
            WeakHashMap<Any, InAppLibraryControllerRefreshState>()
        )
    private val inAppLibraryControllerAppliedAliases =
        Collections.synchronizedMap(
            WeakHashMap<Any, MutableMap<String, AppliedMetadataAlias>>()
        )
    private val collectionPageBoundResolutionStates =
        Collections.synchronizedMap(
            WeakHashMap<Any, CollectionPageBoundResolutionState>()
        )
    private val inAppPlaylistRowRootMediaIds = WeakIdentityMap<View, String>()
    private val inAppPlaylistRowRefs =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, ConcurrentLinkedQueue<InAppPlaylistRowRef>>(
                MAX_GENERIC_RECYCLER_MEDIA_IDS,
                0.75f,
                true,
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<
                        String,
                        ConcurrentLinkedQueue<InAppPlaylistRowRef>,
                        >?,
                ): Boolean = size > MAX_GENERIC_RECYCLER_MEDIA_IDS
            }
        )
    @Volatile
    private var playlistExplicitTitleFormatterClass: Class<*>? = null
    private val albumPageBuildData =
        Collections.synchronizedMap(WeakHashMap<Any, AlbumPageBuildData>())
    private val artistPageBuildData =
        Collections.synchronizedMap(WeakHashMap<Any, ArtistPageBuildData>())
    private val activeAlbumHeaderBuildCaptures =
        ThreadLocalStack<AlbumHeaderBuildCapture>()
    private val inAppAlbumHeaderModelIds = WeakIdentityMap<Any, String>()
    private val albumHeaderFinalBoundResolutionIds = WeakIdentityMap<Any, String>()
    private val deferredMetadataResolutions =
        linkedMapOf<String, DeferredMetadataResolution>()
    private var deferredMetadataResolutionScheduled = false
    @Volatile
    private var activeMetadataPageOwner = WeakReference<Any>(null)
    private val inAppArtistTopSongModelIds = WeakIdentityMap<Any, String>()
    private val inAppArtistTopSongModels =
        WeakIdentityMap<Any, ArtistTopSongModelSnapshot>()
    private val inAppArtistTopSongBindings =
        WeakIdentityMap<Any, ArtistTopSongModelSnapshot>()
    private val artistProfileMediaIds = WeakIdentityMap<Any, String>()
    private val inAppArtistHeaderModelIds = WeakIdentityMap<Any, String>()
    private val inAppArtistHeaderBindingIds = WeakIdentityMap<Any, String>()
    private val artistProfileFinalBoundResolutionIds = WeakIdentityMap<Any, String>()
    private val artistProfileTopSongCandidateArtistIds =
        ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile
    private var latestArtistProfileMediaId: String? = null
    private val activeInAppLibraryComposeCapture =
        ThreadLocal<InAppLibraryComposeCapture?>()
    private val internalContentItemGetterGuard = ThreadLocalReentryGuard()
    private val inAppLibraryComposeStates =
        Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
    private val inAppLibraryComposeStateRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    private val inAppLibraryComposeRefreshPending =
        Collections.synchronizedMap(
            WeakHashMap<Any, MutableMap<String, AppliedMetadataAlias?>>()
        )
    private val inAppLibraryComposeVisibleResolutionPending =
        Collections.synchronizedMap(WeakHashMap<Any, MutableSet<String>>())
    private val inAppLibraryComposeAppliedAliases =
        Collections.synchronizedMap(
            WeakHashMap<Any, MutableMap<String, AppliedMetadataAlias>>()
        )
    private val inAppArtworkContinuityCache =
        Collections.synchronizedMap(
            object : LinkedHashMap<
                InAppArtworkContinuityKey,
                InAppArtworkContinuityEntry,
                >(MAX_IN_APP_ARTWORK_CONTINUITY_ENTRIES, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<
                        InAppArtworkContinuityKey,
                        InAppArtworkContinuityEntry,
                        >?,
                ): Boolean = size > MAX_IN_APP_ARTWORK_CONTINUITY_ENTRIES
            }
        )
    private val inAppListenNowArtworkContinuityCache =
        Collections.synchronizedMap(
            object : LinkedHashMap<
                InAppListenNowArtworkContinuityKey,
                InAppArtworkContinuityEntry,
                >(MAX_IN_APP_ARTWORK_CONTINUITY_ENTRIES, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<
                        InAppListenNowArtworkContinuityKey,
                        InAppArtworkContinuityEntry,
                        >?,
                ): Boolean = size > MAX_IN_APP_ARTWORK_CONTINUITY_ENTRIES
            }
        )
    private val inAppListenNowArtworkKeysByLiveData =
        WeakIdentityMap<Any, InAppListenNowArtworkContinuityKey>()
    private val inAppListenNowSeededArtwork =
        WeakIdentityMap<Any, InAppListenNowSeededArtwork>()
    @Volatile
    private var inAppListenNowArtworkContinuityHookInstalled = false
    private val debugLibraryArtworkComposeCaptures =
        ThreadLocalStack<DebugLibraryArtworkComposeCapture>()
    private val debugLibraryArtworkPainters =
        WeakIdentityMap<Any, DebugLibraryArtworkPainterTrace>()
    private val debugLibraryArtworkPainterStates =
        WeakIdentityMap<Any, DebugLibraryArtworkPainterState>()
    private val debugLibraryArtworkLatestPainters =
        ConcurrentHashMap<String, WeakReference<Any>>()
    private val debugListenNowArtworkLiveData =
        WeakIdentityMap<Any, DebugListenNowArtworkTrace>()
    private val debugListenNowArtworkDelegates =
        WeakIdentityMap<Any, DebugListenNowArtworkTrace>()
    private val debugListenNowArtworkImageViews =
        WeakIdentityMap<Any, DebugListenNowArtworkTrace>()
    private val debugListenNowLatestArtworkTraces =
        ConcurrentHashMap<String, DebugListenNowArtworkTrace>()
    @Volatile
    private var inAppLibraryComposeNeverEqualPolicy: Any? = null
    private val inAppMediaApiSongIds =
        Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val inAppContainerItemRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<InAppContainerItemRef>>()
    private val inAppContainerItemIds = WeakIdentityMap<Any, String>()
    private val inAppContainerNavigationRefs =
        ConcurrentLinkedQueue<InAppContainerNavigationRef>()
    private val inAppActionSheetBindingRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    private val inAppActionSheetBindings =
        WeakIdentityMap<Any, InAppActionSheetBinding>()
    private val inAppMetadataResolveRequests = ConcurrentHashMap.newKeySet<String>()
    private val inAppMetadataResolveMisses = ConcurrentHashMap.newKeySet<String>()
    private val originalMetadataResolveRequests = ConcurrentHashMap.newKeySet<String>()
    private val associatedArtistResolveRequests = ConcurrentHashMap.newKeySet<String>()
    private val originalMetadataResolvedIds = ConcurrentHashMap.newKeySet<String>()
    private val originalMetadataPendingIds = ConcurrentHashMap.newKeySet<String>()
    private val originalMetadataCacheMissUptimeMillis = ConcurrentHashMap<String, Long>()
    private val originalLanguageByArtistKey = ConcurrentHashMap<String, String>()
    private val inAppDataBindingRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    /** Listen Now cards keep title/subtitle in an already-bound DataBinding model. */
    private val inAppListenNowDataBindingRefs =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<WeakReference<Any>>>()
    private val inAppListenNowDataBindingMediaIds =
        Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val inAppListenNowDataBindingPendingRefreshes =
        Collections.synchronizedMap(WeakHashMap<Any, PendingDataBindingRefresh>())
    private val inAppListenNowModelBuildStates =
        WeakIdentityMap<Any, InAppListenNowModelBuildState>()
    private val inAppListenNowModelBuildStatesByLiveData =
        WeakIdentityMap<Any, InAppListenNowModelBuildState>()
    private val inAppDataBindingInstances = ConcurrentLinkedQueue<WeakReference<Any>>()
    private val inAppDataBindingMediaIds =
        Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val inAppDataBindingRootViews =
        Collections.synchronizedMap(WeakHashMap<Any, WeakReference<View>>())
    private val inAppDataBindingsByRoot =
        WeakIdentityMap<View, WeakReference<Any>>()
    private val activeRecyclerBindCaptures = ThreadLocalStack<RecyclerBindCapture>()
    private val inAppRecyclerRootMediaIds = WeakIdentityMap<View, Set<String>>()
    private val inAppRecyclerRootVisibleResolutionIds = WeakIdentityMap<View, Set<String>>()
    private val inAppGenericRecyclerItemRefs =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, ConcurrentLinkedQueue<InAppRecyclerItemRef>>(
                MAX_GENERIC_RECYCLER_MEDIA_IDS,
                0.75f,
                true,
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<
                        String,
                        ConcurrentLinkedQueue<InAppRecyclerItemRef>,
                        >?,
                ): Boolean = size > MAX_GENERIC_RECYCLER_MEDIA_IDS
            }
        )
    private val inAppDataBindingAppliedAliases =
        Collections.synchronizedMap(WeakHashMap<Any, AppliedMetadataAlias>())
    private val inAppDataBindingPendingRefreshes =
        Collections.synchronizedMap(WeakHashMap<Any, PendingDataBindingRefresh>())
    private val inAppDataBindingBindGenerations =
        Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val inAppDataBindingVisibleResolutionPosts =
        Collections.synchronizedMap(
            WeakHashMap<Any, PendingVisibleDataBindingResolution>()
        )
    private val inAppMetadataCallbackAppliedAliases =
        Collections.synchronizedMap(WeakHashMap<Any, AppliedMetadataAlias>())
    private val inAppDataBindingContentFields =
        ConcurrentHashMap<Class<*>, List<Field>>()
    private val debugVisibleViewTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val debugQueueBindTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val debugForegroundActivities =
        Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())
    private val debugLibraryModelRefreshMediaId = ThreadLocal<String?>()
    @Volatile
    private var debugRecyclerViewClass: Class<*>? = null
    private val contentRequestTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val contentRequestDecisionTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val contentRequestHeaderTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val mediaApiLocalizationTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val contentHttpTimingTracker by lazy {
        AppleContentHttpTimingTracker(clock = SystemClock::elapsedRealtime)
    }
    @Volatile
    private var dataBindingInvalidateAllMethod: Method? = null
    @Volatile
    private var dataBindingExecutePendingBindingsMethod: Method? = null
    @Volatile
    private var dataBindingSetVariableMethod: Method? = null
    @Volatile
    private var dataBindingTitleVariableId: Int? = null
    @Volatile
    private var dataBindingSubtitleVariableId: Int? = null
    @Volatile
    private var dataBindingBaseClass: Class<*>? = null
    private val mainHandler: Handler
        get() = runtime.mainHandler
    @Volatile
    private var currentPlaybackMetadataRefresh: PlaybackMetadataRefresh? = null
    @Volatile
    private var currentPlaybackMetadataId: String? = null
    @Volatile
    private var currentPlaybackMetadataOverride: AppleInternalCatalogResolver.Alias? = null
    @Volatile
    private var currentFrameworkMediaSessionRefresh: FrameworkMediaSessionRefresh? = null
    @Volatile
    private var currentFrameworkMediaQueueRefresh: FrameworkMediaQueueRefresh? = null
    private val frameworkMediaQueueRefreshInProgress = AtomicBoolean(false)
    @Volatile
    private var currentInAppMetadataRefresh: InAppNowPlayingRefresh? = null
    @Volatile
    private var currentInAppMetadataDispatcherRefresh: InAppMetadataDispatcherRefresh? = null
    @Volatile
    private var queueInAppMetadataRefresh: InAppQueueRefresh? = null
    @Volatile
    private var historyInAppMetadataRefresh: InAppQueueRefresh? = null
    private val inAppQueueAdapterRefs =
        ConcurrentLinkedQueue<WeakReference<RecyclerView.Adapter<*>>>()
    private val pendingLyricsRequestSources =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val metadataTraceSequence = AtomicLong(0L)

    @Synchronized
    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (::runtime.isInitialized) {
            ProviderLogger.info("Apple Music 内置歌词提供器生命周期 Hook 已存在")
            return
        }
        runtime = AppleMusicProviderRuntime(module, classLoader)
        val onCreate = Application::class.java.getDeclaredMethod("onCreate")
        hookRegistrar.withModule("provider-lifecycle") {
            hookRegistrar.install(onCreate, after = { chain, _ ->
                (chain.thisObject as? Application)?.let(::onAppCreate)
            })
        }
        ProviderLogger.info("Apple Music 内置歌词提供器生命周期 Hook 已安装")
    }

    private fun onAppCreate(app: Application) {
        if (!initialized.compareAndSet(false, true)) return
        val appleMusicVersion = runCatching {
            val packageInfo = app.packageManager.getPackageInfo(APPLE_MUSIC_PACKAGE, 0)
            AppleMusicVersion(
                versionName = packageInfo.versionName,
                versionCode = packageInfo.longVersionCode,
            )
        }.getOrElse {
            AppleMusicVersion(versionName = null, versionCode = null)
        }
        val hookResolver = AppleMusicHookResolver(appleMusicVersion, app.classLoader)
        runtime.attach(app, hookResolver)
        ProviderLogger.info(
            "Apple Music Hook 版本档案已加载: app=${appleMusicVersion.displayName}, " +
                "profile=${hookResolver.profile?.id ?: "compatibility-fallback"}"
        )

        runCatching {
            PreferencesMonitor.initialize(application)
            PreferencesMonitor.listener = object : PreferencesMonitor.Listener {
                override fun onTranslationSelectedChanged(selected: Boolean) {
                    player?.setDisplayTranslation(selected)
                    refreshAppleLyricsSupplementPresentation()
                }

                override fun onPronunciationSelectedChanged(selected: Boolean) {
                    refreshAppleLyricsSupplementPresentation()
                }
            }
            DiskSongManager.initialize(application)
            internalCatalogResolver = AppleInternalCatalogResolver(
                context = application,
                classLoader = classLoader,
                mainHandler = Handler(Looper.getMainLooper())
            )
            initializeContentUiLanguage()
            initScreenStateMonitor()
            initProvider()
            startHooks()
            ProviderLogger.info("Apple Music 内置歌词提供器初始化完成")
        }.onFailure {
            initialized.set(false)
            ProviderLogger.error("Apple Music 内置歌词提供器初始化失败", it)
        }
    }

    private fun initializeContentUiLanguage() {
        val prefs = runCatching {
            module.getRemotePreferences(UIConstants.PREF_NAME)
        }.getOrNull() ?: return
        contentUiLanguagePrefs = prefs
        AppleLyricTextTransform.initialize(application) {
            isSimplifyTraditionalLyricsEnabled()
        }
        internalCatalogResolver.setPersistentLocalizedCacheEnabled(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
            )
        )
        applyConfiguredContentUiLanguage(prefs)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            when (key) {
                RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE -> {
                    applyConfiguredContentUiLanguage(changed)
                    clearPlaybackMetadataOverridesAndRefresh()
                }
                RootConstants.KEY_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE ->
                    clearPlaybackMetadataOverridesAndRefresh()
                RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA ->
                    clearPlaybackMetadataOverridesAndRefresh()
                RootConstants.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE ->
                    applyConfiguredContentUiLanguage(changed)
                RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS ->
                    refreshAppleLyricsDisplay()
                RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN -> {
                    clearPendingApplePronunciationRenderPlans()
                    refreshAppleLyricsSupplementPresentation()
                }
                RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
                RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
                RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX ->
                    refreshAppleLyricsBlurEffect()
                RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT ->
                    refreshAppleSystemFontWeight()
                RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
                RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION -> {
                    if (!isNativeOnlineTranslationEnabled()) {
                        nativeOnlineTranslationStore.clear()
                        refreshAppleLyricsSupplementPresentation()
                    }
                }
                RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS -> {
                    if (ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.OFF) {
                        if (isPlaying && isAodLyricsEnabled()) resumeCoroutineTask()
                        else pauseCoroutineTask()
                    }
                }
            }
        }
        contentUiLanguageListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun applyConfiguredContentUiLanguage(
        prefs: android.content.SharedPreferences? = contentUiLanguagePrefs
    ) {
        prefs ?: return
        val selection = prefs.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
        )
        internalCatalogResolver.applyContentUiLanguage(selection)
        internalCatalogResolver.setPersistentLocalizedCacheEnabled(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
                RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
            )
        )
    }

    private fun clearPlaybackMetadataOverridesAndRefresh() {
        playbackMetadataOverrides.clear()
        originalMetadataOverrides.clear()
        confirmedOriginalMetadataIds.clear()
        playbackArtistOverrides.clear()
        originalArtistOverrides.clear()
        sharedLocalizedArtistOverrides.clear()
        sharedOriginalArtistOverrides.clear()
        originalArtistResolvedIds.clear()
        currentPlaybackMetadataOverride = null
        inAppLibraryControllerRefreshStates.clear()
        inAppLibraryControllerAppliedAliases.clear()
        inAppLibraryComposeAppliedAliases.clear()
        inAppDataBindingAppliedAliases.clear()
        inAppDataBindingPendingRefreshes.clear()
        inAppDataBindingBindGenerations.clear()
        inAppListenNowDataBindingRefs.clear()
        inAppListenNowDataBindingMediaIds.clear()
        inAppListenNowDataBindingPendingRefreshes.clear()
        inAppListenNowModelBuildStates.clear()
        inAppListenNowModelBuildStatesByLiveData.clear()
        inAppMetadataCallbackAppliedAliases.clear()
        inAppMetadataResolveRequests.clear()
        inAppMetadataResolveMisses.clear()
        originalMetadataResolveRequests.clear()
        associatedArtistResolveRequests.clear()
        originalMetadataResolvedIds.clear()
        originalMetadataPendingIds.clear()
        originalMetadataCacheMissUptimeMillis.clear()
        originalLanguageByArtistKey.clear()
        synchronized(deferredMetadataResolutions) {
            deferredMetadataResolutions.clear()
        }
        inAppRecyclerRootVisibleResolutionIds.clear()
        restoreInAppMetadata()
        mainHandler.post {
            restoreFrameworkMediaSessionMetadata()
            restoreFrameworkMediaSessionQueue()
            refreshInAppMetadataViews()
            activePlaybackPlayer?.let { mediaPlayer ->
                refreshCurrentQueueItem(mediaPlayer, "Apple Music 语言覆盖设置变更")
            }
        }
    }

    private fun initProvider() {
        val directPlayer = AppleDirectPlayer(
            context = application,
            onOriginalMetadataRequested = ::resolveOriginalMetadataOnDemand,
            onOnlineTranslationReceived = ::receiveNativeOnlineTranslation,
            onOnlineTranslationCleared = ::clearNativeOnlineTranslation,
            onOnlineTranslationSourceSwitchResult = ::receiveOnlineSourceSwitchResult,
        ).also { it.start() }
        this.directPlayer = directPlayer
        val helper = runCatching {
            LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = APPLE_MUSIC_PACKAGE,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            ).also { it.register() }
        }.onFailure {
            ProviderLogger.error("Lyricon Central 提供器注册失败，使用内置直连", it)
        }.getOrNull()
        val activePlayer = helper?.player?.let { CompositeRemotePlayer(it, directPlayer) }
            ?: directPlayer
        lyricRequester = LyricRequester(classLoader, application)
        PlaybackManager.init(activePlayer, lyricRequester)
        activePlayer.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
        player = activePlayer
    }

    private fun startHooks() {
        hookModules().asSequence()
            .filter { hookModule -> !hookModule.debugOnly || BuildConfig.DEBUG }
            .forEach { hookModule ->
                hookRegistrar.withModule(hookModule.id, hookModule::installHooks)
            }
    }

    internal fun hookModuleIdsForBuild(debug: Boolean): List<String> =
        hookModules()
            .filter { hookModule -> !hookModule.debugOnly || debug }
            .map { hookModule -> hookModule.id }

    private fun hookModules() = listOf(
        FunctionalAppleMusicHookModule("hookMetadataSurfaceLifecycle", installer = ::hookMetadataSurfaceLifecycle),
        FunctionalAppleMusicHookModule("hookTranslationPreference", installer = ::hookTranslationPreference),
        FunctionalAppleMusicHookModule("hookMediaApiLocalization", installer = ::hookMediaApiLocalization),
        FunctionalAppleMusicHookModule("hookContentHttpLocalization", installer = ::hookContentHttpLocalization),
        FunctionalAppleMusicHookModule("hookExoMediaPlayer", installer = ::hookExoMediaPlayer),
        FunctionalAppleMusicHookModule("hookMediaMetadataChange", installer = ::hookMediaMetadataChange),
        FunctionalAppleMusicHookModule("hookContentItemMetadata", installer = ::hookContentItemMetadata),
        FunctionalAppleMusicHookModule("hookInAppLibraryEntities", installer = ::hookInAppLibraryEntities),
        FunctionalAppleMusicHookModule("hookCollectionPageMetadataRefresh", installer = ::hookCollectionPageMetadataRefresh),
        FunctionalAppleMusicHookModule("hookArtistProfileTopSongs", installer = ::hookArtistProfileTopSongs),
        FunctionalAppleMusicHookModule("hookArtistProfileMetadata", installer = ::hookArtistProfileMetadata),
        FunctionalAppleMusicHookModule("hookRecentlySearchedMetadata", installer = ::hookRecentlySearchedMetadata),
        FunctionalAppleMusicHookModule("hookInAppArtworkContinuity", installer = ::hookInAppArtworkContinuity),
        FunctionalAppleMusicHookModule("hookInAppListenNowArtworkContinuity", installer = ::hookInAppListenNowArtworkContinuity),
        FunctionalAppleMusicHookModule("hookInAppLibraryEpoxyRefresh", installer = ::hookInAppLibraryEpoxyRefresh),
        FunctionalAppleMusicHookModule("hookInAppLibraryComposeRefresh", installer = ::hookInAppLibraryComposeRefresh),
        FunctionalAppleMusicHookModule("hookDebugListenNowArtworkLifecycle", debugOnly = true, installer = ::hookDebugListenNowArtworkLifecycle),
        FunctionalAppleMusicHookModule("hookVisibleMetadataDiagnostics", debugOnly = true, installer = ::hookVisibleMetadataDiagnostics),
        FunctionalAppleMusicHookModule("hookInAppDataBindingRefresh", installer = ::hookInAppDataBindingRefresh),
        FunctionalAppleMusicHookModule("hookInAppListenNowMetadataBinding", installer = ::hookInAppListenNowMetadataBinding),
        FunctionalAppleMusicHookModule("hookRecyclerViewCentralBinding", installer = ::hookRecyclerViewCentralBinding),
        FunctionalAppleMusicHookModule("hookInAppMetadata", installer = ::hookInAppMetadata),
        FunctionalAppleMusicHookModule("hookInAppPlaybackItemConversion", installer = ::hookInAppPlaybackItemConversion),
        FunctionalAppleMusicHookModule("hookInAppActionSheetMetadata", installer = ::hookInAppActionSheetMetadata),
        FunctionalAppleMusicHookModule("hookMediaSessionMetadata", installer = ::hookMediaSessionMetadata),
        FunctionalAppleMusicHookModule("hookMediaSessionQueue", installer = ::hookMediaSessionQueue),
        FunctionalAppleMusicHookModule("hookPlaybackNotificationMetadata", installer = ::hookPlaybackNotificationMetadata),
        FunctionalAppleMusicHookModule("hookAppleOfficialPronunciationLanguageMatching", installer = ::hookAppleOfficialPronunciationLanguageMatching),
        FunctionalAppleMusicHookModule("hookAppleLyricsPreferredLanguages", installer = ::hookAppleLyricsPreferredLanguages),
        FunctionalAppleMusicHookModule("hookApplePronunciationWordRendering", installer = ::hookApplePronunciationWordRendering),
        FunctionalAppleMusicHookModule("hookLyricBuildMethod", installer = ::hookLyricBuildMethod),
        FunctionalAppleMusicHookModule("hookAppleNativeLyricsPresentation", installer = ::hookAppleNativeLyricsPresentation),
        FunctionalAppleMusicHookModule("hookAppleSystemFontWeight", installer = ::hookAppleSystemFontWeight),
        FunctionalAppleMusicHookModule("hookAppleLyricsBlurEffect", installer = ::hookAppleLyricsBlurEffect),
        FunctionalAppleMusicHookModule("hookAppleLyricsUiDiagnostics", debugOnly = true, installer = ::hookAppleLyricsUiDiagnostics),
        FunctionalAppleMusicHookModule("hookAppleLyricsBindingDiagnostics", debugOnly = true, installer = ::hookAppleLyricsBindingDiagnostics),
        FunctionalAppleMusicHookModule("hookAppleLyricsSourceMenu", installer = ::hookAppleLyricsSourceMenu),
        FunctionalAppleMusicHookModule("hookLyricsNetworkRequest", debugOnly = true, installer = ::hookLyricsNetworkRequest),
        FunctionalAppleMusicHookModule("hookLyricsCookies", debugOnly = true, installer = ::hookLyricsCookies),
        FunctionalAppleMusicHookModule("hookFinalLyricsHttp", debugOnly = true, installer = ::hookFinalLyricsHttp),
    )

    private fun hookMetadataSurfaceLifecycle() {
        val activityInstalled = runCatching {
            val activityResume = Activity::class.java.getDeclaredMethod("onResume")
                .apply { isAccessible = true }
            val activityPause = Activity::class.java.getDeclaredMethod("onPause")
                .apply { isAccessible = true }
            hookRegistrar.install(activityResume, after = { chain, _ ->
                chain.thisObject?.let(::onMetadataSurfaceResumed)
            })
            hookRegistrar.install(activityPause, before = { chain ->
                chain.thisObject?.let(::onMetadataSurfacePaused)
            })
        }.onFailure {
            ProviderLogger.error("Apple Music Activity 元数据生命周期 Hook 安装失败", it)
        }.isSuccess
        val fragmentInstalled = runCatching {
            val fragmentClass = classLoader.loadClass("androidx.fragment.app.Fragment")
            val fragmentResume = AppleReflection.findMethod(
                fragmentClass,
                "onResume",
                parameterCount = 0,
            )
            val fragmentPause = AppleReflection.findMethod(
                fragmentClass,
                "onPause",
                parameterCount = 0,
            )
            hookRegistrar.install(fragmentResume, after = { chain, _ ->
                chain.thisObject?.let(::onMetadataSurfaceResumed)
            })
            hookRegistrar.install(fragmentPause, before = { chain ->
                chain.thisObject?.let(::onMetadataSurfacePaused)
            })
        }.onFailure {
            ProviderLogger.info(
                "Apple Music 未提供标准 Fragment 生命周期类，改用页面控制器边界"
            )
        }.isSuccess
        ProviderLogger.info(
            "Apple Music 元数据页面生命周期 Hook 已安装: " +
                "activity=$activityInstalled, fragment=$fragmentInstalled"
        )
    }

    private fun onMetadataSurfaceResumed(owner: Any) {
        syncMetadataRequestScope(metadataSurfaceCoordinator.onSurfaceResumed(owner))
    }

    private fun onMetadataSurfacePaused(owner: Any) {
        syncMetadataRequestScope(metadataSurfaceCoordinator.onSurfacePaused(owner))
    }

    private fun onMetadataPageAttached(owner: Any, recycler: RecyclerView) {
        activeMetadataPageOwner = WeakReference(owner)
        onMetadataSurfaceResumed(owner)
        if (BuildConfig.DEBUG) {
            logMetadataIdentity(
                event = "metadata_page_controller_attached",
                details = "controller=${owner.javaClass.name}@${System.identityHashCode(owner)}, " +
                    "recycler=${debugViewDescription(recycler)}",
            )
        }
    }

    private fun onMetadataPageDetached(owner: Any) {
        val removedControllerRefs = removeInAppLibraryControllerRefs(owner)
        inAppLibraryControllerRefreshStates.remove(owner)
        inAppLibraryControllerAppliedAliases.remove(owner)
        collectionPageBoundResolutionStates.remove(owner)
        albumPageBuildData.remove(owner)
        artistPageBuildData.remove(owner)
        val detachedArtistMediaId = artistProfileMediaIds[owner]
        artistProfileMediaIds.remove(owner)
        if (latestArtistProfileMediaId == detachedArtistMediaId) {
            latestArtistProfileMediaId = null
        }
        if (BuildConfig.DEBUG) {
            logMetadataIdentity(
                event = "metadata_page_controller_detached",
                details = "controller=${owner.javaClass.name}@${System.identityHashCode(owner)}, " +
                    "removedControllerRefs=$removedControllerRefs",
            )
        }
        if (activeMetadataPageOwner.get() === owner) {
            activeMetadataPageOwner = WeakReference(null)
        }
        onMetadataSurfacePaused(owner)
    }

    private fun removeInAppLibraryControllerRefs(controller: Any): Int {
        var removed = 0
        inAppLibraryControllerRefs.forEach { (mediaId, refs) ->
            refs.forEach { ref ->
                val target = ref.get()
                if (target == null || target === controller) {
                    if (refs.remove(ref) && target === controller) removed += 1
                }
            }
            if (refs.isEmpty()) inAppLibraryControllerRefs.remove(mediaId, refs)
        }
        return removed
    }

    private fun markMetadataVisible(
        mediaIds: Collection<String>,
    ): AppleMetadataSurfaceCoordinator.SurfaceSnapshot {
        visibleMetadataResolutionLeases.mark(mediaIds)
        metadataSurfaceCoordinator.markCurrentPage(mediaIds)
        val snapshot = metadataSurfaceCoordinator.markVisible(mediaIds)
        syncMetadataRequestScope(snapshot)
        return snapshot
    }

    private fun setMetadataPlaybackMediaId(
        mediaId: String?,
    ): AppleMetadataSurfaceCoordinator.SurfaceSnapshot {
        val snapshot = metadataSurfaceCoordinator.setPlaybackMediaId(mediaId)
        syncMetadataRequestScope(snapshot)
        return snapshot
    }

    private fun syncMetadataRequestScope(
        snapshot: AppleMetadataSurfaceCoordinator.SurfaceSnapshot =
            metadataSurfaceCoordinator.snapshot(),
    ) {
        if (!::internalCatalogResolver.isInitialized) return
        val visible = expandMetadataScopeIds(snapshot.visibleMediaIds)
        val activePage = expandMetadataScopeIds(snapshot.activePageMediaIds) - visible
        val signature = MetadataSurfaceSignature(
            coordinatorRevision = snapshot.scopeRevision,
            visibleMediaIds = visible,
            activePageMediaIds = activePage,
        )
        val dispatchRevision = synchronized(metadataSurfaceSyncLock) {
            if (signature == lastSyncedMetadataSurfaceSignature) return
            lastSyncedMetadataSurfaceSignature = signature
            metadataSurfaceDispatchRevision.incrementAndGet()
        }
        internalCatalogResolver.updateRequestScope(
            revision = dispatchRevision,
            visibleMediaIds = visible,
            activePageMediaIds = activePage,
        )
    }

    private fun expandMetadataScopeIds(mediaIds: Collection<String>): Set<String> = buildSet {
        mediaIds.forEach { mediaId ->
            add(mediaId)
            addAll(playbackMetadataAssociatedArtistIds[mediaId].orEmpty())
        }
    }

    private fun metadataRequestContext(
        mediaId: String,
    ): AppleMetadataSurfaceCoordinator.RequestContext =
        metadataSurfaceCoordinator.requestContext(mediaId)

    private fun isCurrentMetadataSurfaceMediaId(mediaId: String): Boolean =
        metadataRequestContext(mediaId).priority !=
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND

    private fun hasVisibleInAppConsumer(mediaId: String): Boolean {
        val hasVisibleDataBinding = inAppDataBindingRefs[mediaId]?.any { ref ->
            val binding = ref.get() ?: return@any false
            inAppDataBindingMediaIds[binding] == mediaId &&
                inAppDataBindingRootViews[binding]?.get()
                    ?.let(::isVisibleBindingRoot) == true
        } == true
        if (hasVisibleDataBinding) return true
        return inAppGenericRecyclerItemRefs[mediaId]?.any { ref ->
            val root = ref.root.get() ?: return@any false
            ref.adapter.get() != null &&
                boundRecyclerRootContainsMediaId(root, mediaId) &&
                isVisibleBindingRoot(root)
        } == true
    }

    private fun isRefreshableInAppMediaId(mediaId: String): Boolean =
        shouldRefreshInAppSurface(
            surfaceRelevant = isCurrentMetadataSurfaceMediaId(mediaId),
            hasVisibleExactConsumer = hasVisibleInAppConsumer(mediaId),
            hasActiveVisibleLease = visibleMetadataResolutionLeases.contains(mediaId),
        )

    /** Apple Music overwrites every MediaApi query's `l` parameter from the system locale. */
    private fun hookMediaApiLocalization() {
        runCatching {
            val resolved = hookResolver.resolveMethod(
                AppleMusicHookPoint.MEDIA_API_LOCALIZATION
            )
            val method = resolved.method
            hookRegistrar.install(method, after = { _, result ->
                @Suppress("UNCHECKED_CAST")
                val params = result as? MutableMap<Any?, Any?> ?: return@installHook
                val prefs = contentUiLanguagePrefs ?: return@installHook
                val selection = prefs.getInt(
                    RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
                    RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
                )
                internalCatalogResolver.applyContentUiLanguage(selection)
                val requestToken = params[
                    AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
                ]?.toString()
                val requestLocalization =
                    internalCatalogResolver.catalogRequestLocalization(requestToken)
                val language = requestLocalization?.language
                    ?: internalCatalogResolver.languageTagForCurrentRequest(selection)
                language?.let {
                    params["l"] = language
                    if (lastLoggedContentLanguage != language) {
                        lastLoggedContentLanguage = language
                        ProviderLogger.info(
                            "Apple Music 内容本地化参数已覆盖: language=$language"
                        )
                    }
                }
                if (
                    BuildConfig.DEBUG &&
                    requestToken != null &&
                    mediaApiLocalizationTraceKeys.add(requestToken)
                ) {
                    ProviderLogger.diagnostic(
                        "AppleCatalogLocalizationParams: token=$requestToken, " +
                            "resolved=${requestLocalization != null}, " +
                            "storefront=${requestLocalization?.storefront ?: "fallback"}, " +
                            "language=${requestLocalization?.language ?: language ?: "unset"}"
                    )
                }
            })
            ProviderLogger.info(
                "Apple Music 内容本地化参数 Hook 已安装: " +
                    "${resolved.target.className}#${method.name}, " +
                    "fallback=${resolved.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 内容本地化参数 Hook 安装失败", it)
        }
    }

    private fun hookContentHttpLocalization() {
        runCatching {
            val method = AppleReflection.findMethod(
                classLoader.loadClass("u8.a"),
                "a",
                parameterCount = 1
            )
            hookRegistrar.install(
                method,
                before = { chain ->
                    val prefs = contentUiLanguagePrefs ?: return@installHook
                    val selection = prefs.getInt(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
                    )
                    val httpChain = chain.args.firstOrNull() ?: return@installHook
                    val request = AppleReflection.field(httpChain, "e") ?: return@installHook
                    val requestUrl = AppleReflection.field(request, "a")?.toString().orEmpty()
                    val requestUri = Uri.parse(requestUrl)
                    startContentHttpTiming(httpChain, requestUri)
                    val pathSegments = requestUri.pathSegments
                    val isLyricsRequest = isAppleLyricsRequestPath(pathSegments)
                    if (
                        AppleInternalCatalogResolver.isAccountScopedPlaybackPath(pathSegments) ||
                        isLyricsRequest
                    ) {
                        val accountStorefront =
                            internalCatalogResolver.accountStorefrontForPlaybackRequest()
                                ?: return@installHook
                        val rewritten = rewriteContentRequestStorefrontOnly(
                            request = request,
                            storefront = accountStorefront,
                        )
                        rewritten?.let { AppleReflection.setField(httpChain, "e", it) }
                        if (BuildConfig.DEBUG && isLyricsRequest) {
                            val sourceStorefront =
                                AppleInternalCatalogResolver.storefrontFromContentPath(pathSegments)
                            ProviderLogger.info(
                                "Apple 歌词请求使用账号 storefront: " +
                                    "${sourceStorefront ?: "none"}->$accountStorefront"
                            )
                        }
                        return@installHook
                    }
                    val requestToken = requestUri.getQueryParameter(
                        AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
                    )
                    val requestLocalization =
                        internalCatalogResolver.catalogRequestLocalization(requestToken)
                    val configuredStorefront =
                        AppleInternalCatalogResolver.storefrontForContentUiLanguage(selection)
                    val storefront = requestLocalization?.storefront
                        ?: configuredStorefront
                        ?: return@installHook
                    val language = requestLocalization?.language
                        ?: internalCatalogResolver.languageTagForCurrentRequest(selection)
                        ?: return@installHook
                    logContentRequestLocalizationDecision(
                        uri = requestUri,
                        requestToken = requestToken,
                        requestLocalization = requestLocalization,
                        targetStorefront = storefront,
                        targetLanguage = language,
                    )
                    val rewritten = rewriteContentRequest(
                        request = request,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                    )
                        ?: return@installHook
                    AppleReflection.setField(httpChain, "e", rewritten)
                },
                after = { chain, result ->
                    finishContentHttpTiming(
                        httpChain = chain.args.firstOrNull(),
                        response = result,
                    )
                },
            )
            ProviderLogger.info("Apple 内容 HTTP 本地化 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple 内容 HTTP 本地化 Hook 安装失败", it)
        }
    }

    private fun startContentHttpTiming(httpChain: Any, uri: Uri) {
        if (!BuildConfig.DEBUG || !uri.host.orEmpty().contains("apple", ignoreCase = true)) return
        val requestToken = uri.getQueryParameter(
            AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
        )
        val source = if (requestToken == null) {
            AppleContentHttpTimingTracker.Source.NATIVE
        } else {
            AppleContentHttpTimingTracker.Source.MODULE
        }
        val start = contentHttpTimingTracker.start(
            requestKey = httpChain,
            descriptor = AppleContentHttpTimingTracker.RequestDescriptor(
                source = source,
                category = contentHttpRequestCategory(uri.pathSegments),
                storefront = AppleInternalCatalogResolver.storefrontFromContentPath(
                    uri.pathSegments
                ),
                pendingModuleRequests = internalCatalogResolver.pendingCatalogRequestCount(),
            ),
        )
        if (start.sourceInFlight == 1) {
            ProviderLogger.diagnostic(
                "AppleContentHttpTiming: event=source_active, " +
                    "source=${source.name.lowercase()}, " +
                    "category=${start.descriptor.category}, " +
                    "storefront=${start.descriptor.storefront ?: "none"}, " +
                    "pendingModule=${start.descriptor.pendingModuleRequests}, " +
                    "totalInFlight=${start.totalInFlight}"
            )
        }
    }

    private fun finishContentHttpTiming(httpChain: Any?, response: Any?) {
        if (!BuildConfig.DEBUG || httpChain == null) return
        val statusCode = response?.let {
            runCatching { AppleReflection.intField(it, "d") }.getOrNull()
        }
        val completion = contentHttpTimingTracker.finish(httpChain, statusCode) ?: return
        if (completion.isSlow) {
            ProviderLogger.diagnostic(
                "AppleContentHttpTiming: event=slow, " +
                    "source=${completion.descriptor.source.name.lowercase()}, " +
                    "category=${completion.descriptor.category}, " +
                    "storefront=${completion.descriptor.storefront ?: "none"}, " +
                    "elapsedMs=${completion.elapsedMs}, code=${completion.statusCode ?: "unknown"}, " +
                    "pendingModuleAtStart=${completion.descriptor.pendingModuleRequests}, " +
                    "sourceInFlight=${completion.sourceInFlight}, " +
                    "totalInFlight=${completion.totalInFlight}"
            )
        }
        completion.summary?.let { summary ->
            ProviderLogger.diagnostic(
                "AppleContentHttpTiming: event=summary, windowMs=${summary.windowMs}, " +
                    "native=${contentHttpTimingStats(summary.native)}, " +
                    "module=${contentHttpTimingStats(summary.module)}, " +
                    "totalInFlight=${summary.totalInFlight}"
            )
        }
    }

    private fun contentHttpTimingStats(
        stats: AppleContentHttpTimingTracker.SourceStats,
    ): String = "{completed=${stats.completed}, avgMs=${stats.averageElapsedMs}, " +
        "maxMs=${stats.maxElapsedMs}, slow=${stats.slowRequests}, " +
        "inFlight=${stats.inFlight}, categories=${stats.categories}}"

    private fun contentHttpRequestCategory(pathSegments: List<String>): String {
        if (isAppleLyricsRequestPath(pathSegments)) return "lyrics"
        val knownCategories = listOf(
            "artists",
            "albums",
            "songs",
            "music-videos",
            "playlists",
            "search",
            "charts",
            "views",
            "recommendations",
        )
        return pathSegments.firstOrNull(knownCategories::contains) ?: "other"
    }

    private fun rewriteContentRequest(
        request: Any,
        storefront: String,
        language: String,
        requestToken: String?,
    ): Any? {
        val url = AppleReflection.field(request, "a")?.toString().orEmpty()
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty()
        if (!host.contains("apple", ignoreCase = true)) return null

        val segments = uri.pathSegments.toMutableList()
        val pathStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(segments)
        val isPersonalizedContent = segments.take(3) == listOf("v1", "me", "recommendations")
        val isLyricsRequest = isAppleLyricsRequestPath(segments)
        if (pathStorefront == null && !isPersonalizedContent) return null
        if (isLyricsRequest) return null
        if (pathStorefront != null) {
            segments[2] = storefront
        }

        val builder = uri.buildUpon()
        builder.encodedPath(
            segments.joinToString(separator = "/", prefix = "/") { Uri.encode(it) }
        )
        builder.clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (
                name != "l" &&
                name != AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
            ) {
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
        }
        builder.appendQueryParameter("l", language)
        val rewrittenUrl = builder.build().toString()
        val sourceAcceptLanguage = requestHeader(request, "Accept-Language")
        val sourceStorefrontHeader = requestHeader(request, "X-Apple-Store-Front")
        val sourceRequestStorefrontHeader =
            requestHeader(request, "X-Apple-Request-Store-Front")
        val targetStorefrontHeader =
            AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                storefront = storefront,
                currentValue = sourceStorefrontHeader,
            )
        val targetRequestStorefrontHeader =
            AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                storefront = storefront,
                currentValue = sourceRequestStorefrontHeader,
            )
        val hasHeaderChanges =
            sourceAcceptLanguage != language ||
                targetStorefrontHeader != sourceStorefrontHeader ||
                targetRequestStorefrontHeader != sourceRequestStorefrontHeader
        if (rewrittenUrl == url && !hasHeaderChanges) return null

        if (rewrittenUrl != url) {
            logContentRequestRewrite(
                uri = uri,
                pathStorefront = pathStorefront,
                targetStorefront = storefront,
                targetLanguage = language,
                personalized = isPersonalizedContent,
            )
        }

        val requestBuilder = AppleReflection.call(request, "b") ?: return null
        if (rewrittenUrl != url) {
            AppleReflection.call(requestBuilder, "h", rewrittenUrl)
        }
        AppleReflection.call(requestBuilder, "d", "Accept-Language", language)
        targetStorefrontHeader?.let { value ->
            AppleReflection.call(requestBuilder, "d", "X-Apple-Store-Front", value)
        }
        targetRequestStorefrontHeader?.let { value ->
            AppleReflection.call(
                requestBuilder,
                "d",
                "X-Apple-Request-Store-Front",
                value,
            )
        }
        logContentRequestHeaders(
            requestToken = requestToken,
            sourceAcceptLanguage = sourceAcceptLanguage,
            targetAcceptLanguage = language,
            sourceStorefrontHeader = sourceStorefrontHeader,
            targetStorefrontHeader = targetStorefrontHeader,
            sourceRequestStorefrontHeader = sourceRequestStorefrontHeader,
            targetRequestStorefrontHeader = targetRequestStorefrontHeader,
        )
        return AppleReflection.call(requestBuilder, "b")
    }

    private fun requestHeader(request: Any, name: String): String? = runCatching {
        val headers = AppleReflection.field(request, "c") ?: return@runCatching null
        (AppleReflection.call(headers, "e", name) as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }.getOrNull()

    private fun rewriteContentRequestStorefrontOnly(
        request: Any,
        storefront: String,
    ): Any? {
        val url = AppleReflection.field(request, "a")?.toString().orEmpty()
        val uri = Uri.parse(url)
        if (!uri.host.orEmpty().contains("apple", ignoreCase = true)) return null
        val segments = uri.pathSegments.toMutableList()
        val pathStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(segments)
            ?: return null
        if (pathStorefront == storefront) return null
        segments[2] = storefront
        val rewrittenUrl = uri.buildUpon()
            .encodedPath(
                segments.joinToString(separator = "/", prefix = "/") { Uri.encode(it) }
            )
            .build()
            .toString()
        val requestBuilder = AppleReflection.call(request, "b") ?: return null
        AppleReflection.call(requestBuilder, "h", rewrittenUrl)
        return AppleReflection.call(requestBuilder, "b")
    }

    private fun logContentRequestRewrite(
        uri: Uri,
        pathStorefront: String?,
        targetStorefront: String,
        targetLanguage: String,
        personalized: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val segments = uri.pathSegments
        val category = when {
            personalized -> "recommendations"
            "library" in segments -> "library"
            "recent" in segments || "history" in segments -> "recent"
            "radio" in segments || "stations" in segments -> "radio"
            "playlists" in segments -> "playlists"
            "albums" in segments -> "albums"
            "artists" in segments -> "artists"
            "songs" in segments -> "songs"
            segments.getOrNull(1) == "me" -> "me"
            else -> "other"
        }
        val safeSegments = setOf(
            "v1", "catalog", "me", "recommendations", "library", "recent", "history",
            "radio", "stations", "playlists", "albums", "artists", "songs", "search",
            "charts", "views", "relationships", "personal-recommendation",
        )
        val pathShape = segments.mapIndexed { index, segment ->
            when {
                index == 2 && pathStorefront != null -> "{storefront}"
                segment in safeSegments -> segment
                else -> "{value}"
            }
        }.joinToString(separator = "/", prefix = "/")
        val sourceLanguage = uri.getQueryParameter("l") ?: "unset"
        val traceKey = "$category:$pathShape:$pathStorefront:$targetStorefront:" +
            "$sourceLanguage:$targetLanguage"
        if (!contentRequestTraceKeys.add(traceKey)) return
        ProviderLogger.info(
            "Apple 内容请求路径改写: host=${uri.host.orEmpty()}, category=$category, " +
                "path=$pathShape, storefront=${pathStorefront ?: "none"}->$targetStorefront, " +
                "language=$sourceLanguage->$targetLanguage"
        )
    }

    private fun logContentRequestLocalizationDecision(
        uri: Uri,
        requestToken: String?,
        requestLocalization: AppleInternalCatalogResolver.CatalogRequestLocalization?,
        targetStorefront: String,
        targetLanguage: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val segments = uri.pathSegments
        if (segments.getOrNull(3) != "songs") return
        val pendingCount = internalCatalogResolver.pendingCatalogRequestCount()
        if (requestToken == null && pendingCount == 0) return
        val sourceStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(segments)
        val sourceLanguage = uri.getQueryParameter("l") ?: "unset"
        val requestKey = uri.getQueryParameter("ids")
            ?: uri.getQueryParameter("filter[isrc]")
            ?: segments.getOrNull(4)
            ?: "none"
        val traceKey = "$requestToken:$requestKey:$sourceStorefront:$sourceLanguage:" +
            "$targetStorefront:$targetLanguage:${requestLocalization != null}"
        if (!contentRequestDecisionTraceKeys.add(traceKey)) return
        ProviderLogger.diagnostic(
            "AppleContentHttpLocalization: token=${requestToken ?: "none"}, " +
                "resolved=${requestLocalization != null}, pending=$pendingCount, " +
                "request=$requestKey, storefront=${sourceStorefront ?: "none"}" +
                "->$targetStorefront, language=$sourceLanguage->$targetLanguage"
        )
    }

    private fun logContentRequestHeaders(
        requestToken: String?,
        sourceAcceptLanguage: String?,
        targetAcceptLanguage: String,
        sourceStorefrontHeader: String?,
        targetStorefrontHeader: String?,
        sourceRequestStorefrontHeader: String?,
        targetRequestStorefrontHeader: String?,
    ) {
        if (!BuildConfig.DEBUG || requestToken == null) return
        if (!contentRequestHeaderTraceKeys.add(requestToken)) return
        ProviderLogger.diagnostic(
            "AppleContentHttpHeaders: token=$requestToken, " +
                "acceptLanguage=${sourceAcceptLanguage ?: "unset"}->$targetAcceptLanguage, " +
                "storefrontHeader=${sourceStorefrontHeader ?: "unset"}" +
                "->${targetStorefrontHeader ?: "unset"}, " +
                "requestStorefrontHeader=${sourceRequestStorefrontHeader ?: "unset"}" +
                "->${targetRequestStorefrontHeader ?: "unset"}"
        )
    }

    private fun hookTranslationPreference() {
        val preferencesClass =
            classLoader.loadClass("com.apple.android.music.utils.AppSharedPreferences")
        val translationMethod = AppleReflection.findMethod(
            preferencesClass,
            "setLyricsTranslationSelected",
            parameterCount = 1
        )
        hookRegistrar.install(translationMethod, after = { chain, _ ->
            (chain.args.firstOrNull() as? Boolean)?.let {
                PreferencesMonitor.notifyTranslationSelectedChanged(it)
            }
        })
        val pronunciationMethod = AppleReflection.findMethod(
            preferencesClass,
            "setLyricsPronunciationSelected",
            parameterCount = 1
        )
        hookRegistrar.install(pronunciationMethod, after = { chain, _ ->
            (chain.args.firstOrNull() as? Boolean)?.let {
                PreferencesMonitor.notifyPronunciationSelectedChanged(it)
            }
        })
    }

    private fun hookAppleLyricsSourceMenu() {
        runCatching {
            val resolved = hookResolver.resolveMethod(
                AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER
            )
            hookRegistrar.install(resolved.method, after = { chain, _ ->
                addOnlineSourceMenuItems(
                    clickListener = chain.thisObject,
                    anchor = chain.args.firstOrNull() as? View,
                )
            })
            ProviderLogger.debug(
                "Apple Music 三方歌词来源菜单 Hook 已安装: " +
                    "${resolved.target.className}#${resolved.method.name}, " +
                    "fallback=${resolved.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error(
                "Apple Music 三方歌词来源菜单 Hook 安装失败：未找到签名匹配的点击监听器",
                it,
            )
        }
    }

    private fun addOnlineSourceMenuItems(clickListener: Any?, anchor: View?) {
        if (clickListener == null) {
            reportAppleLyricsSourceMenuDiagnostic(stage = "missing_click_listener")
            return
        }
        if (anchor == null) {
            reportAppleLyricsSourceMenuDiagnostic(
                stage = "missing_anchor",
                clickListener = clickListener,
            )
            return
        }
        val fragment = resolveAppleLyricsSourceMenuFragment(clickListener)
        if (fragment == null) {
            reportAppleLyricsSourceMenuDiagnostic(
                stage = "fragment_not_found",
                clickListener = clickListener,
                anchor = anchor,
            )
            return
        }
        val popup = resolveAppleLyricsSourceMenuPopup(fragment)
        if (popup == null) {
            reportAppleLyricsSourceMenuDiagnostic(
                stage = "popup_not_found",
                clickListener = clickListener,
                anchor = anchor,
                fragment = fragment,
            )
            return
        }
        val menu = popup.contentView as? LinearLayout
        if (menu == null) {
            reportAppleLyricsSourceMenuDiagnostic(
                stage = "content_not_linear_layout",
                clickListener = clickListener,
                anchor = anchor,
                fragment = fragment,
                popup = popup,
            )
            return
        }
        if (menu.childCount < 2) {
            reportAppleLyricsSourceMenuDiagnostic(
                stage = "native_items_insufficient",
                clickListener = clickListener,
                anchor = anchor,
                fragment = fragment,
                popup = popup,
                menu = menu,
            )
            return
        }
        normalizeOnlineSourceMenuTextItems(menu)
        val nativeMinimumWidth = nativeOnlineSourceMenuWidth(popup, menu)
        val songId = currentAppleLyricsSongId
        if (songId == null) {
            reportAppleLyricsSourceMenuDiagnostic(
                stage = "missing_song_id",
                clickListener = clickListener,
                anchor = anchor,
                fragment = fragment,
                popup = popup,
                menu = menu,
            )
            return
        }
        pendingOnlineSourceMenuSwitches.entries.removeAll { it.value.songId != songId }
        failedOnlineSourceMenuSwitches.entries.removeAll { it.value.songId != songId }
        confirmedOnlineSourceMenuSelections.entries.removeAll { it.value.songId != songId }
        activeOnlineSourceMenu = ActiveOnlineSourceMenu(
            popup = WeakReference(popup),
            menu = WeakReference(menu),
            anchor = WeakReference(anchor),
            songId = songId,
            nativeMinimumWidth = nativeMinimumWidth,
        )
        reportAppleLyricsSourceMenuDiagnostic(
            stage = "render_requested",
            clickListener = clickListener,
            anchor = anchor,
            fragment = fragment,
            popup = popup,
            menu = menu,
            songId = songId,
        )
        renderOnlineSourceMenuItems(popup, menu, anchor, songId, nativeMinimumWidth)
    }

    /**
     * 仅供 Debug 包定位 AM 菜单注入链路的静默退出点。
     *
     * 6.5.1 的混淆字段与弹窗根节点都可能变化，因此同时记录点击对象、字段静态类型、
     * Popup 内容树和当前三方来源状态；Release 包不会执行或输出这组高频诊断。
     */
    private fun reportAppleLyricsSourceMenuDiagnostic(
        stage: String,
        clickListener: Any? = null,
        anchor: View? = null,
        fragment: Any? = null,
        popup: PopupWindow? = null,
        menu: LinearLayout? = null,
        songId: String? = currentAppleLyricsSongId,
    ) {
        if (!BuildConfig.DEBUG) return
        val content = popup?.contentView
        val resolvedSongId = songId ?: currentAppleLyricsSongId
        val pronunciationSource = currentOnlineSource(resolvedSongId, "pronunciation")
        val translationSource = currentOnlineSource(resolvedSongId, "translation")
        val pronunciationPresentation = onlineSourceMenuPresentation(
            actualSource = pronunciationSource,
            pendingTargetSource = pendingOnlineSourceMenuSwitches["pronunciation"]
                ?.takeIf { it.songId == resolvedSongId }
                ?.targetSource,
            failedSource = failedOnlineSourceMenuSwitches["pronunciation"]
                ?.takeIf { it.songId == resolvedSongId }
                ?.displayedSource,
        )
        val translationPresentation = onlineSourceMenuPresentation(
            actualSource = translationSource,
            pendingTargetSource = pendingOnlineSourceMenuSwitches["translation"]
                ?.takeIf { it.songId == resolvedSongId }
                ?.targetSource,
            failedSource = failedOnlineSourceMenuSwitches["translation"]
                ?.takeIf { it.songId == resolvedSongId }
                ?.displayedSource,
        )
        ProviderLogger.diagnostic(
            "Apple Music 三方歌词来源菜单诊断: stage=$stage, " +
                "listener=${debugAppleLyricsValue(clickListener)}, " +
                "listenerFields=${debugAppleLyricsSourceMenuFields(clickListener)}, " +
                "anchor=${debugAppleLyricsValue(anchor)}, " +
                "fragment=${debugAppleLyricsValue(fragment)}, " +
                "fragmentFields=${debugAppleLyricsSourceMenuFields(fragment)}, " +
                "popup=${debugAppleLyricsValue(popup)}, " +
                "popupShowing=${popup?.isShowing}, content=${debugAppleLyricsValue(content)}, " +
                "menu=${debugAppleLyricsValue(menu)}, nativeChildren=${menu?.childCount}, " +
                "viewTree=${debugAppleLyricsSourceMenuViewTree(content)}, " +
                "songId=$resolvedSongId, storeRevision=${nativeOnlineTranslationStore.revision()}, " +
                "translation=[has=${nativeOnlineTranslationStore.hasTranslation(resolvedSongId)}," +
                "source=$translationSource,presentation=$translationPresentation," +
                "selected=${PreferencesMonitor.isTranslationSelected()}], " +
                "pronunciation=[has=${nativeOnlineTranslationStore.hasPronunciation(resolvedSongId)}," +
                "source=$pronunciationSource,presentation=$pronunciationPresentation," +
                "selected=${PreferencesMonitor.isPronunciationSelected()}," +
                "hidden=${shouldHideMandarinPronunciation(resolvedSongId)}]"
        )
    }

    /** 只输出与菜单解析有关的字段，避免 Debug 日志暴露无关对象状态。 */
    private fun debugAppleLyricsSourceMenuFields(instance: Any?): String {
        if (!BuildConfig.DEBUG || instance == null) return "none"
        return generateSequence(instance.javaClass) { it.superclass }
            .flatMap { clazz -> clazz.declaredFields.asSequence() }
            .filter { field ->
                field.type.name == "com.apple.android.music.player.fragment.PlayerLyricsViewFragment" ||
                    PopupWindow::class.java.isAssignableFrom(field.type)
            }
            .joinToString(prefix = "[", postfix = "]") { field ->
                "${field.declaringClass.simpleName}.${field.name}:${field.type.simpleName}"
            }
    }

    /**
     * 将弹窗根节点压缩为最多两层的树，区分 LinearLayout、包装容器与 RecyclerView。
     *
     * 菜单只在用户点击时记录一次，限制深度可避免把歌词页面整棵 View 树写入日志。
     */
    private fun debugAppleLyricsSourceMenuViewTree(root: View?): String {
        if (!BuildConfig.DEBUG || root == null) return "none"
        fun describe(view: View, depth: Int): String {
            val text = (view as? TextView)
                ?.text
                ?.toString()
                ?.replace('\n', ' ')
                ?.take(48)
            val label = view.javaClass.simpleName +
                "(id=${view.id},children=${(view as? ViewGroup)?.childCount ?: 0},text=$text)"
            if (depth >= 2 || view !is ViewGroup || view.childCount == 0) return label
            return (0 until view.childCount).joinToString(
                prefix = "$label[",
                postfix = "]",
            ) { index -> describe(view.getChildAt(index), depth + 1) }
        }
        return describe(root, depth = 0)
    }

    /**
     * 从不同混淆版本的点击监听器中读取 PlayerLyricsViewFragment。
     *
     * 旧版字段名为 `a`，6.5.1 改为随机字段名；后者按字段静态类型定位，避免再次依赖
     * 某个具体混淆字段名。
     */
    private fun resolveAppleLyricsSourceMenuFragment(clickListener: Any): Any? {
        runCatching { AppleReflection.field(clickListener, "a") }
            .getOrNull()
            ?.let { return it }
        return generateSequence(clickListener.javaClass) { it.superclass }
            .flatMap { clazz -> clazz.declaredFields.asSequence() }
            .filter { field ->
                field.type.name == "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
            }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(clickListener)
                }.getOrNull()
            }
    }

    /** 兼容字段名变更，按 PopupWindow 类型读取当前歌词控制菜单。 */
    private fun resolveAppleLyricsSourceMenuPopup(fragment: Any): PopupWindow? =
        generateSequence(fragment.javaClass) { it.superclass }
            .flatMap { clazz -> clazz.declaredFields.asSequence() }
            .filter { field -> PopupWindow::class.java.isAssignableFrom(field.type) }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(fragment) as? PopupWindow
                }.getOrNull()
            }

    private fun renderOnlineSourceMenuItems(
        popup: PopupWindow,
        menu: LinearLayout,
        anchor: View,
        songId: String,
        nativeMinimumWidth: Int,
    ) {
        for (index in menu.childCount - 1 downTo 0) {
            if (menu.getChildAt(index).tag == ONLINE_SOURCE_MENU_ITEM_TAG) {
                menu.removeViewAt(index)
            }
        }
        normalizeOnlineSourceMenuTextItems(menu)
        val pronunciationSource = currentOnlineSource(songId, "pronunciation")
        val pronunciationPresentation = onlineSourceMenuPresentation(
            actualSource = pronunciationSource,
            pendingTargetSource = pendingOnlineSourceMenuSwitches["pronunciation"]
                ?.takeIf { it.songId == songId }
                ?.targetSource,
            failedSource = failedOnlineSourceMenuSwitches["pronunciation"]
                ?.takeIf { it.songId == songId }
                ?.displayedSource,
        )
        val translationSource = currentOnlineSource(songId, "translation")
        val translationPresentation = onlineSourceMenuPresentation(
            actualSource = translationSource,
            pendingTargetSource = pendingOnlineSourceMenuSwitches["translation"]
                ?.takeIf { it.songId == songId }
                ?.targetSource,
            failedSource = failedOnlineSourceMenuSwitches["translation"]
                ?.takeIf { it.songId == songId }
                ?.displayedSource,
        )
        val pronunciationSelected = PreferencesMonitor.isPronunciationSelected()
        val pronunciationHidden = shouldHideMandarinPronunciation(songId)
        val translationSelected = PreferencesMonitor.isTranslationSelected()
        reportAppleLyricsSourceMenuDiagnostic(
            stage = "render_conditions",
            popup = popup,
            anchor = anchor,
            menu = menu,
            songId = songId,
        )
        if (
            pronunciationPresentation != null &&
            pronunciationSelected &&
            !pronunciationHidden
        ) {
            menu.addView(
                createOnlineSourceMenuItem(
                    menu = menu,
                    songId = songId,
                    contentType = "pronunciation",
                    presentation = pronunciationPresentation,
                ),
                1,
            )
        }
        if (
            translationPresentation != null &&
            translationSelected
        ) {
            menu.addView(
                createOnlineSourceMenuItem(
                    menu = menu,
                    songId = songId,
                    contentType = "translation",
                    presentation = translationPresentation,
                )
            )
        }
        normalizeOnlineSourceMenuTextItems(menu)
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple Music 三方歌词来源菜单诊断: stage=rendered, songId=$songId, " +
                    "translationSource=$translationSource, translationPresentation=$translationPresentation, " +
                    "translationSelected=$translationSelected, pronunciationSource=$pronunciationSource, " +
                    "pronunciationPresentation=$pronunciationPresentation, " +
                    "pronunciationSelected=$pronunciationSelected, pronunciationHidden=$pronunciationHidden, " +
                    "finalChildren=${menu.childCount}, " +
                    "viewTree=${debugAppleLyricsSourceMenuViewTree(menu)}"
            )
        }
        updateOnlineSourceMenuBounds(
            popup = popup,
            menu = menu,
            anchor = anchor,
            nativeMinimumWidth = nativeMinimumWidth,
        )
        menu.post {
            if (popup.isShowing) {
                updateOnlineSourceMenuBounds(
                    popup = popup,
                    menu = menu,
                    anchor = anchor,
                    nativeMinimumWidth = nativeMinimumWidth,
                )
            }
        }
    }

    private fun normalizeOnlineSourceMenuTextItems(menu: LinearLayout) {
        for (index in 0 until menu.childCount) {
            val item = menu.getChildAt(index) as? TextView ?: continue
            item.isSingleLine = true
            item.maxLines = 1
            item.ellipsize = null
            item.maxWidth = Int.MAX_VALUE
            item.setHorizontallyScrolling(true)
            item.layoutParams?.let { layoutParams ->
                if (layoutParams.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    item.layoutParams = layoutParams
                }
            }
        }
    }

    private fun nativeOnlineSourceMenuWidth(
        popup: PopupWindow,
        menu: LinearLayout,
    ): Int {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var nativeItemWidth = 0
        for (index in 0 until menu.childCount) {
            val item = menu.getChildAt(index)
            item.measure(unspecified, unspecified)
            nativeItemWidth = maxOf(
                nativeItemWidth,
                item.measuredWidth,
                item.minimumWidth,
                item.layoutParams?.width?.takeIf { it > 0 } ?: 0,
            )
        }
        menu.measure(
            unspecified,
            unspecified,
        )
        return onlineSourceMenuWidth(
            popup.width,
            popup.contentView.width,
            menu.width,
            menu.measuredWidth,
            menu.minimumWidth,
            menu.layoutParams?.width?.takeIf { it > 0 } ?: 0,
            nativeItemWidth + menu.paddingLeft + menu.paddingRight,
        )
    }

    private fun updateOnlineSourceMenuBounds(
        popup: PopupWindow,
        menu: LinearLayout,
        anchor: View,
        nativeMinimumWidth: Int,
    ) {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        menu.minimumWidth = nativeMinimumWidth
        menu.measure(unspecified, unspecified)
        val desiredWidth = onlineSourceMenuWidth(
            nativeMinimumWidth,
            menu.measuredWidth,
        )
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY),
            unspecified,
        )
        val desiredHeight = menu.measuredHeight.coerceAtLeast(1)

        // Keep PopupWindow's original drop-down anchor and offsets. Absolute screen
        // coordinates use a different origin on inset and multi-window displays.
        popup.update(anchor, desiredWidth, desiredHeight)
        menu.requestLayout()
        menu.invalidate()
    }

    internal fun onlineSourceMenuWidth(vararg candidates: Int): Int =
        candidates.asSequence().filter { it > 0 }.maxOrNull() ?: 1

    private fun createOnlineSourceMenuItem(
        menu: LinearLayout,
        songId: String,
        contentType: String,
        presentation: OnlineSourceMenuPresentation,
    ): TextView {
        val layoutId = application.resources.getIdentifier(
            "menu_item_lyrics_translations",
            "layout",
            APPLE_MUSIC_PACKAGE,
        )
        val item = LayoutInflater.from(menu.context)
            .inflate(layoutId, menu, false) as TextView
        item.tag = ONLINE_SOURCE_MENU_ITEM_TAG
        item.background = menu.getChildAt(0)?.background
            ?.constantState
            ?.newDrawable(menu.resources)
        item.text = onlineSourceMenuLabel(
            source = presentation.source,
            contentType = contentType,
            status = presentation.status,
        )
        val iconId = sequenceOf(
            "ic_nowplaying_repeat",
            "media3_icon_sync",
            "media_action_repeat_off",
        ).map { iconName ->
            application.resources.getIdentifier(
                iconName,
                "drawable",
                APPLE_MUSIC_PACKAGE,
            )
        }.firstOrNull { it != 0 } ?: 0
        item.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, iconId, 0)
        item.isEnabled = presentation.status != OnlineSourceMenuStatus.SWITCHING
        item.setOnClickListener {
            val targetSource = if (presentation.source == "QM") "NE" else "QM"
            val requestId = ++onlineSourceMenuRequestSequence
            val pending = PendingOnlineSourceSwitch(
                requestId = requestId,
                songId = songId,
                contentType = contentType,
                previousSource = presentation.source,
                targetSource = targetSource,
            )
            failedOnlineSourceMenuSwitches.remove(contentType)
            pendingOnlineSourceMenuSwitches[contentType] = pending
            ProviderLogger.diagnostic(
                "Apple Music 在线翻译来源菜单点击: requestId=$requestId, " +
                    "songId=$songId, contentType=$contentType, " +
                    "from=${presentation.source}, to=$targetSource, " +
                    "popupShowing=${activeOnlineSourceMenu?.popup?.get()?.isShowing}"
            )
            refreshActiveOnlineSourceMenu(songId)
            val requestAccepted = directPlayer?.requestOnlineLyricContentSource(
                requestId = requestId,
                songId = songId,
                contentType = contentType,
                source = targetSource,
            ) == true
            ProviderLogger.diagnostic(
                "Apple Music 在线翻译来源请求投递: requestId=$requestId, " +
                    "accepted=$requestAccepted"
            )
            if (!requestAccepted) {
                markOnlineSourceMenuSwitchFailed(
                    pending = pending,
                    actualSource = presentation.source,
                    reason = "binder_unavailable",
                )
                return@setOnClickListener
            }
            mainHandler.postDelayed(
                {
                    val current = pendingOnlineSourceMenuSwitches[contentType]
                    if (current?.requestId != requestId) return@postDelayed
                    markOnlineSourceMenuSwitchFailed(
                        pending = current,
                        actualSource = currentOnlineSource(songId, contentType),
                        reason = "timeout",
                    )
                },
                ONLINE_SOURCE_SWITCH_TIMEOUT_MS,
            )
        }
        return item
    }

    private fun resolvePendingOnlineSourceMenuSwitches(songId: String) {
        var sourceChanged = false
        val iterator = pendingOnlineSourceMenuSwitches.iterator()
        while (iterator.hasNext()) {
            val (_, pending) = iterator.next()
            if (pending.songId != songId) continue
            val actualSource = when (pending.contentType) {
                "pronunciation" -> nativeOnlineTranslationStore.pronunciationSource(songId)
                "translation" -> nativeOnlineTranslationStore.translationSource(songId)
                else -> null
            }
            if (actualSource == pending.targetSource) {
                iterator.remove()
                failedOnlineSourceMenuSwitches.remove(pending.contentType)
                confirmedOnlineSourceMenuSelections[pending.contentType] =
                    ConfirmedOnlineSourceSelection(
                        songId = songId,
                        contentType = pending.contentType,
                        source = actualSource,
                    )
                ProviderLogger.diagnostic(
                    "Apple Music 在线翻译来源菜单切换成功: " +
                        "requestId=${pending.requestId}, songId=$songId, " +
                        "contentType=${pending.contentType}, source=$actualSource"
                )
                sourceChanged = true
            }
        }
        if (sourceChanged) refreshActiveOnlineSourceMenu(songId)
    }

    private fun receiveOnlineSourceSwitchResult(
        requestId: Long,
        songId: String?,
        contentType: String?,
        requestedSource: String?,
        actualSource: String?,
        successful: Boolean,
    ) {
        mainHandler.post {
            val type = contentType ?: return@post
            val pending = pendingOnlineSourceMenuSwitches[type]
            if (
                pending == null ||
                pending.requestId != requestId ||
                pending.songId != songId ||
                pending.targetSource != requestedSource
            ) {
                ProviderLogger.diagnostic(
                    "忽略过期 Apple Music 在线翻译来源结果: " +
                        "requestId=$requestId, songId=$songId, contentType=$contentType, " +
                        "requested=$requestedSource, actual=$actualSource, successful=$successful"
                )
                return@post
            }
            ProviderLogger.diagnostic(
                "Apple Music 在线翻译来源结果已回传: " +
                    "requestId=$requestId, songId=$songId, contentType=$contentType, " +
                    "requested=$requestedSource, actual=$actualSource, successful=$successful"
            )
            if (successful) {
                pendingOnlineSourceMenuSwitches.remove(type)
                failedOnlineSourceMenuSwitches.remove(type)
                confirmedOnlineSourceMenuSelections[type] = ConfirmedOnlineSourceSelection(
                    songId = requireNotNull(songId),
                    contentType = type,
                    source = actualSource ?: requireNotNull(requestedSource),
                )
                refreshActiveOnlineSourceMenu(requireNotNull(songId))
            } else {
                markOnlineSourceMenuSwitchFailed(
                    pending = pending,
                    actualSource = actualSource ?: currentOnlineSource(songId, type),
                    reason = "source_unavailable",
                )
            }
        }
    }

    private fun markOnlineSourceMenuSwitchFailed(
        pending: PendingOnlineSourceSwitch,
        actualSource: String?,
        reason: String,
    ) {
        val current = pendingOnlineSourceMenuSwitches[pending.contentType]
        if (current?.requestId != pending.requestId) return
        pendingOnlineSourceMenuSwitches.remove(pending.contentType)
        val displayedSource = actualSource ?: pending.previousSource
        failedOnlineSourceMenuSwitches[pending.contentType] = FailedOnlineSourceSwitch(
            requestId = pending.requestId,
            songId = pending.songId,
            contentType = pending.contentType,
            displayedSource = displayedSource,
        )
        ProviderLogger.diagnostic(
            "Apple Music 在线翻译来源菜单切换失败: " +
                "requestId=${pending.requestId}, songId=${pending.songId}, " +
                "contentType=${pending.contentType}, target=${pending.targetSource}, " +
                "actual=$displayedSource, reason=$reason"
        )
        refreshActiveOnlineSourceMenu(pending.songId)
        mainHandler.postDelayed(
            {
                val failure = failedOnlineSourceMenuSwitches[pending.contentType]
                if (failure?.requestId != pending.requestId) return@postDelayed
                failedOnlineSourceMenuSwitches.remove(pending.contentType)
                refreshActiveOnlineSourceMenu(pending.songId)
            },
            ONLINE_SOURCE_SWITCH_FAILURE_FEEDBACK_MS,
        )
    }

    private fun currentOnlineSource(songId: String?, contentType: String): String? {
        val storedSource = when (contentType) {
            "pronunciation" -> nativeOnlineTranslationStore.pronunciationSource(songId)
            "translation" -> nativeOnlineTranslationStore.translationSource(songId)
            else -> null
        }
        val confirmedSource = confirmedOnlineSourceMenuSelections[contentType]
            ?.takeIf { it.songId == songId }
            ?.source
        return effectiveOnlineSource(
            storedSource = storedSource,
            confirmedSource = confirmedSource,
            onlineContentConsumed = hasCurrentOnlineContentConsumption(songId, contentType),
        )
    }

    internal fun effectiveOnlineSource(
        storedSource: String?,
        confirmedSource: String?,
        onlineContentConsumed: Boolean,
    ): String? = if (onlineContentConsumed) confirmedSource ?: storedSource else null

    /**
     * 菜单只暴露当前歌词模型真正消费的三方来源。
     *
     * Store 中可以保留 QQ/网易备用内容，但 Apple 官方逐行内容优先；只有某一行的
     * 官方内容无效并实际回退到 Store 时，才允许把对应三方来源显示为当前来源。
     */
    private fun hasCurrentOnlineContentConsumption(
        songId: String?,
        contentType: String,
    ): Boolean {
        if (
            songId.isNullOrBlank() ||
            songId != currentAppleLyricsSongId ||
            !isNativeOnlineTranslationEnabled()
        ) return false
        val songNative = currentAppleLyricsNativeSong(songId) ?: return false
        val lines = currentAppleLyricsNativeLines(songNative)
        if (lines.isEmpty()) return false

        return when (contentType) {
            "translation" -> lines.any { line ->
                AppleNativeOnlineTranslationStore.sanitizeContent(
                    nativeRawLineText(line, "getHtmlTranslationLineText")
                ) == null && onlineTranslationForNativeLine(line) != null
            }
            "pronunciation" -> {
                if (shouldHideMandarinPronunciation(songId = songId)) return false
                lines.any { line ->
                    val originalText = nativeOriginalLineText(line)
                    val officialPronunciation = RomanizationPolicy.sanitize(
                        originalText = originalText,
                        pronunciation = nativeRawLineText(
                            line,
                            "getHtmlPronunciationLineText",
                        ),
                    )
                    officialPronunciation == null &&
                        onlinePronunciationForNativeLine(line) != null
                }
            }
            else -> false
        }
    }

    private fun currentAppleLyricsNativeSong(songId: String): Any? {
        val pointer = appleLyricsSongPointerRef?.get() ?: return null
        val songNative = runCatching { AppleReflection.call(pointer, "get") }.getOrNull()
            ?: return null
        return songNative.takeIf { nativeSongId(it) == songId }
    }

    private fun currentAppleLyricsNativeLines(songNative: Any): List<Any> {
        val sections = runCatching { AppleReflection.call(songNative, "getSections") }
            .getOrNull() ?: return emptyList()
        return nativeVectorItems(sections, limit = 8).flatMap { section ->
            val lines = runCatching { AppleReflection.call(section, "getLines") }.getOrNull()
            nativeVectorItems(lines, limit = 128)
        }
    }

    private fun refreshActiveOnlineSourceMenu(songId: String) {
        val active = activeOnlineSourceMenu ?: return
        if (active.songId != songId) return
        val popup = active.popup.get()
        val menu = active.menu.get()
        val anchor = active.anchor.get()
        if (popup == null || menu == null || anchor == null || !popup.isShowing) {
            activeOnlineSourceMenu = null
            return
        }
        renderOnlineSourceMenuItems(
            popup = popup,
            menu = menu,
            anchor = anchor,
            songId = songId,
            nativeMinimumWidth = active.nativeMinimumWidth,
        )
    }

    internal fun onlineSourceMenuPresentation(
        actualSource: String?,
        pendingTargetSource: String?,
        failedSource: String?,
    ): OnlineSourceMenuPresentation? = when {
        pendingTargetSource != null -> OnlineSourceMenuPresentation(
            source = pendingTargetSource,
            status = OnlineSourceMenuStatus.SWITCHING,
        )
        failedSource != null -> OnlineSourceMenuPresentation(
            source = failedSource,
            status = OnlineSourceMenuStatus.FAILED,
        )
        actualSource != null -> OnlineSourceMenuPresentation(
            source = actualSource,
            status = OnlineSourceMenuStatus.STABLE,
        )
        else -> null
    }

    internal fun onlineSourceMenuLabel(
        source: String,
        contentType: String,
        status: OnlineSourceMenuStatus = OnlineSourceMenuStatus.STABLE,
    ): String {
        val sourceLabel = if (source == "QM") "QQ" else "网易"
        val contentLabel = if (contentType == "pronunciation") "发音" else "翻译"
        return when (status) {
            OnlineSourceMenuStatus.STABLE -> sourceLabel + contentLabel
            OnlineSourceMenuStatus.SWITCHING -> "切换中"
            OnlineSourceMenuStatus.FAILED -> "切换失败"
        }
    }

    private fun hookMediaMetadataChange() {
        val controller =
            classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
        val metadataMethod = AppleReflection.findMethod(
            controller,
            "onMetadataUpdated",
            parameterCount = 2
        )
        hookRegistrar.install(metadataMethod, after = { chain, _ ->
            val mediaPlayer = chain.args.firstOrNull()
            if (!isActivePlaybackCallback(mediaPlayer, activePlaybackPlayer)) {
                ProviderLogger.debug(
                    "忽略非活动播放器的歌曲元数据：source=onMetadataUpdated, " +
                        "callback=${mediaPlayer?.let(System::identityHashCode)}, " +
                        "active=${activePlaybackPlayer?.let(System::identityHashCode)}"
                )
                return@installHook
            }
            val callbackPlayer = mediaPlayer ?: return@installHook
            val changedItem = chain.args.getOrNull(1)
            val currentItem = runCatching {
                AppleReflection.call(callbackPlayer, "getCurrentItem")
            }.getOrNull()
            val publishAsCurrent = isCurrentQueueItem(changedItem, currentItem)
            val refreshPlaybackMetadata = if (publishAsCurrent) {
                val controllerInstance = chain.thisObject
                {
                    runCatching {
                        metadataMethod.invoke(controllerInstance, callbackPlayer, changedItem)
                    }.onFailure {
                        ProviderLogger.error("Apple 播放元数据覆盖刷新失败", it)
                    }
                    Unit
                }
            } else {
                null
            }
            handleQueueItem(
                queueItem = changedItem,
                source = "onMetadataUpdated",
                publishAsCurrent = publishAsCurrent,
                refreshPlaybackMetadata = refreshPlaybackMetadata,
            )
        })

        val indexMethod = AppleReflection.findMethod(
            controller,
            "onPlaybackIndexChanged",
            parameterCount = 3
        )
        hookRegistrar.install(indexMethod, after = { chain, _ ->
            refreshCurrentQueueItemIfActive(
                chain.args.firstOrNull(),
                "onPlaybackIndexChanged"
            )
        })
        ProviderLogger.debug("歌曲元数据 Hook 已安装")
    }

    private fun hookMediaSessionMetadata() {
        runCatching {
            val method = MediaSession::class.java.getDeclaredMethod(
                "setMetadata",
                MediaMetadata::class.java,
            ).also { it.isAccessible = true }
            hookRegistrar.installArgumentRewrite(method) { chain ->
                val metadata = chain.args.firstOrNull() as? MediaMetadata
                    ?: return@installArgumentRewriteHook null
                val session = chain.thisObject as? MediaSession
                    ?: return@installArgumentRewriteHook null
                val identityBefore = activePlaybackMediaIdentity()
                val explicitId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                val mediaId = frameworkMediaMetadataId(metadata)
                if (mediaId == null) {
                    logMetadataIdentity(
                        event = "framework_capture_unresolved",
                        identity = identityBefore,
                        details = "explicitId=$explicitId, " +
                            "title=${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}, " +
                            "artist=${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}",
                    )
                    return@installArgumentRewriteHook null
                }
                val previous = currentFrameworkMediaSessionRefresh
                val alias = effectiveInAppMetadataOverride(mediaId)
                val baseMetadata = if (
                    previous?.mediaId == mediaId &&
                    alias != null &&
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == alias.title &&
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) == alias.artist
                ) {
                    previous.metadata
                } else {
                    metadata
                }
                currentFrameworkMediaSessionRefresh = FrameworkMediaSessionRefresh(
                    mediaId = mediaId,
                    session = WeakReference(session),
                    metadata = baseMetadata,
                )
                logMetadataIdentity(
                    event = "framework_capture",
                    details = "explicitId=$explicitId, resolvedId=$mediaId, " +
                        "aliasHit=${alias != null}, " +
                        "title=${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}, " +
                        "artist=${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}",
                )
                if (alias == null) return@installArgumentRewriteHook null
                val rewritten = rewriteFrameworkMediaMetadata(metadata, alias)
                    ?: return@installArgumentRewriteHook null
                ProviderLogger.info(
                    "Apple MediaSession 元数据已覆盖: " +
                        "id=$mediaId, title=${alias.title}, artist=${alias.artist}"
                )
                arrayOf(rewritten)
            }
            ProviderLogger.info("Apple MediaSession 元数据 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple MediaSession 元数据 Hook 安装失败", it)
        }
    }

    private fun hookMediaSessionQueue() {
        runCatching {
            val method = MediaSession::class.java.getDeclaredMethod(
                "setQueue",
                List::class.java,
            ).also { it.isAccessible = true }
            hookRegistrar.installArgumentRewrite(method) { chain ->
                if (frameworkMediaQueueRefreshInProgress.get()) {
                    return@installArgumentRewriteHook null
                }
                val session = chain.thisObject as? MediaSession
                    ?: return@installArgumentRewriteHook null
                @Suppress("UNCHECKED_CAST")
                val queue = chain.args.firstOrNull() as? List<MediaSession.QueueItem>
                    ?: return@installArgumentRewriteHook null
                val mediaIds = queue.mapNotNullTo(mutableSetOf()) { queueItem ->
                    frameworkQueueItemMediaId(queueItem)
                }
                currentFrameworkMediaQueueRefresh = FrameworkMediaQueueRefresh(
                    session = WeakReference(session),
                    queue = queue.toList(),
                    mediaIds = mediaIds,
                )
                val rewritten = rewriteFrameworkMediaQueue(queue)
                    ?: return@installArgumentRewriteHook null
                ProviderLogger.info(
                    "Apple MediaSession 队列已覆盖: items=${rewritten.size}"
                )
                arrayOf(rewritten)
            }
            ProviderLogger.info("Apple MediaSession 队列 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple MediaSession 队列 Hook 安装失败", it)
        }
    }

    private fun hookPlaybackNotificationMetadata() {
        runCatching {
            listOf(
                "setContentTitle" to true,
                "setContentText" to false,
            ).forEach { (methodName, title) ->
                val method = Notification.Builder::class.java.getDeclaredMethod(
                    methodName,
                    CharSequence::class.java,
                ).also { it.isAccessible = true }
                hookRegistrar.installArgumentRewrite(method) { chain ->
                    val value = chain.args.firstOrNull() as? CharSequence
                        ?: return@installArgumentRewriteHook null
                    val rewritten = rewritePlaybackNotificationText(value, title)
                    if (rewritten == value) null else arrayOf(rewritten)
                }
            }
            val buildMethod = Notification.Builder::class.java.getDeclaredMethod("build")
                .also { it.isAccessible = true }
            hookRegistrar.installResultOverride(buildMethod) { _, original ->
                (original as? Notification)?.let(::rewriteMediaNotificationContentIntent)
                original
            }
            ProviderLogger.info("Apple Music 媒体通知元数据及点击入口 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music 媒体通知元数据及点击入口 Hook 安装失败", it)
        }
    }

    private fun rewriteMediaNotificationContentIntent(notification: Notification) {
        if (!shouldOpenFullPlayerFromMediaNotification()) return
        val hasMediaSession = notification.extras?.let { extras ->
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                extras.containsKey("androidx.media3.session")
        } == true
        if (!shouldOpenFullPlayerFromNotification(notification.category, hasMediaSession)) return
        val intent = Intent().apply {
            component = ComponentName(
                APPLE_MUSIC_PACKAGE,
                APPLE_MUSIC_MAIN_CONTENT_ACTIVITY,
            )
            putExtra(APPLE_MUSIC_SHOW_FULL_PLAYER_EXTRA, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        notification.contentIntent = PendingIntent.getActivity(
            application,
            MEDIA_NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun shouldOpenFullPlayerFromMediaNotification(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER,
        ) == true

    private fun frameworkMediaMetadataId(metadata: MediaMetadata): String? {
        val metadataId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val matchingIds = playbackMetadataAccountValues.entries.mapNotNull { (mediaId, account) ->
            val alias = effectiveInAppMetadataOverride(mediaId)
            val titleMatches = title != null &&
                (title == account.title || title == alias?.title)
            val artistMatches = artist != null &&
                (artist == account.artist || artist == alias?.artist)
            mediaId.takeIf { titleMatches && artistMatches }
        }
        return selectTrustworthyMediaId(metadataId, matchingIds)
    }

    private fun rewriteFrameworkMediaMetadata(
        metadata: MediaMetadata,
        alias: AppleInternalCatalogResolver.Alias,
    ): MediaMetadata? {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        if (
            title == alias.title &&
            artist == alias.artist &&
            (alias.album.isBlank() || album == alias.album)
        ) return null
        return MediaMetadata.Builder(metadata).apply {
            alias.title.takeIf(String::isNotBlank)?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            alias.artist.takeIf(String::isNotBlank)?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            alias.album.takeIf(String::isNotBlank)?.let {
                putString(MediaMetadata.METADATA_KEY_ALBUM, it)
            }
        }.build()
    }

    private fun rewriteFrameworkMediaQueue(
        queue: List<MediaSession.QueueItem>,
    ): List<MediaSession.QueueItem>? {
        var changed = false
        val rewritten = queue.map { queueItem ->
            val mediaId = frameworkQueueItemMediaId(queueItem) ?: return@map queueItem
            val alias = effectiveInAppMetadataOverride(mediaId) ?: return@map queueItem
            val description = queueItem.description
            val title = alias.title.takeIf(String::isNotBlank) ?: description.title
            val artist = alias.artist.takeIf(String::isNotBlank) ?: description.subtitle
            if (title == description.title && artist == description.subtitle) return@map queueItem
            changed = true
            val rewrittenDescription = MediaDescription.Builder().apply {
                setMediaId(description.mediaId)
                setTitle(title)
                setSubtitle(artist)
                setDescription(description.description)
                setIconBitmap(description.iconBitmap)
                setIconUri(description.iconUri)
                setExtras(description.extras)
                setMediaUri(description.mediaUri)
            }.build()
            MediaSession.QueueItem(rewrittenDescription, queueItem.queueId)
        }
        return rewritten.takeIf { changed }
    }

    private fun frameworkQueueItemMediaId(queueItem: MediaSession.QueueItem): String? {
        val description = queueItem.description
        val extras = description.extras
        val candidates = sequenceOf(
            description.mediaId,
            extras?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            extras?.getString(
                "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"
            ),
        )
        candidates.firstOrNull { candidate ->
            !candidate.isNullOrBlank() && candidate.all(Char::isDigit)
        }?.let { return it }

        val title = description.title?.toString()
        val artist = description.subtitle?.toString()
        val matchingIds = playbackMetadataAccountValues.entries.mapNotNull { (mediaId, account) ->
            mediaId.takeIf { account.title == title && account.artist == artist }
        }
        return selectTrustworthyMediaId(
            explicitMediaId = null,
            inferredMediaIds = matchingIds,
        )
    }

    private fun refreshFrameworkMediaSessionMetadata(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        val refresh = currentFrameworkMediaSessionRefresh
            ?.takeIf { it.mediaId == mediaId }
            ?: return
        val session = refresh.session.get() ?: return
        val rewritten = rewriteFrameworkMediaMetadata(refresh.metadata, alias) ?: refresh.metadata
        mainHandler.post {
            runCatching { session.setMetadata(rewritten) }
                .onFailure { ProviderLogger.error("Apple MediaSession 元数据刷新失败", it) }
        }
    }

    private fun refreshFrameworkMediaSessionQueue(mediaId: String) {
        val refresh = currentFrameworkMediaQueueRefresh ?: return
        if (refresh.mediaIds.isNotEmpty() && mediaId !in refresh.mediaIds) return
        val session = refresh.session.get() ?: return
        val rewritten = rewriteFrameworkMediaQueue(refresh.queue) ?: refresh.queue
        mainHandler.post {
            frameworkMediaQueueRefreshInProgress.set(true)
            try {
                session.setQueue(rewritten)
            } catch (throwable: Throwable) {
                ProviderLogger.error("Apple MediaSession 队列刷新失败", throwable)
            } finally {
                frameworkMediaQueueRefreshInProgress.set(false)
            }
        }
    }

    private fun restoreFrameworkMediaSessionMetadata() {
        val refresh = currentFrameworkMediaSessionRefresh ?: return
        val session = refresh.session.get() ?: return
        runCatching { session.setMetadata(refresh.metadata) }
            .onFailure { ProviderLogger.error("Apple MediaSession 原始元数据恢复失败", it) }
    }

    private fun restoreFrameworkMediaSessionQueue() {
        val refresh = currentFrameworkMediaQueueRefresh ?: return
        val session = refresh.session.get() ?: return
        frameworkMediaQueueRefreshInProgress.set(true)
        try {
            session.setQueue(refresh.queue)
        } catch (throwable: Throwable) {
            ProviderLogger.error("Apple MediaSession 原始队列恢复失败", throwable)
        } finally {
            frameworkMediaQueueRefreshInProgress.set(false)
        }
    }

    private fun rewritePlaybackNotificationText(
        value: CharSequence,
        title: Boolean,
    ): CharSequence {
        val identity = activePlaybackMediaIdentity()
        val mediaId = identity.mediaId
        if (mediaId == null) {
            logMetadataIdentity(
                event = "notification_unresolved",
                identity = identity,
                details = "field=${if (title) "title" else "artist"}, value=$value",
            )
            return value
        }
        val alias = effectiveInAppMetadataOverride(mediaId)
        if (alias == null) {
            logMetadataIdentity(
                event = "notification_alias_miss",
                identity = identity,
                details = "field=${if (title) "title" else "artist"}, value=$value",
            )
            return value
        }
        val account = playbackMetadataAccountValues[mediaId]
        val frameworkMetadata = currentFrameworkMediaSessionRefresh
            ?.takeIf { it.mediaId == mediaId }
            ?.metadata
        val originalValues = if (title) {
            sequenceOf(
                account?.title,
                frameworkMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                frameworkMetadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            )
        } else {
            sequenceOf(
                account?.artist,
                frameworkMetadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                frameworkMetadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            )
        }.filterNotNull().filter(String::isNotBlank).toSet()
        if (value.toString() !in originalValues) {
            logMetadataIdentity(
                event = "notification_value_miss",
                identity = identity,
                details = "field=${if (title) "title" else "artist"}, value=$value, " +
                    "expected=$originalValues",
            )
            return value
        }
        val rewritten = if (title) {
            alias.title.takeIf(String::isNotBlank) ?: value
        } else {
            alias.artist.takeIf(String::isNotBlank) ?: value
        }
        logMetadataIdentity(
            event = "notification_rewrite",
            identity = identity,
            details = "field=${if (title) "title" else "artist"}, before=$value, after=$rewritten",
        )
        return rewritten
    }

    private fun hookInAppMetadata() {
        hookInAppMetadataCapture()
        hookInAppNowPlayingMetadata()
        hookInAppQueueMetadata()
        hookInAppQueueAdapter()
    }

    private fun hookContentItemMetadata() {
        runCatching {
            val baseContentItemClass = classLoader.loadClass(
                "com.apple.android.music.model.BaseContentItem"
            )
            baseContentItemClass.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                hookRegistrar.install(constructor, after = { chain, _ ->
                    chain.thisObject?.javaClass?.let(::ensureContentItemMetadataHooks)
                })
            }
            listOf(
                "com.apple.android.music.model.BaseContentItem",
                "com.apple.android.music.model.BasePlaybackItem",
                "com.apple.android.music.model.Song",
                "com.apple.android.music.model.AlbumCollectionItem",
                "com.apple.android.music.model.ArtistCollectionItem",
                "com.apple.android.music.model.MusicVideo",
            ).forEach { className ->
                runCatching { classLoader.loadClass(className) }
                    .getOrNull()
                    ?.let(::ensureContentItemMetadataHooks)
            }
            ProviderLogger.info(
                "Apple Music 内容项 title/artist/album 模型 Hook 已安装"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 内容项元数据 Hook 安装失败", it)
        }
    }

    private fun hookInAppLibraryEntities() {
        runCatching {
            val modelAlbumClass =
                classLoader.loadClass("com.apple.android.music.model.AlbumCollectionItem")
            val modelSongClass = classLoader.loadClass("com.apple.android.music.model.Song")
            val mediaApiSongClass =
                classLoader.loadClass("com.apple.android.music.mediaapi.models.Song")
            val libraryAlbumClass =
                classLoader.loadClass("com.apple.android.music.mediaapi.models.LibraryAlbum")
            val librarySongClass =
                classLoader.loadClass("com.apple.android.music.mediaapi.models.LibrarySong")

            val albumConstructor = libraryAlbumClass
                .getDeclaredConstructor(modelAlbumClass)
                .apply { isAccessible = true }
            hookRegistrar.install(
                albumConstructor,
                before = { chain -> primeInAppLibrarySource(chain.args.firstOrNull()) },
                after = { chain, _ ->
                    val entity = chain.thisObject ?: return@installHook
                    val source = chain.args.firstOrNull() ?: return@installHook
                    val mediaId = contentItemMediaId(source) ?: return@installHook
                    registerInAppLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.ALBUM,
                        requestResolution = false,
                    )
                },
            )

            val songConstructor = mediaApiSongClass
                .getDeclaredConstructor(modelSongClass)
                .apply { isAccessible = true }
            hookRegistrar.install(
                songConstructor,
                before = { chain -> primeInAppLibrarySource(chain.args.firstOrNull()) },
                after = { chain, _ ->
                    val entity = chain.thisObject ?: return@installHook
                    val source = chain.args.firstOrNull() ?: return@installHook
                    val mediaId = contentItemMediaId(source) ?: return@installHook
                    inAppMediaApiSongIds[entity] = mediaId
                    registerInAppLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.SONG,
                        requestResolution = false,
                    )
                },
            )

            val librarySongConstructor = librarySongClass
                .getDeclaredConstructor(mediaApiSongClass)
                .apply { isAccessible = true }
            hookRegistrar.install(librarySongConstructor, after = { chain, _ ->
                val entity = chain.thisObject ?: return@installHook
                val source = chain.args.firstOrNull() ?: return@installHook
                val mediaId = inAppMediaApiSongIds[source]
                    ?: mediaApiEntityCatalogId(source)
                    ?: return@installHook
                registerInAppLibraryEntity(
                    mediaId = mediaId,
                    entity = entity,
                    kind = InAppLibraryEntityKind.SONG,
                    requestResolution = false,
                )
            })

            val explicitlyHookedConstructors = setOf(
                albumConstructor,
                songConstructor,
                librarySongConstructor,
            )
            val mediaApiEntityClasses = listOf(
                "com.apple.android.music.mediaapi.models.Song" to
                    InAppLibraryEntityKind.SONG,
                "com.apple.android.music.mediaapi.models.LibrarySong" to
                    InAppLibraryEntityKind.SONG,
                "com.apple.android.music.mediaapi.models.Album" to
                    InAppLibraryEntityKind.ALBUM,
                "com.apple.android.music.mediaapi.models.LibraryAlbum" to
                    InAppLibraryEntityKind.ALBUM,
                "com.apple.android.music.mediaapi.models.Artist" to
                    InAppLibraryEntityKind.ARTIST,
                "com.apple.android.music.mediaapi.models.LibraryArtist" to
                    InAppLibraryEntityKind.ARTIST,
            )
            var deserializationConstructors = 0
            mediaApiEntityClasses.forEach { (className, kind) ->
                val entityClass = classLoader.loadClass(className)
                entityClass.declaredConstructors
                    .filterNot(explicitlyHookedConstructors::contains)
                    .forEach { constructor ->
                        constructor.isAccessible = true
                        hookRegistrar.install(constructor, after = { chain, _ ->
                            val entity = chain.thisObject ?: return@installHook
                            val attributes = mediaApiEntityAttributes(entity)
                                ?: return@installHook
                            val mediaId = mediaApiEntityCatalogId(entity, attributes)
                                ?: return@installHook
                            registerInAppLibraryEntity(
                                mediaId = mediaId,
                                entity = entity,
                                kind = kind,
                                knownAttributes = attributes,
                                requestResolution = false,
                                retainEntityRef = true,
                            )
                        })
                        deserializationConstructors += 1
                    }
            }
            ProviderLogger.info(
                "Apple Music 资料库媒体快照 Hook 已安装: " +
                    "album=true, song=true, " +
                    "deserializationConstructors=$deserializationConstructors"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 资料库媒体快照 Hook 安装失败", it)
        }
    }

    private fun hookCollectionPageMetadataRefresh() {
        val recyclerClass = runCatching {
            classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
        }.getOrElse {
            ProviderLogger.error("Apple Music 页面 RecyclerView 类解析失败", it)
            return
        }
        val mediaEntityClass = runCatching {
            classLoader.loadClass("com.apple.android.music.mediaapi.models.MediaEntity")
        }.getOrElse {
            ProviderLogger.error("Apple Music 页面 MediaEntity 类解析失败", it)
            return
        }
        hookCollectionPageRowBinding(mediaEntityClass)

        runCatching {
            val albumClass =
                classLoader.loadClass("com.apple.android.music.mediaapi.models.Album")
            val controllerClass = classLoader.loadClass(
                "com.apple.android.music.collection.mediaapi.controller.AlbumPageController"
            )
            val buildMethod = controllerClass.declaredMethods.singleOrNull { method ->
                val parameterTypes = method.parameterTypes
                !method.isBridge &&
                    parameterTypes.size == 2 &&
                    albumClass.isAssignableFrom(parameterTypes[0]) &&
                    Set::class.java.isAssignableFrom(parameterTypes[1])
            }?.apply { isAccessible = true }
                ?: error("AlbumPageController data build method not found")
            val trackBuildMethod = controllerClass.declaredMethods.singleOrNull { method ->
                val parameterTypes = method.parameterTypes
                parameterTypes.size == 2 &&
                    albumClass.isAssignableFrom(parameterTypes[0]) &&
                    parameterTypes[1].isArray &&
                    parameterTypes[1].componentType?.let(
                        mediaEntityClass::isAssignableFrom
                    ) == true
            }?.apply { isAccessible = true }
                ?: error("AlbumPageController track build method not found")
            val headerBuildMethod = controllerClass.declaredMethods.singleOrNull { method ->
                method.name == "buildHeaderModelInternal" &&
                    method.parameterTypes.contentEquals(arrayOf(albumClass))
            }?.apply { isAccessible = true }
                ?: error("AlbumPageController.buildHeaderModelInternal not found")
            val directHeaderClass = classLoader.loadClass("com.apple.android.music.j")
            val directHeaderConstructor = directHeaderClass.declaredConstructors
                .singleOrNull { it.parameterCount == 0 }
                ?.apply { isAccessible = true }
                ?: error("Direct album header constructor not found")

            hookRegistrar.install(
                buildMethod,
                before = { chain ->
                    val controller = chain.thisObject ?: return@installHook
                    val album = chain.args.getOrNull(0) ?: return@installHook
                    val attributes = mediaApiEntityAttributes(album)
                    val mediaId = attributes?.let { mediaApiEntityCatalogId(album, it) }
                        ?: mediaApiEntityCatalogId(album)
                    albumPageBuildData[controller] = AlbumPageBuildData(
                        album = album,
                        selectedItemIds = chain.args.getOrNull(1),
                        mediaId = mediaId,
                    )
                    if (mediaId != null) {
                        registerInAppLibraryEntity(
                            mediaId = mediaId,
                            entity = album,
                            kind = InAppLibraryEntityKind.ALBUM,
                            knownAttributes = attributes,
                            requestResolution = false,
                            retainEntityRef = true,
                        )
                        registerInAppLibraryController(mediaId, controller)
                    }
                },
                after = { chain, _ ->
                    val controller = chain.thisObject ?: return@installHook
                    val buildData = albumPageBuildData[controller] ?: return@installHook
                    recordInAppLibraryControllerBuildAliases(
                        controller = controller,
                        mediaIds = listOfNotNull(buildData.mediaId) + buildData.trackMediaIds,
                        replace = true,
                    )
                },
            )
            hookRegistrar.installScoped(
                executable = headerBuildMethod,
                enter = { chain ->
                    val album = chain.args.firstOrNull() ?: return@installScopedHook false
                    val mediaId = inAppLibraryEntityIds[album]
                        ?: mediaApiEntityCatalogId(album)
                        ?: return@installScopedHook false
                    activeAlbumHeaderBuildCaptures.push(
                        AlbumHeaderBuildCapture(mediaId = mediaId)
                    )
                    true
                },
                after = { _, result ->
                    val mediaId = activeAlbumHeaderBuildCaptures.current?.mediaId
                        ?: return@installScopedHook
                    result?.let { model -> inAppAlbumHeaderModelIds[model] = mediaId }
                },
                exit = { activeAlbumHeaderBuildCaptures.pop() },
            )
            hookRegistrar.install(directHeaderConstructor, after = { chain, _ ->
                val mediaId = activeAlbumHeaderBuildCaptures.current?.mediaId
                    ?: return@installHook
                val model = chain.thisObject ?: return@installHook
                inAppAlbumHeaderModelIds[model] = mediaId
            })
            hookRegistrar.install(trackBuildMethod, before = { chain ->
                val controller = chain.thisObject ?: return@installHook
                val tracks = chain.args.getOrNull(1) as? Array<*> ?: return@installHook
                val trackMediaIds = registerCollectionPageSongEntities(
                    controller,
                    tracks.asList(),
                )
                synchronized(albumPageBuildData) {
                    albumPageBuildData[controller]?.let { buildData ->
                        albumPageBuildData[controller] = buildData.copy(
                            trackMediaIds = trackMediaIds,
                        )
                    }
                }
            })
            hookMetadataPageControllerLifecycle(controllerClass, recyclerClass)
            ProviderLogger.info(
                "Apple Music 专辑页实时元数据 Hook 已安装: " +
                    "build=${buildMethod.name}, header=${headerBuildMethod.name}, " +
                    "directHeader=${directHeaderClass.name}, tracks=${trackBuildMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 专辑页实时元数据 Hook 安装失败", it)
        }

        runCatching {
            val controllerClass = classLoader.loadClass(APPLE_MUSIC_PLAYLIST_PAGE_CONTROLLER)
            val buildItemMethod = controllerClass.declaredMethods.singleOrNull { method ->
                val parameterTypes = method.parameterTypes
                method.name == "buildItemModel" &&
                    parameterTypes.size == 2 &&
                    parameterTypes[0] == Int::class.javaPrimitiveType &&
                    mediaEntityClass.isAssignableFrom(parameterTypes[1])
            }?.apply { isAccessible = true }
                ?: error("PlaylistPageController.buildItemModel not found")

            hookRegistrar.install(
                buildItemMethod,
                before = { chain ->
                    val controller = chain.thisObject ?: return@installHook
                    val entity = chain.args.getOrNull(1) ?: return@installHook
                    registerCollectionPageSongEntities(controller, listOf(entity))
                },
                after = { chain, _ ->
                    val controller = chain.thisObject ?: return@installHook
                    val entity = chain.args.getOrNull(1) ?: return@installHook
                    val mediaId = inAppLibraryEntityIds[entity]
                        ?: mediaApiEntityCatalogId(entity)
                        ?: return@installHook
                    recordInAppLibraryControllerBuildAliases(
                        controller = controller,
                        mediaIds = listOf(mediaId),
                        replace = false,
                    )
                },
            )
            hookMetadataPageControllerLifecycle(controllerClass, recyclerClass)
            ProviderLogger.info(
                "Apple Music 歌单页实时元数据 Hook 已安装: " +
                    "buildItem=${buildItemMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 歌单页实时元数据 Hook 安装失败", it)
        }

        runCatching {
            val artistControllerClass = classLoader.loadClass(
                "com.apple.android.music.profiles.ArtistEpoxyController"
            )
            hookMetadataPageControllerLifecycle(artistControllerClass, recyclerClass)
            ProviderLogger.info("Apple Music 歌手页元数据页面边界 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music 歌手页元数据页面边界 Hook 安装失败", it)
        }
    }

    private fun hookCollectionPageRowBinding(mediaEntityClass: Class<*>) {
        runCatching {
            val albumRowClass = classLoader.loadClass("k6.b")
            val playlistRowClass = classLoader.loadClass("k6.d")
            val artistTopSongClass = classLoader.loadClass("com.apple.android.music.h1")
            val artistHeaderClass = classLoader.loadClass("com.apple.android.music.V")
            val resolvedBind = hookResolver.resolveMethod(
                AppleMusicHookPoint.EPOXY_FINAL_BIND
            )
            val bindMethod = resolvedBind.method
            hookRegistrar.install(bindMethod, after = { chain, _ ->
                val model = chain.args.firstOrNull() ?: return@installHook
                when (metadataPageFinalBindingKind(
                    albumHeader = inAppAlbumHeaderModelIds[model] != null,
                    albumRow = albumRowClass.isInstance(model),
                    playlistRow = playlistRowClass.isInstance(model),
                    artistTopSong = artistTopSongClass.isInstance(model),
                    artistHeader = artistHeaderClass.isInstance(model),
                )) {
                    MetadataPageFinalBindingKind.ALBUM_HEADER -> {
                        val mediaId = inAppAlbumHeaderModelIds[model]
                            ?: return@installHook
                        val binding = epoxyDataBindingFromFinalHolder(chain.thisObject)
                        if (binding != null) {
                            beginInAppDataBindingModelBind(binding)
                            captureInAppDataBinding(binding)
                            registerInAppDataBinding(
                                mediaId = mediaId,
                                binding = binding,
                                originalResolutionMode =
                                    InAppOriginalResolutionMode.ORIGINAL_FIRST,
                            )
                        }
                        onAlbumHeaderFinalBound(
                            model = model,
                            mediaId = mediaId,
                            position = chain.args.getOrNull(3) as? Int,
                            binding = binding,
                        )
                    }

                    MetadataPageFinalBindingKind.ALBUM_ROW,
                    MetadataPageFinalBindingKind.PLAYLIST_ROW -> {
                        val entity = collectionPageRowEntity(model, mediaEntityClass)
                            ?: return@installHook
                        val mediaId = inAppLibraryEntityIds[entity]
                            ?: mediaApiEntityCatalogId(entity)
                            ?: return@installHook
                        val pageType = if (albumRowClass.isInstance(model)) {
                            "album"
                        } else {
                            "playlist"
                        }
                        registerInAppLibraryEntity(
                            mediaId = mediaId,
                            entity = entity,
                            kind = InAppLibraryEntityKind.SONG,
                            requestResolution = false,
                            retainEntityRef = true,
                        )
                        recordCurrentRecyclerMediaId(mediaId)
                        if (pageType == "playlist") {
                            registerInAppPlaylistRowBinding(
                                mediaId = mediaId,
                                entity = entity,
                                model = model,
                                finalHolder = chain.thisObject,
                            )
                        }
                        onCollectionPageRowBound(
                            mediaId = mediaId,
                            entity = entity,
                            pageType = pageType,
                        )
                    }

                    MetadataPageFinalBindingKind.ARTIST_TOP_SONG -> {
                        val snapshot = inAppArtistTopSongModels[model]
                            ?: return@installHook
                        val binding = epoxyDataBindingFromFinalHolder(chain.thisObject)
                        if (binding != null) {
                            inAppArtistTopSongBindings[binding] = snapshot
                            captureInAppDataBinding(binding)
                            registerInAppDataBinding(
                                mediaId = snapshot.mediaId,
                                binding = binding,
                                originalResolutionMode =
                                    InAppOriginalResolutionMode.ORIGINAL_FIRST,
                            )
                        }
                        onArtistProfileFinalBound(
                            model = model,
                            mediaId = snapshot.mediaId,
                            bindingKind = MetadataPageFinalBindingKind.ARTIST_TOP_SONG,
                            position = chain.args.getOrNull(3) as? Int,
                            binding = binding,
                        )
                    }

                    MetadataPageFinalBindingKind.ARTIST_HEADER -> {
                        val mediaId = artistProfileHeaderMediaId(model)
                            ?: return@installHook
                        inAppArtistHeaderModelIds[model] = mediaId
                        val binding = epoxyDataBindingFromFinalHolder(chain.thisObject)
                        if (binding != null) {
                            inAppArtistHeaderBindingIds[binding] = mediaId
                            captureInAppDataBinding(binding)
                            registerInAppDataBinding(
                                mediaId = mediaId,
                                binding = binding,
                                originalResolutionMode =
                                    InAppOriginalResolutionMode.ORIGINAL_FIRST,
                            )
                        }
                        onArtistProfileFinalBound(
                            model = model,
                            mediaId = mediaId,
                            bindingKind = MetadataPageFinalBindingKind.ARTIST_HEADER,
                            position = chain.args.getOrNull(3) as? Int,
                            binding = binding,
                        )
                    }

                    null -> Unit
                }
            })
            ProviderLogger.info(
                "Apple Music 详情页最终绑定元数据 Hook 已安装: " +
                    "holder=${resolvedBind.target.className}, method=${bindMethod.name}, " +
                    "fallback=${resolvedBind.compatibilityFallback}, models=5"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 详情页最终绑定 Hook 安装失败", it)
        }
    }

    private fun collectionPageRowEntity(model: Any, mediaEntityClass: Class<*>): Any? =
        generateSequence(model.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { field -> mediaEntityClass.isAssignableFrom(field.type) }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(model)
                }.getOrNull()
            }

    /**
     * 歌单行模型会把歌曲名和副标题复制到自身字段，单纯修改 MediaEntity 后再
     * notifyItemChanged 仍可能重新绑定旧字符串。这里在已经完成的最终 bind 后
     * 保存当前行的精确 TextView，使每首歌曲的查询结果可以按返回顺序立即覆盖。
     */
    private fun registerInAppPlaylistRowBinding(
        mediaId: String,
        entity: Any,
        model: Any,
        finalHolder: Any?,
    ) {
        val root = finalHolder?.let(::recyclerViewHolderItemView) ?: return
        val attributes = mediaApiEntityAttributes(entity)
        val modelTitle = reflectiveStringField(model, "M")
            ?: attributes?.let { mediaApiAttribute(it, "getName") }
        val modelSubtitle = reflectiveStringField(model, "P")
        val accountArtist = playbackMetadataAccountValues[mediaId]?.artist
            ?: attributes?.let { mediaApiAttribute(it, "getArtistName") }
        val textViews = descendantTextViews(root)
        val titleView = findRenderedTextView(
            textViews = textViews,
            expected = modelTitle,
        )
        val subtitleView = findRenderedTextView(
            textViews = textViews,
            expected = modelSubtitle,
            excluded = titleView,
        ) ?: findContainingTextView(
            textViews = textViews,
            expectedPart = accountArtist,
            excluded = titleView,
        )

        inAppPlaylistRowRootMediaIds[root] = mediaId
        val refs = inAppPlaylistRowRefs.getOrPut(mediaId) {
            ConcurrentLinkedQueue()
        }
        refs.forEach { ref ->
            val targetRoot = ref.root.get()
            if (targetRoot == null || targetRoot === root) {
                refs.remove(ref)
            }
        }
        val rowRef = InAppPlaylistRowRef(
            root = WeakReference(root),
            title = titleView?.let(::WeakReference),
            subtitle = subtitleView?.let(::WeakReference),
            entity = WeakReference(entity),
            originalSubtitle = modelSubtitle,
            originalArtist = accountArtist,
        )
        refs.add(rowRef)
        effectiveInAppMetadataOverride(mediaId)?.let { alias ->
            applyAliasToInAppPlaylistRow(mediaId, rowRef, alias)
        }
        if (BuildConfig.DEBUG) {
            logMetadataIdentity(
                event = "playlist_row_direct_bound",
                details = "contentId=$mediaId, " +
                    "titleView=${titleView != null}, subtitleView=${subtitleView != null}, " +
                    "model=$modelTitle/$modelSubtitle, accountArtist=$accountArtist",
            )
        }
    }

    private fun reflectiveStringField(instance: Any, name: String): String? =
        runCatching { AppleReflection.field(instance, name)?.toString() }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun descendantTextViews(root: View): List<TextView> {
        val textViews = mutableListOf<TextView>()
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 64) {
            val view = pending.removeFirst()
            visited += 1
            if (view is TextView) textViews += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let(pending::addLast)
                }
            }
        }
        return textViews
    }

    private fun findRenderedTextView(
        textViews: Collection<TextView>,
        expected: String?,
        excluded: TextView? = null,
    ): TextView? {
        val normalized = expected?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return textViews.firstOrNull { view ->
            view !== excluded && view.text?.toString()?.trim() == normalized
        }
    }

    private fun findContainingTextView(
        textViews: Collection<TextView>,
        expectedPart: String?,
        excluded: TextView? = null,
    ): TextView? {
        val normalized = expectedPart?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return textViews.firstOrNull { view ->
            view !== excluded &&
                view.text?.toString()?.contains(normalized, ignoreCase = true) == true
        }
    }

    private fun refreshInAppPlaylistRowRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): Int {
        val refs = inAppPlaylistRowRefs[mediaId] ?: return 0
        var completeTargets = 0
        refs.forEach { ref ->
            val root = ref.root.get()
            if (root == null || inAppPlaylistRowRootMediaIds[root] != mediaId) {
                refs.remove(ref)
                return@forEach
            }
            if (applyAliasToInAppPlaylistRow(mediaId, ref, alias)) {
                completeTargets += 1
            }
        }
        return completeTargets
    }

    private fun applyAliasToInAppPlaylistRow(
        mediaId: String,
        ref: InAppPlaylistRowRef,
        alias: AppleInternalCatalogResolver.Alias,
    ): Boolean {
        val root = ref.root.get() ?: return false
        if (inAppPlaylistRowRootMediaIds[root] != mediaId) return false
        val titleApplied = if (alias.title.isBlank()) {
            true
        } else {
            ref.title?.get()?.let { titleView ->
                applyPlaylistRowTitle(
                    titleView = titleView,
                    title = alias.title,
                    entity = ref.entity.get(),
                )
                true
            } ?: false
        }
        val subtitleApplied = if (alias.artist.isBlank()) {
            true
        } else {
            ref.subtitle?.get()?.let { subtitleView ->
                subtitleView.text = artistProfileSubtitleWithArtist(
                    originalSubtitle = ref.originalSubtitle,
                    originalArtist = ref.originalArtist,
                    replacementArtist = alias.artist,
                )
                true
            } ?: false
        }
        if (BuildConfig.DEBUG && (titleApplied || subtitleApplied)) {
            logMetadataIdentity(
                event = "playlist_row_direct_applied",
                details = "contentId=$mediaId, titleApplied=$titleApplied, " +
                    "subtitleApplied=$subtitleApplied, alias=${alias.title}/${alias.artist}",
            )
        }
        return titleApplied && subtitleApplied
    }

    private fun applyPlaylistRowTitle(
        titleView: TextView,
        title: String,
        entity: Any?,
    ) {
        val explicit = entity?.let {
            runCatching { AppleReflection.call(it, "isExplicit") as? Boolean }
                .getOrNull()
        } == true
        val formatter = playlistExplicitTitleFormatterClass
            ?: hookResolver.resolveClasses(AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS)
                .firstOrNull()
                ?.clazz
                ?.also { playlistExplicitTitleFormatterClass = it }
        val formatted = formatter != null && runCatching {
            AppleReflection.callStatic(formatter, "c", titleView, title, explicit)
        }.isSuccess
        if (!formatted) titleView.text = title
    }

    private fun onCollectionPageRowBound(
        mediaId: String,
        entity: Any,
        pageType: String,
    ) {
        val controllers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        inAppLibraryControllerRefs[mediaId]?.forEach { ref ->
            val controller = ref.get()
            if (controller == null) {
                inAppLibraryControllerRefs[mediaId]?.remove(ref)
            } else {
                controllers.add(controller)
            }
        }
        var shouldResolve = controllers.isEmpty()
        controllers.forEach { controller ->
            val newlyBound = synchronized(collectionPageBoundResolutionStates) {
                collectionPageBoundResolutionStates.getOrPut(controller) {
                    CollectionPageBoundResolutionState()
                }.requestedMediaIds.add(mediaId)
            }
            shouldResolve = shouldResolve || newlyBound
        }
        if (!shouldResolve) return

        mainHandler.post {
            markMetadataVisible(listOf(mediaId))
            enrichInAppLibraryEntitiesForResolution(listOf(mediaId))
            val alias = effectiveInAppMetadataOverride(mediaId)
            if (alias != null) {
                applyAliasToInAppMetadataRefs(
                    mediaId = mediaId,
                    alias = alias,
                    forceRebind = true,
                    notifyModelChange = true,
                )
            }
            scheduleInAppMetadataResolution(
                mediaIds = listOf(mediaId),
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                originalResolutionMode =
                    collectionPageOriginalResolutionMode(pageType),
            )
            if (BuildConfig.DEBUG) {
                val attributes = mediaApiEntityAttributes(entity)
                val entityName = attributes?.let {
                    runCatching { AppleReflection.call(it, "getName") as? String }.getOrNull()
                }
                val entityArtist = attributes?.let {
                    runCatching { AppleReflection.call(it, "getArtistName") as? String }.getOrNull()
                }
                ProviderLogger.info(
                    "Apple Music 元数据链路: event=collection_page_row_bound, " +
                        "contentId=$mediaId, pageType=$pageType, controllers=${controllers.size}, " +
                        "entity=$entityName/$entityArtist, " +
                        "effective=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                        "request=${shouldRequestInAppMetadataOverride(mediaId)}"
                )
            }
        }
    }

    private fun onAlbumHeaderFinalBound(
        model: Any,
        mediaId: String,
        position: Int?,
        binding: Any?,
    ) {
        val shouldResolve = albumHeaderFinalBoundResolutionIds[model] != mediaId
        albumHeaderFinalBoundResolutionIds[model] = mediaId
        mainHandler.post {
            markMetadataVisible(listOf(mediaId))
            enrichInAppLibraryEntitiesForResolution(listOf(mediaId))
            val alias = effectiveInAppMetadataOverride(mediaId)
            val shouldRequest = shouldResolve &&
                shouldRequestInAppMetadataOverride(mediaId)
            var aliasAlreadyRendered = false
            if (alias != null) {
                val appliedAlias = AppliedMetadataAlias(mediaId, alias)
                val root = binding?.let { inAppDataBindingRootViews[it]?.get() }
                val values = dataBindingAliasValues(mediaId, alias, binding)
                aliasAlreadyRendered = binding != null &&
                    inAppDataBindingMediaIds[binding] == mediaId &&
                    root != null &&
                    dataBindingAliasAlreadyRendered(
                        expectedTitle = values.title,
                        expectedSubtitle = values.subtitle,
                        renderedTexts = dataBindingRenderedTexts(root),
                    )
                if (aliasAlreadyRendered) {
                    inAppDataBindingAppliedAliases[binding] = appliedAlias
                } else {
                    refreshInAppDataBindingRefs(mediaId, alias)
                }
            }
            if (shouldRequest) {
                scheduleInAppMetadataResolution(
                    mediaIds = listOf(mediaId),
                    priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                )
            }
            if (BuildConfig.DEBUG) {
                val root = binding?.let { inAppDataBindingRootViews[it]?.get() }
                ProviderLogger.info(
                    "Apple Music 元数据链路: " +
                        "seq=${metadataTraceSequence.incrementAndGet()}, " +
                        "event=album_header_final_bound, contentId=$mediaId, " +
                        "position=$position, " +
                        "model=${model.javaClass.name}@${System.identityHashCode(model)}, " +
                        "binding=${binding?.javaClass?.name}@" +
                        "${binding?.let(System::identityHashCode)}, " +
                        "rootVisible=${root?.let(::isVisibleBindingRoot) == true}, " +
                        "effective=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                        "alreadyRendered=$aliasAlreadyRendered, request=$shouldRequest, " +
                        "texts=${root?.let(::debugTextSnapshot)}"
                )
            }
        }
    }

    private fun onArtistProfileFinalBound(
        model: Any,
        mediaId: String,
        bindingKind: MetadataPageFinalBindingKind,
        position: Int?,
        binding: Any?,
    ) {
        val shouldResolve = artistProfileFinalBoundResolutionIds[model] != mediaId
        artistProfileFinalBoundResolutionIds[model] = mediaId
        mainHandler.post {
            markMetadataVisible(listOf(mediaId))
            enrichInAppLibraryEntitiesForResolution(listOf(mediaId))
            val alias = effectiveInAppMetadataOverride(mediaId)
            val shouldRequest = shouldResolve &&
                shouldRequestInAppMetadataOverride(mediaId)
            if (alias != null) {
                applyAliasToInAppMetadataRefs(
                    mediaId = mediaId,
                    alias = alias,
                    forceRebind = true,
                    notifyModelChange = false,
                )
            }
            if (shouldRequest) {
                scheduleInAppMetadataResolution(
                    mediaIds = listOf(mediaId),
                    priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                )
            }
            if (BuildConfig.DEBUG) {
                val root = binding?.let { inAppDataBindingRootViews[it]?.get() }
                ProviderLogger.info(
                    "Apple Music 元数据链路: " +
                        "seq=${metadataTraceSequence.incrementAndGet()}, " +
                        "event=artist_profile_final_bound, contentId=$mediaId, " +
                        "kind=$bindingKind, position=$position, " +
                        "model=${model.javaClass.name}@${System.identityHashCode(model)}, " +
                        "binding=${binding?.javaClass?.name}@" +
                        "${binding?.let(System::identityHashCode)}, " +
                        "rootVisible=${root?.let(::isVisibleBindingRoot) == true}, " +
                        "effective=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                        "request=$shouldRequest"
                )
            }
        }
    }

    private fun hookMetadataPageControllerLifecycle(
        controllerClass: Class<*>,
        recyclerClass: Class<*>,
    ) {
        metadataPageControllerClasses.add(controllerClass)
        val attachedMethod = AppleReflection.findMethod(
            controllerClass,
            "onAttachedToRecyclerView",
            parameterTypes = listOf(recyclerClass),
        )
        val detachedMethod = AppleReflection.findMethod(
            controllerClass,
            "onDetachedFromRecyclerView",
            parameterTypes = listOf(recyclerClass),
        )
        if (metadataPageLifecycleHookedMethods.add(attachedMethod)) {
            hookRegistrar.install(attachedMethod, after = { chain, _ ->
                val owner = chain.thisObject ?: return@installHook
                if (metadataPageControllerClasses.none { it.isInstance(owner) }) {
                    return@installHook
                }
                val recycler = chain.args.firstOrNull() as? RecyclerView
                    ?: return@installHook
                onMetadataPageAttached(owner, recycler)
            })
        }
        if (metadataPageLifecycleHookedMethods.add(detachedMethod)) {
            hookRegistrar.install(detachedMethod, before = { chain ->
                val owner = chain.thisObject ?: return@installHook
                if (metadataPageControllerClasses.any { it.isInstance(owner) }) {
                    onMetadataPageDetached(owner)
                }
            })
        }
    }

    private fun registerCollectionPageSongEntities(
        controller: Any,
        entities: Collection<Any?>,
    ): Set<String> = buildSet {
        entities.forEach { entity ->
            entity ?: return@forEach
            val mediaId = inAppLibraryEntityIds[entity]
                ?: mediaApiEntityCatalogId(entity)
                ?: return@forEach
            registerInAppLibraryEntity(
                mediaId = mediaId,
                entity = entity,
                kind = InAppLibraryEntityKind.SONG,
                requestResolution = false,
                retainEntityRef = true,
            )
            registerInAppLibraryController(mediaId, controller)
            add(mediaId)
        }
    }

    private fun mediaApiEntityCatalogId(
        entity: Any,
        knownAttributes: Any? = null,
    ): String? = mediaApiEntityLookupIds(entity, knownAttributes).firstOrNull()

    /**
     * A Media API entity can expose the same catalog item through more than one numeric ID.
     * Apple has changed which one is returned by [getId] versus playParams.catalogId across
     * collection and Listen Now surfaces. Keep the selected catalog ID as the primary media ID,
     * but retain every equivalent numeric ID for persistent original-metadata cache probes.
     */
    private fun mediaApiEntityLookupIds(
        entity: Any,
        knownAttributes: Any? = null,
    ): Set<String> = buildSet {
        fun addValue(value: Any?) {
            when (value) {
                is Array<*> -> value.forEach(::addValue)
                is Iterable<*> -> value.forEach(::addValue)
                else -> value?.toString()?.trim()?.takeIf { candidate ->
                    candidate.isNotEmpty() && candidate.all(Char::isDigit)
                }?.let(::add)
            }
        }

        val attributes = knownAttributes ?: mediaApiEntityAttributes(entity)
        val playParams = attributes?.let {
            runCatching { AppleReflection.call(it, "getPlayParams") }.getOrNull()
        }
        addValue(playParams?.let {
            runCatching { AppleReflection.call(it, "getCatalogId") }.getOrNull()
        })
        listOf(
            "getId",
            "getSubscriptionStoreId",
            "getAssetAdamId",
            "getReportingAdamId",
        ).forEach { methodName ->
            addValue(runCatching { AppleReflection.call(entity, methodName) }.getOrNull())
        }
        addValue(runCatching { AppleReflection.call(entity, "getFormerIds") }.getOrNull())
    }

    private fun primeInAppLibrarySource(source: Any?) {
        source ?: return
        val mediaId = contentItemMediaId(source) ?: return
        registerInAppPlaybackItem(
            mediaId = mediaId,
            playbackItem = source,
            notifyChange = false,
            analyzeMetadata = false,
        )
        effectiveInAppMetadataOverride(mediaId)?.let { alias ->
            applyAliasToInAppPlaybackItem(source, alias, notifyChange = false)
        }
    }

    private fun registerInAppLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        knownAttributes: Any? = null,
        requestResolution: Boolean = true,
        retainEntityRef: Boolean = true,
    ) {
        val attributes = knownAttributes ?: mediaApiEntityAttributes(entity) ?: return
        val binding = InAppMediaApiAttributeBinding(mediaId, kind)
        if (inAppLibraryEntityIds[entity] == mediaId &&
            inAppMediaApiAttributeBindings[attributes] == binding &&
            !requestResolution &&
            !retainEntityRef
        ) return
        inAppLibraryEntityIds[entity] = mediaId
        inAppLibraryEntityAttributes[entity] = attributes
        val snapshot = AppleMediaApiAttributeSnapshots.remember(
            attributes = attributes,
            name = mediaApiAttribute(attributes, "getName"),
            artistName = mediaApiAttribute(attributes, "getArtistName"),
            albumName = mediaApiAttribute(attributes, "getAlbumName"),
        )
        val originalName = snapshot.name
        val originalArtist = snapshot.artistName
        val originalAlbum = snapshot.albumName
        registerInAppMediaApiAttributes(mediaId, attributes, kind)
        val entityType = localizedEntityTypeForInAppLibraryKind(kind)
        playbackMetadataEntityTypes[mediaId] = entityType
        val lookupIds = mediaApiEntityLookupIds(entity, attributes) + mediaId
        playbackMetadataLookupIds.merge(mediaId, lookupIds) { previous, incoming ->
            previous + incoming
        }
        mergePlaybackAccountMetadata(
            mediaId = mediaId,
            title = originalName,
            artist = originalArtist,
            reconcileArtistAssociations = false,
        )
        if (retainEntityRef) {
            val refs = inAppLibraryEntityRefs.computeIfAbsent(mediaId) {
                ConcurrentLinkedQueue()
            }
            var registered = false
            refs.forEach { ref ->
                val target = ref.entity.get()
                if (target == null) {
                    refs.remove(ref)
                } else if (target === entity) {
                    registered = true
                }
            }
            if (!registered) {
                refs.add(
                    InAppLibraryEntityRef(
                        entity = WeakReference(entity),
                        kind = kind,
                        originalName = originalName,
                        originalArtist = originalArtist,
                        originalAlbum = originalAlbum,
                    )
                )
            }
        }
        if (retainEntityRef) {
            effectiveInAppMetadataOverride(mediaId)?.let { alias ->
                applyAliasToInAppLibraryEntity(entity, kind, alias)
            }
        }
        if (requestResolution &&
            requestPriorityForMediaId(mediaId) ==
            AppleInternalCatalogResolver.RequestPriority.VISIBLE
        ) {
            enrichInAppLibraryEntity(mediaId, entity, kind, attributes)
            scheduleInAppMetadataResolution(
                mediaIds = listOf(mediaId),
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            )
        }
    }

    private fun enrichInAppLibraryEntitiesForResolution(mediaIds: Collection<String>) {
        normalizedRecyclerBindingMediaIds(mediaIds).forEach { mediaId ->
            inAppLibraryEntityRefs[mediaId]?.forEach { ref ->
                val entity = ref.entity.get()
                if (entity == null) {
                    inAppLibraryEntityRefs[mediaId]?.remove(ref)
                    return@forEach
                }
                val attributes = inAppLibraryEntityAttributes[entity]
                    ?: mediaApiEntityAttributes(entity)
                    ?: return@forEach
                enrichInAppLibraryEntity(mediaId, entity, ref.kind, attributes)
            }
        }
    }

    private fun enrichInAppLibraryEntity(
        mediaId: String,
        entity: Any,
        kind: InAppLibraryEntityKind,
        attributes: Any,
    ) {
        if (inAppLibraryEntityEnrichedIds[entity] == mediaId) return
        val snapshot = AppleMediaApiAttributeSnapshots.get(attributes)
        val originalName = snapshot?.name ?: mediaApiAttribute(attributes, "getName")
        val originalArtist = snapshot?.artistName
            ?: mediaApiAttribute(attributes, "getArtistName")
        val originalAlbum = snapshot?.albumName
            ?: mediaApiAttribute(attributes, "getAlbumName")
        val attributeArtistIds = mediaApiAttributeArtistIds(attributes)
        val mediaApiArtistKeys = mediaApiArtistAssociationKeys(entity) +
            attributeArtistIds.map { artistId -> "id:$artistId" }
        val catalogArtistIds = libraryAssociatedArtistIds(
            kind = kind,
            mediaId = mediaId,
            attributeArtistIds = attributeArtistIds,
            associationKeys = mediaApiArtistKeys,
        )
        val existingArtistIds = playbackMetadataAssociatedArtistIds[mediaId].orEmpty()
        val fallbackArtistId = if (catalogArtistIds.isEmpty() &&
            existingArtistIds.isEmpty() &&
            kind == InAppLibraryEntityKind.SONG
        ) {
            artistProfileTopSongCandidateArtistIds[mediaId].orEmpty()
                .mapNotNull { profileArtistId ->
                    artistProfileFallbackArtistId(
                        profileArtistId = profileArtistId,
                        existingArtistIds = emptyList(),
                        songArtistCredit = originalArtist,
                        profileArtistCredits = knownArtistProfileCredits(profileArtistId),
                    )
                }
                .distinct()
                .singleOrNull()
        } else {
            null
        }
        val associatedArtistIds =
            (existingArtistIds + catalogArtistIds + listOfNotNull(fallbackArtistId))
                .distinct()
        if (associatedArtistIds.isNotEmpty()) {
            playbackMetadataAssociatedArtistIds.merge(
                mediaId,
                associatedArtistIds,
            ) { previous, incoming ->
                (previous + incoming).distinct()
            }
            trackAssociatedMediaIds(mediaId, associatedArtistIds)
        }
        val associationKeys = libraryEntityAssociationKeys(
            kind = kind,
            name = originalName,
            artist = originalArtist,
            album = originalAlbum,
        ) + mediaApiArtistKeys +
            associatedArtistIds.map { artistId -> "id:$artistId" } +
            if (kind == InAppLibraryEntityKind.ARTIST) {
                setOf("id:$mediaId")
            } else {
                emptySet()
            }
        if (associationKeys.isNotEmpty()) {
            playbackMetadataArtistKeys.merge(mediaId, associationKeys) { previous, incoming ->
                previous + incoming
            }
            if (associatedArtistIds.isNotEmpty()) {
                persistentOriginalArtistKeys(associationKeys).forEach { artistKey ->
                    associatedMediaIdsByArtistKey.computeIfAbsent(artistKey) {
                        ConcurrentHashMap.newKeySet()
                    }.add(mediaId)
                }
            }
            val originalLanguage = originalMetadataOverrides[mediaId]?.language
                ?.takeIf(String::isNotBlank)
            originalLanguage?.takeIf {
                kind != InAppLibraryEntityKind.SONG ||
                    shouldShareOriginalSongLanguage(
                        localizedTitle = originalName,
                        localizedArtist = originalArtist,
                        alias = originalMetadataOverrides[mediaId],
                    )
            }?.let { language ->
                rememberOriginalLanguageForArtist(mediaId, language)
            }
            val genres = mediaApiGenreNames(attributes)
            inferredOriginalArtistLanguage(
                kind = kind,
                artist = originalArtist ?: originalName,
                associatedArtistIds = associatedArtistIds,
                genres = genres,
            )?.let { language ->
                rememberOriginalLanguageForArtist(mediaId, language)
            }
        }
        hydrateSharedArtistOverrides(mediaId)
        artistProfileTopSongCandidateArtistIds.remove(mediaId)
        inAppLibraryEntityEnrichedIds[entity] = mediaId
    }

    private fun registerInAppMediaApiAttributes(
        mediaId: String,
        attributes: Any,
        kind: InAppLibraryEntityKind,
    ) {
        inAppMediaApiAttributeBindings[attributes] = InAppMediaApiAttributeBinding(mediaId, kind)
        listOf("getName", "getArtistName", "getAlbumName").forEach { getter ->
            val method = runCatching {
                AppleReflection.findMethod(attributes.javaClass, getter, parameterCount = 0)
            }.getOrNull() ?: return@forEach
            if (method.returnType != String::class.java ||
                !inAppMediaApiAttributeHookedMethods.add(method)
            ) return@forEach
            hookRegistrar.installResultOverride(method) { chain, original ->
                val target = chain.thisObject ?: return@installResultOverrideHook original
                val binding = inAppMediaApiAttributeBindings[target]
                    ?: return@installResultOverrideHook original
                recordInAppLibraryComposeMediaId(binding.mediaId)
                recordCurrentRecyclerMediaId(binding.mediaId)
                val alias = effectiveInAppMetadataOverride(binding.mediaId)
                val overridden = alias?.let {
                    inAppMediaApiAttributeOverride(binding.kind, getter, it)
                } ?: original
                overridden
            }
            ProviderLogger.info(
                "Apple Music Media API 属性 getter Hook 已安装: " +
                    "class=${method.declaringClass.name}, method=$getter"
            )
        }
    }

    private fun inAppMediaApiAttributeOverride(
        kind: InAppLibraryEntityKind,
        getter: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): String? = when (getter) {
        "getName" -> when (kind) {
            InAppLibraryEntityKind.ALBUM -> alias.album.ifBlank { alias.title }
            InAppLibraryEntityKind.SONG -> alias.title
            InAppLibraryEntityKind.ARTIST -> alias.artist.ifBlank { alias.title }
        }
        "getArtistName" -> alias.artist
        "getAlbumName" -> if (kind == InAppLibraryEntityKind.SONG) alias.album else null
        else -> null
    }.takeIf { !it.isNullOrBlank() }

    private fun libraryEntityAssociationKeys(
        kind: InAppLibraryEntityKind,
        name: String?,
        artist: String?,
        album: String?,
    ): Set<String> = buildSet {
        artist?.takeIf(String::isNotBlank)?.let { value ->
            add("name:${AppleInternalCatalogResolver.normalizedArtistNameKey(value)}")
        }
        val albumName = when (kind) {
            InAppLibraryEntityKind.ALBUM -> name
            InAppLibraryEntityKind.SONG -> album
            InAppLibraryEntityKind.ARTIST -> null
        }
        albumName?.takeIf(String::isNotBlank)?.let { value ->
            add("album:${AppleInternalCatalogResolver.normalizedArtistNameKey(value)}")
        }
        if (kind == InAppLibraryEntityKind.ARTIST) {
            name?.takeIf(String::isNotBlank)?.let { value ->
                add("name:${AppleInternalCatalogResolver.normalizedArtistNameKey(value)}")
            }
        }
    }

    private fun mediaApiArtistAssociationKeys(entity: Any): Set<String> = buildSet {
        val relationships = runCatching {
            AppleReflection.call(entity, "getRelationships") as? Map<*, *>
        }.getOrNull() ?: return@buildSet
        val relationship = relationships["artists"] ?: relationships["artist"]
            ?: return@buildSet
        val rawArtists = runCatching {
            AppleReflection.call(relationship, "getEntities")
                ?: AppleReflection.call(relationship, "getData")
        }.getOrNull() ?: return@buildSet
        val artists: Iterable<*> = when (rawArtists) {
            is Iterable<*> -> rawArtists
            is Array<*> -> rawArtists.asIterable()
            is Map<*, *> -> rawArtists.values
            else -> return@buildSet
        }
        artists.forEach { artistEntity ->
            artistEntity ?: return@forEach
            mediaApiEntityCatalogId(artistEntity)?.let { artistId -> add("id:$artistId") }
            mediaApiEntityAttributes(artistEntity)
                ?.let { attributes -> mediaApiAttribute(attributes, "getName") }
                ?.takeIf(String::isNotBlank)
                ?.let { artistName ->
                    add(
                        "name:${AppleInternalCatalogResolver.normalizedArtistNameKey(artistName)}"
                    )
                }
        }
    }

    internal fun mediaApiAttributeArtistIds(attributes: Any?): List<String> {
        attributes ?: return emptyList()
        return listOf("getArtistId", "getArtistAdamId", "getArtistStoreId")
            .mapNotNull { getter ->
                runCatching { AppleReflection.call(attributes, getter) }
                    .getOrNull()
                    ?.toString()
                    ?.trim()
                    ?.takeIf { value ->
                        value.isNotEmpty() && value != "0" && value.all(Char::isDigit)
                    }
            }
            .distinct()
    }

    internal fun libraryAssociatedArtistIds(
        kind: InAppLibraryEntityKind,
        mediaId: String,
        attributeArtistIds: List<String>,
        associationKeys: Set<String>,
    ): List<String> = if (kind == InAppLibraryEntityKind.ARTIST) {
        listOf(mediaId)
    } else {
        (attributeArtistIds + associationKeys.mapNotNull(::artistIdFromAssociationKey)).distinct()
    }

    private fun artistIdFromAssociationKey(key: String): String? = key
        .removePrefix("id:")
        .takeIf { it != key && it.isNotEmpty() && it.all(Char::isDigit) }

    private fun associateArtistProfileTopSongWithProfileArtist(
        controller: Any,
        mediaId: String,
    ) {
        val profileArtistId = artistProfileMediaIds[controller] ?: return
        artistProfileTopSongCandidateArtistIds.computeIfAbsent(mediaId) {
            ConcurrentHashMap.newKeySet()
        }.add(profileArtistId)
    }

    private fun knownArtistProfileCredits(artistId: String): Set<String> = buildSet {
        playbackMetadataAccountValues[artistId]?.let { account ->
            account.title?.takeIf(String::isNotBlank)?.let(::add)
            account.artist?.takeIf(String::isNotBlank)?.let(::add)
        }
        val selection = configuredContentUiLanguage()
        listOfNotNull(
            playbackMetadataOverrides[artistId],
            playbackArtistOverrides[artistId],
            originalMetadataOverrides[artistId],
            originalArtistOverrides[artistId],
            sharedLocalizedArtistOverrides[localizedArtistOverrideKey(selection, artistId)],
            sharedOriginalArtistOverrides[artistId],
            internalCatalogResolver.cachedLocalizedMetadata(
                selection = selection,
                entityType = AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                mediaId = artistId,
            ),
            internalCatalogResolver.cachedLocalizedArtist(
                selection = selection,
                artistKeys = setOf("id:$artistId"),
            ),
        ).forEach { alias ->
            alias.title.takeIf(String::isNotBlank)?.let(::add)
            alias.artist.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    internal fun inferredOriginalArtistLanguage(
        kind: InAppLibraryEntityKind,
        artist: String?,
        associatedArtistIds: List<String>,
        genres: Collection<String>,
    ): String? {
        if (associatedArtistIds.size != 1) return null
        if (kind != InAppLibraryEntityKind.ARTIST &&
            AppleInternalCatalogResolver.isCollaborationArtistName(artist.orEmpty())
        ) return null
        return AppleInternalCatalogResolver.languageTagsForOriginalMetadata(
            genre = null,
            catalogGenres = genres,
            isrc = null,
        ).singleOrNull()
    }

    private fun mediaApiGenreNames(attributes: Any): List<String> {
        val values = runCatching { AppleReflection.call(attributes, "getGenreNames") }
            .getOrNull()
        val genres = when (values) {
            is Iterable<*> -> values
            is Array<*> -> values.asIterable()
            else -> emptyList<Any?>()
        }.mapNotNull { value ->
            value?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }
        if (genres.isNotEmpty()) return genres
        return listOfNotNull(
            runCatching { AppleReflection.call(attributes, "getGenreName") as? String }
                .getOrNull()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        )
    }

    private fun hookArtistProfileTopSongs() {
        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.apple.android.music.profiles.BaseProfileEpoxyController"
            )
            val mediaEntityClass = classLoader.loadClass(
                "com.apple.android.music.mediaapi.models.MediaEntity"
            )
            val buildMethod = controllerClass.declaredMethods.singleOrNull { method ->
                val parameterTypes = method.parameterTypes
                method.name == "addSwipingChartItemA2" &&
                    parameterTypes.size == 6 &&
                    parameterTypes[0] == String::class.java &&
                    parameterTypes[1] == mediaEntityClass &&
                    parameterTypes[2] == Int::class.javaPrimitiveType &&
                    parameterTypes[3] == Int::class.javaPrimitiveType &&
                    parameterTypes[4] == String::class.java &&
                    parameterTypes[5] == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true }
                ?: error("BaseProfileEpoxyController.addSwipingChartItemA2 not found")
            val modelClass = classLoader.loadClass("com.apple.android.music.h1")
            val modelBindMethod = modelClass.declaredMethods.singleOrNull { method ->
                val parameterTypes = method.parameterTypes
                method.name == "a" &&
                    parameterTypes.size == 2 &&
                    parameterTypes[0] == Int::class.javaPrimitiveType &&
                    parameterTypes[1] == Any::class.java
            }?.apply { isAccessible = true }
                ?: error("SwipingChartListA2 model bind method not found")

            hookRegistrar.install(
                buildMethod,
                before = { chain ->
                    val controller = chain.thisObject ?: return@installHook
                    val relationshipKey = chain.args.getOrNull(0)
                    val entity = chain.args.getOrNull(1) ?: return@installHook
                    val mediaId = artistProfileTopSongMediaId(
                        relationshipKey = relationshipKey,
                        mediaId = mediaApiEntityCatalogId(entity),
                    ) ?: return@installHook
                    registerInAppLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.SONG,
                        requestResolution = false,
                        retainEntityRef = true,
                    )
                    associateArtistProfileTopSongWithProfileArtist(
                        controller = controller,
                        mediaId = mediaId,
                    )
                },
                after = { chain, result ->
                    val model = result ?: return@installHook
                    val entity = chain.args.getOrNull(1) ?: return@installHook
                    val mediaId = artistProfileTopSongMediaId(
                        relationshipKey = chain.args.getOrNull(0),
                        mediaId = inAppLibraryEntityIds[entity]
                            ?: mediaApiEntityCatalogId(entity),
                    ) ?: return@installHook
                    inAppArtistTopSongModelIds[model] = mediaId
                    val snapshot = ArtistTopSongModelSnapshot(
                        mediaId = mediaId,
                        originalTitle = debugReflectiveField(model, "L")?.toString(),
                        originalSubtitle = debugReflectiveField(model, "P")?.toString(),
                        originalArtist = playbackMetadataAccountValues[mediaId]?.artist,
                    )
                    inAppArtistTopSongModels[model] = snapshot
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.info(
                            "Apple Music 元数据链路: " +
                                "seq=${metadataTraceSequence.incrementAndGet()}, " +
                                "event=artist_top_songs_capture, contentId=$mediaId, " +
                                "relationshipKey=${chain.args.getOrNull(0)}, " +
                                "entity=${entity.javaClass.name}@" +
                                "${System.identityHashCode(entity)}, " +
                                "model=${model.javaClass.name}@" +
                                "${System.identityHashCode(model)}, " +
                                "modelTitle=${debugReflectiveField(model, "L")}, " +
                                "modelSubtitle=${debugReflectiveField(model, "P")}, " +
                                "modelCaption=${debugReflectiveField(model, "H")}, " +
                                "profileArtistId=${artistProfileMediaIds[chain.thisObject]}, " +
                                "artistIds=${playbackMetadataAssociatedArtistIds[mediaId]}"
                        )
                    }
                },
            )
            hookRegistrar.install(
                modelBindMethod,
                before = { chain ->
                    val model = chain.thisObject ?: return@installHook
                    val snapshot = inAppArtistTopSongModels[model] ?: return@installHook
                    val binding = epoxyDataBindingFromHolder(chain.args.getOrNull(1))
                    if (binding != null) {
                        beginInAppDataBindingModelBind(binding)
                        inAppArtistTopSongBindings[binding] = snapshot
                        captureInAppDataBinding(binding)
                        registerInAppDataBinding(
                            mediaId = snapshot.mediaId,
                            binding = binding,
                            originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                        )
                    }
                },
                after = { chain, _ ->
                    val model = chain.thisObject ?: return@installHook
                    val snapshot = inAppArtistTopSongModels[model] ?: return@installHook
                    val binding = epoxyDataBindingFromHolder(chain.args.getOrNull(1))
                    if (binding != null) {
                        inAppArtistTopSongBindings[binding] = snapshot
                        captureInAppDataBinding(binding)
                        registerInAppDataBinding(
                            mediaId = snapshot.mediaId,
                            binding = binding,
                            originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        val root = binding?.let { inAppDataBindingRootViews[it]?.get() }
                        ProviderLogger.info(
                            "Apple Music 元数据链路: " +
                                "seq=${metadataTraceSequence.incrementAndGet()}, " +
                                "event=artist_top_songs_visible, contentId=${snapshot.mediaId}, " +
                                "position=${chain.args.getOrNull(0)}, " +
                                "model=${model.javaClass.name}@" +
                                "${System.identityHashCode(model)}, " +
                                "modelTitle=${debugReflectiveField(model, "L")}, " +
                                "modelSubtitle=${debugReflectiveField(model, "P")}, " +
                                "binding=${binding?.javaClass?.name}@" +
                                "${binding?.let(System::identityHashCode)}, " +
                                "bindingMediaId=${binding?.let { inAppDataBindingMediaIds[it] }}, " +
                                "rootVisible=${root?.let(::isVisibleBindingRoot) == true}, " +
                                "texts=${root?.let(::debugTextSnapshot)}"
                        )
                    }
                },
            )
            ProviderLogger.info(
                "Apple Music 歌手页歌曲排行元数据 Hook 已安装: " +
                    "builder=${buildMethod.name}, binder=${modelBindMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 歌手页歌曲排行元数据 Hook 安装失败", it)
        }
    }

    private fun hookArtistProfileMetadata() {
        runCatching {
            val artistControllerClass = classLoader.loadClass(
                "com.apple.android.music.profiles.ArtistEpoxyController"
            )
            val mediaEntityClass = classLoader.loadClass(
                "com.apple.android.music.mediaapi.models.MediaEntity"
            )
            val buildMethod = artistControllerClass.declaredMethods.singleOrNull { method ->
                val parameterTypes = method.parameterTypes
                method.name == "buildModels" &&
                    !method.isBridge &&
                    parameterTypes.size == 3 &&
                    parameterTypes[0] == mediaEntityClass &&
                    parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    Set::class.java.isAssignableFrom(parameterTypes[2])
            }?.apply { isAccessible = true }
                ?: error("ArtistEpoxyController.buildModels not found")
            val headerModelClass = classLoader.loadClass("com.apple.android.music.V")
            val headerBindMethod = headerModelClass.declaredMethods.singleOrNull { method ->
                val parameterTypes = method.parameterTypes
                method.name == "a" &&
                    parameterTypes.size == 2 &&
                    parameterTypes[0] == Int::class.javaPrimitiveType &&
                    parameterTypes[1] == Any::class.java
            }?.apply { isAccessible = true }
                ?: error("Artist header model bind method not found")

            hookRegistrar.install(
                buildMethod,
                before = { chain ->
                    val controller = chain.thisObject ?: return@installHook
                    val entity = chain.args.firstOrNull() ?: return@installHook
                    val attributes = mediaApiEntityAttributes(entity)
                        ?: return@installHook
                    val mediaId = mediaApiEntityCatalogId(entity, attributes)
                        ?: return@installHook
                    artistProfileMediaIds[controller] = mediaId
                    latestArtistProfileMediaId = mediaId
                    artistPageBuildData[controller] = ArtistPageBuildData(
                        artist = entity,
                        isAddMusicMode = chain.args.getOrNull(1) as? Boolean
                            ?: return@installHook,
                        selectedItemIds = chain.args.getOrNull(2),
                    )
                    registerInAppLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.ARTIST,
                        knownAttributes = attributes,
                        requestResolution = false,
                        retainEntityRef = true,
                    )
                    /*
                     * 歌手头标题会在本方法内部立即复制 data.getTitle()。
                     * 必须先建立“ARTIST 自身 artistID”关联并应用本地 alias，
                     * 否则后续即使缓存命中也只能改源实体，已建好的标题模型不会变化。
                     */
                    enrichInAppLibraryEntity(
                        mediaId = mediaId,
                        entity = entity,
                        kind = InAppLibraryEntityKind.ARTIST,
                        attributes = attributes,
                    )
                    registerInAppLibraryController(mediaId, controller)
                    effectiveInAppMetadataOverride(mediaId)?.let { alias ->
                        applyAliasToInAppLibraryEntity(
                            entity = entity,
                            kind = InAppLibraryEntityKind.ARTIST,
                            alias = alias,
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.info(
                            "Apple Music 元数据链路: " +
                                "seq=${metadataTraceSequence.incrementAndGet()}, " +
                                "event=artist_profile_build, " +
                                "contentId=$mediaId, " +
                                "entity=${entity.javaClass.name}@" +
                                "${System.identityHashCode(entity)}, " +
                                "entityTitle=${runCatching {
                                    AppleReflection.call(entity, "getTitle")
                                }.getOrNull()}, " +
                                "attributeName=${mediaApiAttribute(attributes, "getName")}, " +
                                "artistIds=${playbackMetadataAssociatedArtistIds[mediaId]}, " +
                                "effective=${effectiveInAppMetadataOverride(mediaId)?.let {
                                    "${it.title}/${it.artist}/${it.album}"
                                }}"
                        )
                    }
                },
                after = { chain, _ ->
                    val controller = chain.thisObject ?: return@installHook
                    val mediaId = artistProfileMediaIds[controller]
                        ?: return@installHook
                    /*
                     * 不依赖未命中的 V/K.t 标题绑定；建模结束后直接给真实页面建立
                     * 可见租约并启动原名优先解析，正确结果到达后走 Typed3 setData 重建。
                     */
                    mainHandler.post {
                        markMetadataVisible(listOf(mediaId))
                        enrichInAppLibraryEntitiesForResolution(listOf(mediaId))
                        effectiveInAppMetadataOverride(mediaId)?.let { alias ->
                            applyAliasToInAppMetadataRefs(
                                mediaId = mediaId,
                                alias = alias,
                                forceRebind = true,
                                notifyModelChange = true,
                            )
                        }
                        if (shouldRequestInAppMetadataOverride(mediaId)) {
                            scheduleInAppMetadataResolution(
                                mediaIds = listOf(mediaId),
                                priority =
                                    AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                                originalResolutionMode =
                                    InAppOriginalResolutionMode.ORIGINAL_FIRST,
                            )
                        }
                    }
                },
            )
            hookRegistrar.install(
                headerBindMethod,
                before = { chain ->
                    val model = chain.thisObject ?: return@installHook
                    val mediaId = artistProfileHeaderMediaId(model) ?: return@installHook
                    inAppArtistHeaderModelIds[model] = mediaId
                    epoxyDataBindingFromHolder(chain.args.getOrNull(1))?.let { binding ->
                        beginInAppDataBindingModelBind(binding)
                        inAppArtistHeaderBindingIds[binding] = mediaId
                        captureInAppDataBinding(binding)
                        registerInAppDataBinding(mediaId, binding)
                    }
                },
                after = { chain, _ ->
                    val model = chain.thisObject ?: return@installHook
                    val mediaId = artistProfileHeaderMediaId(model) ?: return@installHook
                    inAppArtistHeaderModelIds[model] = mediaId
                    val binding = epoxyDataBindingFromHolder(chain.args.getOrNull(1))
                    if (binding != null) {
                        inAppArtistHeaderBindingIds[binding] = mediaId
                        captureInAppDataBinding(binding)
                        registerInAppDataBinding(mediaId, binding)
                    }
                    if (BuildConfig.DEBUG) {
                        val root = binding?.let { inAppDataBindingRootViews[it]?.get() }
                        ProviderLogger.info(
                            "Apple Music 元数据链路: " +
                                "seq=${metadataTraceSequence.incrementAndGet()}, " +
                                "event=artist_profile_header_visible, contentId=$mediaId, " +
                                "position=${chain.args.getOrNull(0)}, " +
                                "model=${model.javaClass.name}@" +
                                "${System.identityHashCode(model)}, " +
                                "modelTitle=${debugReflectiveField(model, "x")}, " +
                                "binding=${binding?.javaClass?.name}@" +
                                "${binding?.let(System::identityHashCode)}, " +
                                "bindingMediaId=${binding?.let { inAppDataBindingMediaIds[it] }}, " +
                                "rootVisible=${root?.let(::isVisibleBindingRoot) == true}, " +
                                "texts=${root?.let(::debugTextSnapshot)}"
                        )
                    }
                },
            )
            ProviderLogger.info(
                "Apple Music 歌手页标题实时元数据 Hook 已安装: " +
                    "builder=${buildMethod.name}, binder=${headerBindMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 歌手页标题实时元数据 Hook 安装失败", it)
        }
    }

    /**
     * 接入 Apple Music“最近搜索”自己的实体列表与 Epoxy 绑定。
     *
     * 该页面不是播放历史，也不会进入播放队列 Hook；每一行都直接读取
     * RecentlySearchedEpoxyController 持有的 MediaEntity。
     */
    private fun hookRecentlySearchedMetadata() {
        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.apple.android.music.search2.RecentlySearchedEpoxyController"
            )
            val mediaEntityClass = classLoader.loadClass(
                "com.apple.android.music.mediaapi.models.MediaEntity"
            )
            val setDataMethod = controllerClass.declaredMethods.singleOrNull { method ->
                method.name == "setData" &&
                    !method.isBridge &&
                    method.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(method.parameterTypes[0])
            }?.apply { isAccessible = true }
                ?: error("RecentlySearchedEpoxyController.setData not found")
            val onModelBoundMethod = controllerClass.declaredMethods.singleOrNull { method ->
                method.name == "onModelBound" &&
                    !method.isBridge &&
                    method.parameterTypes.size == 4
            }?.apply { isAccessible = true }
                ?: error("RecentlySearchedEpoxyController.onModelBound not found")

            hookRegistrar.install(setDataMethod, before = { chain ->
                val controller = chain.thisObject ?: return@installHook
                val entities = chain.args.firstOrNull() as? Iterable<*>
                    ?: return@installHook
                entities.forEach { entity ->
                    entity ?: return@forEach
                    if (!mediaEntityClass.isInstance(entity)) return@forEach
                    registerRecentlySearchedEntity(
                        controller = controller,
                        entity = entity,
                        visible = false,
                    )
                }
            })
            hookRegistrar.install(onModelBoundMethod, after = { chain, _ ->
                val controller = chain.thisObject ?: return@installHook
                val model = chain.args.getOrNull(1) ?: return@installHook
                val entity = collectionPageRowEntity(model, mediaEntityClass)
                    ?: return@installHook
                registerRecentlySearchedEntity(
                    controller = controller,
                    entity = entity,
                    visible = true,
                )
            })
            ProviderLogger.info(
                "Apple Music 最近搜索元数据 Hook 已安装: " +
                    "setData=${setDataMethod.name}, bound=${onModelBoundMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 最近搜索元数据 Hook 安装失败", it)
        }
    }

    /**
     * 登记最近搜索行的真实实体；仅在行已绑定可见时发起实时原名解析。
     */
    private fun registerRecentlySearchedEntity(
        controller: Any,
        entity: Any,
        visible: Boolean,
    ) {
        val kind = inAppLibraryEntityKindForClassNames(
            generateSequence(entity.javaClass as Class<*>?) { it.superclass }
                .map(Class<*>::getName)
                .toList()
        ) ?: return
        val attributes = mediaApiEntityAttributes(entity) ?: return
        val mediaId = mediaApiEntityCatalogId(entity, attributes) ?: return
        registerInAppLibraryEntity(
            mediaId = mediaId,
            entity = entity,
            kind = kind,
            knownAttributes = attributes,
            requestResolution = false,
            retainEntityRef = true,
        )
        enrichInAppLibraryEntity(
            mediaId = mediaId,
            entity = entity,
            kind = kind,
            attributes = attributes,
        )
        registerInAppLibraryController(mediaId, controller)
        effectiveInAppMetadataOverride(mediaId)?.let { alias ->
            applyAliasToInAppLibraryEntity(entity, kind, alias)
        }
        if (!visible) return

        mainHandler.post {
            markMetadataVisible(listOf(mediaId))
            val alias = effectiveInAppMetadataOverride(mediaId)
            if (alias != null) {
                applyAliasToInAppMetadataRefs(
                    mediaId = mediaId,
                    alias = alias,
                    forceRebind = true,
                    notifyModelChange = true,
                )
            }
            if (shouldRequestInAppMetadataOverride(mediaId)) {
                scheduleInAppMetadataResolution(
                    mediaIds = listOf(mediaId),
                    priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    originalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST,
                )
            }
            if (BuildConfig.DEBUG) {
                ProviderLogger.info(
                    "Apple Music 元数据链路: " +
                        "seq=${metadataTraceSequence.incrementAndGet()}, " +
                        "event=recent_search_bound, contentId=$mediaId, kind=$kind, " +
                        "controller=${controller.javaClass.name}, " +
                        "entity=${entity.javaClass.name}, " +
                        "effective=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                        "request=${shouldRequestInAppMetadataOverride(mediaId)}"
                )
            }
        }
    }

    private fun artistProfileHeaderMediaId(model: Any): String? {
        inAppArtistHeaderModelIds[model]?.let { return it }
        activeMetadataPageOwner.get()
            ?.let { owner -> artistProfileMediaIds[owner] }
            ?.let { return it }
        val latestMediaId = latestArtistProfileMediaId ?: return null
        val modelTitle = debugReflectiveField(model, "x")?.toString().orEmpty()
        val accountTitle = playbackMetadataAccountValues[latestMediaId]?.title.orEmpty()
        val modelKey = AppleInternalCatalogResolver.normalizedArtistNameKey(modelTitle)
        val accountKey = AppleInternalCatalogResolver.normalizedArtistNameKey(accountTitle)
        return latestMediaId.takeIf {
            modelKey.isNotEmpty() && modelKey == accountKey
        }
    }

    private fun epoxyDataBindingFromHolder(holder: Any?): Any? {
        holder ?: return null
        val bindingClass = dataBindingBaseClass ?: return null
        return generateSequence(holder.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { field -> bindingClass.isAssignableFrom(field.type) }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(holder)
                }.getOrNull()
            }
    }

    private fun epoxyDataBindingFromFinalHolder(holder: Any?): Any? {
        val modelHolder = holder?.let {
            runCatching { AppleReflection.call(it, "u") }.getOrNull()
        }
        return epoxyDataBindingFromHolder(modelHolder)
            ?: epoxyDataBindingFromHolder(holder)
    }

    /**
     * Debug-only trace for the actual artwork rendering path used by LibraryPinKt.
     *
     * Runtime identifiers below were verified against the original Apple Music DEX. In
     * particular, b5.g and T6.i must not be replaced with JADX display aliases such as
     * p028b5.g or T6.C1854i.
     */
    private fun hookDebugLibraryArtworkLifecycle() {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val mediaEntityClass = classLoader.loadClass(
                "com.apple.android.music.mediaapi.models.MediaEntity"
            )
            val composerClass = classLoader.loadClass("z0.m")
            val libraryPinClass = classLoader.loadClass(
                "com.apple.android.music.compose.ui.LibraryPinKt"
            )
            val libraryPinMethod = libraryPinClass.declaredMethods.single { method ->
                method.name == "a" &&
                    Modifier.isStatic(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(
                        arrayOf(mediaEntityClass, composerClass, Int::class.javaPrimitiveType)
                    )
            }.apply { isAccessible = true }

            val requestClass = classLoader.loadClass("b5.g")
            val painterClass = classLoader.loadClass("R4.b")
            val painterStateClass = classLoader.loadClass("R4.b\$b")
            val asyncImagePainterFactoryClass = classLoader.loadClass("R4.g")
            val asyncImagePainterFactoryMethod =
                asyncImagePainterFactoryClass.declaredMethods.single { method ->
                    method.name == "a" &&
                        Modifier.isStatic(method.modifiers) &&
                        method.returnType == painterClass &&
                        method.parameterTypes.contentEquals(
                            arrayOf(
                                Any::class.java,
                                classLoader.loadClass("Q4.g"),
                                classLoader.loadClass("Lg.l"),
                                classLoader.loadClass("Lg.l"),
                                classLoader.loadClass("l1.k"),
                                Int::class.javaPrimitiveType,
                                composerClass,
                            )
                        )
                }.apply { isAccessible = true }
            val accessors = DebugLibraryArtworkAccessors(
                requestClass = requestClass,
                requestData = requestClass.getDeclaredField("b").apply { isAccessible = true },
                requestMemoryCacheKey = requestClass.getDeclaredField("d")
                    .apply { isAccessible = true },
                requestPlaceholder = requestClass.getDeclaredField("A")
                    .apply { isAccessible = true },
                requestError = requestClass.getDeclaredField("C")
                    .apply { isAccessible = true },
                painterState = painterClass.getDeclaredField("l").apply { isAccessible = true },
                painterDrawPainter = painterClass.getDeclaredField("x")
                    .apply { isAccessible = true },
                statePainter = painterStateClass.getDeclaredMethod("a")
                    .apply { isAccessible = true },
                stateKinds = mapOf(
                    classLoader.loadClass("R4.b\$b\$a") to "empty",
                    classLoader.loadClass("R4.b\$b\$b") to "error",
                    classLoader.loadClass("R4.b\$b\$c") to "loading",
                    classLoader.loadClass("R4.b\$b\$d") to "success",
                ),
            )

            hookRegistrar.installScoped(
                executable = libraryPinMethod,
                enter = { chain ->
                    val entity = chain.args.firstOrNull() ?: return@installScopedHook false
                    debugLibraryArtworkComposeCaptures.push(
                        debugLibraryArtworkComposeCapture(entity)
                    )
                    true
                },
                after = { _, _ -> Unit },
                exit = { debugLibraryArtworkComposeCaptures.pop() },
            )
            hookRegistrar.install(asyncImagePainterFactoryMethod, after = { chain, result ->
                val capture = debugLibraryArtworkComposeCaptures.current
                    ?: return@installHook
                val painter = result?.takeIf(painterClass::isInstance)
                    ?: return@installHook
                registerDebugLibraryArtworkPainter(
                    capture = capture,
                    painter = painter,
                    requestCandidate = chain.args.firstOrNull(),
                    accessors = accessors,
                )
            })

            mapOf(
                "e" to "remembered",
                "f" to "forgotten",
                "i" to "abandoned",
            ).forEach { (methodName, lifecycle) ->
                val method = painterClass.declaredMethods.single { candidate ->
                    candidate.name == methodName &&
                        candidate.parameterCount == 0 &&
                        candidate.returnType == Void.TYPE
                }.apply { isAccessible = true }
                hookRegistrar.install(method, after = { chain, _ ->
                    chain.thisObject?.let { painter ->
                        recordDebugLibraryArtworkPainterLifecycle(painter, lifecycle)
                    }
                })
            }
            val stateUpdateMethod = painterClass.declaredMethods.single { method ->
                method.name == "k" &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf(painterStateClass))
            }.apply { isAccessible = true }
            hookRegistrar.install(stateUpdateMethod, after = { chain, _ ->
                chain.thisObject?.let { painter ->
                    recordDebugLibraryArtworkPainterState(
                        painter = painter,
                        accessors = accessors,
                    )
                }
            })
            ProviderLogger.info(
                "Apple Music 资料库封面 painter 生命周期诊断 Hook 已安装: " +
                    "libraryPin=${libraryPinMethod.name}/${libraryPinMethod.parameterCount}, " +
                    "factory=${asyncImagePainterFactoryMethod.name}/" +
                    asyncImagePainterFactoryMethod.parameterCount
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 资料库封面 painter 生命周期诊断 Hook 安装失败", it)
        }
    }

    private fun debugLibraryArtworkComposeCapture(
        entity: Any,
    ): DebugLibraryArtworkComposeCapture {
        val mediaId = runCatching { AppleReflection.call(entity, "getId")?.toString() }
            .getOrNull()
            ?.trim()
            .orEmpty()
        val title = runCatching { AppleReflection.call(entity, "getTitle")?.toString() }
            .getOrNull()
            ?.replace('\n', ' ')
            ?.take(96)
        val persistentId = runCatching {
            (AppleReflection.call(entity, "getPersistentId") as? Number)?.toLong()
        }.getOrNull() ?: 0L
        val contentType = runCatching {
            (AppleReflection.call(entity, "getContentType") as? Number)?.toInt()
        }.getOrNull() ?: -1
        return DebugLibraryArtworkComposeCapture(
            mediaKey = "$mediaId:$persistentId:$contentType",
            mediaId = mediaId.ifEmpty { "none" },
            title = title,
            persistentId = persistentId,
            contentType = contentType,
        )
    }

    private fun registerDebugLibraryArtworkPainter(
        capture: DebugLibraryArtworkComposeCapture,
        painter: Any,
        requestCandidate: Any?,
        accessors: DebugLibraryArtworkAccessors,
    ) {
        val request = requestCandidate?.takeIf(accessors.requestClass::isInstance)
        val requestData = request?.let { accessors.requestData.get(it) } ?: requestCandidate
        val requestMemoryCacheKey = request?.let { accessors.requestMemoryCacheKey.get(it) }
        val requestPlaceholder = request?.let { accessors.requestPlaceholder.get(it) }
        val requestError = request?.let { accessors.requestError.get(it) }
        val trace = DebugLibraryArtworkPainterTrace(
            mediaKey = capture.mediaKey,
            mediaId = capture.mediaId,
            title = capture.title,
            persistentId = capture.persistentId,
            contentType = capture.contentType,
            requestIdentity = request?.let(System::identityHashCode),
            requestDataDescription = debugLibraryArtworkDataDescription(requestData),
            requestDataClass = requestData?.javaClass?.name ?: "null",
            requestDataHash = runCatching { requestData?.hashCode() }.getOrNull(),
            memoryCacheKey = debugLibraryArtworkSensitiveDescription(requestMemoryCacheKey),
            placeholderSignature = debugLibraryArtworkDrawableSignature(requestPlaceholder),
            placeholderIdentity = debugLibraryArtworkObjectIdentity(requestPlaceholder),
            errorSignature = debugLibraryArtworkDrawableSignature(requestError),
            errorIdentity = debugLibraryArtworkObjectIdentity(requestError),
        )
        val previousTrace = debugLibraryArtworkPainters[painter]
        val previousPainter = debugLibraryArtworkLatestPainters[capture.mediaKey]?.get()
        val painterReplaced = previousPainter != null && previousPainter !== painter
        val traceChanged = previousTrace == null ||
            !previousTrace.hasSameSemanticRequest(trace) ||
            painterReplaced
        debugLibraryArtworkPainters[painter] = trace
        debugLibraryArtworkLatestPainters[capture.mediaKey] = WeakReference(painter)
        if (!traceChanged) return

        val runtimeState = debugLibraryArtworkPainterStates[painter]
        ProviderLogger.diagnostic(
            "LibraryArtworkPainter: event=compose_bind, " +
                debugLibraryArtworkTraceIdentity(trace) + ", " +
                "painter=${debugLibraryArtworkObjectIdentity(painter)}, " +
                "previousPainter=${debugLibraryArtworkObjectIdentity(previousPainter)}, " +
                "replaced=$painterReplaced, request=${trace.requestIdentity ?: "raw"}, " +
                "data=${trace.requestDataDescription}, memoryKey=${trace.memoryCacheKey}, " +
                "placeholder=${trace.placeholderIdentity}/${trace.placeholderSignature}, " +
                "error=${trace.errorIdentity}/${trace.errorSignature}, " +
                "lifecycle=${runtimeState?.lifecycle ?: "unknown"}"
        )
        recordDebugLibraryArtworkPainterState(
            painter = painter,
            accessors = accessors,
            force = true,
        )
    }

    private fun recordDebugLibraryArtworkPainterLifecycle(
        painter: Any,
        lifecycle: String,
    ) {
        val previous = debugLibraryArtworkPainterStates[painter]
            ?: DebugLibraryArtworkPainterState()
        if (previous.lifecycle == lifecycle) return
        debugLibraryArtworkPainterStates[painter] = previous.copy(lifecycle = lifecycle)
        val trace = debugLibraryArtworkPainters[painter] ?: return
        ProviderLogger.diagnostic(
            "LibraryArtworkPainter: event=lifecycle, " +
                debugLibraryArtworkTraceIdentity(trace) + ", " +
                "painter=${debugLibraryArtworkObjectIdentity(painter)}, " +
                "from=${previous.lifecycle ?: "unknown"}, to=$lifecycle"
        )
    }

    private fun recordDebugLibraryArtworkPainterState(
        painter: Any,
        accessors: DebugLibraryArtworkAccessors,
        force: Boolean = false,
    ) {
        val state = accessors.painterState.get(painter) ?: return
        val kind = accessors.stateKinds[state.javaClass] ?: state.javaClass.name
        val statePainter = runCatching { accessors.statePainter.invoke(state) }.getOrNull()
        val drawPainter = accessors.painterDrawPainter.get(painter)
        val result = if (kind == "success" || kind == "error") {
            runCatching { AppleReflection.field(state, "b") }.getOrNull()
        } else {
            null
        }
        val dataSource = if (kind == "success" && result != null) {
            runCatching { AppleReflection.field(result, "c")?.toString() }.getOrNull()
        } else {
            null
        }
        val memoryCacheKey = if (kind == "success" && result != null) {
            runCatching { AppleReflection.field(result, "e") }.getOrNull()
        } else {
            null
        }
        val isPlaceholderCached = if (kind == "success" && result != null) {
            runCatching { AppleReflection.field(result, "g") as? Boolean }.getOrNull()
        } else {
            null
        }
        val resultRequest = result?.let {
            runCatching { AppleReflection.field(it, "b") }.getOrNull()
        }
        val resultRequestData = resultRequest
            ?.takeIf(accessors.requestClass::isInstance)
            ?.let { runCatching { accessors.requestData.get(it) }.getOrNull() }
        val fingerprint = listOf(
            kind,
            debugLibraryArtworkObjectIdentity(statePainter),
            debugLibraryArtworkObjectIdentity(drawPainter),
            dataSource ?: "none",
            debugLibraryArtworkSensitiveDescription(memoryCacheKey),
            isPlaceholderCached?.toString() ?: "unknown",
            debugLibraryArtworkDataDescription(resultRequestData),
        ).joinToString("|")
        val previous = debugLibraryArtworkPainterStates[painter]
            ?: DebugLibraryArtworkPainterState()
        if (!force && previous.imageStateFingerprint == fingerprint) return
        debugLibraryArtworkPainterStates[painter] = previous.copy(
            imageStateFingerprint = fingerprint
        )
        val trace = debugLibraryArtworkPainters[painter] ?: return
        ProviderLogger.diagnostic(
            "LibraryArtworkPainter: event=state, " +
                debugLibraryArtworkTraceIdentity(trace) + ", " +
                "painter=${debugLibraryArtworkObjectIdentity(painter)}, state=$kind, " +
                "statePainter=${debugLibraryArtworkObjectIdentity(statePainter)}, " +
                "drawPainter=${debugLibraryArtworkObjectIdentity(drawPainter)}, " +
                "dataSource=${dataSource ?: "none"}, " +
                "memoryCacheKey=${debugLibraryArtworkSensitiveDescription(memoryCacheKey)}, " +
                "placeholderCached=${isPlaceholderCached ?: "unknown"}, " +
                "resultData=${debugLibraryArtworkDataDescription(resultRequestData)}"
        )
    }

    private fun debugLibraryArtworkTraceIdentity(
        trace: DebugLibraryArtworkPainterTrace,
    ): String =
        "mediaId=${trace.mediaId}, persistentId=${trace.persistentId}, " +
            "contentType=${trace.contentType}, title=${trace.title ?: "none"}"

    private fun debugLibraryArtworkDataDescription(value: Any?): String {
        if (value == null) return "null"
        if (value is CharSequence) {
            return "${value.javaClass.name}(${debugLibraryArtworkSensitiveDescription(value)})"
        }
        return "${value.javaClass.name}@${System.identityHashCode(value)}," +
            "hash=${runCatching { value.hashCode() }.getOrNull() ?: "error"}"
    }

    private fun debugLibraryArtworkSensitiveDescription(value: Any?): String {
        if (value == null) return "null"
        val text = value.toString()
        return "len=${text.length},hash=${text.hashCode()}"
    }

    private fun debugLibraryArtworkDrawableSignature(value: Any?): String = when (value) {
        null -> "null"
        is android.graphics.drawable.ColorDrawable ->
            "${value.javaClass.name}:color=${value.color}"
        else -> "${value.javaClass.name}:hash=" +
            (runCatching { value.hashCode() }.getOrNull() ?: "error")
    }

    private fun debugLibraryArtworkObjectIdentity(value: Any?): String =
        value?.let { "${it.javaClass.name}@${System.identityHashCode(it)}" } ?: "null"

    /**
     * Keeps Listen Now cards from binding one empty artwork frame when Apple rebuilds a local
     * library entity without its feed image URL. The last resolved URL is reused only while the
     * media identity and artwork-token identity are unchanged. A seeded card no longer needs the
     * immediately repeated J.t() lookup; token changes and cache misses keep the native path.
     */
    private fun hookInAppListenNowArtworkContinuity() {
        runCatching {
            val resolvedBuilder = hookResolver.resolveMethod(
                AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER
            )
            val resolvedArtworkSubmit = hookResolver.resolveMethod(
                AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER
            )
            val modelClass = hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MODEL
            ).clazz
            val mediaEntityClass = hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY
            ).clazz
            val liveDataClass = classLoader.loadClass("androidx.lifecycle.MutableLiveData")
            val delegateClass = hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM
            ).clazz
            val builderMethod = resolvedBuilder.method
            val resolverSubmitMethod = resolvedArtworkSubmit.method
            val modelLiveDataField = generateSequence(modelClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { field -> liveDataClass.isAssignableFrom(field.type) }
                .apply { isAccessible = true }
            val delegateLiveDataField = generateSequence(delegateClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { field -> liveDataClass.isAssignableFrom(field.type) }
                .apply { isAccessible = true }
            val liveDataGetValue = AppleReflection.findMethod(liveDataClass, "getValue", 0)
            val liveDataSetValue = AppleReflection.findMethod(liveDataClass, "setValue", 1)

            hookRegistrar.install(
                builderMethod,
                before = { chain ->
                    chain.args.getOrNull(3)
                        ?.takeIf(mediaEntityClass::isInstance)
                        ?.let(::primeInAppListenNowMetadata)
                },
                after = { chain, result ->
                    val model = result?.takeIf(modelClass::isInstance) ?: return@installHook
                    val entity = chain.args.getOrNull(3)
                        ?.takeIf(mediaEntityClass::isInstance)
                        ?: return@installHook
                    val identity = inAppListenNowArtworkIdentity(entity)
                    val liveData = runCatching { modelLiveDataField.get(model) }.getOrNull()
                        ?: return@installHook
                    recordInAppListenNowModelBuildState(
                        model = model,
                        entity = entity,
                        liveData = liveData,
                        builderKey = identity.key,
                    )
                    val currentUrls = normalizedInAppArtworkValueUrls(
                        runCatching { liveDataGetValue.invoke(liveData) }.getOrNull()
                    )
                    if (BuildConfig.DEBUG) {
                        logMetadataIdentity(
                            event = "listen_now_artwork_builder_identity",
                            details = "moduleVersion=${BuildConfig.VERSION_CODE}, " +
                                "liveData=${debugLibraryArtworkObjectIdentity(liveData)}, " +
                                "currentUrlHash=${currentUrls.hashCode()}, " +
                                debugInAppListenNowArtworkIdentity(identity),
                        )
                    }
                    val key = identity.key ?: return@installHook
                    inAppListenNowArtworkKeysByLiveData[liveData] = key
                    if (currentUrls.isNotEmpty()) {
                        putInAppListenNowArtworkContinuity(key, currentUrls)
                        if (BuildConfig.DEBUG) {
                            logMetadataIdentity(
                                event = "listen_now_artwork_builder_cache_store",
                                details = "contentId=${key.id}, persistentId=${key.persistentId}, " +
                                    "contentType=${key.contentType}, artworkHash=" +
                                    "${key.artworkIdentity.hashCode()}, urls=${currentUrls.size}, " +
                                    "urlHash=${currentUrls.hashCode()}",
                            )
                        }
                        return@installHook
                    }
                    val cacheProbe = synchronized(inAppListenNowArtworkContinuityCache) {
                        InAppListenNowArtworkCacheProbe(
                            exact = inAppListenNowArtworkContinuityCache[key],
                            cacheSize = inAppListenNowArtworkContinuityCache.size,
                            sameBaseArtworkHashes = inAppListenNowArtworkContinuityCache.keys
                                .asSequence()
                                .filter { candidate ->
                                    candidate.id == key.id &&
                                        candidate.persistentId == key.persistentId &&
                                        candidate.contentType == key.contentType
                                }
                                .map { candidate -> candidate.artworkIdentity.hashCode() }
                                .distinct()
                                .toList(),
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        logMetadataIdentity(
                            event = "listen_now_artwork_cache_lookup",
                            details = "contentId=${key.id}, persistentId=${key.persistentId}, " +
                                "contentType=${key.contentType}, artworkHash=" +
                                "${key.artworkIdentity.hashCode()}, exactHit=" +
                                "${cacheProbe.exact != null}, cacheSize=${cacheProbe.cacheSize}, " +
                                "sameBaseArtworkHashes=${cacheProbe.sameBaseArtworkHashes}",
                        )
                    }
                    val restoredUrls = selectInAppArtworkContinuityUrls(
                        currentUrls = currentUrls,
                        cachedUrls = cacheProbe.exact?.urls,
                        cachedAtUptimeMillis = cacheProbe.exact?.capturedAtUptimeMillis,
                        nowUptimeMillis = SystemClock.uptimeMillis(),
                        ttlMillis = IN_APP_ARTWORK_CONTINUITY_TTL_MS,
                    ) ?: run {
                        if (cacheProbe.exact != null) {
                            synchronized(inAppListenNowArtworkContinuityCache) {
                                inAppListenNowArtworkContinuityCache.remove(key)
                            }
                        }
                        return@installHook
                    }
                    liveDataSetValue.invoke(liveData, restoredUrls.toTypedArray())
                    inAppListenNowSeededArtwork[liveData] = InAppListenNowSeededArtwork(
                        key = key,
                        urls = restoredUrls,
                    )
                    if (BuildConfig.DEBUG) {
                        logMetadataIdentity(
                            event = "listen_now_artwork_continuity_seeded",
                            details = "contentId=${key.id}, persistentId=${key.persistentId}, " +
                                "contentType=${key.contentType}, artworkHash=" +
                                "${key.artworkIdentity.hashCode()}, urls=${restoredUrls.size}, " +
                                "urlHash=${restoredUrls.hashCode()}",
                        )
                    }
                },
            )

            hookRegistrar.installConditionalVoidSkip(resolverSubmitMethod) { chain ->
                val delegate = chain.args.firstOrNull()
                    ?.takeIf(delegateClass::isInstance)
                    ?: return@installConditionalVoidSkipHook false
                val liveData = runCatching { delegateLiveDataField.get(delegate) }.getOrNull()
                    ?: return@installConditionalVoidSkipHook false
                resolveInAppListenNowCatalogIdentity(
                    liveData = liveData,
                    delegateKey = inAppListenNowArtworkContinuityKey(delegate),
                )
                val seeded = inAppListenNowSeededArtwork[liveData]
                    ?: return@installConditionalVoidSkipHook false
                val effectiveKey = preferredInAppListenNowArtworkKey(
                    builderKey = inAppListenNowArtworkKeysByLiveData[liveData],
                    delegateKey = inAppListenNowArtworkContinuityKey(delegate),
                )
                val currentUrls = normalizedInAppArtworkValueUrls(
                    runCatching { liveDataGetValue.invoke(liveData) }.getOrNull()
                )
                val skip = shouldSkipInAppListenNowArtworkLookup(
                    keyMatches = effectiveKey == seeded.key,
                    currentUrls = currentUrls,
                    seededUrls = seeded.urls,
                )
                if (skip && BuildConfig.DEBUG) {
                    logMetadataIdentity(
                        event = "listen_now_artwork_lookup_skipped",
                        details = "contentId=${seeded.key.id}, " +
                            "persistentId=${seeded.key.persistentId}, " +
                            "contentType=${seeded.key.contentType}, " +
                            "urlHash=${seeded.urls.hashCode()}",
                    )
                }
                skip
            }
            inAppListenNowArtworkContinuityHookInstalled = true
            ProviderLogger.info(
                "Apple Music 主页 Listen Now 封面连续性 Hook 已安装: " +
                    "builder=${builderMethod.name}/${builderMethod.parameterCount}, " +
                    "resolver=${resolverSubmitMethod.name}/${resolverSubmitMethod.parameterCount}, " +
                    "fallback=${resolvedBuilder.compatibilityFallback ||
                        resolvedArtworkSubmit.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 主页 Listen Now 封面连续性 Hook 安装失败", it)
        }
    }

    /**
     * Listen Now 的原 builder 会把 MediaEntity 当前文本复制到 Epoxy model。
     * 因此必须在原方法运行前登记类型并消费已预热缓存，且不改动封面 LiveData。
     */
    private fun primeInAppListenNowMetadata(
        entity: Any,
        resolvedCatalogId: String? = null,
    ) {
        val kind = inAppLibraryEntityKindForClassNames(
            generateSequence(entity.javaClass as Class<*>?) { it.superclass }
                .map(Class<*>::getName)
                .toList()
        ) ?: return
        val attributes = mediaApiEntityAttributes(entity) ?: return
        val mediaId = resolvedCatalogId
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: mediaApiEntityCatalogId(entity, attributes)
            ?: return
        registerInAppLibraryEntity(
            mediaId = mediaId,
            entity = entity,
            kind = kind,
            knownAttributes = attributes,
            requestResolution = false,
            retainEntityRef = true,
        )
        enrichInAppLibraryEntity(mediaId, entity, kind, attributes)

        val entityType = localizedEntityTypeForInAppLibraryKind(kind)
        val localizedCacheHit = playbackMetadataOverrides.containsKey(mediaId)
        val originalCacheProbeDue = isRestoreCjkOriginalMetadataEnabled() &&
            !originalMetadataOverrides.containsKey(mediaId) &&
            shouldRetryOriginalMetadataCacheProbe(mediaId)
        val originalCacheHit = if (originalCacheProbeDue) {
            internalCatalogResolver.cachedOriginalEntity(
                mediaId = mediaId,
                entityType = entityType,
                lookupIds = playbackMetadataLookupIds[mediaId].orEmpty(),
            )?.also { alias ->
                originalMetadataPendingIds.remove(mediaId)
                rememberOriginalMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    confirmed = true,
                )
                alias.language.takeIf(String::isNotBlank)?.let { language ->
                    rememberOriginalLanguageForArtist(mediaId, language)
                }
            }
        } else {
            null
        }
        if (originalCacheProbeDue && originalCacheHit == null) {
            // A populated SQLite cache may not be in the bounded in-memory warm set. Probe it
            // before the localized request so a late original result cannot be stranded behind a
            // slow or deduplicated localized callback.
            resolveCachedOriginalEntityForInApp(
                mediaId = mediaId,
                entityType = entityType,
                preBind = true,
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
            )
        }
        val alias = effectiveInAppMetadataOverride(mediaId)
        val originalApplied = originalCacheHit?.let {
            applyAliasToInAppLibraryEntity(entity, kind, it)
        } == true
        val cacheMiss = shouldRequestInAppMetadataOverride(mediaId)
        if (cacheMiss) {
            // 主页先覆盖设定地区，原地区结果随后按优先级补回，避免空缓存阻塞首屏。
            markMetadataVisible(listOf(mediaId))
            scheduleInAppMetadataResolution(
                mediaIds = listOf(mediaId),
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                originalResolutionMode = InAppOriginalResolutionMode.AFTER_LOCALIZED,
            )
            if (BuildConfig.DEBUG) {
                ProviderLogger.info(
                    "Apple Music 元数据链路: seq=${metadataTraceSequence.incrementAndGet()}, " +
                        "event=listen_now_metadata_resolution_dispatched, " +
                        "contentId=$mediaId, kind=$kind, priority=VISIBLE, " +
                        "originalResolutionMode=AFTER_LOCALIZED"
                )
            }
        }
        if (BuildConfig.DEBUG) {
            logMetadataIdentity(
                event = "listen_now_metadata_primed",
                details = "contentId=$mediaId, kind=$kind, entityType=$entityType, " +
                    "localizedCacheHit=$localizedCacheHit, " +
                    "originalCacheHit=${originalCacheHit != null}, " +
                    "originalCacheProbeDue=$originalCacheProbeDue, " +
                    "originalApplied=$originalApplied, " +
                    "cacheMiss=$cacheMiss, request=$cacheMiss, " +
                    "effective=${alias?.title}/${alias?.artist}/${alias?.album}",
            )
        }
    }

    private fun recordInAppListenNowModelBuildState(
        model: Any,
        entity: Any,
        liveData: Any,
        builderKey: InAppListenNowArtworkContinuityKey?,
    ) {
        val directMediaId = mediaApiEntityCatalogId(entity)
        val state = InAppListenNowModelBuildState(
            entity = WeakReference(entity),
            liveData = WeakReference(liveData),
            builderKey = builderKey,
            initialCatalogId = directMediaId,
            builtAlias = directMediaId?.let { mediaId ->
                effectiveInAppMetadataOverride(mediaId)?.let { alias ->
                    AppliedMetadataAlias(mediaId, alias)
                }
            },
        )
        inAppListenNowModelBuildStates[model] = state
        inAppListenNowModelBuildStatesByLiveData[liveData] = state
    }

    /**
     * Listen Now's standard card copies MediaEntity text into its Epoxy model before binding.
     * The model has no observable metadata source, so a later catalog result must update the
     * already-bound DataBinding directly. This hook is kept on the profiled bound-listener
     * callback so recycled cards can be re-associated with their current MediaEntity.
     */
    private fun hookInAppListenNowMetadataBinding() {
        runCatching {
            val resolvedOnModelBound = hookResolver.resolveMethod(
                AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER
            )
            val modelClass = hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MODEL
            ).clazz
            val mediaEntityClass = hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY
            ).clazz
            val onModelBoundMethod = resolvedOnModelBound.method
            hookRegistrar.install(onModelBoundMethod, before = { chain ->
                listenNowDataBindingArgument(chain.args.getOrNull(1))?.let { binding ->
                    beginInAppListenNowDataBindingBind(binding)
                }
            }, after = { chain, _ ->
                val model = chain.args.firstOrNull()
                    ?.takeIf(modelClass::isInstance)
                    ?: return@installHook
                val listener = chain.thisObject ?: return@installHook
                val entity = debugFieldValueByType(listener, mediaEntityClass)
                    ?: return@installHook
                val binding = listenNowDataBindingArgument(chain.args.getOrNull(1))
                    ?: return@installHook
                val buildState = inAppListenNowModelBuildStates[model]
                buildState?.boundBinding = InAppListenNowBoundBinding(
                    binding = WeakReference(binding),
                    bindGeneration = inAppDataBindingBindGeneration(binding),
                )
                val mediaId = mediaApiEntityCatalogId(entity)
                    ?: buildState?.catalogId
                    ?: return@installHook
                captureInAppDataBinding(binding)
                registerInAppDataBinding(mediaId, binding)
                registerInAppListenNowDataBinding(mediaId, binding)
                val alias = effectiveInAppMetadataOverride(mediaId) ?: return@installHook
                val appliedAlias = AppliedMetadataAlias(mediaId, alias)
                if (
                    buildState?.catalogId == mediaId &&
                    buildState.builtAlias == appliedAlias
                ) {
                    val values = dataBindingAliasValues(mediaId, alias, binding)
                    val renderedTexts = inAppDataBindingRootViews[binding]
                        ?.get()
                        ?.let(::dataBindingRenderedTexts)
                        .orEmpty()
                    if (
                        renderedTexts.isEmpty() ||
                        dataBindingAliasAlreadyRendered(
                            expectedTitle = values.title,
                            expectedSubtitle = values.subtitle,
                            renderedTexts = renderedTexts,
                        )
                    ) {
                        // The builder wrote the alias before model creation. Keep the fast path
                        // only while the bound views do not prove that Apple restored old text.
                        inAppDataBindingAppliedAliases[binding] = appliedAlias
                        return@installHook
                    }
                }
                refreshInAppListenNowDataBindingRefs(mediaId, alias)
                if (BuildConfig.DEBUG) {
                    logMetadataIdentity(
                        event = "listen_now_metadata_binding_refresh",
                        details = "contentId=$mediaId, model=${model.javaClass.name}, " +
                            "binding=${binding.javaClass.name}@" +
                            "${System.identityHashCode(binding)}, " +
                            "buildAlias=${buildState?.builtAlias?.title}/" +
                            "${buildState?.builtAlias?.artist}, " +
                            "effective=${alias.title}/${alias.artist}/${alias.album}",
                    )
                }
            })
            ProviderLogger.info(
                "Apple Music 主页 Listen Now 文字绑定 Hook 已安装: " +
                    "bound=${onModelBoundMethod.name}/${onModelBoundMethod.parameterCount}, " +
                    "fallback=${resolvedOnModelBound.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 主页 Listen Now 文字绑定 Hook 安装失败", it)
        }
    }

    private fun listenNowDataBindingArgument(argument: Any?): Any? =
        argument
            ?.takeIf { candidate ->
                dataBindingBaseClass?.isInstance(candidate) == true
            }
            // Older profiles may still pass an Epoxy holder instead of ViewDataBinding.
            ?: epoxyDataBindingFromHolder(argument)

    private fun beginInAppListenNowDataBindingBind(binding: Any) {
        beginInAppDataBindingModelBind(binding)
        synchronized(inAppListenNowDataBindingPendingRefreshes) {
            inAppListenNowDataBindingPendingRefreshes.remove(binding)
        }
        inAppDataBindingMediaIds.remove(binding)
        inAppListenNowDataBindingMediaIds.remove(binding)
    }

    private fun registerInAppListenNowDataBinding(
        mediaId: String,
        binding: Any,
    ) {
        inAppListenNowDataBindingMediaIds[binding] = mediaId
        val refs = inAppListenNowDataBindingRefs.computeIfAbsent(mediaId) {
            ConcurrentLinkedQueue()
        }
        var registered = false
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null) {
                refs.remove(ref)
            } else if (target === binding) {
                registered = true
            }
        }
        if (!registered) refs.add(WeakReference(binding))
    }

    private fun resolveInAppListenNowCatalogIdentity(
        liveData: Any,
        delegateKey: InAppListenNowArtworkContinuityKey?,
    ) {
        val state = inAppListenNowModelBuildStatesByLiveData[liveData] ?: return
        val mediaId = listenNowCatalogIdForExactCard(
            builderLiveData = state.liveData.get(),
            delegateLiveData = liveData,
            builderKey = state.builderKey,
            delegateKey = delegateKey,
        ) ?: return
        if (!state.assignCatalogId(mediaId)) return
        if (BuildConfig.DEBUG) {
            logMetadataIdentity(
                event = "listen_now_catalog_identity_mapped",
                details = "localId=${state.builderKey?.id}, contentId=$mediaId, " +
                    "liveData=${debugLibraryArtworkObjectIdentity(liveData)}, " +
                    "persistentId=${state.builderKey?.persistentId}, " +
                    "contentType=${state.builderKey?.contentType}",
            )
        }
        mainHandler.post {
            if (inAppListenNowModelBuildStatesByLiveData[liveData] !== state ||
                state.catalogId != mediaId
            ) return@post
            state.entity.get()?.let { entity ->
                primeInAppListenNowMetadata(entity, resolvedCatalogId = mediaId)
            }
            registerResolvedInAppListenNowBinding(state, mediaId)
        }
    }

    private fun registerResolvedInAppListenNowBinding(
        state: InAppListenNowModelBuildState,
        mediaId: String,
    ) {
        val bound = state.boundBinding ?: return
        val binding = bound.binding.get() ?: return
        if (inAppDataBindingBindGeneration(binding) != bound.bindGeneration) return
        captureInAppDataBinding(binding)
        registerInAppDataBinding(mediaId, binding)
        registerInAppListenNowDataBinding(mediaId, binding)
        effectiveInAppMetadataOverride(mediaId)?.let { alias ->
            refreshInAppListenNowDataBindingRefs(mediaId, alias)
        }
    }

    private fun refreshInAppListenNowDataBindingRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): Int {
        val refs = inAppListenNowDataBindingRefs[mediaId] ?: return 0
        val appliedAlias = AppliedMetadataAlias(mediaId, alias)
        var scheduledTargets = 0
        refs.forEach { ref ->
            val binding = ref.get()
            if (binding == null) {
                refs.remove(ref)
                return@forEach
            }
            if (inAppListenNowDataBindingMediaIds[binding] != mediaId) {
                refs.remove(ref)
                return@forEach
            }
            val previousAppliedAlias = inAppDataBindingAppliedAliases[binding]
            if (previousAppliedAlias == appliedAlias) {
                val values = dataBindingAliasValues(mediaId, alias, binding)
                val renderedTexts = inAppDataBindingRootViews[binding]
                    ?.get()
                    ?.let(::dataBindingRenderedTexts)
                    .orEmpty()
                if (!shouldRefreshListenNowDataBindingAlias(
                        appliedAlias = previousAppliedAlias,
                        requestedAlias = appliedAlias,
                        expectedTitle = values.title,
                        expectedSubtitle = values.subtitle,
                        renderedTexts = renderedTexts,
                    )
                ) {
                    return@forEach
                }
                if (BuildConfig.DEBUG) {
                    logMetadataIdentity(
                        event = "listen_now_binding_text_stale",
                        details = "contentId=$mediaId, binding=${binding.javaClass.name}@" +
                            "${System.identityHashCode(binding)}, " +
                            "expected=${values.title}/${values.subtitle}, rendered=$renderedTexts",
                    )
                }
            }
            val bindGeneration = inAppDataBindingBindGeneration(binding)
            val pending = PendingDataBindingRefresh(
                mediaId = mediaId,
                alias = appliedAlias,
                bindGeneration = bindGeneration,
            )
            val shouldPost = synchronized(inAppListenNowDataBindingPendingRefreshes) {
                val current = inAppListenNowDataBindingPendingRefreshes[binding]
                if (current == pending) {
                    false
                } else {
                    inAppListenNowDataBindingPendingRefreshes[binding] = pending
                    true
                }
            }
            if (!shouldPost) return@forEach
            scheduledTargets += 1
            mainHandler.post {
                if (inAppListenNowDataBindingPendingRefreshes[binding] != pending) {
                    return@post
                }
                fun clearPending() {
                    synchronized(inAppListenNowDataBindingPendingRefreshes) {
                        if (inAppListenNowDataBindingPendingRefreshes[binding] == pending) {
                            inAppListenNowDataBindingPendingRefreshes.remove(binding)
                        }
                    }
                }
                if (!isDataBindingRefreshCurrent(
                        currentMediaId = inAppListenNowDataBindingMediaIds[binding],
                        requestedMediaId = mediaId,
                        currentBindGeneration = inAppDataBindingBindGeneration(binding),
                        scheduledBindGeneration = bindGeneration,
                    )
                ) {
                    clearPending()
                    return@post
                }
                runCatching {
                    val values = dataBindingAliasValues(mediaId, alias, binding)
                    val variableResults = applyAliasToInAppDataBindingVariables(binding, values)
                    if (
                        dataBindingRefreshStrategy(
                            expectedTitle = values.title,
                            expectedSubtitle = values.subtitle,
                            titleApplied = variableResults.titleApplied,
                            subtitleApplied = variableResults.subtitleApplied,
                        ) == DataBindingRefreshStrategy.FULL_INVALIDATE
                    ) {
                        dataBindingInvalidateAllMethod?.invoke(binding)
                    }
                    dataBindingExecutePendingBindingsMethod?.invoke(binding)
                    inAppDataBindingAppliedAliases[binding] = appliedAlias
                }.onFailure {
                    ProviderLogger.error(
                        "Apple Music 主页 Listen Now 文字绑定刷新失败: " +
                            "id=$mediaId, binding=${binding.javaClass.name}",
                        it,
                    )
                }
                clearPending()
            }
        }
        return scheduledTargets
    }

    private fun inAppListenNowArtworkContinuityKey(
        item: Any,
    ): InAppListenNowArtworkContinuityKey? = inAppListenNowArtworkIdentity(item).key

    private fun inAppListenNowArtworkIdentity(
        item: Any,
    ): InAppListenNowArtworkIdentity {
        val id = runCatching { AppleReflection.call(item, "getId")?.toString() }
            .getOrNull()
            ?.trim()
            .orEmpty()
        val persistentId = runCatching {
            (AppleReflection.call(item, "getPersistentId") as? Number)?.toLong()
        }.getOrNull() ?: 0L
        val contentType = runCatching {
            (AppleReflection.call(item, "getContentType") as? Number)?.toInt()
        }.getOrNull() ?: -1
        val artworkTokenEntries = runCatching {
            @Suppress("UNCHECKED_CAST")
            (AppleReflection.call(item, "getAllArtworkTokens") as? Map<Any?, Any?>)
                .orEmpty()
                .entries
                .mapNotNull { (variant, token) ->
                    val normalizedToken = token?.toString()?.trim().orEmpty()
                    if (normalizedToken.isEmpty()) null else "$variant=$normalizedToken"
                }
                .sorted()
        }.getOrDefault(emptyList())
        val artworkTokens = artworkTokenEntries.joinToString("|")
        val fetchableArtworkToken = runCatching {
            AppleReflection.call(item, "getFetchableArtworkToken")?.toString()
        }.getOrNull()?.trim().orEmpty()
        val artworkToken = runCatching {
            AppleReflection.call(item, "getArtworkToken")?.toString()
        }.getOrNull()?.trim().orEmpty()
        val singularArtworkToken = fetchableArtworkToken.ifEmpty { artworkToken }
        val artworkIdentity = artworkTokens.ifEmpty { singularArtworkToken }
        val key = if (id.isEmpty() || persistentId == 0L || artworkIdentity.isEmpty()) {
            null
        } else {
            InAppListenNowArtworkContinuityKey(
                id = id,
                persistentId = persistentId,
                contentType = contentType,
                artworkIdentity = artworkIdentity,
            )
        }
        return InAppListenNowArtworkIdentity(
            id = id,
            persistentId = persistentId,
            contentType = contentType,
            allArtworkTokenCount = artworkTokenEntries.size,
            allArtworkIdentity = artworkTokens,
            fetchableArtworkToken = fetchableArtworkToken,
            artworkToken = artworkToken,
            selectedArtworkIdentity = artworkIdentity,
            key = key,
        )
    }

    private fun debugInAppListenNowArtworkIdentity(
        identity: InAppListenNowArtworkIdentity,
    ): String =
        "contentId=${identity.id.ifEmpty { "none" }}, " +
            "persistentId=${identity.persistentId}, contentType=${identity.contentType}, " +
            "allTokenCount=${identity.allArtworkTokenCount}, " +
            "allTokenHash=${identity.allArtworkIdentity.takeIf(String::isNotEmpty)?.hashCode()}, " +
            "fetchableTokenHash=" +
            "${identity.fetchableArtworkToken.takeIf(String::isNotEmpty)?.hashCode()}, " +
            "artworkTokenHash=${identity.artworkToken.takeIf(String::isNotEmpty)?.hashCode()}, " +
            "selectedArtworkHash=" +
            "${identity.selectedArtworkIdentity.takeIf(String::isNotEmpty)?.hashCode()}, " +
            "keyValid=${identity.key != null}"

    private fun putInAppListenNowArtworkContinuity(
        key: InAppListenNowArtworkContinuityKey,
        urls: Collection<String>,
    ) {
        val normalizedUrls = urls.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalizedUrls.isEmpty()) return
        synchronized(inAppListenNowArtworkContinuityCache) {
            inAppListenNowArtworkContinuityCache[key] = InAppArtworkContinuityEntry(
                urls = normalizedUrls,
                capturedAtUptimeMillis = SystemClock.uptimeMillis(),
            )
        }
    }

    /**
     * Debug-only trace for the real Listen Now / Home artwork path.
     *
     * The profiled model builder creates one MutableLiveData<String[]> per card and seeds it
     * from the feed image URL. The profiled bound listener submits a second medialibrary artwork
     * lookup only when the entity has a persistent ID. The trace follows that exact LiveData
     * through the profiled resolver, delegate, and image view so a reproduction can distinguish
     * a duplicate URL publication from an actual clear/rebind or a replacement card View.
     */
    private fun hookDebugListenNowArtworkLifecycle() {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val resolvedOnModelBound = hookResolver.resolveMethod(
                AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER
            )
            val resolvedArtworkSubmit = hookResolver.resolveMethod(
                AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER
            )
            val modelClass = hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MODEL
            ).clazz
            val delegateClass = hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM
            ).clazz
            val customImageViewClass = hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW
            ).clazz
            val mediaEntityClass = hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY
            ).clazz
            val liveDataClass = classLoader.loadClass("androidx.lifecycle.MutableLiveData")

            val onModelBoundMethod = resolvedOnModelBound.method
            val resolverSubmitMethod = resolvedArtworkSubmit.method
            val delegateLiveDataField = generateSequence(delegateClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { field -> liveDataClass.isAssignableFrom(field.type) }
                .apply { isAccessible = true }
            val delegateGetImageUrl = AppleReflection.findMethod(delegateClass, "getImageUrl", 0)
            val delegateGetImageUrls = AppleReflection.findMethod(delegateClass, "getImageUrls", 0)
            val liveDataGetValue = AppleReflection.findMethod(liveDataClass, "getValue", 0)
            val liveDataMutationMethods = listOf(
                AppleReflection.findMethod(liveDataClass, "postValue", 1),
                AppleReflection.findMethod(liveDataClass, "setValue", 1),
            )
            val delegateArtworkMethods = delegateClass.declaredMethods.filter { method ->
                (method.name == "setImageUrl" &&
                    method.parameterTypes.firstOrNull() == String::class.java) ||
                    (method.name == "setImageUrls" &&
                        method.parameterTypes.contentEquals(arrayOf(Array<String>::class.java)))
            }.onEach { it.isAccessible = true }
            check(delegateArtworkMethods.isNotEmpty()) {
                "Listen Now delegate artwork setters unavailable"
            }
            val customImageMutationMethods = listOf(
                customImageViewClass.getDeclaredMethod(
                    "setImageDrawable",
                    Drawable::class.java,
                ),
                customImageViewClass.getDeclaredMethod("setBitmap", Bitmap::class.java),
            ).onEach { it.isAccessible = true }

            hookRegistrar.install(
                onModelBoundMethod,
                before = { chain ->
                    val listener = chain.thisObject ?: return@installHook
                    val model = chain.args.firstOrNull()
                        ?.takeIf(modelClass::isInstance)
                        ?: return@installHook
                    val entity = debugFieldValueByType(listener, mediaEntityClass)
                        ?: return@installHook
                    val persistentIdValue = runCatching {
                        AppleReflection.call(entity, "getPersistentId")
                    }.getOrNull() ?: return@installHook
                    val persistentId = (persistentIdValue as? Number)?.toLong()
                        ?: return@installHook
                    val liveData = debugFieldValueByType(listener, liveDataClass)
                        ?: return@installHook
                    val binding = epoxyDataBindingFromHolder(chain.args.getOrNull(1))
                    val root = runCatching {
                        binding?.let { AppleReflection.call(it, "getRoot") as? View }
                    }.getOrNull()
                    val imageViews = debugListenNowImageViews(root)
                    val mediaId = runCatching {
                        AppleReflection.call(entity, "getId")?.toString()
                    }.getOrNull()?.trim().orEmpty()
                    val title = runCatching {
                        AppleReflection.call(entity, "getTitle")?.toString()
                    }.getOrNull()?.replace('\n', ' ')?.take(96)
                    val contentType = runCatching {
                        (AppleReflection.call(entity, "getContentType") as? Number)?.toInt()
                    }.getOrNull() ?: -1
                    val mediaKey = "$mediaId:$persistentId:$contentType"
                    val trace = DebugListenNowArtworkTrace(
                        mediaKey = mediaKey,
                        mediaId = mediaId.ifEmpty { "none" },
                        title = title,
                        persistentId = persistentId,
                        contentType = contentType,
                        liveData = WeakReference(liveData),
                        model = WeakReference(model),
                        root = root?.let(::WeakReference),
                        imageViews = imageViews.map(::WeakReference),
                    )
                    val previous = debugListenNowLatestArtworkTraces.put(mediaKey, trace)
                    debugListenNowArtworkLiveData[liveData] = trace
                    imageViews.forEach { imageView ->
                        debugListenNowArtworkImageViews[imageView] = trace
                    }
                    val currentValue = runCatching {
                        liveDataGetValue.invoke(liveData)
                    }.getOrNull()
                    ProviderLogger.diagnostic(
                        "ListenNowArtwork: event=model_bound_before, " +
                            debugListenNowArtworkTraceIdentity(trace) + ", " +
                            "moduleVersion=${BuildConfig.VERSION_CODE}, " +
                            "continuityInstalled=" +
                            "$inAppListenNowArtworkContinuityHookInstalled, " +
                            "model=${debugLibraryArtworkObjectIdentity(model)}, " +
                            "previousModel=${debugLibraryArtworkObjectIdentity(previous?.model?.get())}, " +
                            "root=${debugLibraryArtworkObjectIdentity(root)}, " +
                            "previousRoot=${debugLibraryArtworkObjectIdentity(previous?.root?.get())}, " +
                            "liveData=${debugLibraryArtworkObjectIdentity(liveData)}, " +
                            "value=${debugListenNowArtworkValueSummary(currentValue)}, " +
                            "images=${debugListenNowArtworkImageStates(trace)}"
                    )
                },
                after = { chain, _ ->
                    val listener = chain.thisObject ?: return@installHook
                    val liveData = debugFieldValueByType(listener, liveDataClass)
                        ?: return@installHook
                    val trace = debugListenNowArtworkLiveData[liveData]
                        ?: return@installHook
                    debugListenNowLogTraceSnapshot(
                        trace = trace,
                        stage = "model_bound_after",
                        liveDataGetValue = liveDataGetValue,
                    )
                    trace.root?.get()?.let { root ->
                        root.post {
                            debugListenNowLogTraceSnapshot(
                                trace = trace,
                                stage = "model_bound_next_frame",
                                liveDataGetValue = liveDataGetValue,
                            )
                        }
                        root.postDelayed(
                            {
                                debugListenNowLogTraceSnapshot(
                                    trace = trace,
                                    stage = "model_bound_250ms",
                                    liveDataGetValue = liveDataGetValue,
                                )
                            },
                            250L,
                        )
                    }
                },
            )

            hookRegistrar.install(
                resolverSubmitMethod,
                before = { chain ->
                    val delegate = chain.args.firstOrNull()
                        ?.takeIf(delegateClass::isInstance)
                        ?: return@installHook
                    val liveData = runCatching { delegateLiveDataField.get(delegate) }
                        .getOrNull()
                        ?: return@installHook
                    val trace = debugListenNowArtworkLiveData[liveData]
                        ?: return@installHook
                    debugListenNowArtworkDelegates[delegate] = trace
                    ProviderLogger.diagnostic(
                        "ListenNowArtwork: event=library_lookup_submit, " +
                            debugListenNowArtworkTraceIdentity(trace) + ", " +
                            "delegate=${debugLibraryArtworkObjectIdentity(delegate)}, " +
                            "liveData=${debugLibraryArtworkObjectIdentity(liveData)}, " +
                            "delegateValue=${debugListenNowDelegateArtworkSummary(
                                delegate,
                                delegateGetImageUrl,
                                delegateGetImageUrls,
                            )}, liveValue=${debugListenNowArtworkValueSummary(
                                runCatching { liveDataGetValue.invoke(liveData) }.getOrNull()
                            )}"
                    )
                },
                after = { chain, _ ->
                    val delegate = chain.args.firstOrNull()
                        ?.takeIf(delegateClass::isInstance)
                        ?: return@installHook
                    val trace = debugListenNowArtworkDelegates[delegate]
                        ?: return@installHook
                    ProviderLogger.diagnostic(
                        "ListenNowArtwork: event=library_lookup_submitted, " +
                            debugListenNowArtworkTraceIdentity(trace) + ", " +
                            "delegate=${debugLibraryArtworkObjectIdentity(delegate)}"
                    )
                },
            )

            delegateArtworkMethods.forEach { method ->
                hookRegistrar.install(
                    method,
                    before = { chain ->
                        val delegate = chain.thisObject ?: return@installHook
                        val trace = debugListenNowTraceForDelegate(
                            delegate = delegate,
                            delegateLiveDataField = delegateLiveDataField,
                        ) ?: return@installHook
                        val liveData = trace.liveData.get()
                        val currentLiveValue = liveData?.let { target ->
                            runCatching { liveDataGetValue.invoke(target) }.getOrNull()
                        }
                        val incoming = chain.args.firstOrNull()
                        ProviderLogger.diagnostic(
                            "ListenNowArtwork: event=delegate_${method.name}_before, " +
                                debugListenNowArtworkTraceIdentity(trace) + ", " +
                                "delegate=${debugLibraryArtworkObjectIdentity(delegate)}, " +
                                "incoming=${debugListenNowArtworkValueSummary(incoming)}, " +
                                "sameAsLive=${debugListenNowArtworkUrls(incoming) ==
                                    debugListenNowArtworkUrls(currentLiveValue)}, " +
                                "liveValue=${debugListenNowArtworkValueSummary(currentLiveValue)}"
                        )
                    },
                    after = { chain, _ ->
                        val delegate = chain.thisObject ?: return@installHook
                        val trace = debugListenNowTraceForDelegate(
                            delegate = delegate,
                            delegateLiveDataField = delegateLiveDataField,
                        ) ?: return@installHook
                        ProviderLogger.diagnostic(
                            "ListenNowArtwork: event=delegate_${method.name}_after, " +
                                debugListenNowArtworkTraceIdentity(trace) + ", " +
                                "delegateValue=${debugListenNowDelegateArtworkSummary(
                                    delegate,
                                    delegateGetImageUrl,
                                    delegateGetImageUrls,
                                )}, images=${debugListenNowArtworkImageStates(trace)}"
                        )
                    },
                )
            }

            liveDataMutationMethods.forEach { method ->
                hookRegistrar.install(
                    method,
                    before = { chain ->
                        val liveData = chain.thisObject ?: return@installHook
                        val trace = debugListenNowArtworkLiveData[liveData]
                            ?: return@installHook
                        val current = runCatching { liveDataGetValue.invoke(liveData) }
                            .getOrNull()
                        val incoming = chain.args.firstOrNull()
                        ProviderLogger.diagnostic(
                            "ListenNowArtwork: event=live_data_${method.name}_before, " +
                                debugListenNowArtworkTraceIdentity(trace) + ", " +
                                "liveData=${debugLibraryArtworkObjectIdentity(liveData)}, " +
                                "incoming=${debugListenNowArtworkValueSummary(incoming)}, " +
                                "current=${debugListenNowArtworkValueSummary(current)}, " +
                                "same=${debugListenNowArtworkUrls(incoming) ==
                                    debugListenNowArtworkUrls(current)}, " +
                                "images=${debugListenNowArtworkImageStates(trace)}"
                        )
                    },
                    after = { chain, _ ->
                        val liveData = chain.thisObject ?: return@installHook
                        val trace = debugListenNowArtworkLiveData[liveData]
                            ?: return@installHook
                        debugListenNowLogTraceSnapshot(
                            trace = trace,
                            stage = "live_data_${method.name}_after",
                            liveDataGetValue = liveDataGetValue,
                        )
                        if (method.name == "postValue") {
                            mainHandler.post {
                                debugListenNowLogTraceSnapshot(
                                    trace = trace,
                                    stage = "live_data_postValue_committed",
                                    liveDataGetValue = liveDataGetValue,
                                )
                            }
                        }
                    },
                )
            }

            customImageMutationMethods.forEach { method ->
                hookRegistrar.install(
                    method,
                    before = { chain ->
                        val imageView = chain.thisObject ?: return@installHook
                        val trace = debugListenNowArtworkImageViews[imageView]
                            ?: return@installHook
                        ProviderLogger.diagnostic(
                            "ListenNowArtwork: event=image_${method.name}_before, " +
                                debugListenNowArtworkTraceIdentity(trace) + ", " +
                                "view=${debugLibraryArtworkObjectIdentity(imageView)}, " +
                                "incoming=${debugListenNowImageMutationSummary(
                                    chain.args.firstOrNull()
                                )}, state=${debugListenNowImageViewState(imageView as ImageView)}"
                        )
                    },
                    after = { chain, _ ->
                        val imageView = chain.thisObject ?: return@installHook
                        val trace = debugListenNowArtworkImageViews[imageView]
                            ?: return@installHook
                        ProviderLogger.diagnostic(
                            "ListenNowArtwork: event=image_${method.name}_after, " +
                                debugListenNowArtworkTraceIdentity(trace) + ", " +
                                "view=${debugLibraryArtworkObjectIdentity(imageView)}, " +
                                "state=${debugListenNowImageViewState(imageView as ImageView)}"
                        )
                    },
                )
            }

            ProviderLogger.info(
                "Apple Music 主页 Listen Now 封面诊断 Hook 已安装: " +
                    "bound=${onModelBoundMethod.name}/${onModelBoundMethod.parameterCount}, " +
                    "resolver=${resolverSubmitMethod.name}/${resolverSubmitMethod.parameterCount}, " +
                    "delegateMethods=${delegateArtworkMethods.size}, " +
                    "imageMethods=${customImageMutationMethods.size}, " +
                    "fallback=${resolvedOnModelBound.compatibilityFallback ||
                        resolvedArtworkSubmit.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 主页 Listen Now 封面诊断 Hook 安装失败", it)
        }
    }

    private fun debugFieldValueByType(instance: Any, fieldType: Class<*>): Any? =
        generateSequence(instance.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { field -> fieldType.isAssignableFrom(field.type) }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()
            }

    private fun debugListenNowTraceForDelegate(
        delegate: Any,
        delegateLiveDataField: Field,
    ): DebugListenNowArtworkTrace? {
        debugListenNowArtworkDelegates[delegate]?.let { return it }
        val liveData = runCatching { delegateLiveDataField.get(delegate) }.getOrNull()
            ?: return null
        return debugListenNowArtworkLiveData[liveData]?.also { trace ->
            debugListenNowArtworkDelegates[delegate] = trace
        }
    }

    private fun debugListenNowLogTraceSnapshot(
        trace: DebugListenNowArtworkTrace,
        stage: String,
        liveDataGetValue: Method,
    ) {
        val liveData = trace.liveData.get()
        val value = liveData?.let { target ->
            runCatching { liveDataGetValue.invoke(target) }.getOrNull()
        }
        ProviderLogger.diagnostic(
            "ListenNowArtwork: event=$stage, " +
                debugListenNowArtworkTraceIdentity(trace) + ", " +
                "liveData=${debugLibraryArtworkObjectIdentity(liveData)}, " +
                "value=${debugListenNowArtworkValueSummary(value)}, " +
                "root=${debugLibraryArtworkObjectIdentity(trace.root?.get())}, " +
                "images=${debugListenNowArtworkImageStates(trace)}"
        )
    }

    private fun debugListenNowArtworkTraceIdentity(
        trace: DebugListenNowArtworkTrace,
    ): String =
        "mediaId=${trace.mediaId}, persistentId=${trace.persistentId}, " +
            "contentType=${trace.contentType}, title=${trace.title ?: "none"}"

    private fun debugListenNowArtworkUrls(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is CharSequence -> listOf(value.toString())
        is Array<*> -> value.mapNotNull { it?.toString() }
        is Iterable<*> -> value.mapNotNull { it?.toString() }
        else -> emptyList()
    }.map(String::trim).filter(String::isNotEmpty)

    private fun debugListenNowArtworkValueSummary(value: Any?): String {
        val urls = debugListenNowArtworkUrls(value)
        val values = urls.joinToString(prefix = "[", postfix = "]") { url ->
            "len=${url.length},hash=${url.hashCode()},error=${url == "error url"}"
        }
        return "type=${value?.javaClass?.name ?: "null"},count=${urls.size}," +
            "hash=${urls.hashCode()},values=$values"
    }

    private fun debugListenNowDelegateArtworkSummary(
        delegate: Any,
        getImageUrl: Method,
        getImageUrls: Method,
    ): String {
        val single = runCatching { getImageUrl.invoke(delegate) }.getOrNull()
        if (single != null) return debugListenNowArtworkValueSummary(single)
        return debugListenNowArtworkValueSummary(
            runCatching { getImageUrls.invoke(delegate) }.getOrNull()
        )
    }

    private fun debugListenNowImageViews(root: View?): List<ImageView> {
        root ?: return emptyList()
        val result = mutableListOf<ImageView>()
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 48 && result.size < 4) {
            val view = pending.removeFirst()
            visited += 1
            if (view is ImageView) result += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let(pending::addLast)
                }
            }
        }
        return result
    }

    private fun debugListenNowArtworkImageStates(
        trace: DebugListenNowArtworkTrace,
    ): String = trace.imageViews.mapNotNull(WeakReference<ImageView>::get)
        .joinToString(prefix = "[", postfix = "]") { imageView ->
            debugListenNowImageViewState(imageView)
        }

    private fun debugListenNowImageViewState(imageView: ImageView): String =
        "${debugLibraryArtworkObjectIdentity(imageView)}{" +
            "drawable=${debugLibraryArtworkObjectIdentity(imageView.drawable)}/" +
            "${debugLibraryArtworkDrawableSignature(imageView.drawable)}," +
            "background=${debugLibraryArtworkObjectIdentity(imageView.background)}/" +
            "${debugLibraryArtworkDrawableSignature(imageView.background)}," +
            "visibility=${imageView.visibility},alpha=${imageView.alpha}," +
            "shown=${imageView.isShown},attached=${imageView.isAttachedToWindow}}"

    private fun debugListenNowImageMutationSummary(value: Any?): String = when (value) {
        null -> "null"
        is Bitmap ->
            "${debugLibraryArtworkObjectIdentity(value)}:" +
                "${value.width}x${value.height},generation=${value.generationId}"
        is Drawable ->
            "${debugLibraryArtworkObjectIdentity(value)}/" +
                debugLibraryArtworkDrawableSignature(value)
        else -> debugLibraryArtworkObjectIdentity(value)
    }

    /**
     * Apple Music 会为依赖资料库 artwork token 的卡片反复新建
     * DelegatingCollectionItemView。新实例的 notifyInitialImageUrl() 会先发布当前
     * imageUrl/imageUrls；当资料库查询尚未重新回填 URL 时，这个值是 null，图片
     * 状态会先清空，随后再收到与上一帧相同的 URL，形成返回页面时的封面闪烁。
     *
     * 这里按原始 artwork 身份保留短时 URL。只有新实例当前没有任何 URL、token
     * 身份完全一致且缓存仍新鲜时才回填；真实 artwork token 变化不会复用旧图。
     * 实际 delegate 类名由 [AppleMusicHookProfiles] 的对应版本档案统一提供。
     */
    private fun hookInAppArtworkContinuity() {
        runCatching {
            val resolvedDelegate = hookResolver.resolveClass(
                AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM
            )
            val delegateClass = resolvedDelegate.clazz
            val liveDataClass = classLoader.loadClass("androidx.lifecycle.MutableLiveData")
            val imageUrlsLiveDataField = generateSequence(delegateClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .single { field -> liveDataClass.isAssignableFrom(field.type) }
                .apply { isAccessible = true }
            val accessors = InAppArtworkContinuityAccessors(
                getId = AppleReflection.findMethod(delegateClass, "getId", 0),
                getPersistentId = AppleReflection.findMethod(
                    delegateClass,
                    "getPersistentId",
                    0,
                ),
                getContentType = AppleReflection.findMethod(delegateClass, "getContentType", 0),
                getArtworkToken = AppleReflection.findMethod(
                    delegateClass,
                    "getArtworkToken",
                    0,
                ),
                getAllArtworkTokens = AppleReflection.findMethod(
                    delegateClass,
                    "getAllArtworkTokens",
                    0,
                ),
                getImageUrl = AppleReflection.findMethod(delegateClass, "getImageUrl", 0),
                getImageUrls = AppleReflection.findMethod(delegateClass, "getImageUrls", 0),
                setImageUrl = delegateClass.declaredMethods.single { method ->
                    method.name == "setImageUrl" &&
                        method.parameterTypes.contentEquals(arrayOf(String::class.java))
                }.apply { isAccessible = true },
                setImageUrls = delegateClass.declaredMethods.single { method ->
                    method.name == "setImageUrls" &&
                        method.parameterTypes.contentEquals(arrayOf(Array<String>::class.java))
                }.apply { isAccessible = true },
                imageUrlsLiveData = imageUrlsLiveDataField,
            )
            val notifyInitialMethod = AppleReflection.findMethod(
                delegateClass,
                "notifyInitialImageUrl",
                0,
            )
            val artworkResultMethods = delegateClass.declaredMethods.filter { method ->
                (method.name == "setImageUrl" && method.parameterTypes.firstOrNull() ==
                    String::class.java) ||
                    (method.name == "setImageUrls" &&
                        method.parameterTypes.contentEquals(arrayOf(Array<String>::class.java)))
            }
            check(artworkResultMethods.isNotEmpty()) {
                "Apple Music artwork delegate result methods unavailable"
            }
            artworkResultMethods.forEach { method ->
                method.isAccessible = true
                hookRegistrar.install(method, after = { chain, _ ->
                    chain.thisObject?.let { delegate ->
                        cacheInAppArtworkContinuity(delegate, accessors)
                    }
                })
            }
            hookRegistrar.install(notifyInitialMethod, before = { chain ->
                val delegate = chain.thisObject ?: return@installHook
                val key = inAppArtworkContinuityKey(delegate, accessors)
                    ?: return@installHook
                val currentUrls = inAppArtworkUrls(delegate, accessors)
                if (currentUrls.isNotEmpty()) {
                    putInAppArtworkContinuity(key, currentUrls)
                    return@installHook
                }
                val cached = synchronized(inAppArtworkContinuityCache) {
                    inAppArtworkContinuityCache[key]
                }
                val restoredUrls = selectInAppArtworkContinuityUrls(
                    currentUrls = currentUrls,
                    cachedUrls = cached?.urls,
                    cachedAtUptimeMillis = cached?.capturedAtUptimeMillis,
                    nowUptimeMillis = SystemClock.uptimeMillis(),
                    ttlMillis = IN_APP_ARTWORK_CONTINUITY_TTL_MS,
                ) ?: run {
                    if (cached != null) {
                        synchronized(inAppArtworkContinuityCache) {
                            inAppArtworkContinuityCache.remove(key)
                        }
                    }
                    return@installHook
                }
                if (restoredUrls.size == 1) {
                    accessors.setImageUrl.invoke(delegate, restoredUrls.single())
                } else {
                    accessors.setImageUrls.invoke(delegate, restoredUrls.toTypedArray())
                }
                if (BuildConfig.DEBUG) {
                    logMetadataIdentity(
                        event = "artwork_continuity_restored",
                        details = "contentId=${key.id}, persistentId=${key.persistentId}, " +
                            "contentType=${key.contentType}, urls=${restoredUrls.size}, " +
                            "urlHash=${restoredUrls.hashCode()}",
                    )
                }
            })
            ProviderLogger.info(
                "Apple Music 资料库封面连续性 Hook 已安装: " +
                    "resultMethods=${artworkResultMethods.size}, " +
                    "fallback=${resolvedDelegate.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 资料库封面连续性 Hook 安装失败", it)
        }
    }

    private fun cacheInAppArtworkContinuity(
        delegate: Any,
        accessors: InAppArtworkContinuityAccessors,
    ) {
        val urls = inAppArtworkUrls(delegate, accessors)
        if (urls.isEmpty()) return
        val listenNowIdentity = inAppListenNowArtworkIdentity(delegate)
        val liveData = runCatching { accessors.imageUrlsLiveData.get(delegate) }.getOrNull()
        val builderKey = liveData?.let(inAppListenNowArtworkKeysByLiveData::get)
        liveData?.let { exactLiveData ->
            resolveInAppListenNowCatalogIdentity(
                liveData = exactLiveData,
                delegateKey = listenNowIdentity.key,
            )
        }
        val listenNowTrace = debugListenNowArtworkDelegates[delegate]
        if (BuildConfig.DEBUG && (builderKey != null || listenNowTrace != null)) {
            logMetadataIdentity(
                event = "listen_now_artwork_delegate_cache_candidate",
                details = "moduleVersion=${BuildConfig.VERSION_CODE}, " +
                    "liveData=${debugLibraryArtworkObjectIdentity(liveData)}, " +
                    "builderArtworkHash=${builderKey?.artworkIdentity?.hashCode()}, " +
                    "builderKeyMatchesDelegate=${builderKey == listenNowIdentity.key}, " +
                    "urls=${urls.size}, urlHash=${urls.hashCode()}, " +
                    debugInAppListenNowArtworkIdentity(listenNowIdentity),
            )
        }
        inAppArtworkContinuityKey(delegate, accessors)?.let { key ->
            putInAppArtworkContinuity(key, urls)
        }
        val listenNowCacheKey = preferredInAppListenNowArtworkKey(
            builderKey = builderKey,
            delegateKey = listenNowIdentity.key,
        )
        listenNowCacheKey?.let { key ->
            putInAppListenNowArtworkContinuity(key, urls)
            if (BuildConfig.DEBUG && (builderKey != null || listenNowTrace != null)) {
                logMetadataIdentity(
                    event = "listen_now_artwork_delegate_cache_stored",
                    details = "contentId=${key.id}, persistentId=${key.persistentId}, " +
                        "contentType=${key.contentType}, artworkHash=" +
                        "${key.artworkIdentity.hashCode()}, urls=${urls.size}, " +
                        "urlHash=${urls.hashCode()}, keyOrigin=" +
                        "${if (builderKey != null) "builder_live_data" else "delegate"}",
                )
            }
        }
    }

    private fun putInAppArtworkContinuity(
        key: InAppArtworkContinuityKey,
        urls: List<String>,
    ) {
        synchronized(inAppArtworkContinuityCache) {
            inAppArtworkContinuityCache[key] = InAppArtworkContinuityEntry(
                urls = urls.toList(),
                capturedAtUptimeMillis = SystemClock.uptimeMillis(),
            )
        }
    }

    private fun inAppArtworkContinuityKey(
        delegate: Any,
        accessors: InAppArtworkContinuityAccessors,
    ): InAppArtworkContinuityKey? {
        val id = runCatching { accessors.getId.invoke(delegate)?.toString() }
            .getOrNull()
            ?.trim()
            .orEmpty()
        val persistentId = runCatching {
            (accessors.getPersistentId.invoke(delegate) as? Number)?.toLong()
        }.getOrNull() ?: 0L
        val contentType = runCatching {
            (accessors.getContentType.invoke(delegate) as? Number)?.toInt()
        }.getOrNull() ?: -1
        val artworkToken = runCatching {
            accessors.getArtworkToken.invoke(delegate)?.toString()
        }.getOrNull()?.trim().orEmpty()
        val artworkTokens = runCatching {
            @Suppress("UNCHECKED_CAST")
            (accessors.getAllArtworkTokens.invoke(delegate) as? Map<Any?, Any?>)
                .orEmpty()
                .entries
                .mapNotNull { (variant, token) ->
                    val normalizedToken = token?.toString()?.trim().orEmpty()
                    if (normalizedToken.isEmpty()) null else "$variant=$normalizedToken"
                }
                .sorted()
                .joinToString("|")
        }.getOrDefault("")
        if (id.isEmpty() && persistentId == 0L) return null
        if (artworkToken.isEmpty() && artworkTokens.isEmpty()) return null
        return InAppArtworkContinuityKey(
            id = id,
            persistentId = persistentId,
            contentType = contentType,
            artworkToken = artworkToken,
            artworkTokens = artworkTokens,
        )
    }

    private fun inAppArtworkUrls(
        delegate: Any,
        accessors: InAppArtworkContinuityAccessors,
    ): List<String> {
        val singleUrl = runCatching {
            accessors.getImageUrl.invoke(delegate)?.toString()
        }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
        if (singleUrl != null) return listOf(singleUrl)
        return runCatching {
            (accessors.getImageUrls.invoke(delegate) as? Array<*>)
                .orEmpty()
                .mapNotNull { value ->
                    value?.toString()?.trim()?.takeIf(String::isNotEmpty)
                }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun hookInAppLibraryEpoxyRefresh() {
        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.apple.android.music.library2.LibraryMainContentEpoxyController"
            )
            val buildMethods = controllerClass.declaredMethods.filter { method ->
                method.name == "buildModels" &&
                    method.parameterCount == 5 &&
                    !method.isBridge
            }
            check(buildMethods.isNotEmpty()) {
                "LibraryMainContentEpoxyController.buildModels not found"
            }
            buildMethods.forEach { method ->
                method.isAccessible = true
                hookRegistrar.install(
                    method,
                    before = {
                        if (BuildConfig.DEBUG) {
                            logMetadataIdentity(
                                event = "library_epoxy_build_begin",
                                details = "triggerMediaId=${debugLibraryModelRefreshMediaId.get()}, " +
                                    "stack=${debugStackSummary()}",
                            )
                            debugVisibleRecyclerViews("epoxy_build_begin")
                        }
                    },
                    after = { chain, _ ->
                        val controller = chain.thisObject ?: return@installHook
                        val recentItems = chain.args.getOrNull(2) as? Iterable<*>
                            ?: return@installHook
                        val mediaIds = buildSet {
                            recentItems.forEach { entity ->
                            entity ?: return@forEach
                            val mediaId = inAppLibraryEntityIds[entity]
                                ?: mediaApiEntityCatalogId(entity)
                                ?: return@forEach
                            registerInAppLibraryController(mediaId, controller)
                                add(mediaId)
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            logMetadataIdentity(
                                event = "library_epoxy_build_end",
                                details = "triggerMediaId=${debugLibraryModelRefreshMediaId.get()}, " +
                                    "controller=${controller.javaClass.name}, " +
                                    "contentIds=$mediaIds",
                            )
                            debugVisibleRecyclerViews("epoxy_build_end")
                        }
                    },
                )
            }
            ProviderLogger.info(
                "Apple Music 资料库 Epoxy 局部刷新 Hook 已安装: " +
                    "buildMethods=${buildMethods.size}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 资料库 Epoxy 局部刷新 Hook 安装失败", it)
        }
    }

    private fun registerInAppLibraryController(mediaId: String, controller: Any) {
        val refs = inAppLibraryControllerRefs.computeIfAbsent(mediaId) {
            ConcurrentLinkedQueue()
        }
        var registered = false
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null) {
                refs.remove(ref)
            } else if (target === controller) {
                registered = true
            }
        }
        if (!registered) {
            refs.add(WeakReference(controller))
        }
    }

    private fun recordInAppLibraryControllerBuildAliases(
        controller: Any,
        mediaIds: Collection<String>,
        replace: Boolean,
    ) {
        val normalizedIds = normalizedRecyclerBindingMediaIds(mediaIds)
        synchronized(inAppLibraryControllerAppliedAliases) {
            val appliedAliases = if (replace) {
                mutableMapOf<String, AppliedMetadataAlias>().also {
                    inAppLibraryControllerAppliedAliases[controller] = it
                }
            } else {
                inAppLibraryControllerAppliedAliases.getOrPut(controller) { mutableMapOf() }
            }
            normalizedIds.forEach { mediaId ->
                val alias = effectiveInAppMetadataOverride(mediaId)
                if (alias == null) {
                    appliedAliases.remove(mediaId)
                } else {
                    appliedAliases[mediaId] = AppliedMetadataAlias(mediaId, alias)
                }
            }
            if (appliedAliases.isEmpty()) {
                inAppLibraryControllerAppliedAliases.remove(controller)
            }
        }
        if (BuildConfig.DEBUG && normalizedIds.isNotEmpty()) {
            logMetadataIdentity(
                event = "library_epoxy_build_aliases_recorded",
                details = "controller=${controller.javaClass.name}@" +
                    "${System.identityHashCode(controller)}, contentIds=$normalizedIds, " +
                    "replace=$replace",
            )
        }
    }

    private fun hookInAppLibraryComposeRefresh() {
        runCatching {
            val fragmentClass = classLoader.loadClass(
                "com.apple.android.music.library3.LibraryComposeContentFragment"
            )
            val libraryContentMethod = AppleReflection.findMethod(
                fragmentClass,
                "J1",
                parameterCount = 2,
            )
            val observeAsStateClass = classLoader.loadClass("C1.c")
            val observeAsStateMethod = AppleReflection.findMethod(
                observeAsStateClass,
                "g",
                parameterCount = 2,
            )
            val viewModelGetter = hookResolver.resolveMethod(
                AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER
            ).method
            val neverEqualPolicyClass = hookResolver.resolveClasses(
                AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY
            ).firstOrNull()?.clazz
                ?: error("Compose NeverEqualPolicy class unavailable")
            inAppLibraryComposeNeverEqualPolicy = neverEqualPolicyClass
                .declaredFields
                .singleOrNull { field ->
                    Modifier.isStatic(field.modifiers) &&
                        neverEqualPolicyClass.isAssignableFrom(field.type)
                }
                ?.apply { isAccessible = true }
                ?.get(null)
                ?: error("Compose NeverEqualPolicy singleton unavailable")
            hookRegistrar.install(
                libraryContentMethod,
                before = { chain ->
                    val fragment = chain.thisObject ?: return@installHook
                    val viewModel = runCatching { viewModelGetter.invoke(fragment) }
                        .getOrNull()
                        ?: return@installHook
                    val liveData = runCatching {
                        AppleReflection.call(viewModel, "getRecentItemsLiveResult")
                    }.getOrNull() ?: return@installHook
                    activeInAppLibraryComposeCapture.set(
                        InAppLibraryComposeCapture(fragment, liveData)
                    )
                },
                after = { chain, _ ->
                    val fragment = chain.thisObject ?: return@installHook
                    val capture = activeInAppLibraryComposeCapture.get()
                    activeInAppLibraryComposeCapture.remove()
                    val fallbackMediaIds = registerInAppLibraryComposeContent(
                        fragment = fragment,
                        viewModelGetter = viewModelGetter,
                    )
                    scheduleInAppLibraryComposeVisibleResolution(
                        fragment = fragment,
                        capturedMediaIds = capture
                            ?.takeIf { it.fragment === fragment }
                            ?.mediaIds
                            .orEmpty(),
                        fallbackMediaIds = fallbackMediaIds,
                    )
                },
            )
            hookRegistrar.install(observeAsStateMethod, after = { chain, result ->
                val capture = activeInAppLibraryComposeCapture.get()
                    ?: return@installHook
                if (chain.args.firstOrNull() !== capture.liveData) return@installHook
                val state = result ?: return@installHook
                inAppLibraryComposeStates[capture.fragment] = WeakReference(state)
            })
            ProviderLogger.info("Apple Music 资料库 Compose 局部刷新 Hook 已安装")
        }.onFailure {
            activeInAppLibraryComposeCapture.remove()
            ProviderLogger.error("Apple Music 资料库 Compose 局部刷新 Hook 安装失败", it)
        }
    }

    private fun registerInAppLibraryComposeContent(
        fragment: Any,
        viewModelGetter: Method,
    ): List<String> {
        val state = inAppLibraryComposeStates[fragment]?.get() ?: return emptyList()
        val viewModel = runCatching { viewModelGetter.invoke(fragment) }
            .getOrNull()
            ?: return emptyList()
        val liveData = runCatching {
            AppleReflection.call(viewModel, "getRecentItemsLiveResult")
        }.getOrNull() ?: return emptyList()
        val recentItems = runCatching { AppleReflection.call(liveData, "getValue") as? Iterable<*> }
            .getOrNull()
            ?: return emptyList()
        return buildList {
            recentItems.forEach { entity ->
                entity ?: return@forEach
                val mediaId = inAppLibraryEntityIds[entity]
                    ?: mediaApiEntityCatalogId(entity)
                    ?: return@forEach
                registerInAppLibraryComposeState(mediaId, state)
                add(mediaId)
            }
        }.distinct()
    }

    private fun recordInAppLibraryComposeMediaId(mediaId: String) {
        activeInAppLibraryComposeCapture.get()?.mediaIds?.add(mediaId)
    }

    private fun scheduleInAppLibraryComposeVisibleResolution(
        fragment: Any,
        capturedMediaIds: Collection<String>,
        fallbackMediaIds: Collection<String>,
    ) {
        val state = inAppLibraryComposeStates[fragment]?.get() ?: return
        val mediaIds = composeVisibleMetadataResolutionIds(
            capturedMediaIds = capturedMediaIds,
            fallbackMediaIds = fallbackMediaIds,
            limit = MAX_LIBRARY_COMPOSE_VISIBLE_RESOLUTION_IDS,
        )
        if (mediaIds.isEmpty()) return
        mediaIds.forEach { mediaId -> registerInAppLibraryComposeState(mediaId, state) }
        recordInAppLibraryComposeBuildAliases(state, mediaIds)
        val shouldPost = synchronized(inAppLibraryComposeVisibleResolutionPending) {
            val pending = inAppLibraryComposeVisibleResolutionPending[state]
            if (pending != null) {
                pending.addAll(mediaIds)
                false
            } else {
                inAppLibraryComposeVisibleResolutionPending[state] =
                    mediaIds.toCollection(linkedSetOf())
                true
            }
        }
        if (!shouldPost) return
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${metadataTraceSequence.incrementAndGet()}, " +
                    "event=library_compose_visible_candidates, " +
                    "source=${if (normalizedRecyclerBindingMediaIds(capturedMediaIds).isNotEmpty()) {
                        "render_capture"
                    } else {
                        "first_items_fallback"
                    }}, contentIds=$mediaIds"
            )
        }
        postInAppLibraryComposeVisibleResolution(state)
    }

    private fun postInAppLibraryComposeVisibleResolution(state: Any) {
        mainHandler.post {
            Choreographer.getInstance().postFrameCallback {
                drainInAppLibraryComposeVisibleResolution(state)
            }
        }
    }

    private fun drainInAppLibraryComposeVisibleResolution(state: Any) {
        val (mediaIds, hasMore) = synchronized(inAppLibraryComposeVisibleResolutionPending) {
            val pending = inAppLibraryComposeVisibleResolutionPending[state]
                ?: return@synchronized emptyList<String>() to false
            val batch = pending.take(MAX_LIBRARY_COMPOSE_VISIBLE_RESOLUTION_IDS)
            pending.removeAll(batch.toSet())
            val remaining = pending.isNotEmpty()
            if (!remaining) inAppLibraryComposeVisibleResolutionPending.remove(state)
            batch to remaining
        }
        resolveInAppLibraryComposeVisibleMediaIds(mediaIds)
        if (hasMore) postInAppLibraryComposeVisibleResolution(state)
    }

    private fun resolveInAppLibraryComposeVisibleMediaIds(mediaIds: Collection<String>) {
        val normalizedIds = normalizedRecyclerBindingMediaIds(mediaIds)
        if (normalizedIds.isEmpty()) return
        val aliasesBeforeEnrichment = normalizedIds.associateWith(
            ::effectiveInAppMetadataOverride
        )
        enrichInAppLibraryEntitiesForResolution(normalizedIds)
        markMetadataVisible(normalizedIds)
        normalizedIds.forEach { mediaId ->
            val alias = effectiveInAppMetadataOverride(mediaId) ?: return@forEach
            if (aliasesBeforeEnrichment[mediaId] != alias) {
                applyAliasToInAppMetadataRefs(
                    mediaId = mediaId,
                    alias = alias,
                    forceRebind = true,
                    notifyModelChange = true,
                )
            }
        }
        scheduleInAppMetadataResolution(
            mediaIds = normalizedIds,
            priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
        )
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${metadataTraceSequence.incrementAndGet()}, " +
                    "event=library_compose_visible_request, contentIds=$normalizedIds, " +
                    "afterFirstFrame=true"
            )
        }
    }

    private fun registerInAppLibraryComposeState(mediaId: String, state: Any) {
        val refs = inAppLibraryComposeStateRefs.computeIfAbsent(mediaId) {
            ConcurrentLinkedQueue()
        }
        var registered = false
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null) {
                refs.remove(ref)
            } else if (target === state) {
                registered = true
            }
        }
        if (!registered) {
            refs.add(WeakReference(state))
            if (BuildConfig.DEBUG) {
                ProviderLogger.info(
                    "Apple Music 元数据链路: seq=${metadataTraceSequence.incrementAndGet()}, " +
                        "event=library_compose_capture, contentId=$mediaId, " +
                        "state=${state.javaClass.name}"
                )
            }
        }
    }

    private fun recordInAppLibraryComposeBuildAliases(
        state: Any,
        mediaIds: Collection<String>,
    ) {
        val normalizedIds = normalizedRecyclerBindingMediaIds(mediaIds)
        synchronized(inAppLibraryComposeAppliedAliases) {
            val appliedAliases = inAppLibraryComposeAppliedAliases.getOrPut(state) {
                mutableMapOf()
            }
            normalizedIds.forEach { mediaId ->
                val alias = effectiveInAppMetadataOverride(mediaId)
                if (alias == null) {
                    appliedAliases.remove(mediaId)
                } else {
                    appliedAliases[mediaId] = AppliedMetadataAlias(mediaId, alias)
                }
            }
            if (appliedAliases.isEmpty()) inAppLibraryComposeAppliedAliases.remove(state)
        }
    }

    private fun mediaApiEntityAttributes(entity: Any): Any? =
        runCatching { AppleReflection.call(entity, "getAttributes") }.getOrNull()

    private fun mediaApiAttribute(attributes: Any, getter: String): String? =
        runCatching { AppleReflection.call(attributes, getter) as? String }.getOrNull()

    private fun hookInAppDataBindingRefresh() {
        runCatching {
            val bindingClass = classLoader.loadClass("androidx.databinding.ViewDataBinding")
            val observableClass = classLoader.loadClass("androidx.databinding.i")
            dataBindingBaseClass = bindingClass
            val registrationMethod = AppleReflection.findMethod(
                bindingClass,
                "k0",
                parameterTypes = listOf(Int::class.javaPrimitiveType!!, observableClass),
            )
            dataBindingInvalidateAllMethod = AppleReflection.findMethod(
                bindingClass,
                "y",
                parameterCount = 0,
            )
            dataBindingExecutePendingBindingsMethod = AppleReflection.findMethod(
                bindingClass,
                "n",
                parameterCount = 0,
            )
            dataBindingSetVariableMethod = AppleReflection.findMethod(
                bindingClass,
                "h0",
                parameterTypes = listOf(
                    Int::class.javaPrimitiveType!!,
                    Any::class.java,
                ),
            )
            val brClass = classLoader.loadClass("com.apple.android.music.playback.BR")
            dataBindingTitleVariableId = brClass.getDeclaredField("title")
                .apply { isAccessible = true }
                .getInt(null)
            dataBindingSubtitleVariableId = brClass.getDeclaredField("subtitle")
                .apply { isAccessible = true }
                .getInt(null)
            val executePendingBindingsMethod =
                requireNotNull(dataBindingExecutePendingBindingsMethod)
            val bindingConstructor = bindingClass.getDeclaredConstructor(
                Any::class.java,
                View::class.java,
                Int::class.javaPrimitiveType!!,
            ).apply { isAccessible = true }
            hookRegistrar.install(bindingConstructor, after = { chain, _ ->
                chain.thisObject?.let { binding ->
                    captureInAppDataBinding(
                        binding = binding,
                        root = chain.args.getOrNull(1) as? View,
                    )
                }
            })
            hookRegistrar.install(registrationMethod, after = { chain, _ ->
                val binding = chain.thisObject ?: return@installHook
                captureInAppDataBinding(binding)
                val contentItem = chain.args.getOrNull(1) ?: return@installHook
                if (!isAppleContentItem(contentItem)) return@installHook
                val mediaId = contentItemMediaId(contentItem) ?: return@installHook
                registerInAppDataBinding(mediaId, binding)
            })
            hookRegistrar.install(executePendingBindingsMethod, after = { chain, _ ->
                val binding = chain.thisObject ?: return@installHook
                val mediaId = inAppDataBindingMediaIds[binding] ?: return@installHook
                val root = inAppDataBindingRootViews[binding]?.get()
                    ?: return@installHook
                if (!isVisibleBindingRoot(root)) return@installHook
                invalidateOverwrittenArtistHeaderAlias(binding, mediaId, root)
                postVisibleDataBindingResolution(binding, mediaId)
            })
            ProviderLogger.info(
                "Apple Music 资料库精确重绑定 Hook 已安装: " +
                    "registration=${registrationMethod.name}, " +
                    "invalidate=${dataBindingInvalidateAllMethod?.name}, " +
                    "execute=${executePendingBindingsMethod.name}, " +
                    "setVariable=${dataBindingSetVariableMethod?.name}, " +
                    "titleVariable=$dataBindingTitleVariableId, " +
                    "subtitleVariable=$dataBindingSubtitleVariableId, constructor=true"
            )
        }.onFailure {
            dataBindingInvalidateAllMethod = null
            dataBindingExecutePendingBindingsMethod = null
            dataBindingSetVariableMethod = null
            dataBindingTitleVariableId = null
            dataBindingSubtitleVariableId = null
            dataBindingBaseClass = null
            ProviderLogger.error("Apple Music 资料库精确重绑定 Hook 安装失败", it)
        }
    }

    private fun isAppleContentItem(candidate: Any): Boolean =
        generateSequence(candidate.javaClass) { it.superclass }
            .any { it.name == "com.apple.android.music.model.BaseContentItem" }

    private fun captureInAppDataBinding(binding: Any, root: View? = null) {
        if (root != null) {
            inAppDataBindingRootViews[binding] = WeakReference(root)
            inAppDataBindingsByRoot[root] = WeakReference(binding)
        }
        var registered = false
        inAppDataBindingInstances.forEach { ref ->
            val target = ref.get()
            if (target == null) {
                inAppDataBindingInstances.remove(ref)
            } else if (target === binding) {
                registered = true
            }
        }
        if (!registered) inAppDataBindingInstances.add(WeakReference(binding))
    }

    private fun beginInAppDataBindingModelBind(binding: Any) {
        synchronized(inAppDataBindingBindGenerations) {
            inAppDataBindingBindGenerations[binding] =
                (inAppDataBindingBindGenerations[binding] ?: 0L) + 1L
        }
        inAppArtistHeaderBindingIds.remove(binding)
        inAppDataBindingAppliedAliases.remove(binding)
        inAppDataBindingPendingRefreshes.remove(binding)
    }

    private fun inAppDataBindingBindGeneration(binding: Any): Long =
        synchronized(inAppDataBindingBindGenerations) {
            inAppDataBindingBindGenerations[binding] ?: 0L
        }

    private fun clearPendingDataBindingRefresh(
        binding: Any,
        expected: PendingDataBindingRefresh,
    ) {
        synchronized(inAppDataBindingPendingRefreshes) {
            if (inAppDataBindingPendingRefreshes[binding] == expected) {
                inAppDataBindingPendingRefreshes.remove(binding)
            }
        }
    }

    private fun isVisibleBindingRoot(root: View): Boolean {
        if (!root.isAttachedToWindow ||
            root.visibility != View.VISIBLE ||
            !root.isShown ||
            root.width <= 0 ||
            root.height <= 0
        ) return false
        val visibleRect = Rect()
        return root.getGlobalVisibleRect(visibleRect) &&
            visibleRect.width() > 0 &&
            visibleRect.height() > 0
    }

    private fun requestPriorityForMediaId(
        mediaId: String,
    ): AppleInternalCatalogResolver.RequestPriority = metadataRequestContext(mediaId).priority

    private fun registerInAppDataBinding(
        mediaId: String,
        binding: Any,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
    ) {
        val previousMediaId = inAppDataBindingMediaIds[binding]
        inAppArtistTopSongBindings[binding]
            ?.takeIf { snapshot -> snapshot.mediaId != mediaId }
            ?.let { inAppArtistTopSongBindings.remove(binding) }
        inAppArtistHeaderBindingIds[binding]
            ?.takeIf { artistId -> artistId != mediaId }
            ?.let { inAppArtistHeaderBindingIds.remove(binding) }
        inAppDataBindingMediaIds[binding] = mediaId
        val refs = inAppDataBindingRefs.computeIfAbsent(mediaId) {
            ConcurrentLinkedQueue()
        }
        var registered = false
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null) {
                refs.remove(ref)
            } else if (target === binding) {
                registered = true
            }
        }
        if (!registered) {
            refs.add(WeakReference(binding))
        }
        val root = inAppDataBindingRootViews[binding]?.get()
        if (root != null && isVisibleBindingRoot(root)) {
            postVisibleDataBindingResolution(binding, mediaId, originalResolutionMode)
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: " +
                    "seq=${metadataTraceSequence.incrementAndGet()}, " +
                    "event=data_binding_register, contentId=$mediaId, " +
                    "previousContentId=$previousMediaId, " +
                    "binding=${binding.javaClass.name}@${System.identityHashCode(binding)}, " +
                    "rootVisible=${root?.let(::isVisibleBindingRoot) == true}, " +
                    "texts=${root?.let(::debugTextSnapshot)}"
            )
        }
    }

    private fun hookRecyclerViewCentralBinding() {
        runCatching {
            val recyclerClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val bindMethod = recyclerClass.declaredClasses.asSequence()
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { method ->
                    val types = method.parameterTypes
                    method.returnType == Boolean::class.javaPrimitiveType &&
                        types.size == 4 &&
                        generateSequence(types[0]) { it.superclass }.any { holderType ->
                            holderType.declaredFields.any { field ->
                                View::class.java.isAssignableFrom(field.type)
                            }
                        } &&
                        types[1] == Int::class.javaPrimitiveType &&
                        types[2] == Int::class.javaPrimitiveType &&
                        types[3] == Long::class.javaPrimitiveType
                }
                ?.apply { isAccessible = true }
                ?: error("RecyclerView central bind method unavailable")
            hookRegistrar.install(bindMethod, after = { chain, result ->
                val capture = activeRecyclerBindCaptures.pop()
                if (result != true || capture == null || !capture.captureMetadata) {
                    return@installHook
                }
                val holder = chain.args.firstOrNull() ?: return@installHook
                val root = capture.root?.get()
                    ?: recyclerViewHolderItemView(holder)
                    ?: return@installHook
                captureBoundRecyclerRoot(root)?.let(capture.mediaIds::add)
                if (capture.mediaIds.isEmpty()) {
                    inAppRecyclerRootMediaIds.remove(root)
                    inAppRecyclerRootVisibleResolutionIds.remove(root)
                    return@installHook
                }
                registerGenericRecyclerBinding(capture, root)
                root.postOnAnimation {
                    val visible = isVisibleBindingRoot(root)
                    val mediaIds = inAppRecyclerRootMediaIds[root].orEmpty()
                    if (!shouldScheduleVisibleRecyclerMetadata(
                            previousMediaIds = inAppRecyclerRootVisibleResolutionIds[root],
                            currentMediaIds = mediaIds,
                            visible = visible,
                        )
                    ) {
                        return@postOnAnimation
                    }
                    inAppRecyclerRootVisibleResolutionIds[root] = mediaIds
                    captureBoundRecyclerRoot(root, visible = true)
                    enrichInAppLibraryEntitiesForResolution(mediaIds)
                    markMetadataVisible(mediaIds)
                    requestRecyclerMetadataOverrides(
                        mediaIds = mediaIds,
                        priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    )
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.info(
                            "Apple Music 元数据链路: " +
                                "seq=${metadataTraceSequence.incrementAndGet()}, " +
                                "event=generic_recycler_visible_request, " +
                                "contentIds=$mediaIds, " +
                                "root=${root.javaClass.name}, " +
                                "position=${capture.position}"
                        )
                    }
                }
            }, before = { chain ->
                val recycler = recyclerViewFromRecycler(chain.thisObject)
                val adapter = recycler?.let {
                    runCatching { AppleReflection.call(it, "getAdapter") }.getOrNull()
                }
                val position = chain.args.getOrNull(1) as? Int ?: -1
                val captureMetadata = !isAppleLyricsRecyclerAdapter(adapter)
                val root = if (captureMetadata) {
                    chain.args.firstOrNull()?.let(::recyclerViewHolderItemView)
                } else {
                    null
                }
                root
                    ?.let { inAppDataBindingsByRoot[it]?.get() }
                    ?.let(::beginInAppDataBindingModelBind)
                activeRecyclerBindCaptures.push(
                    RecyclerBindCapture(
                        adapter = adapter?.let(::WeakReference),
                        position = position,
                        root = root?.let(::WeakReference),
                        captureMetadata = captureMetadata,
                    )
                )
            })
            ProviderLogger.info(
                "Apple Music RecyclerView 通用元数据绑定 Hook 已安装: " +
                    "class=${bindMethod.declaringClass.name}, method=${bindMethod.name}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music RecyclerView 通用元数据绑定 Hook 安装失败", it)
        }
    }

    private fun recyclerViewHolderItemView(holder: Any): View? =
        generateSequence(holder.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { field -> View::class.java.isAssignableFrom(field.type) }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(holder) as? View
                }.getOrNull()
            }

    private fun captureBoundRecyclerRoot(root: View, visible: Boolean = false): String? {
        val binding = inAppDataBindingsByRoot[root]?.get() ?: return null
        val mediaId = resolvedDataBindingMediaId(binding) ?: return null
        if (visible && isVisibleBindingRoot(root)) {
            resolveVisibleDataBinding(binding, mediaId)
        }
        return mediaId
    }

    private fun postVisibleDataBindingResolution(
        binding: Any,
        mediaId: String,
        originalResolutionMode: InAppOriginalResolutionMode =
            if (inAppArtistTopSongBindings[binding] != null) {
                InAppOriginalResolutionMode.ORIGINAL_FIRST
            } else {
                InAppOriginalResolutionMode.AFTER_LOCALIZED
            },
    ) {
        val root = inAppDataBindingRootViews[binding]?.get() ?: return
        val pending = PendingVisibleDataBindingResolution(
            mediaId = mediaId,
            bindGeneration = inAppDataBindingBindGeneration(binding),
            originalResolutionMode = originalResolutionMode,
        )
        val shouldPost = synchronized(inAppDataBindingVisibleResolutionPosts) {
            if (inAppDataBindingVisibleResolutionPosts[binding] == pending) {
                false
            } else {
                inAppDataBindingVisibleResolutionPosts[binding] = pending
                true
            }
        }
        if (!shouldPost) return
        root.postOnAnimation {
            val current = synchronized(inAppDataBindingVisibleResolutionPosts) {
                inAppDataBindingVisibleResolutionPosts[binding]
            }
            if (current != pending) return@postOnAnimation
            synchronized(inAppDataBindingVisibleResolutionPosts) {
                inAppDataBindingVisibleResolutionPosts.remove(binding)
            }
            if (inAppDataBindingMediaIds[binding] != mediaId ||
                inAppDataBindingBindGeneration(binding) != pending.bindGeneration ||
                !isVisibleBindingRoot(root)
            ) return@postOnAnimation
            resolveVisibleDataBinding(
                binding = binding,
                mediaId = mediaId,
                originalResolutionMode = pending.originalResolutionMode,
            )
        }
    }

    private fun invalidateOverwrittenArtistHeaderAlias(
        binding: Any,
        mediaId: String,
        root: View,
    ) {
        if (inAppArtistHeaderBindingIds[binding] != mediaId) return
        val appliedAlias = inAppDataBindingAppliedAliases[binding] ?: return
        val effectiveAlias = effectiveInAppMetadataOverride(mediaId) ?: return
        val expectedAlias = AppliedMetadataAlias(mediaId, effectiveAlias)
        val pendingAlias = inAppDataBindingPendingRefreshes[binding]?.alias
        val expectedTitle = dataBindingAliasValues(
            mediaId = mediaId,
            alias = effectiveAlias,
            binding = binding,
        ).title
        val renderedTexts = dataBindingRenderedTexts(root)
        if (!shouldInvalidateArtistHeaderAppliedAlias(
                appliedAlias = appliedAlias,
                effectiveAlias = expectedAlias,
                pendingAlias = pendingAlias,
                expectedTitle = expectedTitle,
                renderedTexts = renderedTexts,
            )
        ) return

        inAppDataBindingAppliedAliases.remove(binding)
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: " +
                    "seq=${metadataTraceSequence.incrementAndGet()}, " +
                    "event=artist_header_alias_invalidated, contentId=$mediaId, " +
                    "expected=$expectedTitle, rendered=$renderedTexts"
            )
        }
    }

    private fun dataBindingRenderedTexts(root: View): List<String> {
        val texts = mutableListOf<String>()
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 32 && texts.size < 8) {
            val view = pending.removeFirst()
            visited += 1
            if (view is TextView) {
                view.text?.toString()?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(texts::add)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let(pending::addLast)
                }
            }
        }
        return texts
    }

    private fun resolveVisibleDataBinding(
        binding: Any,
        mediaId: String,
        originalResolutionMode: InAppOriginalResolutionMode =
            if (inAppArtistTopSongBindings[binding] != null) {
                InAppOriginalResolutionMode.ORIGINAL_FIRST
            } else {
                InAppOriginalResolutionMode.AFTER_LOCALIZED
            },
    ) {
        enrichInAppLibraryEntitiesForResolution(listOf(mediaId))
        markMetadataVisible(listOf(mediaId))
        effectiveInAppMetadataOverride(mediaId)?.let { alias ->
            refreshInAppDataBindingRefs(mediaId, alias)
        }
        if (shouldRequestInAppMetadataOverride(mediaId)) {
            scheduleInAppMetadataResolution(
                mediaIds = listOf(mediaId),
                priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                originalResolutionMode = originalResolutionMode,
            )
        }
    }

    private fun resolvedDataBindingMediaId(binding: Any): String? {
        inAppDataBindingMediaIds[binding]?.let { return it }
        val fields = inAppDataBindingContentFields.computeIfAbsent(binding.javaClass) {
            bindingContentFields(it)
        }
        val candidates = fields.mapNotNull { field ->
            val value = runCatching { field.get(binding) }.getOrNull() ?: return@mapNotNull null
            synchronized(inAppMetadataIds) { inAppMetadataIds[value] }
                ?: inAppPlaybackItemIds[value]
                ?: inAppLibraryEntityIds[value]
                ?: inAppMediaApiAttributeBindings[value]?.mediaId
        }.distinct()
        val mediaId = candidates.singleOrNull()
        if (mediaId == null) {
            if (BuildConfig.DEBUG) {
                ProviderLogger.info(
                    "Apple Music 元数据链路: " +
                        "seq=${metadataTraceSequence.incrementAndGet()}, " +
                        "event=data_binding_resolve_miss, " +
                        "binding=${binding.javaClass.name}@" +
                        "${System.identityHashCode(binding)}, " +
                        "candidates=$candidates, fieldCount=${fields.size}, " +
                        "texts=${inAppDataBindingRootViews[binding]?.get()?.let(
                            ::debugTextSnapshot
                        )}"
                )
            }
            return null
        }
        registerInAppDataBinding(mediaId, binding)
        return mediaId
    }

    private fun recyclerViewFromRecycler(recycler: Any?): Any? {
        recycler ?: return null
        return generateSequence(recycler.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { field ->
                field.type.name == "androidx.recyclerview.widget.RecyclerView"
            }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(recycler)
                }.getOrNull()
            }
    }

    private fun recordCurrentRecyclerMediaId(mediaId: String): Boolean {
        val normalized = mediaId.trim()
        if (normalized.isEmpty() || !normalized.all(Char::isDigit)) return false
        val capture = activeRecyclerBindCaptures.current ?: return false
        if (!capture.captureMetadata) return false
        capture.mediaIds.add(normalized)
        return true
    }

    private fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean =
        adapter?.javaClass?.name in appleLyricsRecyclerAdapterClassNames

    private fun registerGenericRecyclerBinding(capture: RecyclerBindCapture, root: View) {
        val mediaIds = normalizedRecyclerBindingMediaIds(capture.mediaIds)
        if (mediaIds.isEmpty()) return
        inAppRecyclerRootMediaIds[root] = mediaIds
        val adapter = capture.adapter?.get() ?: return
        if (capture.position < 0) return
        val dataBinding = inAppDataBindingsByRoot[root]?.get()
        val dataBindingMediaId = dataBinding?.let(::resolvedDataBindingMediaId)
            ?: mediaIds.singleOrNull()?.also { mediaId ->
                dataBinding?.let { registerInAppDataBinding(mediaId, it) }
            }
        val isQueueAdapter = inAppQueueAdapterRefs.any { it.get() === adapter }
        if (isQueueAdapter) return
        val blockMultiItemStructuralRefresh =
            mediaIds.size > 1 && isArtistProfileRecyclerAdapter(adapter)
        if (
            !shouldRegisterGenericRecyclerRefresh(
                mediaIds = mediaIds,
                dataBindingMediaId = dataBindingMediaId,
                blockMultiItemStructuralRefresh = blockMultiItemStructuralRefresh,
            )
        ) {
            if (BuildConfig.DEBUG && blockMultiItemStructuralRefresh) {
                ProviderLogger.info(
                    "Apple Music 元数据链路: " +
                        "seq=${metadataTraceSequence.incrementAndGet()}, " +
                        "event=generic_recycler_structural_refresh_blocked, " +
                        "contentIds=$mediaIds, root=${root.javaClass.name}, " +
                        "position=${capture.position}, adapter=${adapter.javaClass.name}"
                )
            }
            return
        }
        mediaIds.filterNot { it == dataBindingMediaId }.forEach { mediaId ->
            val refs = inAppGenericRecyclerItemRefs.computeIfAbsent(mediaId) {
                ConcurrentLinkedQueue()
            }
            var registered = false
            refs.forEach { ref ->
                val targetAdapter = ref.adapter.get()
                val targetRoot = ref.root.get()
                if (targetAdapter == null || targetRoot == null) {
                    refs.remove(ref)
                } else if (
                    targetAdapter === adapter &&
                    targetRoot === root &&
                    ref.position == capture.position
                ) {
                    registered = true
                }
            }
            if (!registered) {
                refs.add(
                    InAppRecyclerItemRef(
                        adapter = WeakReference(adapter),
                        root = WeakReference(root),
                        position = capture.position,
                    )
                )
            }
        }
    }

    private fun isArtistProfileRecyclerAdapter(adapter: Any): Boolean {
        if (isArtistProfileController(adapter)) return true
        return generateSequence(adapter.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { field ->
                !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive
            }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(adapter)
                }.getOrNull()
            }
            .any(::isArtistProfileController)
    }

    private fun isArtistProfileController(candidate: Any): Boolean =
        isArtistProfileControllerClassNames(
            generateSequence(candidate.javaClass) { it.superclass }
                .map(Class<*>::getName)
                .asIterable()
        )

    private fun requestRecyclerMetadataOverrides(
        mediaIds: Collection<String>,
        priority: AppleInternalCatalogResolver.RequestPriority,
    ) {
        scheduleInAppMetadataResolution(mediaIds, priority)
    }

    private fun scheduleInAppMetadataResolution(
        mediaIds: Collection<String>,
        priority: AppleInternalCatalogResolver.RequestPriority,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
    ) {
        if (priority == AppleInternalCatalogResolver.RequestPriority.BACKGROUND) return
        enrichInAppLibraryEntitiesForResolution(mediaIds)
        val unresolvedIds = normalizedRecyclerBindingMediaIds(mediaIds)
            .filter(::shouldRequestInAppMetadataOverride)
        if (unresolvedIds.isEmpty()) return
        val incoming = DeferredMetadataResolution(
            priority = priority,
            originalResolutionMode = originalResolutionMode,
        )
        val shouldPost = synchronized(deferredMetadataResolutions) {
            unresolvedIds.forEach { mediaId ->
                val previous = deferredMetadataResolutions[mediaId]
                deferredMetadataResolutions[mediaId] =
                    mergeDeferredMetadataResolution(previous, incoming)
            }
            if (deferredMetadataResolutionScheduled) {
                false
            } else {
                deferredMetadataResolutionScheduled = true
                true
            }
        }
        if (!shouldPost) return
        mainHandler.post {
            val pending = synchronized(deferredMetadataResolutions) {
                deferredMetadataResolutions.entries
                    .map { it.key to it.value }
                    .also {
                        deferredMetadataResolutions.clear()
                        deferredMetadataResolutionScheduled = false
                    }
            }
            pending
                .groupBy(keySelector = { it.second }, valueTransform = { it.first })
                .entries
                .sortedByDescending { it.key.priority.ordinal }
                .forEach { (resolution, pendingIds) ->
                    val stillUnresolved = pendingIds.filter(::shouldRequestInAppMetadataOverride)
                    if (stillUnresolved.isNotEmpty()) {
                        ensureInAppMetadataOverrides(
                            mediaIds = stillUnresolved,
                            preBind = true,
                            priority = resolution.priority,
                            originalResolutionMode = resolution.originalResolutionMode,
                        )
                    }
                }
        }
    }

    private fun boundRecyclerRootContainsMediaId(root: View, mediaId: String): Boolean =
        mediaId in inAppRecyclerRootMediaIds[root].orEmpty()

    private fun refreshGenericRecyclerItemRefs(mediaId: String): Int {
        if (!isRefreshableInAppMediaId(mediaId)) return 0
        val refs = inAppGenericRecyclerItemRefs[mediaId] ?: return 0
        var targets = 0
        refs.forEach { ref ->
            val adapter = ref.adapter.get()
            val root = ref.root.get()
            if (
                adapter == null ||
                root == null ||
                !boundRecyclerRootContainsMediaId(root, mediaId) ||
                !shouldRefreshExactBoundTarget(
                    surfaceRelevant = isCurrentMetadataSurfaceMediaId(mediaId),
                    mediaIdMatches = true,
                    rootVisible = isVisibleBindingRoot(root),
                )
            ) {
                refs.remove(ref)
                return@forEach
            }
            targets += 1
            mainHandler.post {
                if (
                    shouldRefreshExactBoundTarget(
                        surfaceRelevant = isCurrentMetadataSurfaceMediaId(mediaId),
                        mediaIdMatches = boundRecyclerRootContainsMediaId(root, mediaId),
                        rootVisible = isVisibleBindingRoot(root),
                    )
                ) {
                    runCatching { AppleReflection.call(adapter, "notifyItemChanged", ref.position) }
                        .onFailure {
                            ProviderLogger.error(
                                "Apple Music RecyclerView 精确刷新失败: " +
                                    "id=$mediaId, position=${ref.position}",
                                it,
                            )
                        }
                }
            }
        }
        return targets
    }

    private fun hookVisibleMetadataDiagnostics() {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
                .apply { isAccessible = true }
            val onPause = Activity::class.java.getDeclaredMethod("onPause")
                .apply { isAccessible = true }
            val onWindowFocusChanged = Activity::class.java.getDeclaredMethod(
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            hookRegistrar.install(onResume, after = { chain, _ ->
                val activity = chain.thisObject as? Activity ?: return@installHook
                debugForegroundActivities[activity] = true
                mainHandler.postDelayed(
                    { debugScanVisibleMetadataViews("activity_resumed") },
                    250L,
                )
            })
            hookRegistrar.install(onPause, before = { chain ->
                val activity = chain.thisObject as? Activity ?: return@installHook
                debugForegroundActivities.remove(activity)
            })
            hookRegistrar.install(onWindowFocusChanged, after = { chain, _ ->
                val hasFocus = chain.args.firstOrNull() as? Boolean ?: false
                if (!hasFocus) return@installHook
                val activity = chain.thisObject as? Activity ?: return@installHook
                debugForegroundActivities[activity] = true
                mainHandler.postDelayed(
                    { debugScanVisibleMetadataViews("window_focus") },
                    250L,
                )
            })
            ProviderLogger.info("Apple Music debug 可见元数据取证 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music debug 可见元数据取证 Hook 安装失败", it)
        }
    }

    private fun debugScanVisibleMetadataViews(trigger: String) {
        if (!BuildConfig.DEBUG) return
        val mediaId = activePlaybackMediaIdentity().mediaId ?: return
        val alias = effectiveInAppMetadataOverride(mediaId)
        val metadataValues = debugActiveMetadataValues()
        val activities = synchronized(debugForegroundActivities) {
            debugForegroundActivities.keys.toList()
        }
        activities.forEach { activity ->
            val root = runCatching { activity.window?.decorView }.getOrNull() ?: return@forEach
            var visited = 0
            var textViews = 0
            var logged = 0
            val pending = ArrayDeque<View>()
            pending.add(root)
            while (pending.isNotEmpty() && visited < 2_000) {
                val view = pending.removeFirst()
                visited += 1
                if (view is TextView) {
                    textViews += 1
                    val text = view.text?.toString()?.trim().orEmpty()
                    if (
                        text.isNotEmpty() &&
                        text in metadataValues &&
                        view.isShown &&
                        logged < MAX_DEBUG_VISIBLE_VIEWS_PER_SCAN
                    ) {
                        val traceKey =
                            "view:${System.identityHashCode(view)}:$text:${activity.javaClass.name}"
                        if (
                            debugVisibleViewTraceKeys.size < MAX_DEBUG_VISIBLE_VIEW_TRACE_KEYS &&
                            debugVisibleViewTraceKeys.add(traceKey)
                        ) {
                            logged += 1
                            ProviderLogger.info(
                                "Apple Music 元数据链路: " +
                                    "seq=${metadataTraceSequence.incrementAndGet()}, " +
                                    "event=debug_visible_view, trigger=$trigger, " +
                                    "selected=$mediaId, alias=${alias?.title}/${alias?.artist}, " +
                                    "text=${text.take(120)}, " +
                                    "view=${debugViewDescription(view)}, " +
                                    "parents=${debugViewParentChain(view)}"
                            )
                        }
                    }
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        view.getChildAt(index)?.let(pending::addLast)
                    }
                }
            }
            ProviderLogger.info(
                "Apple Music 元数据链路: " +
                    "seq=${metadataTraceSequence.incrementAndGet()}, " +
                    "event=debug_view_scan, trigger=$trigger, selected=$mediaId, " +
                    "activity=${activity.javaClass.name}, root=${root.javaClass.name}, " +
                    "visited=$visited, textViews=$textViews, logged=$logged"
            )
        }
    }

    private fun debugActiveMetadataValues(): Set<String> {
        if (!BuildConfig.DEBUG) return emptySet()
        val mediaId = activePlaybackMediaIdentity().mediaId ?: return emptySet()
        val account = playbackMetadataAccountValues[mediaId]
        val alias = effectiveInAppMetadataOverride(mediaId)
        val framework = currentFrameworkMediaSessionRefresh
            ?.takeIf { it.mediaId == mediaId }
            ?.metadata
        return buildSet {
            listOf(
                account?.title,
                account?.artist,
                alias?.title,
                alias?.artist,
                framework?.getString(MediaMetadata.METADATA_KEY_TITLE),
                framework?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                framework?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                framework?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            ).filterNotNull().map(String::trim).filter(String::isNotEmpty).forEach(::add)
            inAppPlaybackItemRefs[mediaId].orEmpty().forEach { ref ->
                ref.originalTitle?.toString()?.trim()
                    ?.takeIf(String::isNotEmpty)?.let(::add)
                ref.originalArtist?.toString()?.trim()
                    ?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private fun debugReflectiveField(instance: Any, name: String): Any? {
        if (!BuildConfig.DEBUG) return null
        return generateSequence(instance.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name == name }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()
            }
    }

    private fun debugTextSnapshot(root: View): String {
        if (!BuildConfig.DEBUG) return "disabled"
        val texts = mutableListOf<String>()
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited < 96 && texts.size < 12) {
            val view = pending.removeFirst()
            visited += 1
            if (view is TextView) {
                val text = view.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    texts += "${view.javaClass.simpleName}=${text.take(160)}"
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let(pending::addLast)
                }
            }
        }
        return texts.joinToString(prefix = "[", postfix = "]")
    }

    private fun debugViewDescription(view: View): String {
        val id = view.id
        val resourceName = if (id == View.NO_ID) {
            "no-id"
        } else {
            runCatching { view.resources.getResourceName(id) }
                .getOrElse { "0x${id.toString(16)}" }
        }
        return "${view.javaClass.name}@${System.identityHashCode(view)}" +
            "[id=$resourceName,shown=${view.isShown},attached=${view.isAttachedToWindow}," +
            "visibility=${view.visibility},alpha=${view.alpha}]"
    }

    private fun debugRecyclerViewSnapshot(recycler: Any): String {
        if (!BuildConfig.DEBUG) return "disabled"
        val view = recycler as? View ?: return "not_view"
        val scrollState = runCatching {
            AppleReflection.call(recycler, "getScrollState")
        }.getOrNull()
        val adapter = runCatching {
            AppleReflection.call(recycler, "getAdapter")
        }.getOrNull()
        val layoutManager = runCatching {
            AppleReflection.call(recycler, "getLayoutManager")
        }.getOrNull()
        val firstVisible = layoutManager?.let { manager ->
            runCatching {
                AppleReflection.call(manager, "findFirstVisibleItemPosition")
            }.getOrNull()
        }
        val child = (recycler as? ViewGroup)?.getChildAt(0)
        return "view=${debugViewDescription(view)}, state=$scrollState, " +
            "adapter=${adapter?.javaClass?.name}, layout=${layoutManager?.javaClass?.name}, " +
            "first=$firstVisible, childTop=${child?.top}, childCount=" +
            "${(recycler as? ViewGroup)?.childCount}"
    }

    private fun debugVisibleRecyclerViews(trigger: String) {
        if (!BuildConfig.DEBUG) return
        val recyclerClass = debugRecyclerViewClass ?: return
        val activities = synchronized(debugForegroundActivities) {
            debugForegroundActivities.keys.toList()
        }
        var logged = 0
        activities.forEach { activity ->
            if (logged >= MAX_DEBUG_RECYCLER_VIEWS_PER_SCAN) return@forEach
            val root = runCatching { activity.window?.decorView }.getOrNull()
                ?: return@forEach
            val pending = ArrayDeque<View>()
            pending.add(root)
            var visited = 0
            while (
                pending.isNotEmpty() &&
                visited < 2_000 &&
                logged < MAX_DEBUG_RECYCLER_VIEWS_PER_SCAN
            ) {
                val view = pending.removeFirst()
                visited += 1
                if (recyclerClass.isInstance(view) && view.isShown) {
                    logged += 1
                    logMetadataIdentity(
                        event = "library_scroll_snapshot",
                        details = "trigger=$trigger, activity=${activity.javaClass.name}, " +
                            debugRecyclerViewSnapshot(view),
                    )
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        view.getChildAt(index)?.let(pending::addLast)
                    }
                }
            }
        }
        if (logged == 0) {
            logMetadataIdentity(
                event = "library_scroll_snapshot",
                details = "trigger=$trigger, recyclerCount=0",
            )
        }
    }

    private fun debugViewParentChain(view: View): String {
        val parents = mutableListOf<String>()
        var current = view.parent
        while (current is View && parents.size < 8) {
            val id = current.id
            val resourceName = if (id == View.NO_ID) {
                "no-id"
            } else {
                runCatching { current.resources.getResourceName(id) }
                    .getOrElse { "0x${id.toString(16)}" }
            }
            parents += "${current.javaClass.name}[$resourceName]"
            current = current.parent
        }
        return parents.joinToString(" <- ")
    }

    private fun debugStackSummary(): String = Thread.currentThread().stackTrace
        .asSequence()
        .filterNot { frame ->
            frame.className.startsWith("java.lang.Thread") ||
                frame.className.contains("AppleMusicProvider")
        }
        .take(10)
        .joinToString(" <- ") { frame ->
            "${frame.className}#${frame.methodName}:${frame.lineNumber}"
        }

    private fun hookInAppMetadataCapture() {
        runCatching {
            val dispatcherClass = classLoader.loadClass("com.apple.android.music.player.f")
            val method = AppleReflection.findMethod(
                dispatcherClass,
                "onMediaMetadataChanged",
                parameterCount = 1,
            )
            hookRegistrar.install(method, before = { chain ->
                val dispatcher = chain.thisObject ?: return@installHook
                val metadata = chain.args.firstOrNull() ?: return@installHook
                val identityBefore = activePlaybackMediaIdentity()
                val mediaId = media3MetadataId(metadata, null)
                if (mediaId == null) {
                    logMetadataIdentity(
                        event = "in_app_global_unresolved",
                        identity = identityBefore,
                        details = media3MetadataDetails(metadata),
                    )
                    return@installHook
                }
                currentInAppMetadataDispatcherRefresh = InAppMetadataDispatcherRefresh(
                    mediaId = mediaId,
                    dispatcher = WeakReference(dispatcher),
                    method = method,
                    metadata = WeakReference(metadata),
                )
                logMetadataIdentity(
                    event = "in_app_global_capture",
                    identity = identityBefore,
                    details = "resolvedId=$mediaId, ${media3MetadataDetails(metadata)}",
                )
                registerInAppMetadata(
                    mediaId = mediaId,
                    metadata = metadata,
                    priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                )
            })
            ProviderLogger.info("Apple Music App 全局元数据捕获 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music App 全局元数据捕获 Hook 安装失败", it)
        }
    }

    private fun hookInAppPlaybackItemConversion() {
        runCatching {
            val playerUtilClass = classLoader.loadClass("com.apple.android.music.player.O")
            val containerMethod = AppleReflection.findMethod(
                playerUtilClass,
                "a",
                parameterCount = 1,
            )
            hookRegistrar.installResultOverride(containerMethod) { chain, original ->
                val containerItem = original ?: return@installResultOverrideHook original
                val metadata = chain.args.firstOrNull()
                    ?: return@installResultOverrideHook original
                val kind = inAppContainerKind(containerItem)
                    ?: return@installResultOverrideHook original
                val metadataId = media3MetadataId(metadata, null)
                val identity = activePlaybackMediaIdentity()
                val mediaId = metadataId
                logMetadataIdentity(
                    event = "container_conversion",
                    identity = identity,
                    details = "metadataId=$metadataId, resolvedId=$mediaId, kind=$kind, " +
                        "class=${containerItem.javaClass.name}, ${media3MetadataDetails(metadata)}",
                )
                if (mediaId == null) return@installResultOverrideHook original
                markInAppContainerNavigationItem(containerItem, kind, mediaId)
                markMetadataVisible(listOf(mediaId))
                registerInAppContainerItem(mediaId, containerItem, kind)
                effectiveInAppMetadataOverride(mediaId)?.let { alias ->
                    applyAliasToInAppContainerItem(containerItem, kind, alias)
                }
                if (shouldRequestInAppMetadataOverride(mediaId)) {
                    ensureInAppMetadataOverride(
                        mediaId = mediaId,
                        priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    )
                }
                original
            }

            val playbackItemMethod = AppleReflection.findMethod(
                playerUtilClass,
                "b",
                parameterCount = 1,
            )
            hookRegistrar.installResultOverride(playbackItemMethod) { chain, original ->
                val playbackItem = original ?: return@installResultOverrideHook original
                val metadata = chain.args.firstOrNull()
                    ?: return@installResultOverrideHook original
                val mediaId = media3MetadataId(metadata, null)
                    ?: contentItemMediaId(playbackItem)
                    ?: return@installResultOverrideHook original
                markMetadataVisible(listOf(mediaId))
                registerInAppPlaybackItem(mediaId, playbackItem)
                effectiveInAppMetadataOverride(mediaId)?.let { alias ->
                    applyAliasToInAppPlaybackItem(playbackItem, alias)
                }
                if (shouldRequestInAppMetadataOverride(mediaId)) {
                    ensureInAppMetadataOverride(
                        mediaId = mediaId,
                        priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    )
                }
                original
            }
            ProviderLogger.info(
                "Apple Music App 容器跳转项/PlaybackItem 转换 Hook 已安装"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music App 内容项/PlaybackItem 转换 Hook 安装失败", it)
        }
    }

    private fun hookInAppActionSheetMetadata() {
        runCatching {
            val resolvedBinding = hookResolver.resolveMethod(
                AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING
            )
            val bindingClass = resolvedBinding.method.declaringClass
            val bindMethod = resolvedBinding.method
            val itemField = generateSequence(bindingClass) { current -> current.superclass }
                .flatMap { current -> current.declaredFields.asSequence() }
                .singleOrNull { field ->
                    !Modifier.isStatic(field.modifiers) &&
                        field.type.name == "com.apple.android.music.model.CollectionItemView"
                }
                ?.apply { isAccessible = true }
                ?: error("Action sheet CollectionItemView field unavailable")
            hookRegistrar.install(bindMethod, after = { chain, _ ->
                val binding = chain.thisObject ?: return@installHook
                val item = runCatching { itemField.get(binding) }.getOrNull()
                    ?: return@installHook
                val contentType = runCatching {
                    AppleReflection.call(item, "getContentType") as? Int
                }.getOrNull() ?: return@installHook
                val field = actionSheetField(contentType) ?: return@installHook
                val identity = activePlaybackMediaIdentity()
                val mediaId = identity.mediaId ?: return@installHook
                markMetadataVisible(listOf(mediaId))
                val rawTitle = runCatching {
                    AppleReflection.call(item, "getTitle") as? String
                }.getOrNull() ?: rawContentItemValue(item, "name") as? String
                if (!actionSheetItemMatchesMedia(mediaId, field, rawTitle)) {
                    logMetadataIdentity(
                        event = "action_sheet_bind_skip",
                        identity = identity,
                        details = "binding=${binding.javaClass.name}, item=${item.javaClass.name}, " +
                            "type=$contentType, field=$field, raw=$rawTitle, " +
                            "reason=active_metadata_mismatch",
                    )
                    return@installHook
                }
                if (field == VisibleTextField.ARTIST) {
                    val artistKeys = contentItemArtistCacheKeys(item, rawTitle)
                    if (artistKeys.isNotEmpty()) {
                        playbackMetadataArtistKeys.merge(
                            mediaId,
                            artistKeys,
                        ) { previous, incoming -> previous + incoming }
                    }
                    mergePlaybackAssociatedArtistIds(
                        mediaId = mediaId,
                        artistIds = artistIdsFromAssociationKeys(artistKeys) +
                            contentItemCatalogLookupIds(item, mediaId = "")
                                .filterNot { it == mediaId },
                    )
                }
                registerInAppActionSheetBinding(mediaId, binding, field)
                val alias = effectiveInAppMetadataOverride(mediaId)
                if (BuildConfig.DEBUG) {
                    val boundText = actionSheetTitleTextViews(binding, rawTitle)
                        .joinToString(prefix = "[", postfix = "]") { view ->
                            view.text?.toString().orEmpty()
                        }
                    logMetadataIdentity(
                        event = "action_sheet_bind",
                        identity = identity,
                        details = "binding=${binding.javaClass.name}, " +
                            "item=${item.javaClass.name}, type=$contentType, field=$field, " +
                            "raw=$rawTitle, bound=$boundText, " +
                            "alias=${alias?.title}/${alias?.artist}/${alias?.album}",
                    )
                }
                if (shouldRequestInAppMetadataOverride(mediaId)) {
                    ensureInAppMetadataOverride(
                        mediaId = mediaId,
                        priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                    )
                }
                alias?.let {
                    applyAliasToInAppActionSheetBinding(
                        binding = binding,
                        association = InAppActionSheetBinding(mediaId, field),
                        alias = it,
                    )
                }
            })
            ProviderLogger.info(
                "Apple Music 播放菜单元数据 Hook 已安装: " +
                    "binding=${resolvedBinding.target.className}, " +
                    "fallback=${resolvedBinding.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 播放菜单元数据 Hook 安装失败", it)
        }
    }

    private fun registerInAppActionSheetBinding(
        mediaId: String,
        binding: Any,
        field: VisibleTextField,
    ) {
        inAppActionSheetBindings[binding] = InAppActionSheetBinding(mediaId, field)
        val refs = inAppActionSheetBindingRefs.computeIfAbsent(mediaId) {
            ConcurrentLinkedQueue()
        }
        var registered = false
        refs.forEach { ref ->
            val target = ref.get()
            if (target == null) {
                refs.remove(ref)
            } else if (target === binding) {
                registered = true
            }
        }
        if (!registered) refs.add(WeakReference(binding))
    }

    private fun applyAliasToInAppActionSheetBindings(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        val apply = apply@{
            var liveBindings = 0
            val refs = inAppActionSheetBindingRefs[mediaId]
                ?: return@apply
            refs.forEach { ref ->
                val binding = ref.get()
                if (binding == null) {
                    refs.remove(ref)
                } else {
                    val association = inAppActionSheetBindings[binding]
                        ?.takeIf { it.mediaId == mediaId }
                        ?: return@forEach
                    liveBindings += 1
                    applyAliasToInAppActionSheetBinding(
                        binding = binding,
                        association = association,
                        alias = alias,
                    )
                }
            }
            if (BuildConfig.DEBUG) {
                logMetadataIdentity(
                    event = "action_sheet_refresh",
                    details = "overrideId=$mediaId, liveBindings=$liveBindings, " +
                        "alias=${alias.title}/${alias.artist}/${alias.album}",
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post(apply)
    }

    private fun applyAliasToInAppActionSheetBinding(
        binding: Any,
        association: InAppActionSheetBinding,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        if (inAppActionSheetBindings[binding] != association) return
        val item = actionSheetCollectionItem(binding)
            ?: return
        val contentType = runCatching {
            AppleReflection.call(item, "getContentType") as? Int
        }.getOrNull() ?: return
        if (actionSheetField(contentType) != association.field) return
        val value = localizedVisibleText(association.field, alias)
            .takeIf(String::isNotBlank) ?: return
        val originalTitle = runCatching {
            AppleReflection.call(item, "getTitle") as? String
        }.getOrNull()
        val titleViews = actionSheetTitleTextViews(binding, originalTitle)
        runCatching {
            AppleReflection.call(item, "setTitle", value)
            AppleReflection.call(item, "notifyChange")
        }
        val before = titleViews.map { view -> view.text?.toString().orEmpty() }
        titleViews.forEach { view ->
            if (view.text?.toString() != value) view.text = value
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 播放菜单元数据覆盖: id=${association.mediaId}, " +
                    "field=${association.field}, type=$contentType, " +
                    "binding=${System.identityHashCode(binding)}, " +
                    "before=$before, target=$value, " +
                    "after=${titleViews.map { view -> view.text?.toString().orEmpty() }}"
            )
        }
    }

    private fun actionSheetCollectionItem(binding: Any): Any? =
        generateSequence(binding.javaClass) { current -> current.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) &&
                    field.type.name == "com.apple.android.music.model.CollectionItemView"
            }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(binding)
                }.getOrNull()
            }

    private fun actionSheetTitleTextViews(
        binding: Any,
        expectedTitle: String?,
    ): List<TextView> {
        val normalizedTitle = expectedTitle?.trim()?.takeIf(String::isNotEmpty)
            ?: return emptyList()
        return generateSequence(binding.javaClass) { current -> current.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .filter { field ->
                !Modifier.isStatic(field.modifiers) &&
                    TextView::class.java.isAssignableFrom(field.type)
            }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(binding) as? TextView
                }.getOrNull()
            }
            .filter { view -> view.text?.toString()?.trim() == normalizedTitle }
            .distinctBy(System::identityHashCode)
            .toList()
    }

    private fun actionSheetField(contentType: Int): VisibleTextField? = when (contentType) {
        6 -> VisibleTextField.ARTIST
        3 -> VisibleTextField.ALBUM
        else -> null
    }

    private fun actionSheetItemMatchesMedia(
        mediaId: String,
        field: VisibleTextField,
        value: String?,
    ): Boolean {
        val text = value?.takeIf(String::isNotBlank) ?: return false
        val alias = effectiveInAppMetadataOverride(mediaId)
        val account = playbackMetadataAccountValues[mediaId]
        val knownValues = buildSet {
            when (field) {
                VisibleTextField.ARTIST -> {
                    account?.artist?.let(::add)
                    alias?.artist?.let(::add)
                    inAppPlaybackItemRefs[mediaId].orEmpty().forEach { ref ->
                        ref.originalArtist?.toString()?.let(::add)
                    }
                }
                VisibleTextField.ALBUM -> {
                    alias?.album?.let(::add)
                    inAppPlaybackItemRefs[mediaId].orEmpty().forEach { ref ->
                        ref.originalCollectionName?.let(::add)
                    }
                }
                VisibleTextField.TITLE -> Unit
            }
        }
        return text in knownValues
    }

    private fun hookInAppNowPlayingMetadata() {
        runCatching {
            val listenerClass = classLoader.loadClass(
                "com.apple.android.music.player.fragment." +
                    "PlayerSongViewFragment\$PlayerListener"
            )
            val method = AppleReflection.findMethod(
                listenerClass,
                "onMediaMetadataChanged",
                parameterCount = 1,
            )
            hookRegistrar.install(method, before = { chain ->
                val listener = chain.thisObject ?: return@installHook
                val metadata = chain.args.firstOrNull() ?: return@installHook
                val identityBefore = activePlaybackMediaIdentity()
                val mediaId = media3MetadataId(metadata, null)
                if (mediaId == null) {
                    logMetadataIdentity(
                        event = "in_app_now_playing_unresolved",
                        identity = identityBefore,
                        details = media3MetadataDetails(metadata),
                    )
                    return@installHook
                }
                currentInAppMetadataRefresh = InAppNowPlayingRefresh(
                    mediaId = mediaId,
                    listener = WeakReference(listener),
                    method = method,
                    metadata = WeakReference(metadata),
                )
                logMetadataIdentity(
                    event = "in_app_now_playing_capture",
                    details = "resolvedId=$mediaId, ${media3MetadataDetails(metadata)}, " +
                        "aliasHit=${playbackMetadataOverrides.containsKey(mediaId)}",
                )
                registerInAppMetadata(
                    mediaId = mediaId,
                    metadata = metadata,
                    priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                )
            })
            ProviderLogger.info("Apple Music App 播放页元数据 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music App 播放页元数据 Hook 安装失败", it)
        }
    }

    private fun hookInAppQueueMetadata() {
        runCatching {
            val viewModelClass = classLoader.loadClass(
                "com.apple.android.music.player.queuefa.NewPlayerQueueViewModel"
            )
            val queueMethod = AppleReflection.findMethod(
                viewModelClass,
                "updateQueue",
                parameterCount = 5,
            )
            hookRegistrar.install(queueMethod, before = { chain ->
                chain.thisObject ?: return@installHook
                val items = chain.args.firstOrNull() as? Iterable<*> ?: return@installHook
                val mediaIds = registerInAppQueueEntries(items, preBind = true)
                val queueChanged = queueInAppMetadataRefresh?.mediaIds != mediaIds
                if (queueChanged) {
                    queueInAppMetadataRefresh = InAppQueueRefresh(mediaIds = mediaIds)
                }
                if (BuildConfig.DEBUG && queueChanged) ProviderLogger.info(
                    "Apple Music 继续播放捕获: ids=${mediaIds.size}, " +
                        "unresolved=${mediaIds.count { !playbackMetadataOverrides.containsKey(it) }}"
                )
            })

            val historyMethod = AppleReflection.findMethod(
                viewModelClass,
                "updateHistory",
                parameterCount = 1,
            )
            hookRegistrar.install(historyMethod, before = { chain ->
                chain.thisObject ?: return@installHook
                val items = chain.args.firstOrNull() as? Iterable<*> ?: return@installHook
                val mediaIds = registerInAppQueueEntries(
                    items = items,
                    preBind = true,
                    maxEntries = MAX_QUEUE_LOCALIZED_PREFETCH_ENTRIES,
                    originalResolutionLimit = MAX_QUEUE_PREBIND_ENTRIES,
                    historyEntries = true,
                )
                val historyChanged = historyInAppMetadataRefresh?.mediaIds != mediaIds
                if (historyChanged) {
                    historyInAppMetadataRefresh = InAppQueueRefresh(mediaIds = mediaIds)
                }
                if (BuildConfig.DEBUG && historyChanged) ProviderLogger.info(
                    "Apple Music 历史记录捕获: ids=${mediaIds.size}, " +
                        "unresolved=${mediaIds.count { !playbackMetadataOverrides.containsKey(it) }}"
                )
            })
            ProviderLogger.info("Apple Music App 播放列表/历史记录元数据 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music App 播放列表/历史记录元数据 Hook 安装失败", it)
        }
    }

    private fun hookInAppQueueAdapter() {
        runCatching {
            val adapterClass = classLoader.loadClass("Y8.a")
            val submitMethod = AppleReflection.findMethod(
                adapterClass,
                "B",
                parameterCount = 1,
            )
            hookRegistrar.install(submitMethod, before = { chain ->
                val adapterObject = chain.thisObject ?: return@installHook
                (adapterObject as? RecyclerView.Adapter<*>)?.let(::registerInAppQueueAdapter)
                val items = chain.args.firstOrNull() as? Iterable<*> ?: return@installHook
                registerInAppQueueEntries(
                    items = items,
                    preBind = true,
                    maxEntries = MAX_QUEUE_LOCALIZED_PREFETCH_ENTRIES,
                    originalResolutionLimit = MAX_QUEUE_PREBIND_ENTRIES,
                )
                if (BuildConfig.DEBUG) {
                    debugQueueBindTraceKeys.clear()
                    items.take(MAX_DEBUG_QUEUE_SUBMIT_TRACE_ENTRIES).forEachIndexed { position, entry ->
                        captureInAppQueueEntry(
                            position = position,
                            entry = entry,
                            entrySource = "adapter_submit",
                            requestResolution = false,
                            preBind = true,
                        )
                    }
                }
            })

            val bindMethod = AppleReflection.findMethod(
                adapterClass,
                "p",
                parameterCount = 2,
            )
            hookRegistrar.install(bindMethod, before = { chain ->
                val adapter = chain.thisObject ?: return@installHook
                (adapter as? RecyclerView.Adapter<*>)?.let(::registerInAppQueueAdapter)
                val position = chain.args.getOrNull(1) as? Int ?: return@installHook
                val entryLookup = inAppQueueEntryAt(adapter, position)
                captureInAppQueueEntry(
                    position = position,
                    entry = entryLookup.entry,
                    entrySource = entryLookup.source,
                    requestResolution = true,
                    preBind = true,
                    priority = AppleInternalCatalogResolver.RequestPriority.VISIBLE,
                )
            })
            ProviderLogger.info("Apple Music App 播放列表 Adapter 捕获 Hook 已安装")
        }.onFailure {
            ProviderLogger.error("Apple Music App 播放列表 Adapter 捕获 Hook 安装失败", it)
        }
    }

    private fun registerInAppQueueAdapter(adapter: RecyclerView.Adapter<*>) {
        var registered = false
        inAppQueueAdapterRefs.forEach { ref ->
            val target = ref.get()
            if (target == null) {
                inAppQueueAdapterRefs.remove(ref)
            } else if (target === adapter) {
                registered = true
            }
        }
        if (!registered) inAppQueueAdapterRefs.add(WeakReference(adapter))
    }

    private fun refreshInAppQueueAdapters(mediaId: String): Int {
        if (!isCurrentMetadataSurfaceMediaId(mediaId)) return 0
        val isCaptured = queueInAppMetadataRefresh?.mediaIds?.contains(mediaId) == true ||
            historyInAppMetadataRefresh?.mediaIds?.contains(mediaId) == true ||
            inAppPlaybackItemRefs[mediaId]?.any { it.playbackItem.get() != null } == true
        if (!isCaptured) return 0
        var targets = 0
        inAppQueueAdapterRefs.forEach { ref ->
            val adapter = ref.get()
            if (adapter == null) {
                inAppQueueAdapterRefs.remove(ref)
                return@forEach
            }
            val itemCount = runCatching { adapter.itemCount }.getOrDefault(0)
            val matchingPositions = (0 until itemCount).filter { position ->
                val entry = inAppQueueEntryAt(adapter, position).entry
                registerInAppQueueEntry(
                    entry = entry,
                    requestResolution = false,
                    preBind = true,
                ) == mediaId
            }
            if (matchingPositions.isEmpty()) return@forEach
            targets += 1
            mainHandler.post {
                if (!isCurrentMetadataSurfaceMediaId(mediaId)) return@post
                matchingPositions.forEach(adapter::notifyItemChanged)
            }
        }
        return targets
    }

    private fun inAppQueueEntryAt(adapter: Any, position: Int): InAppQueueEntryLookup {
        // Y8.a is a ListAdapter. Its source field is replaced before AsyncListDiffer commits,
        // so only A(position) identifies the item currently bound at this adapter position.
        val displayedEntry = runCatching {
            AppleReflection.call(adapter, "A", position)
        }.getOrNull()
        if (displayedEntry != null) {
            return InAppQueueEntryLookup(displayedEntry, "displayed_list")
        }
        val submittedEntry = runCatching {
            (AppleReflection.field(adapter, "l") as? List<*>)?.getOrNull(position)
        }.getOrNull()
        return InAppQueueEntryLookup(submittedEntry, "submitted_list_fallback")
    }

    private fun registerInAppQueueEntries(
        items: Iterable<*>,
        preBind: Boolean = false,
        maxEntries: Int = Int.MAX_VALUE,
        originalResolutionLimit: Int = MAX_QUEUE_PREBIND_ENTRIES,
        historyEntries: Boolean = false,
    ): Set<String> = buildSet {
        val iterator = items.iterator()
        var processed = 0
        while (iterator.hasNext() && processed < maxEntries) {
            val entry = iterator.next()
            registerInAppQueueEntry(
                entry = entry,
                requestResolution = false,
                preBind = preBind,
                historyEntry = historyEntries || isInAppHistoryQueueEntry(entry),
            )?.let(::add)
            processed += 1
        }
        ensureInAppMetadataOverrides(
            mediaIds = this,
            preBind = preBind,
            originalResolutionLimit = originalResolutionLimit,
        )
    }

    private fun isInAppHistoryQueueEntry(entry: Any?): Boolean =
        entry != null && isInAppHistoryQueueEntryClassName(entry.javaClass.name)

    private fun registerInAppQueueEntry(
        entry: Any?,
        requestResolution: Boolean = true,
        preBind: Boolean = false,
        historyEntry: Boolean = entry?.let(::isInAppHistoryQueueEntry) == true,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
    ): String? {
        entry ?: return null
        val item = runCatching { AppleReflection.field(entry, "b") }.getOrNull()
            ?: return null
        if (historyEntry) {
            inAppPlaybackItemContracts[item] = InAppPlaybackItemContract.HISTORY
        }
        val metadata = runCatching { AppleReflection.field(item, "d") }.getOrNull()
        if (metadata != null) {
            val itemId = runCatching { AppleReflection.field(item, "a") as? String }.getOrNull()
            val mediaId = media3MetadataId(
                metadata = metadata,
                fallback = itemId,
                trustedFallback = true,
            ) ?: return null
            registerInAppMetadata(
                mediaId = mediaId,
                metadata = metadata,
                requestResolution = requestResolution,
                preBind = preBind,
                priority = priority,
            )
            return mediaId
        }

        val mediaId = contentItemMediaId(item, refresh = historyEntry) ?: return null
        val existingAlias = effectiveInAppMetadataOverride(mediaId)
        existingAlias?.let { alias ->
            registerInAppPlaybackItem(
                mediaId = mediaId,
                playbackItem = item,
                notifyChange = false,
                analyzeMetadata = shouldRequestInAppMetadataOverride(mediaId),
            )
            applyAliasToInAppPlaybackItem(item, alias, notifyChange = !preBind)
            if (
                requestResolution &&
                shouldRequestInAppMetadataOverride(mediaId)
            ) {
                ensureInAppMetadataOverride(
                    mediaId = mediaId,
                    preBind = preBind,
                    priority = priority,
                )
            }
            return mediaId
        }
        registerInAppPlaybackItem(mediaId, item, notifyChange = !preBind)
        effectiveInAppMetadataOverride(mediaId)?.let { alias ->
            applyAliasToInAppPlaybackItem(item, alias, notifyChange = !preBind)
        }
        if (requestResolution && shouldRequestInAppMetadataOverride(mediaId)) {
            ensureInAppMetadataOverride(
                mediaId = mediaId,
                preBind = preBind,
                priority = priority,
            )
        }
        return mediaId
    }

    private fun captureInAppQueueEntry(
        position: Int,
        entry: Any?,
        entrySource: String,
        requestResolution: Boolean,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
    ): String? {
        val historyEntry = isInAppHistoryQueueEntry(entry)
        val item = entry?.let {
            runCatching { AppleReflection.field(it, "b") }.getOrNull()
        }
        val metadata = item?.takeUnless { historyEntry }?.let {
            runCatching { AppleReflection.field(it, "d") }.getOrNull()
        }
        val contract = if (historyEntry) {
            InAppPlaybackItemContract.HISTORY
        } else {
            InAppPlaybackItemContract.STANDARD
        }
        val itemId = when {
            item == null -> null
            historyEntry -> contentItemMediaId(item, refresh = true)
            else -> runCatching { AppleReflection.field(item, "a") as? String }.getOrNull()
        }
        val bundleId = metadata?.let {
            runCatching {
                (AppleReflection.field(it, "I") as? Bundle)?.getString(
                    "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"
                )
            }.getOrNull()
        }
        val rawTitle = when {
            item == null -> null
            historyEntry -> readInAppPlaybackItemValue(
                item,
                InAppPlaybackItemField.TITLE,
                contract,
            )
            else -> metadata?.let {
                runCatching { AppleReflection.field(it, "a") }.getOrNull()?.toString()
            }
        }
        val rawSubtitle = when {
            item == null -> null
            historyEntry -> readInAppPlaybackItemValue(
                item,
                InAppPlaybackItemField.ARTIST,
                contract,
            )
            else -> metadata?.let {
                runCatching { AppleReflection.field(it, "b") }.getOrNull()?.toString()
            }
        }
        val mediaId = registerInAppQueueEntry(
            entry = entry,
            requestResolution = requestResolution,
            preBind = preBind,
            historyEntry = historyEntry,
            priority = priority,
        )
        if (mediaId != null) {
            when (priority) {
                AppleInternalCatalogResolver.RequestPriority.VISIBLE ->
                    markMetadataVisible(listOf(mediaId))
                AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE -> Unit
                AppleInternalCatalogResolver.RequestPriority.BACKGROUND -> Unit
            }
        }
        if (BuildConfig.DEBUG) {
            val finalTitle = when {
                item == null -> null
                historyEntry -> readInAppPlaybackItemValue(
                    item,
                    InAppPlaybackItemField.TITLE,
                    contract,
                )
                else -> metadata?.let {
                    runCatching { AppleReflection.field(it, "a") }.getOrNull()?.toString()
                }
            }
            val finalSubtitle = when {
                item == null -> null
                historyEntry -> readInAppPlaybackItemValue(
                    item,
                    InAppPlaybackItemField.ARTIST,
                    contract,
                )
                else -> metadata?.let {
                    runCatching { AppleReflection.field(it, "b") }.getOrNull()?.toString()
                }
            }
            logInAppQueueBind(
                position = position,
                entry = entry,
                entrySource = entrySource,
                item = item,
                metadata = metadata,
                mediaId = mediaId,
                itemId = itemId,
                bundleId = bundleId,
                rawTitle = rawTitle,
                rawSubtitle = rawSubtitle,
                finalTitle = finalTitle,
                finalSubtitle = finalSubtitle,
                historyEntry = historyEntry,
                resolutionRequested = requestResolution,
            )
        }
        return mediaId
    }

    private fun logInAppQueueBind(
        position: Int,
        entry: Any?,
        entrySource: String,
        item: Any?,
        metadata: Any?,
        mediaId: String?,
        itemId: String?,
        bundleId: String?,
        rawTitle: String?,
        rawSubtitle: String?,
        finalTitle: String?,
        finalSubtitle: String?,
        historyEntry: Boolean,
        resolutionRequested: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val event = if (historyEntry) "history_bind" else "queue_bind"
        val traceKey =
            "$event:$entrySource:$position:$mediaId:$rawTitle:$rawSubtitle:$finalTitle:$finalSubtitle"
        if (
            traceKey !in debugQueueBindTraceKeys &&
            debugQueueBindTraceKeys.size >= MAX_DEBUG_QUEUE_BIND_TRACE_KEYS
        ) {
            return
        }
        if (!debugQueueBindTraceKeys.add(traceKey)) return
        val alias = mediaId?.let(::effectiveInAppMetadataOverride)
        val shouldRequest = mediaId?.let(::shouldRequestInAppMetadataOverride)
        ProviderLogger.info(
            "Apple Music 队列绑定: event=$event, source=$entrySource, position=$position, " +
                "entryClass=${entry?.javaClass?.name}, itemClass=${item?.javaClass?.name}, " +
                "metadataClass=${metadata?.javaClass?.name}, itemId=$itemId, bundleId=$bundleId, " +
                "registeredId=$mediaId, rawTitle=$rawTitle, rawSubtitle=$rawSubtitle, " +
                "alias=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                "resolutionRequested=$resolutionRequested, shouldRequest=$shouldRequest, " +
                "originalResolved=${mediaId != null && mediaId in originalMetadataResolvedIds}, " +
                "originalPending=${mediaId != null && mediaId in originalMetadataPendingIds}, " +
                "localizedResolved=${mediaId != null &&
                    playbackMetadataOverrides.containsKey(mediaId)}, " +
                "writeTarget=${metadata?.javaClass?.name ?: item?.javaClass?.name}, " +
                "finalTitle=$finalTitle, finalSubtitle=$finalSubtitle"
        )
    }

    private fun media3MetadataId(
        metadata: Any,
        fallback: String?,
        trustedFallback: Boolean = false,
    ): String? {
        val bundleId = runCatching {
            (AppleReflection.field(metadata, "I") as? Bundle)?.getString(
                "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"
            )
        }.getOrNull()
        bundleId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.let { return it }
        val inferredId = media3MetadataAccountMatches(metadata).singleOrNull()
        inferredId?.let { return it }
        return fallback
            ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?.takeIf { trustedFallback || media3MetadataMatchesId(metadata, it) }
    }

    private fun media3MetadataMatchesId(metadata: Any, mediaId: String): Boolean {
        val title = runCatching { AppleReflection.field(metadata, "a") as? CharSequence }
            .getOrNull()?.toString()?.takeIf(String::isNotBlank)
        val artist = runCatching { AppleReflection.field(metadata, "b") as? CharSequence }
            .getOrNull()?.toString()?.takeIf(String::isNotBlank)
        if (title == null && artist == null) return false

        val account = playbackMetadataAccountValues[mediaId]
        val alias = effectiveInAppMetadataOverride(mediaId)
        val cached = MediaMetadataCache.getMetadataById(mediaId)
        val framework = currentFrameworkMediaSessionRefresh
            ?.takeIf { it.mediaId == mediaId }
            ?.metadata
        val knownTitles = sequenceOf(
            account?.title,
            alias?.title,
            cached?.title,
            framework?.getString(MediaMetadata.METADATA_KEY_TITLE),
            framework?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        ).filterNotNull().filter(String::isNotBlank).toSet()
        val knownArtists = sequenceOf(
            account?.artist,
            alias?.artist,
            cached?.artist,
            framework?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            framework?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
        ).filterNotNull().filter(String::isNotBlank).toSet()
        return (title == null || title in knownTitles) &&
            (artist == null || artist in knownArtists)
    }

    private fun media3MetadataDetails(metadata: Any): String {
        val bundleId = runCatching {
            (AppleReflection.field(metadata, "I") as? Bundle)?.getString(
                "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"
            )
        }.getOrNull()
        val title = runCatching { AppleReflection.field(metadata, "a") }.getOrNull()
        val artist = runCatching { AppleReflection.field(metadata, "b") }.getOrNull()
        val matches = media3MetadataAccountMatches(metadata)
        return "bundleId=$bundleId, title=$title, artist=$artist, " +
            "accountMatches=$matches, matchCount=${matches.size}"
    }

    private fun media3MetadataAccountMatches(metadata: Any): List<String> {
        val title = runCatching { AppleReflection.field(metadata, "a") as? CharSequence }
            .getOrNull()?.toString()?.takeIf(String::isNotBlank) ?: return emptyList()
        val artist = runCatching { AppleReflection.field(metadata, "b") as? CharSequence }
            .getOrNull()?.toString()?.takeIf(String::isNotBlank) ?: return emptyList()
        return playbackMetadataAccountValues.entries.mapNotNull { (mediaId, account) ->
            val alias = effectiveInAppMetadataOverride(mediaId)
            val titleMatches = title == account.title || title == alias?.title
            val artistMatches = artist == account.artist || artist == alias?.artist
            mediaId.takeIf { titleMatches && artistMatches }
        }
    }

    private fun activePlaybackMediaIdentity(): ActivePlaybackMediaIdentity {
        val candidates = listOf(
            "queue" to currentPlaybackMetadataId,
            "in_app_now_playing" to currentInAppMetadataRefresh?.mediaId,
            "framework_session" to currentFrameworkMediaSessionRefresh?.mediaId,
            "playback_refresh" to currentPlaybackMetadataRefresh?.mediaId,
        )
        val selected = candidates.firstOrNull { (_, mediaId) ->
            !mediaId.isNullOrBlank() && mediaId.all(Char::isDigit)
        }
        return ActivePlaybackMediaIdentity(
            mediaId = selected?.second,
            source = selected?.first ?: "none",
            candidates = candidates.joinToString(prefix = "[", postfix = "]") { (source, id) ->
                "$source=$id"
            },
        )
    }

    private fun logMetadataIdentity(
        event: String,
        identity: ActivePlaybackMediaIdentity = activePlaybackMediaIdentity(),
        details: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val sequence = metadataTraceSequence.incrementAndGet()
        val alias = identity.mediaId?.let(::effectiveInAppMetadataOverride)
        ProviderLogger.info(
            "Apple Music 元数据链路: seq=$sequence, event=$event, " +
                "selected=${identity.mediaId}, source=${identity.source}, " +
                "candidates=${identity.candidates}, aliasHit=${alias != null}, " +
                "alias=${alias?.title}/${alias?.artist}/${alias?.album}, $details"
        )
    }

    private fun registerInAppMetadata(
        mediaId: String,
        metadata: Any,
        requestResolution: Boolean = true,
        preBind: Boolean = false,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
    ) {
        recordCurrentRecyclerMediaId(mediaId)
        when (priority) {
            AppleInternalCatalogResolver.RequestPriority.VISIBLE ->
                markMetadataVisible(listOf(mediaId))
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE -> Unit
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND -> Unit
        }
        registerInAppMetadataRef(mediaId, metadata)
        effectiveInAppMetadataOverride(mediaId)?.let { alias ->
            applyAliasToInAppMetadata(metadata, alias)
        }
        if (requestResolution && shouldRequestInAppMetadataOverride(mediaId)) {
            ensureInAppMetadataOverride(
                mediaId = mediaId,
                preBind = preBind,
                priority = priority,
            )
        }
    }

    private fun registerInAppMetadataRef(mediaId: String, metadata: Any) {
        val originalTitle = AppleReflection.field(metadata, "a")
        val originalArtist = AppleReflection.field(metadata, "b")
        mergePlaybackAccountMetadata(
            mediaId = mediaId,
            title = originalTitle?.toString(),
            artist = originalArtist?.toString(),
        )
        val registered = synchronized(inAppMetadataIds) {
            if (inAppMetadataIds[metadata] == mediaId) {
                true
            } else {
                inAppMetadataIds[metadata] = mediaId
                false
            }
        }
        if (!registered) {
            val refs = inAppMetadataRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
            refs.add(
                InAppMetadataRef(
                    metadata = WeakReference(metadata),
                    originalTitle = originalTitle,
                    originalArtist = originalArtist,
                )
            )
        }
    }

    private fun registerInAppPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean = true,
        analyzeMetadata: Boolean = true,
    ) {
        recordCurrentRecyclerMediaId(mediaId)
        val registered = synchronized(inAppPlaybackItemIds) {
            if (inAppPlaybackItemIds[playbackItem] == mediaId) {
                true
            } else {
                inAppPlaybackItemIds[playbackItem] = mediaId
                false
            }
        }
        val refs = inAppPlaybackItemRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
        val contract = inAppPlaybackItemContract(playbackItem)
        val rawTitle = readInAppPlaybackItemValue(
            playbackItem,
            InAppPlaybackItemField.TITLE,
            contract,
        )
        val rawArtist = readInAppPlaybackItemValue(
            playbackItem,
            InAppPlaybackItemField.ARTIST,
            contract,
        )
        val rawCollectionName = readInAppPlaybackItemValue(
            playbackItem,
            InAppPlaybackItemField.ALBUM,
            contract,
        )
        if (!registered) {
            refs.add(
                InAppPlaybackItemRef(
                    playbackItem = WeakReference(playbackItem),
                    originalTitle = rawTitle,
                    originalArtist = rawArtist,
                    originalCollectionName = rawCollectionName,
                    contract = contract,
                )
            )
        }
        mergePlaybackAccountMetadata(
            mediaId = mediaId,
            title = rawTitle,
            artist = rawArtist,
            reconcileArtistAssociations = analyzeMetadata,
        )
        if (!analyzeMetadata) return
        val lookupIds = contentItemCatalogLookupIds(playbackItem, mediaId)
        val entityType = contentItemLocalizedEntityType(playbackItem)
        val artistKeys = contentItemArtistCacheKeys(
            playbackItem,
            rawArtist ?: rawTitle.takeIf {
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.ARTIST
            },
        )
        if (artistKeys.isNotEmpty()) {
            playbackMetadataArtistKeys.merge(mediaId, artistKeys) { previous, incoming ->
                previous + incoming
            }
            val associatedArtistIds = artistIdsFromAssociationKeys(artistKeys)
            mergePlaybackAssociatedArtistIds(
                mediaId = mediaId,
                artistIds = associatedArtistIds,
            )
            if (
                !playbackMetadataOverrides.containsKey(mediaId) &&
                shouldAcceptAssociatedArtistResolution(
                    requestedArtistIds = associatedArtistIds,
                    currentArtistIds =
                        playbackMetadataAssociatedArtistIds[mediaId].orEmpty(),
                    artistCredit = associatedArtistCredit(mediaId),
                )
            ) {
                internalCatalogResolver.cachedLocalizedArtist(
                    configuredContentUiLanguage(),
                    localizedArtistCacheKeys(artistKeys),
                )?.let { artistAlias ->
                    playbackArtistOverrides[mediaId] = artistAlias
                    effectiveInAppMetadataOverride(mediaId)?.let { effectiveAlias ->
                        applyAliasToInAppPlaybackItem(
                            playbackItem,
                            effectiveAlias,
                            notifyChange = notifyChange,
                        )
                    }
                }
            }
        }
        playbackMetadataLookupIds.merge(mediaId, lookupIds) { previous, incoming ->
            previous + incoming
        }
        if (entityType == null) {
            nonCatalogContentItemIds.add(mediaId)
        } else {
            nonCatalogContentItemIds.remove(mediaId)
            playbackMetadataEntityTypes[mediaId] = entityType
        }
    }

    private fun mergePlaybackAccountMetadata(
        mediaId: String,
        title: String?,
        artist: String?,
        reconcileArtistAssociations: Boolean = true,
    ) {
        val incoming = AccountMetadata(
            title = title?.takeIf(String::isNotBlank),
            artist = artist?.takeIf(String::isNotBlank),
        )
        if (incoming.title == null && incoming.artist == null) return
        val previousArtist = playbackMetadataAccountValues[mediaId]?.artist
        val merged = playbackMetadataAccountValues.merge(mediaId, incoming) { previous, next ->
            AccountMetadata(
                title = previous.title ?: next.title,
                artist = previous.artist ?: next.artist,
            )
        }
        if (reconcileArtistAssociations) {
            enforceAssociatedArtistIsolation(
                mediaId = mediaId,
                resetSafeResolution = previousArtist != merged?.artist,
            )
            hydrateSharedArtistOverrides(mediaId)
        }
    }

    private fun registerInAppContainerItem(
        mediaId: String,
        containerItem: Any,
        kind: InAppContainerKind,
    ) {
        inAppContainerItemIds[containerItem] = mediaId
        val refs = inAppContainerItemRefs.computeIfAbsent(mediaId) { ConcurrentLinkedQueue() }
        var registered = false
        refs.forEach { ref ->
            val target = ref.containerItem.get()
            if (target == null) {
                refs.remove(ref)
            } else if (target === containerItem) {
                registered = true
            }
        }
        if (!registered) {
            refs.add(
                InAppContainerItemRef(
                    containerItem = WeakReference(containerItem),
                    kind = kind,
                    originalTitle = rawContentItemValue(containerItem, "name") as? String,
                )
            )
        }
        ProviderLogger.info(
            "Apple Music 播放页跳转项捕获: id=$mediaId, kind=$kind, " +
                "class=${containerItem.javaClass.name}"
        )
    }

    private fun inAppContainerKind(containerItem: Any): InAppContainerKind? {
        val classNames = generateSequence(containerItem.javaClass) { it.superclass }
            .map(Class<*>::getName)
            .toSet()
        return when {
            "com.apple.android.music.model.Artist" in classNames -> InAppContainerKind.ARTIST
            "com.apple.android.music.model.Album" in classNames -> InAppContainerKind.ALBUM
            else -> null
        }
    }

    private fun markInAppContainerNavigationItem(
        containerItem: Any,
        kind: InAppContainerKind,
        mediaId: String,
    ) {
        var registered = false
        inAppContainerNavigationRefs.forEach { ref ->
            val target = ref.containerItem.get()
            if (target == null) {
                inAppContainerNavigationRefs.remove(ref)
            } else if (target === containerItem) {
                if (ref.kind == kind && ref.mediaId == mediaId) {
                    registered = true
                } else {
                    inAppContainerNavigationRefs.remove(ref)
                }
            }
        }
        if (!registered) {
            inAppContainerNavigationRefs.add(
                InAppContainerNavigationRef(
                    containerItem = WeakReference(containerItem),
                    kind = kind,
                    mediaId = mediaId,
                )
            )
        }
    }

    private fun inAppContainerNavigationBinding(
        containerItem: Any,
    ): InAppContainerNavigationRef? {
        inAppContainerNavigationRefs.forEach { ref ->
            val target = ref.containerItem.get()
            if (target == null) {
                inAppContainerNavigationRefs.remove(ref)
            } else if (target === containerItem) {
                return ref
            }
        }
        return null
    }

    private fun rawContentItemValue(contentItem: Any, fieldName: String): Any? =
        runCatching { AppleReflection.field(contentItem, fieldName) }.getOrNull()

    private fun inAppPlaybackItemContract(playbackItem: Any): InAppPlaybackItemContract =
        inAppPlaybackItemContracts[playbackItem] ?: InAppPlaybackItemContract.STANDARD

    private fun readInAppPlaybackItemValue(
        playbackItem: Any,
        field: InAppPlaybackItemField,
        contract: InAppPlaybackItemContract = inAppPlaybackItemContract(playbackItem),
    ): String? {
        val access = inAppPlaybackItemAccess(contract, field) ?: return null
        val value = if (access.readViaMethod) {
            runCatching {
                internalContentItemGetterGuard.run {
                    AppleReflection.call(playbackItem, access.readMember)
                }
            }.getOrNull()
        } else {
            rawContentItemValue(playbackItem, access.readMember)
        }
        return value?.toString()
    }

    private fun writeInAppPlaybackItemValue(
        playbackItem: Any,
        field: InAppPlaybackItemField,
        value: String?,
        contract: InAppPlaybackItemContract = inAppPlaybackItemContract(playbackItem),
    ): Boolean {
        val setter = inAppPlaybackItemAccess(contract, field)?.setter ?: return false
        return runCatching { AppleReflection.call(playbackItem, setter, value) }.isSuccess
    }

    private fun contentItemMediaId(
        contentItem: Any,
        refresh: Boolean = false,
    ): String? {
        if (!refresh) {
            synchronized(contentItemMediaIds) {
                contentItemMediaIds[contentItem]?.let { return it }
            }
        }
        val subscriptionStoreId = runCatching {
            AppleReflection.call(contentItem, "getSubscriptionStoreId") as? String
        }.getOrNull()
        val id = runCatching {
            AppleReflection.call(contentItem, "getId") as? String
        }.getOrNull()
        val persistentId = runCatching {
            AppleReflection.call(contentItem, "getPersistentId") as? Long
        }.getOrNull()?.takeIf { it > 0L }?.toString()
        val mediaId = sequenceOf(subscriptionStoreId, id, persistentId)
            .filterNotNull()
            .firstOrNull { candidate -> candidate.isNotBlank() && candidate.all(Char::isDigit) }
        synchronized(contentItemMediaIds) {
            if (mediaId == null && refresh) {
                contentItemMediaIds.remove(contentItem)
            } else if (mediaId != null) {
                contentItemMediaIds[contentItem] = mediaId
            }
        }
        return mediaId
    }

    private fun contentItemCatalogLookupIds(contentItem: Any, mediaId: String): Set<String> =
        buildSet {
            fun addString(value: Any?) {
                value?.toString()?.trim()?.takeIf { candidate ->
                    candidate.isNotEmpty() && candidate.all(Char::isDigit)
                }?.let(::add)
            }

            addString(mediaId)
            listOf("getSubscriptionStoreId", "getId").forEach { methodName ->
                addString(runCatching { AppleReflection.call(contentItem, methodName) }.getOrNull())
            }
            listOf("getAssetAdamId", "getReportingAdamId").forEach { methodName ->
                val value = runCatching { AppleReflection.call(contentItem, methodName) as? Long }
                    .getOrNull()
                value?.takeIf { it > 0L }?.let(::addString)
            }
            val formerIds = runCatching {
                AppleReflection.call(contentItem, "getFormerIds") as? Array<*>
            }.getOrNull().orEmpty()
            formerIds.forEach(::addString)
        }

    private fun contentItemArtistCacheKeys(
        contentItem: Any,
        rawArtist: String?,
    ): Set<String> = buildSet {
        rawArtist?.takeIf(String::isNotBlank)?.let { artist ->
            add("name:${AppleInternalCatalogResolver.normalizedArtistNameKey(artist)}")
        }
        listOf(
            "getArtistId",
            "getArtistAdamId",
            "getArtistStoreId",
            "getArtistSubscriptionStoreId",
        ).forEach { methodName ->
            val value = runCatching { AppleReflection.call(contentItem, methodName) }.getOrNull()
            value?.toString()?.trim()?.takeIf { id ->
                id.isNotEmpty() && id.all(Char::isDigit)
            }?.let { add("id:$it") }
        }
    }

    private fun mergePlaybackAssociatedArtistIds(
        mediaId: String,
        artistIds: Collection<String>,
    ) {
        val normalizedIds = artistIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "0" && it.all(Char::isDigit) }
            .distinct()
            .toList()
        if (normalizedIds.isEmpty()) return
        val previousAlias = effectiveInAppMetadataOverride(mediaId)
        trackAssociatedMediaIds(mediaId, normalizedIds)
        var changed = false
        playbackMetadataAssociatedArtistIds.compute(mediaId) { _, previous ->
            val merged = (previous.orEmpty() + normalizedIds).distinct()
            changed = merged != previous
            merged
        }
        if (changed) {
            enforceAssociatedArtistIsolation(
                mediaId = mediaId,
                resetSafeResolution = true,
            )
            hydrateSharedArtistOverrides(mediaId)
            syncMetadataRequestScope()
            changedAssociatedArtistAlias(
                previousAlias = previousAlias,
                updatedAlias = effectiveInAppMetadataOverride(mediaId),
            )?.let { updatedAlias ->
                if (BuildConfig.DEBUG) {
                    logMetadataIdentity(
                        event = "associated_artist_alias_hydrated",
                        details = "contentId=$mediaId, artistIds=$normalizedIds, " +
                            "before=${previousAlias?.title}/${previousAlias?.artist}/" +
                            "${previousAlias?.album}, " +
                            "after=${updatedAlias.title}/${updatedAlias.artist}/" +
                            updatedAlias.album,
                    )
                }
                applyAliasToInAppMetadataRefs(
                    mediaId = mediaId,
                    alias = updatedAlias,
                    forceRebind = true,
                    notifyModelChange = true,
                )
            }
        }
    }

    private fun trackAssociatedMediaIds(
        mediaId: String,
        artistIds: Collection<String>,
    ) {
        normalizedAssociatedArtistIds(artistIds).forEach { artistId ->
            associatedMediaIdsByArtistKey.computeIfAbsent("id:$artistId") {
                ConcurrentHashMap.newKeySet()
            }.add(mediaId)
        }
    }

    internal fun artistIdsFromAssociationKeys(keys: Collection<String>): List<String> =
        keys.mapNotNull(::artistIdFromAssociationKey).distinct()

    private fun normalizedAssociatedArtistIds(artistIds: Collection<String>): Set<String> =
        artistIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "0" && it.all(Char::isDigit) }
            .toCollection(linkedSetOf())

    internal fun sharedAssociatedArtistId(
        artistIds: Collection<String>,
        artistCredit: String? = null,
    ): String? {
        val artistId = normalizedAssociatedArtistIds(artistIds).singleOrNull() ?: return null
        val normalizedCredit = artistCredit?.trim().orEmpty()
        return artistId.takeIf {
            normalizedCredit.isNotEmpty() &&
                !AppleInternalCatalogResolver.isCollaborationArtistName(normalizedCredit)
        }
    }

    internal fun shouldUseAssociatedArtistEntities(
        artistIds: Collection<String>,
        artistCredit: String? = null,
    ): Boolean = sharedAssociatedArtistId(artistIds, artistCredit) != null

    internal fun shouldShareAssociatedArtistAlias(
        artistId: String,
        targetArtistIds: Collection<String>,
        targetArtistCredit: String?,
    ): Boolean = sharedAssociatedArtistId(
        artistIds = targetArtistIds,
        artistCredit = targetArtistCredit,
    ) == artistId

    private fun associatedArtistCredit(mediaId: String): String? {
        val account = playbackMetadataAccountValues[mediaId]
        return associatedArtistCredit(
            entityType = playbackMetadataEntityTypes[mediaId],
            accountTitle = account?.title,
            accountArtist = account?.artist,
        )
    }

    internal fun associatedArtistCredit(
        entityType: AppleInternalCatalogResolver.LocalizedEntityType?,
        accountTitle: String?,
        accountArtist: String?,
    ): String? = if (
        entityType == AppleInternalCatalogResolver.LocalizedEntityType.ARTIST
    ) {
        accountTitle?.takeIf(String::isNotBlank) ?: accountArtist
    } else {
        accountArtist
    }

    internal fun shouldAcceptAssociatedArtistResolution(
        requestedArtistIds: Collection<String>,
        currentArtistIds: Collection<String>,
        artistCredit: String?,
    ): Boolean {
        val requested = normalizedAssociatedArtistIds(requestedArtistIds)
        val current = normalizedAssociatedArtistIds(currentArtistIds)
        return requested == current &&
            shouldUseAssociatedArtistEntities(current, artistCredit)
    }

    private fun enforceAssociatedArtistIsolation(
        mediaId: String,
        resetSafeResolution: Boolean = false,
    ): Boolean {
        val artistIds = playbackMetadataAssociatedArtistIds[mediaId].orEmpty()
        if (artistIds.isEmpty()) return false
        val canUseAssociatedArtist = shouldUseAssociatedArtistEntities(
            artistIds = artistIds,
            artistCredit = associatedArtistCredit(mediaId),
        )
        if (canUseAssociatedArtist && resetSafeResolution) {
            originalArtistResolvedIds.remove(mediaId)
        } else if (!canUseAssociatedArtist) {
            originalArtistResolvedIds.add(mediaId)
            originalArtistOverrides.remove(mediaId)
            playbackArtistOverrides.remove(mediaId)
        }
        return canUseAssociatedArtist
    }

    private fun sharedAssociatedArtistId(mediaId: String): String? =
        sharedAssociatedArtistId(
            artistIds = playbackMetadataAssociatedArtistIds[mediaId].orEmpty(),
            artistCredit = associatedArtistCredit(mediaId),
        )

    private fun localizedArtistOverrideKey(selection: Int, artistId: String): String =
        "$selection:$artistId"

    private fun hydrateSharedArtistOverrides(mediaId: String) {
        val artistId = sharedAssociatedArtistId(mediaId) ?: return
        sharedLocalizedArtistOverrides[
            localizedArtistOverrideKey(configuredContentUiLanguage(), artistId)
        ]?.let { alias ->
            playbackArtistOverrides[mediaId] = alias
        }
        sharedOriginalArtistOverrides[artistId]?.let { alias ->
            originalArtistResolvedIds.add(mediaId)
            originalArtistOverrides[mediaId] = alias
        }
    }

    private fun effectiveInAppMetadataOverride(
        mediaId: String,
    ): AppleInternalCatalogResolver.Alias? {
        val selection = configuredContentUiLanguage()
        val associatedArtistIds = playbackMetadataAssociatedArtistIds[mediaId].orEmpty()
        val sharedArtistId = sharedAssociatedArtistId(mediaId)
        val canUseAssociatedArtist = sharedArtistId != null
        val localizedMetadata = playbackMetadataOverrides[mediaId] ?: if (
            shouldOverrideAccountLanguage(selection)
        ) {
            val entityType = playbackMetadataEntityTypes[mediaId]
                ?: AppleInternalCatalogResolver.LocalizedEntityType.SONG
            internalCatalogResolver.cachedLocalizedMetadata(
                selection = selection,
                entityType = entityType,
                mediaId = mediaId,
            )?.let { alias ->
                playbackMetadataOverrides.putIfAbsent(mediaId, alias)
                playbackMetadataOverrides[mediaId] ?: alias
            }
        } else {
            null
        }
        val localizedArtist = if (canUseAssociatedArtist) {
            val artistKeys = buildSet {
                addAll(playbackMetadataArtistKeys[mediaId].orEmpty())
                associatedArtistIds.forEach { artistId -> add("id:$artistId") }
            }
            playbackArtistOverrides[mediaId]
                ?: sharedArtistId.let { artistId ->
                    sharedLocalizedArtistOverrides[
                        localizedArtistOverrideKey(selection, artistId)
                    ]?.also { alias ->
                        playbackArtistOverrides.putIfAbsent(mediaId, alias)
                    }
                }
                ?: internalCatalogResolver.cachedLocalizedArtist(
                    selection = selection,
                    artistKeys = localizedArtistCacheKeys(artistKeys),
                )?.also { alias -> playbackArtistOverrides.putIfAbsent(mediaId, alias) }
        } else {
            null
        }
        val originalMetadata = originalMetadataOverrides[mediaId]?.takeIf {
            shouldExposeOriginalMetadataOverride(
                mediaId = mediaId,
                currentPlaybackMediaId = currentPlaybackMetadataId,
                confirmed = mediaId in confirmedOriginalMetadataIds,
            )
        }
        val originalArtist = if (canUseAssociatedArtist) {
            originalArtistOverrides[mediaId]
                ?: sharedArtistId.let { artistId ->
                    sharedOriginalArtistOverrides[artistId]?.also { alias ->
                        originalArtistResolvedIds.add(mediaId)
                        originalArtistOverrides.putIfAbsent(mediaId, alias)
                    }
                }
        } else {
            null
        }
        val originalArtistResolved = associatedArtistIds.isEmpty() ||
            !canUseAssociatedArtist ||
            mediaId in originalArtistResolvedIds
        return selectEffectiveMetadataAlias(
            restoreOriginalEnabled = isRestoreCjkOriginalMetadataEnabled(),
            originalMetadataResolved = mediaId in originalMetadataResolvedIds,
            originalMetadata = originalMetadata,
            originalArtistResolved = originalArtistResolved,
            originalArtist = originalArtist,
            localizedMetadata = localizedMetadata,
            localizedArtist = localizedArtist,
        ) ?: selectIndependentArtistAlias(
            restoreOriginalEnabled = isRestoreCjkOriginalMetadataEnabled(),
            canUseAssociatedArtist = canUseAssociatedArtist,
            originalArtist = originalArtist,
            localizedArtist = localizedArtist,
        )
    }

    private fun contentItemLocalizedEntityType(
        contentItem: Any,
    ): AppleInternalCatalogResolver.LocalizedEntityType? =
        localizedEntityTypeForQueueItem(
            historyEntry = inAppPlaybackItemContract(contentItem) ==
                InAppPlaybackItemContract.HISTORY,
            classNames =
            generateSequence(contentItem.javaClass as Class<*>?) { it.superclass }
                .map { it.simpleName }
                .toList(),
        )

    internal fun localizedEntityTypeForQueueItem(
        historyEntry: Boolean,
        classNames: Collection<String>,
    ): AppleInternalCatalogResolver.LocalizedEntityType? =
        if (historyEntry) {
            AppleInternalCatalogResolver.LocalizedEntityType.SONG
        } else {
            localizedEntityTypeForContentItemClassNames(classNames)
        }

    internal fun localizedEntityTypeForContentItemClassNames(
        classNames: Collection<String>,
    ): AppleInternalCatalogResolver.LocalizedEntityType? {
        val excludedTokens = listOf(
            "Radio",
            "Station",
            "Playlist",
            "Editorial",
            "Recommendation",
            "Curator",
        )
        if (classNames.any { className ->
                excludedTokens.any { token -> className.contains(token, ignoreCase = true) }
            }
        ) return null
        return when {
            classNames.any { it.contains("MusicVideo", ignoreCase = true) } ->
                AppleInternalCatalogResolver.LocalizedEntityType.SONG
            classNames.any { it.contains("Song", ignoreCase = true) } ->
                AppleInternalCatalogResolver.LocalizedEntityType.SONG
            classNames.any { it.contains("Album", ignoreCase = true) } ->
                AppleInternalCatalogResolver.LocalizedEntityType.ALBUM
            classNames.any { it.contains("Artist", ignoreCase = true) } ->
                AppleInternalCatalogResolver.LocalizedEntityType.ARTIST
            else -> null
        }
    }

    private fun ensureAssociatedArtistOverride(
        mediaId: String,
        preBind: Boolean = false,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
    ) {
        val artistIds = playbackMetadataAssociatedArtistIds[mediaId].orEmpty()
        if (artistIds.isEmpty()) return
        if (!shouldUseAssociatedArtistEntities(
                artistIds = artistIds,
                artistCredit = associatedArtistCredit(mediaId),
            )
        ) {
            originalArtistResolvedIds.add(mediaId)
            originalArtistOverrides.remove(mediaId)
            playbackArtistOverrides.remove(mediaId)
            return
        }
        val artistKeys = artistIds.mapTo(linkedSetOf()) { artistId -> "id:$artistId" }
        val selection = configuredContentUiLanguage()
        val cachedLocalizedArtist = internalCatalogResolver.cachedLocalizedArtist(
            selection = selection,
            artistKeys = artistKeys,
        )
        if (cachedLocalizedArtist != null) {
            playbackArtistOverrides.putIfAbsent(mediaId, cachedLocalizedArtist)
            applyPlaybackMetadataOverride(
                mediaId = mediaId,
                alias = cachedLocalizedArtist,
                forceInAppRebind = !preBind,
                rememberLocalizedArtist = false,
                artistOnly = true,
            )
        }
        if (!isRestoreCjkOriginalMetadataEnabled()) {
            if (cachedLocalizedArtist == null) {
                resolveLocalizedAssociatedArtist(mediaId, artistIds, preBind, priority = priority)
            }
            return
        }

        val originalLanguage = if (artistIds.size == 1) originalLanguageFor(mediaId) else null
        if (originalLanguage != null) {
            resolveOriginalAssociatedArtist(
                mediaId = mediaId,
                artistIds = artistIds,
                language = originalLanguage,
                preBind = preBind,
                priority = priority,
            )
        } else {
            resolveCachedOriginalAssociatedArtist(
                mediaId = mediaId,
                artistIds = artistIds,
                preBind = preBind,
                priority = priority,
            )
        }
    }

    private fun resolveCachedOriginalAssociatedArtist(
        mediaId: String,
        artistIds: List<String>,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
    ) {
        val requestKey = "original-artist-cache:$mediaId:" + artistIds.joinToString(",")
        if (!associatedArtistResolveRequests.add(requestKey)) return
        var bindingPhase = true
        collectAssociatedArtistAliases(
            artistIds = artistIds,
            request = { artistId, callback ->
                internalCatalogResolver.resolveCachedOriginalEntity(
                    mediaId = artistId,
                    entityType = AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                    lookupIds = playbackMetadataLookupIds[artistId].orEmpty(),
                    onResolved = callback,
                )
            },
        ) { resolved ->
            val callbackPreBind = preBind && bindingPhase
            associatedArtistResolveRequests.remove(requestKey)
            if (!shouldAcceptAssociatedArtistResolution(
                    requestedArtistIds = artistIds,
                    currentArtistIds =
                        playbackMetadataAssociatedArtistIds[mediaId].orEmpty(),
                    artistCredit = associatedArtistCredit(mediaId),
                )
            ) {
                enforceAssociatedArtistIsolation(mediaId)
                publishResolvedAssociatedArtistFallback(mediaId, callbackPreBind)
                return@collectAssociatedArtistAliases
            }
            val language = resolved.values.firstOrNull()?.language.orEmpty()
            val alias = associatedArtistAlias(artistIds, resolved, language)
            if (alias != null) {
                originalArtistResolvedIds.add(mediaId)
                alias.language.takeIf(String::isNotBlank)?.let { originalLanguage ->
                    rememberOriginalLanguageForArtist(mediaId, originalLanguage)
                }
                applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    forceInAppRebind = !callbackPreBind,
                    rememberLocalizedArtist = false,
                    originalMetadata = true,
                    artistOnly = true,
                )
            } else {
                resolveLocalizedAssociatedArtist(
                    mediaId = mediaId,
                    artistIds = artistIds,
                    preBind = callbackPreBind,
                    priority = priority,
                    completesOriginalArtistResolution = true,
                )
            }
        }
        bindingPhase = false
    }

    private fun resolveOriginalAssociatedArtist(
        mediaId: String,
        artistIds: List<String>,
        language: String,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
    ) {
        val canonicalLanguage = AppleInternalCatalogResolver.canonicalOriginalLanguage(language)
        val cached = originalArtistOverrides[mediaId]
        if (cached != null &&
            shouldAcceptAssociatedArtistResolution(
                requestedArtistIds = artistIds,
                currentArtistIds = playbackMetadataAssociatedArtistIds[mediaId].orEmpty(),
                artistCredit = associatedArtistCredit(mediaId),
            ) &&
            AppleInternalCatalogResolver.canonicalOriginalLanguage(cached.language) == canonicalLanguage
        ) {
            originalArtistResolvedIds.add(mediaId)
            return
        }
        val requestKey = "original-artist:$canonicalLanguage:$mediaId:" +
            artistIds.joinToString(",")
        if (!associatedArtistResolveRequests.add(requestKey)) return
        var bindingPhase = true
        collectAssociatedArtistAliases(
            artistIds = artistIds,
            request = { artistId, callback ->
                internalCatalogResolver.resolveOriginalEntityForLanguage(
                    mediaId = artistId,
                    lookupIds = listOf(artistId),
                    entityType = AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                    language = canonicalLanguage,
                    priority = priority,
                    onResolved = callback,
                )
            },
        ) { resolved ->
            val callbackPreBind = preBind && bindingPhase
            associatedArtistResolveRequests.remove(requestKey)
            if (!shouldAcceptAssociatedArtistResolution(
                    requestedArtistIds = artistIds,
                    currentArtistIds =
                        playbackMetadataAssociatedArtistIds[mediaId].orEmpty(),
                    artistCredit = associatedArtistCredit(mediaId),
                )
            ) {
                enforceAssociatedArtistIsolation(mediaId)
                publishResolvedAssociatedArtistFallback(mediaId, callbackPreBind)
                return@collectAssociatedArtistAliases
            }
            val alias = associatedArtistAlias(artistIds, resolved, canonicalLanguage)
            if (alias != null) {
                originalArtistResolvedIds.add(mediaId)
                applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    forceInAppRebind = !callbackPreBind,
                    rememberLocalizedArtist = false,
                    originalMetadata = true,
                    artistOnly = true,
                )
            } else {
                resolveLocalizedAssociatedArtist(
                    mediaId = mediaId,
                    artistIds = artistIds,
                    preBind = callbackPreBind,
                    priority = priority,
                    completesOriginalArtistResolution = true,
                )
            }
        }
        bindingPhase = false
    }

    private fun resolveLocalizedAssociatedArtist(
        mediaId: String,
        artistIds: List<String>,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
        completesOriginalArtistResolution: Boolean = false,
    ) {
        val selection = configuredContentUiLanguage()
        val requestKey = "localized-artist:$selection:$mediaId:" +
            artistIds.joinToString(",")
        if (!associatedArtistResolveRequests.add(requestKey)) return
        var bindingPhase = true
        collectAssociatedArtistAliases(
            artistIds = artistIds,
            request = { artistId, callback ->
                internalCatalogResolver.resolveForContentUiLanguage(
                    mediaId = artistId,
                    lookupIds = listOf(artistId),
                    entityType = AppleInternalCatalogResolver.LocalizedEntityType.ARTIST,
                    selection = selection,
                    priority = priority,
                    onResolved = callback,
                )
            },
        ) { resolved ->
            val callbackPreBind = preBind && bindingPhase
            associatedArtistResolveRequests.remove(requestKey)
            if (configuredContentUiLanguage() != selection) return@collectAssociatedArtistAliases
            if (!shouldAcceptAssociatedArtistResolution(
                    requestedArtistIds = artistIds,
                    currentArtistIds =
                        playbackMetadataAssociatedArtistIds[mediaId].orEmpty(),
                    artistCredit = associatedArtistCredit(mediaId),
                )
            ) {
                enforceAssociatedArtistIsolation(mediaId)
                if (completesOriginalArtistResolution) {
                    publishResolvedAssociatedArtistFallback(mediaId, callbackPreBind)
                }
                return@collectAssociatedArtistAliases
            }
            val language = AppleInternalCatalogResolver
                .languageTagForContentUiLanguage(selection)
                .orEmpty()
            val alias = associatedArtistAlias(artistIds, resolved, language)
            if (completesOriginalArtistResolution) {
                originalArtistResolvedIds.add(mediaId)
            }
            if (alias == null) {
                if (completesOriginalArtistResolution) {
                    publishResolvedAssociatedArtistFallback(mediaId, callbackPreBind)
                }
                return@collectAssociatedArtistAliases
            }
            internalCatalogResolver.rememberLocalizedArtist(
                selection = selection,
                artistKeys = artistIds.map { artistId -> "id:$artistId" },
                localizedArtist = alias.artist,
                language = language,
            )
            applyPlaybackMetadataOverride(
                mediaId = mediaId,
                alias = alias,
                forceInAppRebind = !callbackPreBind,
                rememberLocalizedArtist = false,
                artistOnly = true,
            )
        }
        bindingPhase = false
    }

    private fun publishResolvedAssociatedArtistFallback(
        mediaId: String,
        preBind: Boolean,
    ) {
        val original = originalMetadataOverrides[mediaId]
        if (original != null) {
            applyPlaybackMetadataOverride(
                mediaId = mediaId,
                alias = original,
                forceInAppRebind = !preBind,
                rememberLocalizedArtist = false,
                originalMetadata = true,
                originalMetadataConfirmed = mediaId in confirmedOriginalMetadataIds,
            )
            return
        }
        playbackMetadataOverrides[mediaId]?.let { localized ->
            applyPlaybackMetadataOverride(
                mediaId = mediaId,
                alias = localized,
                forceInAppRebind = !preBind,
                rememberLocalizedArtist = false,
            )
        }
    }

    private fun collectAssociatedArtistAliases(
        artistIds: List<String>,
        request: (String, (AppleInternalCatalogResolver.Alias?) -> Unit) -> Unit,
        onComplete: (Map<String, AppleInternalCatalogResolver.Alias>) -> Unit,
    ) {
        if (artistIds.isEmpty()) {
            onComplete(emptyMap())
            return
        }
        val resolved = ConcurrentHashMap<String, AppleInternalCatalogResolver.Alias>()
        val remaining = AtomicInteger(artistIds.size)
        artistIds.forEach { artistId ->
            request(artistId) { alias ->
                if (alias != null) resolved[artistId] = alias
                if (remaining.decrementAndGet() == 0) onComplete(resolved)
            }
        }
    }

    private fun ensureInAppMetadataOverride(
        mediaId: String,
        preBind: Boolean = false,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.ACTIVE_PAGE,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
    ) {
        ensureInAppMetadataOverrides(
            mediaIds = listOf(mediaId),
            preBind = preBind,
            priority = priority,
            originalResolutionMode = originalResolutionMode,
        )
    }

    private fun ensureInAppMetadataOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean = false,
        originalResolutionLimit: Int = Int.MAX_VALUE,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
        originalResolutionMode: InAppOriginalResolutionMode =
            InAppOriginalResolutionMode.AFTER_LOCALIZED,
    ) {
        val normalizedIds = mediaIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .filterNot(nonCatalogContentItemIds::contains)
            .distinct()
            .toList()
        if (normalizedIds.isEmpty()) return
        internalCatalogResolver.promotePendingRequests(
            mediaIds = normalizedIds + normalizedIds.flatMap { mediaId ->
                playbackMetadataAssociatedArtistIds[mediaId].orEmpty()
            },
            priority = priority,
        )
        if (!isRestoreCjkOriginalMetadataEnabled()) {
            ensureLocalizedInAppMetadataOverrides(normalizedIds, preBind, priority)
            return
        }

        val originalResolutionIds = normalizedIds.take(originalResolutionLimit.coerceAtLeast(0))
        originalResolutionIds.forEach { mediaId ->
            if (
                !playbackMetadataAssociatedArtistIds[mediaId].isNullOrEmpty() &&
                mediaId !in originalArtistResolvedIds
            ) {
                ensureAssociatedArtistOverride(mediaId, preBind, priority)
            }
        }
        val beforeLocalizedPlan = inAppOriginalResolutionPlan(
            mediaIds = originalResolutionIds,
            awaitingLocalizedIds = emptySet(),
            mode = originalResolutionMode,
        )
        if (BuildConfig.DEBUG &&
            beforeLocalizedPlan.beforeLocalized.isNotEmpty()
        ) {
            beforeLocalizedPlan.beforeLocalized.forEach { mediaId ->
                val account = playbackMetadataAccountValues[mediaId]
                ProviderLogger.info(
                    "Apple Music 元数据链路: " +
                        "seq=${metadataTraceSequence.incrementAndGet()}, " +
                        "event=original_song_visible_dispatch, contentId=$mediaId, " +
                        "title=${account?.title}, artist=${account?.artist}, " +
                        "priority=$priority, " +
                        "resolved=${mediaId in originalMetadataResolvedIds}, " +
                        "hasAlias=${originalMetadataOverrides.containsKey(mediaId)}"
                )
            }
        }
        ensureOriginalInAppMetadataOverrides(
            beforeLocalizedPlan.beforeLocalized,
            preBind,
            priority,
        )
        val awaitingLocalizedIds = if (beforeLocalizedPlan.resolveLocalizedImmediately) {
            ensureLocalizedInAppMetadataOverrides(
                normalizedIds,
                preBind,
                priority,
            )
        } else {
            emptySet()
        }
        val afterLocalizedPlan = inAppOriginalResolutionPlan(
            mediaIds = originalResolutionIds,
            awaitingLocalizedIds = awaitingLocalizedIds,
            mode = originalResolutionMode,
        )
        ensureOriginalInAppMetadataOverrides(
            afterLocalizedPlan.afterLocalized,
            preBind,
            priority,
        )
        val localizedFallbackIds = normalizedIds.filter { mediaId ->
            mediaId in originalMetadataResolvedIds &&
                !originalMetadataOverrides.containsKey(mediaId)
        }
        ensureLocalizedInAppMetadataOverrides(localizedFallbackIds, preBind, priority)
    }

    private fun ensureOriginalInAppMetadataOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
    ) {
        mediaIds.forEach { mediaId ->
            if ((mediaId in originalMetadataResolvedIds &&
                !shouldRetryOriginalMetadataCacheProbe(mediaId)) ||
                originalMetadataOverrides.containsKey(mediaId)
            ) return@forEach
            val account = playbackMetadataAccountValues[mediaId]
            if (account?.title.isNullOrBlank()) {
                originalMetadataPendingIds.add(mediaId)
                return@forEach
            }
            val entityType = playbackMetadataEntityTypes[mediaId]
                ?: AppleInternalCatalogResolver.LocalizedEntityType.SONG
            if (entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG) {
                resolveOriginalSongForInApp(mediaId, account, preBind, priority)
            } else {
                val language = originalLanguageFor(mediaId)
                if (language == null) {
                    if (!originalMetadataPendingIds.add(mediaId)) return@forEach
                    resolveCachedOriginalEntityForInApp(
                        mediaId = mediaId,
                        entityType = entityType,
                        preBind = preBind,
                        priority = priority,
                    )
                } else {
                    resolveOriginalEntityForInApp(
                        mediaId = mediaId,
                        entityType = entityType,
                        language = language,
                        preBind = preBind,
                        priority = priority,
                    )
                }
            }
        }
    }

    private fun resolveCachedOriginalEntityForInApp(
        mediaId: String,
        entityType: AppleInternalCatalogResolver.LocalizedEntityType,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
    ) {
        val requestKey = "original-cache:$entityType:$mediaId"
        if (!originalMetadataResolveRequests.add(requestKey)) return
        var bindingPhase = true
        internalCatalogResolver.resolveCachedOriginalEntity(
            mediaId = mediaId,
            entityType = entityType,
            lookupIds = playbackMetadataLookupIds[mediaId].orEmpty(),
            onResolved = { alias ->
                originalMetadataResolveRequests.remove(requestKey)
                if (!isRestoreCjkOriginalMetadataEnabled()) return@resolveCachedOriginalEntity
                if (alias == null) {
                    // 这是一次暂时的缓存未命中，不代表原名永久不存在；资料库页稍后可能写入同一 ID。
                    originalMetadataCacheMissUptimeMillis[mediaId] = SystemClock.uptimeMillis()
                    originalMetadataResolvedIds.add(mediaId)
                    originalMetadataPendingIds.remove(mediaId)
                    if (BuildConfig.DEBUG) {
                        logMetadataIdentity(
                            event = "original_cache_resolve_finished",
                            details = "contentId=$mediaId, entityType=$entityType, hit=false, " +
                                "confirmed=false, preBind=$preBind",
                        )
                    }
                    ensureLocalizedInAppMetadataOverrides(
                        mediaIds = listOf(mediaId),
                        preBind = false,
                        priority = priority,
                    )
                    return@resolveCachedOriginalEntity
                }
                originalMetadataPendingIds.remove(mediaId)
                alias.language.takeIf(String::isNotBlank)?.let { language ->
                    rememberOriginalLanguageForArtist(mediaId, language)
                }
                applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    forceInAppRebind = !preBind || !bindingPhase,
                    rememberLocalizedArtist = false,
                    originalMetadata = true,
                    originalMetadataConfirmed = true,
                )
                if (BuildConfig.DEBUG) {
                    logMetadataIdentity(
                        event = "original_cache_resolve_finished",
                        details = "contentId=$mediaId, entityType=$entityType, hit=true, " +
                            "confirmed=true, preBind=$preBind, " +
                            "resolved=${alias.title}/${alias.artist}/${alias.album}",
                    )
                }
            },
        )
        bindingPhase = false
    }

    private fun resolveOriginalSongForInApp(
        mediaId: String,
        account: AccountMetadata,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
    ) {
        val requestKey = "original:SONG:$mediaId"
        if (!originalMetadataResolveRequests.add(requestKey)) return
        val metadata = MediaMetadataCache.Metadata(
            id = mediaId,
            title = account.title,
            artist = account.artist,
            genre = null,
            duration = 0L,
            queueId = 0L,
        )
        ProviderLogger.info(
            "Apple App 原地区歌曲解析开始: id=$mediaId, " +
                "title=${account.title}, artist=${account.artist}, preBind=$preBind"
        )
        var bindingPhase = true
        internalCatalogResolver.resolveOriginalMetadata(
            metadata = metadata,
            priority = priority,
            onCandidate = candidate@{ candidate ->
                if (!isRestoreCjkOriginalMetadataEnabled()) return@candidate
                val safeCandidate = validatedOriginalSongAlias(
                    alias = candidate,
                    localizedTitle = account.title,
                    localizedArtist = account.artist,
                ) ?: return@candidate
                applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = safeCandidate,
                    forceInAppRebind = !preBind || !bindingPhase,
                    rememberLocalizedArtist = false,
                )
            },
            onResolved = { resolution ->
                if (!isRestoreCjkOriginalMetadataEnabled()) {
                    originalMetadataResolveRequests.remove(requestKey)
                    return@resolveOriginalMetadata
                }
                mergePlaybackAssociatedArtistIds(mediaId, resolution.artistIds)
                originalArtistLanguageFromSongResolution(
                    resolution = resolution,
                    localizedArtist = account.artist,
                )?.let { language ->
                    rememberOriginalLanguageForArtist(mediaId, language)
                }
                fun finishResolution(alias: AppleInternalCatalogResolver.Alias?) {
                    originalMetadataResolveRequests.remove(requestKey)
                    if (!isRestoreCjkOriginalMetadataEnabled()) return
                    originalMetadataResolvedIds.add(mediaId)
                    originalMetadataPendingIds.remove(mediaId)
                    val safeAlias = validatedOriginalSongAlias(
                        alias = alias,
                        localizedTitle = account.title,
                        localizedArtist = account.artist,
                    )
                    if (alias != null && safeAlias == null) {
                        internalCatalogResolver.invalidateOriginalEntity(
                            mediaId = mediaId,
                            entityType =
                            AppleInternalCatalogResolver.LocalizedEntityType.SONG,
                        )
                        ProviderLogger.info(
                            "Apple App 原地区合作歌曲别名拒绝: id=$mediaId, " +
                                "account=${account.title}/${account.artist}, " +
                                "candidate=${alias.title}/${alias.artist}"
                        )
                    }
                    safeAlias?.language?.takeIf {
                        shouldShareOriginalSongLanguage(
                            localizedTitle = account.title,
                            localizedArtist = account.artist,
                            alias = safeAlias,
                        )
                    }?.let { language ->
                        rememberOriginalLanguageForArtist(mediaId, language)
                    }
                    if (safeAlias != null) {
                        applyPlaybackMetadataOverride(
                            mediaId = mediaId,
                            alias = safeAlias,
                            forceInAppRebind = !preBind || !bindingPhase,
                            rememberLocalizedArtist = false,
                            originalMetadata = true,
                            originalMetadataConfirmed = true,
                        )
                    } else {
                        ensureLocalizedInAppMetadataOverrides(
                            mediaIds = listOf(mediaId),
                            preBind = false,
                            priority = priority,
                        )
                    }
                    if (!playbackMetadataAssociatedArtistIds[mediaId].isNullOrEmpty()) {
                        ensureAssociatedArtistOverride(
                            mediaId = mediaId,
                            preBind = false,
                            priority = priority,
                        )
                    }
                }

                val alias = confirmedOriginalSongAlias(resolution)
                val retryLanguage = originalSongRetryLanguage(resolution)
                if (retryLanguage == null) {
                    finishResolution(alias)
                } else {
                    ProviderLogger.info(
                        "Apple App 原地区歌曲精确重试: id=$mediaId, language=$retryLanguage"
                    )
                    internalCatalogResolver.resolveOriginalEntityForLanguage(
                        mediaId = mediaId,
                        lookupIds = playbackMetadataLookupIds[mediaId].orEmpty()
                            .ifEmpty { setOf(mediaId) },
                        entityType = AppleInternalCatalogResolver.LocalizedEntityType.SONG,
                        language = retryLanguage,
                        priority = priority,
                        onResolved = ::finishResolution,
                    )
                }
            },
        )
        bindingPhase = false
    }

    internal fun confirmedOriginalSongAlias(
        resolution: AppleInternalCatalogResolver.OriginalResolution,
    ): AppleInternalCatalogResolver.Alias? = resolution.alias

    internal fun validatedOriginalSongAlias(
        alias: AppleInternalCatalogResolver.Alias?,
        localizedTitle: String?,
        localizedArtist: String?,
    ): AppleInternalCatalogResolver.Alias? = alias?.takeIf {
        AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
            alias = it,
            localizedTitle = localizedTitle.orEmpty(),
            localizedArtist = localizedArtist.orEmpty(),
        )
    }

    internal fun originalSongRetryLanguage(
        resolution: AppleInternalCatalogResolver.OriginalResolution,
    ): String? = resolution.language?.takeIf {
        resolution.alias == null && resolution.originKnown
    }

    internal fun originalArtistLanguageFromSongResolution(
        resolution: AppleInternalCatalogResolver.OriginalResolution,
        localizedArtist: String?,
    ): String? {
        if (!resolution.originKnown ||
            !shouldUseAssociatedArtistEntities(
                artistIds = resolution.artistIds,
                artistCredit = localizedArtist,
            )
        ) {
            return null
        }
        return resolution.language?.let(
            AppleInternalCatalogResolver::supportedOriginalLanguageOrNull
        )
    }

    private fun resolveOriginalEntityForInApp(
        mediaId: String,
        entityType: AppleInternalCatalogResolver.LocalizedEntityType,
        language: String,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
    ) {
        val requestKey = "original:$entityType:$language:$mediaId"
        if (!originalMetadataResolveRequests.add(requestKey)) return
        var bindingPhase = true
        internalCatalogResolver.resolveOriginalEntityForLanguage(
            mediaId = mediaId,
            lookupIds = playbackMetadataLookupIds[mediaId].orEmpty(),
            entityType = entityType,
            language = language,
            priority = priority,
        ) { alias ->
            originalMetadataResolveRequests.remove(requestKey)
            if (!isRestoreCjkOriginalMetadataEnabled()) return@resolveOriginalEntityForLanguage
            originalMetadataResolvedIds.add(mediaId)
            originalMetadataPendingIds.remove(mediaId)
            if (alias != null) {
                applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    forceInAppRebind = !preBind || !bindingPhase,
                    rememberLocalizedArtist = false,
                    originalMetadata = true,
                    originalMetadataConfirmed = true,
                )
            } else {
                ensureLocalizedInAppMetadataOverrides(
                    mediaIds = listOf(mediaId),
                    preBind = false,
                    priority = priority,
                )
            }
        }
        bindingPhase = false
    }

    private fun rememberOriginalLanguageForArtist(mediaId: String, language: String) {
        val artistKeys = originalArtistKeysForMedia(mediaId)
        val regionKeys = persistentOriginalArtistKeys(artistKeys)
        if (regionKeys.isEmpty()) return
        val canonicalLanguage = AppleInternalCatalogResolver
            .supportedOriginalLanguageOrNull(language)
        if (canonicalLanguage == null) {
            ProviderLogger.info(
                "Apple 艺人原地区语言忽略: id=$mediaId, language=$language, " +
                    "reason=unsupported_language"
            )
            return
        }
        val changed = regionKeys.any { key ->
            originalLanguageByArtistKey[key] != canonicalLanguage
        }
        if (!changed) return
        regionKeys.forEach { key -> originalLanguageByArtistKey[key] = canonicalLanguage }
        internalCatalogResolver.rememberOriginalArtistRegion(
            regionKeys,
            canonicalLanguage,
        )
        val readyIds = originalMetadataPendingIds.filter { pendingId ->
            playbackMetadataArtistKeys[pendingId].orEmpty().any { key ->
                key in regionKeys
            }
        }
        val associatedMediaIds = regionKeys.asSequence()
            .flatMap { key -> associatedMediaIdsByArtistKey[key].orEmpty().asSequence() }
            .distinct()
            .toList()
        if (readyIds.isNotEmpty() || associatedMediaIds.isNotEmpty()) {
            mainHandler.post {
                readyIds.forEach(originalMetadataPendingIds::remove)
                if (readyIds.isNotEmpty()) {
                    ensureOriginalInAppMetadataOverrides(readyIds, preBind = false)
                }
                associatedMediaIds.forEach { candidateId ->
                    ensureAssociatedArtistOverride(candidateId, preBind = false)
                }
            }
        }
    }

    private fun shouldShareOriginalSongLanguage(
        localizedTitle: String?,
        localizedArtist: String?,
        alias: AppleInternalCatalogResolver.Alias?,
    ): Boolean {
        alias ?: return false
        val artist = localizedArtist.orEmpty()
        if (AppleInternalCatalogResolver.isCollaborationArtistName(artist)) return false
        return AppleInternalCatalogResolver.isConfidentOriginalSongAlias(
            alias = alias,
            localizedTitle = localizedTitle.orEmpty(),
            localizedArtist = artist,
        )
    }

    private fun originalLanguageFor(mediaId: String): String? {
        val artistKeys = persistentOriginalArtistKeys(originalArtistKeysForMedia(mediaId))
        artistKeys.forEach { key ->
            val cached = originalLanguageByArtistKey[key] ?: return@forEach
            val supported = AppleInternalCatalogResolver.supportedOriginalLanguageOrNull(cached)
            if (supported != null) return supported
            originalLanguageByArtistKey.remove(key, cached)
        }
        val restored = internalCatalogResolver.cachedOriginalArtistRegion(
            persistentOriginalArtistKeys(artistKeys)
        ) ?: return null
        artistKeys.forEach { key -> originalLanguageByArtistKey[key] = restored }
        return restored
    }

    private fun originalArtistKeysForMedia(mediaId: String): Set<String> = buildSet {
        addAll(playbackMetadataArtistKeys[mediaId].orEmpty())
        playbackMetadataAssociatedArtistIds[mediaId].orEmpty().forEach { artistId ->
            add("id:$artistId")
        }
    }

    private fun persistentOriginalArtistKeys(keys: Collection<String>): Set<String> =
        stableArtistCacheKeys(keys)

    internal fun stableArtistCacheKeys(keys: Collection<String>): Set<String> {
        val ids = keys.filterTo(linkedSetOf()) { it.startsWith("id:") }
        if (ids.isNotEmpty()) return ids
        return keys.filterTo(linkedSetOf()) { it.startsWith("name:") }
    }

    internal fun localizedArtistCacheKeys(keys: Collection<String>): Set<String> =
        keys.filterTo(linkedSetOf()) { key ->
            key.startsWith("id:") && key.removePrefix("id:").let { id ->
                id.isNotEmpty() && id.all(Char::isDigit)
            }
        }

    private fun ensureLocalizedInAppMetadataOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean = false,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.BACKGROUND,
    ): Set<String> {
        val selection = configuredContentUiLanguage()
        if (!shouldOverrideAccountLanguage(selection)) {
            return emptySet()
        }
        val awaitingIds = linkedSetOf<String>()
        val requests = mediaIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .distinct()
            .filterNot(playbackMetadataOverrides::containsKey)
            .filterNot(nonCatalogContentItemIds::contains)
            .mapNotNull { mediaId ->
                val lookupIds = playbackMetadataLookupIds[mediaId].orEmpty()
                    .ifEmpty { setOf(mediaId) }
                    .sorted()
                val entityType = playbackMetadataEntityTypes[mediaId]
                    ?: AppleInternalCatalogResolver.LocalizedEntityType.SONG
                val requestKey =
                    "$selection:$entityType:$mediaId:${lookupIds.joinToString(",")}".trim()
                if (requestKey in inAppMetadataResolveMisses) return@mapNotNull null
                awaitingIds += mediaId
                if (!inAppMetadataResolveRequests.add(requestKey)) return@mapNotNull null
                PendingMetadataLookup(
                    requestKey = requestKey,
                    lookup = AppleInternalCatalogResolver.LocalizedLookup(
                        mediaId = mediaId,
                        lookupIds = lookupIds,
                        entityType = entityType,
                    ),
                )
            }
            .toList()
        if (requests.isEmpty()) return awaitingIds
        val byMediaId = requests.associateBy { it.lookup.mediaId }
        ProviderLogger.info(
            "Apple 地区元数据批量解析开始: count=${requests.size}, " +
                "selection=$selection, preBind=$preBind, priority=$priority"
        )
        var bindingPhase = true
        internalCatalogResolver.resolveManyForContentUiLanguage(
            lookups = requests.map(PendingMetadataLookup::lookup),
            selection = selection,
            priority = priority,
        ) { mediaId, alias ->
            val request = byMediaId[mediaId] ?: return@resolveManyForContentUiLanguage
            val lookupIds = request.lookup.lookupIds
            val entityType = request.lookup.entityType
            inAppMetadataResolveRequests.remove(request.requestKey)
            logMetadataIdentity(
                event = "catalog_resolve_finished",
                details = "requestedId=$mediaId, lookupIds=$lookupIds, " +
                    "entityType=$entityType, selection=$selection, hit=${alias != null}, " +
                    "resolved=${alias?.title}/${alias?.artist}/${alias?.album}",
            )
            if (
                alias != null &&
                configuredContentUiLanguage() == selection &&
                shouldOverrideAccountLanguage(selection)
            ) {
                applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    forceInAppRebind = !preBind || !bindingPhase,
                )
            } else if (alias == null) {
                inAppMetadataResolveMisses.add(request.requestKey)
            }
            if (isRestoreCjkOriginalMetadataEnabled()) {
                ensureOriginalInAppMetadataOverrides(
                    mediaIds = listOf(mediaId),
                    preBind = false,
                    priority = priority,
                )
            }
        }
        bindingPhase = false
        return awaitingIds
    }

    private fun applyAliasToInAppMetadata(
        metadata: Any,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        alias.title.takeIf(String::isNotBlank)?.let { value ->
            val current = runCatching { AppleReflection.field(metadata, "a") }
                .getOrNull()?.toString()
            if (current != value) AppleReflection.setField(metadata, "a", value)
        }
        alias.artist.takeIf(String::isNotBlank)?.let { value ->
            val current = runCatching { AppleReflection.field(metadata, "b") }
                .getOrNull()?.toString()
            if (current != value) AppleReflection.setField(metadata, "b", value)
        }
    }

    private fun applyAliasToInAppMetadataRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceRebind: Boolean = true,
        notifyModelChange: Boolean = true,
    ) {
        var metadataApplied = 0
        val refs = inAppMetadataRefs[mediaId]
        refs?.forEach { ref ->
            val metadata = ref.metadata.get()
            if (metadata == null) {
                refs.remove(ref)
            } else if (synchronized(inAppMetadataIds) {
                    inAppMetadataIds[metadata]
                } != mediaId
            ) {
                refs.remove(ref)
            } else {
                applyAliasToInAppMetadata(metadata, alias)
                metadataApplied += 1
            }
        }
        var playbackItemApplied = 0
        val playbackItemRefs = inAppPlaybackItemRefs[mediaId]
        playbackItemRefs?.forEach { ref ->
            val playbackItem = ref.playbackItem.get()
            if (playbackItem == null) {
                playbackItemRefs.remove(ref)
            } else if (
                !shouldApplyInAppPlaybackItemAlias(
                    expectedMediaId = mediaId,
                    currentMediaId = synchronized(inAppPlaybackItemIds) {
                        inAppPlaybackItemIds[playbackItem]
                    },
                )
            ) {
                playbackItemRefs.remove(ref)
            } else {
                applyAliasToInAppPlaybackItem(
                    playbackItem,
                    alias,
                    notifyChange = notifyModelChange,
                )
                playbackItemApplied += 1
            }
        }
        var containerItemApplied = 0
        val containerItemRefs = inAppContainerItemRefs[mediaId]
        containerItemRefs?.forEach { ref ->
            val containerItem = ref.containerItem.get()
            if (containerItem == null) {
                containerItemRefs.remove(ref)
            } else if (inAppContainerItemIds[containerItem] != mediaId) {
                containerItemRefs.remove(ref)
            } else {
                applyAliasToInAppContainerItem(
                    containerItem,
                    ref.kind,
                    alias,
                    notifyChange = notifyModelChange,
                )
                containerItemApplied += 1
            }
        }
        val libraryEntitiesApplied = applyAliasToInAppLibraryEntityRefs(mediaId, alias)
        val playlistRowsApplied =
            if (forceRebind) refreshInAppPlaylistRowRefs(mediaId, alias) else 0
        val libraryControllers =
            if (forceRebind) {
                refreshInAppLibraryControllerRefs(
                    mediaId = mediaId,
                    alias = alias,
                    hasDirectPlaylistRow = playlistRowsApplied > 0,
                )
            } else {
                0
            }
        val libraryComposeStates =
            if (forceRebind) refreshInAppLibraryComposeStateRefs(mediaId, alias) else 0
        val dataBindingTargets =
            if (forceRebind) refreshInAppDataBindingRefs(mediaId, alias) else 0
        val listenNowDataBindingTargets =
            if (forceRebind) {
                refreshInAppListenNowDataBindingRefs(mediaId, alias)
            } else {
                0
            }
        val queueAdapterTargets =
            if (forceRebind) refreshInAppQueueAdapters(mediaId) else 0
        val genericRecyclerTargets =
            if (forceRebind) refreshGenericRecyclerItemRefs(mediaId) else 0
        if (
            metadataApplied + playbackItemApplied + containerItemApplied +
                libraryEntitiesApplied + playlistRowsApplied + libraryControllers +
                libraryComposeStates + dataBindingTargets + listenNowDataBindingTargets +
                queueAdapterTargets +
                genericRecyclerTargets > 0
        ) {
            ProviderLogger.info(
                "Apple Music App 内元数据已覆盖: id=$mediaId, " +
                    "title=${alias.title}, artist=${alias.artist}, album=${alias.album}, " +
                    "metadata=$metadataApplied, items=$playbackItemApplied, " +
                    "containers=$containerItemApplied, libraryEntities=$libraryEntitiesApplied, " +
                    "playlistRows=$playlistRowsApplied, " +
                    "libraryControllers=$libraryControllers, " +
                    "libraryComposeStates=$libraryComposeStates, " +
                    "dataBindings=$dataBindingTargets, " +
                    "listenNowDataBindings=$listenNowDataBindingTargets, " +
                    "queueAdapters=$queueAdapterTargets, " +
                    "genericRecyclerItems=$genericRecyclerTargets"
            )
        }
    }

    private fun hasLiveInAppModelTarget(mediaId: String): Boolean =
        inAppMetadataRefs[mediaId]?.any { it.metadata.get() != null } == true ||
            inAppPlaybackItemRefs[mediaId]?.any { it.playbackItem.get() != null } == true ||
            inAppContainerItemRefs[mediaId]?.any { it.containerItem.get() != null } == true ||
            inAppLibraryEntityRefs[mediaId]?.any { it.entity.get() != null } == true ||
            inAppLibraryControllerRefs[mediaId]?.any { it.get() != null } == true ||
            inAppLibraryComposeStateRefs[mediaId]?.any { it.get() != null } == true ||
            inAppDataBindingRefs[mediaId]?.any { it.get() != null } == true ||
            inAppGenericRecyclerItemRefs[mediaId]?.any {
                it.adapter.get() != null && it.root.get() != null
            } == true ||
            (
                queueInAppMetadataRefresh?.mediaIds?.contains(mediaId) == true ||
                    historyInAppMetadataRefresh?.mediaIds?.contains(mediaId) == true
                ) && inAppQueueAdapterRefs.any { it.get() != null } ||
            inAppActionSheetBindingRefs[mediaId]?.any { it.get() != null } == true

    private fun applyAliasToInAppLibraryEntityRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): Int {
        var applied = 0
        val refs = inAppLibraryEntityRefs[mediaId] ?: return 0
        refs.forEach { ref ->
            val entity = ref.entity.get()
            if (entity == null) {
                refs.remove(ref)
            } else if (inAppLibraryEntityIds[entity] != mediaId) {
                refs.remove(ref)
            } else if (applyAliasToInAppLibraryEntity(entity, ref.kind, alias)) {
                applied += 1
            }
        }
        return applied
    }

    private fun applyAliasToInAppLibraryEntity(
        entity: Any,
        kind: InAppLibraryEntityKind,
        alias: AppleInternalCatalogResolver.Alias,
    ): Boolean {
        val attributes = mediaApiEntityAttributes(entity) ?: return false
        val name = when (kind) {
            InAppLibraryEntityKind.ALBUM -> alias.album.ifBlank { alias.title }
            InAppLibraryEntityKind.SONG -> alias.title
            InAppLibraryEntityKind.ARTIST -> alias.artist.ifBlank { alias.title }
        }
        var changed = false
        name.takeIf(String::isNotBlank)?.let { value ->
            runCatching { AppleReflection.call(attributes, "setName", value) }
                .onSuccess { changed = true }
        }
        alias.artist.takeIf(String::isNotBlank)?.let { value ->
            runCatching { AppleReflection.call(attributes, "setArtistName", value) }
                .onSuccess { changed = true }
        }
        if (kind == InAppLibraryEntityKind.SONG) {
            alias.album.takeIf(String::isNotBlank)?.let { value ->
                runCatching { AppleReflection.call(attributes, "setAlbumName", value) }
                    .onSuccess { changed = true }
            }
        }
        return changed
    }

    private fun refreshInAppLibraryControllerRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias? = null,
        hasDirectPlaylistRow: Boolean = false,
    ): Int {
        if (!isRefreshableInAppMediaId(mediaId)) return 0
        val refs = inAppLibraryControllerRefs[mediaId] ?: return 0
        val targets = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var collectedRefs = 0
        var staleRefs = 0
        var alreadyAppliedRefs = 0
        refs.forEach { ref ->
            val controller = ref.get()
            if (controller == null) {
                refs.remove(ref)
                staleRefs += 1
            } else {
                val expectedAppliedAlias = alias?.let { resolvedAlias ->
                    libraryControllerAppliedAlias(controller, mediaId, resolvedAlias)
                }
                if (
                    expectedAppliedAlias == null ||
                    synchronized(inAppLibraryControllerAppliedAliases) {
                        inAppLibraryControllerAppliedAliases[controller]?.get(mediaId)
                    } != expectedAppliedAlias
                ) {
                    collectedRefs += 1
                    targets.add(controller)
                } else {
                    alreadyAppliedRefs += 1
                }
            }
        }
        if (BuildConfig.DEBUG) {
            logMetadataIdentity(
                event = "library_epoxy_refresh_decision",
                details = "contentId=$mediaId, refs=${refs.size}, collected=$collectedRefs, " +
                    "stale=$staleRefs, alreadyApplied=$alreadyAppliedRefs, " +
                    "targets=${targets.size}, alias=${alias?.title}/${alias?.artist}/${alias?.album}",
            )
        }
        var scheduledTargets = 0
        targets.forEach { controller ->
            val buildStrategy = inAppLibraryControllerBuildStrategy(
                hasAlbumBuildData = albumPageBuildData[controller] != null,
                hasArtistBuildData = artistPageBuildData[controller] != null,
                isPlaylistPageController = isPlaylistPageController(controller),
            )
            if (shouldUsePlaylistDirectRowRefresh(buildStrategy, hasDirectPlaylistRow)) {
                alias?.let { directAlias ->
                    synchronized(inAppLibraryControllerAppliedAliases) {
                        inAppLibraryControllerAppliedAliases.getOrPut(controller) {
                            mutableMapOf()
                        }[mediaId] = AppliedMetadataAlias(mediaId, directAlias)
                    }
                }
                if (BuildConfig.DEBUG) {
                    logMetadataIdentity(
                        event = "library_epoxy_refresh_skipped",
                        details = "contentId=$mediaId, controller=${controller.javaClass.name}, " +
                            "strategy=$buildStrategy, reason=playlist_direct_row",
                    )
                }
                return@forEach
            }
            scheduledTargets += 1
            val dispatch = synchronized(inAppLibraryControllerRefreshStates) {
                val state = inAppLibraryControllerRefreshStates.getOrPut(controller) {
                    InAppLibraryControllerRefreshState()
                }
                state.enqueue(
                    mediaId = mediaId,
                    strategy = buildStrategy,
                    nowUptimeMillis = SystemClock.uptimeMillis(),
                    albumDebounceMillis = ALBUM_CONTROLLER_REFRESH_DEBOUNCE_MS,
                    playlistIntervalMillis = PLAYLIST_CONTROLLER_REFRESH_INTERVAL_MS,
                )
            }
            if (dispatch == null) {
                if (BuildConfig.DEBUG) {
                    logMetadataIdentity(
                        event = "library_epoxy_refresh_coalesced",
                        details = "contentId=$mediaId, controller=${controller.javaClass.name}, " +
                            "strategy=$buildStrategy, reason=rebuild_scheduled",
                    )
                }
                return@forEach
            }
            scheduleInAppLibraryControllerRefresh(controller, dispatch)
        }
        return scheduledTargets
    }

    private fun scheduleInAppLibraryControllerRefresh(
        controller: Any,
        dispatch: InAppLibraryControllerRefreshDispatch,
    ) {
        val refresh = Runnable { drainInAppLibraryControllerRefresh(controller) }
        if (dispatch.delayMillis == 0L) {
            mainHandler.post(refresh)
        } else {
            mainHandler.postDelayed(refresh, dispatch.delayMillis)
        }
    }

    private fun drainInAppLibraryControllerRefresh(controller: Any) {
        val pendingMediaIds = synchronized(inAppLibraryControllerRefreshStates) {
            val state = inAppLibraryControllerRefreshStates[controller]
                ?: return@synchronized emptyList()
            state.takePendingMediaIds()
        }
        val buildStrategy = inAppLibraryControllerBuildStrategy(
            hasAlbumBuildData = albumPageBuildData[controller] != null,
            hasArtistBuildData = artistPageBuildData[controller] != null,
            isPlaylistPageController = isPlaylistPageController(controller),
        )
        val pendingMediaIdSet = pendingMediaIds.toSet()
        val candidateMediaIds = if (
            buildStrategy == InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA
        ) {
            pendingMediaIds + albumPageBuildData[controller]?.trackMediaIds.orEmpty()
        } else {
            pendingMediaIds
        }
        val activeMediaIds = candidateMediaIds.distinct()
            .filter(::isRefreshableInAppMediaId)
            .filter { mediaId ->
                val alias = effectiveInAppMetadataOverride(mediaId)
                if (alias == null) {
                    mediaId in pendingMediaIdSet
                } else {
                    val appliedAlias = libraryControllerAppliedAlias(
                        controller = controller,
                        mediaId = mediaId,
                        alias = alias,
                    )
                    synchronized(inAppLibraryControllerAppliedAliases) {
                        inAppLibraryControllerAppliedAliases[controller]?.get(mediaId)
                    } != appliedAlias
                }
            }
        if (activeMediaIds.isNotEmpty()) {
            val traceMediaId = activeMediaIds.first()
            if (BuildConfig.DEBUG) {
                logMetadataIdentity(
                    event = "library_epoxy_refresh_invoke",
                    details = "contentIds=$activeMediaIds, " +
                        "controller=${controller.javaClass.name}, strategy=$buildStrategy",
                )
                debugVisibleRecyclerViews("before_epoxy_refresh:$traceMediaId")
                debugLibraryModelRefreshMediaId.set(traceMediaId)
                }
            runCatching {
                requestInAppLibraryControllerBuild(controller, buildStrategy)
            }
                .onSuccess {
                    val rebuiltMediaIds = if (
                        buildStrategy == InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA
                    ) {
                        (activeMediaIds +
                            albumPageBuildData[controller]?.trackMediaIds.orEmpty()).distinct()
                    } else {
                        activeMediaIds
                    }
                    synchronized(inAppLibraryControllerAppliedAliases) {
                        val appliedAliases =
                            inAppLibraryControllerAppliedAliases.getOrPut(controller) {
                                mutableMapOf()
                            }
                        rebuiltMediaIds.forEach { mediaId ->
                            val alias = effectiveInAppMetadataOverride(mediaId)
                            if (alias == null) {
                                appliedAliases.remove(mediaId)
                            } else {
                                appliedAliases[mediaId] = libraryControllerAppliedAlias(
                                    controller = controller,
                                    mediaId = mediaId,
                                    alias = alias,
                                )
                            }
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        ProviderLogger.info(
                            "Apple Music 元数据链路: " +
                                "seq=${metadataTraceSequence.incrementAndGet()}, " +
                                "event=library_epoxy_rebuild, contentIds=$activeMediaIds, " +
                                "controller=${controller.javaClass.name}"
                        )
                        mainHandler.post {
                            debugVisibleRecyclerViews(
                                "after_epoxy_refresh:$traceMediaId"
                            )
                        }
                    }
                }
                .onFailure {
                    ProviderLogger.error(
                        "Apple Music 资料库 Epoxy 合并刷新失败: " +
                            "ids=$activeMediaIds, controller=${controller.javaClass.name}",
                        it,
                    )
                }
            synchronized(inAppLibraryControllerRefreshStates) {
                inAppLibraryControllerRefreshStates[controller]?.recordBuildAttempt(
                    SystemClock.uptimeMillis()
                )
            }
            if (BuildConfig.DEBUG) debugLibraryModelRefreshMediaId.remove()
        }
        val nextDispatch = synchronized(inAppLibraryControllerRefreshStates) {
            val state = inAppLibraryControllerRefreshStates[controller]
                ?: return@synchronized null
            val dispatch = state.finishDrain(
                strategy = buildStrategy,
                nowUptimeMillis = SystemClock.uptimeMillis(),
                albumDebounceMillis = ALBUM_CONTROLLER_REFRESH_DEBOUNCE_MS,
                playlistIntervalMillis = PLAYLIST_CONTROLLER_REFRESH_INTERVAL_MS,
            )
            if (!state.scheduled && buildStrategy !=
                InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD
            ) {
                inAppLibraryControllerRefreshStates.remove(controller)
            }
            dispatch
        }
        if (nextDispatch != null) {
            scheduleInAppLibraryControllerRefresh(controller, nextDispatch)
        }
    }

    private fun libraryControllerAppliedAlias(
        controller: Any,
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ): AppliedMetadataAlias {
        val appliedAlias = AppliedMetadataAlias(mediaId, alias)
        val albumData = albumPageBuildData[controller] ?: return appliedAlias
        if (mediaId !in albumData.trackMediaIds) return appliedAlias
        val albumMediaId = albumData.mediaId ?: return appliedAlias
        val albumArtist = effectiveInAppMetadataOverride(albumMediaId)
            ?.artist
            ?.takeIf(String::isNotBlank)
            ?: playbackMetadataAccountValues[albumMediaId]?.artist
        return albumPageControllerAppliedAlias(
            appliedAlias = appliedAlias,
            songArtistId = sharedAssociatedArtistId(mediaId),
            albumArtistId = sharedAssociatedArtistId(albumMediaId),
            albumArtist = albumArtist,
        )
    }

    private fun requestInAppLibraryControllerBuild(
        controller: Any,
        strategy: InAppLibraryControllerBuildStrategy,
    ) {
        when (strategy) {
            InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA -> {
                val albumData = checkNotNull(albumPageBuildData[controller])
                AppleReflection.call(
                    controller,
                    "setData",
                    albumData.album,
                    albumData.selectedItemIds,
                )
            }

            InAppLibraryControllerBuildStrategy.ARTIST_SET_DATA -> {
                val artistData = checkNotNull(artistPageBuildData[controller])
                /*
                 * ArtistEpoxyController 禁止直接 requestModelBuild()。
                 * 复用 Apple 自己的三参数 setData，确保 V.x 从已经覆盖的
                 * ARTIST 实体重新建模，同时保留加歌模式与选中项状态。
                 */
                AppleReflection.call(
                    controller,
                    "setData",
                    artistData.artist,
                    artistData.isAddMusicMode,
                    artistData.selectedItemIds,
                )
            }

            InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD ->
                AppleReflection.call(controller, "requestForcedModelBuild")

            InAppLibraryControllerBuildStrategy.GENERIC_REQUEST_MODEL_BUILD ->
                AppleReflection.call(controller, "requestModelBuild")
        }
    }

    private fun isPlaylistPageController(controller: Any): Boolean =
        generateSequence(controller.javaClass) { it.superclass }
            .any { it.name == APPLE_MUSIC_PLAYLIST_PAGE_CONTROLLER }

    private fun refreshInAppLibraryComposeStateRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias? = null,
    ): Int {
        if (!isRefreshableInAppMediaId(mediaId)) return 0
        val refs = inAppLibraryComposeStateRefs[mediaId] ?: return 0
        val targets = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val appliedAlias = alias?.let { AppliedMetadataAlias(mediaId, it) }
        refs.forEach { ref ->
            val state = ref.get()
            if (state == null) {
                refs.remove(ref)
            } else if (shouldRefreshInAppLibraryComposeAlias(
                    appliedAliases = synchronized(inAppLibraryComposeAppliedAliases) {
                        inAppLibraryComposeAppliedAliases[state]?.toMap()
                    },
                    mediaId = mediaId,
                    requestedAlias = appliedAlias,
                )
            ) {
                targets.add(state)
            }
        }
        targets.forEach { state ->
            val shouldPost = synchronized(inAppLibraryComposeRefreshPending) {
                val pendingAliases = inAppLibraryComposeRefreshPending.getOrPut(state) {
                    mutableMapOf()
                }
                val wasEmpty = pendingAliases.isEmpty()
                pendingAliases[mediaId] = appliedAlias
                wasEmpty
            }
            if (!shouldPost) return@forEach
            mainHandler.post {
                val pendingAliases = synchronized(inAppLibraryComposeRefreshPending) {
                    inAppLibraryComposeRefreshPending.remove(state).orEmpty()
                }
                val activeAliases = pendingAliases.filterKeys(::isRefreshableInAppMediaId)
                if (activeAliases.isEmpty()) return@post
                runCatching {
                    val originalPolicy = AppleReflection.field(state, "b")
                    val neverEqualPolicy = inAppLibraryComposeNeverEqualPolicy
                        ?: error("Compose NeverEqualPolicy unavailable")
                    val value = AppleReflection.call(state, "getValue")
                    AppleReflection.setField(state, "b", neverEqualPolicy)
                    try {
                        AppleReflection.call(state, "setValue", value)
                    } finally {
                        AppleReflection.setField(state, "b", originalPolicy)
                    }
                }
                    .onSuccess {
                        synchronized(inAppLibraryComposeAppliedAliases) {
                            val stateAliases = inAppLibraryComposeAppliedAliases.getOrPut(state) {
                                mutableMapOf()
                            }
                            activeAliases.forEach { (activeMediaId, activeAlias) ->
                                if (activeAlias == null) {
                                    stateAliases.remove(activeMediaId)
                                } else {
                                    stateAliases[activeMediaId] = activeAlias
                                }
                            }
                            if (stateAliases.isEmpty()) {
                                inAppLibraryComposeAppliedAliases.remove(state)
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            ProviderLogger.info(
                                "Apple Music 元数据链路: " +
                                    "seq=${metadataTraceSequence.incrementAndGet()}, " +
                                    "event=library_compose_invalidate, " +
                                    "contentIds=${activeAliases.keys}, " +
                                    "state=${state.javaClass.name}"
                            )
                        }
                    }
                    .onFailure {
                        ProviderLogger.error(
                            "Apple Music 资料库 Compose 局部刷新失败: " +
                                "ids=${activeAliases.keys}, state=${state.javaClass.name}",
                            it,
                        )
                    }
            }
        }
        return targets.size
    }

    private fun refreshInAppDataBindingRefs(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias? = null,
    ): Int {
        val surfaceRelevant = isCurrentMetadataSurfaceMediaId(mediaId)
        if (!shouldRefreshInAppSurface(
                surfaceRelevant = surfaceRelevant,
                hasVisibleExactConsumer = hasVisibleInAppConsumer(mediaId),
            )
        ) return 0
        val invalidateAll = dataBindingInvalidateAllMethod ?: return 0
        val executePendingBindings = dataBindingExecutePendingBindingsMethod
        val appliedAlias = alias?.let { AppliedMetadataAlias(mediaId, it) }
        val defaultDataBindingValues = alias?.let {
            dataBindingAliasValues(mediaId, it, binding = null)
        }
        val playbackItems = inAppPlaybackItemRefs[mediaId]
            ?.mapNotNull { it.playbackItem.get() }
            .orEmpty()
        val libraryEntities = inAppLibraryEntityRefs[mediaId]
            ?.mapNotNull { it.entity.get() }
            .orEmpty()
        val bindingCandidates = playbackItems + libraryEntities
        val targets = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        inAppDataBindingRefs[mediaId]?.forEach { ref ->
            val binding = ref.get()
            if (binding == null) {
                inAppDataBindingRefs[mediaId]?.remove(ref)
            } else if (shouldRefreshExactBoundTarget(
                    surfaceRelevant = surfaceRelevant,
                    mediaIdMatches = inAppDataBindingMediaIds[binding] == mediaId,
                    rootVisible = inAppDataBindingRootViews[binding]?.get()
                        ?.let(::isVisibleBindingRoot) == true,
                ) &&
                shouldScheduleDataBindingAliasRefresh(
                    appliedAlias = inAppDataBindingAppliedAliases[binding],
                    pendingAlias = inAppDataBindingPendingRefreshes[binding]?.alias,
                    requestedAlias = appliedAlias,
                )
            ) {
                targets.add(binding)
            }
        }
        if (bindingCandidates.isNotEmpty()) {
            inAppDataBindingInstances.forEach { ref ->
                val binding = ref.get()
                if (binding == null) {
                    inAppDataBindingInstances.remove(ref)
                } else if (bindingReferencesAny(binding, bindingCandidates)) {
                    inAppDataBindingMediaIds[binding] = mediaId
                    if (shouldRefreshExactBoundTarget(
                            surfaceRelevant = surfaceRelevant,
                            mediaIdMatches = true,
                            rootVisible = inAppDataBindingRootViews[binding]?.get()
                                ?.let(::isVisibleBindingRoot) == true,
                        ) &&
                        shouldScheduleDataBindingAliasRefresh(
                            appliedAlias = inAppDataBindingAppliedAliases[binding],
                            pendingAlias = inAppDataBindingPendingRefreshes[binding]?.alias,
                            requestedAlias = appliedAlias,
                        )
                    ) {
                        targets.add(binding)
                    }
                }
            }
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: " +
                    "seq=${metadataTraceSequence.incrementAndGet()}, " +
                    "event=data_binding_refresh_targets, contentId=$mediaId, " +
                    "alias=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                    "values=${defaultDataBindingValues?.title}/" +
                    "${defaultDataBindingValues?.subtitle}, " +
                    "directRefs=${inAppDataBindingRefs[mediaId]?.size ?: 0}, " +
                    "playbackItems=${playbackItems.size}, " +
                    "libraryEntities=${libraryEntities.size}, targets=${targets.size}"
            )
        }
        targets.forEach { binding ->
            val bindGeneration = inAppDataBindingBindGeneration(binding)
            val pendingRefresh = appliedAlias?.let { requestedAlias ->
                PendingDataBindingRefresh(
                    mediaId = mediaId,
                    alias = requestedAlias,
                    bindGeneration = bindGeneration,
                )
            }
            val shouldPost = if (pendingRefresh == null) {
                true
            } else {
                synchronized(inAppDataBindingPendingRefreshes) {
                    if (!shouldScheduleDataBindingAliasRefresh(
                            appliedAlias = inAppDataBindingAppliedAliases[binding],
                            pendingAlias = inAppDataBindingPendingRefreshes[binding]?.alias,
                            requestedAlias = pendingRefresh.alias,
                        )
                    ) {
                        false
                    } else {
                        inAppDataBindingPendingRefreshes[binding] = pendingRefresh
                        true
                    }
                }
            }
            if (!shouldPost) return@forEach
            mainHandler.post {
                fun abandonPendingRefresh() {
                    pendingRefresh?.let { pending ->
                        clearPendingDataBindingRefresh(binding, pending)
                    }
                }
                if (pendingRefresh != null &&
                    inAppDataBindingPendingRefreshes[binding] != pendingRefresh
                ) return@post
                if (!isDataBindingRefreshCurrent(
                        currentMediaId = inAppDataBindingMediaIds[binding],
                        requestedMediaId = mediaId,
                        currentBindGeneration = inAppDataBindingBindGeneration(binding),
                        scheduledBindGeneration = bindGeneration,
                    ) ||
                    !shouldRefreshExactBoundTarget(
                        surfaceRelevant = isCurrentMetadataSurfaceMediaId(mediaId),
                        mediaIdMatches = true,
                        rootVisible = inAppDataBindingRootViews[binding]?.get()
                            ?.let(::isVisibleBindingRoot) == true,
                    )
                ) {
                    abandonPendingRefresh()
                    return@post
                }
                val previousAppliedAlias = inAppDataBindingAppliedAliases[binding]
                val root = inAppDataBindingRootViews[binding]?.get()
                val dataBindingValues = alias?.let {
                    dataBindingAliasValues(mediaId, it, binding)
                }
                if (BuildConfig.DEBUG) {
                    ProviderLogger.info(
                        "Apple Music 元数据链路: " +
                            "seq=${metadataTraceSequence.incrementAndGet()}, " +
                            "event=data_binding_apply_before, contentId=$mediaId, " +
                            "binding=${binding.javaClass.name}@" +
                            "${System.identityHashCode(binding)}, " +
                            "values=${dataBindingValues?.title}/" +
                            "${dataBindingValues?.subtitle}, " +
                            "texts=${root?.let(::debugTextSnapshot)}"
                    )
                }
                var refreshStrategy = DataBindingRefreshStrategy.FULL_INVALIDATE
                runCatching {
                    val variableResults = dataBindingValues?.let { values ->
                        applyAliasToInAppDataBindingVariables(binding, values)
                    }
                    refreshStrategy = dataBindingRefreshStrategy(
                        expectedTitle = dataBindingValues?.title,
                        expectedSubtitle = dataBindingValues?.subtitle,
                        titleApplied = variableResults?.titleApplied == true,
                        subtitleApplied = variableResults?.subtitleApplied == true,
                    )
                    if (refreshStrategy == DataBindingRefreshStrategy.FULL_INVALIDATE) {
                        invalidateAll.invoke(binding)
                    }
                    executePendingBindings?.invoke(binding)
                    variableResults
                }
                    .onSuccess { variableResults ->
                        if (inAppDataBindingMediaIds[binding] == mediaId &&
                            inAppDataBindingBindGeneration(binding) == bindGeneration
                        ) {
                            appliedAlias?.let { inAppDataBindingAppliedAliases[binding] = it }
                        }
                        abandonPendingRefresh()
                        if (BuildConfig.DEBUG) {
                            ProviderLogger.info(
                                "Apple Music 元数据链路: " +
                                    "seq=${metadataTraceSequence.incrementAndGet()}, " +
                                    "event=data_binding_rebind, contentId=$mediaId, " +
                                    "binding=${binding.javaClass.name}, " +
                                    "titleApplied=${variableResults?.titleApplied}, " +
                                    "subtitleApplied=${variableResults?.subtitleApplied}, " +
                                    "refreshStrategy=$refreshStrategy, " +
                                    "texts=${root?.let(::debugTextSnapshot)}"
                            )
                        }
                    }
                    .onFailure {
                        abandonPendingRefresh()
                        if (previousAppliedAlias == null) {
                            inAppDataBindingAppliedAliases.remove(binding)
                        } else {
                            inAppDataBindingAppliedAliases[binding] = previousAppliedAlias
                        }
                        ProviderLogger.error(
                            "Apple Music 资料库精确重绑定失败: " +
                                "id=$mediaId, binding=${binding.javaClass.name}",
                            it,
                        )
                    }
            }
        }
        return targets.size
    }

    private fun dataBindingAliasValues(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        binding: Any?,
    ): DataBindingAliasValues {
        val entityType = playbackMetadataEntityTypes[mediaId]
            ?: AppleInternalCatalogResolver.LocalizedEntityType.SONG
        val title = contentItemMetadataOverride(
            entityType = entityType,
            getter = "getTitle",
            alias = alias,
            original = null,
        )
        val defaultSubtitle = contentItemMetadataOverride(
            entityType = entityType,
            getter = "getSubTitle",
            alias = alias,
            original = null,
        )
        val artistTopSong = binding?.let { inAppArtistTopSongBindings[it] }
        val subtitle = if (artistTopSong != null) {
            artistProfileSubtitleWithArtist(
                originalSubtitle = artistTopSong.originalSubtitle,
                originalArtist = artistTopSong.originalArtist,
                replacementArtist = alias.artist,
            )
        } else {
            defaultSubtitle
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: " +
                    "seq=${metadataTraceSequence.incrementAndGet()}, " +
                    "event=data_binding_values, contentId=$mediaId, " +
                    "entityType=$entityType, " +
                    "alias=${alias.title}/${alias.artist}/${alias.album}, " +
                    "title=$title, subtitle=$subtitle"
            )
        }
        return DataBindingAliasValues(title = title, subtitle = subtitle)
    }

    private fun applyAliasToInAppDataBindingVariables(
        binding: Any,
        values: DataBindingAliasValues,
    ): DataBindingVariableApplyResult {
        val setVariable = dataBindingSetVariableMethod
            ?: return DataBindingVariableApplyResult(false, false)
        fun setVariableValue(variableId: Int?, value: String?): Boolean {
            if (variableId == null || value.isNullOrBlank()) return false
            return runCatching {
                setVariable.invoke(binding, variableId, value) == true
            }.getOrDefault(false)
        }
        return DataBindingVariableApplyResult(
            titleApplied = setVariableValue(dataBindingTitleVariableId, values.title),
            subtitleApplied = setVariableValue(dataBindingSubtitleVariableId, values.subtitle),
        )
    }

    private fun bindingReferencesAny(binding: Any, candidates: List<Any>): Boolean {
        val candidateSet = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        candidateSet.addAll(candidates)
        val fields = inAppDataBindingContentFields.computeIfAbsent(binding.javaClass) {
            bindingContentFields(it)
        }
        return fields.any { field ->
            runCatching { field.get(binding) }
                .getOrNull()
                ?.let(candidateSet::contains) == true
        }
    }

    private fun bindingContentFields(bindingClass: Class<*>): List<Field> {
        val baseClass = dataBindingBaseClass ?: return emptyList()
        return generateSequence(bindingClass) { current ->
            current.superclass?.takeUnless { it == baseClass }
        }.flatMap { current ->
            current.declaredFields.asSequence()
        }.filter { field ->
            !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive
        }.onEach { field ->
            field.isAccessible = true
        }.toList()
    }

    internal fun associatedArtistAlias(
        artistIds: List<String>,
        aliases: Map<String, AppleInternalCatalogResolver.Alias>,
        language: String,
    ): AppleInternalCatalogResolver.Alias? {
        if (artistIds.isEmpty()) return null
        val names = artistIds.map { artistId ->
            val alias = aliases[artistId] ?: return null
            alias.artist.ifBlank { alias.title }.trim().takeIf(String::isNotEmpty) ?: return null
        }.distinct()
        val separator = if (language.startsWith("zh-", ignoreCase = true)) "、" else ", "
        return AppleInternalCatalogResolver.Alias(
            title = "",
            artist = names.joinToString(separator),
            language = language,
            album = "",
        )
    }

    internal fun selectEffectiveMetadataAlias(
        restoreOriginalEnabled: Boolean,
        originalMetadataResolved: Boolean,
        originalMetadata: AppleInternalCatalogResolver.Alias?,
        originalArtistResolved: Boolean,
        originalArtist: AppleInternalCatalogResolver.Alias?,
        localizedMetadata: AppleInternalCatalogResolver.Alias?,
        localizedArtist: AppleInternalCatalogResolver.Alias?,
    ): AppleInternalCatalogResolver.Alias? {
        if (restoreOriginalEnabled) {
            if (originalMetadata != null) {
                return mergeMetadataArtist(
                    originalMetadata,
                    originalArtist.takeIf { originalArtistResolved },
                )
            }
            if (!originalMetadataResolved) {
                return mergeMetadataArtist(
                    localizedMetadata,
                    originalArtist.takeIf { originalArtistResolved } ?: localizedArtist,
                )
            }
            val localizedMetadataAllowed = AppleOriginalMetadataPolicy.shouldExposeLocalizedMetadata(
                restoreOriginalEnabled = true,
                originalResolved = originalMetadataResolved,
                hasOriginalMetadata = false,
            )
            if (!localizedMetadataAllowed) return null
            return mergeMetadataArtist(
                localizedMetadata,
                originalArtist.takeIf { originalArtistResolved } ?: localizedArtist,
            )
        }
        return mergeMetadataArtist(localizedMetadata, localizedArtist)
    }

    internal fun selectIndependentArtistAlias(
        restoreOriginalEnabled: Boolean,
        canUseAssociatedArtist: Boolean,
        originalArtist: AppleInternalCatalogResolver.Alias?,
        localizedArtist: AppleInternalCatalogResolver.Alias?,
    ): AppleInternalCatalogResolver.Alias? {
        if (!canUseAssociatedArtist) return null
        val selected = if (restoreOriginalEnabled) {
            originalArtist ?: localizedArtist
        } else {
            localizedArtist
        } ?: return null
        val artist = selected.artist.trim().takeIf(String::isNotEmpty) ?: return null
        return selected.copy(
            title = "",
            artist = artist,
            album = "",
        )
    }

    internal fun shouldRequestEffectiveMetadataResolution(
        restoreOriginalEnabled: Boolean,
        originalMetadataResolved: Boolean,
        hasOriginalMetadata: Boolean,
        hasAssociatedArtists: Boolean,
        originalArtistResolved: Boolean,
        hasLocalizedMetadata: Boolean,
    ): Boolean {
        if (!restoreOriginalEnabled) return !hasLocalizedMetadata
        val metadataPending = if (originalMetadataResolved) {
            !hasOriginalMetadata && !hasLocalizedMetadata
        } else {
            !hasOriginalMetadata
        }
        val artistPending = hasAssociatedArtists && !originalArtistResolved
        return metadataPending || artistPending
    }

    internal fun inAppOriginalResolutionPlan(
        mediaIds: Collection<String>,
        awaitingLocalizedIds: Set<String>,
        mode: InAppOriginalResolutionMode,
    ): InAppOriginalResolutionPlan {
        val normalizedIds = mediaIds.distinct()
        return when (mode) {
            InAppOriginalResolutionMode.ORIGINAL_FIRST -> InAppOriginalResolutionPlan(
                beforeLocalized = normalizedIds,
                afterLocalized = emptyList(),
                resolveLocalizedImmediately = false,
            )

            InAppOriginalResolutionMode.AFTER_LOCALIZED -> InAppOriginalResolutionPlan(
                beforeLocalized = emptyList(),
                afterLocalized = normalizedIds.filterNot(awaitingLocalizedIds::contains),
                resolveLocalizedImmediately = true,
            )
        }
    }

    internal fun collectionPageOriginalResolutionMode(
        @Suppress("UNUSED_PARAMETER") pageType: String,
    ): InAppOriginalResolutionMode = InAppOriginalResolutionMode.ORIGINAL_FIRST

    private fun mergeMetadataArtist(
        metadata: AppleInternalCatalogResolver.Alias?,
        artist: AppleInternalCatalogResolver.Alias?,
    ): AppleInternalCatalogResolver.Alias? {
        metadata ?: return null
        val artistName = artist?.artist?.takeIf(String::isNotBlank) ?: return metadata
        return metadata.copy(artist = artistName)
    }

    internal fun localizedVisibleText(
        field: VisibleTextField,
        alias: AppleInternalCatalogResolver.Alias,
    ): String = when (field) {
        VisibleTextField.TITLE -> alias.title
        VisibleTextField.ARTIST -> alias.artist
        VisibleTextField.ALBUM -> alias.album.ifBlank { alias.title }
    }

    internal fun visibleTextFieldForMediaApiAttribute(
        kind: InAppLibraryEntityKind,
        getter: String,
    ): VisibleTextField? = when (getter) {
        "getName" -> when (kind) {
            InAppLibraryEntityKind.SONG -> VisibleTextField.TITLE
            InAppLibraryEntityKind.ALBUM -> VisibleTextField.ALBUM
            InAppLibraryEntityKind.ARTIST -> VisibleTextField.ARTIST
        }
        "getArtistName" -> VisibleTextField.ARTIST
        "getAlbumName" -> VisibleTextField.ALBUM
        else -> null
    }

    internal fun contentItemMetadataOverride(
        entityType: AppleInternalCatalogResolver.LocalizedEntityType,
        getter: String,
        alias: AppleInternalCatalogResolver.Alias,
        original: String?,
    ): String? = when (getter) {
        "getTitle" -> when (entityType) {
            AppleInternalCatalogResolver.LocalizedEntityType.SONG -> alias.title
            AppleInternalCatalogResolver.LocalizedEntityType.ALBUM ->
                alias.album.ifBlank { alias.title }
            AppleInternalCatalogResolver.LocalizedEntityType.ARTIST ->
                alias.artist.ifBlank { alias.title }
        }
        "getNowPlayingTitle" ->
            alias.title.takeIf {
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
            }
        "getArtistName" -> alias.artist
        "getNowPlayingSubtitle" ->
            alias.artist.takeIf {
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
            }
        "getSubTitle" ->
            alias.artist.takeIf {
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG ||
                    entityType == AppleInternalCatalogResolver.LocalizedEntityType.ALBUM
            }
        "getCollectionName" ->
            alias.album.takeIf {
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
            }
        else -> null
    }?.takeIf { it.isNotBlank() } ?: original

    internal fun visibleTextFieldForContentItemGetter(
        entityType: AppleInternalCatalogResolver.LocalizedEntityType,
        getter: String,
    ): VisibleTextField? = when (getter) {
        "getTitle" -> when (entityType) {
            AppleInternalCatalogResolver.LocalizedEntityType.SONG -> VisibleTextField.TITLE
            AppleInternalCatalogResolver.LocalizedEntityType.ALBUM -> VisibleTextField.ALBUM
            AppleInternalCatalogResolver.LocalizedEntityType.ARTIST -> VisibleTextField.ARTIST
        }
        "getNowPlayingTitle" ->
            VisibleTextField.TITLE.takeIf {
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
            }
        "getArtistName" -> VisibleTextField.ARTIST
        "getNowPlayingSubtitle" ->
            VisibleTextField.ARTIST.takeIf {
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
            }
        "getSubTitle" ->
            VisibleTextField.ARTIST.takeIf {
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG ||
                    entityType == AppleInternalCatalogResolver.LocalizedEntityType.ALBUM
            }
        "getCollectionName" ->
            VisibleTextField.ALBUM.takeIf {
                entityType == AppleInternalCatalogResolver.LocalizedEntityType.SONG
            }
        else -> null
    }

    internal fun preferredVisibleEntityType(
        field: VisibleTextField?,
    ): AppleInternalCatalogResolver.LocalizedEntityType? = when (field) {
        VisibleTextField.TITLE -> AppleInternalCatalogResolver.LocalizedEntityType.SONG
        VisibleTextField.ARTIST -> AppleInternalCatalogResolver.LocalizedEntityType.ARTIST
        VisibleTextField.ALBUM -> AppleInternalCatalogResolver.LocalizedEntityType.ALBUM
        null -> null
    }

    private fun applyAliasToInAppContainerItem(
        containerItem: Any,
        kind: InAppContainerKind,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean = true,
    ) {
        val title = when (kind) {
            InAppContainerKind.ARTIST -> alias.artist
            InAppContainerKind.ALBUM -> alias.album
        }.takeIf(String::isNotBlank) ?: return
        val changed = runCatching {
            AppleReflection.call(containerItem, "setTitle", title)
            true
        }.getOrDefault(false)
        if (changed && notifyChange) {
            runCatching { AppleReflection.call(containerItem, "notifyChange") }
                .onFailure {
                    ProviderLogger.error(
                        "Apple Music App 容器跳转项变更通知失败: " +
                            "class=${containerItem.javaClass.name}, kind=$kind",
                        it,
                    )
                }
        }
    }

    private fun applyAliasToInAppPlaybackItem(
        playbackItem: Any,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean = true,
    ) {
        val entityType = contentItemLocalizedEntityType(playbackItem) ?: return
        val contract = inAppPlaybackItemContract(playbackItem)
        var changed = false
        contentItemMetadataOverride(
            entityType = entityType,
            getter = "getTitle",
            alias = alias,
            original = null,
        )?.takeIf(String::isNotBlank)?.let { value ->
            val current = readInAppPlaybackItemValue(
                playbackItem,
                InAppPlaybackItemField.TITLE,
                contract,
            )
            if (current != value) {
                changed = writeInAppPlaybackItemValue(
                    playbackItem,
                    InAppPlaybackItemField.TITLE,
                    value,
                    contract,
                ) || changed
            }
        }
        contentItemMetadataOverride(
            entityType = entityType,
            getter = "getArtistName",
            alias = alias,
            original = null,
        )?.takeIf(String::isNotBlank)?.let { value ->
            val current = readInAppPlaybackItemValue(
                playbackItem,
                InAppPlaybackItemField.ARTIST,
                contract,
            )
            if (current != value) {
                changed = writeInAppPlaybackItemValue(
                    playbackItem,
                    InAppPlaybackItemField.ARTIST,
                    value,
                    contract,
                ) || changed
            }
        }
        contentItemMetadataOverride(
            entityType = entityType,
            getter = "getCollectionName",
            alias = alias,
            original = null,
        )?.takeIf(String::isNotBlank)?.let { value ->
            val current = readInAppPlaybackItemValue(
                playbackItem,
                InAppPlaybackItemField.ALBUM,
                contract,
            )
            if (current != value) {
                changed = writeInAppPlaybackItemValue(
                    playbackItem,
                    InAppPlaybackItemField.ALBUM,
                    value,
                    contract,
                ) || changed
            }
        }
        if (
            changed &&
            notifyChange &&
            contract == InAppPlaybackItemContract.STANDARD
        ) {
            runCatching { AppleReflection.call(playbackItem, "notifyChange") }
                .onFailure {
                    ProviderLogger.error(
                        "Apple Music App PlaybackItem 变更通知失败: " +
                            "class=${playbackItem.javaClass.name}",
                        it,
                    )
                }
        }
    }

    private fun restoreInAppMetadata() {
        inAppMetadataRefs.values.forEach { refs ->
            refs.forEach { ref ->
                val metadata = ref.metadata.get()
                if (metadata == null) {
                    refs.remove(ref)
                } else {
                    AppleReflection.setField(metadata, "a", ref.originalTitle)
                    AppleReflection.setField(metadata, "b", ref.originalArtist)
                }
            }
        }
        inAppPlaybackItemRefs.forEach { (mediaId, refs) ->
            refs.forEach { ref ->
                val playbackItem = ref.playbackItem.get()
                if (playbackItem == null) {
                    refs.remove(ref)
                } else if (
                    !shouldApplyInAppPlaybackItemAlias(
                        expectedMediaId = mediaId,
                        currentMediaId = synchronized(inAppPlaybackItemIds) {
                            inAppPlaybackItemIds[playbackItem]
                        },
                    )
                ) {
                    refs.remove(ref)
                } else {
                    writeInAppPlaybackItemValue(
                        playbackItem,
                        InAppPlaybackItemField.TITLE,
                        ref.originalTitle?.toString(),
                        ref.contract,
                    )
                    writeInAppPlaybackItemValue(
                        playbackItem,
                        InAppPlaybackItemField.ARTIST,
                        ref.originalArtist?.toString(),
                        ref.contract,
                    )
                    writeInAppPlaybackItemValue(
                        playbackItem,
                        InAppPlaybackItemField.ALBUM,
                        ref.originalCollectionName,
                        ref.contract,
                    )
                    if (ref.contract == InAppPlaybackItemContract.STANDARD) {
                        runCatching { AppleReflection.call(playbackItem, "notifyChange") }
                            .onFailure {
                                ProviderLogger.error(
                                    "Apple Music App PlaybackItem 恢复通知失败: " +
                                        "class=${playbackItem.javaClass.name}",
                                    it,
                                )
                            }
                    }
                }
            }
        }
        inAppLibraryEntityRefs.forEach { (mediaId, refs) ->
            refs.forEach { ref ->
                val entity = ref.entity.get()
                if (entity == null) {
                    refs.remove(ref)
                } else {
                    val attributes = mediaApiEntityAttributes(entity) ?: return@forEach
                    ref.originalName?.let { value ->
                        runCatching { AppleReflection.call(attributes, "setName", value) }
                    }
                    ref.originalArtist?.let { value ->
                        runCatching { AppleReflection.call(attributes, "setArtistName", value) }
                    }
                    ref.originalAlbum?.let { value ->
                        runCatching { AppleReflection.call(attributes, "setAlbumName", value) }
                    }
                }
            }
            refreshInAppLibraryControllerRefs(mediaId)
            refreshInAppLibraryComposeStateRefs(mediaId)
            refreshInAppDataBindingRefs(mediaId)
        }
        inAppContainerItemRefs.values.forEach { refs ->
            refs.forEach { ref ->
                val containerItem = ref.containerItem.get()
                if (containerItem == null) {
                    refs.remove(ref)
                } else {
                    AppleReflection.call(containerItem, "setTitle", ref.originalTitle)
                    runCatching { AppleReflection.call(containerItem, "notifyChange") }
                        .onFailure {
                            ProviderLogger.error(
                                "Apple Music App 容器跳转项恢复通知失败: " +
                                    "class=${containerItem.javaClass.name}, kind=${ref.kind}",
                                it,
                            )
                        }
                }
            }
        }
    }

    private fun refreshInAppMetadataViews(
        mediaId: String? = null,
        alias: AppleInternalCatalogResolver.Alias? = null,
    ) {
        val dispatcherRefresh = currentInAppMetadataDispatcherRefresh
            ?.takeIf { mediaId == null || it.mediaId == mediaId }
        val listenerRefresh = currentInAppMetadataRefresh
            ?.takeIf { mediaId == null || it.mediaId == mediaId }
        if (dispatcherRefresh == null && listenerRefresh == null) return
        val appliedAlias = mediaId?.let { id -> alias?.let { AppliedMetadataAlias(id, it) } }
        mainHandler.post {
            var listenerHandled = false
            listenerRefresh?.let { refresh ->
                val listener = refresh.listener.get()
                val metadata = refresh.metadata.get()
                if (listener != null && metadata != null) {
                    if (appliedAlias != null &&
                        inAppMetadataCallbackAppliedAliases[listener] == appliedAlias
                    ) {
                        listenerHandled = true
                    } else {
                        runCatching { refresh.method.invoke(listener, metadata) }
                            .onSuccess {
                                listenerHandled = true
                                appliedAlias?.let {
                                    inAppMetadataCallbackAppliedAliases[listener] = it
                                }
                                logMetadataIdentity(
                                    event = "in_app_now_playing_refresh",
                                    details = "refreshId=${refresh.mediaId}",
                                )
                            }
                            .onFailure {
                                ProviderLogger.error(
                                    "Apple Music App 播放页元数据刷新失败",
                                    it,
                                )
                            }
                    }
                }
            }
            if (!listenerHandled) {
                dispatcherRefresh?.let { refresh ->
                    val dispatcher = refresh.dispatcher.get()
                    val metadata = refresh.metadata.get()
                    if (dispatcher != null && metadata != null) {
                        if (appliedAlias == null ||
                            inAppMetadataCallbackAppliedAliases[dispatcher] != appliedAlias
                        ) {
                            runCatching { refresh.method.invoke(dispatcher, metadata) }
                                .onSuccess {
                                    appliedAlias?.let {
                                        inAppMetadataCallbackAppliedAliases[dispatcher] = it
                                    }
                                    logMetadataIdentity(
                                        event = "in_app_dispatcher_refresh",
                                        details = "refreshId=${refresh.mediaId}",
                                    )
                                }
                                .onFailure {
                                    ProviderLogger.error(
                                        "Apple Music App 全局元数据刷新失败",
                                        it,
                                    )
                                }
                        }
                    }
                }
            }
        }
    }

    private fun refreshCurrentQueueItemIfActive(mediaPlayer: Any?, source: String) {
        if (!isActivePlaybackCallback(mediaPlayer, activePlaybackPlayer)) {
            ProviderLogger.debug(
                "忽略非活动播放器的歌曲元数据：source=$source, " +
                    "callback=${mediaPlayer?.let(System::identityHashCode)}, " +
                    "active=${activePlaybackPlayer?.let(System::identityHashCode)}"
            )
            return
        }
        refreshCurrentQueueItem(mediaPlayer, source)
    }

    private fun refreshCurrentQueueItem(mediaPlayer: Any?, source: String) {
        if (mediaPlayer == null) {
            ProviderLogger.debug("歌曲元数据刷新失败：$source 的 MediaPlayer 为空")
            return
        }
        runCatching {
            handleQueueItem(AppleReflection.call(mediaPlayer, "getCurrentItem"), source)
        }.onFailure {
            ProviderLogger.error("歌曲元数据刷新异常：source=$source", it)
        }
    }

    private fun handleQueueItem(
        queueItem: Any?,
        source: String,
        publishAsCurrent: Boolean = true,
        refreshPlaybackMetadata: (() -> Unit)? = null,
    ) {
        if (queueItem == null) {
            ProviderLogger.debug("歌曲元数据更新失败：$source 的 PlayerQueueItem 为空")
            return
        }
        runCatching {
            val mediaItem = AppleReflection.call(queueItem, "getItem") ?: return@runCatching
            val mediaId = mediaItemId(mediaItem) ?: return@runCatching
            val languageSelection = configuredContentUiLanguage()
            val overrideAccountLanguage = shouldOverrideAccountLanguage(languageSelection)

            val metadata = MediaMetadataCache.Metadata(
                id = mediaId,
                title = AppleReflection.call(mediaItem, "getTitle") as? String,
                artist = AppleReflection.call(mediaItem, "getArtistName") as? String,
                genre = AppleReflection.call(mediaItem, "getGenreName") as? String,
                duration = AppleReflection.call(mediaItem, "getDuration") as? Long ?: 0L,
                queueId = AppleReflection.call(queueItem, "getPlaybackQueueId") as? Long ?: 0L
            )
            val restoreCjkOriginalMetadata = shouldRestoreCjkOriginalMetadata(metadata)
            if (overrideAccountLanguage || restoreCjkOriginalMetadata) {
                ensureContentItemMetadataHooks(mediaItem.javaClass)
            }
            val previousMetadata = MediaMetadataCache.getMetadataById(mediaId)
            MediaMetadataCache.put(metadata)
            ProviderLogger.debug(
                "歌曲元数据已更新：source=$source, id=${metadata.id}, " +
                    "queueId=${metadata.queueId}, 标题=${metadata.title}"
            )
            if (publishAsCurrent) {
                val previousCurrentId = currentPlaybackMetadataId
                currentPlaybackMetadataId = mediaId
                setMetadataPlaybackMediaId(mediaId)
                currentPlaybackMetadataOverride = effectiveInAppMetadataOverride(mediaId)
                refreshPlaybackMetadata?.let { callback ->
                    currentPlaybackMetadataRefresh = PlaybackMetadataRefresh(mediaId, callback)
                }
                PlaybackManager.onSongChanged(metadata.id)
                logMetadataIdentity(
                    event = "queue_current_published",
                    details = "trigger=$source, previousId=$previousCurrentId, " +
                        "publishedId=$mediaId, title=${metadata.title}, artist=${metadata.artist}, " +
                        "queueId=${metadata.queueId}, overrideEnabled=$overrideAccountLanguage",
                )
                if (
                    previousMetadata != null &&
                    (previousMetadata.title != metadata.title ||
                        previousMetadata.artist != metadata.artist)
                ) {
                    PlaybackManager.onCatalogMetadataResolved(metadata.id)
                }
                resolveCatalogMetadata(
                    metadata = metadata,
                    languageSelection = languageSelection,
                    overrideAccountLanguage = overrideAccountLanguage,
                    restoreCjkOriginalMetadata = restoreCjkOriginalMetadata,
                )
            }
        }.onFailure {
            ProviderLogger.error("歌曲元数据解析异常：source=$source", it)
        }
    }

    private fun resolveCatalogMetadata(
        metadata: MediaMetadataCache.Metadata,
        languageSelection: Int,
        overrideAccountLanguage: Boolean,
        restoreCjkOriginalMetadata: Boolean,
    ) {
        val resolutionPlan = catalogMetadataResolutionPlan(
            overrideAccountLanguage = overrideAccountLanguage,
            restoreCjkOriginalMetadata = restoreCjkOriginalMetadata,
        )
        if (resolutionPlan.resolveConfiguredRegion) {
            resolveConfiguredCatalogMetadata(metadata, languageSelection)
        }

        if (resolutionPlan.resolveOriginalRegion) {
            if (metadata.originalMetadataResolved) {
                val cachedAlias = cachedOriginalMetadataAlias(metadata)
                if (cachedAlias != null) {
                    applyPlaybackMetadataOverride(
                        mediaId = metadata.id,
                        alias = cachedAlias,
                        rememberLocalizedArtist = false,
                        originalMetadata = true,
                        originalMetadataConfirmed = true,
                    )
                } else {
                    PlaybackManager.onCatalogMetadataResolved(metadata.id)
                }
                return
            }
            resolveOriginalMetadata(
                metadata = metadata,
                applyToPlayback = true,
                reason = "setting_enabled",
            )
            return
        }
        if (!resolutionPlan.resolveConfiguredRegion) {
            logMetadataIdentity(
                event = "current_catalog_resolve_skipped",
                details = "requestedId=${metadata.id}, selection=$languageSelection, reason=disabled",
            )
        }
    }

    private fun resolveConfiguredCatalogMetadata(
        metadata: MediaMetadataCache.Metadata,
        languageSelection: Int,
    ) {
        logMetadataIdentity(
            event = "current_catalog_resolve_started",
            details = "requestedId=${metadata.id}, selection=$languageSelection, " +
                "title=${metadata.title}, artist=${metadata.artist}",
        )

        internalCatalogResolver.resolveForContentUiLanguage(
            mediaId = metadata.id,
            selection = languageSelection,
        ) { alias ->
            logMetadataIdentity(
                event = "current_catalog_resolve_finished",
                details = "requestedId=${metadata.id}, selection=$languageSelection, " +
                    "hit=${alias != null}, resolved=${alias?.title}/${alias?.artist}/${alias?.album}",
            )
            if (
                alias != null &&
                shouldOverrideAccountLanguage(languageSelection) &&
                configuredContentUiLanguage() == languageSelection
            ) {
                applyPlaybackMetadataOverride(metadata.id, alias)
            }
        }
    }

    internal fun catalogMetadataResolutionPlan(
        overrideAccountLanguage: Boolean,
        restoreCjkOriginalMetadata: Boolean,
    ): CatalogMetadataResolutionPlan = CatalogMetadataResolutionPlan(
        resolveConfiguredRegion = overrideAccountLanguage,
        resolveOriginalRegion = restoreCjkOriginalMetadata,
    )

    private fun resolveOriginalMetadataOnDemand(mediaId: String) {
        val metadata = MediaMetadataCache.getMetadataById(mediaId)
        if (metadata == null) {
            ProviderLogger.info("Apple 原名按需查询忽略: id=$mediaId, reason=metadata_missing")
            return
        }
        if (
            metadata.originalMetadataResolved ||
            !metadata.originalTitle.isNullOrBlank() ||
            !metadata.originalArtist.isNullOrBlank()
        ) {
            PlaybackManager.onCatalogMetadataResolved(mediaId)
            return
        }
        resolveOriginalMetadata(
            metadata = metadata,
            applyToPlayback = shouldRestoreCjkOriginalMetadata(metadata),
            reason = "online_source_requested",
        )
    }

    private fun resolveOriginalMetadata(
        metadata: MediaMetadataCache.Metadata,
        applyToPlayback: Boolean,
        reason: String,
    ) {
        ProviderLogger.info(
            "Apple 原名查询开始: id=${metadata.id}, title=${metadata.title}, " +
                "artist=${metadata.artist}, reason=$reason"
        )
        internalCatalogResolver.resolveOriginalMetadata(
            metadata = metadata,
            onCandidate = candidate@{ candidate ->
                if (!applyToPlayback ||
                    !isRestoreCjkOriginalMetadataEnabled() ||
                    currentPlaybackMetadataId != metadata.id
                ) return@candidate
                val safeCandidate = validatedOriginalSongAlias(
                    alias = candidate,
                    localizedTitle = metadata.title,
                    localizedArtist = metadata.artist,
                ) ?: return@candidate
                applyPlaybackMetadataOverride(
                    mediaId = metadata.id,
                    alias = safeCandidate,
                    rememberLocalizedArtist = false,
                )
            },
            onResolved = { resolution ->
                val alias = validatedOriginalSongAlias(
                    alias = resolution.alias,
                    localizedTitle = metadata.title,
                    localizedArtist = metadata.artist,
                )
                originalMetadataResolvedIds.add(metadata.id)
                resolution.language?.takeIf {
                    shouldShareOriginalSongLanguage(
                        localizedTitle = metadata.title,
                        localizedArtist = metadata.artist,
                        alias = alias,
                    )
                }?.let { language ->
                    rememberOriginalLanguageForArtist(metadata.id, language)
                }
                MediaMetadataCache.updateOriginalMetadata(
                    mediaId = metadata.id,
                    title = alias?.title,
                    artist = alias?.artist,
                    resolved = true,
                )
                if (alias == null) {
                    ProviderLogger.info(
                        "Apple 原名查询未命中: id=${metadata.id}, reason=$reason"
                    )
                    playbackMetadataOverrides[metadata.id]?.let { localizedAlias ->
                        applyPlaybackMetadataOverride(metadata.id, localizedAlias)
                    } ?: PlaybackManager.onCatalogMetadataResolved(metadata.id)
                    return@resolveOriginalMetadata
                }
                if (
                    applyToPlayback &&
                    isRestoreCjkOriginalMetadataEnabled() &&
                    currentPlaybackMetadataId == metadata.id
                ) {
                    applyPlaybackMetadataOverride(
                        mediaId = metadata.id,
                        alias = alias,
                        rememberLocalizedArtist = false,
                        originalMetadata = true,
                        originalMetadataConfirmed = true,
                    )
                } else {
                    PlaybackManager.onCatalogMetadataResolved(metadata.id)
                }
            },
        )
    }

    private fun rememberOriginalMetadataOverride(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        confirmed: Boolean,
    ) {
        originalMetadataCacheMissUptimeMillis.remove(mediaId)
        originalMetadataResolvedIds.add(mediaId)
        if (confirmed) {
            confirmedOriginalMetadataIds.add(mediaId)
            originalMetadataOverrides[mediaId] = alias
        } else {
            originalMetadataOverrides.putIfAbsent(mediaId, alias)
        }
    }

    private fun applyPlaybackMetadataOverride(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean = true,
        rememberLocalizedArtist: Boolean = true,
        originalMetadata: Boolean = false,
        originalMetadataConfirmed: Boolean = false,
        artistOnly: Boolean = false,
        propagateArtistEntity: Boolean = true,
    ) {
        if (artistOnly && propagateArtistEntity) {
            val artistId = sharedAssociatedArtistId(mediaId)
            if (artistId != null) {
                propagateSharedArtistOverride(
                    sourceMediaId = mediaId,
                    artistId = artistId,
                    alias = alias,
                    forceInAppRebind = forceInAppRebind,
                    rememberLocalizedArtist = rememberLocalizedArtist,
                    originalMetadata = originalMetadata,
                    originalMetadataConfirmed = originalMetadataConfirmed,
                )
                return
            }
        }
        val previousEffective = effectiveInAppMetadataOverride(mediaId)
        if (artistOnly && originalMetadata) {
            originalArtistResolvedIds.add(mediaId)
            originalArtistOverrides[mediaId] = alias
        } else if (artistOnly) {
            playbackArtistOverrides[mediaId] = alias
        } else if (originalMetadata) {
            rememberOriginalMetadataOverride(
                mediaId = mediaId,
                alias = alias,
                confirmed = originalMetadataConfirmed,
            )
        } else {
            playbackMetadataOverrides[mediaId] = alias
        }
        if (rememberLocalizedArtist && !originalMetadata && artistOnly) {
            internalCatalogResolver.rememberLocalizedArtist(
                selection = configuredContentUiLanguage(),
                artistKeys = localizedArtistCacheKeys(
                    playbackMetadataArtistKeys[mediaId].orEmpty()
                ),
                localizedArtist = alias.artist,
            )
        }
        val effectiveAlias = effectiveInAppMetadataOverride(mediaId)
        if (effectiveAlias == null) {
            ProviderLogger.info(
                "Apple 设定地区元数据仅缓存: id=$mediaId, " +
                    "reason=original_region_pending"
            )
            return
        }
        val shouldForceInAppRebind =
            forceInAppRebind || previousEffective != effectiveAlias
        val identity = activePlaybackMediaIdentity()
        val appliesToActivePlayback = identity.mediaId == mediaId
        val hasBoundConsumer = hasLiveInAppModelTarget(mediaId)
        val surfaceRelevant = isCurrentMetadataSurfaceMediaId(mediaId)
        val hasVisibleExactConsumer = hasVisibleInAppConsumer(mediaId)
        val hasActiveVisibleLease = visibleMetadataResolutionLeases.contains(mediaId)
        val allowModelRefresh = shouldRefreshInAppSurface(
            surfaceRelevant = surfaceRelevant,
            hasVisibleExactConsumer = hasVisibleExactConsumer,
            hasActiveVisibleLease = hasActiveVisibleLease,
        ) &&
            shouldNotifyInAppModelChange(
                mediaId = mediaId,
                activeMediaId = identity.mediaId,
                hasBoundConsumer = hasBoundConsumer,
            )
        if (BuildConfig.DEBUG) {
            logMetadataIdentity(
                event = "model_refresh_policy",
                identity = identity,
                details = "overrideId=$mediaId, entityType=${playbackMetadataEntityTypes[mediaId]}, " +
                    "forceInAppRebind=$shouldForceInAppRebind, " +
                    "requestedForceInAppRebind=$forceInAppRebind, " +
                    "allowModelRefresh=$allowModelRefresh, " +
                    "surfaceRelevant=$surfaceRelevant, " +
                    "visibleExactConsumer=$hasVisibleExactConsumer, " +
                    "visibleLease=$hasActiveVisibleLease, " +
                    "hasBoundConsumer=$hasBoundConsumer, " +
                    "epoxyRefs=${inAppLibraryControllerRefs[mediaId]?.size ?: 0}, " +
                    "composeRefs=${inAppLibraryComposeStateRefs[mediaId]?.size ?: 0}, " +
                    "dataBindingRefs=${inAppDataBindingRefs[mediaId]?.size ?: 0}",
            )
        }
        if (currentPlaybackMetadataId == mediaId) {
            currentPlaybackMetadataOverride = effectiveAlias
        }
        MediaMetadataCache.updateDisplayMetadata(mediaId, effectiveAlias.title, effectiveAlias.artist)
        PlaybackManager.onCatalogMetadataResolved(mediaId)
        // Listen Now is built from a one-time Epoxy model snapshot. Its bound listener is
        // an exact consumer even when the generic page-scope coordinator has expired or
        // does not classify the Home surface as an active metadata page. Keep this narrow
        // direct path so a late cache/catalog result updates the card in place.
        val listenNowDirectBindingTargets = if (
            !allowModelRefresh &&
            inAppListenNowDataBindingRefs[mediaId]?.isNotEmpty() == true
        ) {
            refreshInAppListenNowDataBindingRefs(mediaId, effectiveAlias)
        } else {
            0
        }
        if (allowModelRefresh) {
            applyAliasToInAppMetadataRefs(
                mediaId = mediaId,
                alias = effectiveAlias,
                forceRebind = shouldForceInAppRebind,
                notifyModelChange = true,
            )
        }
        if (appliesToActivePlayback) {
            applyAliasToInAppActionSheetBindings(mediaId, effectiveAlias)
            if (BuildConfig.DEBUG) {
                mainHandler.post {
                    debugScanVisibleMetadataViews("active_alias_applied")
                }
            }
        }

        logMetadataIdentity(
            event = "override_applied",
            identity = identity,
            details = "overrideId=$mediaId, active=$appliesToActivePlayback, " +
                "effective=${effectiveAlias.title}/${effectiveAlias.artist}/${effectiveAlias.album}, " +
                "original=$originalMetadata, confirmed=$originalMetadataConfirmed, " +
                "artistOnly=$artistOnly, " +
                "changed=${previousEffective != effectiveAlias}, " +
                "listenNowDirectBindingTargets=$listenNowDirectBindingTargets, " +
                "metadataRefs=${inAppMetadataRefs[mediaId]?.size ?: 0}, " +
                "itemRefs=${inAppPlaybackItemRefs[mediaId]?.size ?: 0}, " +
                "containerRefs=${inAppContainerItemRefs[mediaId]?.size ?: 0}",
        )
        if (previousEffective == effectiveAlias) {
            if (appliesToActivePlayback) refreshInAppMetadataViews(mediaId, effectiveAlias)
            return
        }

        ProviderLogger.info(
            "Apple 播放元数据已覆盖: id=$mediaId, " +
                "title=${effectiveAlias.title}, artist=${effectiveAlias.artist}, " +
                "language=${effectiveAlias.language}, original=$originalMetadata, " +
                "confirmed=$originalMetadataConfirmed, artistOnly=$artistOnly"
        )
        currentPlaybackMetadataRefresh
            ?.takeIf { it.mediaId == mediaId }
            ?.refresh
            ?.invoke()
        refreshFrameworkMediaSessionMetadata(mediaId, effectiveAlias)
        refreshFrameworkMediaSessionQueue(mediaId)
        refreshInAppMetadataViews(mediaId, effectiveAlias)
    }

    private fun propagateSharedArtistOverride(
        sourceMediaId: String,
        artistId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean,
        rememberLocalizedArtist: Boolean,
        originalMetadata: Boolean,
        originalMetadataConfirmed: Boolean,
    ) {
        val previousSharedAlias = if (originalMetadata) {
            sharedOriginalArtistOverrides.put(artistId, alias)
        } else {
            sharedLocalizedArtistOverrides.put(
                localizedArtistOverrideKey(configuredContentUiLanguage(), artistId),
                alias,
            )
        }
        val targets = linkedSetOf(sourceMediaId, artistId).apply {
            addAll(associatedMediaIdsByArtistKey["id:$artistId"].orEmpty())
        }
        targets.forEach { targetMediaId ->
            if (
                targetMediaId != artistId &&
                !shouldShareAssociatedArtistAlias(
                    artistId = artistId,
                    targetArtistIds =
                        playbackMetadataAssociatedArtistIds[targetMediaId].orEmpty(),
                    targetArtistCredit = associatedArtistCredit(targetMediaId),
                )
            ) return@forEach
            applyPlaybackMetadataOverride(
                mediaId = targetMediaId,
                alias = alias,
                forceInAppRebind = forceInAppRebind ||
                    previousSharedAlias != alias ||
                    targetMediaId != sourceMediaId,
                rememberLocalizedArtist = rememberLocalizedArtist,
                originalMetadata = originalMetadata,
                originalMetadataConfirmed = originalMetadataConfirmed,
                artistOnly = true,
                propagateArtistEntity = false,
            )
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: " +
                    "seq=${metadataTraceSequence.incrementAndGet()}, " +
                    "event=artist_id_alias_propagated, artistId=$artistId, " +
                    "sourceId=$sourceMediaId, targets=$targets, " +
                    "artist=${alias.artist}, original=$originalMetadata"
            )
        }
    }

    private fun configuredContentUiLanguage(): Int {
        val prefs = contentUiLanguagePrefs
        return prefs?.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE,
        ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
    }

    private fun cachedOriginalMetadataAlias(
        metadata: MediaMetadataCache.Metadata,
    ): AppleInternalCatalogResolver.Alias? {
        val title = metadata.originalTitle?.takeIf(String::isNotBlank)
        val artist = metadata.originalArtist?.takeIf(String::isNotBlank)
        if (title == null && artist == null) return null
        return AppleInternalCatalogResolver.Alias(
            title = title ?: metadata.title.orEmpty(),
            artist = artist ?: metadata.artist.orEmpty(),
            language = "original",
        )
    }

    private fun shouldOverrideAccountLanguage(selection: Int): Boolean {
        if (selection == RootConstants.APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE) return false
        return contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE,
        ) == true
    }

    private fun shouldRestoreCjkOriginalMetadata(
        metadata: MediaMetadataCache.Metadata,
    ): Boolean = isRestoreCjkOriginalMetadataEnabled() &&
        AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
            mediaId = metadata.id,
            title = metadata.title,
            artist = metadata.artist,
            genre = metadata.genre,
        )

    private fun shouldRetryOriginalMetadataCacheProbe(mediaId: String): Boolean =
        shouldRetryOriginalMetadataCacheProbe(
            originalResolved = mediaId in originalMetadataResolvedIds,
            lastMissUptimeMillis = originalMetadataCacheMissUptimeMillis[mediaId],
            nowUptimeMillis = SystemClock.uptimeMillis(),
        )

    internal fun shouldRetryOriginalMetadataCacheProbe(
        originalResolved: Boolean,
        lastMissUptimeMillis: Long?,
        nowUptimeMillis: Long,
        retryAfterMillis: Long = ORIGINAL_METADATA_CACHE_MISS_RETRY_MS,
    ): Boolean {
        if (!originalResolved) return true
        val lastMiss = lastMissUptimeMillis ?: return false
        return nowUptimeMillis >= lastMiss + retryAfterMillis
    }

    private fun shouldRequestInAppMetadataOverride(mediaId: String): Boolean {
        val associatedArtistIds = playbackMetadataAssociatedArtistIds[mediaId].orEmpty()
        return shouldRequestEffectiveMetadataResolution(
            restoreOriginalEnabled = isRestoreCjkOriginalMetadataEnabled(),
            originalMetadataResolved = mediaId in originalMetadataResolvedIds &&
                !shouldRetryOriginalMetadataCacheProbe(mediaId),
            hasOriginalMetadata = originalMetadataOverrides.containsKey(mediaId),
            hasAssociatedArtists = shouldUseAssociatedArtistEntities(
                artistIds = associatedArtistIds,
                artistCredit = associatedArtistCredit(mediaId),
            ),
            originalArtistResolved = mediaId in originalArtistResolvedIds,
            hasLocalizedMetadata = playbackMetadataOverrides.containsKey(mediaId),
        )
    }

    private fun isRestoreCjkOriginalMetadataEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
        RootConstants.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA,
    ) == true

    private fun isSimplifyTraditionalLyricsEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_LYRICS,
        ) == true

    private fun isFollowSystemFontWeightEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT,
        ) == true

    private fun ensureContentItemMetadataHooks(contentItemClass: Class<*>) {
        listOf(
            "getTitle",
            "getNowPlayingTitle",
            "getArtistName",
            "getNowPlayingSubtitle",
            "getSubTitle",
            "getCollectionName",
        ).forEach { methodName ->
            val method = runCatching {
                AppleReflection.findMethod(contentItemClass, methodName, parameterCount = 0)
            }.getOrNull() ?: return@forEach
            if (method.returnType != String::class.java || !playbackMetadataHookedMethods.add(method)) {
                return@forEach
            }
            hookRegistrar.installResultOverride(method) { chain, original ->
                if (internalContentItemGetterGuard.isActive) {
                    return@installResultOverrideHook original
                }
                val contentItem = chain.thisObject ?: return@installResultOverrideHook original
                val containerBinding = inAppContainerNavigationBinding(contentItem)
                if (containerBinding != null) {
                    val mediaId = containerBinding.mediaId
                    val containerKind = containerBinding.kind
                    val alias = effectiveInAppMetadataOverride(mediaId)
                    registerInAppContainerItem(mediaId, contentItem, containerKind)
                    if (methodName != "getTitle" || alias == null) {
                        return@installResultOverrideHook original
                    }
                    return@installResultOverrideHook when (containerKind) {
                        InAppContainerKind.ARTIST ->
                            alias.artist.takeIf(String::isNotBlank) ?: original
                        InAppContainerKind.ALBUM ->
                            alias.album.takeIf(String::isNotBlank) ?: original
                    }
                }
                val entityType = contentItemLocalizedEntityType(contentItem)
                    ?: return@installResultOverrideHook original
                val mediaId = runCatching { contentItemMediaId(contentItem) }.getOrNull()
                    ?: return@installResultOverrideHook original
                recordInAppLibraryComposeMediaId(mediaId)
                recordCurrentRecyclerMediaId(mediaId)
                val requestPriority = requestPriorityForMediaId(mediaId)
                val surfaceRelevant = shouldResolveMetadataFromGetter(requestPriority)
                val resolvedAlias = effectiveInAppMetadataOverride(mediaId)
                registerInAppPlaybackItem(
                    mediaId = mediaId,
                    playbackItem = contentItem,
                    notifyChange = false,
                    analyzeMetadata = surfaceRelevant && (
                        resolvedAlias == null ||
                            shouldRequestInAppMetadataOverride(mediaId)
                        ),
                )
                if (resolvedAlias != null) {
                    applyAliasToInAppPlaybackItem(
                        playbackItem = contentItem,
                        alias = resolvedAlias,
                        notifyChange = false,
                    )
                }
                val alias = resolvedAlias ?: effectiveInAppMetadataOverride(mediaId)
                val overridden = if (alias == null) {
                    original
                } else {
                    contentItemMetadataOverride(
                        entityType = entityType,
                        getter = methodName,
                        alias = alias,
                        original = original as? String,
                    ) ?: original
                }
                overridden
            }
            ProviderLogger.info(
                "Apple 内容项元数据 getter Hook 已安装: " +
                    "class=${method.declaringClass.name}, method=$methodName"
            )
        }
    }

    internal fun shouldResolveMetadataFromGetter(
        priority: AppleInternalCatalogResolver.RequestPriority,
    ): Boolean = priority == AppleInternalCatalogResolver.RequestPriority.VISIBLE

    private fun isCurrentQueueItem(candidate: Any?, current: Any?): Boolean {
        if (candidate == null || current == null) return false
        if (candidate === current) return true
        val candidateQueueId = AppleReflection.call(candidate, "getPlaybackQueueId") as? Long ?: 0L
        val currentQueueId = AppleReflection.call(current, "getPlaybackQueueId") as? Long ?: 0L
        if (candidateQueueId > 0L && currentQueueId > 0L) {
            return candidateQueueId == currentQueueId
        }
        val candidateMediaId = queueItemMediaId(candidate)
        val currentMediaId = queueItemMediaId(current)
        return candidateMediaId != null && candidateMediaId == currentMediaId
    }

    private fun queueItemMediaId(queueItem: Any): String? {
        val mediaItem = AppleReflection.call(queueItem, "getItem") ?: return null
        return mediaItemId(mediaItem)
    }

    private fun currentPlaybackQueueMediaId(): String? {
        val currentQueueItem = activePlaybackPlayer?.let { player ->
            runCatching { AppleReflection.call(player, "getCurrentItem") }.getOrNull()
        }
        return currentQueueItem
            ?.let { queueItem -> runCatching { queueItemMediaId(queueItem) }.getOrNull() }
            ?: currentPlaybackMetadataId
    }

    private fun mediaItemId(mediaItem: Any): String? {
        val subscriptionStoreId =
            AppleReflection.call(mediaItem, "getSubscriptionStoreId") as? String
        if (!subscriptionStoreId.isNullOrBlank()) return subscriptionStoreId
        val persistentId = AppleReflection.call(mediaItem, "getPersistentId") as? Long ?: 0L
        return persistentId.takeIf { it > 0L }?.toString()
    }

    private fun refreshAppleLyricsDisplay() {
        mainHandler.post {
            val method = appleLyricsLoadMethod ?: return@post
            val viewModel = appleLyricsViewModelRef?.get() ?: return@post
            val item = appleLyricsItemRef?.get() ?: return@post
            runCatching {
                method.invoke(viewModel, item)
            }.onFailure {
                ProviderLogger.error("Apple Music 当前歌词页刷新失败", it)
            }
        }
    }

    private fun receiveNativeOnlineTranslation(compressedSong: ByteArray) {
        coroutineScope.launch {
            val song = runCatching {
                json.decodeFromString<LocalSong>(
                    compressedSong.inflate().toString(Charsets.UTF_8)
                )
            }.onFailure {
                ProviderLogger.error("Apple Music 原生在线翻译解析失败", it)
            }.getOrNull() ?: return@launch

            mainHandler.post {
                val romanizedLineCount = song.lyrics.orEmpty().count {
                    !it.roma.isNullOrBlank()
                }
                reportApplePronunciationRuntimeDiagnostic(
                    stage = "payload_callback_decoded",
                    songId = song.id,
                    details = "lyrics=${song.lyrics.orEmpty().size}, " +
                        "romanizedLines=$romanizedLineCount, " +
                        "featureEnabled=${isNativeOnlineTranslationEnabled()}",
                )
                if (!isNativeOnlineTranslationEnabled()) return@post
                if (nativeOnlineTranslationStore.update(song)) {
                    val revision = nativeOnlineTranslationStore.revision()
                    clearPendingApplePronunciationRenderPlans()
                    ProviderLogger.info(
                        "Apple Music 原生在线翻译已接收: id=${song.id}, " +
                            "translatedLines=${song.lyrics.orEmpty().count {
                                !it.translation.isNullOrBlank()
                            }}, romanizedLines=${song.lyrics.orEmpty().count {
                                !it.roma.isNullOrBlank()
                            }}"
                    )
                    song.id?.let(::resolvePendingOnlineSourceMenuSwitches)
                    song.id?.let(::refreshActiveOnlineSourceMenu)
                    refreshAppleLyricsSupplementPresentation(
                        expectedSongId = song.id,
                        expectedRevision = revision,
                    )
                }
            }
        }
    }

    private fun clearNativeOnlineTranslation(songId: String?) {
        mainHandler.post {
            if (nativeOnlineTranslationStore.clear(songId)) {
                clearPendingApplePronunciationRenderPlans()
                ProviderLogger.debug("Apple Music 原生在线翻译已清除: id=$songId")
                songId?.let(::refreshActiveOnlineSourceMenu)
                refreshAppleLyricsSupplementPresentation()
            }
        }
    }

    private fun refreshAppleLyricsSupplementPresentation(
        expectedSongId: String? = null,
        expectedRevision: Long? = null,
        deferWhileSourceMenuShowing: Boolean = true,
    ) {
        mainHandler.post {
            val activeMenu = activeOnlineSourceMenu
            if (
                deferWhileSourceMenuShowing &&
                shouldDeferNativeTranslationPresentationRefresh(
                    activeMenuSongId = activeMenu?.songId,
                    popupShowing = activeMenu?.popup?.get()?.isShowing == true,
                    expectedSongId = expectedSongId,
                )
            ) {
                deferNativeTranslationPresentationRefresh(
                    expectedSongId = expectedSongId ?: requireNotNull(activeMenu).songId,
                    expectedRevision = expectedRevision,
                )
                return@post
            }
            if (activeMenu?.popup?.get()?.isShowing != true) {
                activeOnlineSourceMenu = null
            }
            val method = appleLyricsPresentationMethod ?: return@post
            val fragment = appleLyricsFragmentRef?.get() ?: return@post
            val pointer = appleLyricsSongPointerRef?.get() ?: return@post
            val songNative = runCatching {
                AppleReflection.call(pointer, "get")
            }.getOrNull() ?: return@post
            val currentSongId = nativeSongId(songNative)
            if (!expectedSongId.isNullOrBlank() && expectedSongId != currentSongId) {
                ProviderLogger.debug(
                    "跳过非当前 Apple Music 在线翻译页面刷新: " +
                        "expected=$expectedSongId, current=$currentSongId"
                )
                return@post
            }
            if (
                expectedRevision != null &&
                !nativeOnlineTranslationStore.isCurrentRevision(
                    songId = currentSongId,
                    revision = expectedRevision,
                )
            ) {
                ProviderLogger.debug(
                    "跳过过期 Apple Music 在线翻译页面刷新: " +
                        "id=$currentSongId, revision=$expectedRevision"
                )
                return@post
            }
            if (currentAppleLyricsSongId != currentSongId) {
                clearPendingApplePronunciationRenderPlans()
                currentAppleLyricsSongId = currentSongId
            }
            ensureAppleLyricTextHooks(songNative)
            applyAppleNativeSupplementSelection(songNative)
            runCatching {
                method.invoke(fragment, pointer)
            }.onSuccess {
                refreshAppleLyricsRecyclerView(
                    fragment = fragment,
                    expectedSongId = currentSongId,
                    expectedRevision = expectedRevision,
                )
                ProviderLogger.debug(
                    "Apple Music 在线翻译页面已轻量刷新: " +
                        "id=$currentSongId, revision=${expectedRevision ?: "none"}"
                )
            }.onFailure {
                ProviderLogger.error("Apple Music 在线翻译页面轻量刷新失败", it)
            }
        }
    }

    private fun deferNativeTranslationPresentationRefresh(
        expectedSongId: String,
        expectedRevision: Long?,
    ) {
        deferredNativeTranslationRefreshSongId = expectedSongId
        deferredNativeTranslationRefreshRevision = expectedRevision
        if (deferredNativeTranslationRefreshScheduled) return
        deferredNativeTranslationRefreshScheduled = true
        mainHandler.postDelayed(
            {
                deferredNativeTranslationRefreshScheduled = false
                val deferredSongId = deferredNativeTranslationRefreshSongId
                    ?: return@postDelayed
                val deferredRevision = deferredNativeTranslationRefreshRevision
                val popupStillShowing = activeOnlineSourceMenu
                    ?.takeIf { it.songId == deferredSongId }
                    ?.popup
                    ?.get()
                    ?.isShowing == true
                if (popupStillShowing) {
                    deferNativeTranslationPresentationRefresh(
                        expectedSongId = deferredSongId,
                        expectedRevision = deferredRevision,
                    )
                    return@postDelayed
                }
                deferredNativeTranslationRefreshSongId = null
                deferredNativeTranslationRefreshRevision = null
                refreshAppleLyricsSupplementPresentation(
                    expectedSongId = deferredSongId,
                    expectedRevision = deferredRevision,
                    deferWhileSourceMenuShowing = false,
                )
            },
            100L,
        )
    }

    internal fun shouldDeferNativeTranslationPresentationRefresh(
        activeMenuSongId: String?,
        popupShowing: Boolean,
        expectedSongId: String?,
    ): Boolean =
        popupShowing &&
            !activeMenuSongId.isNullOrBlank() &&
            (expectedSongId.isNullOrBlank() || activeMenuSongId == expectedSongId)

    private fun isNativeOnlineTranslationEnabled(): Boolean {
        val prefs = contentUiLanguagePrefs ?: return false
        return prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
        ) && prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION,
        )
    }

    private fun isHideMandarinPinyinEnabled(): Boolean {
        val prefs = contentUiLanguagePrefs ?: return false
        return prefs.getBoolean(
            RootConstants.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN,
        )
    }

    private fun appleLyricsBlurMode(): Int {
        val prefs = contentUiLanguagePrefs
        val configured = prefs?.getInt(
            RootConstants.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
            RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
        ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT
        return AppleLyricsBlurPolicy.normalizeMode(configured)
    }

    private fun appleLyricsBlurRadiusRange(mode: Int): ClosedFloatingPointRange<Float> {
        val prefs = contentUiLanguagePrefs
        val (configuredMin, configuredMax, allowedMin, allowedMax) =
            if (mode == AppleLyricsBlurPolicy.NATIVE) {
                listOf(
                    prefs?.getFloat(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
                    prefs?.getFloat(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
                    RootConstants.MIN_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
                    RootConstants.MAX_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_RADIUS_DP,
                )
            } else {
                listOf(
                    (prefs?.getInt(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX)
                        .toFloat(),
                    (prefs?.getInt(
                        RootConstants.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX,
                        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX,
                    ) ?: RootConstants.DEFAULT_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX)
                        .toFloat(),
                    RootConstants.MIN_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX.toFloat(),
                    RootConstants.MAX_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_RADIUS_PX.toFloat(),
                )
            }
        val boundedMin = configuredMin.coerceIn(allowedMin, allowedMax)
        val boundedMax = configuredMax.coerceIn(allowedMin, allowedMax)
        return minOf(boundedMin, boundedMax)..maxOf(boundedMin, boundedMax)
    }

    private fun shouldHideMandarinPronunciation(
        songId: String? = null,
        pronunciationLanguages: Collection<String> = emptyList(),
        lyricObject: Any? = null,
    ): Boolean {
        val lyricContext = lyricObject?.let(applePronunciationContextByLyricObject::get)
        val resolvedSongId = songId ?: lyricContext?.songId ?: currentAppleLyricsSongId
        val genre = resolvedSongId?.let { id ->
            sequenceOf(MediaMetadataCache.getMetadataById(id)?.genre)
                .plus(
                    if (::internalCatalogResolver.isInitialized) {
                        internalCatalogResolver.cachedCatalogGenres(id).asSequence()
                    } else {
                        emptySequence()
                    }
                )
                .filterNotNull()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .joinToString(", ")
                .takeIf(String::isNotEmpty)
        }
        val resolvedPronunciationLanguages = buildList {
            addAll(pronunciationLanguages)
            addAll(lyricContext?.pronunciationLanguages.orEmpty())
            resolvedSongId?.let { id ->
                addAll(applePronunciationLanguagesBySongId[id].orEmpty())
            }
        }.map(String::trim).filter(String::isNotEmpty).distinct()
        return ApplePronunciationVisibilityPolicy.shouldHide(
            genre = genre,
            pronunciationLanguages = resolvedPronunciationLanguages,
            hideMandarinPinyin = isHideMandarinPinyinEnabled(),
        )
    }

    private fun ensureAppleLyricTextHooks(songNative: Any) {
        val songId = nativeSongId(songNative)
        val pronunciationLanguages = nativePronunciationLanguages(songNative)
        rememberApplePronunciationLanguages(songNative, pronunciationLanguages)
        ensureAppleNativeOnlineTranslationHooks(songNative)
        val sections = runCatching {
            AppleReflection.call(songNative, "getSections")
        }.getOrNull() ?: return
        val lines = nativeVectorItems(sections, limit = 8).flatMap { section ->
            val lineVector = runCatching {
                AppleReflection.call(section, "getLines")
            }.getOrNull()
            nativeVectorItems(lineVector, limit = 16)
        }
        if (lines.isEmpty()) return
        val pronunciationContext = ApplePronunciationContext(
            songId = songId,
            pronunciationLanguages = pronunciationLanguages,
        )
        lines.forEach { line ->
            applePronunciationContextByLyricObject[line] = pronunciationContext
        }
        logApplePronunciationDiagnostics(songNative, lines)

        val textGetterNames = listOf(
            "getHtmlLineText",
            "getHtmlTranslationLineText",
            "getHtmlPronunciationLineText",
            "getHtmlBackgroundVocalsLineText",
            "getHtmlTranslatedBackgroundVocalsLineText",
            "getHtmlPronunciationBackgroundVocalsLineText",
        )
        lines.map(Any::javaClass).distinct().forEach { lineClass ->
            textGetterNames.forEach { name -> hookAppleLyricTextGetter(lineClass, name) }
            hookApplePronunciationWordsGetters(lineClass)
        }

        val words = lines.flatMap { line ->
            buildList {
                runCatching { AppleReflection.call(line, "getWords") }
                    .getOrNull()
                    ?.let { addAll(nativeVectorItems(it, limit = 8)) }
                val backgroundWords = runCatching {
                    AppleReflection.call(line, "getBackgroundWords", false)
                }.recoverCatching {
                    AppleReflection.call(line, "getBackgroundWords")
                }.getOrNull()
                backgroundWords?.let { addAll(nativeVectorItems(it, limit = 8)) }
            }
        }
        words.map(Any::javaClass).distinct().forEach { wordClass ->
            hookAppleLyricTextGetter(wordClass, "getHtmlLineText")
        }
    }

    private fun hookAppleLyricTextGetter(clazz: Class<*>, name: String) {
        val method = runCatching {
            AppleReflection.findMethod(clazz, name, parameterCount = 0)
        }.getOrNull() ?: return
        if (method.returnType != String::class.java || !lyricDisplayTextHookedMethods.add(method)) {
            return
        }
        hookRegistrar.installResultOverride(method) { chain, original ->
            val originalText = original as? String
            if (AppleLyricTextTransform.isRawReadActive()) {
                return@installResultOverrideHook AppleLyricTextTransform.transform(originalText)
                    ?: original
            }
            // 补全发音复用 Apple 主句原生 word；只在发音渲染调用栈内替换其显示文本。
            // 这样 word 仍保留原生父 LyricsLine、lineId、wordId 与主句时间轴。
            val scopedPronunciationText = applePronunciationWordRenderContexts.current
                ?.displayText(chain.thisObject)
            if (scopedPronunciationText != null) {
                return@installResultOverrideHook AppleLyricTextTransform.transform(
                    scopedPronunciationText
                ) ?: scopedPronunciationText
            }
            when (name) {
                "getHtmlTranslationLineText" -> {
                    val text = AppleNativeOnlineTranslationStore.sanitizeContent(originalText)
                        ?: onlineTranslationForNativeLine(chain.thisObject)
                    AppleLyricTextTransform.transform(text) ?: original
                }
                "getHtmlPronunciationLineText" -> {
                    if (shouldHideMandarinPronunciation(lyricObject = chain.thisObject)) {
                        return@installResultOverrideHook ""
                    }
                    val officialText = RomanizationPolicy.sanitize(
                        originalText = nativeOriginalLineText(chain.thisObject),
                        pronunciation = originalText,
                    )
                    val onlineText = if (officialText == null) {
                        onlinePronunciationForNativeLine(chain.thisObject)
                    } else {
                        null
                    }
                    if (onlineText != null) {
                        reportApplePronunciationRuntimeDiagnostic(
                            stage = "line_text_overlay",
                            details = nativeLineDiagnosticDetails(
                                line = chain.thisObject,
                                pronunciation = onlineText,
                            ),
                        )
                    }
                    val text = officialText ?: onlineText
                    val displayText = ApplePronunciationPolicy.nonNullDisplayText(
                        AppleLyricTextTransform.transform(text)
                    )
                    logApplePronunciationLineBinding(
                        line = chain.thisObject,
                        originalPronunciation = originalText,
                        officialPronunciation = officialText,
                        onlinePronunciation = onlineText,
                        displayText = displayText,
                    )
                    displayText
                }
                "getHtmlPronunciationBackgroundVocalsLineText" -> {
                    if (shouldHideMandarinPronunciation(lyricObject = chain.thisObject)) {
                        return@installResultOverrideHook ""
                    }
                    val text = RomanizationPolicy.sanitize(
                        originalText = nativeOriginalBackgroundLineText(chain.thisObject),
                        pronunciation = originalText,
                    )
                    ApplePronunciationPolicy.nonNullDisplayText(
                        AppleLyricTextTransform.transform(text)
                    )
                }
                else -> AppleLyricTextTransform.transform(originalText) ?: original
            }
        }
        ProviderLogger.debug("Apple Music 歌词文本转换 Hook 已安装: ${clazz.name}#$name")
    }

    private fun hookApplePronunciationWordsGetters(clazz: Class<*>) {
        hookApplePronunciationWordsGetter(
            clazz = clazz,
            methodName = "getPronunciationWords",
            parameterCount = 0,
            originalTextGetter = "getHtmlLineText",
            pronunciationTextGetter = "getHtmlPronunciationLineText",
            mainWordsGetter = "getWords",
            onlineFallback = true,
        )
        hookApplePronunciationWordsGetter(
            clazz = clazz,
            methodName = "getPronunciationBackgroundWords",
            parameterCount = 1,
            originalTextGetter = "getHtmlBackgroundVocalsLineText",
            pronunciationTextGetter = "getHtmlPronunciationBackgroundVocalsLineText",
            mainWordsGetter = "getBackgroundWords",
            onlineFallback = false,
        )
    }

    /**
     * Hook Apple 的两个逐词布局构建方法。补全发音传入的是主句原生 word vector，
     * 此处只在该 vector 被消费的调用栈内替换显示文本，退出后立即恢复主句原文。
     */
    private fun hookApplePronunciationWordRendering() {
        val wordVectorClassName =
            "com.apple.android.music.ttml.javanative.model.LyricsWordVector"
        hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER)
            .forEach { resolvedClass ->
                val adapterClass = resolvedClass.clazz
                val renderMethods = generateSequence(adapterClass) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .filter { method ->
                        method.parameterTypes.firstOrNull()?.name == wordVectorClassName &&
                            method.returnType.name == "android.util.ArrayMap"
                    }
                    .distinctBy { method ->
                        method.name to method.parameterTypes.joinToString { it.name }
                    }
                    .toList()
                renderMethods.forEach { method ->
                    if (!applePronunciationRenderHookedMethods.add(method)) return@forEach
                    method.isAccessible = true
                    hookRegistrar.installScoped(
                        executable = method,
                        enter = enter@{ chain ->
                            val vector = chain.args.firstOrNull() ?: return@enter false
                            val plan = consumeApplePronunciationRenderPlan(vector)
                            if (plan == null) {
                                if (
                                    nativeOnlineTranslationStore.hasPronunciation(
                                        currentAppleLyricsSongId
                                    ) && nativeVectorSize(vector) > 0
                                ) {
                                    reportApplePronunciationRuntimeDiagnostic(
                                        stage = "render_plan_miss",
                                        details = "method=${method.declaringClass.name}#" +
                                            "${method.name}/${method.parameterCount}, " +
                                            "vector=${System.identityHashCode(vector)}, " +
                                            "words=${nativeVectorSize(vector)}, " +
                                            "args=${applePronunciationRenderArgumentSummary(chain.args)}",
                                        dedupeKey = "render_plan_miss:${method.name}:" +
                                            method.parameterCount,
                                    )
                                }
                                return@enter false
                            }
                            val context = buildApplePronunciationWordRenderContext(vector, plan)
                                ?: return@enter false
                            reportApplePronunciationRuntimeDiagnostic(
                                stage = "render_plan_consumed",
                                details = "method=${method.declaringClass.name}#" +
                                    "${method.name}/${method.parameterCount}, " +
                                    "vector=${System.identityHashCode(vector)}, " +
                                    "words=${nativeVectorSize(vector)}, " +
                                    "pronunciationChars=${plan.pronunciation.length}, " +
                                    "args=${applePronunciationRenderArgumentSummary(chain.args)}",
                                dedupeKey = "render_plan_consumed:${method.name}:" +
                                    method.parameterCount,
                            )
                            applePronunciationWordRenderContexts.push(context)
                            true
                        },
                        after = { _, _ -> Unit },
                        exit = { applePronunciationWordRenderContexts.pop() },
                    )
                    ProviderLogger.debug(
                        "Apple Music 发音主句时间轴渲染 Hook 已安装: " +
                            "${method.declaringClass.name}#${method.name}/${method.parameterCount}"
                    )
                }
            }
    }

    private fun hookApplePronunciationWordsGetter(
        clazz: Class<*>,
        methodName: String,
        parameterCount: Int,
        originalTextGetter: String,
        pronunciationTextGetter: String,
        mainWordsGetter: String,
        onlineFallback: Boolean,
    ) {
        val method = runCatching {
            AppleReflection.findMethod(clazz, methodName, parameterCount = parameterCount)
        }.getOrNull() ?: return
        if (!nativeOnlineTranslationHookedMethods.add(method)) return

        hookRegistrar.installResultOverride(method) { chain, original ->
            if (AppleLyricTextTransform.isRawReadActive()) {
                return@installResultOverrideHook original
            }
            val line = chain.thisObject ?: return@installResultOverrideHook original
            if (shouldHideMandarinPronunciation(lyricObject = line)) {
                return@installResultOverrideHook emptyApplePronunciationWords(
                    originalVector = original,
                    mainWords = null,
                ) ?: original
            }
            val originalText = nativeRawLineText(line, originalTextGetter)
            val officialPronunciation = RomanizationPolicy.sanitize(
                originalText = originalText,
                pronunciation = nativeRawLineText(line, pronunciationTextGetter),
            )
            val onlinePronunciation = if (onlineFallback) {
                onlinePronunciationForNativeLine(line)
            } else {
                null
            }
            val hasValidOfficialWords = officialPronunciation != null &&
                RomanizationPolicy.sanitize(
                    originalText = originalText,
                    pronunciation = nativeRawWordVectorText(original),
                ) != null
            val mainWords = runCatching {
                AppleReflection.call(
                    line,
                    mainWordsGetter,
                    *chain.args.toTypedArray(),
                )
            }.getOrNull()
            val hasCompatibleOfficialWords = hasValidOfficialWords &&
                ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                    mainWordBegins = nativeRenderableWordBegins(mainWords),
                    pronunciationWordBegins = nativeRenderableWordBegins(original),
                )
            val mainTimingPronunciation = when {
                officialPronunciation != null && !hasCompatibleOfficialWords -> {
                    reportApplePronunciationRuntimeDiagnostic(
                        stage = "official_word_timing_fallback",
                        details = nativeLineDiagnosticDetails(
                            line = line,
                            pronunciation = officialPronunciation,
                        ) + ", method=$methodName/$parameterCount, " +
                            "mainBegins=${nativeRenderableWordBegins(mainWords)}, " +
                            "officialBegins=${nativeRenderableWordBegins(original)}",
                        dedupeKey = "official_word_timing_fallback:$methodName:" +
                            runCatching { AppleReflection.call(line, "getBegin") }
                                .getOrNull(),
                    )
                    officialPronunciation
                }
                else -> onlinePronunciation
            }
            val wordTrack = ApplePronunciationPolicy.wordTrack(
                hasValidOfficialPronunciation = hasCompatibleOfficialWords,
                hasOnlinePronunciation = mainTimingPronunciation != null,
            )
            val resolvedWords = when (wordTrack) {
                ApplePronunciationWordTrack.OFFICIAL -> original
                ApplePronunciationWordTrack.MAIN_LINE_TIMING -> {
                    mainWords?.takeIf { nativeVectorSize(it) > 0 }?.also { vector ->
                        registerApplePronunciationRenderPlan(
                            vector = vector,
                            pronunciation = requireNotNull(mainTimingPronunciation),
                        )
                        reportApplePronunciationRuntimeDiagnostic(
                            stage = "word_track_registered",
                            details = nativeLineDiagnosticDetails(
                                line = line,
                                pronunciation = mainTimingPronunciation,
                            ) + ", method=$methodName/$parameterCount, " +
                                "vector=${System.identityHashCode(vector)}, " +
                                "words=${nativeVectorSize(vector)}, track=$wordTrack",
                            dedupeKey = "word_track_registered:$methodName",
                        )
                    } ?: emptyApplePronunciationWords(
                        originalVector = original,
                        mainWords = mainWords,
                    ) ?: original
                }
                ApplePronunciationWordTrack.HIDDEN -> emptyApplePronunciationWords(
                    originalVector = original,
                    mainWords = null,
                ) ?: original
            }
            logApplePronunciationWordBinding(
                line = line,
                methodName = methodName,
                wordTrack = wordTrack,
                originalWords = original,
                resolvedWords = resolvedWords,
                officialPronunciation = officialPronunciation,
                onlinePronunciation = onlinePronunciation,
            )
            resolvedWords
        }
        ProviderLogger.debug(
            "Apple Music 发音逐词轨道 Hook 已安装: ${clazz.name}#$methodName/$parameterCount"
        )
    }

    private fun logApplePronunciationDiagnostics(songNative: Any, lines: List<Any>) {
        if (!BuildConfig.DEBUG) return
        val songId = nativeSongId(songNative) ?: return

        val languages = runCatching {
            nativeVectorItems(
                AppleReflection.call(songNative, "getPronunciationLanguages"),
                limit = 16,
            ).map(Any::toString).filter(String::isNotBlank)
        }.getOrDefault(emptyList())
        val nativeTextLines = AppleLyricTextTransform.withRawReads {
            lines.count { line ->
                runCatching {
                    AppleNativeOnlineTranslationStore.sanitizeContent(
                        AppleReflection.call(line, "getHtmlPronunciationLineText") as? String
                    ) != null
                }.getOrDefault(false)
            }
        }
        val nativeWordLines = AppleLyricTextTransform.withRawReads {
            lines.count { line ->
                runCatching {
                    nativeVectorSize(AppleReflection.call(line, "getPronunciationWords")) > 0
                }.getOrDefault(false)
            }
        }
        val mainWordLines = lines.count { line ->
            runCatching { nativeVectorSize(AppleReflection.call(line, "getWords")) > 0 }
                .getOrDefault(false)
        }
        val onlineTextLines = lines.count { onlinePronunciationForNativeLine(it) != null }
        val stateSignature = listOf(
            songId,
            languages.joinToString(","),
            nativeTextLines,
            nativeWordLines,
            onlineTextLines,
            mainWordLines,
        ).joinToString("|")
        if (!applePronunciationDiagnosticsLoggedSongIds.add(stateSignature)) return
        ProviderLogger.diagnostic(
            "Apple pronunciation: id=$songId, languages=$languages, " +
                "nativeTextLines=$nativeTextLines, nativeWordLines=$nativeWordLines, " +
                "onlineTextLines=$onlineTextLines, mainWordLines=$mainWordLines"
        )
        reportApplePronunciationRuntimeDiagnostic(
            stage = "song_snapshot",
            songId = songId,
            details = "languages=$languages, nativeTextLines=$nativeTextLines, " +
                "nativeWordLines=$nativeWordLines, onlineTextLines=$onlineTextLines, " +
                "mainWordLines=$mainWordLines, " +
                "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}, " +
                "hidden=${shouldHideMandarinPronunciation(songId = songId)}",
            dedupeKey = "song_snapshot:$stateSignature",
        )
    }

    private fun reportApplePronunciationRuntimeDiagnostic(
        stage: String,
        songId: String? = currentAppleLyricsSongId,
        details: String,
        dedupeKey: String = stage,
    ) {
        if (!BuildConfig.DEBUG || !::application.isInitialized) return
        val normalizedSongId = songId?.takeIf(String::isNotBlank) ?: "unknown"
        if (!applePronunciationRuntimeDiagnosticKeys.add("$normalizedSongId|$dedupeKey")) {
            return
        }
        val message = "stage=$stage, id=$normalizedSongId, $details"
        Log.i("ApplePronunciationDiag", message)
        runCatching {
            application.contentResolver.call(
                Uri.parse("content://${RootConstants.CLASSIC_AOD_FOCUS_REFRESH_AUTHORITY}"),
                RootConstants.DEBUG_APPLE_PRONUNCIATION_DIAGNOSTIC_METHOD,
                message,
                null,
            )
        }.onFailure {
            ProviderLogger.debug(
                "Apple 发音诊断回传失败: stage=$stage, reason=${it.message}"
            )
        }
    }

    private fun logApplePronunciationLineBinding(
        line: Any?,
        originalPronunciation: String?,
        officialPronunciation: String?,
        onlinePronunciation: String?,
        displayText: String,
    ) {
        val context = appleLyricsBindingDiagnosticContexts.current ?: return
        val begin = line?.let {
            runCatching { AppleReflection.call(it, "getBegin") as? Number }
                .getOrNull()
                ?.toLong()
        }
        val source = when {
            officialPronunciation != null -> "official"
            onlinePronunciation != null -> "online"
            else -> "none"
        }
        logApplePronunciationBindingDiagnostic(
            stage = "line_getter",
            context = context,
            details = "begin=$begin, source=$source, " +
                "originalChars=${originalPronunciation.orEmpty().length}, " +
                "officialChars=${officialPronunciation.orEmpty().length}, " +
                "onlineChars=${onlinePronunciation.orEmpty().length}, " +
                "displayChars=${displayText.length}",
            dedupeKey = "line:${context.methodName}:${context.position}:$begin:" +
                "$source:${displayText.length}",
        )
    }

    private fun logApplePronunciationWordBinding(
        line: Any?,
        methodName: String,
        wordTrack: ApplePronunciationWordTrack,
        originalWords: Any?,
        resolvedWords: Any?,
        officialPronunciation: String?,
        onlinePronunciation: String?,
    ) {
        val context = appleLyricsBindingDiagnosticContexts.current ?: return
        val begin = line?.let {
            runCatching { AppleReflection.call(it, "getBegin") as? Number }
                .getOrNull()
                ?.toLong()
        }
        val alignment = if (wordTrack == ApplePronunciationWordTrack.OFFICIAL) {
            debugAppleOfficialPronunciationAlignment(
                line = line,
                pronunciationWords = resolvedWords,
            )
        } else {
            "not_official"
        }
        logApplePronunciationBindingDiagnostic(
            stage = "word_getter",
            context = context,
            details = "getter=$methodName, begin=$begin, track=$wordTrack, " +
                "originalWords=${nativeVectorSize(originalWords)}, " +
                "resolvedWords=${nativeVectorSize(resolvedWords)}, " +
                "officialChars=${officialPronunciation.orEmpty().length}, " +
                "onlineChars=${onlinePronunciation.orEmpty().length}, " +
                "alignment=$alignment",
            dedupeKey = "words:${context.methodName}:${context.position}:$begin:" +
                "$methodName:$wordTrack:${nativeVectorSize(resolvedWords)}",
        )
    }

    private fun debugAppleOfficialPronunciationAlignment(
        line: Any?,
        pronunciationWords: Any?,
    ): String {
        if (!BuildConfig.DEBUG || line == null) return "unavailable"
        val mainWords = AppleLyricTextTransform.withRawReads {
            runCatching { AppleReflection.call(line, "getWords") }.getOrNull()
        }
        val main = debugAppleWordTimings(mainWords)
        val pronunciation = debugAppleWordTimings(pronunciationWords)
        val pronunciationBegins = pronunciation.mapTo(hashSetOf()) { it.begin }
        val exactMatches = main.count { it.begin in pronunciationBegins }
        val nearestDeltas = main.map { mainWord ->
            pronunciation.minOfOrNull { pronunciationWord ->
                abs(mainWord.begin - pronunciationWord.begin)
            }
        }
        return "exactBegin=$exactMatches/${main.size}, " +
            "nearestDelta=$nearestDeltas, main=${debugAppleWordTimings(main)}, " +
            "pronunciation=${debugAppleWordTimings(pronunciation)}"
    }

    private fun debugAppleWordTimings(vector: Any?): List<AppleDebugWordTiming> =
        AppleLyricTextTransform.withRawReads {
            nativeVectorItems(vector, limit = 32).mapNotNull { word ->
                runCatching {
                    val begin = (AppleReflection.call(word, "getBegin") as Number).toInt()
                    val duration = (AppleReflection.call(word, "getDuration") as Number).toInt()
                    AppleDebugWordTiming(
                        wordId = (AppleReflection.call(word, "getWordId") as Number).toInt(),
                        begin = begin,
                        end = begin + duration,
                        text = (AppleReflection.call(word, "getHtmlLineText") as? String)
                            .orEmpty()
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .take(24),
                    )
                }.getOrNull()
            }
        }

    private fun debugAppleWordTimings(words: List<AppleDebugWordTiming>): String =
        words.joinToString(prefix = "[", postfix = "]") { word ->
            "${word.wordId}@${word.begin}-${word.end}:${word.text}"
        }

    private fun logApplePronunciationBindingDiagnostic(
        stage: String,
        context: AppleLyricsBindingDiagnosticContext,
        details: String,
        dedupeKey: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val songId = context.songId?.takeIf(String::isNotBlank) ?: "unknown"
        if (!applePronunciationBindingDiagnosticKeys.add("$songId|$dedupeKey")) return
        Log.i(
            "ApplePronunciationBindDiag",
            "stage=$stage, id=$songId, adapter=${context.adapterClass}@" +
                "${context.adapterIdentity}, method=${context.methodName}, " +
                "position=${context.position}, translation=${context.translationEnabled}, " +
                "pronunciation=${context.pronunciationEnabled}, $details",
        )
    }

    private fun nativeLineDiagnosticDetails(
        line: Any?,
        pronunciation: String?,
    ): String {
        val begin = line?.let {
            runCatching { AppleReflection.call(it, "getBegin") as? Number }
                .getOrNull()
                ?.toLong()
        }
        val end = line?.let {
            runCatching { AppleReflection.call(it, "getEnd") as? Number }
                .getOrNull()
                ?.toLong()
        }
        return "begin=$begin, end=$end, pronunciationChars=${pronunciation.orEmpty().length}"
    }

    private fun applePronunciationRenderArgumentSummary(args: List<Any?>): String =
        args.mapIndexedNotNull { index, value ->
            val resourceId = (value as? Number)?.toInt() ?: return@mapIndexedNotNull null
            val resourceName = runCatching {
                application.resources.getResourceEntryName(resourceId)
            }.getOrNull()
            "$index=$resourceId${resourceName?.let { ":$it" }.orEmpty()}"
        }.joinToString(prefix = "[", postfix = "]")

    private fun ensureAppleNativeOnlineTranslationHooks(songNative: Any) {
        val translationAvailabilityChecks = mapOf<String, (String?) -> Boolean>(
            "setTranslation" to nativeOnlineTranslationStore::hasTranslation,
            "hasTranslation" to nativeOnlineTranslationStore::hasTranslation,
        )
        translationAvailabilityChecks.forEach { (name, hasOnlineContent) ->
            val method = runCatching {
                AppleReflection.findMethod(songNative.javaClass, name, parameterCount = 1)
            }.getOrNull() ?: return@forEach
            if (
                method.returnType != Boolean::class.javaPrimitiveType ||
                !nativeOnlineTranslationHookedMethods.add(method)
            ) return@forEach

            // Apple Music 6.5.0 的歌词页会先用系统语言调用 setTranslation/hasTranslation，
            // 但官方对象可能只提供带地区的标签（例如 zh-Hans-CN）。把这两个调用统一
            // 映射到对象真实存在的官方标签，避免译文已加载却被 G2 可见性检查隐藏。
            hookRegistrar.installArgumentRewrite(method) { chain ->
                if (appleOfficialTranslationProbeGuard.isActive) {
                    return@installArgumentRewriteHook null
                }
                val requestedLanguage = chain.args.firstOrNull() as? String
                    ?: return@installArgumentRewriteHook null
                val selectedLanguage = selectAppleOfficialTranslationArgument(
                    songNative = chain.thisObject,
                    requestedLanguage = requestedLanguage,
                ) ?: return@installArgumentRewriteHook null
                if (selectedLanguage == requestedLanguage) {
                    null
                } else {
                    ProviderLogger.debug(
                        "Apple 官方翻译语言参数兼容映射：method=$name, " +
                            "requested=$requestedLanguage, selected=$selectedLanguage"
                    )
                    arrayOf(selectedLanguage)
                }
            }

            hookRegistrar.installResultOverride(method) { chain, original ->
                if (appleOfficialTranslationProbeGuard.isActive) {
                    return@installResultOverrideHook original
                }
                if (original == true) {
                    true
                } else {
                    isNativeOnlineTranslationEnabled() &&
                        hasOnlineContent(nativeSongId(chain.thisObject))
                }
            }
            ProviderLogger.debug(
                "Apple Music 原生在线翻译可用性 Hook 已安装: " +
                    "${method.declaringClass.name}#$name"
            )
        }
        listOf("setPronunciation", "hasPronunciation").forEach { name ->
            val method = runCatching {
                AppleReflection.findMethod(songNative.javaClass, name, parameterCount = 1)
            }.getOrNull() ?: return@forEach
            if (
                method.returnType != Boolean::class.javaPrimitiveType ||
                !nativeOnlineTranslationHookedMethods.add(method)
            ) return@forEach

            hookRegistrar.installResultOverride(method) { chain, original ->
                val songId = nativeSongId(chain.thisObject)
                val requestedLanguage = chain.args.firstOrNull() as? String
                if (
                    shouldHideMandarinPronunciation(
                        songId = songId,
                        pronunciationLanguages = listOfNotNull(requestedLanguage),
                    )
                ) {
                    return@installResultOverrideHook false
                }
                val hasOnlineRomanization = isNativeOnlineTranslationEnabled() &&
                    nativeOnlineTranslationStore.hasPronunciation(songId)
                val resolved = hasOnlineRomanization ||
                    (original == true && hasValidOfficialRomanization(chain.thisObject))
                reportApplePronunciationRuntimeDiagnostic(
                    stage = "availability_$name",
                    songId = songId,
                    details = "requested=$requestedLanguage, original=$original, " +
                        "online=$hasOnlineRomanization, resolved=$resolved, " +
                        "selected=${PreferencesMonitor.isPronunciationSelected()}",
                    dedupeKey = "availability_$name:$requestedLanguage:$original:$resolved",
                )
                resolved
            }
            ProviderLogger.debug(
                "Apple Music 罗马音可用性 Hook 已安装: " +
                    "${method.declaringClass.name}#$name"
            )
        }
    }

    /**
     * 根据 SongInfo 当前真实提供的官方语言标签，修正 Apple 歌词页传入的系统语言参数。
     * 这里只返回官方轨道标签，不把三方缓存伪装成 Apple 官方可用性。
     */
    private fun selectAppleOfficialTranslationArgument(
        songNative: Any?,
        requestedLanguage: String,
    ): String? {
        songNative ?: return null
        val availableLanguages = nativeVectorStrings(
            runCatching {
                AppleReflection.call(songNative, "getTranslationLanguages")
            }.getOrNull()
        )
        return selectAppleLyricsTranslationLanguage(
            systemLanguage = requestedLanguage,
            availableLanguages = availableLanguages,
        )
    }

    private fun hookAppleOfficialPronunciationLanguageMatching() {
        val localeUtilClass =
            classLoader.loadClass("com.apple.android.music.playback.util.LocaleUtil")
        val method = AppleReflection.findMethod(
            localeUtilClass,
            "matchToSystemLyricsScript",
            parameterCount = 1,
        )
        hookRegistrar.installResultOverride(method) { chain, original ->
            val appleLanguages = nativeVectorStrings(chain.args.firstOrNull())
            if (
                shouldHideMandarinPronunciation(
                    pronunciationLanguages = appleLanguages,
                )
            ) {
                return@installResultOverrideHook null
            }
            ApplePronunciationPolicy.selectLanguage(
                systemMatch = original as? String,
                appleLanguages = appleLanguages,
                onlineFallbackLanguage = thirdPartyPronunciationFallbackLanguage(),
            )
        }
        ProviderLogger.debug(
            "Apple Music 官方发音语言优先选择 Hook 已安装: " +
                "${method.declaringClass.name}#matchToSystemLyricsScript"
        )
    }

    private fun hookAppleLyricsPreferredLanguages() {
        runCatching {
            val requestClass = classLoader.loadClass(
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel\$f"
            )
            val constructorAndIndexes = requestClass.declaredConstructors.firstNotNullOfOrNull {
                candidate ->
                val indexes = appleLyricsStringArrayParameterIndexes(candidate.parameterTypes)
                if (indexes.size == 2) candidate to indexes else null
            } ?: throw NoSuchMethodException(
                "${requestClass.name}<init>(...,String[],...,String[],...)"
            )
            val (constructor, stringArrayIndexes) = constructorAndIndexes
            val translationIndex = stringArrayIndexes.first()
            val pronunciationIndex = stringArrayIndexes.last()
            constructor.isAccessible = true
            hookRegistrar.installArgumentRewrite(constructor) { chain ->
                val originalTranslationLanguages =
                    chain.args.getOrNull(translationIndex) as? Array<*>
                val expandedTranslationLanguages = expandAppleLyricsTranslationLanguages(
                    originalTranslationLanguages?.filterIsInstance<String>().orEmpty()
                ).toTypedArray()
                val originalPronunciationLanguages =
                    chain.args.getOrNull(pronunciationIndex) as? Array<*>
                val expandedPronunciationLanguages = expandAppleLyricsPronunciationLanguages(
                    originalPronunciationLanguages?.filterIsInstance<String>().orEmpty()
                ).toTypedArray()
                ProviderLogger.debug(
                    "Apple Music 官方歌词翻译候选请求: " +
                        "original=${originalTranslationLanguages?.toList()}, " +
                        "expanded=${expandedTranslationLanguages.toList()}"
                )
                ProviderLogger.debug(
                    "Apple Music 官方歌词发音候选请求: " +
                        "original=${originalPronunciationLanguages?.toList()}, " +
                        "expanded=${expandedPronunciationLanguages.toList()}"
                )
                chain.args.toTypedArray().also { rewritten ->
                    rewritten[translationIndex] = expandedTranslationLanguages
                    rewritten[pronunciationIndex] = expandedPronunciationLanguages
                }
            }
            ProviderLogger.debug(
                "Apple Music 官方歌词语言候选 Hook 已安装: " +
                    "${requestClass.name}<init>, translationIndex=$translationIndex, " +
                    "pronunciationIndex=$pronunciationIndex"
            )
        }.onFailure {
            ProviderLogger.error(
                "Apple Music 官方歌词语言候选 Hook 安装失败，已保留原生歌词链路",
                it,
            )
        }
    }

    private fun currentSystemLyricsLanguage(): String? = runCatching {
        appleLyricsViewModelRef?.get()?.let {
            AppleReflection.call(it, "getCurrentSystemLyricsLanguage") as? String
        }
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun thirdPartyPronunciationFallbackLanguage(): String? {
        val systemLanguage = currentSystemLyricsLanguage()
        if (
            shouldHideMandarinPronunciation(
                pronunciationLanguages = listOfNotNull(systemLanguage),
            )
        ) return null
        if (!isNativeOnlineTranslationEnabled()) return null
        val songId = currentAppleLyricsSongId
        if (!nativeOnlineTranslationStore.hasPronunciation(songId)) return null
        return systemLanguage
            ?.takeIf(RomanizationPolicy::isLatinLanguageTag)
            ?: "und-Latn"
    }

    private fun applyAppleNativeSupplementSelection(songNative: Any) {
        val songId = nativeSongId(songNative)
        reportApplePronunciationRuntimeDiagnostic(
            stage = "supplement_selection",
            songId = songId,
            details = "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}, " +
                "translationSelected=${PreferencesMonitor.isTranslationSelected()}, " +
                "hasOnlinePronunciation=${nativeOnlineTranslationStore.hasPronunciation(songId)}",
        )
        val tracks = appleNativeSupplementTracks(
            pronunciationSelected = PreferencesMonitor.isPronunciationSelected(),
            translationSelected = PreferencesMonitor.isTranslationSelected(),
        )
        if (AppleNativeSupplementTrack.TRANSLATION in tracks) {
            applyAppleNativeTranslationSelection(songNative)
        }
        if (AppleNativeSupplementTrack.PRONUNCIATION in tracks) {
            applyAppleNativePronunciationSelection(songNative)
        }
    }

    private fun applyAppleNativePronunciationSelection(songNative: Any) {
        val officialLanguages = nativeVectorStrings(
            runCatching {
                AppleReflection.call(songNative, "getPronunciationLanguages")
            }.getOrNull()
        ).filter(RomanizationPolicy::isLatinLanguageTag)
        val songId = nativeSongId(songNative)
        rememberApplePronunciationLanguages(songNative, officialLanguages)
        if (
            shouldHideMandarinPronunciation(
                songId = songId,
                pronunciationLanguages = officialLanguages,
            )
        ) {
            ProviderLogger.debug(
                "Apple 歌词发音轨道已隐藏：id=$songId, languages=$officialLanguages"
            )
            return
        }
        val language = officialLanguages
            .firstOrNull()
            ?.takeIf { hasValidOfficialRomanization(songNative) }
            ?: thirdPartyPronunciationFallbackLanguage()
            ?: return
        val selected = runCatching {
            AppleReflection.call(songNative, "setPronunciation", language) as? Boolean
        }.onFailure {
            ProviderLogger.error("Apple 歌词发音轨道选择失败：language=$language", it)
        }.getOrNull()
        reportApplePronunciationRuntimeDiagnostic(
            stage = "pronunciation_selection_applied",
            songId = songId,
            details = "language=$language, officialLanguages=$officialLanguages, " +
                "selected=$selected, preference=${PreferencesMonitor.isPronunciationSelected()}",
        )
        ProviderLogger.debug(
            "Apple 歌词发音轨道选择：language=$language, " +
                "official=${officialLanguages.isNotEmpty()}, selected=$selected"
        )
    }

    private fun applyAppleNativeTranslationSelection(songNative: Any) {
        val systemLanguage = currentSystemLyricsLanguage() ?: return
        val officialLanguages = nativeVectorStrings(
            runCatching {
                AppleReflection.call(songNative, "getTranslationLanguages")
            }.getOrNull()
        )
        val officialLanguage = selectAppleLyricsTranslationLanguage(
            systemLanguage = systemLanguage,
            availableLanguages = officialLanguages,
        )
        val language = officialLanguage ?: systemLanguage
        val selected = runCatching {
            appleOfficialTranslationProbeGuard.run {
                AppleReflection.call(songNative, "setTranslation", language) as? Boolean
            }
        }.onFailure {
            ProviderLogger.error("Apple 歌词翻译轨道选择失败：language=$language", it)
        }.getOrNull()
        ProviderLogger.debug(
            "Apple 歌词翻译轨道选择：systemLanguage=$systemLanguage, " +
                "officialLanguages=$officialLanguages, language=$language, " +
                "official=${officialLanguage != null}, selected=$selected"
        )
    }

    private fun refreshAppleLyricsBlurEffect() {
        mainHandler.post {
            val fragment = appleLyricsFragmentRef?.get() ?: return@post
            scheduleAppleLyricsBlur(resolveAppleLyricsRecyclerView(fragment))
        }
    }

    private fun refreshAppleSystemFontWeight() {
        mainHandler.post {
            val enabled = isFollowSystemFontWeightEnabled()
            appleSystemFontVariationCache.clear()
            if (enabled) currentMiuiFontWeightScale(forceRefresh = true)
            val trackedViews = synchronized(appleSystemFontTrackedTextViews) {
                appleSystemFontTrackedTextViews.entries.map { it.key to it.value }
            }
            trackedViews.forEach { (view, state) ->
                val target = if (enabled) {
                    createAppleWeightAdjustedTypeface(
                        original = state.originalTypeface,
                        requestedWeight = state.requestedWeight,
                        italic = state.italic,
                        textView = view,
                    )
                } else {
                    state.originalTypeface
                }
                appleSystemFontApplyGuard.run {
                    if (enabled) {
                        view.setTypeface(target)
                    } else {
                        view.setTypeface(target, state.originalStyle)
                    }
                }
                view.requestLayout()
                view.invalidate()
            }
            if (BuildConfig.DEBUG) {
                ProviderLogger.debug(
                    "Apple 系统字体粗细已刷新：enabled=$enabled, " +
                        "views=${trackedViews.size}, scale=${currentMiuiFontWeightScale()}"
                )
            }
        }
    }

    private fun scheduleAppleLyricsBlur(
        recyclerView: Any?,
        delayMs: Long = 0L,
    ) {
        val recyclerViewAsView = recyclerView as? View ?: return
        val viewRef = WeakReference(recyclerViewAsView)
        lateinit var applyBlur: Runnable
        applyBlur = Runnable {
            val target = viewRef.get() ?: return@Runnable
            val isCurrent = synchronized(appleLyricsBlurRuntimeStates) {
                val state = appleLyricsBlurRuntimeStates[target]
                if (state?.pendingApplyBlur !== applyBlur) {
                    false
                } else {
                    state.pendingApplyBlur = null
                    true
                }
            }
            if (!isCurrent) return@Runnable
            applyAppleLyricsBlur(target)
        }
        val previous = synchronized(appleLyricsBlurRuntimeStates) {
            val state = appleLyricsBlurRuntimeStates.getOrPut(recyclerViewAsView) {
                AppleLyricsBlurRuntimeState()
            }
            state.pendingApplyBlur.also { state.pendingApplyBlur = applyBlur }
        }
        previous?.let(recyclerViewAsView::removeCallbacks)
        if (delayMs > 0L) {
            recyclerViewAsView.postDelayed(applyBlur, delayMs)
        } else {
            recyclerViewAsView.postOnAnimation(applyBlur)
        }
    }

    private fun resetAppleLyricsBlurRuntimeState(recyclerView: Any?) {
        val recyclerViewAsView = recyclerView as? View ?: return
        val previous = synchronized(appleLyricsBlurRuntimeStates) {
            appleLyricsBlurRuntimeStates.remove(recyclerViewAsView)?.pendingApplyBlur
        }
        previous?.let(recyclerViewAsView::removeCallbacks)
        clearAppleLyricsBlurForRecycler(recyclerViewAsView)
        scheduleAppleLyricsBlur(recyclerViewAsView)
    }

    private fun suspendAppleLyricsBlurForScroll(recyclerView: Any?) {
        val recyclerViewAsView = recyclerView as? View ?: return
        val becameSuspended = synchronized(appleLyricsBlurRuntimeStates) {
            val state = appleLyricsBlurRuntimeStates.getOrPut(recyclerViewAsView) {
                AppleLyricsBlurRuntimeState()
            }
            state.pendingProgrammaticRecenterPosition = null
            if (state.suspendedForScroll) {
                false
            } else {
                state.suspendedForScroll = true
                true
            }
        }
        if (becameSuspended) clearAppleLyricsBlurForRecycler(recyclerViewAsView)
    }

    private fun onAppleLyricsProgrammaticRecenterRequested(
        layoutManager: Any?,
        targetPosition: Int,
    ) {
        if (layoutManager == null || targetPosition < 0) return
        val recyclerView = synchronized(appleLyricsBlurRuntimeStates) {
            appleLyricsBlurRuntimeStates.keys.firstOrNull { candidate ->
                runCatching {
                    AppleReflection.call(candidate, "getLayoutManager")
                }.getOrNull() === layoutManager
            }
        } ?: return
        if (appleLyricsRecyclerScrollState(recyclerView) != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            return
        }
        val marked = synchronized(appleLyricsBlurRuntimeStates) {
            val state = appleLyricsBlurRuntimeStates.getOrPut(recyclerView) {
                AppleLyricsBlurRuntimeState()
            }
            if (!state.suspendedForScroll) {
                false
            } else {
                state.pendingProgrammaticRecenterPosition = targetPosition
                true
            }
        }
        if (marked && BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple lyrics blur: programmatic_recenter_requested, " +
                    "target=$targetPosition"
            )
        }
    }

    private fun completeAppleLyricsProgrammaticRecenter(recyclerView: Any?): Boolean {
        val recyclerViewAsView = recyclerView as? View ?: return false
        val adapter = appleRecyclerAdapter(recyclerViewAsView) ?: return false
        val activePositions = appleLyricsActiveAdapterPositions(adapter)
        val positionedChildren = appleLyricsVisiblePositionedChildren(recyclerViewAsView)
        val instrumentalPositions = appleLyricsInstrumentalAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val writersCreditsPositions = appleLyricsWritersCreditsAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val focusPositions = appleLyricsBlurFocusPositions(
            activePositions = activePositions,
            instrumentalPositions = instrumentalPositions,
            writersCreditsPositions = writersCreditsPositions,
        )
        val scrollState = appleLyricsRecyclerScrollState(recyclerViewAsView)
        var completedTarget: Int? = null
        val completed = synchronized(appleLyricsBlurRuntimeStates) {
            val state = appleLyricsBlurRuntimeStates[recyclerViewAsView] ?: return@synchronized false
            val targetPosition = state.pendingProgrammaticRecenterPosition
            if (
                !shouldCompleteAppleLyricsProgrammaticRecenter(
                    suspendedForScroll = state.suspendedForScroll,
                    scrollState = scrollState,
                    pendingTargetPosition = targetPosition,
                    focusPositions = focusPositions,
                )
            ) {
                false
            } else {
                completedTarget = targetPosition
                state.pendingProgrammaticRecenterPosition = null
                state.suspendedForScroll = false
                state.settledAnchorTopY = null
                state.lastActivePositions = activePositions
                state.lastDiagnosticSignature = null
                true
            }
        }
        if (completed && BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "Apple lyrics blur: programmatic_recenter_completed, " +
                    "target=$completedTarget, active=${activePositions.sorted()}, " +
                    "instrumental=${instrumentalPositions.sorted()}, " +
                    "writers=${writersCreditsPositions.sorted()}, " +
                    "focus=${focusPositions.sorted()}"
            )
        }
        return completed
    }

    private fun onAppleLyricsScrollStateChanged(
        recyclerView: Any?,
        scrollState: Int,
    ) {
        if (scrollState != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            suspendAppleLyricsBlurForScroll(recyclerView)
            return
        }
        scheduleAppleLyricsBlur(
            recyclerView = recyclerView,
            delayMs = APPLE_LYRICS_IDLE_RECHECK_DELAY_MS,
        )
    }

    private fun onAppleLyricsActiveLinesUpdated(adapter: Any?) {
        adapter ?: return
        val recyclerView = synchronized(appleLyricsRecyclerViewsByAdapter) {
            appleLyricsRecyclerViewsByAdapter[adapter]?.get()
        } ?: return
        val activePositions = appleLyricsActiveAdapterPositions(adapter)
        val changed = synchronized(appleLyricsBlurRuntimeStates) {
            val state = appleLyricsBlurRuntimeStates.getOrPut(recyclerView) {
                AppleLyricsBlurRuntimeState()
            }
            updateAppleLyricsActivePositions(state, activePositions)
        }
        if (changed) scheduleAppleLyricsBlur(recyclerView)
    }

    private fun updateAppleLyricsActivePositions(
        state: AppleLyricsBlurRuntimeState,
        activePositions: Set<Int>,
    ): Boolean {
        val previousPositions = state.lastActivePositions
        if (state.suspendedForScroll) {
            state.pendingOutgoingPositions = emptySet()
            state.outgoingZoneTopByPosition = emptyMap()
            state.lastActivePositions = activePositions
            return previousPositions != activePositions
        }
        val newlyOutgoingPositions = previousPositions - activePositions
        val pendingOutgoingPositions =
            (state.pendingOutgoingPositions + newlyOutgoingPositions) - activePositions
        val outgoingZoneTopByPosition = state.outgoingZoneTopByPosition
            .filterKeys { it in pendingOutgoingPositions }
            .toMutableMap()
        state.settledAnchorTopY?.let { currentZoneTopY ->
            newlyOutgoingPositions.forEach { position ->
                outgoingZoneTopByPosition.putIfAbsent(position, currentZoneTopY)
            }
        }
        state.pendingOutgoingPositions = pendingOutgoingPositions
        state.outgoingZoneTopByPosition = outgoingZoneTopByPosition
        state.lastActivePositions = activePositions
        return previousPositions != activePositions
    }

    private fun applyAppleLyricsBlur(recyclerView: View) {
        val container = recyclerView as? ViewGroup ?: return
        val state = synchronized(appleLyricsBlurRuntimeStates) {
            appleLyricsBlurRuntimeStates.getOrPut(recyclerView) {
                AppleLyricsBlurRuntimeState()
            }
        }
        val mode = appleLyricsBlurMode()
        if (mode == AppleLyricsBlurPolicy.OFF) {
            state.pendingOutgoingPositions = emptySet()
            state.outgoingZoneTopByPosition = emptyMap()
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "off",
                mode = mode,
            )
            return
        }
        val blurRadiusRange = appleLyricsBlurRadiusRange(mode)

        val adapter = appleRecyclerAdapter(recyclerView) ?: run {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "adapter_missing",
                mode = mode,
            )
            return
        }
        appleLyricsRecyclerViewsByAdapter[adapter] = WeakReference(recyclerView)
        synchronized(appleLyricsBlurRuntimeStates) {
            state.also { currentState ->
                if (currentState.adapterRef?.get() !== adapter) {
                    currentState.adapterRef = WeakReference(adapter)
                    currentState.settledAnchorTopY = null
                    currentState.suspendedForScroll = false
                    currentState.pendingProgrammaticRecenterPosition = null
                    currentState.lastActivePositions = emptySet()
                    currentState.pendingOutgoingPositions = emptySet()
                    currentState.outgoingZoneTopByPosition = emptyMap()
                }
                if (
                    recyclerView.height > 0 &&
                    currentState.recyclerHeight != recyclerView.height
                ) {
                    currentState.recyclerHeight = recyclerView.height
                    currentState.settledAnchorTopY = null
                    currentState.suspendedForScroll = false
                    currentState.pendingProgrammaticRecenterPosition = null
                    currentState.pendingOutgoingPositions = emptySet()
                    currentState.outgoingZoneTopByPosition = emptyMap()
                }
            }
        }
        if (appleLyricsRecyclerScrollState(recyclerView) != APPLE_LYRICS_SCROLL_STATE_IDLE) {
            state.suspendedForScroll = true
            state.pendingProgrammaticRecenterPosition = null
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "scrolling",
                mode = mode,
                adapter = adapter,
            )
            return
        }

        val children = buildList {
            repeat(container.childCount) { index ->
                container.getChildAt(index)
                    ?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
                    ?.let(::add)
            }
        }
        if (children.isEmpty()) {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "children_empty",
                mode = mode,
                adapter = adapter,
            )
            return
        }

        val positionedChildren = children.mapNotNull { child ->
            appleLyricsChildAdapterPosition(recyclerView, child)
                .takeIf { it >= 0 }
                ?.let { position -> position to child }
        }
        val activePositions = appleLyricsActiveAdapterPositions(adapter)
        updateAppleLyricsActivePositions(state, activePositions)
        val instrumentalPositions = appleLyricsInstrumentalAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val writersCreditsPositions = appleLyricsWritersCreditsAdapterPositions(
            adapter = adapter,
            positionedChildren = positionedChildren,
        )
        val focusPositions = appleLyricsBlurFocusPositions(
            activePositions = activePositions,
            instrumentalPositions = instrumentalPositions,
            writersCreditsPositions = writersCreditsPositions,
        )
        val firstLineBeginMs = appleLyricsFirstLineBeginMs(adapter)
        val currentPositionMs = appleLyricsCurrentPlaybackPositionMs()
        val shouldBlurBeforeFirstLine = focusPositions.isEmpty() &&
            AppleLyricsBlurPolicy.shouldBlurBeforeFirstLine(
                currentPositionMs = currentPositionMs,
                firstLineBeginMs = firstLineBeginMs,
            )
        if (focusPositions.isEmpty()) {
            if (shouldBlurBeforeFirstLine) {
                state.settledAnchorTopY = null
                state.pendingOutgoingPositions = emptySet()
                state.outgoingZoneTopByPosition = emptyMap()
                val radiusByPosition = linkedMapOf<Int, Int>()
                positionedChildren
                    .sortedBy(Pair<Int, View>::first)
                    .forEachIndexed { visibleRowIndex, (position, child) ->
                        val radiusPx = AppleLyricsBlurPolicy.beforeFirstLineBlurRadiusPx(
                            mode = mode,
                            visibleRowIndex = visibleRowIndex,
                            minRadius = blurRadiusRange.start,
                            maxRadius = blurRadiusRange.endInclusive,
                            density = recyclerView.resources.displayMetrics.density,
                        )
                        radiusByPosition[position] = radiusPx
                        applyAppleLyricsBlur(
                            view = child,
                            mode = mode,
                            radiusPx = radiusPx,
                        )
                    }
                logAppleLyricsBlurDiagnostic(
                    recyclerView = recyclerView,
                    state = state,
                    stage = "before_first_line",
                    mode = mode,
                    adapter = adapter,
                    activePositions = activePositions,
                    instrumentalPositions = instrumentalPositions,
                    writersCreditsPositions = writersCreditsPositions,
                    focusPositions = focusPositions,
                    positionedChildren = positionedChildren,
                    radiusByPosition = radiusByPosition,
                    currentPositionMs = currentPositionMs,
                    firstLineBeginMs = firstLineBeginMs,
                )
                if (isPlaying && currentPositionMs != null && firstLineBeginMs != null) {
                    scheduleAppleLyricsBlur(
                        recyclerView = recyclerView,
                        delayMs = (firstLineBeginMs - currentPositionMs)
                            .coerceIn(APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS, APPLE_LYRICS_BEFORE_FIRST_LINE_RECHECK_MAX_MS),
                    )
                }
                return
            }
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "active_empty",
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
                currentPositionMs = currentPositionMs,
                firstLineBeginMs = firstLineBeginMs,
            )
            return
        }
        val focusChildren = positionedChildren.filter { (position, _) ->
            position in focusPositions
        }
        if (focusChildren.isEmpty()) {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "active_not_visible",
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
            )
            return
        }

        val initialAnchorY = recyclerView.height * APPLE_LYRICS_INITIAL_ANCHOR_Y_FRACTION
        val anchorChild = focusChildren.minByOrNull { (_, child) ->
            abs(appleLyricsChildTopY(child) - (state.settledAnchorTopY ?: initialAnchorY))
        }?.second ?: run {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = "anchor_child_missing",
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
            )
            return
        }
        val anchorTopY = appleLyricsChildTopY(anchorChild)
        val settledAnchorTopY = state.settledAnchorTopY
        if (state.suspendedForScroll) {
            clearAppleLyricsBlurForRecycler(recyclerView)
            logAppleLyricsBlurDiagnostic(
                recyclerView = recyclerView,
                state = state,
                stage = if (state.pendingProgrammaticRecenterPosition == null) {
                    "awaiting_recenter_request"
                } else {
                    "awaiting_recenter_layout"
                },
                mode = mode,
                adapter = adapter,
                activePositions = activePositions,
                instrumentalPositions = instrumentalPositions,
                writersCreditsPositions = writersCreditsPositions,
                focusPositions = focusPositions,
                positionedChildren = positionedChildren,
            )
            return
        }
        if (settledAnchorTopY == null) {
            state.settledAnchorTopY = anchorTopY
        }

        val radiusByPosition = linkedMapOf<Int, Int>()
        val pendingOutgoingPositions = state.pendingOutgoingPositions
        val outgoingZoneTopByPosition = state.outgoingZoneTopByPosition.toMutableMap()
        val deferredBlurPositions = linkedSetOf<Int>()
        val outgoingBottomByPosition = linkedMapOf<Int, Int>()
        val currentZoneTopByPosition = linkedMapOf<Int, Int>()
        positionedChildren.forEach { (childPosition, child) ->
            val rowDistance = focusPositions.minOf { focusPosition ->
                abs(childPosition - focusPosition)
            }
            val isPendingOutgoing = childPosition in pendingOutgoingPositions
            val currentZoneTopY = if (isPendingOutgoing) {
                outgoingZoneTopByPosition.getOrPut(childPosition) {
                    state.settledAnchorTopY ?: appleLyricsChildTopY(child)
                }
            } else {
                null
            }
            val rowBottomY = currentZoneTopY?.let { appleLyricsChildBottomY(child) }
            if (rowBottomY != null) {
                outgoingBottomByPosition[childPosition] = rowBottomY.roundToInt()
                currentZoneTopByPosition[childPosition] = currentZoneTopY.roundToInt()
            }
            val deferBlur = shouldDeferAppleLyricsOutgoingBlur(
                isPendingOutgoing = isPendingOutgoing,
                rowBottomY = rowBottomY,
                currentZoneTopY = currentZoneTopY,
            )
            if (deferBlur) deferredBlurPositions += childPosition
            val radiusPx = if (deferBlur) {
                0
            } else {
                AppleLyricsBlurPolicy.blurRadiusPx(
                    mode = mode,
                    rowDistance = rowDistance,
                    minRadius = blurRadiusRange.start,
                    maxRadius = blurRadiusRange.endInclusive,
                    density = recyclerView.resources.displayMetrics.density,
                )
            }
            radiusByPosition[childPosition] = radiusPx
            applyAppleLyricsBlur(
                view = child,
                mode = mode,
                radiusPx = radiusPx,
            )
        }
        state.pendingOutgoingPositions = deferredBlurPositions
        state.outgoingZoneTopByPosition = outgoingZoneTopByPosition
            .filterKeys { it in deferredBlurPositions }
        logAppleLyricsBlurDiagnostic(
            recyclerView = recyclerView,
            state = state,
            stage = "applied",
            mode = mode,
            adapter = adapter,
            activePositions = activePositions,
            instrumentalPositions = instrumentalPositions,
            writersCreditsPositions = writersCreditsPositions,
            focusPositions = focusPositions,
            positionedChildren = positionedChildren,
            radiusByPosition = radiusByPosition,
            deferredBlurPositions = deferredBlurPositions,
            outgoingBottomByPosition = outgoingBottomByPosition,
            currentZoneTopByPosition = currentZoneTopByPosition,
        )
        if (deferredBlurPositions.isNotEmpty()) {
            scheduleAppleLyricsBlur(
                recyclerView = recyclerView,
                delayMs = APPLE_LYRICS_OUTGOING_RECHECK_DELAY_MS,
            )
        }
    }

    private fun appleLyricsChildTopY(view: View): Float =
        view.top + view.translationY

    private fun appleLyricsChildBottomY(view: View): Float =
        view.bottom + view.translationY

    private fun appleLyricsRecyclerScrollState(recyclerView: Any): Int =
        runCatching {
            (AppleReflection.call(recyclerView, "getScrollState") as? Number)?.toInt()
        }.getOrNull() ?: APPLE_LYRICS_SCROLL_STATE_IDLE

    private fun appleLyricsActiveAdapterPositions(adapter: Any): Set<Int> =
        ((runCatching { AppleReflection.call(adapter, "B") }.getOrNull() as? Iterable<*>)
            ?.mapNotNull { (it as? Number)?.toInt()?.takeIf { position -> position >= 0 } }
            ?.toSet())
            .orEmpty()

    private fun appleLyricsFirstLineBeginMs(adapter: Any): Long? = runCatching {
        val lyrics = AppleReflection.call(adapter, "C") ?: return@runCatching null
        val lineCount = (AppleReflection.call(lyrics, "b") as? Number)?.toInt()
            ?: return@runCatching null
        if (lineCount <= 0) return@runCatching null
        val firstLinePointer = AppleReflection.call(lyrics, "a", 0)
            ?: return@runCatching null
        val firstLine = AppleReflection.call(firstLinePointer, "get")
            ?: return@runCatching null
        (AppleReflection.call(firstLine, "getBegin") as? Number)?.toLong()
    }.getOrNull()

    private fun appleLyricsCurrentPlaybackPositionMs(): Long? =
        runCatching { playbackPositionSource?.readPosition() }.getOrNull()
            ?: lastTimingSamplePosition.takeIf { it >= 0L }

    private fun appleLyricsVisiblePositionedChildren(
        recyclerView: View,
    ): List<Pair<Int, View>> {
        val container = recyclerView as? ViewGroup ?: return emptyList()
        return buildList {
            repeat(container.childCount) { index ->
                val child = container.getChildAt(index)
                    ?.takeIf {
                        it.visibility == View.VISIBLE && it.width > 0 && it.height > 0
                    }
                    ?: return@repeat
                appleLyricsChildAdapterPosition(recyclerView, child)
                    .takeIf { it >= 0 }
                    ?.let { position -> add(position to child) }
            }
        }
    }

    private fun appleLyricsInstrumentalAdapterPositions(
        adapter: Any,
        positionedChildren: List<Pair<Int, View>>,
    ): Set<Int> = positionedChildren.mapNotNull { (position, child) ->
        position.takeIf {
            appleLyricsAdapterItemViewType(adapter, position) == 2 ||
                appleLyricsIsInstrumentalIndicator(child)
        }
    }.toSet()

    private fun appleLyricsWritersCreditsAdapterPositions(
        adapter: Any,
        positionedChildren: List<Pair<Int, View>>,
    ): Set<Int> = positionedChildren.mapNotNull { (position, _) ->
        position.takeIf { appleLyricsAdapterItemViewType(adapter, position) == 1 }
    }.toSet()

    private fun appleLyricsAdapterItemViewType(adapter: Any, position: Int): Int? =
        runCatching {
            (AppleReflection.call(adapter, "getItemViewType", position) as? Number)?.toInt()
        }.getOrNull() ?: runCatching {
            (AppleReflection.call(adapter, "k", position) as? Number)?.toInt()
        }.getOrNull()

    private fun appleLyricsIsInstrumentalIndicator(view: View): Boolean {
        val rootId = runCatching {
            view.resources.getIdentifier(
                "lyrics_instrumental_root",
                "id",
                APPLE_MUSIC_PACKAGE,
            )
        }.getOrDefault(0)
        return rootId != 0 && view.findViewById<View>(rootId) != null
    }

    private fun appleLyricsChildAdapterPosition(recyclerView: View, child: View): Int {
        val namedPosition = runCatching {
            (AppleReflection.call(recyclerView, "getChildAdapterPosition", child) as? Number)
                ?.toInt()
        }.getOrNull()?.takeIf { it >= 0 }
        if (namedPosition != null) return namedPosition

        val method = appleLyricsChildAdapterPositionMethods[recyclerView.javaClass]
            ?: findAppleLyricsChildAdapterPositionMethod(recyclerView.javaClass)?.also {
                appleLyricsChildAdapterPositionMethods[recyclerView.javaClass] = it
            }
            ?: return -1
        return runCatching {
            (method.invoke(null, child) as? Number)?.toInt()
        }.getOrNull()?.takeIf { it >= 0 } ?: -1
    }

    private fun findAppleLyricsChildAdapterPositionMethod(clazz: Class<*>): Method? =
        generateSequence(clazz) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(arrayOf(View::class.java))
            }
            ?.apply { isAccessible = true }

    private fun logAppleLyricsBlurDiagnostic(
        recyclerView: View,
        state: AppleLyricsBlurRuntimeState,
        stage: String,
        mode: Int,
        adapter: Any? = null,
        activePositions: Set<Int> = emptySet(),
        instrumentalPositions: Set<Int> = emptySet(),
        writersCreditsPositions: Set<Int> = emptySet(),
        focusPositions: Set<Int> = activePositions,
        positionedChildren: List<Pair<Int, View>> = emptyList(),
        radiusByPosition: Map<Int, Int> = emptyMap(),
        deferredBlurPositions: Set<Int> = emptySet(),
        outgoingBottomByPosition: Map<Int, Int> = emptyMap(),
        currentZoneTopByPosition: Map<Int, Int> = emptyMap(),
        currentPositionMs: Long? = null,
        firstLineBeginMs: Long? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val visiblePositions = positionedChildren.map(Pair<Int, View>::first)
        val nativeRenderEffectCount = positionedChildren.count { (_, child) ->
            appleLyricsHasNativeRenderEffect(child)
        }
        val signature = listOf(
            stage,
            mode,
            adapter?.javaClass?.name,
            appleLyricsRecyclerScrollState(recyclerView),
            activePositions.sorted(),
            instrumentalPositions.sorted(),
            writersCreditsPositions.sorted(),
            focusPositions.sorted(),
            visiblePositions,
            state.suspendedForScroll,
            state.settledAnchorTopY?.roundToInt(),
            state.pendingProgrammaticRecenterPosition,
            radiusByPosition,
            deferredBlurPositions.sorted(),
            outgoingBottomByPosition,
            currentZoneTopByPosition,
            currentPositionMs,
            firstLineBeginMs,
            nativeRenderEffectCount,
        ).joinToString("|")
        if (state.lastDiagnosticSignature == signature) return
        state.lastDiagnosticSignature = signature
        ProviderLogger.diagnostic(
            "Apple lyrics blur: stage=$stage, mode=$mode, " +
                "adapter=${adapter?.javaClass?.name ?: "none"}, " +
                "scrollState=${appleLyricsRecyclerScrollState(recyclerView)}, " +
                "active=${activePositions.sorted()}, " +
                "instrumental=${instrumentalPositions.sorted()}, " +
                "writers=${writersCreditsPositions.sorted()}, " +
                "focus=${focusPositions.sorted()}, visible=$visiblePositions, " +
                "suspended=${state.suspendedForScroll}, " +
                "anchor=${state.settledAnchorTopY?.roundToInt() ?: "none"}, " +
                "pendingRecenter=${state.pendingProgrammaticRecenterPosition ?: "none"}, " +
                "deferred=${deferredBlurPositions.sorted()}, " +
                "outgoingBottom=$outgoingBottomByPosition, " +
                "currentZoneTop=$currentZoneTopByPosition, radii=$radiusByPosition, " +
                "currentPosition=$currentPositionMs, firstLineBegin=$firstLineBeginMs, " +
                "nativeEffects=$nativeRenderEffectCount"
        )
    }

    private fun findAppleLyricsScrollToPositionWithOffsetMethod(clazz: Class<*>): Method? {
        runCatching {
            AppleReflection.findMethod(
                clazz,
                "scrollToPositionWithOffset",
                parameterCount = 2,
            )
        }.getOrNull()?.takeIf { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                )
        }?.let { return it }

        return generateSequence(clazz) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                Modifier.isPublic(method.modifiers) &&
                    !Modifier.isStatic(method.modifiers) &&
                    !Modifier.isFinal(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                        )
                    )
            }
            ?.apply { isAccessible = true }
    }

    private fun appleLyricsHasNativeRenderEffect(view: View): Boolean =
        runCatching {
            AppleReflection.call(view, "getRenderEffect") != null
        }.recoverCatching {
            AppleReflection.field(view, "mRenderEffect") != null
        }.getOrDefault(false)

    private fun applyAppleLyricsBlur(view: View, mode: Int, radiusPx: Int) {
        appleLyricsBlurredViews.add(view)
        when (mode) {
            AppleLyricsBlurPolicy.NATIVE -> {
                clearAppleLyricsHyperOsBlur(view)
                applyAppleLyricsNativeBlur(view, radiusPx)
            }
            AppleLyricsBlurPolicy.ADVANCED_MATERIAL -> {
                view.setRenderEffect(null)
                if (!applyAppleLyricsHyperOsBlur(view, radiusPx)) {
                    clearAppleLyricsBlur(view)
                }
            }
            else -> clearAppleLyricsBlur(view)
        }
    }

    private fun applyAppleLyricsNativeBlur(view: View, radiusPx: Int) {
        view.setRenderEffect(
            radiusPx.takeIf { it > 0 }?.let { radius ->
                RenderEffect.createBlurEffect(
                    radius.toFloat(),
                    radius.toFloat(),
                    Shader.TileMode.CLAMP,
                )
            }
        )
    }

    private fun applyAppleLyricsHyperOsBlur(view: View, radiusPx: Int): Boolean {
        val methods = appleLyricsHyperOsMethods(view)
        val setSelfBlur = methods.setSelfBlur ?: return false
        return runCatching {
            methods.setSelfBlurType?.invoke(view, APPLE_LYRICS_HYPER_OS_SELF_BLUR_TYPE)
            setSelfBlur.invoke(view, radiusPx.coerceAtLeast(0), ArrayList<Any>())
            true
        }.getOrElse {
            false
        }
    }

    private fun clearAppleLyricsBlur(view: View) {
        view.setRenderEffect(null)
        clearAppleLyricsHyperOsBlur(view)
    }

    private fun clearAppleLyricsBlurForRecycler(recyclerView: View) {
        (recyclerView as? ViewGroup)?.let { container ->
            repeat(container.childCount) { index ->
                container.getChildAt(index)?.let(::clearAppleLyricsBlur)
            }
        }
        val affectedViews = synchronized(appleLyricsBlurredViews) {
            appleLyricsBlurredViews.toList().also { appleLyricsBlurredViews.clear() }
        }
        affectedViews.forEach(::clearAppleLyricsBlur)
    }

    private fun clearAppleLyricsHyperOsBlur(view: View) {
        val methods = appleLyricsHyperOsMethods(view)
        runCatching {
            methods.setSelfBlur?.invoke(view, 0, ArrayList<Any>())
        }
    }

    private fun appleLyricsHyperOsMethods(view: View): AppleLyricsHyperOsMethods =
        synchronized(appleLyricsHyperOsMethods) {
            appleLyricsHyperOsMethods.getOrPut(view.javaClass) {
                fun findPublicMethod(name: String, vararg parameterTypes: Class<*>): Method? =
                    runCatching {
                        view.javaClass.getMethod(name, *parameterTypes).apply {
                            isAccessible = true
                        }
                    }.getOrNull()

                AppleLyricsHyperOsMethods(
                    setSelfBlur = findPublicMethod(
                        "setMiSelfBlur",
                        Int::class.javaPrimitiveType!!,
                        ArrayList::class.java,
                    ),
                    setSelfBlurType = findPublicMethod(
                        "setMiSelfBlurType",
                        Int::class.javaPrimitiveType!!,
                    ),
                )
            }
        }

    private fun refreshAppleLyricsRecyclerView(
        fragment: Any,
        expectedSongId: String?,
        expectedRevision: Long?,
    ) {
        val recyclerView = resolveAppleLyricsRecyclerView(fragment) ?: run {
            logAppleLyricsUiState(
                fragment = fragment,
                stage = "refresh_resolve_failed",
                expectedSongId = expectedSongId,
                expectedRevision = expectedRevision,
            )
            ProviderLogger.debug(
                "Apple Music 歌词 RecyclerView 解析失败: fragment=${fragment.javaClass.name}"
            )
            return
        }
        logAppleLyricsUiState(
            fragment = fragment,
            stage = "refresh_resolved",
            expectedSongId = expectedSongId,
            expectedRevision = expectedRevision,
        )

        fun isRefreshCurrent(): Boolean =
            expectedRevision == null ||
                nativeOnlineTranslationStore.isCurrentRevision(
                    songId = expectedSongId,
                    revision = expectedRevision,
                )

        val recyclerViewAsView = recyclerView as? View ?: return

        fun isComputingLayout(): Boolean =
            runCatching {
                AppleReflection.call(recyclerView, "isComputingLayout") as? Boolean
            }.getOrNull() == true

        fun rebindAllRows(stage: String) {
            if (!isRefreshCurrent()) return
            if (isComputingLayout()) {
                recyclerViewAsView.postOnAnimation { rebindAllRows(stage) }
                return
            }
            val adapter = appleRecyclerAdapter(recyclerView) ?: return
            val itemCount = appleRecyclerAdapterItemCount(adapter)
            if (itemCount <= 0) return
            runCatching {
                appleRecyclerNotifyDataSetChanged(adapter)
            }.onFailure {
                ProviderLogger.error(
                    "Apple Music 歌词列表完整重绑失败: stage=$stage",
                    it,
                )
            }
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 歌词列表已请求完整重绑: " +
                        "id=$expectedSongId, revision=${expectedRevision ?: "none"}, " +
                        "stage=$stage, adapter=${adapter.javaClass.name}, " +
                        "itemCount=$itemCount"
                )
            }
        }

        // Apple Music's karaoke adapter appends translation child views when it handles
        // PAYLOAD_TOGGLE_TRANSLATION. A scroll fixes duplicate rows because the normal full bind
        // clears and rebuilds the holder. Request the same full-bind path for all rows instead of
        // sending the partial translation payload or directly binding foreign-ClassLoader holders.
        recyclerViewAsView.postOnAnimation {
            rebindAllRows("next_frame")
        }
    }

    private fun logAppleLyricsUiState(
        fragment: Any,
        stage: String,
        expectedSongId: String? = null,
        expectedRevision: Long? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val bindingRead = runCatching { AppleReflection.field(fragment, "i0") }
        val binding = bindingRead.getOrNull()
        val bindingRecyclerRead = binding?.let { currentBinding ->
            runCatching { AppleReflection.field(currentBinding, "a0") }
        }
        val bindingRecycler = bindingRecyclerRead
            ?.getOrNull()
            ?.takeIf(::isAppleRecyclerViewInstance)
        val fragmentAdapterRead = runCatching { AppleReflection.field(fragment, "k0") }
        val fragmentAdapter = fragmentAdapterRead.getOrNull()
        val lyricsPointer = appleLyricsSongPointerRef?.get()
        val lyricsNative = lyricsPointer?.let { pointer ->
            runCatching { AppleReflection.call(pointer, "get") }.getOrNull()
        }
        val fragmentRoot = runCatching {
            AppleReflection.call(fragment, "getView") as? View
        }.getOrNull()
        val lifecycle = listOf("isAdded", "isVisible", "isResumed").joinToString(",") { name ->
            "$name=${runCatching { AppleReflection.call(fragment, name) }.getOrNull()}"
        }
        val currentSongId = currentAppleLyricsSongId
        ProviderLogger.diagnostic(
            "Apple lyrics UI state: stage=$stage, " +
                "fragment=${debugAppleLyricsValue(fragment)}, lifecycle=[$lifecycle], " +
                "root=${debugAppleLyricsValue(fragmentRoot)}, " +
                "i0=${debugAppleLyricsRead(bindingRead)}, " +
                "i0.a0=${debugAppleLyricsRead(bindingRecyclerRead)}, " +
                "k0=${debugAppleLyricsRead(fragmentAdapterRead)}, " +
                "k0State=${debugAppleLyricsAdapterState(fragmentAdapter)}, " +
                "viewModelState=${debugAppleLyricsViewModelState(fragment)}, " +
                "songPointer=${debugAppleNativePointer(lyricsPointer)}, " +
                "songNative=${debugAppleNativePointer(lyricsNative)}, " +
                "songState=${debugApplePronunciationSongState(lyricsNative)}, " +
                "fragmentRecyclerFields=${debugAppleLyricsRecyclerFields(fragment)}, " +
                "bindingRecyclerFields=${debugAppleLyricsRecyclerFields(binding)}, " +
                "resolvedRecycler=${bindingRecycler?.let(::debugRecyclerViewSnapshot)}, " +
                "expectedSongId=$expectedSongId, currentSongId=$currentSongId, " +
                "expectedRevision=${expectedRevision ?: "none"}, " +
                "storeRevision=${nativeOnlineTranslationStore.revision()}, " +
                "hasTranslation=${nativeOnlineTranslationStore.hasTranslation(currentSongId)}, " +
                "hasPronunciation=${nativeOnlineTranslationStore.hasPronunciation(currentSongId)}, " +
                "translationSelected=${PreferencesMonitor.isTranslationSelected()}, " +
                "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}"
        )
    }

    private fun debugAppleLyricsRead(result: Result<Any?>?): String = when {
        result == null -> "not_read"
        result.isFailure -> {
            val throwable = result.exceptionOrNull()
            "error:${throwable?.javaClass?.simpleName}:${throwable?.message}"
        }
        else -> debugAppleLyricsValue(result.getOrNull())
    }

    private fun debugAppleLyricsValue(value: Any?): String = when (value) {
        null -> "null"
        else -> when {
            isAppleRecyclerViewInstance(value) -> debugRecyclerViewSnapshot(value)
            value is View -> {
                val idName = runCatching {
                    value.resources.getResourceName(value.id)
                }.getOrNull()
                "${value.javaClass.name}@${System.identityHashCode(value)}" +
                    "[id=$idName,attached=${value.isAttachedToWindow}," +
                    "shown=${value.isShown},visibility=${value.visibility}]"
            }
            else -> "${value.javaClass.name}@${System.identityHashCode(value)}"
        }
    }

    private fun debugAppleBooleanField(instance: Any?, name: String): Boolean? =
        instance?.let { value ->
            runCatching { AppleReflection.field(value, name) as? Boolean }.getOrNull()
        }

    private fun debugAppleLyricsAdapterState(adapter: Any?): String {
        if (adapter == null) return "null"
        val itemCount = runCatching {
            (AppleReflection.call(adapter, "i") as? Number)?.toInt()
        }.recoverCatching {
            (AppleReflection.call(adapter, "getItemCount") as? Number)?.toInt()
        }.getOrNull()
        return "${adapter.javaClass.name}@${System.identityHashCode(adapter)}" +
            "[translation=${debugAppleBooleanField(adapter, "d")}," +
            "pronunciation=${debugAppleBooleanField(adapter, "e")}," +
            "itemCount=$itemCount]"
    }

    private fun debugAppleLyricsViewModelState(fragment: Any): String {
        val viewModel = runCatching { AppleReflection.field(fragment, "j1") }.getOrNull()
            ?: return "null"
        fun liveValue(getter: String): Any? = runCatching {
            val liveData = AppleReflection.call(viewModel, getter) ?: return@runCatching null
            AppleReflection.call(liveData, "getValue")
        }.getOrNull()
        return "${viewModel.javaClass.name}@${System.identityHashCode(viewModel)}" +
            "[pronunciationSelected=${liveValue("getPronunciationSelectedLiveResult")}," +
            "pronunciationAvailable=${liveValue("getPronunciationAvailableLiveResult")}," +
            "translationSelected=${liveValue("getTranslationSelectedLiveResult")}," +
            "translationAvailable=${liveValue("getTranslationAvailableLiveResult")}]"
    }

    private fun debugAppleNativePointer(value: Any?): String {
        if (value == null) return "null"
        val address = runCatching {
            (AppleReflection.call(value, "address") as? Number)?.toLong()
        }.getOrNull()
        return "${value.javaClass.name}@${System.identityHashCode(value)}[address=$address]"
    }

    private fun debugApplePronunciationSongState(songNative: Any?): String {
        songNative ?: return "null"
        val languages = nativePronunciationLanguages(songNative)
        val sections = runCatching { AppleReflection.call(songNative, "getSections") }
            .getOrNull()
        val lines = nativeVectorItems(sections, limit = 8).flatMap { section ->
            val lineVector = runCatching { AppleReflection.call(section, "getLines") }
                .getOrNull()
            nativeVectorItems(lineVector, limit = 64)
        }
        val textLines = AppleLyricTextTransform.withRawReads {
            lines.count { line ->
                runCatching {
                    AppleNativeOnlineTranslationStore.sanitizeContent(
                        AppleReflection.call(line, "getHtmlPronunciationLineText") as? String
                    ) != null
                }.getOrDefault(false)
            }
        }
        val wordLines = AppleLyricTextTransform.withRawReads {
            lines.count { line ->
                runCatching {
                    nativeVectorSize(AppleReflection.call(line, "getPronunciationWords")) > 0
                }.getOrDefault(false)
            }
        }
        return "[id=${nativeSongId(songNative)},languages=$languages," +
            "lines=${lines.size},textLines=$textLines,wordLines=$wordLines]"
    }

    private fun logApplePronunciationModelState(
        stage: String,
        viewModel: Any?,
        pointer: Any?,
        songNative: Any?,
    ) {
        if (!BuildConfig.DEBUG) return
        ProviderLogger.diagnostic(
            "Apple pronunciation model: stage=$stage, " +
                "viewModel=${viewModel?.let(::debugAppleLyricsValue)}, " +
                "pointer=${debugAppleNativePointer(pointer)}, " +
                "native=${debugAppleNativePointer(songNative)}, " +
                "state=${debugApplePronunciationSongState(songNative)}, " +
                "currentSongId=$currentAppleLyricsSongId"
        )
    }

    private fun debugAppleLyricsRecyclerFields(instance: Any?): String {
        if (instance == null) return "none"
        return generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filter { isAppleRecyclerViewClass(it.type) }
            .take(8)
            .map { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }
                "${field.declaringClass.simpleName}.${field.name}=${debugAppleLyricsRead(value)}"
            }
            .joinToString(prefix = "[", postfix = "]")
            .ifEmpty { "none" }
    }

    private fun logAppleLyricsRecyclerLifecycle(
        recyclerView: Any,
        stage: String,
    ) {
        if (!BuildConfig.DEBUG || !isAppleLyricsRecyclerView(recyclerView)) return
        val songId = currentAppleLyricsSongId
        ProviderLogger.diagnostic(
            "Apple lyrics Recycler lifecycle: stage=$stage, " +
                "snapshot=${debugRecyclerViewSnapshot(recyclerView)}, " +
                "currentSongId=$songId, " +
                "storeRevision=${nativeOnlineTranslationStore.revision()}, " +
                "hasTranslation=${nativeOnlineTranslationStore.hasTranslation(songId)}, " +
                "hasPronunciation=${nativeOnlineTranslationStore.hasPronunciation(songId)}"
        )
    }

    private fun isAppleLyricsRecyclerView(recyclerView: Any): Boolean {
        val recyclerViewAsView = recyclerView as? View ?: return false
        appleLyricsRecyclerViewClassifications[recyclerViewAsView]?.let { return it }
        val resourceEntryName = runCatching {
            recyclerViewAsView.resources.getResourceEntryName(recyclerViewAsView.id)
        }.getOrNull()
        val isLyrics = resourceEntryName == "lyrics_main_content" ||
            isAppleLyricsRecyclerAdapter(appleRecyclerAdapter(recyclerView))
        appleLyricsRecyclerViewClassifications[recyclerViewAsView] = isLyrics
        return isLyrics
    }

    private fun resolveAppleLyricsRecyclerView(fragment: Any): Any? {
        runCatching {
            AppleReflection.call(fragment, "getRecyclerView")
        }.getOrNull()?.takeIf(::isAppleRecyclerViewInstance)?.let { return it }

        runCatching {
            val binding = AppleReflection.field(fragment, "i0") ?: return@runCatching null
            AppleReflection.field(binding, "a0")
        }.getOrNull()?.takeIf(::isAppleRecyclerViewInstance)?.let { recyclerView ->
            ProviderLogger.debug(
                "Apple Music 歌词 RecyclerView 已解析: source=PlayerLyricsViewFragment.i0.a0"
            )
            return recyclerView
        }

        instanceFieldValues(fragment)
            .filter(::isDataBindingInstance)
            .firstNotNullOfOrNull(::directRecyclerViewField)
            ?.let { recyclerView ->
                ProviderLogger.debug(
                    "Apple Music 歌词 RecyclerView 已解析: source=databinding_scan"
                )
                return recyclerView
            }
        return directRecyclerViewField(fragment)
    }

    private fun directRecyclerViewField(instance: Any): Any? =
        generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { isAppleRecyclerViewClass(it.type) }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()?.takeIf(::isAppleRecyclerViewInstance)
            }

    private fun isDataBindingInstance(instance: Any): Boolean =
        generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .any { it.name == "androidx.databinding.ViewDataBinding" }

    private fun instanceFieldValues(instance: Any): Sequence<Any> =
        generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()
            }

    private fun isAppleRecyclerViewInstance(value: Any): Boolean =
        isAppleRecyclerViewClass(value.javaClass)

    private fun isAppleRecyclerViewClass(clazz: Class<*>): Boolean =
        generateSequence(clazz) { it.superclass }
            .any { it.name == "androidx.recyclerview.widget.RecyclerView" }

    private fun appleRecyclerAdapter(recyclerView: Any): Any? =
        runCatching { AppleReflection.call(recyclerView, "getAdapter") }.getOrNull()

    private fun appleRecyclerAdapterItemCount(adapter: Any): Int =
        runCatching {
            AppleReflection.call(adapter, "getItemCount") as? Number
        }.recoverCatching {
            AppleReflection.call(adapter, "i") as? Number
        }.getOrNull()?.toInt()?.coerceAtLeast(0) ?: 0

    private fun appleRecyclerNotifyDataSetChanged(adapter: Any) {
        runCatching {
            AppleReflection.call(adapter, "notifyDataSetChanged")
        }.recoverCatching {
            AppleReflection.call(adapter, "l")
        }.getOrThrow()
    }

    private fun onlineTranslationForNativeLine(line: Any?): String? {
        if (line == null || !isNativeOnlineTranslationEnabled()) return null
        val begin = (AppleReflection.call(line, "getBegin") as? Number)?.toLong()
            ?: return null
        val end = (AppleReflection.call(line, "getEnd") as? Number)?.toLong()
            ?: return null
        val text = AppleLyricTextTransform.withRawReads {
            AppleReflection.call(line, "getHtmlLineText") as? String
        }
        return nativeOnlineTranslationStore.translation(
            songId = currentAppleLyricsSongId,
            begin = begin,
            end = end,
            text = text,
        )
    }

    private fun onlinePronunciationForNativeLine(line: Any?): String? {
        if (line == null || !isNativeOnlineTranslationEnabled()) return null
        if (shouldHideMandarinPronunciation()) return null
        val begin = (AppleReflection.call(line, "getBegin") as? Number)?.toLong()
            ?: return null
        val end = (AppleReflection.call(line, "getEnd") as? Number)?.toLong()
            ?: return null
        val text = AppleLyricTextTransform.withRawReads {
            AppleReflection.call(line, "getHtmlLineText") as? String
        }
        return RomanizationPolicy.sanitize(
            originalText = text,
            pronunciation = nativeOnlineTranslationStore.pronunciation(
                songId = currentAppleLyricsSongId,
                begin = begin,
                end = end,
                text = text,
            ),
        )
    }

    private fun nativeOriginalLineText(line: Any?): String? {
        if (line == null) return null
        return nativeRawLineText(line, "getHtmlLineText")
    }

    private fun nativeOriginalBackgroundLineText(line: Any?): String? {
        if (line == null) return null
        return nativeRawLineText(line, "getHtmlBackgroundVocalsLineText")
    }

    private fun nativeRawLineText(line: Any, getter: String): String? =
        AppleLyricTextTransform.withRawReads {
            runCatching { AppleReflection.call(line, getter) as? String }.getOrNull()
        }

    private fun nativeRawWordVectorText(vector: Any?): String? =
        AppleLyricTextTransform.withRawReads {
            nativeVectorItems(vector, limit = 256)
                .joinToString(separator = "") { word ->
                    runCatching {
                        AppleReflection.call(word, "getHtmlLineText") as? String
                    }.getOrNull().orEmpty()
                }
                .trim()
                .takeIf(String::isNotEmpty)
        }

    private fun nativeRenderableWordBegins(vector: Any?): List<Int> =
        AppleLyricTextTransform.withRawReads {
            nativeVectorItems(vector, limit = 256).mapNotNull { word ->
                val isWhitespace = runCatching {
                    AppleReflection.call(word, "isWhitespace") as? Boolean
                }.getOrNull() == true
                val text = runCatching {
                    AppleReflection.call(word, "getHtmlLineText") as? String
                }.getOrNull()?.trim().orEmpty()
                val begin = runCatching {
                    (AppleReflection.call(word, "getBegin") as? Number)?.toInt()
                }.getOrNull()
                begin?.takeIf { !isWhitespace && text.isNotEmpty() && it >= 0 }
            }
        }

    /**
     * 创建空发音向量，用于彻底隐藏发音或在主句没有原生 word 时安全降级。
     * 该路径只创建容器，绝不创建缺少父 LyricsLine 的 LyricsWord。
     */
    private fun emptyApplePronunciationWords(
        originalVector: Any?,
        mainWords: Any?,
    ): Any? {
        val vectorClass = originalVector?.javaClass ?: mainWords?.javaClass ?: return null
        return runCatching {
            AppleReflection.newInstance(vectorClass)
        }.onFailure {
            ProviderLogger.error("Apple Music 空发音向量构建失败", it)
        }.getOrNull()
    }

    /** 登记一次性的发音渲染计划；对应 vector 被 Apple 消费后立即移除。 */
    private fun registerApplePronunciationRenderPlan(
        vector: Any,
        pronunciation: String,
    ) {
        synchronized(pendingApplePronunciationRenderPlans) {
            if (pendingApplePronunciationRenderPlans.size >= 256) {
                pendingApplePronunciationRenderPlans.clear()
            }
            pendingApplePronunciationRenderPlans[vector] =
                ApplePronunciationRenderPlan(pronunciation)
        }
    }

    private fun consumeApplePronunciationRenderPlan(vector: Any): ApplePronunciationRenderPlan? =
        synchronized(pendingApplePronunciationRenderPlans) {
            pendingApplePronunciationRenderPlans.remove(vector)
        }

    private fun clearPendingApplePronunciationRenderPlans() {
        synchronized(pendingApplePronunciationRenderPlans) {
            pendingApplePronunciationRenderPlans.clear()
        }
    }

    /**
     * 为 Apple 主句原生 word 生成仅在当前渲染栈生效的发音文本映射。
     * 发音片段沿用每个主句 word 的原生时间，不创建额外 native 对象。
     */
    private fun buildApplePronunciationWordRenderContext(
        vector: Any,
        plan: ApplePronunciationRenderPlan,
    ): ApplePronunciationWordRenderContext? {
        val words = nativeVectorItems(vector, limit = 256)
        val contentWords = words.filterNot { word ->
            runCatching {
                AppleReflection.call(word, "isWhitespace") as? Boolean
            }.getOrNull() == true
        }
        val mainWordTexts = AppleLyricTextTransform.withRawReads {
            contentWords.map { word ->
                runCatching {
                    AppleReflection.call(word, "getHtmlLineText") as? String
                }.getOrNull().orEmpty()
            }
        }
        val segments = ApplePronunciationPolicy.displaySegments(
            pronunciation = plan.pronunciation,
            mainWordTexts = mainWordTexts,
        )
        if (segments.isEmpty()) return null
        reportApplePronunciationRuntimeDiagnostic(
            stage = "word_segments_mapped",
            details = "mainWords=${mainWordTexts.joinToString(prefix = "[", postfix = "]")}, " +
                "segments=${segments.joinToString(prefix = "[", postfix = "]")}",
            dedupeKey = "word_segments_mapped:${mainWordTexts.joinToString("|")}:" +
                plan.pronunciation,
        )

        val lastVisibleSegment = segments.indexOfLast(String::isNotEmpty)
        val displayTextByWord = LinkedHashMap<ApplePronunciationWordKey, String>(words.size)
        words.forEach { word ->
            applePronunciationWordKey(word)?.let { key -> displayTextByWord[key] = "" }
        }
        contentWords.forEachIndexed { index, word ->
            val key = applePronunciationWordKey(word) ?: return@forEachIndexed
            val segment = segments[index]
            displayTextByWord[key] = when {
                segment.isEmpty() -> ""
                index < lastVisibleSegment -> "$segment "
                else -> segment
            }
        }
        return ApplePronunciationWordRenderContext(displayTextByWord)
    }

    private fun hasValidOfficialRomanization(songNative: Any?): Boolean {
        if (songNative == null) return false
        val pronunciationLanguages = nativePronunciationLanguages(songNative)
        rememberApplePronunciationLanguages(songNative, pronunciationLanguages)
        if (
            shouldHideMandarinPronunciation(
                songId = nativeSongId(songNative),
                pronunciationLanguages = pronunciationLanguages,
            )
        ) return false
        val sections = runCatching { AppleReflection.call(songNative, "getSections") }
            .getOrNull() ?: return false
        return nativeVectorItems(sections, limit = 8).any { section ->
            val lines = runCatching { AppleReflection.call(section, "getLines") }
                .getOrNull()
            nativeVectorItems(lines, limit = 64).any { line ->
                val pronunciation = AppleLyricTextTransform.withRawReads {
                    runCatching {
                        AppleReflection.call(line, "getHtmlPronunciationLineText") as? String
                    }.getOrNull()
                }
                RomanizationPolicy.sanitize(
                    originalText = nativeOriginalLineText(line),
                    pronunciation = pronunciation,
                ) != null
            }
        }
    }

    private fun nativeSongId(songNative: Any?): String? = songNative?.let { song ->
        runCatching {
            AppleReflection.call(song, "getAdamId")?.toString()
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun nativePronunciationLanguages(songNative: Any?): List<String> =
        nativeVectorStrings(
            songNative?.let { song ->
                runCatching {
                    AppleReflection.call(song, "getPronunciationLanguages")
                }.getOrNull()
            }
        )

    private fun rememberApplePronunciationLanguages(
        songNative: Any?,
        pronunciationLanguages: Collection<String> = nativePronunciationLanguages(songNative),
    ) {
        val songId = nativeSongId(songNative) ?: return
        val normalized = pronunciationLanguages
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (normalized.isNotEmpty()) {
            applePronunciationLanguagesBySongId[songId] = normalized
        }
    }

    private fun nativeVectorItems(vector: Any?, limit: Int): List<Any> {
        vector ?: return emptyList()
        val size = nativeVectorSize(vector)
        return buildList {
            repeat(minOf(size, limit)) { index ->
                val pointer = runCatching {
                    AppleReflection.call(vector, "get", index.toLong())
                }.recoverCatching {
                    AppleReflection.call(vector, "get", index)
                }.getOrNull() ?: return@repeat
                val value = runCatching {
                    AppleReflection.call(pointer, "get")
                }.getOrNull() ?: pointer
                add(value)
            }
        }
    }

    private fun nativeVectorStrings(vector: Any?, limit: Int = 16): List<String> {
        vector ?: return emptyList()
        val size = minOf(nativeVectorSize(vector), limit)
        return buildList {
            repeat(size) { index ->
                val value = runCatching {
                    AppleReflection.call(vector, "get", index.toLong()) as? String
                }.recoverCatching {
                    AppleReflection.call(vector, "get", index) as? String
                }.getOrNull()?.takeIf(String::isNotBlank)
                if (value != null) add(value)
            }
        }
    }

    private fun nativeVectorSize(vector: Any?): Int = vector?.let {
        runCatching {
            (AppleReflection.call(it, "size") as? Number)?.toInt()
        }.getOrNull()?.coerceAtLeast(0)
    } ?: 0

    private fun hookLyricBuildMethod() {
        val viewModelClass =
            classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
        val loadMethod = AppleReflection.findMethod(viewModelClass, "loadLyrics", parameterCount = 1)
        appleLyricsLoadMethod = loadMethod
        hookRegistrar.install(loadMethod, before = { chain ->
            val item = chain.args.firstOrNull() ?: return@installHook
            val source = if (lyricRequester.ownsViewModel(chain.thisObject)) "module" else "apple"
            if (source == "apple") {
                chain.thisObject?.let { appleLyricsViewModelRef = WeakReference(it) }
                appleLyricsItemRef = WeakReference(item)
            }
            val id = runCatching { AppleReflection.call(item, "getId") }.getOrNull()
            id?.toString()?.let { requestId ->
                pendingLyricsRequestSources
                    .computeIfAbsent(requestId) { ConcurrentLinkedQueue() }
                    .add(source)
            }
            val queueId = runCatching { AppleReflection.call(item, "getQueueId") }.getOrNull()
            val language = runCatching {
                chain.thisObject?.let {
                    AppleReflection.call(it, "getCurrentSystemLyricsLanguage")
                }
            }.getOrNull()
            ProviderLogger.debug(
                "loadLyrics：source=$source, id=$id, queueId=$queueId, language=$language"
            )
        })

        val buildMethod = AppleReflection.findMethod(
            viewModelClass,
            "buildTimeRangeToLyricsMap",
            parameterCount = 1
        )
        hookRegistrar.install(
            buildMethod,
            before = { chain ->
                val pointer = chain.args.firstOrNull() ?: return@installHook
                val songNative = AppleReflection.call(pointer, "get") ?: return@installHook
                ensureAppleLyricTextHooks(songNative)
                applyAppleNativeSupplementSelection(songNative)
                logApplePronunciationModelState(
                    stage = "build_before",
                    viewModel = chain.thisObject,
                    pointer = pointer,
                    songNative = songNative,
                )
            },
            after = { chain, _ ->
                val pointer = chain.args.firstOrNull() ?: return@installHook
                val songNative = AppleReflection.call(pointer, "get") ?: return@installHook

                val source = if (lyricRequester.ownsViewModel(chain.thisObject)) "module" else "apple"
                val songId = nativeSongId(songNative)
                logApplePronunciationModelState(
                    stage = "build_after:$source",
                    viewModel = chain.thisObject,
                    pointer = pointer,
                    songNative = songNative,
                )
                val visibleSongId = currentAppleLyricsSongId
                    ?.takeIf { source == "apple" && it == songId }
                mainHandler.post {
                    PlaybackManager.onLyricsBuilt(
                        nativeSongObj = songNative,
                        source = source,
                        visibleSongId = visibleSongId,
                        playbackSongId = currentPlaybackQueueMediaId(),
                    )
                    if (
                        source == "apple" &&
                        !songId.isNullOrBlank() &&
                        songId == currentAppleLyricsSongId
                    ) {
                        refreshActiveOnlineSourceMenu(songId)
                    }
                }
                applyConfiguredContentUiLanguage()
                val onlineTranslation =
                    songId?.let(nativeOnlineTranslationStore::hasTranslation) == true
                val onlinePronunciation =
                    songId?.let(nativeOnlineTranslationStore::hasPronunciation) == true
                val officialPronunciation = source == "apple" &&
                    hasValidOfficialRomanization(songNative)
                if (
                    ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild(
                        sourceIsApple = source == "apple",
                        hasValidOfficialPronunciation = officialPronunciation,
                        hasOnlineTranslation = onlineTranslation,
                        hasOnlinePronunciation = onlinePronunciation,
                        pronunciationSelected = PreferencesMonitor.isPronunciationSelected(),
                    )
                ) {
                    ProviderLogger.debug(
                        "Apple Music 歌词模型完成后请求补充轨道刷新: " +
                            "id=$songId, officialPronunciation=$officialPronunciation, " +
                            "onlineTranslation=$onlineTranslation, " +
                            "onlinePronunciation=$onlinePronunciation, " +
                            "pronunciationSelected=${PreferencesMonitor.isPronunciationSelected()}"
                    )
                    refreshAppleLyricsSupplementPresentation(songId)
                }
            }
        )
        ProviderLogger.debug("歌词构建 Hook 已安装")
    }

    private fun hookAppleNativeLyricsPresentation() {
        val fragmentClass =
            classLoader.loadClass("com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
        val method = AppleReflection.findMethod(fragmentClass, "R2", parameterCount = 1)
        appleLyricsPresentationMethod = method
        hookRegistrar.install(
            method,
            before = { chain ->
                val fragment = chain.thisObject ?: return@installHook
                val pointer = chain.args.firstOrNull() ?: return@installHook
                appleLyricsFragmentRef = WeakReference(fragment)
                appleLyricsSongPointerRef = WeakReference(pointer)
                val songNative = runCatching {
                    AppleReflection.call(pointer, "get")
                }.getOrNull() ?: return@installHook
                val songId = nativeSongId(songNative)
                if (currentAppleLyricsSongId != songId) {
                    clearPendingApplePronunciationRenderPlans()
                    currentAppleLyricsSongId = songId
                }
                ensureAppleLyricTextHooks(songNative)
                logAppleLyricsUiState(
                    fragment = fragment,
                    stage = "presentation_before",
                    expectedSongId = songId,
                )
            },
            after = { chain, _ ->
                val fragment = chain.thisObject ?: return@installHook
                val pointer = chain.args.firstOrNull() ?: return@installHook
                val songNative = runCatching {
                    AppleReflection.call(pointer, "get")
                }.getOrNull() ?: return@installHook
                val songId = nativeSongId(songNative) ?: return@installHook
                if (currentAppleLyricsSongId != songId) {
                    clearPendingApplePronunciationRenderPlans()
                    currentAppleLyricsSongId = songId
                }
                logAppleLyricsUiState(
                    fragment = fragment,
                    stage = "presentation_after",
                    expectedSongId = songId,
                )
                scheduleAppleLyricsBlur(resolveAppleLyricsRecyclerView(fragment))
                mainHandler.post {
                    PlaybackManager.onLyricsBuilt(
                        nativeSongObj = songNative,
                        source = "apple",
                        visibleSongId = songId,
                        playbackSongId = currentPlaybackQueueMediaId(),
                    )
                }
            },
        )
        ProviderLogger.debug("Apple Music 原生歌词呈现 Hook 已安装")
    }

    private fun hookAppleSystemFontWeight() {
        val installedHooks = mutableListOf<String>()
        val failedHooks = mutableListOf<String>()
        val customTextViewClass = runCatching {
            classLoader.loadClass("com.apple.android.music.common.views.CustomTextView")
        }.getOrNull()

        runCatching {
            val getFont = Resources::class.java.getDeclaredMethod(
                "getFont",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            hookRegistrar.installResultOverride(getFont) { chain, original ->
                val resources = chain.thisObject as? Resources
                    ?: return@installResultOverrideHook original
                val resourceId = (chain.args.firstOrNull() as? Number)?.toInt()
                    ?: return@installResultOverrideHook original
                val typeface = original as? Typeface
                    ?: return@installResultOverrideHook original
                replaceAppleFontResource(resources, resourceId, typeface)
            }
            installedHooks += "Resources.getFont"
        }.onFailure { throwable ->
            failedHooks += "Resources.getFont:${throwable.javaClass.simpleName}"
        }

        runCatching {
            val createWithWeight = Typeface::class.java.getDeclaredMethod(
                "create",
                Typeface::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            hookRegistrar.installResultOverride(createWithWeight) { chain, originalResult ->
                if (
                    appleSystemFontApplyGuard.isActive ||
                    !isFollowSystemFontWeightEnabled()
                ) {
                    return@installResultOverrideHook originalResult
                }
                val base = chain.args.getOrNull(0) as? Typeface
                    ?: return@installResultOverrideHook originalResult
                val originalTypeface = originalAppleTypeface(base)
                    ?: return@installResultOverrideHook originalResult
                val requestedWeight = (chain.args.getOrNull(1) as? Number)?.toInt()
                    ?: originalTypeface.weight
                val italic = chain.args.getOrNull(2) as? Boolean
                    ?: originalTypeface.isItalic
                createAppleWeightAdjustedTypeface(
                    original = originalTypeface,
                    requestedWeight = requestedWeight,
                    italic = italic,
                ).also { replacement ->
                    logAppleSystemFontReplacement(
                        stage = "typeface_create",
                        resourceName = null,
                        original = originalTypeface,
                        replacement = replacement,
                        requestedWeight = requestedWeight,
                    )
                }
            }
            installedHooks += "Typeface.create(weight)"
        }.onFailure { throwable ->
            failedHooks += "Typeface.create(weight):${throwable.javaClass.simpleName}"
        }

        runCatching {
            val setTypeface = TextView::class.java.getDeclaredMethod(
                "setTypeface",
                Typeface::class.java,
            ).apply { isAccessible = true }
            hookRegistrar.installArgumentRewrite(setTypeface) { chain ->
                if (appleSystemFontApplyGuard.isActive) {
                    return@installArgumentRewriteHook null
                }
                val view = chain.thisObject as? TextView
                    ?: return@installArgumentRewriteHook null
                val requested = chain.args.firstOrNull() as? Typeface
                    ?: return@installArgumentRewriteHook null
                val originalTypeface = originalAppleTypeface(requested)
                    ?: return@installArgumentRewriteHook null
                synchronized(appleSystemFontTrackedTextViews) {
                    appleSystemFontTrackedTextViews[view] =
                        AppleSystemFontTextViewState(
                            originalTypeface = originalTypeface,
                            requestedWeight = originalTypeface.weight,
                            italic = originalTypeface.isItalic,
                            originalStyle = originalTypeface.style,
                        )
                }
                if (!isFollowSystemFontWeightEnabled()) {
                    return@installArgumentRewriteHook if (requested === originalTypeface) {
                        null
                    } else {
                        arrayOf(originalTypeface)
                    }
                }
                val replacement = createAppleWeightAdjustedTypeface(
                    original = originalTypeface,
                    textView = view,
                )
                logAppleSystemFontReplacement(
                    stage = "text_view",
                    resourceName = null,
                    original = originalTypeface,
                    replacement = replacement,
                    requestedWeight = originalTypeface.weight,
                )
                if (replacement === requested) null else arrayOf(replacement)
            }
            installedHooks += "TextView.setTypeface"
        }.onFailure { throwable ->
            failedHooks += "TextView.setTypeface:${throwable.javaClass.simpleName}"
        }

        runCatching {
            val styledTypefaceOwner = customTextViewClass?.superclass
                ?: error("CustomTextView superclass unavailable")
            val styledTypeface = styledTypefaceOwner.getDeclaredMethod(
                "setTypeface",
                Typeface::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            hookRegistrar.installArgumentRewrite(styledTypeface) { chain ->
                if (appleSystemFontApplyGuard.isActive) {
                    return@installArgumentRewriteHook null
                }
                val view = chain.thisObject as? TextView
                    ?: return@installArgumentRewriteHook null
                val requested = chain.args.getOrNull(0) as? Typeface
                    ?: return@installArgumentRewriteHook null
                val requestedStyle = (chain.args.getOrNull(1) as? Number)?.toInt()
                    ?: Typeface.NORMAL
                val originalTypeface = originalAppleTypeface(requested)
                    ?: return@installArgumentRewriteHook null
                val bold = requestedStyle and Typeface.BOLD != 0
                val italic = originalTypeface.isItalic ||
                    requestedStyle and Typeface.ITALIC != 0
                val requestedWeight = if (bold) {
                    maxOf(originalTypeface.weight, 700)
                } else {
                    originalTypeface.weight
                }
                synchronized(appleSystemFontTrackedTextViews) {
                    appleSystemFontTrackedTextViews[view] =
                        AppleSystemFontTextViewState(
                            originalTypeface = originalTypeface,
                            requestedWeight = requestedWeight,
                            italic = italic,
                            originalStyle = requestedStyle,
                        )
                }
                if (!isFollowSystemFontWeightEnabled()) {
                    return@installArgumentRewriteHook if (requested === originalTypeface) {
                        null
                    } else {
                        arrayOf(originalTypeface, requestedStyle)
                    }
                }
                val replacement = createAppleWeightAdjustedTypeface(
                    original = originalTypeface,
                    requestedWeight = requestedWeight,
                    italic = italic,
                    textView = view,
                )
                logAppleSystemFontReplacement(
                    stage = "custom_text_view_style",
                    resourceName = null,
                    original = originalTypeface,
                    replacement = replacement,
                    requestedWeight = requestedWeight,
                )
                arrayOf(replacement, Typeface.NORMAL)
            }
            installedHooks += "CustomTextView.setTypeface(style)"
        }.onFailure { throwable ->
            failedHooks += "CustomTextView.setTypeface(style):${throwable.javaClass.simpleName}"
        }

        runCatching {
            val setText = TextView::class.java.getDeclaredMethod(
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java,
            ).apply { isAccessible = true }
            hookRegistrar.install(setText, after = { chain, _ ->
                val view = chain.thisObject as? TextView ?: return@installHook
                val text = chain.args.firstOrNull() as? CharSequence
                applyAppleSystemFontForTextView(
                    view = view,
                    textOverride = text,
                    stage = "text_view_set_text",
                )
            })
            installedHooks += "TextView.setText(CharSequence,BufferType)"
        }.onFailure { throwable ->
            failedHooks += "TextView.setText(CharSequence,BufferType):${throwable.javaClass.simpleName}"
        }

        runCatching {
            val customSetText = customTextViewClass?.getDeclaredMethod(
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java,
            )?.apply { isAccessible = true }
                ?: error("CustomTextView.setText(CharSequence,BufferType) unavailable")
            hookRegistrar.install(customSetText, after = { chain, _ ->
                val view = chain.thisObject as? TextView ?: return@installHook
                val text = chain.args.firstOrNull() as? CharSequence
                applyAppleSystemFontForTextView(
                    view = view,
                    textOverride = text,
                    stage = "custom_text_view_set_text",
                )
            })
            installedHooks += "CustomTextView.setText(CharSequence,BufferType)"
        }.onFailure { throwable ->
            failedHooks += "CustomTextView.setText(CharSequence,BufferType):${throwable.javaClass.simpleName}"
        }

        runCatching {
            val futureOwner = customTextViewClass?.superclass
                ?: error("CustomTextView Future owner unavailable")
            val futureField = futureOwner.declaredFields.firstOrNull { field ->
                java.util.concurrent.Future::class.java.isAssignableFrom(field.type)
            }?.apply { isAccessible = true }
                ?: error("CustomTextView Future field unavailable")
            // 不依赖 JADX 的 p301q.A 展示包名，只从真实 CustomTextView superclass 取 Future 解析方法。
            val resolveFuture = futureOwner.declaredMethods.firstOrNull { method ->
                method.name == "f" &&
                    method.parameterCount == 0 &&
                    method.returnType == Void.TYPE
            }?.apply { isAccessible = true }
                ?: error("CustomTextView Future resolver f() unavailable")
            hookRegistrar.installScoped(
                executable = resolveFuture,
                enter = { chain ->
                    val view = chain.thisObject as? TextView ?: return@installScopedHook false
                    runCatching { futureField.get(view) != null }.getOrDefault(false)
                },
                after = { chain, _ ->
                    val view = chain.thisObject as? TextView ?: return@installScopedHook
                    applyAppleSystemFontForTextView(
                        view = view,
                        stage = "text_future_resolved",
                    )
                },
                exit = { Unit },
            )
            installedHooks += "CustomTextView.FutureResolver"
        }.onFailure { throwable ->
            failedHooks += "CustomTextView.FutureResolver:${throwable.javaClass.simpleName}"
        }

        runCatching {
            val getTextMetricsParams = TextView::class.java.getDeclaredMethod(
                "getTextMetricsParams"
            ).apply { isAccessible = true }
            hookRegistrar.install(getTextMetricsParams, before = { chain ->
                val measurementText = appleSystemFontLyricsMeasurementTexts.current
                    ?: return@installHook
                val view = chain.thisObject as? TextView ?: return@installHook
                // Apple 创建 PrecomputedText Future 前必须先固定最终字体，否则 Future 会缓存旧字宽。
                applyAppleSystemFontForTextView(
                    view = view,
                    textOverride = measurementText,
                    stage = "lyrics_text_metrics",
                    requestLayout = false,
                )
            })
            installedHooks += "TextView.getTextMetricsParams"
        }.onFailure { throwable ->
            failedHooks += "TextView.getTextMetricsParams:${throwable.javaClass.simpleName}"
        }

        hookAppleLyricsWordFontMeasurement(
            customTextViewClass = customTextViewClass,
            installedHooks = installedHooks,
            failedHooks = failedHooks,
        )
        hookAppleLyricsMeasureTextDiagnostics(
            installedHooks = installedHooks,
            failedHooks = failedHooks,
        )
        hookAppleLyricsGradientDiagnostics(
            installedHooks = installedHooks,
            failedHooks = failedHooks,
        )

        if (BuildConfig.DEBUG) {
            runCatching {
                val onDraw = TextView::class.java.getDeclaredMethod(
                    "onDraw",
                    Canvas::class.java,
                ).apply { isAccessible = true }
                hookRegistrar.install(onDraw, before = { chain ->
                    val view = chain.thisObject as? TextView ?: return@installHook
                    logAppleSystemFontDrawState(view)
                })
                installedHooks += "TextView.onDraw[debug]"
            }.onFailure { throwable ->
                failedHooks += "TextView.onDraw:${throwable.javaClass.simpleName}"
            }

            runCatching {
                val onDraw = customTextViewClass?.getDeclaredMethod(
                    "onDraw",
                    Canvas::class.java,
                )?.apply { isAccessible = true }
                    ?: error("CustomTextView.onDraw unavailable")
                hookRegistrar.install(onDraw, before = { chain ->
                    val view = chain.thisObject as? TextView ?: return@installHook
                    logAppleSystemFontDrawState(view)
                })
                installedHooks += "CustomTextView.onDraw[debug]"
            }.onFailure { throwable ->
                failedHooks += "CustomTextView.onDraw:${throwable.javaClass.simpleName}"
            }
        }

        hookAppleComposeSystemFontWeight(
            installedHooks = installedHooks,
            failedHooks = failedHooks,
        )

        if (installedHooks.isNotEmpty()) {
            ProviderLogger.info(
                "Apple 系统字体粗细 Hook 已安装：hooks=${installedHooks.joinToString()}, " +
                    "enabled=${isFollowSystemFontWeightEnabled()}"
            )
        }
        if (failedHooks.isNotEmpty()) {
            ProviderLogger.error(
                "Apple 系统字体粗细 Hook 安装不完整：${failedHooks.joinToString()}"
            )
        }
    }

    private fun hookAppleComposeSystemFontWeight(
        installedHooks: MutableList<String>,
        failedHooks: MutableList<String>,
    ) {
        runCatching {
            val typefaceFactoryClass = hookResolver.resolveClasses(
                AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS
            ).firstOrNull()?.clazz
                ?: error("Apple text style utils unavailable")
            val typefaceFactoryMethod = typefaceFactoryClass.declaredMethods.first { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.returnType == Typeface::class.java &&
                    method.parameterTypes.size == 4 &&
                    method.parameterTypes.firstOrNull() == android.content.Context::class.java
            }.apply { isAccessible = true }
            hookRegistrar.installResultOverride(typefaceFactoryMethod) { _, original ->
                (original as? Typeface)?.let(appleSystemFontManagedTypefaces::add)
                original
            }
            installedHooks += "ComposeTypefaceFactory.${typefaceFactoryMethod.name}"
        }.onFailure { throwable ->
            failedHooks += "ComposeTypefaceFactory:${throwable.javaClass.simpleName}"
        }

        hookResolver.resolveClasses(AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT)
            .forEach { resolvedClass ->
            val className = resolvedClass.target.className
            runCatching {
                val layoutClass = resolvedClass.clazz
                val constructors = layoutClass.declaredConstructors.filter { constructor ->
                    val parameterTypes = constructor.parameterTypes
                    parameterTypes.firstOrNull() == CharSequence::class.java &&
                        parameterTypes.any(TextPaint::class.java::isAssignableFrom)
                }
                check(constructors.isNotEmpty()) {
                    "Compose text layout constructor unavailable: $className"
                }
                constructors.forEachIndexed { index, constructor ->
                    constructor.isAccessible = true
                    val paintIndex = constructor.parameterTypes.indexOfFirst(
                        TextPaint::class.java::isAssignableFrom,
                    )
                    hookRegistrar.installArgumentRewrite(constructor) { chain ->
                        if (appleSystemFontApplyGuard.isActive) {
                            return@installArgumentRewriteHook null
                        }
                        val text = chain.args.firstOrNull() as? CharSequence
                            ?: return@installArgumentRewriteHook null
                        val paint = chain.args.getOrNull(paintIndex) as? TextPaint
                            ?: return@installArgumentRewriteHook null
                        val rewritten = rewriteAppleSystemFontLayoutInput(
                            text = text,
                            paint = paint,
                        ) ?: return@installArgumentRewriteHook null
                        chain.args.toTypedArray().also { args ->
                            args[0] = rewritten.text
                            args[paintIndex] = rewritten.paint
                        }
                    }
                    installedHooks += "ComposeTextLayout.$className#$index"
                }
            }.onFailure { throwable ->
                failedHooks += "$className:${throwable.javaClass.simpleName}"
            }
        }
    }

    private fun hookAppleLyricsWordFontMeasurement(
        customTextViewClass: Class<*>?,
        installedHooks: MutableList<String>,
        failedHooks: MutableList<String>,
    ) {
        customTextViewClass ?: run {
            failedHooks += "LyricsWordFontMeasurement:CustomTextViewUnavailable"
            return
        }
        hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER)
            .forEach { resolvedClass ->
            val className = resolvedClass.target.className
            val adapterClass = resolvedClass.clazz
            runCatching {
                val methods = generateSequence(adapterClass) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .filter { method ->
                        method.returnType.name == "android.util.ArrayMap" &&
                            method.parameterTypes.firstOrNull()?.name ==
                            "com.apple.android.music.ttml.javanative.model.LyricsWordVector"
                    }
                    .distinctBy { method ->
                        method.name to method.parameterTypes.joinToString { it.name }
                    }
                    .toList()
                methods.forEach { method ->
                    if (!appleSystemFontLyricsRenderHookedMethods.add(method)) {
                        return@forEach
                    }
                    method.isAccessible = true
                    hookRegistrar.installScoped(
                        executable = method,
                        enter = enter@{ chain ->
                            val measurementText = nativeRawWordVectorText(
                                chain.args.firstOrNull()
                            )?.takeIf(
                                AppleSystemFontWeightPolicy::shouldReplaceTextContent
                            ) ?: return@enter false
                            appleSystemFontLyricsMeasurementTexts.push(measurementText)
                            applyAppleLyricsTemplateFontsForMeasurement(
                                adapter = chain.thisObject,
                                sampleText = measurementText,
                            )
                            true
                        },
                        after = { _, _ -> Unit },
                        exit = { appleSystemFontLyricsMeasurementTexts.pop() },
                    )
                    installedHooks +=
                        "LyricsWordFontMeasurement.${method.declaringClass.name}#${method.name}"
                }
            }.onFailure { throwable ->
                failedHooks += "LyricsWordFontMeasurement.$className:${throwable.javaClass.simpleName}"
            }
        }
    }

    private fun hookAppleLyricsMeasureTextDiagnostics(
        installedHooks: MutableList<String>,
        failedHooks: MutableList<String>,
    ) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val measureText = Paint::class.java.getDeclaredMethod(
                "measureText",
                String::class.java,
            ).apply { isAccessible = true }
            hookRegistrar.install(measureText, after = { chain, result ->
                if (appleSystemFontLyricsMeasureDiagnosticGuard.isActive) {
                    return@installHook
                }
                val lineText = appleSystemFontLyricsMeasurementTexts.current
                    ?: return@installHook
                val measuredText = chain.args.firstOrNull() as? String
                    ?: return@installHook
                val paint = chain.thisObject as? Paint ?: return@installHook
                val currentTypeface = paint.typeface ?: return@installHook
                val actualWidth = (result as? Number)?.toFloat() ?: return@installHook
                val expectedTypeface = appleSystemTypefaceForText(
                    current = currentTypeface,
                    text = lineText,
                    textSizePx = paint.textSize,
                ) ?: return@installHook
                val expectedWidth = appleSystemFontLyricsMeasureDiagnosticGuard.run {
                    Paint(paint).apply { typeface = expectedTypeface }
                        .measureText(measuredText)
                }
                val originalTypeface = synchronized(appleSystemFontOriginalTypefacesByReplacement) {
                    appleSystemFontOriginalTypefacesByReplacement[currentTypeface]
                }
                if (originalTypeface != null && originalTypeface !== currentTypeface) {
                    val originalWidth = appleSystemFontLyricsMeasureDiagnosticGuard.run {
                        Paint(paint).apply { typeface = originalTypeface }
                            .measureText(measuredText)
                    }
                    val baselineDelta = actualWidth - originalWidth
                    val baselineKey = listOf(
                        measuredText.take(32),
                        paint.textSize.roundToInt(),
                        currentTypeface.weight,
                        originalTypeface.weight,
                        (baselineDelta * 10f).roundToInt(),
                    ).joinToString(":")
                    if (
                        appleSystemFontLyricsMeasureBaselineKeys.size < 256 &&
                        appleSystemFontLyricsMeasureBaselineKeys.add(baselineKey)
                    ) {
                        ProviderLogger.diagnostic(
                            "Apple 歌词逐字字体宽度基线: text=${measuredText.take(48)}, " +
                                "line=${lineText.take(48)}, textSizePx=${paint.textSize}, " +
                                "originalWeight=${originalTypeface.weight}, " +
                                "replacementWeight=${currentTypeface.weight}, " +
                                "originalWidth=$originalWidth, replacementWidth=$actualWidth, " +
                                "delta=$baselineDelta"
                        )
                    }
                }
                val currentSignature = synchronized(appleSystemFontSignaturesByReplacement) {
                    appleSystemFontSignaturesByReplacement[currentTypeface]
                }
                val expectedSignature = synchronized(appleSystemFontSignaturesByReplacement) {
                    appleSystemFontSignaturesByReplacement[expectedTypeface]
                }
                val sameTypeface = currentTypeface === expectedTypeface ||
                    currentSignature != null && currentSignature == expectedSignature
                val widthDelta = expectedWidth - actualWidth
                if (sameTypeface && abs(widthDelta) < 0.25f) return@installHook
                val diagnosticKey = listOf(
                    measuredText.take(32),
                    currentTypeface.weight,
                    expectedTypeface.weight,
                    (widthDelta * 10f).roundToInt(),
                ).joinToString(":")
                if (appleSystemFontLyricsMeasureDiagnosticKeys.size >= 256 ||
                    !appleSystemFontLyricsMeasureDiagnosticKeys.add(diagnosticKey)
                ) {
                    return@installHook
                }
                ProviderLogger.diagnostic(
                    "Apple 歌词逐字测量诊断: text=${measuredText.take(48)}, " +
                        "line=${lineText.take(48)}, textSizePx=${paint.textSize}, " +
                        "currentWeight=${currentTypeface.weight}, " +
                        "expectedWeight=${expectedTypeface.weight}, " +
                        "sameTypeface=$sameTypeface, actualWidth=$actualWidth, " +
                        "expectedWidth=$expectedWidth, delta=$widthDelta"
                )
            })
            installedHooks += "Paint.measureText(String)[debug]"
        }.onFailure { throwable ->
            failedHooks += "Paint.measureText(String):${throwable.javaClass.simpleName}"
        }
    }

    private fun hookAppleLyricsGradientDiagnostics(
        installedHooks: MutableList<String>,
        failedHooks: MutableList<String>,
    ) {
        if (!BuildConfig.DEBUG) return

        runCatching {
            val getAnimatedFraction = ValueAnimator::class.java.getDeclaredMethod(
                "getAnimatedFraction",
            ).apply { isAccessible = true }
            hookRegistrar.install(getAnimatedFraction, after = { chain, result ->
                if (!isFollowSystemFontWeightEnabled()) return@installHook
                val animator = chain.thisObject as? ValueAnimator ?: return@installHook
                val fraction = (result as? Number)?.toFloat() ?: return@installHook
                appleLyricsGradientAnimatorSample.set(
                    AppleLyricsGradientAnimatorSample(
                        animatorIdentity = System.identityHashCode(animator),
                        animatedFraction = fraction,
                        currentPlayTimeMs = animator.currentPlayTime,
                        durationMs = animator.duration,
                        capturedAtUptimeMs = SystemClock.uptimeMillis(),
                    )
                )
            })
            installedHooks += "ValueAnimator.getAnimatedFraction[lyrics-gradient-debug]"
        }.onFailure { throwable ->
            failedHooks +=
                "ValueAnimator.getAnimatedFraction:${throwable.javaClass.simpleName}"
        }

        runCatching {
            val layoutClass = classLoader.loadClass(
                "com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout"
            )
            val maskClass = classLoader.loadClass(
                "com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout\$a"
            )
            val updateMask = maskClass.getDeclaredMethod(
                "b",
                IntArray::class.java,
                FloatArray::class.java,
                Float::class.javaObjectType,
            ).apply { isAccessible = true }
            val startChildField = maskClass.getDeclaredField("c").apply { isAccessible = true }
            val endChildField = maskClass.getDeclaredField("d").apply { isAccessible = true }
            val positionsField = maskClass.getDeclaredField("h").apply { isAccessible = true }
            val fractionField = maskClass.getDeclaredField("i").apply { isAccessible = true }
            val layoutField = maskClass.declaredFields.first { field ->
                field.type == layoutClass
            }.apply { isAccessible = true }

            hookRegistrar.install(updateMask, after = { chain, _ ->
                if (!isFollowSystemFontWeightEnabled()) return@installHook
                val mask = chain.thisObject ?: return@installHook
                val inputFraction = (chain.args.getOrNull(2) as? Number)?.toFloat()
                    ?: return@installHook
                val positions = (positionsField.get(mask) as? FloatArray)
                    ?.copyOf()
                    ?: (chain.args.getOrNull(1) as? FloatArray)?.copyOf()
                    ?: return@installHook
                val now = SystemClock.uptimeMillis()
                val maskIdentity = System.identityHashCode(mask)
                val lastLoggedAt = appleLyricsGradientLastLogAt[maskIdentity] ?: Long.MIN_VALUE
                if (now - lastLoggedAt < 100L) return@installHook
                appleLyricsGradientLastLogAt[maskIdentity] = now

                val animatorSample = appleLyricsGradientAnimatorSample.get()
                    ?.takeIf { now - it.capturedAtUptimeMs <= 16L }
                val layout = layoutField.get(mask) as? View
                val storedFraction = (fractionField.get(mask) as? Number)?.toFloat()
                val startChild = (startChildField.get(mask) as? Number)?.toInt()
                val endChild = (endChildField.get(mask) as? Number)?.toInt()
                ProviderLogger.diagnostic(
                    "Apple 歌词逐字渐变诊断: mask=$maskIdentity, " +
                        "layout=${layout?.let(System::identityHashCode)}, " +
                        "childRange=$startChild..$endChild, inputFraction=$inputFraction, " +
                        "storedFraction=$storedFraction, positions=${positions.contentToString()}, " +
                        "animator=${animatorSample?.animatorIdentity}, " +
                        "animatorFraction=${animatorSample?.animatedFraction}, " +
                        "playTime=${animatorSample?.currentPlayTimeMs}, " +
                        "duration=${animatorSample?.durationMs}, " +
                        "playbackPosition=${appleLyricsCurrentPlaybackPositionMs()}, " +
                        "lyricsSongId=$currentAppleLyricsSongId, " +
                        "fontScale=${currentMiuiFontWeightScale()}"
                )
            })
            installedHooks +=
                "FullWidthAlphaGradientFlexboxLayout.Mask#b[lyrics-gradient-debug]"
        }.onFailure { throwable ->
            failedHooks +=
                "FullWidthAlphaGradientFlexboxLayout.Mask#b:${throwable.javaClass.simpleName}"
        }
    }

    private fun applyAppleLyricsTemplateFontsForMeasurement(
        adapter: Any?,
        sampleText: String,
    ) {
        adapter ?: return
        val paths = appleSystemFontLyricsTemplateFieldPaths.getOrPut(adapter.javaClass) {
            resolveAppleLyricsTemplateFieldPaths(adapter.javaClass)
        }
        if (paths.isEmpty()) return
        var applied = 0
        paths.forEach { path ->
            val view = path.get(adapter) ?: return@forEach
            applyAppleSystemFontForTextView(
                view = view,
                textOverride = sampleText,
                stage = "lyrics_word_template_measure",
                requestLayout = false,
            )
            applied += 1
        }
        if (BuildConfig.DEBUG && applied > 0) {
            val traceKey = "lyrics_template_font:${adapter.javaClass.name}:$applied"
            if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                ProviderLogger.debug(
                    "Apple 歌词逐字模板测量字体已同步：adapter=${adapter.javaClass.name}, " +
                        "views=$applied, text=${sampleText.take(24)}"
                )
            }
        }
    }

    private fun resolveAppleLyricsTemplateFieldPaths(
        adapterClass: Class<*>,
    ): List<AppleSystemFontTemplateFieldPath> {
        val bindingBaseClass = runCatching {
            classLoader.loadClass("androidx.databinding.ViewDataBinding")
        }.getOrNull()
        return generateSequence(adapterClass) { it.superclass }
            .flatMap { owner -> owner.declaredFields.asSequence() }
            .mapNotNull { bindingField ->
                if (bindingBaseClass == null ||
                    !bindingBaseClass.isAssignableFrom(bindingField.type)
                ) {
                    return@mapNotNull null
                }
                val textField = bindingField.type.declaredFields.firstOrNull { field ->
                    TextView::class.java.isAssignableFrom(field.type) &&
                        field.type.name ==
                        "com.apple.android.music.common.views.CustomTextView"
                } ?: return@mapNotNull null
                runCatching {
                    bindingField.isAccessible = true
                    textField.isAccessible = true
                    AppleSystemFontTemplateFieldPath(bindingField, textField)
                }.getOrNull()
            }
            .toList()
    }

    private fun rewriteAppleSystemFontLayoutInput(
        text: CharSequence,
        paint: TextPaint,
    ): AppleSystemFontLayoutInput? {
        if (!AppleSystemFontWeightPolicy.shouldReplaceTextContent(text)) return null

        val rewrittenPaint = TextPaint(paint)
        var paintChanged = false
        val originalPaintTypeface = rewrittenPaint.typeface
        val replacementPaintTypeface = appleSystemTypefaceForText(
            current = originalPaintTypeface,
            text = text,
            textSizePx = rewrittenPaint.textSize,
        )
        if (
            replacementPaintTypeface != null &&
            replacementPaintTypeface !== originalPaintTypeface
        ) {
            rewrittenPaint.typeface = replacementPaintTypeface
            paintChanged = true
        }

        var rewrittenText: CharSequence = text
        var rewrittenSpanCount = 0
        if (text is Spanned) {
            var spannable: SpannableString? = null
            text.getSpans(0, text.length, MetricAffectingSpan::class.java)
                .forEach { span ->
                    val currentSpanTypeface = metricSpanTypeface(span)
                        ?: return@forEach
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    if (start < 0 || end <= start || end > text.length) return@forEach
                    val replacementSpanTypeface = appleSystemTypefaceForText(
                        current = currentSpanTypeface,
                        text = text.subSequence(start, end),
                        textSizePx = rewrittenPaint.textSize,
                    ) ?: return@forEach
                    if (replacementSpanTypeface === currentSpanTypeface) return@forEach
                    val replacementSpan = createTypefaceMetricSpan(
                        original = span,
                        replacement = replacementSpanTypeface,
                    ) ?: return@forEach
                    val mutableText = spannable ?: SpannableString(text).also {
                        spannable = it
                    }
                    val flags = text.getSpanFlags(span)
                    mutableText.removeSpan(span)
                    mutableText.setSpan(replacementSpan, start, end, flags)
                    rewrittenSpanCount += 1
                }
            spannable?.let { rewrittenText = it }
        }

        if (!paintChanged && rewrittenSpanCount == 0) return null
        if (BuildConfig.DEBUG) {
            val traceKey = listOf(
                "compose_font_layout",
                originalPaintTypeface?.weight,
                rewrittenPaint.typeface?.weight,
                AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(text),
                rewrittenSpanCount,
                isFollowSystemFontWeightEnabled(),
            ).joinToString(":")
            if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                ProviderLogger.debug(
                    "Apple Compose 字体粗细布局：paintChanged=$paintChanged, " +
                        "spans=$rewrittenSpanCount, " +
                        "cjkFallback=" +
                        AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(text) +
                        ", textSizePx=${rewrittenPaint.textSize}, " +
                        "enabled=${isFollowSystemFontWeightEnabled()}"
                )
            }
        }
        return AppleSystemFontLayoutInput(
            text = rewrittenText,
            paint = rewrittenPaint,
        )
    }

    private fun appleSystemTypefaceForText(
        current: Typeface?,
        text: CharSequence,
        textSizePx: Float,
    ): Typeface? {
        current ?: return null
        val request = appleSystemFontRequest(current) ?: return current
        if (
            !isFollowSystemFontWeightEnabled() ||
            !AppleSystemFontWeightPolicy.shouldReplaceTextContent(text)
        ) {
            return request.original
        }

        val effectiveWeight = mappedAppleSystemFontWeight(request.semanticWeight)
        val expectedSignature = AppleSystemFontReplacementSignature(
            effectiveSfProWeight = effectiveWeight,
            semanticWeight = request.semanticWeight,
            usesCjkFallback = AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(text),
            italic = request.italic,
        )
        val currentSignature = synchronized(appleSystemFontSignaturesByReplacement) {
            appleSystemFontSignaturesByReplacement[current]
        }
        if (currentSignature == expectedSignature) return current

        return createAppleWeightAdjustedTypeface(
            original = request.original,
            requestedWeight = request.semanticWeight,
            italic = request.italic,
            text = text,
            textSizePx = textSizePx,
        )
    }

    private fun appleSystemFontRequest(typeface: Typeface): AppleSystemFontRequest? {
        val replacementOriginal = synchronized(appleSystemFontOriginalTypefacesByReplacement) {
            appleSystemFontOriginalTypefacesByReplacement[typeface]
        }
        if (replacementOriginal != null) {
            val signature = synchronized(appleSystemFontSignaturesByReplacement) {
                appleSystemFontSignaturesByReplacement[typeface]
            }
            return AppleSystemFontRequest(
                original = replacementOriginal,
                semanticWeight = signature?.semanticWeight
                    ?: AppleSystemFontWeightPolicy.semanticWeight(
                        reportedWeight = replacementOriginal.weight,
                        isBold = replacementOriginal.isBold,
                    ),
                italic = signature?.italic ?: replacementOriginal.isItalic,
            )
        }
        val managed = synchronized(appleSystemFontManagedTypefaces) {
            appleSystemFontManagedTypefaces.contains(typeface)
        }
        if (!managed) return null
        return AppleSystemFontRequest(
            original = typeface,
            semanticWeight = AppleSystemFontWeightPolicy.semanticWeight(
                reportedWeight = typeface.weight,
                isBold = typeface.isBold,
            ),
            italic = typeface.isItalic,
        )
    }

    private fun metricSpanTypeface(span: MetricAffectingSpan): Typeface? {
        if (span is TypefaceSpan) {
            span.typeface?.let { return it }
        }
        var owner: Class<*>? = span.javaClass
        while (owner != null && owner != MetricAffectingSpan::class.java) {
            owner.declaredFields.firstOrNull { field ->
                Typeface::class.java.isAssignableFrom(field.type)
            }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(span) as? Typeface
                }.getOrNull()
            }
            owner = owner.superclass
        }
        return null
    }

    private fun createTypefaceMetricSpan(
        original: MetricAffectingSpan,
        replacement: Typeface,
    ): MetricAffectingSpan? {
        if (original is TypefaceSpan) return TypefaceSpan(replacement)
        val constructor = original.javaClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.contentEquals(arrayOf(Typeface::class.java))
        } ?: return null
        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(replacement) as? MetricAffectingSpan
        }.getOrNull()
    }

    private fun replaceAppleFontResource(
        resources: Resources,
        resourceId: Int,
        original: Typeface,
    ): Typeface {
        val resourceIdentity = runCatching {
            Triple(
                resources.getResourcePackageName(resourceId),
                resources.getResourceTypeName(resourceId),
                resources.getResourceEntryName(resourceId),
            )
        }.getOrNull() ?: return original
        if (!AppleSystemFontWeightPolicy.shouldReplaceFontResource(
                packageName = resourceIdentity.first,
                resourceType = resourceIdentity.second,
                resourceName = resourceIdentity.third,
            )
        ) {
            return original
        }
        appleSystemFontManagedTypefaces.add(original)
        if (!isFollowSystemFontWeightEnabled()) return original

        val replacement = createAppleWeightAdjustedTypeface(original)
        logAppleSystemFontReplacement(
            stage = "font_resource",
            resourceName = resourceIdentity.third,
            original = original,
            replacement = replacement,
            requestedWeight = original.weight,
        )
        return replacement
    }

    private fun originalAppleTypeface(typeface: Typeface): Typeface? {
        synchronized(appleSystemFontOriginalTypefacesByReplacement) {
            appleSystemFontOriginalTypefacesByReplacement[typeface]?.let { return it }
        }
        return synchronized(appleSystemFontManagedTypefaces) {
            typeface.takeIf(appleSystemFontManagedTypefaces::contains)
        }
    }

    private fun createAppleWeightAdjustedTypeface(
        original: Typeface,
        requestedWeight: Int = original.weight,
        italic: Boolean = original.isItalic,
        textView: TextView? = null,
        text: CharSequence? = textView?.text,
        textSizePx: Float? = textView?.textSize,
    ): Typeface {
        val semanticWeight = AppleSystemFontWeightPolicy.semanticWeight(
            reportedWeight = requestedWeight,
            isBold = original.isBold,
        )
        val effectiveWeight = mappedAppleSystemFontWeight(semanticWeight)
        val usesCjkFallback = AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(
            text,
        )
        val cjkComposite = if (usesCjkFallback) {
            createAppleTypefaceWithSystemCjkFallback(
                original = original,
                semanticWeight = semanticWeight,
                effectiveSfProWeight = effectiveWeight,
                italic = italic,
                textSizePx = textSizePx,
            )
        } else {
            null
        }
        val result = cjkComposite ?: createAppleTypefaceWithVariation(
            original = original,
            effectiveWeight = effectiveWeight,
            italic = italic,
        ) ?: original
        rememberAppleSystemFontReplacement(
            replacement = result,
            original = original,
            effectiveWeight = effectiveWeight,
            semanticWeight = semanticWeight,
            usesCjkFallback = cjkComposite != null,
            italic = italic,
        )
        if (BuildConfig.DEBUG) {
            val path = if (result !== original) {
                "apple_typeface_variation_axis"
            } else {
                "apple_typeface_variation_unavailable"
            }
            val traceKey =
                "system_typeface:$path:$semanticWeight:$effectiveWeight:$italic:${text != null}"
            if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                ProviderLogger.debug(
                    "Apple 系统字体粗细生成：path=$path, semanticWeight=$semanticWeight, " +
                        "effectiveWeight=$effectiveWeight, resultWeight=${result.weight}, " +
                        "sameAsOriginal=${result === original}, italic=$italic, " +
                        "cjkFallback=${cjkComposite != null}, " +
                        "variationInstance=${isAppleTypefaceVariationInstance(result)}, " +
                        "textSizePx=$textSizePx, scale=${currentMiuiFontWeightScale()}"
                )
            }
        }
        return result
    }

    private fun createAppleTypefaceWithSystemCjkFallback(
        original: Typeface,
        semanticWeight: Int,
        effectiveSfProWeight: Int,
        italic: Boolean,
        textSizePx: Float?,
    ): Typeface? {
        val methods = hyperOsFontWeightMethods ?: return null
        @Suppress("DEPRECATION")
        val textSizeSp = (textSizePx ?: 16f).let { sizePx ->
            val scaledDensity = application.resources.displayMetrics.scaledDensity
            if (scaledDensity > 0f) sizePx / scaledDensity else sizePx
        }
        val cjkAxis = hyperOsCjkWeightAxis(
            methods = methods,
            semanticWeight = semanticWeight,
            textSizeSp = textSizeSp,
        ) ?: return null
        val cacheKey = listOf(
            System.identityHashCode(original),
            effectiveSfProWeight,
            semanticWeight,
            cjkAxis,
            italic,
            methods.miuiFontPath,
        ).joinToString(":")
        appleSystemFontCompositeCache[cacheKey]?.let { return it }

        return runCatching {
            val originalFamilies = methods.typefaceFontFamiliesField.get(original) as? List<*>
            val originalFamily = originalFamilies
                ?.filterIsInstance<FontFamily>()
                ?.firstOrNull()
                ?: return@runCatching null
            val originalFont = originalFamily.getFont(0)
            val primaryFont = buildFontWithWeight(
                source = originalFont,
                weight = effectiveSfProWeight,
                italic = italic,
                variation = "'wght' $effectiveSfProWeight",
            ) ?: return@runCatching null
            val cjkFont = Font.Builder(File(methods.miuiFontPath))
                .setWeight(semanticWeight.coerceIn(1, 1000))
                .setFontVariationSettings("'wght' $cjkAxis")
                .build()
            val builder = Typeface.CustomFallbackBuilder(
                FontFamily.Builder(primaryFont).build(),
            )
                .addCustomFallback(FontFamily.Builder(cjkFont).build())
                .setStyle(
                    FontStyle(
                        AppleSystemFontWeightPolicy.compositeStyleWeight(semanticWeight),
                        if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT,
                    )
                )
            val composite = builder.build()
            if (appleSystemFontCompositeCache.size >= 512) {
                appleSystemFontCompositeCache.clear()
            }
            appleSystemFontCompositeCache[cacheKey] = composite
            if (BuildConfig.DEBUG) {
                val traceKey = "cjk_fallback:$semanticWeight:$effectiveSfProWeight:$cjkAxis:$italic"
                if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                    ProviderLogger.debug(
                        "Apple 中文系统字体 fallback：font=${methods.miuiFontPath}, " +
                            "semanticWeight=$semanticWeight, sfProAxis=$effectiveSfProWeight, " +
                            "miuiAxis=$cjkAxis, textSizeSp=$textSizeSp, scale=" +
                            currentMiuiFontWeightScale()
                    )
                }
            }
            composite
        }.onFailure { throwable ->
            if (BuildConfig.DEBUG) {
                val traceKey = "cjk_fallback_create:${throwable.javaClass.name}"
                if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                    ProviderLogger.error("Apple 中文系统字体 fallback 创建失败", throwable)
                }
            }
        }.getOrNull()
    }

    private fun buildFontWithWeight(
        source: Font,
        weight: Int,
        italic: Boolean,
        variation: String,
    ): Font? = runCatching {
        val builder = source.file?.let { Font.Builder(it) }
            ?: source.buffer.duplicate().let { Font.Builder(it) }
        builder
            .setTtcIndex(source.ttcIndex)
            .setWeight(weight.coerceIn(1, 1000))
            .setSlant(if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
            .setFontVariationSettings(variation)
            .build()
    }.getOrNull()

    private fun hyperOsCjkWeightAxis(
        methods: HyperOsFontWeightMethods,
        semanticWeight: Int,
        textSizeSp: Float,
    ): Int? {
        val scale = currentMiuiFontWeightScale()
        if (hyperOsFontSettingsLastSyncedScale != scale) {
            synchronized(methods) {
                if (hyperOsFontSettingsLastSyncedScale != scale) {
                    runCatching { methods.loadFontSettingMethod.invoke(null) }
                    runCatching { methods.fontScaleField.setInt(null, scale) }
                    hyperOsFontSettingsLastSyncedScale = scale
                }
            }
        }
        return runCatching {
            val weightIndex = methods.getWeightIdxMethod.invoke(
                null,
                semanticWeight,
                false,
                methods.miuiFontType,
            ) as Int
            methods.getScaleWghtMethod.invoke(
                null,
                weightIndex,
                textSizeSp,
                methods.miuiFontType,
            ) as Int
        }.onFailure { throwable ->
            if (BuildConfig.DEBUG) {
                val traceKey = "cjk_fallback_axis:${throwable.javaClass.name}"
                if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                    ProviderLogger.error("HyperOS 中文字体字重轴读取失败", throwable)
                }
            }
        }.getOrNull()
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private fun resolveHyperOsFontWeightMethods(): HyperOsFontWeightMethods? = runCatching {
        val fontSettingsClass = Class.forName(
            "miui.util.font.FontSettings",
            false,
            classLoader,
        )
        val loadFontSetting = fontSettingsClass.getDeclaredMethod("loadFontSetting")
            .apply { isAccessible = true }
        val fontScaleField = fontSettingsClass.getDeclaredField("sFontScale")
            .apply { isAccessible = true }
        val fontTypeClass = Class.forName(
            "miui.util.font.FontType",
            false,
            classLoader,
        )
        val miuiFontType = requireNotNull(
            (fontTypeClass.enumConstants as Array<*>)
                .first { (it as Enum<*>).name == "MIUI" },
        )
        val fontWghtClass = Class.forName(
            "miui.util.font.FontWght",
            false,
            classLoader,
        )
        val getWeightIdx = fontWghtClass.getDeclaredMethod(
            "getWeightIdx",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            fontTypeClass,
        ).apply { isAccessible = true }
        val getScaleWght = fontWghtClass.getDeclaredMethod(
            "getScaleWght",
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            fontTypeClass,
        ).apply { isAccessible = true }
        val helperClass = Class.forName(
            "miui.util.TypefaceHelper",
            false,
            classLoader,
        )
        val getFontPath = helperClass.getDeclaredMethod("getFontPath", fontTypeClass)
            .apply { isAccessible = true }
        val fontPath = (getFontPath.invoke(null, miuiFontType) as? String)
            ?.takeIf { File(it).isFile }
            ?: error("HyperOS MiSans variable font path unavailable")
        val typefaceFontFamiliesField = Typeface::class.java
            .getDeclaredField("fontFamilies")
            .apply { isAccessible = true }
        runCatching { loadFontSetting.invoke(null) }
        ProviderLogger.info("HyperOS 中文 MiSans 可变字体接口已接入：path=$fontPath")
        HyperOsFontWeightMethods(
            loadFontSettingMethod = loadFontSetting,
            fontScaleField = fontScaleField,
            getWeightIdxMethod = getWeightIdx,
            getScaleWghtMethod = getScaleWght,
            miuiFontType = miuiFontType,
            miuiFontPath = fontPath,
            typefaceFontFamiliesField = typefaceFontFamiliesField,
        )
    }.onFailure { throwable ->
        ProviderLogger.error("HyperOS 中文 MiSans 可变字体接口不可用", throwable)
    }.getOrNull()

    private fun createAppleTypefaceWithVariation(
        original: Typeface,
        effectiveWeight: Int,
        italic: Boolean,
    ): Typeface? {
        val methods = appleSystemFontVariationMethods ?: return null
        return appleSystemFontVariationCache.getOrCreate(
            original = original,
            effectiveWeight = effectiveWeight,
            italic = italic,
        ) {
            runCatching {
                val styledBase = if (original.isItalic == italic) {
                    original
                } else {
                    appleSystemFontApplyGuard.run {
                        Typeface.create(
                            original,
                            original.weight.coerceIn(1, 1000),
                            italic,
                        )
                    }
                }
                val weightAxis = methods.axisConstructor.newInstance(
                    "wght",
                    effectiveWeight.toFloat(),
                )
                appleSystemFontApplyGuard.run {
                    methods.createFromTypefaceWithVariation.invoke(
                        null,
                        styledBase,
                        listOf(weightAxis),
                    ) as? Typeface
                }
            }.onFailure { throwable ->
                if (BuildConfig.DEBUG) {
                    val cause = throwable.cause ?: throwable
                    val traceKey = "apple_font_variation_create:${cause.javaClass.name}"
                    if (appleSystemFontDebugTraceKeys.add(traceKey)) {
                        ProviderLogger.error("Apple SF Pro 可变字体 wght 轴创建失败", cause)
                    }
                }
            }.getOrNull()
        }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private fun resolveAppleSystemFontVariationMethods(): AppleSystemFontVariationMethods? {
        return runCatching {
            val axisClass = Class.forName(
                "android.graphics.fonts.FontVariationAxis",
                false,
                classLoader,
            )
            val axisConstructor = axisClass.getDeclaredConstructor(
                String::class.java,
                Float::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val createFromTypefaceWithVariation = Typeface::class.java.getDeclaredMethod(
                "createFromTypefaceWithVariation",
                Typeface::class.java,
                List::class.java,
            ).apply { isAccessible = true }
            val isVariationInstance = Typeface::class.java.getDeclaredMethod(
                "isVariationInstance",
            ).apply { isAccessible = true }
            ProviderLogger.info("Apple SF Pro 可变字体 wght 轴接口已接入")
            AppleSystemFontVariationMethods(
                axisConstructor = axisConstructor,
                createFromTypefaceWithVariation = createFromTypefaceWithVariation,
                isVariationInstance = isVariationInstance,
            )
        }.getOrElse { throwable ->
            ProviderLogger.error("Apple SF Pro 可变字体 wght 轴接口不可用", throwable)
            null
        }
    }

    private fun isAppleTypefaceVariationInstance(typeface: Typeface?): Boolean? {
        typeface ?: return null
        val methods = appleSystemFontVariationMethods ?: return null
        return runCatching {
            methods.isVariationInstance.invoke(typeface) as? Boolean
        }.getOrNull()
    }

    private fun mappedAppleSystemFontWeight(semanticWeight: Int): Int =
        AppleSystemFontWeightPolicy.sfProWeightForSystemScale(
            semanticWeight = semanticWeight,
            systemScale = currentMiuiFontWeightScale(),
        )

    private fun logAppleSystemFontDrawState(view: TextView) {
        if (!BuildConfig.DEBUG || !isFollowSystemFontWeightEnabled()) return
        val viewTypeface = view.typeface
        val paint = view.paint
        val paintTypeface = paint.typeface
        // onDraw 诊断不能调用 CustomTextView.getText()，否则会重新进入 Future 解析 Hook。
        val text = view.layout?.text
            ?.toString()
            ?.replace('\n', ' ')
            ?.take(32)
            .orEmpty()
        val traceKey = listOf(
            "font_draw",
            System.identityHashCode(view),
            viewTypeface?.weight,
            paintTypeface?.weight,
            text,
        ).joinToString(":")
        if (!appleSystemFontDebugTraceKeys.add(traceKey)) return
        ProviderLogger.debug(
            "Apple 系统字体粗细最终绘制：view=${view.javaClass.name}@" +
                System.identityHashCode(view) +
                ", text=$text, viewWeight=${viewTypeface?.weight}, " +
                "paintWeight=${paintTypeface?.weight}, " +
                "sameTypeface=${viewTypeface === paintTypeface}, " +
                "viewVariationInstance=${isAppleTypefaceVariationInstance(viewTypeface)}, " +
                "paintVariationInstance=${isAppleTypefaceVariationInstance(paintTypeface)}, " +
                "variation=${runCatching { paint.fontVariationSettings }.getOrNull()}, " +
                "fakeBold=${paint.isFakeBoldText}, textSizePx=${paint.textSize}"
        )
    }

    private fun applyAppleSystemFontForTextView(
        view: TextView,
        textOverride: CharSequence? = null,
        stage: String,
        requestLayout: Boolean = true,
    ) {
        if (appleSystemFontApplyGuard.isActive) return

        appleSystemFontApplyGuard.run {
            val state = synchronized(appleSystemFontTrackedTextViews) {
                appleSystemFontTrackedTextViews[view]
            }
            val current = view.typeface ?: return@run
            val originalFromReplacement = synchronized(appleSystemFontOriginalTypefacesByReplacement) {
                appleSystemFontOriginalTypefacesByReplacement[current]
            }
            val content = textOverride ?: view.text

            fun restoreOriginalTypeface() {
                val original = state?.originalTypeface ?: originalFromReplacement ?: return
                view.setTypeface(original, state?.originalStyle ?: original.style)
                if (requestLayout) view.requestLayout()
            }

            if (!isFollowSystemFontWeightEnabled()) {
                if (originalFromReplacement != null) restoreOriginalTypeface()
                if (state != null) {
                    synchronized(appleSystemFontTrackedTextViews) {
                        appleSystemFontTrackedTextViews.remove(view)
                    }
                }
                return@run
            }

            if (!AppleSystemFontWeightPolicy.shouldReplaceTextContent(content)) {
                if (originalFromReplacement != null) restoreOriginalTypeface()
                synchronized(appleSystemFontTrackedTextViews) {
                    appleSystemFontTrackedTextViews.remove(view)
                }
                return@run
            }

            val appliedSignature = synchronized(appleSystemFontSignaturesByReplacement) {
                appleSystemFontSignaturesByReplacement[current]
            }
            val original = originalFromReplacement
                ?: if (state != null && current === state.originalTypeface) {
                    state.originalTypeface
                } else {
                    current
                }
            val requestedWeight = if (state != null && state.originalTypeface === original) {
                state.requestedWeight
            } else {
                appliedSignature?.semanticWeight ?: original.weight
            }
            val italic = if (state != null && state.originalTypeface === original) {
                state.italic
            } else {
                appliedSignature?.italic ?: original.isItalic
            }
            val originalStyle = state?.originalStyle ?: original.style
            val semanticWeight = AppleSystemFontWeightPolicy.semanticWeight(
                reportedWeight = requestedWeight,
                isBold = original.isBold,
            )
            val expectedWeight = mappedAppleSystemFontWeight(semanticWeight)
            val expectedSignature = AppleSystemFontReplacementSignature(
                effectiveSfProWeight = expectedWeight,
                semanticWeight = semanticWeight,
                usesCjkFallback = AppleSystemFontWeightPolicy.shouldUseSystemCjkFallback(content),
                italic = italic,
            )
            val alreadyApplied = originalFromReplacement != null &&
                appliedSignature == expectedSignature
            if (alreadyApplied) return@run

            val nextState = AppleSystemFontTextViewState(
                originalTypeface = original,
                requestedWeight = requestedWeight,
                italic = italic,
                originalStyle = originalStyle,
            )
            if (state != nextState) {
                synchronized(appleSystemFontTrackedTextViews) {
                    appleSystemFontTrackedTextViews[view] = nextState
                }
            }

            val replacement = createAppleWeightAdjustedTypeface(
                original = original,
                requestedWeight = requestedWeight,
                italic = italic,
                text = content,
                textSizePx = view.textSize,
            )
            view.setTypeface(replacement)
            if (requestLayout) view.requestLayout()
            logAppleSystemFontReplacement(
                stage = stage,
                resourceName = null,
                original = original,
                replacement = replacement,
                requestedWeight = requestedWeight,
            )
        }
    }

    private fun rememberAppleSystemFontReplacement(
        replacement: Typeface,
        original: Typeface,
        effectiveWeight: Int,
        semanticWeight: Int,
        usesCjkFallback: Boolean,
        italic: Boolean,
    ) {
        if (replacement === original) return
        synchronized(appleSystemFontOriginalTypefacesByReplacement) {
            appleSystemFontOriginalTypefacesByReplacement[replacement] = original
        }
        synchronized(appleSystemFontSignaturesByReplacement) {
            appleSystemFontSignaturesByReplacement[replacement] =
                AppleSystemFontReplacementSignature(
                    effectiveSfProWeight = effectiveWeight,
                    semanticWeight = semanticWeight,
                    usesCjkFallback = usesCjkFallback,
                    italic = italic,
                )
        }
    }

    private fun logAppleSystemFontReplacement(
        stage: String,
        resourceName: String?,
        original: Typeface,
        replacement: Typeface,
        requestedWeight: Int,
    ) {
        if (!BuildConfig.DEBUG) return
        val traceKey = listOf(
            stage,
            resourceName.orEmpty(),
            requestedWeight,
            original.weight,
            replacement.weight,
            original.isItalic,
        ).joinToString(":")
        if (!appleSystemFontDebugTraceKeys.add(traceKey)) return
        ProviderLogger.debug(
            "Apple 系统字体粗细替换：stage=$stage, resource=$resourceName, " +
                "requestedWeight=$requestedWeight, originalWeight=${original.weight}, " +
                "resultWeight=${replacement.weight}, italic=${original.isItalic}, " +
                "scale=${currentMiuiFontWeightScale()}"
        )
    }

    private fun currentMiuiFontWeightScale(forceRefresh: Boolean = false): Int {
        val now = SystemClock.uptimeMillis()
        val lastRead = appleSystemFontScaleLastReadUptimeMillis
        if (!forceRefresh && lastRead >= 0L && now - lastRead < 500L) {
            return appleSystemFontScaleCache
        }
        return synchronized(appleSystemFontScaleLock) {
            val synchronizedLastRead = appleSystemFontScaleLastReadUptimeMillis
            if (!forceRefresh && synchronizedLastRead >= 0L && now - synchronizedLastRead < 500L) {
                return@synchronized appleSystemFontScaleCache
            }
            val resolver = application.contentResolver
            val scale = runCatching {
                Settings.System.getInt(resolver, "key_miui_font_weight_scale")
            }.getOrNull() ?: runCatching {
                Settings.Global.getInt(resolver, "key_miui_font_weight_scale")
            }.getOrNull() ?: 50
            appleSystemFontScaleCache = scale.coerceIn(0, 100)
            appleSystemFontScaleLastReadUptimeMillis = now
            appleSystemFontScaleCache
        }
    }

    private fun hookAppleLyricsBlurEffect() {
        runCatching {
            val recyclerClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val passiveLifecycleMethods = listOf(
                "setAdapter" to 1,
                "onAttachedToWindow" to 0,
                "onLayout" to 5,
                "onChildAttachedToWindow" to 1,
            )
            val installedHooks = mutableListOf<String>()
            val failedHooks = mutableListOf<String>()
            passiveLifecycleMethods.forEach { (name, parameterCount) ->
                runCatching {
                    val method = AppleReflection.findMethod(
                        recyclerClass,
                        name,
                        parameterCount = parameterCount,
                    )
                    hookRegistrar.install(method, after = { chain, _ ->
                        if (name == "setAdapter") {
                            (chain.thisObject as? View)?.let(
                                appleLyricsRecyclerViewClassifications::remove
                            )
                        }
                        chain.thisObject
                            ?.takeIf(::isAppleLyricsRecyclerView)
                            ?.let { recyclerView ->
                                if (name == "setAdapter") {
                                    resetAppleLyricsBlurRuntimeState(recyclerView)
                                } else {
                                    if (name == "onLayout") {
                                        completeAppleLyricsProgrammaticRecenter(recyclerView)
                                    }
                                    scheduleAppleLyricsBlur(recyclerView)
                                }
                            }
                    })
                    installedHooks += "RecyclerView.$name"
                }.onFailure { throwable ->
                    failedHooks += "RecyclerView.$name:${throwable.javaClass.simpleName}"
                }
            }

            runCatching {
                val method = AppleReflection.findMethod(
                    recyclerClass,
                    "onScrolled",
                    parameterCount = 2,
                )
                hookRegistrar.install(method, after = { chain, _ ->
                    chain.thisObject
                        ?.takeIf(::isAppleLyricsRecyclerView)
                        ?.let { recyclerView ->
                            suspendAppleLyricsBlurForScroll(recyclerView)
                            scheduleAppleLyricsBlur(
                                recyclerView = recyclerView,
                                delayMs = APPLE_LYRICS_IDLE_RECHECK_DELAY_MS,
                            )
                        }
                })
                installedHooks += "RecyclerView.onScrolled"
            }.onFailure { throwable ->
                failedHooks += "RecyclerView.onScrolled:${throwable.javaClass.simpleName}"
            }

            runCatching {
                val linearLayoutManagerClass = classLoader.loadClass(
                    "androidx.recyclerview.widget.LinearLayoutManager"
                )
                val method = findAppleLyricsScrollToPositionWithOffsetMethod(
                    linearLayoutManagerClass
                ) ?: error("scrollToPositionWithOffset method not found")
                hookRegistrar.install(method, after = { chain, _ ->
                    val targetPosition = (chain.args.firstOrNull() as? Number)?.toInt()
                        ?: return@installHook
                    onAppleLyricsProgrammaticRecenterRequested(
                        layoutManager = chain.thisObject,
                        targetPosition = targetPosition,
                    )
                })
                installedHooks += "LinearLayoutManager.${method.name}"
            }.onFailure { throwable ->
                failedHooks +=
                    "LinearLayoutManager.scrollToPositionWithOffset:" +
                        throwable.javaClass.simpleName
            }

            listOf("onScrollStateChanged", "setScrollState").forEach { name ->
                runCatching {
                    val method = AppleReflection.findMethod(
                        recyclerClass,
                        name,
                        parameterCount = 1,
                    )
                    hookRegistrar.install(method, after = { chain, _ ->
                        val scrollState = (chain.args.firstOrNull() as? Number)?.toInt()
                            ?: return@installHook
                        chain.thisObject
                            ?.takeIf(::isAppleLyricsRecyclerView)
                            ?.let { recyclerView ->
                                onAppleLyricsScrollStateChanged(recyclerView, scrollState)
                            }
                    })
                    installedHooks += "RecyclerView.$name"
                }.onFailure { throwable ->
                    failedHooks += "RecyclerView.$name:${throwable.javaClass.simpleName}"
                }
            }

            hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
                .forEach { resolvedClass ->
                val adapterClassName = resolvedClass.target.className
                runCatching {
                    val adapterClass = resolvedClass.clazz
                    val method = AppleReflection.findMethod(
                        adapterClass,
                        "T",
                        parameterCount = 3,
                    )
                    hookRegistrar.install(method, after = { chain, _ ->
                        onAppleLyricsActiveLinesUpdated(chain.thisObject)
                    })
                    installedHooks += "$adapterClassName.T"
                }.onFailure { throwable ->
                    failedHooks += "$adapterClassName.T:${throwable.javaClass.simpleName}"
                }
            }
            check(installedHooks.isNotEmpty()) { "No RecyclerView lifecycle method was hookable" }
            if (BuildConfig.DEBUG) {
                ProviderLogger.diagnostic(
                    "Apple Music 歌词模糊 Hook 明细: " +
                        "installed=$installedHooks, failed=$failedHooks"
                )
            }
            ProviderLogger.debug("Apple Music 歌词模糊 Hook 已安装: methods=${installedHooks.size}")
        }.onFailure {
            ProviderLogger.error("Apple Music 歌词模糊 Hook 安装失败", it)
        }
    }

    private fun hookAppleLyricsBindingDiagnostics() {
        val holderClassName = "androidx.recyclerview.widget.RecyclerView\$D"
        hookResolver.resolveClasses(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
            .forEach { resolvedClass ->
            val adapterClass = resolvedClass.clazz
            val bindMethods = adapterClass.declaredMethods.filter { method ->
                val parameterTypes = method.parameterTypes
                !method.isBridge &&
                    !method.isSynthetic &&
                    method.returnType == Void.TYPE &&
                    parameterTypes.size in 2..3 &&
                    parameterTypes[0].name == holderClassName &&
                    parameterTypes[1] == Int::class.javaPrimitiveType &&
                    (
                        parameterTypes.size == 2 ||
                            List::class.java.isAssignableFrom(parameterTypes[2])
                    )
            }
            bindMethods.forEach { method ->
                if (!appleLyricsAdapterBindingDiagnosticMethods.add(method)) return@forEach
                method.isAccessible = true
                hookRegistrar.installScoped(
                    executable = method,
                    enter = { chain ->
                        val adapter = chain.thisObject ?: return@installScopedHook false
                        val context = AppleLyricsBindingDiagnosticContext(
                            songId = currentAppleLyricsSongId,
                            adapterClass = adapter.javaClass.name,
                            adapterIdentity = System.identityHashCode(adapter),
                            methodName = "${method.name}/${method.parameterCount}",
                            holder = chain.args.firstOrNull(),
                            position = (chain.args.getOrNull(1) as? Number)?.toInt(),
                            translationEnabled = debugAppleBooleanField(adapter, "d"),
                            pronunciationEnabled = debugAppleBooleanField(adapter, "e"),
                        )
                        appleLyricsBindingDiagnosticContexts.push(context)
                        true
                    },
                    after = { _, _ ->
                        val context = appleLyricsBindingDiagnosticContexts.current
                            ?: return@installScopedHook
                        val root = appleLyricsHolderRoot(context.holder)
                        val texts = root?.let(::debugTextSnapshot) ?: "none"
                        val layers = debugAppleLyricsHolderLayers(context.holder)
                        logApplePronunciationBindingDiagnostic(
                            stage = "holder_bound",
                            context = context,
                            details = "root=${root?.let(::debugViewDescription)}, " +
                                "texts=$texts, layers=$layers",
                            dedupeKey = "holder:${context.methodName}:${context.position}:" +
                                "${context.pronunciationEnabled}:$texts",
                        )
                        root?.let { capturedRoot ->
                            val capturedContext = context
                            capturedRoot.postOnAnimation {
                                logApplePronunciationBindingDiagnostic(
                                    stage = "holder_next_frame",
                                    context = capturedContext,
                                    details = "root=${debugViewDescription(capturedRoot)}, " +
                                        "texts=${debugTextSnapshot(capturedRoot)}, " +
                                        "layers=${debugAppleLyricsHolderLayers(capturedContext.holder)}",
                                    dedupeKey = "holder_next_frame:" +
                                        "${capturedContext.methodName}:" +
                                        "${capturedContext.position}",
                                )
                            }
                            capturedRoot.postDelayed(
                                {
                                    logApplePronunciationBindingDiagnostic(
                                        stage = "holder_120ms",
                                        context = capturedContext,
                                        details = "root=${debugViewDescription(capturedRoot)}, " +
                                            "texts=${debugTextSnapshot(capturedRoot)}, " +
                                            "layers=${debugAppleLyricsHolderLayers(capturedContext.holder)}",
                                        dedupeKey = "holder_120ms:" +
                                            "${capturedContext.methodName}:" +
                                            "${capturedContext.position}",
                                    )
                                },
                                120L,
                            )
                        }
                    },
                    exit = { appleLyricsBindingDiagnosticContexts.pop() },
                )
            }
        }
        ProviderLogger.diagnostic(
            "Apple pronunciation binding diagnostics installed: " +
                "methods=${appleLyricsAdapterBindingDiagnosticMethods.size}"
        )
    }

    private fun appleLyricsHolderRoot(holder: Any?): View? {
        holder ?: return null
        val binding = epoxyDataBindingFromHolder(holder)
        runCatching {
            binding?.let { AppleReflection.call(it, "getRoot") as? View }
        }.getOrNull()?.let { return it }
        return generateSequence(holder.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { View::class.java.isAssignableFrom(it.type) }
            .firstNotNullOfOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(holder) as? View
                }.getOrNull()
            }
    }

    private fun debugAppleLyricsHolderLayers(holder: Any?): String {
        if (!BuildConfig.DEBUG) return "disabled"
        val binding = epoxyDataBindingFromHolder(holder) ?: return "binding=none"
        return listOf("U", "V").joinToString(prefix = "[", postfix = "]") { fieldName ->
            val view = runCatching {
                AppleReflection.field(binding, fieldName) as? View
            }.getOrNull()
            if (view == null) {
                "$fieldName=none"
            } else {
                val childCount = (view as? ViewGroup)?.childCount ?: -1
                "$fieldName={${debugViewDescription(view)},childCount=$childCount," +
                    "texts=${debugTextSnapshot(view)},children=${debugAppleDirectChildren(view)}}"
            }
        }
    }

    private fun debugAppleDirectChildren(view: View): String {
        val group = view as? ViewGroup ?: return "not_group"
        return (0 until minOf(group.childCount, 24)).joinToString(
            prefix = "[",
            postfix = "]",
        ) { index ->
            val child = group.getChildAt(index)
            val text = (child as? TextView)?.text?.toString()?.trim().orEmpty().take(80)
            "${child.javaClass.simpleName}@${System.identityHashCode(child)}" +
                "(visibility=${child.visibility},alpha=${child.alpha},text=$text," +
                "nested=${(child as? ViewGroup)?.childCount ?: -1})"
        }
    }

    private fun hookAppleLyricsUiDiagnostics() {
        val fragmentClass =
            classLoader.loadClass("com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
        runCatching {
            val onCreateView = AppleReflection.findMethod(
                fragmentClass,
                "onCreateView",
                parameterCount = 3,
            )
            hookRegistrar.install(
                onCreateView,
                before = { chain ->
                    chain.thisObject?.let { fragment ->
                        logAppleLyricsUiState(fragment, "onCreateView_before")
                    }
                },
                after = { chain, result ->
                    val fragment = chain.thisObject ?: return@installHook
                    logAppleLyricsUiState(fragment, "onCreateView_after")
                    (result as? View)?.let { root ->
                        root.postOnAnimation {
                            logAppleLyricsUiState(fragment, "onCreateView_next_frame")
                        }
                        root.postDelayed(
                            {
                                logAppleLyricsUiState(fragment, "onCreateView_250ms")
                            },
                            250L,
                        )
                    }
                },
            )

            val onResume = AppleReflection.findMethod(
                fragmentClass,
                "onResume",
                parameterCount = 0,
            )
            hookRegistrar.install(onResume, after = { chain, _ ->
                chain.thisObject?.let { fragment ->
                    logAppleLyricsUiState(fragment, "onResume_after")
                }
            })

            val onDestroyView = AppleReflection.findMethod(
                fragmentClass,
                "onDestroyView",
                parameterCount = 0,
            )
            hookRegistrar.install(onDestroyView, before = { chain ->
                chain.thisObject?.let { fragment ->
                    logAppleLyricsUiState(fragment, "onDestroyView_before")
                }
            })

            val recyclerClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val setAdapter = AppleReflection.findMethod(
                recyclerClass,
                "setAdapter",
                parameterCount = 1,
            )
            hookRegistrar.install(setAdapter, after = { chain, _ ->
                chain.thisObject
                    ?.takeIf(::isAppleRecyclerViewInstance)
                    ?.let { recyclerView ->
                    logAppleLyricsRecyclerLifecycle(recyclerView, "setAdapter_after")
                }
            })
            val onRecyclerAttached = AppleReflection.findMethod(
                recyclerClass,
                "onAttachedToWindow",
                parameterCount = 0,
            )
            hookRegistrar.install(onRecyclerAttached, after = { chain, _ ->
                chain.thisObject
                    ?.takeIf(::isAppleRecyclerViewInstance)
                    ?.let { recyclerView ->
                    logAppleLyricsRecyclerLifecycle(recyclerView, "onAttachedToWindow_after")
                }
            })
            ProviderLogger.diagnostic("Apple lyrics UI lifecycle diagnostics installed")
        }.onFailure {
            ProviderLogger.error("Apple lyrics UI lifecycle diagnostics install failed", it)
        }
    }

    private fun hookExoMediaPlayer() {
        val exoPlayerClass =
            classLoader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")
        exoPlayerClass.declaredConstructors.forEach { constructor ->
            hookRegistrar.install(constructor, after = { chain, _ ->
                capturePlaybackPositionSource(
                    mediaPlayer = chain.thisObject,
                    source = "ExoMediaPlayer.<init>",
                    replace = false
                )
            })
        }
        hookExoPlaybackLifecycle(exoPlayerClass)

        val seekMethod = AppleReflection.findMethod(
            exoPlayerClass,
            "seekToPosition",
            parameterCount = 1
        )
        hookRegistrar.install(seekMethod, after = { chain, _ ->
            val position = chain.args.firstOrNull() as? Long ?: 0L
            if (BuildConfig.DEBUG) {
                lastExplicitSeekAtMs = SystemClock.elapsedRealtime()
                lastExplicitSeekPosition = position
                ProviderLogger.diagnostic(
                    "Timing seek: requested=$position, callbackPlayer=" +
                        "${chain.thisObject?.let(System::identityHashCode)}, " +
                        "activePlayer=${activePlaybackPlayer?.let(System::identityHashCode)}, " +
                        "sourcePlayer=${playbackPositionSource?.player?.let(System::identityHashCode)}"
                )
            }
            if (isPlaying) player?.seekTo(position)
        })

        val controller =
            classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
        val stateMethod = AppleReflection.findMethod(
            controller,
            "onPlaybackStateChanged",
            parameterCount = 3
        )
        hookRegistrar.install(stateMethod, after = { chain, _ ->
            val activeMediaPlayer = chain.args.firstOrNull()
            val playbackState = PlaybackState.of(chain.args.getOrNull(2) as? Int ?: -1)
            ProviderLogger.diagnostic(
                "Timing lifecycle: source=onPlaybackStateChanged, state=$playbackState, " +
                    "callbackPlayer=${activeMediaPlayer?.let(System::identityHashCode)}, " +
                    "activePlayer=${activePlaybackPlayer?.let(System::identityHashCode)}, " +
                    "sourcePlayer=${playbackPositionSource?.player?.let(System::identityHashCode)}"
            )
            when (playbackState) {
                PlaybackState.PLAYING -> {
                    activatePlaybackPlayer(
                        mediaPlayer = activeMediaPlayer,
                        source = "LocalMediaPlayerController.onPlaybackStateChanged"
                    )
                    refreshCurrentQueueItem(activeMediaPlayer, "onPlaybackStateChanged")
                    startSyncAction()
                }
                else -> {
                    if (activePlaybackPlayer === activeMediaPlayer) stopSyncAction()
                }
            }
        })
    }

    private fun startSyncAction() {
        if (isPlaying) return
        isPlaying = true
        player?.setPlaybackState(true)
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        isPlaying = false
        player?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive && isPlaying) {
                runCatching {
                    playbackPositionSource?.readPosition()?.let { position ->
                        logPositionSyncState(position)
                        player?.setPosition(position)
                    }
                }.onFailure {
                    ProviderLogger.error("读取 Apple Music 当前播放进度失败", it)
                }
                delay(positionUpdateInterval())
            }
        }
    }

    private fun hookExoPlaybackLifecycle(exoPlayerClass: Class<*>) {
        val playMethod = AppleReflection.findMethod(exoPlayerClass, "play", parameterCount = 0)
        hookRegistrar.install(playMethod, after = { chain, _ ->
            activatePlaybackPlayer(
                mediaPlayer = chain.thisObject,
                source = "ExoMediaPlayer.play"
            )
            refreshCurrentQueueItem(chain.thisObject, "ExoMediaPlayer.play")
            startSyncAction()
        })

        listOf("pause", "stop", "release").forEach { methodName ->
            val method = AppleReflection.findMethod(exoPlayerClass, methodName, parameterCount = 0)
            hookRegistrar.install(method, after = { chain, _ ->
                if (playbackPositionSource?.player === chain.thisObject) {
                    stopSyncAction()
                    if (methodName == "release") {
                        playbackPositionSource = null
                        if (activePlaybackPlayer === chain.thisObject) {
                            activePlaybackPlayer = null
                        }
                    }
                }
            })
        }
        ProviderLogger.info("Apple Music 播放生命周期 Hook 已安装")
    }

    private fun activatePlaybackPlayer(mediaPlayer: Any?, source: String) {
        if (mediaPlayer == null) return
        ProviderLogger.diagnostic(
            "Timing activate: source=$source, requested=${System.identityHashCode(mediaPlayer)}, " +
                "previousActive=${activePlaybackPlayer?.let(System::identityHashCode)}, " +
                "previousSource=${playbackPositionSource?.player?.let(System::identityHashCode)}, " +
                "metadataId=$currentPlaybackMetadataId, lyricsSongId=$currentAppleLyricsSongId"
        )
        activePlaybackPlayer = mediaPlayer
        capturePlaybackPositionSource(
            mediaPlayer = mediaPlayer,
            source = source,
            replace = true
        )
    }

    private fun capturePlaybackPositionSource(
        mediaPlayer: Any?,
        source: String,
        replace: Boolean
    ) {
        if (mediaPlayer == null || (!replace && playbackPositionSource != null)) return
        val resolved = resolvePlaybackPositionSource(mediaPlayer)
        if (resolved == null) {
            ProviderLogger.error(
                "Apple Music 播放器缺少 getCurrentPosition：class=${mediaPlayer.javaClass.name}"
            )
            return
        }
        val previous = playbackPositionSource
        playbackPositionSource = resolved
        if (previous?.player !== mediaPlayer) {
            zeroPositionReadCount = 0
            hasLoggedNonZeroPosition = false
            lastTimingSamplePosition = -1L
            lastTimingSampleAtMs = 0L
            lastTimingStateSignature = null
            ProviderLogger.info(
                "播放进度源已绑定：source=$source, class=${mediaPlayer.javaClass.name}, " +
                    "instance=${System.identityHashCode(mediaPlayer)}"
            )
        }
    }

    private fun logPositionSyncState(position: Long) {
        logPlaybackTimingDiagnostic(position)
        if (position > 0L) {
            if (!hasLoggedNonZeroPosition) {
                hasLoggedNonZeroPosition = true
                ProviderLogger.info("播放进度同步已启动：position=$position")
            }
            zeroPositionReadCount = 0
            return
        }
        zeroPositionReadCount += 1
        if (zeroPositionReadCount == 10) {
            val source = playbackPositionSource
            ProviderLogger.info(
                "播放进度连续为 0：class=${source?.player?.javaClass?.name}, " +
                    "instance=${source?.player?.let(System::identityHashCode)}"
            )
        }
    }

    private fun logPlaybackTimingDiagnostic(position: Long) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        val source = playbackPositionSource
        val activeIdentity = activePlaybackPlayer?.let(System::identityHashCode)
        val sourceIdentity = source?.player?.let(System::identityHashCode)
        val stateSignature = listOf(
            activeIdentity,
            sourceIdentity,
            currentPlaybackMetadataId,
            currentAppleLyricsSongId,
            isPlaying,
        ).joinToString("|")
        val sampleElapsed = (now - lastTimingSampleAtMs).takeIf { lastTimingSampleAtMs > 0L }
        val positionDelta = (position - lastTimingSamplePosition)
            .takeIf { lastTimingSamplePosition >= 0L }
        val recentExplicitSeek = now - lastExplicitSeekAtMs <= 2_000L
        val unexpectedJump = sampleElapsed != null && positionDelta != null &&
            sampleElapsed in 1L..2_000L &&
            kotlin.math.abs(positionDelta - sampleElapsed) > 1_500L &&
            !recentExplicitSeek
        val shouldTrace = unexpectedJump || stateSignature != lastTimingStateSignature ||
            now - lastTimingTraceAtMs >= 5_000L

        if (shouldTrace) {
            val queueItem = runCatching {
                source?.player?.let { AppleReflection.call(it, "getCurrentItem") }
            }.getOrNull()
            val queueMediaId = queueItem?.let(::queueItemMediaId)
            val queueId = queueItem?.let {
                runCatching { AppleReflection.call(it, "getPlaybackQueueId") as? Long }
                    .getOrNull()
            }
            ProviderLogger.diagnostic(
                "Timing sample: reason=${if (unexpectedJump) "unexpected_jump" else "periodic"}, " +
                    "rawPosition=$position, positionDelta=$positionDelta, elapsedDelta=$sampleElapsed, " +
                    "activePlayer=$activeIdentity, sourcePlayer=$sourceIdentity, " +
                    "playerMismatch=${activePlaybackPlayer !== source?.player}, " +
                    "queueMediaId=$queueMediaId, queueId=$queueId, " +
                    "metadataId=$currentPlaybackMetadataId, lyricsSongId=$currentAppleLyricsSongId, " +
                    "isPlaying=$isPlaying, recentSeek=$recentExplicitSeek, " +
                    "seekPosition=$lastExplicitSeekPosition"
            )
            lastTimingTraceAtMs = now
            lastTimingStateSignature = stateSignature
        }
        lastTimingSampleAtMs = now
        lastTimingSamplePosition = position
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun initScreenStateMonitor() {
        ScreenStateMonitor.initialize(application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (isPlaying) resumeCoroutineTask()
            }

            override fun onScreenOff() {
                if (isPlaying && isAodLyricsEnabled()) resumeCoroutineTask()
                else pauseCoroutineTask()
            }

            override fun onScreenUnlocked() {
                if (isPlaying && progressJob == null) resumeCoroutineTask()
            }
        })
    }

    private fun isAodLyricsEnabled(): Boolean = contentUiLanguagePrefs?.getBoolean(
        RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
        RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS
    ) ?: RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS

    private fun positionUpdateInterval(): Long {
        return if (
            ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.OFF &&
            isAodLyricsEnabled()
        ) {
            250L
        } else {
            ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL
        }
    }

    /** Debug-only request diagnostics; sensitive values are represented by length and hash. */
    private fun hookLyricsNetworkRequest() {
        runCatching {
            val method = AppleReflection.findMethod(
                classLoader.loadClass("t8.N0"),
                "z"
            )
            hookRegistrar.install(method, before = { chain ->
                @Suppress("UNCHECKED_CAST")
                val query = chain.args.getOrNull(5) as? MutableMap<String, String>
                    ?: return@installHook
                val id = chain.args.getOrNull(4)?.toString().orEmpty()
                val sourceQueue = pendingLyricsRequestSources[id]
                val source = sourceQueue?.poll() ?: "unknown"
                if (sourceQueue?.isEmpty() == true) {
                    pendingLyricsRequestSources.remove(id, sourceQueue)
                }
                ProviderLogger.debug(
                    "Lyrics network request: source=$source, id=$id, " +
                        "dsid=${sensitiveSummary(chain.args.getOrNull(0))}, " +
                        "userAgent=${sensitiveSummary(chain.args.getOrNull(1))}, " +
                        "authorization=${sensitiveSummary(chain.args.getOrNull(2))}, " +
                        "storefront=${chain.args.getOrNull(3)}, localizationQuery=$query"
                )
            })
        }.onFailure { ProviderLogger.error("Lyrics network request Hook 安装失败", it) }
    }

    private fun hookLyricsCookies() {
        runCatching {
            val method = AppleReflection.findMethod(
                classLoader.loadClass("s8.b"),
                "d",
                parameterCount = 1
            )
            hookRegistrar.install(method, after = { chain, result ->
                val url = chain.args.firstOrNull()?.toString().orEmpty()
                if (!url.contains("/syllable-lyrics")) return@installHook
                val cookies = (result as? Iterable<*>)?.mapNotNull { cookie ->
                    cookie ?: return@mapNotNull null
                    val name = AppleReflection.field(cookie, "a") as? String
                        ?: return@mapNotNull null
                    "$name(${sensitiveSummary(AppleReflection.field(cookie, "b"))})"
                }.orEmpty()
                ProviderLogger.debug("Lyrics CookieJar: url=$url, cookies=$cookies")
            })
        }.onFailure { ProviderLogger.error("Lyrics CookieJar Hook 安装失败", it) }
    }

    private fun hookFinalLyricsHttp() {
        runCatching {
            val method = AppleReflection.findMethod(
                classLoader.loadClass("u8.a"),
                "a",
                parameterCount = 1
            )
            hookRegistrar.install(
                method,
                before = { chain ->
                    val interceptor = chain.args.firstOrNull() ?: return@installHook
                    val request = AppleReflection.field(interceptor, "e") ?: return@installHook
                    val url = AppleReflection.field(request, "a")?.toString().orEmpty()
                    if (!url.contains("/syllable-lyrics")) return@installHook
                    val headers = AppleReflection.field(request, "c")
                    ProviderLogger.debug(
                        "Lyrics HTTP network request: url=$url, " +
                            "headers=${summarizeHeaders(headers, response = false)}"
                    )
                },
                after = { _, result ->
                    val response = result ?: return@installHook
                    val request = AppleReflection.field(response, "a") ?: return@installHook
                    val url = AppleReflection.field(request, "a")?.toString().orEmpty()
                    if (!url.contains("/syllable-lyrics")) return@installHook
                    val code = AppleReflection.intField(response, "d")
                    val headers = AppleReflection.field(response, "f")
                    ProviderLogger.debug(
                        "Lyrics HTTP network response: url=$url, code=$code, " +
                            "headers=${summarizeHeaders(headers, response = true)}"
                    )
                }
            )
        }.onFailure { ProviderLogger.error("Lyrics HTTP network Hook 安装失败", it) }
    }

    private fun sensitiveSummary(value: Any?): String {
        if (value == null) return "null"
        val text = value.toString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return "len=${text.length},sha256=$digest"
    }

    private fun summarizeHeaders(headers: Any?, response: Boolean): List<String> {
        if (headers == null) return emptyList()
        val values = AppleReflection.field(headers, "a") as? Array<*> ?: return emptyList()
        val safeNames = if (response) {
            setOf(
                "age", "cache-control", "content-length", "date", "etag", "expires",
                "last-modified", "via", "x-cache", "x-cache-hits"
            )
        } else {
            setOf(
                "accept", "accept-language", "cache-control", "content-type",
                "if-modified-since", "if-none-match", "pragma"
            )
        }
        return values.toList().chunked(2).mapNotNull { pair ->
            val name = pair.getOrNull(0)?.toString() ?: return@mapNotNull null
            val value = pair.getOrNull(1)?.toString().orEmpty()
            val rendered = if (name.lowercase() in safeNames) value else sensitiveSummary(value)
            "$name=$rendered"
        }
    }

}

internal fun inAppPlaybackItemAccess(
    contract: InAppPlaybackItemContract,
    field: InAppPlaybackItemField,
): InAppPlaybackItemAccess? = when (contract) {
    InAppPlaybackItemContract.STANDARD -> when (field) {
        InAppPlaybackItemField.TITLE ->
            InAppPlaybackItemAccess("name", readViaMethod = false, setter = "setTitle")
        InAppPlaybackItemField.ARTIST ->
            InAppPlaybackItemAccess(
                "artistName",
                readViaMethod = false,
                setter = "setArtistName",
            )
        InAppPlaybackItemField.ALBUM ->
            InAppPlaybackItemAccess(
                "collectionName",
                readViaMethod = false,
                setter = "setCollectionName",
            )
    }
    InAppPlaybackItemContract.HISTORY -> when (field) {
        InAppPlaybackItemField.TITLE ->
            InAppPlaybackItemAccess("getTitle", readViaMethod = true, setter = "setTitle")
        InAppPlaybackItemField.ARTIST ->
            InAppPlaybackItemAccess(
                "getSubTitle",
                readViaMethod = true,
                setter = "setSubTitle",
            )
        InAppPlaybackItemField.ALBUM -> null
    }
}

internal fun isInAppHistoryQueueEntryClassName(className: String): Boolean =
    className == "Z8.d"

internal fun shouldApplyInAppPlaybackItemAlias(
    expectedMediaId: String,
    currentMediaId: String?,
): Boolean = expectedMediaId == currentMediaId

internal fun selectTrustworthyMediaId(
    explicitMediaId: String?,
    inferredMediaIds: Collection<String>,
): String? {
    explicitMediaId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.let { return it }
    return inferredMediaIds.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .distinct()
        .singleOrNull()
}

internal fun normalizedRecyclerBindingMediaIds(mediaIds: Collection<String>): Set<String> =
    mediaIds.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .toCollection(linkedSetOf())

internal fun shouldScheduleVisibleRecyclerMetadata(
    previousMediaIds: Set<String>?,
    currentMediaIds: Set<String>,
    visible: Boolean,
): Boolean = visible && currentMediaIds.isNotEmpty() && previousMediaIds != currentMediaIds

internal fun composeVisibleMetadataResolutionIds(
    capturedMediaIds: Collection<String>,
    fallbackMediaIds: Collection<String>,
    limit: Int,
): List<String> {
    if (limit <= 0) return emptyList()
    val captured = normalizedRecyclerBindingMediaIds(capturedMediaIds)
    val candidates = if (captured.isNotEmpty()) {
        captured
    } else {
        normalizedRecyclerBindingMediaIds(fallbackMediaIds)
    }
    return candidates.take(limit)
}

internal fun shouldRegisterGenericRecyclerRefresh(
    mediaIds: Set<String>,
    dataBindingMediaId: String?,
    blockMultiItemStructuralRefresh: Boolean,
): Boolean {
    if (mediaIds.isEmpty()) return false
    if (mediaIds.size == 1 && dataBindingMediaId in mediaIds) return false
    return !blockMultiItemStructuralRefresh
}

internal fun shouldScheduleDataBindingAliasRefresh(
    appliedAlias: AppliedMetadataAlias?,
    pendingAlias: AppliedMetadataAlias?,
    requestedAlias: AppliedMetadataAlias?,
): Boolean = requestedAlias == null ||
    (appliedAlias != requestedAlias && pendingAlias != requestedAlias)

internal fun dataBindingRefreshStrategy(
    expectedTitle: String?,
    expectedSubtitle: String?,
    titleApplied: Boolean,
    subtitleApplied: Boolean,
): DataBindingRefreshStrategy {
    val titleRequired = !expectedTitle.isNullOrBlank()
    val subtitleRequired = !expectedSubtitle.isNullOrBlank()
    val allRequiredVariablesApplied =
        (titleRequired || subtitleRequired) &&
            (!titleRequired || titleApplied) &&
            (!subtitleRequired || subtitleApplied)
    return if (allRequiredVariablesApplied) {
        DataBindingRefreshStrategy.VARIABLES_ONLY
    } else {
        DataBindingRefreshStrategy.FULL_INVALIDATE
    }
}

internal fun dataBindingAliasAlreadyRendered(
    expectedTitle: String?,
    expectedSubtitle: String?,
    renderedTexts: Collection<String>,
): Boolean {
    val rendered = renderedTexts
        .map(String::trim)
        .filter(String::isNotEmpty)
    fun containsExpected(value: String?): Boolean {
        val expected = value?.trim()?.takeIf(String::isNotEmpty) ?: return true
        return rendered.any { text -> text == expected || expected in text }
    }
    return rendered.isNotEmpty() &&
        containsExpected(expectedTitle) &&
        containsExpected(expectedSubtitle)
}

internal fun shouldRefreshListenNowDataBindingAlias(
    appliedAlias: AppliedMetadataAlias?,
    requestedAlias: AppliedMetadataAlias,
    expectedTitle: String?,
    expectedSubtitle: String?,
    renderedTexts: Collection<String>,
): Boolean {
    if (appliedAlias != requestedAlias) return true
    if (renderedTexts.isEmpty()) return false
    return !dataBindingAliasAlreadyRendered(
        expectedTitle = expectedTitle,
        expectedSubtitle = expectedSubtitle,
        renderedTexts = renderedTexts,
    )
}

internal fun isDataBindingRefreshCurrent(
    currentMediaId: String?,
    requestedMediaId: String,
    currentBindGeneration: Long,
    scheduledBindGeneration: Long,
): Boolean = currentMediaId == requestedMediaId &&
    currentBindGeneration == scheduledBindGeneration

internal fun shouldRefreshInAppLibraryComposeAlias(
    appliedAliases: Map<String, AppliedMetadataAlias>?,
    mediaId: String,
    requestedAlias: AppliedMetadataAlias?,
): Boolean = requestedAlias == null || appliedAliases?.get(mediaId) != requestedAlias

internal fun shouldInvalidateArtistHeaderAppliedAlias(
    appliedAlias: AppliedMetadataAlias?,
    effectiveAlias: AppliedMetadataAlias?,
    pendingAlias: AppliedMetadataAlias?,
    expectedTitle: String?,
    renderedTexts: Collection<String>,
): Boolean {
    if (appliedAlias == null || appliedAlias != effectiveAlias) return false
    if (pendingAlias == effectiveAlias) return false
    val expected = expectedTitle?.trim()?.takeIf(String::isNotEmpty) ?: return false
    val rendered = renderedTexts.map(String::trim).filter(String::isNotEmpty)
    return rendered.isNotEmpty() && expected !in rendered
}

internal fun shouldRefreshInAppSurface(
    surfaceRelevant: Boolean,
    hasVisibleExactConsumer: Boolean,
    hasActiveVisibleLease: Boolean = false,
): Boolean = surfaceRelevant || hasVisibleExactConsumer || hasActiveVisibleLease

internal fun shouldRefreshExactBoundTarget(
    surfaceRelevant: Boolean,
    mediaIdMatches: Boolean,
    rootVisible: Boolean,
): Boolean = mediaIdMatches && (surfaceRelevant || rootVisible)

internal fun isArtistProfileControllerClassNames(classNames: Iterable<String>): Boolean =
    classNames.any { className ->
        className == "com.apple.android.music.profiles.ArtistEpoxyController"
    }

internal fun artistProfileTopSongMediaId(
    relationshipKey: Any?,
    mediaId: String?,
): String? {
    if (relationshipKey != "top-songs") return null
    return mediaId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

internal fun artistProfileFallbackArtistId(
    profileArtistId: String?,
    existingArtistIds: Collection<String>,
    songArtistCredit: String?,
    profileArtistCredits: Collection<String>,
): String? {
    if (existingArtistIds.isNotEmpty()) return null
    val artistId = profileArtistId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?: return null
    val credit = songArtistCredit?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (AppleInternalCatalogResolver.isCollaborationArtistName(credit)) return null
    val creditKey = AppleInternalCatalogResolver.normalizedArtistNameKey(credit)
        .takeIf(String::isNotEmpty)
        ?: return null
    val knownCreditKeys = profileArtistCredits.asSequence()
        .map(AppleInternalCatalogResolver::normalizedArtistNameKey)
        .filter(String::isNotEmpty)
        .toSet()
    return artistId.takeIf { creditKey in knownCreditKeys }
}

internal fun artistProfileSubtitleWithArtist(
    originalSubtitle: String?,
    originalArtist: String?,
    replacementArtist: String?,
): String? {
    val subtitle = originalSubtitle?.takeIf(String::isNotBlank) ?: return null
    val original = originalArtist?.trim()?.takeIf(String::isNotEmpty) ?: return subtitle
    val replacement = replacementArtist?.trim()?.takeIf(String::isNotEmpty) ?: return subtitle
    if (original == replacement) return subtitle
    if (subtitle == original) return replacement

    if (subtitle.startsWith(original)) {
        val suffix = subtitle.substring(original.length)
        val boundary = suffix.firstOrNull()
        if (
            boundary == null ||
            boundary.isWhitespace() ||
            boundary in setOf('·', '•', '—', '–', '-', '|', '/', '（', '(')
        ) {
            return replacement + suffix
        }
    }

    val separators = listOf(" · ", " • ", " — ", " – ")
    separators.forEach { separator ->
        val separatorIndex = subtitle.indexOf(separator)
        if (separatorIndex <= 0) return@forEach
        val credit = subtitle.substring(0, separatorIndex)
        if (
            AppleInternalCatalogResolver.normalizedArtistNameKey(credit) ==
            AppleInternalCatalogResolver.normalizedArtistNameKey(original)
        ) {
            return replacement + subtitle.substring(separatorIndex)
        }
    }
    return subtitle
}

internal fun inAppLibraryControllerBuildStrategy(
    hasAlbumBuildData: Boolean,
    hasArtistBuildData: Boolean,
    isPlaylistPageController: Boolean,
): InAppLibraryControllerBuildStrategy = when {
    hasAlbumBuildData -> InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA
    hasArtistBuildData -> InAppLibraryControllerBuildStrategy.ARTIST_SET_DATA
    isPlaylistPageController ->
        InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD
    else -> InAppLibraryControllerBuildStrategy.GENERIC_REQUEST_MODEL_BUILD
}

internal fun shouldUsePlaylistDirectRowRefresh(
    strategy: InAppLibraryControllerBuildStrategy,
    hasDirectPlaylistRow: Boolean,
): Boolean =
    strategy == InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD &&
        hasDirectPlaylistRow

internal fun selectInAppArtworkContinuityUrls(
    currentUrls: Collection<String>,
    cachedUrls: Collection<String>?,
    cachedAtUptimeMillis: Long?,
    nowUptimeMillis: Long,
    ttlMillis: Long,
): List<String>? {
    if (currentUrls.any(String::isNotBlank)) return null
    val normalizedCachedUrls = cachedUrls
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
        .orEmpty()
    if (normalizedCachedUrls.isEmpty()) return null
    val capturedAt = cachedAtUptimeMillis ?: return null
    val ageMillis = nowUptimeMillis - capturedAt
    if (ageMillis < 0L || ageMillis > ttlMillis.coerceAtLeast(0L)) return null
    return normalizedCachedUrls
}

internal fun normalizedInAppArtworkValueUrls(value: Any?): List<String> {
    val values: Sequence<Any?> = when (value) {
        null -> emptySequence()
        is CharSequence -> sequenceOf(value)
        is Array<*> -> value.asSequence()
        is Iterable<*> -> value.asSequence()
        else -> emptySequence()
    }
    return values.mapNotNull { item ->
        item?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }.distinct().toList()
}

internal fun preferredInAppListenNowArtworkKey(
    builderKey: InAppListenNowArtworkContinuityKey?,
    delegateKey: InAppListenNowArtworkContinuityKey?,
): InAppListenNowArtworkContinuityKey? = builderKey ?: delegateKey

internal fun listenNowCatalogIdForExactCard(
    builderLiveData: Any?,
    delegateLiveData: Any?,
    builderKey: InAppListenNowArtworkContinuityKey?,
    delegateKey: InAppListenNowArtworkContinuityKey?,
): String? {
    if (builderLiveData == null || builderLiveData !== delegateLiveData) return null
    val builder = builderKey ?: return null
    val delegate = delegateKey ?: return null
    if (builder.persistentId != delegate.persistentId ||
        builder.contentType != delegate.contentType ||
        builder.artworkIdentity != delegate.artworkIdentity
    ) return null
    val delegateCatalogId = delegate.id.trim()
        .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?: return null
    val builderId = builder.id.trim()
    val builderCatalogId = builderId.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    if (builderCatalogId != null) {
        return delegateCatalogId.takeIf { it == builderCatalogId }
    }
    return delegateCatalogId.takeIf { builderId.startsWith("l.") }
}

internal fun shouldSkipInAppListenNowArtworkLookup(
    keyMatches: Boolean,
    currentUrls: Collection<String>,
    seededUrls: Collection<String>,
): Boolean {
    if (!keyMatches) return false
    val normalizedCurrent = currentUrls.map(String::trim).filter(String::isNotEmpty).distinct()
    val normalizedSeeded = seededUrls.map(String::trim).filter(String::isNotEmpty).distinct()
    return normalizedCurrent.isNotEmpty() && normalizedCurrent == normalizedSeeded
}

internal fun albumPageControllerAppliedAlias(
    appliedAlias: AppliedMetadataAlias,
    songArtistId: String?,
    albumArtistId: String?,
    albumArtist: String?,
): AppliedMetadataAlias {
    if (songArtistId == null || songArtistId != albumArtistId) return appliedAlias
    val targetArtist = albumArtist?.trim().orEmpty()
    if (targetArtist.isEmpty()) return appliedAlias
    val songArtistKey = AppleInternalCatalogResolver.normalizedArtistNameKey(
        appliedAlias.artist
    )
    val albumArtistKey = AppleInternalCatalogResolver.normalizedArtistNameKey(targetArtist)
    if (songArtistKey == albumArtistKey) return appliedAlias
    return appliedAlias.copy(artist = targetArtist)
}

internal fun changedAssociatedArtistAlias(
    previousAlias: AppleInternalCatalogResolver.Alias?,
    updatedAlias: AppleInternalCatalogResolver.Alias?,
): AppleInternalCatalogResolver.Alias? = updatedAlias?.takeIf { it != previousAlias }

internal fun inAppLibraryControllerRefreshDelayMillis(
    strategy: InAppLibraryControllerBuildStrategy,
    lastBuildUptimeMillis: Long?,
    nowUptimeMillis: Long,
    albumDebounceMillis: Long,
    playlistIntervalMillis: Long,
): Long {
    if (strategy == InAppLibraryControllerBuildStrategy.ALBUM_SET_DATA) {
        return albumDebounceMillis.coerceAtLeast(0L)
    }
    if (
        strategy != InAppLibraryControllerBuildStrategy.PLAYLIST_FORCE_MODEL_BUILD ||
        lastBuildUptimeMillis == null
    ) {
        return 0L
    }
    val elapsed = (nowUptimeMillis - lastBuildUptimeMillis).coerceAtLeast(0L)
    return (playlistIntervalMillis - elapsed).coerceAtLeast(0L)
}

internal fun metadataPageFinalBindingKind(
    albumHeader: Boolean,
    albumRow: Boolean,
    playlistRow: Boolean,
    artistTopSong: Boolean,
    artistHeader: Boolean,
): MetadataPageFinalBindingKind? = when {
    albumHeader -> MetadataPageFinalBindingKind.ALBUM_HEADER
    albumRow -> MetadataPageFinalBindingKind.ALBUM_ROW
    playlistRow -> MetadataPageFinalBindingKind.PLAYLIST_ROW
    artistTopSong -> MetadataPageFinalBindingKind.ARTIST_TOP_SONG
    artistHeader -> MetadataPageFinalBindingKind.ARTIST_HEADER
    else -> null
}

internal fun localizedEntityTypeForInAppLibraryKind(
    kind: InAppLibraryEntityKind,
): AppleInternalCatalogResolver.LocalizedEntityType = when (kind) {
    InAppLibraryEntityKind.ALBUM -> AppleInternalCatalogResolver.LocalizedEntityType.ALBUM
    InAppLibraryEntityKind.SONG -> AppleInternalCatalogResolver.LocalizedEntityType.SONG
    InAppLibraryEntityKind.ARTIST -> AppleInternalCatalogResolver.LocalizedEntityType.ARTIST
}

/**
 * 只按 Apple Media API 的明确实体类型分类最近搜索项。
 *
 * 不根据标题、歌手字段或当前播放内容猜测类型，避免把歌曲（尤其是合唱、
 * 多 artistID、feat. 或多人署名）误当成单一歌手实体。
 */
internal fun inAppLibraryEntityKindForClassNames(
    classNames: Iterable<String>,
): InAppLibraryEntityKind? {
    val names = classNames.toSet()
    return when {
        names.any {
            it == "com.apple.android.music.mediaapi.models.Artist" ||
                it == "com.apple.android.music.mediaapi.models.LibraryArtist"
        } -> InAppLibraryEntityKind.ARTIST

        names.any {
            it == "com.apple.android.music.mediaapi.models.Album" ||
                it == "com.apple.android.music.mediaapi.models.LibraryAlbum"
        } -> InAppLibraryEntityKind.ALBUM

        names.any {
            it == "com.apple.android.music.mediaapi.models.Song" ||
                it == "com.apple.android.music.mediaapi.models.LibrarySong"
        } -> InAppLibraryEntityKind.SONG

        else -> null
    }
}

internal fun appleNativeSupplementTracks(
    pronunciationSelected: Boolean,
    translationSelected: Boolean,
): List<AppleNativeSupplementTrack> = buildList {
    if (translationSelected) add(AppleNativeSupplementTrack.TRANSLATION)
    if (pronunciationSelected) add(AppleNativeSupplementTrack.PRONUNCIATION)
}

internal fun shouldCompleteAppleLyricsProgrammaticRecenter(
    suspendedForScroll: Boolean,
    scrollState: Int,
    pendingTargetPosition: Int?,
    focusPositions: Set<Int>,
): Boolean =
    suspendedForScroll &&
        scrollState == 0 &&
        pendingTargetPosition != null &&
        pendingTargetPosition in focusPositions

internal fun appleLyricsBlurFocusPositions(
    activePositions: Set<Int>,
    instrumentalPositions: Set<Int>,
    writersCreditsPositions: Set<Int> = emptySet(),
): Set<Int> {
    if (instrumentalPositions.isNotEmpty()) return instrumentalPositions
    if (activePositions.isEmpty()) return emptySet()
    val trailingWritersCredits = writersCreditsPositions.filterTo(linkedSetOf()) { position ->
        position - 1 in activePositions
    }
    return activePositions + trailingWritersCredits
}

internal fun shouldDeferAppleLyricsOutgoingBlur(
    isPendingOutgoing: Boolean,
    rowBottomY: Float?,
    currentZoneTopY: Float?,
): Boolean =
    isPendingOutgoing &&
        (rowBottomY == null || currentZoneTopY == null || rowBottomY > currentZoneTopY)

internal fun resolvePlaybackPositionSource(mediaPlayer: Any?): PlaybackPositionSource? {
    mediaPlayer ?: return null
    val method = runCatching {
        AppleReflection.findMethod(
            mediaPlayer.javaClass,
            "getCurrentPosition",
            parameterCount = 0
        )
    }.getOrNull() ?: return null
    return PlaybackPositionSource(mediaPlayer, method)
}


internal fun isActivePlaybackCallback(callbackPlayer: Any?, activePlayer: Any?): Boolean =
    callbackPlayer != null && callbackPlayer === activePlayer

internal fun shouldNotifyInAppModelChange(
    mediaId: String,
    activeMediaId: String?,
    hasBoundConsumer: Boolean = false,
): Boolean = mediaId == activeMediaId || hasBoundConsumer

internal fun mergeDeferredMetadataResolution(
    previous: DeferredMetadataResolution?,
    incoming: DeferredMetadataResolution,
): DeferredMetadataResolution {
    if (previous == null) return incoming
    return DeferredMetadataResolution(
        priority = if (incoming.priority.ordinal > previous.priority.ordinal) {
            incoming.priority
        } else {
            previous.priority
        },
        originalResolutionMode = if (
            previous.originalResolutionMode == InAppOriginalResolutionMode.ORIGINAL_FIRST ||
            incoming.originalResolutionMode == InAppOriginalResolutionMode.ORIGINAL_FIRST
        ) {
            InAppOriginalResolutionMode.ORIGINAL_FIRST
        } else {
            InAppOriginalResolutionMode.AFTER_LOCALIZED
        },
    )
}

internal fun shouldExposeOriginalMetadataOverride(
    mediaId: String,
    currentPlaybackMediaId: String?,
    confirmed: Boolean,
): Boolean = mediaId != currentPlaybackMediaId || confirmed

internal fun shouldOpenFullPlayerFromNotification(
    category: String?,
    hasMediaSession: Boolean,
): Boolean = category == Notification.CATEGORY_TRANSPORT || hasMediaSession

internal fun appleLyricsStringArrayParameterIndexes(
    parameterTypes: Array<Class<*>>,
): List<Int> = parameterTypes.indices.filter { parameterTypes[it] == Array<String>::class.java }

internal fun isAppleLyricsRequestPath(pathSegments: List<String>): Boolean =
    pathSegments.getOrNull(3) == "songs" &&
        pathSegments.lastOrNull()?.contains("lyrics") == true

internal fun expandAppleLyricsPronunciationLanguages(original: List<String>): List<String> =
    (
        original + listOf(
            "ja-Latn",
            "ko-Latn",
            "zh-Latn",
        )
    )
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

internal fun expandAppleLyricsTranslationLanguages(original: List<String>): List<String> =
    original
        .flatMap { language ->
            when (normalizedAppleLyricsLanguageTag(language)) {
                "zh-hans", "zh-hans-cn", "zh-cn" ->
                    listOf(language, "zh-Hans", "zh-Hans-CN", "zh-CN")
                "zh-hans-sg", "zh-sg" ->
                    listOf(language, "zh-Hans", "zh-Hans-SG", "zh-SG", "zh-Hans-CN")
                "zh-hant", "zh-hant-tw", "zh-tw" ->
                    listOf(language, "zh-Hant", "zh-Hant-TW", "zh-TW", "zh-Hant-HK")
                "zh-hant-hk", "zh-hk", "zh-hant-mo", "zh-mo" ->
                    listOf(language, "zh-Hant", "zh-Hant-HK", "zh-HK", "zh-Hant-TW")
                else -> listOf(language)
            }
        }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(::normalizedAppleLyricsLanguageTag)

internal fun selectAppleLyricsTranslationLanguage(
    systemLanguage: String,
    availableLanguages: List<String>,
): String? {
    val system = appleLyricsLanguageParts(systemLanguage) ?: return null
    val available = availableLanguages.mapNotNull { language ->
        appleLyricsLanguageParts(language)?.let { parts -> language to parts }
    }
    return available.firstOrNull { (_, parts) -> parts.normalized == system.normalized }?.first
        ?: available.firstOrNull { (_, parts) ->
            parts.language == system.language &&
                parts.script != null &&
                parts.script == system.script
        }?.first
        ?: available.firstOrNull { (_, parts) ->
            parts.language == system.language &&
                parts.region != null &&
                parts.region == system.region
        }?.first
        ?: available.firstOrNull { (_, parts) -> parts.language == system.language }?.first
}

private fun normalizedAppleLyricsLanguageTag(language: String): String =
    language.trim().replace('_', '-').lowercase()

private fun appleLyricsLanguageParts(language: String): AppleLyricsLanguageParts? {
    val normalized = normalizedAppleLyricsLanguageTag(language)
    val segments = normalized.split('-').filter(String::isNotEmpty)
    val primary = segments.firstOrNull() ?: return null
    val explicitScript = segments.drop(1).firstOrNull { it.length == 4 }
    val region = segments.drop(1).firstOrNull { segment ->
        segment.length == 2 || segment.length == 3 && segment.all(Char::isDigit)
    }
    val inferredScript = explicitScript ?: when {
        primary != "zh" -> null
        region in setOf("tw", "hk", "mo") -> "hant"
        region in setOf("cn", "sg") -> "hans"
        else -> null
    }
    return AppleLyricsLanguageParts(
        normalized = normalized,
        language = primary,
        script = inferredScript,
        region = region,
    )
}
