package com.lidesheng.hyperlyric.lyric.view.yoyo.anim

import com.daimajia.androidanimations.library.BaseViewAnimator
import kotlin.reflect.KClass

enum class MyTechniques(private val klass: KClass<*>) {

    LandingSoft(LandingSoftAnimator::class);

    fun getAnimator(): BaseViewAnimator {
        try {
            return klass.java.getConstructor().newInstance() as BaseViewAnimator
        } catch (e: Exception) {
            throw e
        }
    }
}
