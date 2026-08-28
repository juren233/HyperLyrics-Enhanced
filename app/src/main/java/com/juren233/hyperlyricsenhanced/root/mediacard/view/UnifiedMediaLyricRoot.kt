/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.mediacard.view

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.IdentityHashMap
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationAlignment
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationConfig
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationGroup
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationModel
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationRole
import com.juren233.hyperlyricsenhanced.root.mediacard.LyricPresentationSlot
import com.juren233.hyperlyricsenhanced.root.mediacard.transition.MediaCardFramePlan
import kotlin.math.roundToInt

/**
 * One persistent lyric root shared by notification media and Full-AOD morphing.
 * The hierarchy is created once: empty/late translation slots change visibility,
 * never View identity or child count.
 */
internal class UnifiedMediaLyricRoot(
    context: Context,
) : LinearLayout(context) {
    private val groupContainers = LyricPresentationGroup.values().map { group ->
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setClipChildren(true)
            setClipToPadding(true)
            tag = group
        }
    }
    private val rowViews = LyricPresentationGroup.values().associateWith { group ->
        LyricPresentationSlot.values().associateWith { slot ->
            TextView(context).apply {
                includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                visibility = GONE
            }.also { view ->
                groupContainers[group.ordinal].addView(
                    view,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                )
            }
        }
    }
    private val roleByRow = IdentityHashMap<TextView, LyricPresentationRole>()
    private val previewRow = TextView(context).apply {
        includeFontPadding = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private var currentModel: LyricPresentationModel? = null
    private var currentConfig: LyricPresentationConfig = LyricPresentationConfig()
    private var currentPreview: String? = null
    private var mainTextSizeSp: Float = 18f
    private var backingTextSizeSp: Float = 14f
    private var translationTextSizeSp: Float = 13f

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setClipChildren(true)
        setClipToPadding(true)
        groupContainers.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
        addView(previewRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        alpha = 1f
    }

    fun bind(
        model: LyricPresentationModel,
        config: LyricPresentationConfig,
        previewText: String? = null,
        previewAlignment: LyricPresentationAlignment = LyricPresentationAlignment.CENTER,
        mainTextSizeSp: Float = 18f,
        backingTextSizeSp: Float = 14f,
        translationTextSizeSp: Float = 13f,
    ) {
        currentModel = model
        currentConfig = config
        currentPreview = previewText?.trim()?.takeIf { it.isNotEmpty() }
        this.mainTextSizeSp = mainTextSizeSp.coerceAtLeast(1f)
        this.backingTextSizeSp = backingTextSizeSp.coerceAtLeast(1f)
        this.translationTextSizeSp = translationTextSizeSp.coerceAtLeast(1f)
        val groups = model.groups.associateBy { it.group }
        LyricPresentationGroup.values().forEach { group ->
            val groupModel = groups[group]
            val rows = rowViews.getValue(group)
            LyricPresentationSlot.values().forEach { slot ->
                val row = rows.getValue(slot)
                val line = groupModel?.lines?.firstOrNull { it.slot == slot }
                val text = line?.text.orEmpty()
                row.text = text
                line?.role?.let { roleByRow[row] = it }
                row.gravity = gravityFor(line?.alignment ?: LyricPresentationAlignment.CENTER)
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp(line?.role, config))
                row.alpha = 1f
                row.translationY = 0f
                row.visibility = if (text.isNotBlank()) VISIBLE else GONE
            }
            groupContainers[group.ordinal].visibility = if (rows.values.any { it.visibility == VISIBLE }) VISIBLE else GONE
            groupContainers[group.ordinal].alpha = 1f
        }
        previewRow.text = currentPreview.orEmpty()
        roleByRow[previewRow] = LyricPresentationRole.PREVIEW
        previewRow.gravity = gravityFor(previewAlignment)
        previewRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, translationTextSizeSp)
        previewRow.visibility = if (currentPreview != null) VISIBLE else GONE
        previewRow.alpha = 1f
        requestLayout()
    }

    fun applyTextColors(mainColor: Int, secondaryColor: Int) {
        roleByRow.forEach { (row, role) ->
            row.setTextColor(if (role == LyricPresentationRole.MAIN) mainColor else secondaryColor)
        }
    }

    fun applyFrame(plan: MediaCardFramePlan) {
        var layoutDirty = false
        alpha = plan.rootAlpha.coerceIn(0f, 1f)
        translationY = plan.rootTranslationY
        scaleY = plan.rootScaleY.coerceAtLeast(0f)
        groupContainers.forEachIndexed { index, group ->
            val groupAlpha = plan.groupAlphas.getOrElse(index) { 0f }.coerceIn(0f, 1f)
            group.alpha = groupAlpha
            val desiredVisibility = if (hasText(index) &&
                (!plan.stableAfterCommit || groupAlpha > 0.001f)
            ) VISIBLE else GONE
            if (group.visibility != desiredVisibility) {
                group.visibility = desiredVisibility
                layoutDirty = true
            }
        }
        val previewAlpha = if (plan.targetFullAod) 1f - plan.fraction else plan.fraction
        previewRow.alpha = previewAlpha.coerceIn(0f, 1f)
        val previewVisibility = if (plan.stableAfterCommit && previewAlpha <= 0.001f) GONE
        else if (currentPreview != null) VISIBLE else GONE
        if (previewRow.visibility != previewVisibility) {
            previewRow.visibility = previewVisibility
            layoutDirty = true
        }
        val secondary = firstVisibleRow(LyricPresentationGroup.NEXT)
        plan.secondaryTextSizeSp?.let { size ->
            val oldSize = secondary?.textSize
            secondary?.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (oldSize != null && oldSize != secondary.textSize) layoutDirty = true
        }
        secondary?.translationY = plan.secondaryTranslationY
        plan.resolvedSecondaryAlpha?.let { secondary?.alpha = it.coerceIn(0f, 1f) }
        if (!plan.secondaryVisible) {
            groupContainers.getOrNull(LyricPresentationGroup.NEXT.ordinal)?.alpha = 0f
        }
        if (plan.stableAfterCommit && !plan.lyricVisible) {
            // Keep the single root attached for the next native transition, but
            // remove it from drawing/layout only after the terminal frame.
            if (visibility != INVISIBLE) {
                visibility = INVISIBLE
                layoutDirty = true
            }
        } else if (visibility != VISIBLE) {
            visibility = VISIBLE
            layoutDirty = true
        }
        if (layoutDirty) requestLayout() else invalidate()
    }

    fun resetToStable() {
        visibility = VISIBLE
        alpha = 1f
        translationY = 0f
        scaleY = 1f
        groupContainers.forEachIndexed { index, group ->
            group.alpha = 1f
            group.visibility = if (hasText(index)) VISIBLE else GONE
        }
        rowViews.values.flatMap { it.values }.forEach { row ->
            row.alpha = 1f
            row.translationY = 0f
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp(roleByRow[row], currentConfig))
        }
        previewRow.alpha = 1f
        previewRow.visibility = if (currentPreview != null) VISIBLE else GONE
        previewRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, translationTextSizeSp)
        requestLayout()
    }

    fun measuredContentHeight(): Int = measuredHeight.takeIf { it > 0 } ?: height

    fun visibleSecondaryTextSizeSp(): Float? = firstVisibleRow(LyricPresentationGroup.NEXT)
        ?.let {
            val oneSp = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                1f,
                resources.displayMetrics,
            )
            it.textSize / oneSp
        }
        ?.takeIf { it > 0f }

    fun slotCount(): Int = LyricPresentationGroup.values().sumOf { group ->
        rowViews.getValue(group).size
    }

    fun hasVisibleContent(): Boolean = groupContainers.any { it.visibility == VISIBLE } ||
        previewRow.visibility == VISIBLE

    private fun hasText(index: Int): Boolean = groupContainers.getOrNull(index)?.let { container ->
        container.childCount > 0 && (0 until container.childCount).any {
            container.getChildAt(it).visibility == VISIBLE &&
                container.getChildAt(it) is TextView &&
                (container.getChildAt(it) as TextView).text.isNotBlank()
        }
    } == true

    private fun firstVisibleRow(group: LyricPresentationGroup): TextView? =
        rowViews.getValue(group).values.firstOrNull { it.visibility == VISIBLE }

    private fun textSizeSp(role: LyricPresentationRole?, config: LyricPresentationConfig): Float = when (role) {
        LyricPresentationRole.MAIN -> mainTextSizeSp
        LyricPresentationRole.BACKING -> backingTextSizeSp
        LyricPresentationRole.TRANSLATION,
        LyricPresentationRole.PREVIEW,
        null -> translationTextSizeSp
    }.coerceAtLeast(1f)

    private fun gravityFor(alignment: LyricPresentationAlignment): Int = when (alignment) {
        LyricPresentationAlignment.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
        LyricPresentationAlignment.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
        LyricPresentationAlignment.CENTER -> Gravity.CENTER
    }
}
