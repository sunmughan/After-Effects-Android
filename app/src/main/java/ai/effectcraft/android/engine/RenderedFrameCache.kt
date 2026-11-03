package ai.effectcraft.android.engine

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

    @Synchronized
    internal fun stats(): Pair<Int, Int> = entries.size to retainedBytes

    internal fun key(projectJson: String, compositionId: Long, frame: Long, maxSide: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(projectJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "$digest:$compositionId:$frame:$maxSide"
    }
}
