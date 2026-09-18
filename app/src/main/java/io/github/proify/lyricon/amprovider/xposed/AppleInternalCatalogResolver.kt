package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import android.os.Handler
import com.juren233.hyperlyricsenhanced.common.RootConstants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class AppleInternalCatalogResolver(
    context: Context,
    internal val classLoader: ClassLoader,
    internal val hookResolver: AppleMusicHookResolver,
    internal val mainHandler: Handler
) {
    internal val dispatch = AppleInternalCatalogDispatch()
    internal val caches = AppleInternalCatalogCaches()
    internal val persistentLocalizedCache = AppleLocalizedMetadataCache(context, mainHandler)
    internal val persistentOriginalCache = AppleOriginalMetadataCache(context, mainHandler)
    internal val resolvedCatalogHolder by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS)
    }
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
    // 6.5.3 起 Application.onCreate 阶段 MediaApi 尚未就绪（applicationConnector 未初始化），
    // storefront 应用需要延迟重试；门控与尝试计数供重试调度跨线程防重入。
    internal val storefrontApplyRetryGate = AtomicBoolean()
    internal val storefrontApplyRetryAttempts = AtomicInteger()
}
