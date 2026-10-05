package com.juren233.hyperlyricsenhanced.lyric.view.line

/**
 * One private, freshly bound model owns its geometry. No words or arrays are cached here.
 * Exposing the mutable model permanently revokes reuse until a different model is bound.
 * Descriptor identity is safe only when the caller reuses an immutable, fully matched descriptor.
 */
internal class BoundGeometryReuse<Model : Any, Descriptor : Any> {
    private var model: Model? = null
    private var owned = false
    private var valid = false
    private var attempt = 0L

    var descriptor: Descriptor? = null
        private set
    var revision: Long = 0L
        private set
    val ownershipRetained: Boolean get() = owned

    fun bind(value: Model) {
        // A harmless refresh must never re-trust an already exposed model.
        if (model === value) return
        model = value
        owned = true
        valid = false
        descriptor = null
        attempt++
        revision++
    }

    fun revoke() {
        owned = false
        valid = false
        attempt++
    }

    fun canReuse(value: Model, resize: Boolean, hasWords: Boolean, metrics: Descriptor?): Boolean =
        resize && hasWords && owned && valid && model === value &&
            metrics != null && descriptor === metrics

    fun begin(value: Model): Long {
        if (model !== value) return 0L
        valid = false
        return ++attempt
    }

    fun complete(value: Model, ticket: Long, metrics: Descriptor?, hasWords: Boolean, reused: Boolean) {
        if (model !== value) return
        if (attempt != ticket) {
            // Nested work may have completed before the outer call wrote its last word.
            valid = false
            descriptor = null
            revision++
            return
        }
        // Owned words are unchanged since binding. Repeating the same known measurement
        // (including a forced/configuration refresh) must not introduce a new seam rebuild.
        // Exposed words may have been edited; unknown measurement behavior is also a miss.
        if (metrics == null || descriptor !== metrics || (hasWords && !reused && !owned)) revision++
        descriptor = metrics
        valid = owned && hasWords && metrics != null
    }

    fun fail(value: Model) {
        if (model !== value) return
        valid = false
        descriptor = null
        attempt++
        revision++
    }
}
