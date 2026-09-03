package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandGradientCoverLayoutTest {
    @Test
    fun centerCropWindowTracksTheVisibleRightEdgeColumn() {
        assertEquals(
            IslandCenterCropWindow(0f, 200f, 400f, 600f),
            IslandGradientCoverLayout.centerCropWindow(
                sourceWidth = 400,
                sourceHeight = 800,
                targetWidth = 104f,
                targetHeight = 104f,
            ),
        )
        assertEquals(
            IslandCenterCropWindow(200f, 0f, 600f, 400f),
            IslandGradientCoverLayout.centerCropWindow(
                sourceWidth = 800,
                sourceHeight = 400,
                targetWidth = 104f,
                targetHeight = 104f,
            ),
        )
    }

    @Test
    fun unmeasuredCoverUsesHostIconDpInsteadOfArtworkPixels() {
        assertEquals(
            66,
            IslandGradientCoverLayout.resolveIconDimension(
                actualSize = 0,
                measuredSize = 0,
                layoutParamSize = 0,
                minimumSize = 0,
                density = 2.75f,
            ),
        )
    }

    @Test
    fun measuredOrDeclaredIconSizeWinsOverTheDpFallback() {
        assertEquals(
            68,
            IslandGradientCoverLayout.resolveIconDimension(
                actualSize = 68,
                measuredSize = 64,
                layoutParamSize = 60,
                minimumSize = 56,
                density = 2.75f,
            ),
        )
        assertEquals(
            60,
            IslandGradientCoverLayout.resolveIconDimension(
                actualSize = 0,
                measuredSize = 0,
                layoutParamSize = 60,
                minimumSize = 56,
                density = 2.75f,
            ),
        )
    }

    @Test
    fun rotatingCoverUsesItsMeasuredCenterAfterGradientPivotReset() {
        val pivot = requireNotNull(
            IslandAlbumCoverRotationGeometry.centeredPivot(width = 60, height = 60)
        )

        assertEquals(30f, pivot.x)
        assertEquals(30f, pivot.y)
    }

    @Test
    fun rotatingCoverWaitsForAValidLayoutBeforeSettingPivot() {
        assertEquals(null, IslandAlbumCoverRotationGeometry.centeredPivot(width = 0, height = 60))
        assertEquals(null, IslandAlbumCoverRotationGeometry.centeredPivot(width = 60, height = 0))
    }

    @Test
    fun runtimeStateNameMatchesOriginalMiuiDex() {
        assertEquals(
            "miui.systemui.dynamicisland.event.DynamicIslandState\$SmallIsland",
            IslandGradientCoverRuntimeIdentifiers.SMALL_ISLAND_STATE_CLASS,
        )
        assertEquals(
            "miui.systemui.dynamicisland.event.DynamicIslandState\$BigIsland",
            IslandGradientCoverRuntimeIdentifiers.BIG_ISLAND_STATE_CLASS,
        )
        assertEquals(
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentFakeView",
            IslandGradientCoverRuntimeIdentifiers.FAKE_CONTENT_VIEW_CLASS,
        )
        assertEquals(
            "miui.systemui.dynamicisland.window.content.helpers.DynamicIslandContentViewPhoneHelper",
            IslandGradientCoverRuntimeIdentifiers.PHONE_HELPER_CLASS,
        )
        assertEquals(
            "miui.systemui.dynamicisland.model.IslandContentViewHolder",
            IslandGradientCoverRuntimeIdentifiers.CONTENT_VIEW_HOLDER_CLASS,
        )
        assertEquals(
            "findAndInitViews",
            IslandGradientCoverRuntimeIdentifiers.FIND_AND_INIT_VIEWS_METHOD,
        )
        assertEquals(
            true,
            IslandGradientCoverRuntimeIdentifiers.isSmallIslandState(
                "miui.systemui.dynamicisland.event.DynamicIslandState\$SmallIsland",
            ),
        )
        assertEquals(
            true,
            IslandGradientCoverRuntimeIdentifiers.compactIslandRole(
                "miui.systemui.dynamicisland.event.DynamicIslandState\$SmallIsland",
            ),
        )
        assertEquals(
            false,
            IslandGradientCoverRuntimeIdentifiers.compactIslandRole(
                "miui.systemui.dynamicisland.event.DynamicIslandState\$BigIsland",
            ),
        )
        assertEquals(
            null,
            IslandGradientCoverRuntimeIdentifiers.compactIslandRole(
                "miui.systemui.dynamicisland.event.DynamicIslandState\$AppExpanded",
            ),
        )
    }

    @Test
    fun decompilerStyleStateAliasesAreRejected() {
        assertEquals(
            false,
            IslandGradientCoverRuntimeIdentifiers.isSmallIslandState(
                "miui.systemui.dynamicisland.event.DynamicIslandState.SmallIsland",
            ),
        )
        assertEquals(
            false,
            IslandGradientCoverRuntimeIdentifiers.isSmallIslandState(
                "miui.systemui.dynamicisland.event_1.DynamicIslandState\$SmallIsland",
            ),
        )
    }

    @Test
    fun expandedIslandUsesStableModuleHeightAndCoverGeometry() {
        val placement = requireNotNull(
            IslandGradientCoverLayout.resolve(
                moduleWidth = 287,
                moduleHeight = 104,
                moduleWindowY = 19f,
                islandWindowX = 246f,
                iconWindowX = 270f,
                iconWindowY = 41f,
                iconWidth = 60,
                iconHeight = 60,
                isSmallIsland = false,
                density = 2.75f,
            )
        )

        assertEquals(104, placement.coverWidth)
        assertEquals(104, placement.coverHeight)
        assertEquals(104f / 60f, placement.iconScaleX)
        assertEquals(104f / 60f, placement.iconScaleY)
        assertEquals(-24f, placement.iconTranslationX)
        assertEquals(-22f, placement.iconTranslationY)
        assertEquals(28f / 104f, placement.gradientBandFraction)
    }

    @Test
    fun smallIslandCoverFillsTheStableSmallModuleAndDoesNotReserveTextSpace() {
        val placement = requireNotNull(
            IslandGradientCoverLayout.resolve(
                moduleWidth = 104,
                moduleHeight = 104,
                moduleWindowY = 19f,
                islandWindowX = 548f,
                iconWindowX = 564f,
                iconWindowY = 27f,
                iconWidth = 68,
                iconHeight = 68,
                isSmallIsland = true,
                density = 2.75f,
            )
        )

        assertEquals(104, placement.coverWidth)
        assertEquals(104, placement.coverHeight)
        assertEquals(104f / 68f, placement.iconScaleX)
        assertEquals(104f / 68f, placement.iconScaleY)
        assertEquals(-16f, placement.iconTranslationX)
        assertEquals(-8f, placement.iconTranslationY)
    }

    @Test
    fun expandedAndSmallLayoutsNeverDependOnTextGeometry() {
        val expanded = IslandGradientCoverLayout.resolve(
            moduleWidth = 287,
            moduleHeight = 104,
            moduleWindowY = 19f,
            islandWindowX = 246f,
            iconWindowX = 270f,
            iconWindowY = 41f,
            iconWidth = 60,
            iconHeight = 60,
            isSmallIsland = false,
            density = 2.75f,
        )
        val small = IslandGradientCoverLayout.resolve(
            moduleWidth = 104,
            moduleHeight = 104,
            moduleWindowY = 19f,
            islandWindowX = 548f,
            iconWindowX = 564f,
            iconWindowY = 27f,
            iconWidth = 68,
            iconHeight = 68,
            isSmallIsland = true,
            density = 2.75f,
        )
        assertEquals(expanded?.coverWidth, small?.coverWidth)
        assertEquals(expanded?.coverHeight, small?.coverHeight)
    }

    @Test
    fun invalidModuleHeightIsNotApplied() {
        val placement = IslandGradientCoverLayout.resolve(
            moduleWidth = 104,
            moduleHeight = 0,
            moduleWindowY = 0f,
            islandWindowX = 0f,
            iconWindowX = 0f,
            iconWindowY = 0f,
            iconWidth = 60,
            iconHeight = 60,
            isSmallIsland = true,
            density = 2.75f,
        )

        assertEquals(null, placement)
    }

    @Test
    fun selectsTheWideStableCapsuleAndRejectsTheTransientWrongInstance() {
        val geometry = IslandGradientCoverLayout.selectGeometry(
            candidates = listOf(
                IslandGradientGeometryCandidate(left = 560, top = 19, width = 256, height = 104),
                IslandGradientGeometryCandidate(left = 274, top = 19, width = 652, height = 104),
                IslandGradientGeometryCandidate(left = 1129, top = 19, width = 104, height = 104),
            ),
            moduleWidth = 256,
            moduleHeight = 104,
            iconCenterX = 471f,
            iconCenterY = 71f,
            rootLeft = -11f,
            rootRight = 1211f,
            isSmallIsland = false,
            tolerance = 11,
        )

        assertEquals(
            IslandGradientGeometryCandidate(left = 274, top = 19, width = 652, height = 104),
            geometry,
        )
    }

    @Test
    fun actualCoordinatesAreAlreadyWindowCoordinates() {
        val backgroundWindowX = 286
        val geometry = IslandGradientCoverLayout.fromActualEdges(
            actualLeft = 274,
            actualTop = 19,
            actualRight = 926,
            actualBottom = 123,
        )

        assertEquals(274, geometry.left)
        assertEquals(19, geometry.top)
        assertEquals(652, geometry.width)
        assertEquals(104, geometry.height)
        assertEquals(560, backgroundWindowX + geometry.left)
    }

    @Test
    fun animatedActualEdgesResolveToTheStableIslandHeight() {
        val geometry = IslandGradientCoverLayout.fromActualEdges(
            actualLeft = 269,
            actualTop = 19,
            actualRight = 930,
            actualBottom = 123,
        )

        assertEquals(661, geometry.width)
        assertEquals(104, geometry.height)
    }

    @Test
    fun smallIslandChoosesTheMatchingCircleAroundItsIcon() {
        val geometry = IslandGradientCoverLayout.selectGeometry(
            candidates = listOf(
                IslandGradientGeometryCandidate(left = 274, top = 19, width = 652, height = 104),
                IslandGradientGeometryCandidate(left = 548, top = 19, width = 104, height = 104),
            ),
            moduleWidth = 104,
            moduleHeight = 104,
            iconCenterX = 600f,
            iconCenterY = 71f,
            rootLeft = -11f,
            rootRight = 1211f,
            isSmallIsland = true,
            tolerance = 11,
        )

        assertEquals(
            IslandGradientGeometryCandidate(left = 548, top = 19, width = 104, height = 104),
            geometry,
        )
    }

    @Test
    fun embeddedTransitionCacheWidthDoesNotFollowAnimatedAvailableWidth() {
        val density = 3f
        val coverWidth = 104f
        assertEquals(
            9f,
            IslandGradientCoverLayout.embeddedTransitionInset(
                coverWidth = coverWidth,
                density = density,
            ),
        )
        assertEquals(
            24f,
            IslandGradientCoverLayout.embeddedTransitionBlurInset(
                coverWidth = coverWidth,
                density = density,
            ),
        )
        assertEquals(
            24f,
            IslandGradientCoverLayout.embeddedTransitionCacheInset(
                coverWidth = coverWidth,
                density = density,
            ),
        )
        assertEquals(
            15f,
            IslandGradientCoverLayout.embeddedTransitionRawCacheOffset(
                transitionInset = 9f,
                blurInset = 24f,
            ),
        )
        val cacheWidth = IslandGradientCoverLayout.embeddedTransitionBitmapWidth(
            coverWidth = coverWidth,
            density = density,
        )

        assertEquals(240, cacheWidth)
        assertEquals(
            12f,
            IslandGradientCoverLayout.embeddedTransitionVisibleExtension(
                availableWidth = 12f,
                density = density,
            ),
        )
        assertEquals(
            216f,
            IslandGradientCoverLayout.embeddedTransitionVisibleExtension(
                availableWidth = 220f,
                density = density,
            ),
        )
        assertEquals(
            cacheWidth,
            IslandGradientCoverLayout.embeddedTransitionBitmapWidth(
                coverWidth = coverWidth,
                density = density,
            ),
        )
    }

    @Test
    fun pausedStateDrawsCoverAboveTransitionButPlayingStateKeepsTransitionAboveCover() {
        assertTrue(IslandGradientCoverLayout.embeddedCoverOnTopForPlaybackState(isPlaying = false))
        assertFalse(IslandGradientCoverLayout.embeddedCoverOnTopForPlaybackState(isPlaying = true))
    }

    @Test
    fun embeddedTransitionKeepsAFullStrengthPlatformBeforeSmootherFade() {
        val overlap = 6f
        val hold = 18f
        val totalWidth = 137f
        val fadeMidpoint = (overlap + hold + totalWidth) / 2f

        assertEquals(
            0f,
            IslandGradientCoverLayout.embeddedTransitionBlackMix(
                position = overlap + hold,
                totalWidth = totalWidth,
                overlap = overlap,
                hold = hold,
            ),
            0.0001f,
        )
        assertEquals(
            0.5f,
            IslandGradientCoverLayout.embeddedTransitionBlackMix(
                position = fadeMidpoint,
                totalWidth = totalWidth,
                overlap = overlap,
                hold = hold,
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            IslandGradientCoverLayout.embeddedTransitionBlackMix(
                position = totalWidth,
                totalWidth = totalWidth,
                overlap = overlap,
                hold = hold,
            ),
            0.0001f,
        )
    }

    @Test
    fun embeddedTransitionUsesAShortSmoothFeatherInsteadOfCoverWideDimming() {
        assertEquals(
            0f,
            IslandGradientCoverLayout.embeddedTransitionFeatherAlpha(
                position = 0f,
                overlap = 6f,
            ),
            0.0001f,
        )
        assertEquals(
            0.5f,
            IslandGradientCoverLayout.embeddedTransitionFeatherAlpha(
                position = 3f,
                overlap = 6f,
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            IslandGradientCoverLayout.embeddedTransitionFeatherAlpha(
                position = 6f,
                overlap = 6f,
            ),
            0.0001f,
        )
    }

    @Test
    fun embeddedTransitionUsesRealArtworkOnlyInsideOverlapThenClampsToTheLastColumn() {
        val cropRight = 401
        val sourceOverlap = 24f

        assertEquals(
            376f,
            IslandGradientCoverLayout.embeddedTransitionEdgeSourceX(
                position = 0f,
                overlap = 6f,
                cropRight = cropRight,
                sourceOverlap = sourceOverlap,
            ),
            0.0001f,
        )
        assertEquals(
            388f,
            IslandGradientCoverLayout.embeddedTransitionEdgeSourceX(
                position = 3f,
                overlap = 6f,
                cropRight = cropRight,
                sourceOverlap = sourceOverlap,
            ),
            0.0001f,
        )
        assertEquals(
            400f,
            IslandGradientCoverLayout.embeddedTransitionEdgeSourceX(
                position = 6f,
                overlap = 6f,
                cropRight = cropRight,
                sourceOverlap = sourceOverlap,
            ),
            0.0001f,
        )
        assertEquals(
            400f,
            IslandGradientCoverLayout.embeddedTransitionEdgeSourceX(
                position = 90f,
                overlap = 6f,
                cropRight = cropRight,
                sourceOverlap = sourceOverlap,
            ),
            0.0001f,
        )
    }

    @Test
    fun embeddedTransitionDiffusionStartsAtZeroAndExpandsSmoothlyWithinHeightBound() {
        val density = 3f
        val totalWidth = 137f
        val overlap = 6f
        val transitionInset = IslandGradientCoverLayout.embeddedTransitionInset(104f, density)
        val blurInset = IslandGradientCoverLayout.embeddedTransitionBlurInset(104f, density)
        val diffusionStart = IslandGradientCoverLayout.embeddedTransitionDiffusionStart(overlap)
        val midpoint = (diffusionStart + totalWidth) / 2f

        assertEquals(9f, IslandGradientCoverLayout.embeddedTransitionOverlap(104f, density))
        assertEquals(9f, transitionInset)
        assertEquals(24f, blurInset)
        assertEquals(18f, IslandGradientCoverLayout.embeddedTransitionHold(density))
        assertEquals(1f, IslandGradientCoverLayout.embeddedTransitionBlurEdgeAlpha())
        assertEquals(0f, IslandGradientCoverLayout.embeddedTransitionBlurFullAfterEdge(density))
        assertEquals(
            0f,
            IslandGradientCoverLayout.embeddedTransitionDiffusionRadius(
                position = diffusionStart,
                totalWidth = totalWidth,
                overlap = overlap,
                targetHeight = 104,
                density = density,
            ),
            0.0001f,
        )
        assertEquals(2.7f, diffusionStart, 0.0001f)
        assertEquals(
            0f,
            IslandGradientCoverLayout.embeddedTransitionDiffusionBlend(
                position = diffusionStart,
                overlap = overlap,
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            IslandGradientCoverLayout.embeddedTransitionDiffusionBlend(
                position = overlap,
                overlap = overlap,
            ),
            0.0001f,
        )
        assertEquals(
            13.52f,
            IslandGradientCoverLayout.embeddedTransitionDiffusionRadius(
                position = midpoint,
                totalWidth = totalWidth,
                overlap = overlap,
                targetHeight = 104,
                density = density,
            ),
            0.0001f,
        )
        assertEquals(
            27.04f,
            IslandGradientCoverLayout.embeddedTransitionDiffusionRadius(
                position = totalWidth,
                totalWidth = totalWidth,
                overlap = overlap,
                targetHeight = 104,
                density = density,
            ),
            0.0001f,
        )
        assertEquals(
            10.4f,
            IslandGradientCoverLayout.embeddedTransitionDiffusionRadius(
                position = totalWidth,
                totalWidth = totalWidth,
                overlap = overlap,
                targetHeight = 40,
                density = density,
            ),
            0.0001f,
        )
        assertEquals(6f, IslandGradientCoverLayout.embeddedTransitionBlurRadiusX(density), 0.0001f)
        assertEquals(15f, IslandGradientCoverLayout.embeddedTransitionBlurRadiusY(density), 0.0001f)
        assertEquals(
            0f,
            IslandGradientCoverLayout.embeddedTransitionBlurProgress(
                position = 0f,
                totalWidth = totalWidth,
                blurInset = blurInset,
                density = density,
            ),
            0.0001f,
        )
        assertEquals(
            0.5f,
            IslandGradientCoverLayout.embeddedTransitionBlurProgress(
                position = blurInset / 2f,
                totalWidth = totalWidth,
                blurInset = blurInset,
                density = density,
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            IslandGradientCoverLayout.embeddedTransitionBlurProgress(
                position = blurInset,
                totalWidth = totalWidth,
                blurInset = blurInset,
                density = density,
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            IslandGradientCoverLayout.embeddedTransitionBlurProgress(
                position = blurInset + 18f,
                totalWidth = totalWidth,
                blurInset = blurInset,
                density = density,
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            IslandGradientCoverLayout.embeddedTransitionBlurProgress(
                position = blurInset + 36f,
                totalWidth = totalWidth,
                blurInset = blurInset,
                density = density,
            ),
            0.0001f,
        )
    }

}
