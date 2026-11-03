package ai.effectcraft.android.data

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Content-addressed, app-private media library for offline project assets.
 *
 * Import is streaming and bounded. The caller retains ownership of [input]; its
 * bytes are consumed but the stream is not closed. Asset payloads are immutable
 * and addressed by SHA-256, so identical media is stored only once. The index is
 * atomically replaced after the payload has been safely written.
 */
class OfflineAssetStore(
    private val root: File,
    private val maxAssetBytes: Long = DEFAULT_MAX_ASSET_BYTES
) {
    data class Asset(
        val sha256: String,
        val displayName: String,
        val mimeType: String?,
        val extension: String,
        val sizeBytes: Long
    )

    init {
        require(maxAssetBytes > 0) { "Asset size limit must be positive" }
    }

    private val indexFile get() = File(root, "assets.properties")

    @Synchronized
    @Throws(IOException::class)
    fun import(input: InputStream, displayName: String, mimeType: String? = null): Asset {
        val name = displayName.trim()
        require(name.isNotEmpty() && name.length <= 255) { "Asset name must contain 1–255 characters" }
        val normalizedMime = mimeType?.trim()?.takeIf(String::isNotEmpty)
        require(normalizedMime == null || normalizedMime.length <= 128) { "MIME type is too long" }
        ensureDirectory()

        val temporary = File(root, ".import-${UUID.randomUUID()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    size += read
                    if (size > maxAssetBytes) throw IOException("Asset exceeds ${maxAssetBytes} byte import limit")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            val hash = digest.digest().toHex()
            val extension = extensionFor(name)
            val payload = payloadFile(hash, extension)
            if (payload.exists()) {
                if (payload.length() != size) throw IOException("Existing content-addressed asset has an inconsistent size")
                temporary.delete()
            } else {
                moveReplacing(temporary, payload)
            }

            val properties = readIndex()
            val existing = properties.getProperty(hash)?.decodeRecord(hash)
            val asset = existing ?: Asset(hash, name, normalizedMime, extension, size)
            if (existing == null) {
                properties.setProperty(hash, asset.encodeRecord())
                writeIndex(properties)
            }
            return asset
        } catch (failure: Throwable) {
            temporary.delete()
            if (failure is IOException || failure is IllegalArgumentException) throw failure
            throw IOException("Unable to import offline asset", failure)
        }
    }

    @Synchronized
    fun list(): List<Asset> = readIndex().entries
        .mapNotNull { entry ->
            runCatching {
                val hash = (entry.key as String).decodeHash()
                (entry.value as String).decodeRecord(hash)
            }.getOrNull()
        }
        .sortedWith(compareBy<Asset> { it.displayName.lowercase() }.thenBy { it.sha256 })

    @Synchronized
    fun get(sha256: String): Asset? {
        val hash = sha256.decodeHash()
        return readIndex().getProperty(hash)?.decodeRecord(hash)
    }

    /** Returns the immutable app-private payload path for native relinking. */
    @Synchronized
    fun fileFor(sha256: String): File? {
        val hash = sha256.decodeHash()
        if (readIndex().getProperty(hash) == null) return null
        val asset = readIndex().getProperty(hash)?.decodeRecord(hash) ?: return null
        return payloadFile(hash, asset.extension).takeIf(File::isFile)
    }

    @Synchronized
    @Throws(IOException::class)
    fun open(sha256: String): InputStream {
        val hash = sha256.decodeHash()
        if (readIndex().getProperty(hash) == null) throw IOException("Asset $hash is not in the offline library")
        val asset = readIndex().getProperty(hash)?.decodeRecord(hash) ?: throw IOException("Asset $hash is not in the offline library")
        val payload = payloadFile(hash, asset.extension)
        if (!payload.isFile()) throw IOException("Offline asset payload is missing: $hash")
        return FileInputStream(payload)
    }

    /** Recomputes the payload digest and size and compares them to the index record. */
    @Synchronized
    fun verify(sha256: String): Boolean {
        val hash = sha256.decodeHash()
        val asset = readIndex().getProperty(hash)?.decodeRecord(hash) ?: return false
        val payload = payloadFile(hash, asset.extension)
        if (!payload.isFile() || payload.length() != asset.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        payload.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex() == hash
    }

    /** Indexed assets whose payload is missing, resized or digest-mismatched. */
    @Synchronized
    fun verifyAll(): List<Asset> = list().filterNot { verify(it.sha256) }

    data class GcResult(
        val deleted: List<Asset>,
        val orphanPayloadsRemoved: Int,
        val failed: List<String>
    )

    /**
     * Deletes every indexed asset not in [referencedHashes] plus every
     * content-addressed payload file with no index entry, and returns what
     * was removed. A failed delete never aborts the sweep: the hash is
     * reported in [GcResult.failed] for the next run. Re-running collection
     * with the same referenced set is a no-op.
     */
    @Synchronized
    fun collectGarbage(referencedHashes: Set<String>): GcResult {
        val referenced = referencedHashes.map { it.decodeHash() }.toSet()
        val deleted = mutableListOf<Asset>()
        val failed = mutableListOf<String>()
        list().filter { it.sha256 !in referenced }.forEach { asset ->
            runCatching { delete(asset.sha256) }
                .onSuccess { removed -> if (removed) deleted += asset }
                .onFailure { failed += asset.sha256 }
        }
        val indexed = list().map { payloadFile(it.sha256, it.extension).name }.toSet()
        var orphans = 0
        if (root.isDirectory) {
            root.listFiles { file ->
                file.isFile && file.name.matches(Regex("[a-f0-9]{64}\\.[a-z0-9]{1,10}")) && file.name !in indexed
            }.orEmpty().forEach { orphan ->
                if (orphan.delete()) orphans++ else failed += orphan.name
            }
        }
        return GcResult(deleted, orphans, failed)
    }

    @Synchronized
    @Throws(IOException::class)
    fun delete(sha256: String): Boolean {
        val hash = sha256.decodeHash()
        val properties = readIndex()
        val record = properties.getProperty(hash) ?: return false
        val asset = record.decodeRecord(hash)
        properties.remove(hash)
        writeIndex(properties)
        val payload = payloadFile(hash, asset.extension)
        if (payload.exists() && !payload.delete()) throw IOException("Asset index removed but payload could not be deleted: $hash")
        return true
    }

    private fun ensureDirectory() {
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create offline asset directory")
        if (!root.isDirectory) throw IOException("Offline asset path is not a directory")
    }

    private fun payloadFile(hash: String, extension: String) = File(root, "$hash.$extension")

    private fun extensionFor(name: String): String = name.substringAfterLast('.', "asset")
        .lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "asset"

    private fun readIndex(): Properties = Properties().apply {
        if (indexFile.isFile) indexFile.inputStream().buffered().use(::load)
    }

    private fun writeIndex(properties: Properties) {
        val temporary = File(root, "assets.properties.tmp")
        temporary.outputStream().buffered().use { properties.store(it, "EffectCraft offline media index v1") }
        moveReplacing(temporary, indexFile)
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun Asset.encodeRecord(): String = listOf(
        displayName.toBase64(),
        (mimeType ?: "").toBase64(),
        extension.toBase64(),
        sizeBytes.toString()
    ).joinToString(".")

    private fun String.decodeRecord(hash: String): Asset {
        val fields = split('.')
        require(fields.size == 4) { "Invalid offline asset index record" }
        val name = fields[0].fromBase64()
        val mime = fields[1].fromBase64().ifEmpty { null }
        val extension = fields[2].fromBase64()
        val size = fields[3].toLong()
        require(name.isNotBlank() && extension.matches(Regex("[a-z0-9]{1,10}")) && size >= 0) { "Invalid offline asset metadata" }
        return Asset(hash, name, mime, extension, size)
    }

    private fun String.decodeHash(): String {
        require(matches(Regex("[a-f0-9]{64}"))) { "Asset ID must be a lowercase SHA-256 digest" }
        return this
    }

    private fun String.toBase64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))
    private fun String.fromBase64(): String = String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_MAX_ASSET_BYTES: Long = 512L * 1024L * 1024L
    }
}
