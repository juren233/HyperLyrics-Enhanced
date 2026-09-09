package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class AppleInternalCatalogResolver(
    context: Context,
    internal val classLoader: ClassLoader,
    internal val hookResolver: AppleMusicHookResolver,
    internal val mainHandler: Handler
) {
    internal val persistentLocalizedCache = AppleLocalizedMetadataCache(context, mainHandler)
    internal val persistentOriginalCache = AppleOriginalMetadataCache(context, mainHandler)
    internal val resolvedCatalogHolder by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS)
    }
    internal val cache = object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias>?): Boolean =
            size > CACHE_SIZE
    }
    internal val inFlight = mutableMapOf<String, MutableList<(OriginalResolution) -> Unit>>()
    internal val originalCandidateCallbacks =
        mutableMapOf<String, MutableList<(Alias) -> Unit>>()
    internal val catalogIdentityCache = ConcurrentHashMap<String, CatalogIdentity>()
    internal val catalogIdentityInFlight =
        mutableMapOf<String, MutableList<(CatalogIdentity) -> Unit>>()
    internal val originalEntityPending = LinkedHashMap<String, OriginalEntityRequest>()
    internal var originalEntityBatchScheduled = false
    internal var originalEntityBatchesRunning = 0
    internal var originalEntityBackgroundBatchesRunning = 0
    internal val localizedCache = object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias>?): Boolean =
            size > LOCALIZED_CACHE_SIZE
    }
    internal val localizedArtistAliasCache =
        object : LinkedHashMap<String, Alias>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Alias>?,
            ): Boolean = size > LOCALIZED_ARTIST_ALIAS_CACHE_SIZE
        }
    internal val localizedInFlight =
        mutableMapOf<String, MutableList<(Alias?) -> Unit>>()
    internal val localizedPending = LinkedHashMap<String, LocalizedRequest>()
    internal var localizedBatchScheduled = false
    internal var localizedBatchesRunning = 0
    internal var localizedBackgroundBatchesRunning = 0
    internal val requestPriorityByMediaId =
        object : LinkedHashMap<String, RequestPriority>(256, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, RequestPriority>?,
            ): Boolean = size > REQUEST_PRIORITY_CACHE_SIZE
        }
    @Volatile
    internal var requestScopeActive = false
    internal var requestScopeRevision = -1L
    internal val warmedSelections = mutableSetOf<Int>()
    internal val warmingSelections = mutableSetOf<Int>()
    @Volatile
    internal var persistentLocalizedCacheEnabled = true
    internal var catalogAccess: CatalogAccess? = null
    @Volatile
    internal var accountStorefront: String? = null
    @Volatile
    internal var accountStorefrontCaptured = false
    @Volatile
    internal var lastAppliedConfiguredStorefront: String? = null
    internal val activeCatalogRequest = ThreadLocal<CatalogRequestLocalization?>()
    internal val pendingCatalogRequests = ConcurrentHashMap<String, CatalogRequestLocalization>()
    internal val catalogRequestSequence = AtomicLong()
    internal val catalogDiagnosticSequence = AtomicLong()
    @Volatile
    internal var contentUiLanguageSelection =
        RootConstants.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE

}
