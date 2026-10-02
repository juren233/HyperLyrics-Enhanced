package com.juren233.hyperlyricsenhanced.root.island

import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.HookEntry
import com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport.TAG
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

internal object RealIslandHooker {

    class UpdateBigIslandViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            var mediaInfo: IslandProbeUtils.MediaIslandInfo? = null
            runCatching {
                val contentView = chain.thisObject as? ViewGroup ?: return@runCatching
                // 无论歌词状态如何，先记为岛根候选：媒体切换窗口内本视图可能被硬清
                // 注销，若之后原生不再重发更新，重挂扫描只能靠这里的候选找回。
                IslandViewRegistry.rememberCandidate(contentView)
                val data = chain.args.getOrNull(0)
                if (IslandProbeUtils.isSuperIslandEnabled()) {
                    mediaInfo = IslandProbeUtils.extractMediaIslandInfo(data)
                    if (mediaInfo != null && data != null) {
                        // 双播冲突断供窗口的重放来源：缓存最后一次带媒体信息的数据。
                        IslandMediaReinstater.rememberMediaData(data, mediaInfo.packageName)
                    }
                    if (mediaInfo?.let(IslandTextHookerSupport::isCurrentLyricIsland) == true) {
                        if (!IslandTextHookerSupport.shouldRenderInjectedIsland()) {
                            IslandTextHookerSupport.clearInjectedIsland(contentView)
                            return@runCatching
                        }
                        if (IslandLyricTextInjector.restoreExistingSlotsLightweight(contentView)) {
                            IslandLyricTextInjector.refreshCurrentContent(contentView)
                            IslandHostFacade.triggerSystemRelayout(contentView)
                HookLogger.d(TAG, "updateBigIslandView 前已轻量恢复歌词视图并重新布局")
                        }
                    }
                }
            }.onFailure { e ->
            HookLogger.e(TAG, "预恢复歌词视图失败", e)
            }

            val result = chain.proceed()

            // updateBigIslandView is a suspend function. During the first cold template build,
            // proceed() returns the target process' COROUTINE_SUSPENDED marker before the
            // template has been initialized. The continuation later re-enters this same method
            // with data=null, so treating either invocation as a completed non-media update
            // unregisters the first music island. Only mutate the completed view below.
            if (SuspendingHookResultPolicy.isCoroutineSuspended(result)) {
                return result
            }

            runCatching {
                val contentView = chain.thisObject as? ViewGroup ?: return@runCatching
                IslandViewRegistry.rememberCandidate(contentView)
                val prefs = HookEntry.instance?.prefs ?: return@runCatching
                if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_HYPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_HYPER_ISLAND)) {
                    return@runCatching
                }

                // Kotlin's generated continuation resumes updateBigIslandView(null, false, this).
                // Recover the original data that the target method restored from its continuation.
                val data = chain.args.getOrNull(0)
                    ?: IslandProbeUtils.getCurrentIslandData(contentView)
                val info = mediaInfo ?: IslandProbeUtils.extractMediaIslandInfo(data)

                if (info == null) {
                    if (BuildConfig.DEBUG) {
                        HookLogger.d(
                            TAG,
                            "岛数据无媒体信息，执行清理: 数据=${data?.javaClass?.simpleName}, " +
                                "是媒体岛=${IslandProbeUtils.isMediaIsland(data)}",
                        )
                    }
                    IslandTextHookerSupport.hardClearInjectedIsland(contentView)
                    return@runCatching
                }

                if (!IslandTextHookerSupport.isCurrentLyricIsland(info)) {
                    IslandTextHookerSupport.clearOnlyWhenPackageIsDefinitelyDifferent(contentView, info)
                    return@runCatching
                }

                IslandViewRegistry.register(contentView, info.packageName)
                if (!IslandTextHookerSupport.shouldRenderInjectedIsland()) {
                    IslandTextHookerSupport.clearInjectedIsland(contentView)
                    return@runCatching
                }

                if (IslandLyricTextInjector.injectSlots(contentView, reconfigureExisting = false)) {
                    IslandViewHelper.triggerSystemRelayout(contentView)
                }
                IslandLyricTextInjector.refreshCurrentContent(contentView)

                IslandHostFacade.injectHostGlow(contentView, data, prefs)
            }.onFailure { e ->
            HookLogger.e(TAG, "注入歌词视图失败", e)
            }

            return result
        }
    }

    class LayoutVisibilityHook(
        private val eventName: String
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                val contentView = chain.thisObject as? ViewGroup ?: return@runCatching
                IslandViewRegistry.rememberCandidate(contentView)
                if (!IslandProbeUtils.isSuperIslandEnabled()) return@runCatching
                val currentData = IslandProbeUtils.getCurrentIslandData(contentView)
                val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(currentData) ?: return@runCatching

                if (!IslandTextHookerSupport.isCurrentLyricIsland(mediaInfo)) {
                    IslandTextHookerSupport.clearOnlyWhenPackageIsDefinitelyDifferent(contentView, mediaInfo)
                    return@runCatching
                }
                if (!IslandTextHookerSupport.shouldRenderInjectedIsland()) {
                    IslandTextHookerSupport.clearInjectedIsland(contentView)
                    return@runCatching
                }

                if (IslandLyricTextInjector.restoreExistingSlotsLightweight(contentView)) {
                    IslandLyricTextInjector.refreshCurrentContent(contentView)
                    IslandHostFacade.triggerSystemRelayout(contentView)
                HookLogger.d(TAG, "$eventName 后已轻量恢复歌词视图并重新布局")
                }
            }.onFailure { e ->
            HookLogger.e(TAG, "$eventName 后恢复歌词视图失败", e)
            }

            return result
        }
    }
}
