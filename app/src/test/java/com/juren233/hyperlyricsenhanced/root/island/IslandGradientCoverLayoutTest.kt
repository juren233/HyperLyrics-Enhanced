package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandGradientCoverLayoutTest {
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
}
