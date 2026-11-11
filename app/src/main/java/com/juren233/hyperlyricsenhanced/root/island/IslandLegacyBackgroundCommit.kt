/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import android.view.ViewGroup
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 内容视图当前记录的大岛宽度与左边界（`bigIslandViewWidth` / `bigIslandX`）。 */
internal data class IslandLegacyBigIslandBounds(
    val width: Int,
    val x: Int,
)

/** 「是否补做原生大岛变化提交」的纯判断，不依赖 Android 与反射。 */
internal object IslandLegacyBackgroundCommitPolicy {
    /**
     * 宽度或 x 任一变化，且内容视图处于大岛状态、且未临时隐藏，才应提交。
     * 状态与临时隐藏用惰性参数传入，前一个条件不成立时不会触发后一个查询。
     */
    fun shouldCommit(
        before: IslandLegacyBigIslandBounds,
        after: IslandLegacyBigIslandBounds,
        isBigIslandState: () -> Boolean,
        isTempHidden: () -> Boolean,
    ): Boolean = before != after && isBigIslandState() && !isTempHidden()
}

/**
 * 旧版超级岛（没有 `updateBigIslandViewWidth`）的背景提交。
 *
 * 原生 `calculateBigIslandWidth` 只更新内容区，背景几何只在 `bigIslandChangedAnimation`
 * 里随 `BigIsland → BigIsland` 状态提交（Issue #65）。模块主动刷新使宽度或 x 变化后，
 * 在这里补上同一个原生提交，不手工改背景。
 */
internal object IslandLegacyBackgroundCommit {
    private const val TAG = "IslandLegacyBackgroundCommit"

    private class Members(
        val width: Field,
        val x: Field,
        val getState: Method,
        val getAnimatorDelegate: Method,
        val getEventCoordinator: Method,
        val isTempHidden: Method,
        val commit: Method,
        val bigIslandState: Class<*>,
    )

    // 已加载类的成员集合不可变，解析失败同样缓存（Optional.empty），警告因此天然每类只打一次。
    private val resolutions = ConcurrentHashMap<Class<*>, Optional<Members>>()
    private val firstCommitLogged = AtomicBoolean(false)

    /** 刷新前读一次；成员解析不到或读取失败时返回 null，调用方随后不做提交。 */
    fun capture(islandView: ViewGroup): IslandLegacyBigIslandBounds? {
        val members = members(islandView.javaClass) ?: return null
        return runCatching { read(members, islandView) }
            .onFailure { HookLogger.e(TAG, "读取旧版岛宽度与 x 失败", it) }
            .getOrNull()
    }

    /** 刷新后再读一次，满足 [IslandLegacyBackgroundCommitPolicy] 时调用原生大岛变化提交。 */
    fun commitIfNeeded(islandView: ViewGroup, before: IslandLegacyBigIslandBounds) {
        val members = members(islandView.javaClass) ?: return
        runCatching {
            val after = read(members, islandView)
            val shouldCommit = IslandLegacyBackgroundCommitPolicy.shouldCommit(
                before = before,
                after = after,
                isBigIslandState = {
                    members.bigIslandState.isInstance(members.getState.invoke(islandView))
                },
                isTempHidden = {
                    val coordinator = members.getEventCoordinator.invoke(islandView)
                    coordinator != null &&
                        members.isTempHidden.invoke(coordinator, islandView) as Boolean
                },
            )
            if (!shouldCommit) return@runCatching
            // getAnimatorDelegate 读的是可空字段，委托尚未创建时没有可提交的对象。
            val delegate = members.getAnimatorDelegate.invoke(islandView) ?: return@runCatching
            members.commit.invoke(delegate, islandView)
            if (BuildConfig.DEBUG && firstCommitLogged.compareAndSet(false, true)) {
                HookLogger.i(
                    TAG,
                    "旧版岛背景提交首次命中: width ${before.width} -> ${after.width}, " +
                        "x ${before.x} -> ${after.x}",
                )
            }
        }.onFailure { HookLogger.e(TAG, "旧版岛背景提交失败", it) }
    }

    private fun read(members: Members, islandView: ViewGroup) = IslandLegacyBigIslandBounds(
        width = members.width.getInt(islandView),
        x = members.x.getInt(islandView),
    )

    private fun members(hostClass: Class<*>): Members? =
        resolutions.computeIfAbsent(hostClass) { Optional.ofNullable(resolve(it)) }.orElse(null)

    private fun resolve(hostClass: Class<*>): Members? {
        var baseClass: Class<*>? = hostClass
        while (baseClass != null && baseClass.name != IslandLegacyWidthProfile.BASE_CONTENT_VIEW_CLASS) {
            baseClass = baseClass.superclass
        }
        if (baseClass == null) {
            warnMissing(hostClass, IslandLegacyWidthProfile.BASE_CONTENT_VIEW_CLASS)
            return null
        }
        val loader = baseClass.classLoader
        fun loadClass(name: String): Class<*>? =
            runCatching { Class.forName(name, false, loader) }.getOrNull()
        fun field(name: String): Field? = runCatching {
            baseClass.getDeclaredField(name).takeIf(IslandLegacyWidthProfile::isRequiredField)
                ?.also { it.isAccessible = true }
        }.getOrNull()
        fun method(owner: Class<*>?, spec: LegacyMethodSpec): Method? = owner?.declaredMethods
            ?.filter { IslandLegacyWidthProfile.matches(spec, it) }
            ?.singleOrNull()
            ?.also { it.isAccessible = true }

        val stateClass = loadClass(IslandLegacyWidthProfile.STATE_CLASS)
        val bigIslandState = loadClass(IslandLegacyWidthProfile.BIG_ISLAND_STATE_CLASS)
            ?.takeIf { stateClass?.isAssignableFrom(it) == true }
        val coordinatorClass = loadClass(IslandLegacyWidthProfile.EVENT_COORDINATOR_CLASS)
        val delegateClass = loadClass(IslandLegacyWidthProfile.ANIMATION_DELEGATE_CLASS)

        val width = field(IslandLegacyWidthProfile.VIEW_WIDTH_FIELD)
        val x = field(IslandLegacyWidthProfile.VIEW_X_FIELD)
        val getState = method(baseClass, IslandLegacyWidthProfile.GET_STATE_METHOD)
        val getAnimatorDelegate = method(baseClass, IslandLegacyWidthProfile.GET_ANIMATOR_DELEGATE_METHOD)
        val getEventCoordinator = method(baseClass, IslandLegacyWidthProfile.GET_EVENT_COORDINATOR_METHOD)
        val isTempHidden = method(coordinatorClass, IslandLegacyWidthProfile.IS_TEMP_HIDDEN_METHOD)
        val commit = method(delegateClass, IslandLegacyWidthProfile.BIG_ISLAND_CHANGED_ANIMATION_METHOD)

        val missing = listOf(
            "字段 ${IslandLegacyWidthProfile.VIEW_WIDTH_FIELD}" to width,
            "字段 ${IslandLegacyWidthProfile.VIEW_X_FIELD}" to x,
            "类 ${IslandLegacyWidthProfile.BIG_ISLAND_STATE_CLASS}" to bigIslandState,
            "方法 ${IslandLegacyWidthProfile.GET_STATE_METHOD.name}" to getState,
            "方法 ${IslandLegacyWidthProfile.GET_ANIMATOR_DELEGATE_METHOD.name}" to getAnimatorDelegate,
            "方法 ${IslandLegacyWidthProfile.GET_EVENT_COORDINATOR_METHOD.name}" to getEventCoordinator,
            "方法 ${IslandLegacyWidthProfile.IS_TEMP_HIDDEN_METHOD.name}" to isTempHidden,
            "方法 ${IslandLegacyWidthProfile.BIG_ISLAND_CHANGED_ANIMATION_METHOD.name}" to commit,
        ).filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) {
            warnMissing(hostClass, missing.joinToString())
            return null
        }
        return Members(
            width = width!!,
            x = x!!,
            getState = getState!!,
            getAnimatorDelegate = getAnimatorDelegate!!,
            getEventCoordinator = getEventCoordinator!!,
            isTempHidden = isTempHidden!!,
            commit = commit!!,
            bigIslandState = bigIslandState!!,
        )
    }

    private fun warnMissing(hostClass: Class<*>, missing: String) {
        HookLogger.w(
            TAG,
            "跳过旧版岛背景提交: 缺少 $missing, class=${hostClass.name}",
            null,
        )
    }
}
