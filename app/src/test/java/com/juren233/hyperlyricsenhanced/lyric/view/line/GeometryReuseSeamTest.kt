/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM geometry integration: real BoundGeometryReuse and SeamOcclusionLayout, supplied
 * fractional advances. No Android Paint, View, font metrics, or rendering is exercised here.
 */
class GeometryReuseSeamTest {
    @Test
    fun `equal total advance with redistributed characters changes revision and seam straddler`() {
        val fixture = Fixture()
        val first = Metrics(listOf(4.25f, 8.5f, 3.75f))
        fixture.refresh(first)
        val oldLayout = fixture.layout()
        val oldRevision = fixture.owner.revision
        assertEquals(1, oldLayout.straddlingUnit(0f, 6f)!!.charStart)

        val redistributed = Metrics(listOf(8.5f, 4.25f, 3.75f))
        assertEquals(first.advances.sum(), redistributed.advances.sum(), 0f)
        assertFalse(fixture.refresh(redistributed, resize = true))
        val newLayout = fixture.layout()

        assertEquals(oldRevision + 1, fixture.owner.revision)
        assertNotSame(oldLayout, newLayout)
        assertEquals(oldLayout.totalAdvance, newLayout.totalAdvance, 0f)
        assertEquals(0, newLayout.straddlingUnit(0f, 6f)!!.charStart)
        assertEquals(8.5f, newLayout.charAdvanceAt(1), 0f)
    }

    @Test
    fun `owned same metrics resize and forced full refresh preserve seam revision and table`() {
        val fixture = Fixture()
        val metrics = Metrics(listOf(4.25f, 8.5f, 3.75f))
        fixture.refresh(metrics)
        val layout = fixture.layout()
        val revision = fixture.owner.revision

        assertTrue(fixture.refresh(metrics, resize = true))
        assertEquals(revision, fixture.owner.revision)
        assertSame(layout, fixture.layout())
        assertFalse(fixture.refresh(metrics, resize = false))
        assertEquals(revision, fixture.owner.revision)
        assertSame(layout, fixture.layout())
        assertEquals(1, fixture.layout().straddlingUnit(0.375f, 6.375f)!!.charStart)
    }

    @Test
    fun `independent same content model cannot inherit previous model seam ownership`() {
        val fixture = Fixture()
        val metrics = Metrics(listOf(4.25f, 8.5f, 3.75f))
        fixture.refresh(metrics)
        val previous = fixture.model
        val previousLayout = fixture.layout()
        val previousRevision = fixture.owner.revision
        val independent = Model(previous.text, previous.advances.toList())
        assertEquals(previous, independent)
        assertNotSame(previous, independent)
        assertFalse(fixture.owner.canReuse(independent, true, true, metrics))

        fixture.model = independent
        fixture.owner.bind(independent)
        assertTrue(fixture.owner.revision > previousRevision)
        assertFalse(fixture.refresh(metrics, resize = true))
        assertNotSame(previousLayout, fixture.layout())
        assertTrue(fixture.owner.canReuse(independent, true, true, metrics))
        assertFalse(fixture.owner.canReuse(previous, true, true, metrics))
        assertEquals(previousLayout.totalAdvance, fixture.layout().totalAdvance, 0f)
    }

    @Test
    fun `escaped word geometry full refresh invalidates seam even with same descriptor and total`() {
        val fixture = Fixture()
        val metrics = Metrics(listOf(4.25f, 8.5f, 3.75f))
        fixture.refresh(metrics)
        val previousLayout = fixture.layout()
        val revision = fixture.owner.revision
        fixture.owner.revoke()
        // Simulate a caller editing escaped per-character geometry before a full update.
        // The supplied callback is deterministic JVM input, not an Android measurement stub.
        assertFalse(fixture.refresh(metrics, resize = true, measured = listOf(8.5f, 4.25f, 3.75f)))

        assertEquals(revision + 1, fixture.owner.revision)
        assertFalse(fixture.owner.ownershipRetained)
        assertFalse(fixture.owner.canReuse(fixture.model, true, true, metrics))
        val rebuilt = fixture.layout()
        assertNotSame(previousLayout, rebuilt)
        assertEquals(previousLayout.totalAdvance, rebuilt.totalAdvance, 0f)
        assertEquals(0, rebuilt.straddlingUnit(0f, 6f)!!.charStart)
        fixture.owner.bind(fixture.model)
        assertFalse(fixture.refresh(metrics, resize = false))
        assertEquals(revision + 2, fixture.owner.revision)
        assertNotSame(rebuilt, fixture.layout())
    }

    private data class Model(val text: String, var advances: List<Float> = emptyList())
    private data class Metrics(val advances: List<Float>)

    /** Minimal composition harness; Android call-site wiring is checked separately as source. */
    private class Fixture {
        var model = Model("甲乙丙")
        val owner = BoundGeometryReuse<Model, Metrics>().apply { bind(model) }
        private var cachedModel: Model? = null
        private var cachedRevision = Long.MIN_VALUE
        private var cachedLayout: SeamOcclusionLayout? = null

        fun refresh(metrics: Metrics, resize: Boolean = false, measured: List<Float> = metrics.advances): Boolean {
            val reused = owner.canReuse(model, resize, true, metrics)
            val ticket = owner.begin(model)
            if (!reused) model.advances = measured.toList()
            owner.complete(model, ticket, metrics, true, reused)
            return reused
        }

        fun layout(): SeamOcclusionLayout {
            if (cachedModel !== model || cachedRevision != owner.revision) {
                cachedLayout = requireNotNull(SeamOcclusionLayout.build(model.text, model.advances.toFloatArray()))
                cachedModel = model
                cachedRevision = owner.revision
            }
            return requireNotNull(cachedLayout)
        }
    }
}
