package com.juren233.hyperlyricsenhanced.root.salt

import android.app.Application
import android.os.Looper
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal data class SaltPlayerCurrentTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal data class SaltPlayerNextTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal data class SaltPlayerNextTrackResult(
    val decoded: Boolean,
    val next: SaltPlayerNextTrack?,
    val detail: String,
)

internal class SaltPlayerNextTrackResolver private constructor(
    private val stateHolders: List<StateHolder>,
    private val profile: SaltPlayerNextTrackProfile,
) {
    private val stateAccessors = ConcurrentHashMap<Class<*>, StateAccessors>()
    private val itemAccessors = ConcurrentHashMap<Class<*>, ItemAccessors>()

    fun resolve(current: SaltPlayerCurrentTrack): SaltPlayerNextTrackResult {
        check(Looper.myLooper() == Looper.getMainLooper())
        val state = stateHolders.asSequence()
            .mapNotNull { holder ->
                val flow = runCatching { holder.field.get(null) }.getOrNull()
                    ?: return@mapNotNull null
                runCatching { holder.getValue.invoke(flow) }.getOrNull()
            }
            .firstOrNull { StateAccessors.supports(it.javaClass) }
            ?: return SaltPlayerNextTrackResult(false, null, "queue_state_unavailable_or_invalid")
        val accessors = stateAccessors.computeIfAbsent(state.javaClass, StateAccessors::create)
        val snapshot = accessors.decode(state, current, profile, itemAccessors)
            ?: return SaltPlayerNextTrackResult(false, null, "queue_state_decode_failed")
        val queue = if (snapshot.random) snapshot.randomQueue else snapshot.normalQueue
        if (queue.isEmpty()) return SaltPlayerNextTrackResult(true, null, "queue_empty")
        val configuredIndex = if (snapshot.random) snapshot.randomIndex else snapshot.normalIndex
        val currentIndex = queue.indexOfFirst { it.id == current.id }.takeIf { it >= 0 } ?: configuredIndex
        if (currentIndex !in queue.indices) {
            return SaltPlayerNextTrackResult(true, null, "current_not_in_queue")
        }
        if (snapshot.random && queue.size > 1 && currentIndex == queue.lastIndex) {
            return SaltPlayerNextTrackResult(true, null, "random_tail")
        }
        val next = queue[(currentIndex + 1) % queue.size]
        return SaltPlayerNextTrackResult(
            decoded = true,
            next = next,
            detail = "random=${snapshot.random}, normal=${snapshot.normalQueue.size}, " +
                "randomQueue=${snapshot.randomQueue.size}, next=${next.id}",
        )
    }

    private data class QueueSnapshot(
        val random: Boolean,
        val normalQueue: List<SaltPlayerNextTrack>,
        val normalIndex: Int,
        val randomQueue: List<SaltPlayerNextTrack>,
        val randomIndex: Int,
    )

    private data class StateHolder(
        val field: Field,
        val getValue: Method,
    )

    private data class StateAccessors(
        val mode: Field,
        val normalQueue: Field,
        val normalIndex: Field,
        val randomQueue: Field,
        val randomIndex: Field,
    ) {
        fun decode(
            state: Any,
            current: SaltPlayerCurrentTrack,
            profile: SaltPlayerNextTrackProfile,
            itemAccessors: ConcurrentHashMap<Class<*>, ItemAccessors>,
        ): QueueSnapshot? {
            fun decodeQueue(value: Any?): List<SaltPlayerNextTrack>? {
                val queue = value as? List<*> ?: return null
                return queue.mapNotNull { item ->
                    item ?: return@mapNotNull null
                    itemAccessors.computeIfAbsent(item.javaClass) {
                        ItemAccessors.create(it, item, profile)
                    }.decode(item)
                }
            }
            val modeName = (mode.get(state) as? Enum<*>)?.name ?: return null
            val normal = decodeQueue(normalQueue.get(state)) ?: return null
            val random = decodeQueue(randomQueue.get(state)) ?: return null
            val normalIndex = (normalIndex.get(state) as? Number)?.toInt() ?: return null
            val randomIndex = (randomIndex.get(state) as? Number)?.toInt() ?: return null
            val randomMode = modeName.equals("Random", ignoreCase = true)
            if (!randomMode && !modeName.equals("Circle", true) &&
                !modeName.equals("CircleEnd", true) && !modeName.equals("RepeatOne", true)
            ) return null
            if (modeName.equals("RepeatOne", true)) {
                return QueueSnapshot(false, listOf(current.toNext()), 0, random, randomIndex)
            }
            return QueueSnapshot(randomMode, normal, normalIndex, random, randomIndex)
        }

        companion object {
            fun supports(clazz: Class<*>): Boolean {
                val fields = instanceFields(clazz)
                return fields.count { it.type.isEnum } == 1 &&
                    fields.count { List::class.java.isAssignableFrom(it.type) } >= 2 &&
                    fields.count { it.type == Int::class.javaPrimitiveType } >= 2
            }

            fun create(clazz: Class<*>): StateAccessors {
                val fields = instanceFields(clazz)
                val mode = fields.single { it.type.isEnum }
                val queues = fields.filter { List::class.java.isAssignableFrom(it.type) }
                val indexes = fields.filter { it.type == Int::class.javaPrimitiveType }
                require(queues.size >= 2 && indexes.size >= 2)
                fun pair(queue: Field, fallback: Int): Field = indexes.firstOrNull {
                    fields.indexOf(it) > fields.indexOf(queue)
                } ?: indexes[fallback]
                return StateAccessors(mode, queues[0], pair(queues[0], 0), queues[1], pair(queues[1], 1))
            }
        }
    }

    private data class ItemAccessors(
        val songField: Field?,
        val id: Method,
        val title: Method,
        val artist: Method,
        val album: Method,
        val duration: Method,
    ) {
        fun decode(item: Any): SaltPlayerNextTrack? {
            val song = songField?.get(item) ?: item
            return SaltPlayerNextTrack(
                id = id.invoke(song) as? String ?: return null,
                title = title.invoke(song) as? String ?: return null,
                artist = artist.invoke(song) as? String ?: "",
                album = album.invoke(song) as? String ?: "",
                durationMs = (duration.invoke(song) as? Number)?.toLong() ?: -1L,
            )
        }

        companion object {
            fun create(itemClass: Class<*>, sample: Any, profile: SaltPlayerNextTrackProfile): ItemAccessors {
                fun hasContract(clazz: Class<*>) = listOf("getId", "getTitle", "getArtist", "getAlbum", "getDuration")
                    .all { name -> clazz.methods.any { it.name == name && it.parameterCount == 0 } }
                val field = instanceFields(itemClass).firstOrNull { f ->
                    val value = runCatching { f.get(sample) }.getOrNull() ?: return@firstOrNull false
                    hasContract(value.javaClass)
                }
                val songClass = field?.get(sample)?.javaClass ?: itemClass.takeIf(::hasContract)
                    ?: error("Salt Player queue item has no Song contract: ${itemClass.name}")
                fun method(name: String) = songClass.getMethod(name).apply { isAccessible = true }
                return ItemAccessors(field, method("getId"), method("getTitle"), method("getArtist"), method("getAlbum"), method("getDuration"))
            }
        }
    }

    companion object {
        fun create(application: Application, profile: SaltPlayerNextTrackProfile, target: OfficialProviderMethodTarget): SaltPlayerNextTrackResolver {
            check(Looper.myLooper() == Looper.getMainLooper())
            val controller = Class.forName(target.className, true, application.classLoader)
            val holders = controller.declaredFields.mapNotNull { field ->
                if (!Modifier.isStatic(field.modifiers)) return@mapNotNull null
                val getValue = stateValueMethod(field.type) ?: return@mapNotNull null
                field.isAccessible = true
                getValue.isAccessible = true
                StateHolder(field, getValue)
            }
            require(holders.isNotEmpty()) { "Salt Player static getValue state holders not found" }
            return SaltPlayerNextTrackResolver(holders, profile)
        }

        internal fun hasStateHolderContract(clazz: Class<*>): Boolean =
            stateValueMethod(clazz) != null

        private fun stateValueMethod(clazz: Class<*>): Method? =
            clazz.methods.singleOrNull { method ->
                method.name == "getValue" &&
                    method.parameterCount == 0 &&
                    !method.returnType.isPrimitive
            }
    }
}

private fun instanceFields(clazz: Class<*>): List<Field> = generateSequence(clazz) { it.superclass }
    .flatMap { it.declaredFields.asSequence() }
    .filterNot { Modifier.isStatic(it.modifiers) }
    .onEach { it.isAccessible = true }
    .toList()

private fun SaltPlayerCurrentTrack.toNext() = SaltPlayerNextTrack(id, title, artist, album, durationMs)
