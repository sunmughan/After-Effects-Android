package ai.effectcraft.android.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

/**
 * Durable record of persisted Storage Access Framework grants.
 *
 * The operating system owns the truth: a grant recorded here is only a claim
 * until [reconcile] compares it against the grants Android still reports
 * (`ContentResolver.persistedUriPermissions`). Reconcile never deletes a
 * record — a grant the OS no longer lists becomes LOST so the UI can offer
 * recovery (re-open the document) instead of silently forgetting it. Release
 * is the only operation that removes a record, and the app also releases the
 * OS-side grant when it does so.
 *
 * This registry never holds document bytes or paths: only the grant URI, its
 * kind, a display name and a status.
 */
class SafPermissionRegistry(private val file: File) {
    enum class Kind { READ, WRITE }
    enum class Status { GRANTED, LOST }

    data class Grant(
        val uri: String,
        val kind: Kind,
        val displayName: String,
        val status: Status,
        val recordedAtMillis: Long
    )

    data class GrantKey(val uri: String, val kind: Kind)

    @Synchronized
    fun record(uri: String, kind: Kind, displayName: String): Grant {
        val checkedUri = validateUri(uri)
        val name = validateDisplayName(displayName)
        val properties = read()
        val key = keyFor(checkedUri, kind)
        val previous = properties.getProperty(key)?.let { decode(it) }
        val grant = Grant(checkedUri, kind, name, Status.GRANTED, previous?.recordedAtMillis ?: System.currentTimeMillis())
        properties.setProperty(key, encode(grant))
        write(properties)
        return grant
    }

    @Synchronized
    fun grants(): List<Grant> = read().entries
        .mapNotNull { entry -> runCatching { decode(entry.value as String) }.getOrNull() }
        .sortedWith(compareBy<Grant> { it.displayName.lowercase() }.thenBy { it.uri }.thenBy { it.kind.name })

    @Synchronized
    fun lost(): List<Grant> = grants().filter { it.status == Status.LOST }

    @Synchronized
    fun needsRecovery(): Boolean = lost().isNotEmpty()

    @Synchronized
    fun isGranted(uri: String, kind: Kind): Boolean =
        grants().any { it.uri == validateUri(uri) && it.kind == kind && it.status == Status.GRANTED }

    @Synchronized
    fun markLost(uri: String, kind: Kind): Boolean {
        val properties = read()
        val key = keyFor(validateUri(uri), kind)
        val current = properties.getProperty(key)?.let { decode(it) } ?: return false
        if (current.status == Status.LOST) return false
        properties.setProperty(key, encode(current.copy(status = Status.LOST)))
        write(properties)
        return true
    }

    @Synchronized
    fun release(uri: String): Boolean {
        val checkedUri = validateUri(uri)
        val properties = read()
        val keys = properties.stringPropertyNames().filter { key ->
            runCatching { decode(properties.getProperty(key)).uri == checkedUri }.getOrDefault(false)
        }
        if (keys.isEmpty()) return false
        keys.forEach(properties::remove)
        write(properties)
        return true
    }

    /**
     * Compares recorded grants against the grants the OS still reports.
     * Held grants return to GRANTED (recovery); unheld grants become LOST.
     * Returns only the grants whose status changed.
     */
    @Synchronized
    fun reconcile(held: Set<GrantKey>): List<Grant> {
        val properties = read()
        val changed = mutableListOf<Grant>()
        properties.stringPropertyNames().forEach { key ->
            val current = runCatching { decode(properties.getProperty(key)) }.getOrNull() ?: return@forEach
            val shouldBeGranted = GrantKey(current.uri, current.kind) in held
            val nextStatus = if (shouldBeGranted) Status.GRANTED else Status.LOST
            if (nextStatus != current.status) {
                val updated = current.copy(status = nextStatus)
                properties.setProperty(key, encode(updated))
                changed += updated
            }
        }
        if (changed.isNotEmpty()) write(properties)
        return changed.sortedWith(compareBy<Grant> { it.displayName.lowercase() }.thenBy { it.uri })
    }

    private fun keyFor(uri: String, kind: Kind): String = "${uri.toBase64()}.${kind.name}"

    private fun encode(grant: Grant): String = listOf(
        grant.uri.toBase64(),
        grant.displayName.toBase64(),
        grant.kind.name,
        grant.status.name,
        grant.recordedAtMillis.toString()
    ).joinToString(".")

    private fun decode(value: String): Grant {
        val fields = value.split('.')
        require(fields.size == 5) { "Invalid SAF permission record" }
        return Grant(
            uri = fields[0].fromBase64(),
            displayName = fields[1].fromBase64(),
            kind = Kind.valueOf(fields[2]),
            status = Status.valueOf(fields[3]),
            recordedAtMillis = fields[4].toLong()
        )
    }

    private fun read(): Properties = Properties().apply {
        if (file.isFile) file.inputStream().buffered().use(::load)
    }

    private fun write(properties: Properties) {
        val parent = file.parentFile ?: throw IOException("SAF permission file has no parent")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create SAF permission directory")
        val temporary = File(parent, "${file.name}.tmp")
        temporary.outputStream().buffered().use { properties.store(it, "EffectCraft SAF permission registry v1") }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun validateUri(value: String): String {
        require(value.isNotBlank() && value.length <= 2048) { "SAF grant URI must contain 1–2048 characters" }
        return value
    }

    private fun validateDisplayName(value: String): String {
        require(value.isNotBlank() && value.length <= 255) { "SAF grant display name must contain 1–255 characters" }
        return value
    }

    private fun String.toBase64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))
    private fun String.fromBase64(): String = String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
}
