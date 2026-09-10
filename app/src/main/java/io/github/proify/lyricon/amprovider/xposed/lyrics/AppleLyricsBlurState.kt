/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import android.view.View
import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Narrow component that owns the Apple lyrics blur runtime state.
 *
 * [AppleLyricsBlurHooks] keeps Hook discovery/installation/event forwarding and holds one
 * instance; the apply/clear/release paths in AppleLyricsBlurEngine and the reflection helper in
 * AppleLyricsRecyclerUtils use the named operations here instead of reaching into the facade.
 *
 * Container kinds are preserved verbatim: a weak-key synchronized runtime-state map, a weak
 * synchronized set of blurred views, a weak adapter→recycler map, an identity map of
 * classifications, a concurrent method cache and a weak-key HyperOS method cache. Object
 * identity and weak-key semantics are unchanged, as is the rule that every critical section
 * runs under the same monitor the inline `synchronized(map)` blocks used.
 *
 * Not exposed: the collections themselves. Callers get one per-view scene (the mutable
 * working set the blur algorithm is built around) or an immutable copy, never a writable map.
 */
internal class AppleLyricsBlurState {

    // ---- runtime states (weak keys, synchronized) ----

    private val runtimeStates = Collections.synchronizedMap(
        WeakHashMap<View, AppleLyricsBlurRuntimeState>()
    )

    /** Existing scene for [view], or null; read under the map monitor. */
    fun runtimeStateOrNull(view: View): AppleLyricsBlurRuntimeState? =
        synchronized(runtimeStates) { runtimeStates[view] }

    /**
     * Runs [block] under the runtime-state monitor with the scene for [view], creating it on
     * demand. The scene object is the per-view working set the algorithm mutates; the map
     * itself stays private, and no host call is made inside this critical section by the owner.
     */
    fun <T> withRuntimeState(view: View, block: (AppleLyricsBlurRuntimeState) -> T): T =
        synchronized(runtimeStates) {
            block(runtimeStates.getOrPut(view) { AppleLyricsBlurRuntimeState() })
        }

    /**
     * Like [withRuntimeState] but never creates a scene: returns null when [view] has none,
     * so a caller can preserve its previous no-state short-circuit inside one critical section.
     */
    fun <T> withExistingRuntimeState(view: View, block: (AppleLyricsBlurRuntimeState) -> T): T? =
        synchronized(runtimeStates) {
            val state = runtimeStates[view] ?: return null
            block(state)
        }

    /** True when a scene is already registered for [view]. */
    fun containsRuntimeState(view: View): Boolean =
        synchronized(runtimeStates) { runtimeStates.containsKey(view) }

    /** First registered scene key matching [predicate], or null. */
    fun firstRuntimeStateKey(predicate: (View) -> Boolean): View? =
        synchronized(runtimeStates) { runtimeStates.keys.firstOrNull(predicate) }

    /** Detaches the scene for [view]; returns the previous scene if any. */
    fun removeRuntimeState(view: View): AppleLyricsBlurRuntimeState? =
        synchronized(runtimeStates) { runtimeStates.remove(view) }

    /** Detaches [view]'s scene only when it is still the [expected] instance. */
    fun removeRuntimeStateIf(view: View, expected: AppleLyricsBlurRuntimeState): Boolean =
        synchronized(runtimeStates) {
            if (runtimeStates[view] !== expected) return false
            runtimeStates.remove(view)
            true
        }

    // ---- blurred views (weak keys, synchronized) ----

    private val blurredViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    )

    fun markBlurred(view: View) {
        blurredViews.add(view)
    }

    fun unmarkBlurred(view: View) {
        blurredViews.remove(view)
    }

    /** Atomically takes every currently-blurred view, clearing the set. */
    fun takeBlurredViews(): List<View> =
        synchronized(blurredViews) { blurredViews.toList().also { blurredViews.clear() } }

    // ---- adapter → recycler weak map ----

    private val recyclerViewsByAdapter = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<View>>()
    )

    fun rememberRecycler(adapter: Any, recyclerView: View) {
        recyclerViewsByAdapter[adapter] = WeakReference(recyclerView)
    }

    fun recyclerFor(adapter: Any): View? =
        synchronized(recyclerViewsByAdapter) { recyclerViewsByAdapter[adapter]?.get() }

    // ---- lyrics RecyclerView classification (identity map) ----

    private val recyclerViewClassifications = WeakIdentityMap<View, Boolean>()

    fun classificationOf(view: View): Boolean? = recyclerViewClassifications[view]

    fun rememberClassification(view: View, isLyrics: Boolean) {
        recyclerViewClassifications[view] = isLyrics
    }

    fun forgetClassification(view: View) {
        recyclerViewClassifications.remove(view)
    }

    // ---- resolved reflection helpers ----

    private val childAdapterPositionMethods = ConcurrentHashMap<Class<*>, Method>()

    fun childAdapterPositionMethod(recyclerViewClass: Class<*>): Method? =
        childAdapterPositionMethods[recyclerViewClass]

    fun rememberChildAdapterPositionMethod(recyclerViewClass: Class<*>, method: Method) {
        childAdapterPositionMethods[recyclerViewClass] = method
    }

    private val hyperOsMethods = Collections.synchronizedMap(
        WeakHashMap<Class<*>, AppleLyricsHyperOsMethods>()
    )

    /** Resolves (and caches) the HyperOS self-blur methods for [viewClass]. */
    fun hyperOsMethodsFor(viewClass: Class<*>, resolve: () -> AppleLyricsHyperOsMethods): AppleLyricsHyperOsMethods =
        synchronized(hyperOsMethods) {
            hyperOsMethods.getOrPut(viewClass, resolve)
        }
}
