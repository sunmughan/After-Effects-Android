package com.codeair.effectcraft.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable mapping from canonical EffectCraft footage item IDs to offline media
 * SHA-256 IDs. This lets the UI prevent deletion of assets still referenced by
 * the currently cached canonical project.
 *
 * Instances targeting the same filesystem file share a process-local lock.
 * Lock identity is canonicalized (resolving symlink aliases), so workers
 * opened through different paths cannot lose read/modify/write updates.
 */
class OfflineAssetReferences(private val file: File) {
    private val lock = lockFor(file)

    fun assetFor(itemId: Long): String? = synchronized(lock) {
        require(itemId > 0) { "Footage item ID must be positive" }
        read().getProperty(itemId.toString())?.also(::validateHash)
    }

    /**
     * Returns every referenced SHA-256 ID. Corrupt reference metadata is
     * fail-closed: silently dropping an invalid mapping could make a live
     * asset appear unreferenced and eligible for garbage collection.
     */
    fun referencedAssetIds(): Set<String> = synchronized(lock) {
        val properties = read()
        properties.stringPropertyNames().map { key ->
            val itemId = key.toLongOrNull()
                ?: throw IllegalStateException("Invalid footage item ID in offline references: $key")
            require(itemId > 0) { "Footage item ID in offline references must be positive" }
            val hash = properties.getProperty(key)
                ?: throw IllegalStateException("Missing asset hash for footage item $itemId")
            validateHash(hash)
            hash
        }.toSet()
    }

    fun isReferenced(sha256: String): Boolean = synchronized(lock) {
        validateHash(sha256)
        sha256 in referencedAssetIds()
    }

    /** Footage item ids currently mapped to [sha256]; used by asset replacement. */
    fun itemIdsFor(sha256: String): Set<Long> = synchronized(lock) {
        validateHash(sha256)
        val properties = read()
        properties.stringPropertyNames().mapNotNull { key ->
            key.toLongOrNull()?.takeIf { properties.getProperty(key) == sha256 }
        }.toSet()
    }

    @Throws(IOException::class)
    fun setReference(itemId: Long, sha256: String) = synchronized(lock) {
        require(itemId > 0) { "Footage item ID must be positive" }
        validateHash(sha256)
        val properties = read()
        properties.setProperty(itemId.toString(), sha256)
        write(properties)
    }

    @Throws(IOException::class)
    fun clear() = synchronized(lock) {
        write(Properties())
    }

    @Throws(IOException::class)
    fun removeReference(itemId: Long): Boolean = synchronized(lock) {
        require(itemId > 0) { "Footage item ID must be positive" }
        val properties = read()
        if (properties.remove(itemId.toString()) == null) return@synchronized false
        write(properties)
        true
    }

    private fun read(): Properties = Properties().apply {
        if (file.isFile) FileInputStream(file).buffered().use(::load)
    }

    private fun write(properties: Properties) {
        val parent = file.parentFile ?: throw IOException("Asset reference file has no parent")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create asset reference directory")
        if (!parent.isDirectory) throw IOException("Asset reference parent is not a directory")
        // Unique staging avoids collisions between independent instances; the
        // shared canonical-file lock serializes their read/modify/write.
        val temporary = File(parent, "${file.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                properties.store(output, "EffectCraft offline asset references v1")
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Only unsupported atomic moves permit fallback. Permission,
                // storage and other I/O failures must propagate unchanged.
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun validateHash(hash: String) {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Asset ID must be a lowercase SHA-256 digest" }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()

        private fun lockFor(path: File): Any {
            // canonicalFile resolves symlink aliases and normalizes path
            // segments. If canonicalization fails (e.g. inaccessible parent),
            // retain a deterministic absolute normalized fallback; actual I/O
            // will still report the underlying permission/storage failure.
            val key = runCatching { path.canonicalFile.toPath().normalize().toString() }
                .getOrElse { path.absoluteFile.toPath().normalize().toString() }
            return locks.computeIfAbsent(key) { Any() }
        }
    }
}
