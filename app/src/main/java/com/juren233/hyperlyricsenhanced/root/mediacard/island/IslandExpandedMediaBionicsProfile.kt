/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.island

import java.lang.reflect.Method

/**
 * OS4 液态玻璃（bionics）材质的运行时标识。
 *
 * 原始 DEX 证据（OS4.0.0.6 MIUISystemUIPlugin classes2.dex）：
 * `Lmiui/systemui/util/MiBackgroundStyle;.clearBionicsMaterial(Landroid/view/View;)V`
 * 为 PUBLIC STATIC FINAL。该方法把视图材质重置回 Classic，
 * 是系统在 `updateBackgroundBg` 中从液态玻璃分支切回经典混色分支的原生做法；
 * 经典材质下的背景混色（背景混色 config）在液态玻璃材质上不生效。
 */
internal object IslandExpandedMediaBionicsProfile {
    const val MI_BACKGROUND_STYLE_CLASS = "miui.systemui.util.MiBackgroundStyle"
    const val CLEAR_BIONICS_MATERIAL_METHOD = "clearBionicsMaterial"
    const val VIEW_CLASS = "android.view.View"

    fun isClearBionicsMaterial(method: Method): Boolean {
        return isClearBionicsMaterial(
            name = method.name,
            returnTypeName = method.returnType.name,
            parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
        )
    }

    internal fun isClearBionicsMaterial(
        name: String,
        returnTypeName: String,
        parameterTypeNames: List<String>,
    ): Boolean {
        return name == CLEAR_BIONICS_MATERIAL_METHOD &&
            returnTypeName == Void.TYPE.name &&
            parameterTypeNames == listOf(VIEW_CLASS)
    }
}
