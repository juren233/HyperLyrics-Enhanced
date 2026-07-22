package com.juren233.hyperlyricsenhanced.root.island

import android.view.View
import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.root.island.IslandTextHookerSupport.TAG
import com.juren233.hyperlyricsenhanced.root.mediacard.island.IslandExpandedMediaAmbientFlowHooker
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

internal object FakeIslandTransitionHooker {

    class TrackingStartHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                val fakeView = chain.thisObject as? ViewGroup ?: return@runCatching
                IslandExpandedMediaAmbientFlowHooker.applyFakeTransitionTheme(fakeView)
                val generation = FakeIslandTransitionState.ensureActive(fakeView)
                IslandTextHookerSupport.prepareFrozenFakeIslandForTransition(fakeView, "after fake.onTrackingFakeViewStart")
                fakeView.post {
                    if (FakeIslandTransitionState.isActive(fakeView, generation)) {
                        IslandTextHookerSupport.prepareFrozenFakeIslandForTransition(fakeView, "post fake.onTrackingFakeViewStart")
                    }
                }
                fakeView.postDelayed({
                    if (FakeIslandTransitionState.isActive(fakeView, generation)) {
                        IslandTextHookerSupport.prepareFrozenFakeIslandForTransition(fakeView, "retry fake.onTrackingFakeViewStart")
                    }
                }, 48L)
            }.onFailure { e ->
                HookLogger.e(TAG, "跟踪开始后冻结过渡视图失败", e)
            }

            return result
        }
    }

    class PrepareVisibleHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                val fakeView = chain.thisObject as? ViewGroup ?: return@runCatching
                IslandExpandedMediaAmbientFlowHooker.restoreFakeTransitionTheme(fakeView)
                val generation = FakeIslandTransitionState.ensureActive(fakeView)
                IslandTextHookerSupport.prepareFrozenFakeIslandForTransition(fakeView, "after fake.updateViewStateWhenOpenAnimStart")
                fakeView.post {
                    if (FakeIslandTransitionState.isActive(fakeView, generation)) {
                        IslandTextHookerSupport.prepareFrozenFakeIslandForTransition(fakeView, "post fake.updateViewStateWhenOpenAnimStart")
                    }
                }
            }.onFailure { e ->
                HookLogger.e(TAG, "打开动画开始后冻结过渡视图失败", e)
            }

            return result
        }
    }

    class VisibilityHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val visibility = (chain.args.getOrNull(0) as? Number)?.toInt()
            if (visibility == View.VISIBLE) {
                runCatching {
                    val fakeView = chain.thisObject as? ViewGroup ?: return@runCatching
                    IslandExpandedMediaAmbientFlowHooker.restoreFakeTransitionTheme(fakeView)
                    FakeIslandTransitionState.ensureActive(fakeView)
                    IslandTextHookerSupport.prepareFrozenFakeIslandForTransition(fakeView, "before fake.setVisibility(VISIBLE)")
                }.onFailure { e ->
                HookLogger.e(TAG, "过渡视图显示前冻结视图失败", e)
                }
            }

            val result = chain.proceed()

            if (visibility == View.VISIBLE) {
                runCatching {
                    val fakeView = chain.thisObject as? ViewGroup ?: return@runCatching
                    val generation = FakeIslandTransitionState.ensureActive(fakeView)
                    fakeView.post {
                        if (FakeIslandTransitionState.isActive(fakeView, generation)) {
                            IslandTextHookerSupport.prepareFrozenFakeIslandForTransition(fakeView, "post fake.setVisibility(VISIBLE)")
                        }
                    }
                }.onFailure { e ->
                HookLogger.e(TAG, "过渡视图显示后冻结视图失败", e)
                }
            } else if (visibility == View.INVISIBLE) {
                runCatching {
                    val fakeView = chain.thisObject as? ViewGroup ?: return@runCatching
                    IslandExpandedMediaAmbientFlowHooker.restoreFakeTransitionTheme(fakeView)
                    FakeIslandTransitionState.finish(fakeView)
                    IslandTextHookerSupport.restoreRealIslandAfterFakeTransition(fakeView, "after fake.setVisibility(INVISIBLE)")
                }.onFailure { e ->
                HookLogger.e(TAG, "过渡视图隐藏后恢复真实岛失败", e)
                }
            }

            return result
        }
    }
}
