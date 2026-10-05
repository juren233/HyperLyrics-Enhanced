/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundGeometryReuseTest {
    @Test
    fun `fresh binding misses until completed then same binding resize hits`() {
        val fixture = Fixture()

        assertFalse(fixture.canReuse())
        assertNull(fixture.reuse.descriptor)

        fixture.complete()

        assertTrue(fixture.canReuse())
        assertSame(fixture.metrics, fixture.reuse.descriptor)
    }

    @Test
    fun `nonresize requires full work but keeps revision for private words with the same metrics`() {
        val fixture = Fixture()
        fixture.complete()
        val revision = fixture.reuse.revision

        assertFalse(fixture.canReuse(resize = false))
        fixture.complete(reused = false)

        assertEquals(revision, fixture.reuse.revision)
        assertTrue(fixture.canReuse())
    }

    @Test
    fun `changed descriptor misses until complete and replaces the previous descriptor`() {
        val fixture = Fixture()
        fixture.complete()
        val changedMetrics = Any()
        val revision = fixture.reuse.revision

        assertFalse(fixture.canReuse(metrics = changedMetrics))
        fixture.complete(metrics = changedMetrics)

        assertEquals(revision + 1L, fixture.reuse.revision)
        assertSame(changedMetrics, fixture.reuse.descriptor)
        assertTrue(fixture.canReuse(metrics = changedMetrics))
        assertFalse(fixture.canReuse())
    }

    @Test
    fun `unknown descriptor never hits and completion discards known geometry`() {
        val fixture = Fixture()
        fixture.complete()
        val revision = fixture.reuse.revision

        assertFalse(fixture.canReuse(metrics = null))
        fixture.complete(metrics = null)

        assertNull(fixture.reuse.descriptor)
        assertEquals(revision + 1L, fixture.reuse.revision)
        assertFalse(fixture.canReuse())
        assertFalse(fixture.canReuse(metrics = null))
    }

    @Test
    fun `empty words cannot reuse or arm word geometry`() {
        val fixture = Fixture()
        fixture.complete()

        assertFalse(fixture.canReuse(hasWords = false))
        fixture.complete(hasWords = false)

        assertSame(fixture.metrics, fixture.reuse.descriptor)
        assertFalse(fixture.canReuse(hasWords = false))
        assertFalse(fixture.canReuse(hasWords = true))
    }

    @Test
    fun `starting another attempt invalidates partial geometry until completion`() {
        val fixture = Fixture()
        fixture.complete()
        val reused = fixture.canReuse()

        val ticket = fixture.reuse.begin(fixture.model)

        assertTrue(reused)
        assertFalse(fixture.canReuse())
        fixture.reuse.complete(fixture.model, ticket, fixture.metrics, true, reused)
        assertTrue(fixture.canReuse())
    }

    @Test
    fun `revocation is permanent for the same binding despite refresh and recomputation`() {
        val fixture = Fixture()
        fixture.complete()

        fixture.reuse.revoke()
        assertFalse(fixture.canReuse())
        assertFalse(fixture.reuse.ownershipRetained)
        fixture.reuse.bind(fixture.model)
        fixture.complete()

        assertFalse(fixture.canReuse())
        assertFalse(fixture.reuse.ownershipRetained)
        fixture.reuse.bind(fixture.model)
        fixture.complete(reused = true)
        assertFalse(fixture.canReuse())
    }

    @Test
    fun `revocation during an in flight attempt cannot be undone by its completion`() {
        val fixture = Fixture()
        val ticket = fixture.reuse.begin(fixture.model)

        fixture.reuse.revoke()
        fixture.reuse.complete(fixture.model, ticket, fixture.metrics, true, false)

        assertFalse(fixture.canReuse())
        assertNull(fixture.reuse.descriptor)
        fixture.complete()
        assertFalse(fixture.canReuse())
    }

    @Test
    fun `a new independent binding recovers ownership after revocation`() {
        val fixture = Fixture()
        fixture.complete()
        fixture.reuse.revoke()
        val newModel = Any()
        val revision = fixture.reuse.revision

        fixture.reuse.bind(newModel)

        assertTrue(fixture.reuse.ownershipRetained)
        assertNull(fixture.reuse.descriptor)
        assertEquals(revision + 1L, fixture.reuse.revision)
        assertFalse(fixture.canReuse(model = newModel))
        fixture.complete(model = newModel)
        assertTrue(fixture.canReuse(model = newModel))
        assertFalse(fixture.canReuse())
    }

    @Test
    fun `refreshing the same private binding preserves completed geometry and revision`() {
        val fixture = Fixture()
        fixture.complete()
        val revision = fixture.reuse.revision

        fixture.reuse.bind(fixture.model)

        assertTrue(fixture.canReuse())
        assertSame(fixture.metrics, fixture.reuse.descriptor)
        assertEquals(revision, fixture.reuse.revision)
    }

    @Test
    fun `value equal models and descriptors are still distinct identities`() {
        val model = EqualToken("model")
        val metrics = EqualToken("metrics")
        val equalModel = EqualToken("model")
        val equalMetrics = EqualToken("metrics")
        val fixture = Fixture(model, metrics)
        fixture.complete()

        assertEquals(model, equalModel)
        assertEquals(metrics, equalMetrics)
        assertEquals(model.hashCode(), equalModel.hashCode())
        assertEquals(metrics.hashCode(), equalMetrics.hashCode())
        assertFalse(fixture.canReuse(model = equalModel))
        assertFalse(fixture.canReuse(metrics = equalMetrics))

        fixture.reuse.bind(equalModel)
        assertFalse(fixture.canReuse(model = equalModel))
        assertNull(fixture.reuse.descriptor)
        fixture.complete(model = equalModel, metrics = equalMetrics)
        assertTrue(fixture.canReuse(model = equalModel, metrics = equalMetrics))
        assertFalse(fixture.canReuse(model = equalModel, metrics = metrics))
    }

    @Test
    fun `state operations never invoke model or descriptor equals or hashCode`() {
        val fixture = Fixture(IdentityOnly(), IdentityOnly())
        val otherModel = IdentityOnly()
        val otherMetrics = IdentityOnly()
        fixture.complete()

        assertTrue(fixture.canReuse())
        assertFalse(fixture.canReuse(model = otherModel))
        assertFalse(fixture.canReuse(metrics = otherMetrics))
        fixture.reuse.bind(fixture.model)
        fixture.complete(reused = true)
        fixture.reuse.bind(otherModel)
        fixture.complete(model = otherModel, metrics = otherMetrics)
        fixture.reuse.fail(fixture.model)
        assertTrue(fixture.canReuse(model = otherModel, metrics = otherMetrics))
        fixture.reuse.revoke()
        assertFalse(fixture.canReuse(model = otherModel, metrics = otherMetrics))
    }

    @Test
    fun `failed partial work clears geometry and rejects its late completion`() {
        val fixture = Fixture()
        fixture.complete()
        val ticket = fixture.reuse.begin(fixture.model)
        val revision = fixture.reuse.revision

        fixture.reuse.fail(fixture.model)

        assertFalse(fixture.canReuse())
        assertNull(fixture.reuse.descriptor)
        assertEquals(revision + 1L, fixture.reuse.revision)
        fixture.reuse.complete(fixture.model, ticket, fixture.metrics, true, false)
        assertFalse(fixture.canReuse())
        assertNull(fixture.reuse.descriptor)

        fixture.complete()
        assertTrue(fixture.canReuse())
    }

    @Test
    fun `stale outer completion invalidates even a successful reentrant inner attempt`() {
        val fixture = Fixture()
        fixture.complete()
        val outerTicket = fixture.reuse.begin(fixture.model)
        val innerMetrics = Any()
        val innerTicket = fixture.reuse.begin(fixture.model)
        fixture.reuse.complete(fixture.model, innerTicket, innerMetrics, true, false)
        assertTrue(fixture.canReuse(metrics = innerMetrics))
        val innerRevision = fixture.reuse.revision

        fixture.reuse.complete(fixture.model, outerTicket, fixture.metrics, true, false)

        assertFalse(fixture.canReuse(metrics = innerMetrics))
        assertFalse(fixture.canReuse())
        assertNull(fixture.reuse.descriptor)
        assertEquals(innerRevision + 1L, fixture.reuse.revision)
        fixture.complete(metrics = innerMetrics)
        assertTrue(fixture.canReuse(metrics = innerMetrics))
    }

    @Test
    fun `completion from a replaced model cannot invalidate the new model`() {
        val fixture = Fixture()
        val oldTicket = fixture.reuse.begin(fixture.model)
        val newModel = Any()
        val newMetrics = Any()
        fixture.reuse.bind(newModel)
        fixture.complete(model = newModel, metrics = newMetrics)
        val revision = fixture.reuse.revision

        fixture.reuse.complete(fixture.model, oldTicket, fixture.metrics, true, false)

        assertTrue(fixture.canReuse(model = newModel, metrics = newMetrics))
        assertSame(newMetrics, fixture.reuse.descriptor)
        assertEquals(revision, fixture.reuse.revision)
    }

    @Test
    fun `failure from a replaced model cannot invalidate the new model`() {
        val fixture = Fixture()
        fixture.reuse.begin(fixture.model)
        val newModel = Any()
        val newMetrics = Any()
        fixture.reuse.bind(newModel)
        fixture.complete(model = newModel, metrics = newMetrics)
        val revision = fixture.reuse.revision

        fixture.reuse.fail(fixture.model)

        assertTrue(fixture.canReuse(model = newModel, metrics = newMetrics))
        assertSame(newMetrics, fixture.reuse.descriptor)
        assertEquals(revision, fixture.reuse.revision)
    }

    @Test
    fun `begin from a replaced model cannot invalidate the new model or its active ticket`() {
        val fixture = Fixture()
        fixture.complete()
        val newModel = Any()
        val newMetrics = Any()
        fixture.reuse.bind(newModel)
        fixture.complete(model = newModel, metrics = newMetrics)
        val revision = fixture.reuse.revision

        assertEquals(0L, fixture.reuse.begin(fixture.model))
        assertTrue(fixture.canReuse(model = newModel, metrics = newMetrics))
        assertSame(newMetrics, fixture.reuse.descriptor)
        assertEquals(revision, fixture.reuse.revision)

        val newTicket = fixture.reuse.begin(newModel)
        assertEquals(0L, fixture.reuse.begin(fixture.model))
        fixture.reuse.complete(newModel, newTicket, newMetrics, true, true)
        assertTrue(fixture.canReuse(model = newModel, metrics = newMetrics))
        assertEquals(revision, fixture.reuse.revision)
    }

    @Test
    fun `plain rows keep revision stable on resize with unchanged metrics`() {
        val fixture = Fixture()
        fixture.complete(hasWords = false)
        val revision = fixture.reuse.revision

        repeat(3) {
            assertFalse(fixture.canReuse(hasWords = false))
            fixture.complete(hasWords = false, reused = false)
            assertEquals(revision, fixture.reuse.revision)
        }
    }

    @Test
    fun `plain rows change revision for changed or unknown metrics`() {
        val fixture = Fixture()
        fixture.complete(hasWords = false)
        val revision = fixture.reuse.revision
        val changedMetrics = Any()

        fixture.complete(metrics = changedMetrics, hasWords = false)

        assertEquals(revision + 1L, fixture.reuse.revision)
        assertSame(changedMetrics, fixture.reuse.descriptor)
        fixture.complete(metrics = null, hasWords = false)
        assertEquals(revision + 2L, fixture.reuse.revision)
        fixture.complete(metrics = null, hasWords = false)
        assertEquals(revision + 3L, fixture.reuse.revision)
    }

    @Test
    fun `word resize hits and full private recomputation preserve revision with the same metrics`() {
        val fixture = Fixture()
        fixture.complete()
        val revision = fixture.reuse.revision

        repeat(3) {
            val reused = fixture.canReuse()
            assertTrue(reused)
            fixture.complete(reused = reused)
            assertEquals(revision, fixture.reuse.revision)
        }

        repeat(3) {
            fixture.complete(reused = false)
            assertEquals(revision, fixture.reuse.revision)
            assertTrue(fixture.canReuse())
        }
    }

    @Test
    fun `escaped words advance revision on every full recomputation with the same metrics`() {
        val fixture = Fixture()
        fixture.complete()
        fixture.reuse.revoke()
        val revision = fixture.reuse.revision

        repeat(3) { index ->
            assertFalse(fixture.canReuse())
            assertFalse(fixture.reuse.ownershipRetained)
            fixture.complete(reused = false)
            assertEquals(revision + index + 1L, fixture.reuse.revision)
            assertSame(fixture.metrics, fixture.reuse.descriptor)
            assertFalse(fixture.canReuse())
        }
    }

    @Test
    fun `equal total width does not hide changed word distribution or seam metrics`() {
        val firstMetrics = GeometryDescriptor(listOf(10, 30), 12, 9)
        val redistributedMetrics = GeometryDescriptor(listOf(20, 20), 12, 9)
        val tallerMetrics = GeometryDescriptor(listOf(20, 20), 16, 11)

        for (hasWords in listOf(false, true)) {
            val fixture = Fixture(metrics = firstMetrics)
            fixture.complete(hasWords = hasWords)
            var revision = fixture.reuse.revision

            for (changedMetrics in listOf(redistributedMetrics, tallerMetrics)) {
                assertEquals(firstMetrics.wordWidths.sum(), changedMetrics.wordWidths.sum())
                assertFalse(fixture.canReuse(metrics = changedMetrics, hasWords = hasWords))
                fixture.complete(metrics = changedMetrics, hasWords = hasWords)
                assertEquals(revision + 1L, fixture.reuse.revision)
                assertEquals(hasWords, fixture.canReuse(metrics = changedMetrics, hasWords = hasWords))
                revision = fixture.reuse.revision
            }
        }
    }

    private class Fixture(val model: Any = Any(), val metrics: Any = Any()) {
        val reuse = BoundGeometryReuse<Any, Any>().apply { bind(model) }

        fun canReuse(
            model: Any = this.model,
            resize: Boolean = true,
            hasWords: Boolean = true,
            metrics: Any? = this.metrics,
        ): Boolean = reuse.canReuse(model, resize, hasWords, metrics)

        fun complete(
            model: Any = this.model,
            metrics: Any? = this.metrics,
            hasWords: Boolean = true,
            reused: Boolean = false,
        ) {
            reuse.complete(model, reuse.begin(model), metrics, hasWords, reused)
        }
    }

    private data class EqualToken(val value: String)

    private class IdentityOnly {
        override fun equals(other: Any?): Boolean = error("Equality must not participate in reuse")
        override fun hashCode(): Int = error("Hash codes must not participate in reuse")
    }

    private data class GeometryDescriptor(
        val wordWidths: List<Int>,
        val seamHeight: Int,
        val baseline: Int,
    )
}
