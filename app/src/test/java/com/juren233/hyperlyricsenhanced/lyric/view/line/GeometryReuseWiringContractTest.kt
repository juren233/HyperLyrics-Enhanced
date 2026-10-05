/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.lyric.view.line

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE CONTRACT tests only: guard the Android call-site boundaries without loading a View
 * or invoking Paint. These assertions do not demonstrate Android measurement/rendering behavior.
 */
class GeometryReuseWiringContractTest {
    private val views = listOf("LyricLineView.kt", "SpaceGateLyricLineView.kt")

    @Test
    fun `public mutable model getter revokes ownership before returning in both views`() {
        views.forEach { file ->
            val code = source(file)
            val getter = block(code.substringAfter("val model: LyricModel"), "get()")
            assertEquals("$file mutable model escape must revoke before return",
                "geometryReuse.revoke() return _model", compact(getter))
            assertTrue(code.contains("private var _model: LyricModel"))
        }
    }

    @Test
    fun `only positive size callbacks explicitly authorize resize reuse`() {
        views.forEach { file ->
            val code = source(file)
            val resize = block(code, "override fun onSizeChanged(")
            val positiveSize = block(resize, "if (w > 0 && h > 0)")
            val optIn = Regex("allowResizeReuse\\s*=\\s*true")
            assertEquals("$file must have exactly one reuse opt-in", 1, optIn.findAll(code).count())
            assertEquals(1, optIn.findAll(positiveSize).count())
            assertTrue(compact(positiveSize).contains(
                "refreshSizes(GeometryReason.RESIZE, allowResizeReuse = true)"))
            assertTrue(compact(code).contains(
                "private fun refreshSizes(reason: Int, allowResizeReuse: Boolean = false)"))
            assertTrue(code.contains("fun refreshSizes() = refreshSizes(GeometryReason.OTHER)"))
        }
    }

    @Test
    fun `whole width remains unconditional while only word loop is guarded`() {
        val body = block(source("model/LyricModel.kt"), "private fun updateSizesInternal(")
        val whole = "width = preparedTextWidth ?: measureLyricTextWidth(paint, text, typefaceSelector)"
        assertBefore(body, whole, "if (!reused)")
        val words = block(body, "if (!reused)")
        assertTrue(words.contains("words.forEach { word ->"))
        assertTrue(words.contains("word.updateSizes(previous, paint, typefaceSelector)"))
        assertTrue(words.contains("previous = word"))
        assertFalse(words.contains("width = preparedTextWidth"))
        assertFalse(words.contains("owner?.complete"))
        assertBefore(body, "if (!reused)", "owner?.complete(this, ticket, metrics, words.isNotEmpty(), reused)")
        assertTrue(body.contains("if (failed) owner?.fail(this)"))
    }

    @Test
    fun `measurement descriptor explicitly checks both hyphen edits beyond native equality`() {
        // The prepared whole-width path must not reuse its old result after the word key misses.
        listOf("WordGeometryMetrics.kt", "IncomingLyricWidth.kt").forEach { file ->
            val metrics = source(file)
            assertTrue(metrics.contains("private val paint = Paint(paint)"))
            assertTrue(metrics.contains("paint.equalsForTextMeasurement(other)"))
            assertTrue(metrics.contains("paint.startHyphenEdit == other.startHyphenEdit"))
            assertTrue(metrics.contains("paint.endHyphenEdit == other.endHyphenEdit"))
        }
    }

    @Test
    fun `plain independent rows and completely empty rows bypass owned snapshot work`() {
        val refresh = block(source("LyricLineView.kt"), "private fun refreshSizes(")
        val plain = block(refresh, "if (model.words.isEmpty())")
        assertTrue(plain.contains("model.updateSizesDiagnosed("))
        assertFalse(plain.contains("updateOwnedSizes("))
        val owned = block(source("model/LyricModel.kt"), "internal fun updateOwnedSizes(")
        val empty = block(owned, "if (text.isEmpty() && words.isEmpty())")
        assertTrue(empty.contains("updateSizesDiagnosed(paint, selector, preparedTextWidth, diagnosticReason)"))
        assertFalse(empty.contains("updateSizesInternal("))
        assertTrue(source("model/LyricModel.kt").contains(
            "owner?.canReuse(this, allowResizeReuse, words.isNotEmpty(), metrics)"))
    }

    @Test
    fun `space gate plain rows avoid snapshots until a seam is active and activate with a fresh key`() {
        val code = source("SpaceGateLyricLineView.kt")
        val refresh = block(code, "private fun refreshSizes(")
        val owned = block(refresh, "if (model.words.isNotEmpty() || (spaceGateEnabled && siblingView != null))")
        assertTrue(owned.contains("model.updateOwnedSizes("))
        val plain = block(refresh, "else")
        assertBefore(plain, "seamLayoutModel = null", "model.updateSizesDiagnosed(")
        assertFalse(plain.contains("updateOwnedSizes("))
        assertFalse(plain.contains("clearSeamLayout()"))
        val sibling = block(code.substringAfter("var siblingView: SpaceGateLyricLineView? = null"), "set(value)")
        assertTrue(sibling.contains("if (field === value) return"))
        assertTrue(sibling.contains("if (_model.words.isEmpty()) seamLayoutModel = null"))
        val gate = block(code.substringAfter("var spaceGateEnabled = true"), "set(value)")
        assertTrue(gate.contains("if (value && _model.words.isEmpty()) seamLayoutModel = null"))
        assertFalse(block(code, "private fun ensureSeamLayout()").contains("WordGeometryMetrics("))
    }

    @Test
    fun `preview color width notification and renderer relayout work remain outside reuse gate`() {
        views.forEach { file ->
            val code = source(file)
            val resize = block(code, "override fun onSizeChanged(")
            assertBefore(resize, "refreshSizes(GeometryReason.RESIZE", "updateColorsIfReady()")
            assertTrue(block(resize, "if (w != oldw && w > 0)").contains("onAvailableWidthChanged()"))
            assertTrue(block(code, "override fun onLayout(").contains("if (changed) relayout()"))
            assertTrue(block(code, "fun relayout()").contains("syncRenderer.updateLayout(_model, lineState,"))
        }
        val space = source("SpaceGateLyricLineView.kt")
        val refresh = block(space, "private fun refreshSizes(")
        assertBefore(refresh, "model.updateOwnedSizes(", "rightPreview.configure(")
        assertFalse(refresh.contains("if (allowResizeReuse)"))
        assertBefore(block(space, "fun relayout()"), "ensureSeamLayout()", "syncRenderer.updateLayout(")
    }

    @Test
    fun `public incoming snapshot and right preview entrypoints retain full measurement paths`() {
        val model = compact(source("model/LyricModel.kt"))
        val publicApi = model.substringAfter("fun updateSizes(").substringBefore("internal fun updateSizesDiagnosed(")
        val diagnosedApi = model.substringAfter("internal fun updateSizesDiagnosed(").substringBefore("internal fun updateOwnedSizes(")
        assertTrue(publicApi.contains(
            ") = updateSizesDiagnosed(paint, typefaceSelector, preparedTextWidth, 0)"))
        assertFalse(publicApi.contains("owner"))
        assertFalse(publicApi.contains("allowResizeReuse"))
        assertTrue(diagnosedApi.contains(
            ") = updateSizesInternal(paint, typefaceSelector, preparedTextWidth, diagnosticReason)"))
        assertTrue(model.contains("owner: BoundGeometryReuse<LyricModel, WordGeometryMetrics>? = null"))
        assertTrue(model.contains("allowResizeReuse: Boolean = false"))
        val role = block(source("LyricLineView.kt"), "internal fun layoutRoleSnapshot(")
        val promotion = block(source("SpaceGateLyricLineView.kt"), "internal fun promotionSnapshot(")
        listOf(role to "ROLE_SNAPSHOT", promotion to "PROMOTION_SNAPSHOT").forEach { (body, reason) ->
            assertTrue(body.contains("createModel()"))
            assertTrue(body.contains("updateSizesDiagnosed(textPaint, currentTypefaceSelector, diagnosticReason = GeometryReason.$reason)"))
            assertFalse(body.contains("updateOwnedSizes("))
        }
        val preview = source("SpaceGateRightPreviewRenderer.kt")
        assertTrue(preview.contains("model.updateSizes(paint, fonts)"))
        assertFalse(preview.contains("updateOwnedSizes("))
    }

    @Test
    fun `seam rebuild key uses model identity and geometry revision before renderer capacity`() {
        val space = source("SpaceGateLyricLineView.kt")
        assertFalse(space.contains("seamLayoutKey"))
        val seam = block(space, "private fun ensureSeamLayout()")
        assertTrue(seam.contains("val revision = geometryReuse.revision"))
        assertTrue(seam.contains("val textSizeBits = textPaint.textSize.toBits()"))
        assertTrue(seam.contains("seamLayoutModel !== model || seamLayoutRevision != revision"))
        assertTrue(seam.contains("seamLayoutWidthBits != widthBits || seamLayoutTextSizeBits != textSizeBits"))
        assertBefore(seam, "scrollRenderer.seamLayout = cachedSeamLayout", "if (layoutChanged || seamChanged) onAvailableWidthChanged()")
        assertBefore(seam, "syncRenderer.seamX = seam.toFloat()", "if (layoutChanged || seamChanged) onAvailableWidthChanged()")
        val reset = block(space, "fun reset()")
        assertTrue(reset.contains("seamLayoutModel = null"))
        assertFalse("Reset must retain pre-draw renderer publication semantics", reset.contains("clearSeamLayout()"))
    }

    // Same module/project-relative convention as RichLyricHugMeasureContractTest; walking
    // ancestors also supports a test runner started in a nested build directory.
    private fun source(name: String): String {
        val relative = "src/main/java/com/juren233/hyperlyricsenhanced/lyric/view/line/$name"
        val candidates = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .flatMap { sequenceOf(File(it, relative), File(it, "app/$relative")) }
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Cannot locate source contract input: $name")
    }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun assertBefore(source: String, first: String, second: String) {
        val a = source.indexOf(first)
        val b = source.indexOf(second)
        assertTrue("Expected '$first' before '$second'", a >= 0 && b > a)
    }

    private fun block(source: String, marker: String): String {
        val start = source.indexOf(marker)
        check(start >= 0) { "Missing source contract marker: $marker" }
        val opening = source.indexOf('{', start + marker.length)
        check(opening >= 0) { "Missing block for: $marker" }
        var depth = 1
        for (index in opening + 1 until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(opening + 1, index)
            }
        }
        error("Unclosed source contract block: $marker")
    }
}
