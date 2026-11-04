package com.codeair.effectcraft.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Durable mapping from canonical EffectCraft footage item IDs to offline media
 * SHA-256 IDs. This lets the UI prevent deletion of assets still referenced by
 * the currently cached canonical project.
 */
class OfflineAssetReferences(private val file: File) {
    @Synchronized
    fun assetFor(itemId: Long): String? {
        require(itemId > 0) { "Footage item ID must be positive" }
        return read().getProperty(itemId.toString())?.also(::validateHash)
    }

    @Synchronized
    fun referencedAssetIds(): Set<String> {
        val properties = read()
        return properties.stringPropertyNames().mapNotNull { key ->
            properties.getProperty(key)?.let { hash -> runCatching { validateHash(hash); hash }.getOrNull() }
        }.toSet()
    }

    @Synchronized
    fun isReferenced(sha256: String): Boolean {
        validateHash(sha256)
        return sha256 in referencedAssetIds()
    }

    /** Footage item ids currently mapped to [sha256]; used by asset replacement. */
    @Synchronized
    fun itemIdsFor(sha256: String): Set<Long> {
        validateHash(sha256)
        val properties = read()
        return properties.stringPropertyNames().mapNotNull { key ->
            key.toLongOrNull()?.takeIf { properties.getProperty(key) == sha256 }
        }.toSet()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setReference(itemId: Long, sha256: String) {
        require(itemId > 0) { "Footage item ID must be positive" }
        validateHash(sha256)
        val properties = read()
        properties.setProperty(itemId.toString(), sha256)
        write(properties)
    }

    @Synchronized
    @Throws(IOException::class)
    fun clear() {
        write(Properties())
    }

    @Synchronized
    @Throws(IOException::class)
    fun removeReference(itemId: Long): Boolean {
        require(itemId > 0) { "Footage item ID must be positive" }
        val properties = read()
        if (properties.remove(itemId.toString()) == null) return false
        write(properties)
        return true
    }

    private fun read(): Properties = Properties().apply {
        if (file.isFile) file.inputStream().buffered().use(::load)
    }

    private fun write(properties: Properties) {
        val parent = file.parentFile ?: throw IOException("Asset reference file has no parent")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create asset reference directory")
        val temporary = File(parent, "${file.name}.tmp")
        temporary.outputStream().buffered().use { properties.store(it, "EffectCraft offline asset references v1") }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun validateHash(hash: String) {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Asset ID must be a lowercase SHA-256 digest" }
    }
}
