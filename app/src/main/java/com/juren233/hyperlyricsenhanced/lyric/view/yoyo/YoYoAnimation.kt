package com.juren233.hyperlyricsenhanced.lyric.view.yoyo

import android.animation.Animator
import android.view.View
import com.daimajia.androidanimations.library.YoYo

object YoYoAnimation {

    private const val KEY_ANIM_LOCK = 0x7F_114514
    private const val KEY_ANIM_HANDLE = 0x7F_191981

    internal fun isRunning(target: View): Boolean = state(target)?.running == true

    /** Width needs the new binding, not the end of its entrance animation. */
    internal fun isAwaitingContent(target: View): Boolean = state(target)?.awaitingContent == true

    private fun state(target: View) = target.getTag(KEY_ANIM_LOCK) as? ContentAnimationState

    fun <T : View> switchContent(
        target: T,
        outConfig: AnimConfig,
        inConfig: AnimConfig,
        action: (T) -> Unit
    ) {
        cancelAnimation(target)
        if (target.parent == null || !target.isAttachedToWindow) {
            action(target)
            return
        }
        val transaction = ContentAnimationState(awaitingContent = true)
        target.setTag(KEY_ANIM_LOCK, transaction)

        val outHandle = YoYo.with(outConfig.technique)
            .duration(outConfig.duration)
            .interpolate(outConfig.interpolator)
            .withListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(p0: Animator) {}
                override fun onAnimationRepeat(p0: Animator) {}
                override fun onAnimationCancel(p0: Animator) {
                    // 内容写入只排队在淡出结束回调，装配层却在排队瞬间就同步记录了
                    // 目标签名（"已排队"语义）。取消若只清锁，写入会被永久丢弃：
                    // 后续所有应用都按签名相同跳过，视图停在旧内容（单曲循环下
                    // 没有元数据刷新来补救，表现为下首预览残留）。取消时必须把
                    // 排队中的写入立即落地。
                    val queued = transaction.cancel()
                    if (state(target) === transaction && queued) action(target)
                }

                override fun onAnimationEnd(p0: Animator) {
                    if (state(target) !== transaction || !transaction.claimContent()) return

                    // 执行内容更新
                    action(target)
                    if (state(target) !== transaction || !transaction.running) return

                    val inHandle = YoYo.with(inConfig.technique)
                        .duration(inConfig.duration)
                        .interpolate(inConfig.interpolator)
                        .withListener(object : Animator.AnimatorListener {
                            override fun onAnimationStart(p0: Animator) {}
                            override fun onAnimationRepeat(p0: Animator) {}
                            override fun onAnimationCancel(p0: Animator) {
                                transaction.finish()
                            }

                            override fun onAnimationEnd(p0: Animator) {
                                transaction.finish()
                                if (state(target) === transaction) target.setTag(KEY_ANIM_HANDLE, null)
                            }
                        })
                        .playOn(target)

                    target.setTag(KEY_ANIM_HANDLE, inHandle)
                }
            })
            .playOn(target)

        target.setTag(KEY_ANIM_HANDLE, outHandle)
    }

    fun <T : View> revealContent(
        target: T,
        inConfig: AnimConfig,
        action: (T) -> Unit
    ) {
        cancelAnimation(target)
        action(target)
        if (target.parent == null || !target.isAttachedToWindow) return

        val transaction = ContentAnimationState(awaitingContent = false)
        target.setTag(KEY_ANIM_LOCK, transaction)
        val inHandle = YoYo.with(inConfig.technique)
            .duration(inConfig.duration)
            .interpolate(inConfig.interpolator)
            .withListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) = Unit
                override fun onAnimationRepeat(animation: Animator) = Unit

                override fun onAnimationCancel(animation: Animator) {
                    transaction.finish()
                    if (state(target) === transaction) target.setTag(KEY_ANIM_HANDLE, null)
                }

                override fun onAnimationEnd(animation: Animator) {
                    transaction.finish()
                    if (state(target) === transaction) target.setTag(KEY_ANIM_HANDLE, null)
                }
            })
            .playOn(target)

        target.setTag(KEY_ANIM_HANDLE, inHandle)
    }

    fun cancelAnimation(target: View) {
        val transaction = state(target)
        val handle = target.getTag(KEY_ANIM_HANDLE) as? YoYo.YoYoString
        handle?.stop(true)
        if (state(target) === transaction) {
            transaction?.finish()
            target.setTag(KEY_ANIM_HANDLE, null)
            target.setTag(KEY_ANIM_LOCK, null)
        }
    }
}

/**
 * 歌词行更新扩展
 * @param preset 使用 [YoYoPresets] 中的预设组合
 */
fun <T : View> T.animateUpdate(
    preset: Pair<AnimConfig, AnimConfig> = YoYoPresets.FadeOut_FadeIn,
    block: T.() -> Unit
) {
    YoYoAnimation.switchContent(this, preset.first, preset.second) {
        it.block()
    }
}

fun <T : View> T.animateEntrance(
    preset: Pair<AnimConfig, AnimConfig> = YoYoPresets.FadeOut_FadeIn,
    block: T.() -> Unit
) {
    YoYoAnimation.revealContent(this, preset.second) {
        it.block()
    }
}
