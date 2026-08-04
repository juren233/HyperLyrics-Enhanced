/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

internal data class PendingDataBindingRefresh(
    val mediaId: String,
    val alias: AppliedMetadataAlias,
    val bindGeneration: Long,
)

internal data class PendingVisibleDataBindingResolution(
    val mediaId: String,
    val bindGeneration: Long,
    val originalResolutionMode: InAppOriginalResolutionMode,
)

internal enum class DataBindingRefreshStrategy {
    VARIABLES_ONLY,
    FULL_INVALIDATE,
}

internal data class DataBindingAliasValues(
    val title: String?,
    val subtitle: String?,
)

internal data class DataBindingVariableApplyResult(
    val titleApplied: Boolean,
    val subtitleApplied: Boolean,
)

internal enum class VisibleTextField {
    TITLE,
    ARTIST,
    ALBUM,
}

internal data class InAppActionSheetBinding(
    val mediaId: String,
    val field: VisibleTextField,
)
