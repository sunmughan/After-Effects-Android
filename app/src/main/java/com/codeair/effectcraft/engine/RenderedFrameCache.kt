package com.codeair.effectcraft.engine

import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Small process-local LRU for canonical CPU-rendered RGBA frames.
 *
 * Cached values are immutable copies and the byte budget is enforced independently
 * of entry count. Oversized frames are rendered normally but not retained.
 */
internal object RenderedFrameCache {
    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val MAX_ENTRIES = 12
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var retainedBytes = 0

    @Synchronized
    fun get(key: String): ByteArray? = entries[key]?.copyOf()

    /**
     * Serializes renderer calls and coalesces identical in-flight requests. The
     * monitor intentionally spans render(): upstream MediaPool is shared and the
     * per-frame Renderer API has no cancellation primitive.
     */
    @Synchronized
    fun getOrRender(key: String, render: () -> ByteArray?): ByteArray? {
        entries[key]?.let { return it.copyOf() }
        val frame = render()?.takeIf { it.size >= 8 } ?: return null
        put(key, frame)
        return frame.copyOf()
    }

    @Synchronized
    fun put(key: String, frame: ByteArray) {
        if (frame.isEmpty() || frame.size > MAX_BYTES) return
        entries.remove(key)?.let { retainedBytes -= it.size }
        entries[key] = frame.copyOf()
        retainedBytes += frame.size
        val iterator = entries.entries.iterator()
        while ((retainedBytes > MAX_BYTES || entries.size > MAX_ENTRIES) && iterator.hasNext()) {
            retainedBytes -= iterator.next().value.size
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        retainedBytes = 0
    }

    /**
     * Drops retained frames for one immutable project revision. Preview edits
     * otherwise leave unreachable old-revision frames in the process LRU until
     * unrelated renders evict them. The caller must pass the full SHA-256.
     */
    @Synchronized
    internal fun removeProjectDigest(projectDigest: String): Int {
        require(projectDigest.matches(Regex("[0-9a-f]{64}"))) {
            "Project digest must be lowercase SHA-256"
        }
        val prefix = "$projectDigest:"
        var removed = 0
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) {
                retainedBytes -= entry.value.size
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    @Synchronized
    internal fun stats(): Pair<Int, Int> = entries.size to retainedBytes

    internal fun projectDigest(projectJson: String): String = MessageDigest.getInstance("SHA-256")
        .digest(projectJson.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    internal fun keyFromDigest(projectDigest: String, compositionId: Long, frame: Long, maxSide: Int): String {
        require(projectDigest.matches(Regex("[0-9a-f]{64}"))) { "Project digest must be lowercase SHA-256" }
        require(compositionId >= 0 && frame >= 0 && maxSide >= 0) { "Frame cache coordinates must be non-negative" }
        return "$projectDigest:$compositionId:$frame:$maxSide"
    }

    internal fun key(projectJson: String, compositionId: Long, frame: Long, maxSide: Int): String =
        keyFromDigest(projectDigest(projectJson), compositionId, frame, maxSide)
}
