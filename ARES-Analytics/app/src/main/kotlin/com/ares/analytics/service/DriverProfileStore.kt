package com.ares.analytics.service

import com.ares.analytics.shared.models.DriverProfile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.WeakHashMap

internal val WRITE_DRIVER_PROFILES: (File, ByteArray) -> Unit = { file, bytes ->
    writeFileAtomically(file) { temporary -> Files.write(temporary.toPath(), bytes) }
}

/** One process-local commit boundary per canonical file. Snapshots are immutable and reads do no IO. */
internal class DriverProfileStore private constructor(private val file: File) {
    private val lock = Any()
    @Volatile private var snapshot: Map<String, DriverProfile> = emptyMap()
    private var initialized = false

    fun all(): List<DriverProfile> = snapshot.values.toList()
    fun get(name: String): DriverProfile? = snapshot[name]

    private fun initialize(write: (File, ByteArray) -> Unit) = synchronized(lock) {
        if (initialized) return@synchronized
        val bytes = try { readBytes() } catch (_: NoSuchFileException) { null }
        val initial = if (bytes == null) {
            defaults().also { persist(it, write) }
        } else {
            try { decode(bytes) } catch (_: InvalidProfiles) {
                // Preserve the exact original before replacing malformed data. Read/permission,
                // directory and file/list budget failures never take this recovery path.
                val backup = Files.createTempFile(file.parentFile.toPath(), "${file.name}.corrupt-", "").toFile()
                try { write(backup, bytes) } catch (failure: Exception) {
                    try { Files.deleteIfExists(backup.toPath()) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
                defaults().also { persist(it, write) }
            }
        }
        snapshot = initial
        initialized = true
    }

    fun save(profile: DriverProfile, write: (File, ByteArray) -> Unit, checkActive: () -> Unit) {
        requireValid(profile)
        mutate(write, checkActive) { it + (profile.name to profile) }
    }

    fun delete(name: String, write: (File, ByteArray) -> Unit, checkActive: () -> Unit) = mutate(write, checkActive) { it - name }

    private fun mutate(write: (File, ByteArray) -> Unit, checkActive: () -> Unit, change: (Map<String, DriverProfile>) -> Map<String, DriverProfile>) = synchronized(lock) {
        checkActive()
        // Incorporate already-completed external edits, but never silently repair data during a
        // user's mutation. Concurrent writers in other processes are outside this local lock.
        val current = decode(readBytes())
        val next = change(current).toSortedMap().toMap()
        require(next.size <= MAX_PROFILES) { "At most $MAX_PROFILES driver profiles can be stored" }
        if (next != current) {
            checkActive()
            persist(next, write)
        }
        snapshot = next
    }

    private fun readBytes(): ByteArray {
        val path = file.toPath()
        if (!Files.readAttributes(path, BasicFileAttributes::class.java).isRegularFile) {
            throw IOException("Driver profile path must be a regular file: $file")
        }
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) throw IOException("Driver profile file exceeds $MAX_BYTES bytes")
        return bytes
    }

    private fun decode(bytes: ByteArray): Map<String, DriverProfile> {
        val text = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        catch (failure: CharacterCodingException) { throw InvalidProfiles(failure) }
        val profiles = try { json.decodeFromString<List<DriverProfile>>(text) }
        catch (failure: SerializationException) { throw InvalidProfiles(failure) }
        if (profiles.size > MAX_PROFILES) throw IOException("Driver profile file exceeds $MAX_PROFILES profiles")
        try {
            profiles.forEach(::requireValid)
            require(profiles.map { it.name }.distinct().size == profiles.size) { "Driver profile names must be unique" }
        } catch (failure: IllegalArgumentException) { throw InvalidProfiles(failure) }
        return profiles.sortedBy { it.name }.associateBy { it.name }
    }

    private fun persist(profiles: Map<String, DriverProfile>, write: (File, ByteArray) -> Unit) {
        val bytes = json.encodeToString(profiles.values.sortedBy { it.name }).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Encoded driver profile file exceeds $MAX_BYTES bytes" }
        write(file, bytes)
    }

    private class InvalidProfiles(cause: Exception) : IllegalArgumentException("Driver profile data is malformed or invalid", cause)

    companion object {
        private const val MAX_BYTES = 1_048_576
        private const val MAX_PROFILES = 256
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
        // Neither keys nor values keep a disposed store alive. The store itself retains its key
        // while any service uses it, preserving one shared lock/snapshot for live instances.
        private val stores = WeakHashMap<File, WeakReference<DriverProfileStore>>()

        fun open(path: String, write: (File, ByteArray) -> Unit): DriverProfileStore {
            val file = File(path).canonicalFile
            val store = synchronized(stores) {
                stores[file]?.get() ?: DriverProfileStore(file).also { stores[file] = WeakReference(it) }
            }
            store.initialize(write)
            return store
        }

        private fun requireValid(profile: DriverProfile) {
            require(profile.name.isNotBlank() && profile.name.length <= 256 &&
                profile.deadbandExponent.isFinite() && profile.deadbandExponent > 0.0 &&
                profile.slewRateLimit.isFinite() && profile.slewRateLimit > 0.0 &&
                profile.jitterPeakFrequencyHz.isFinite() && profile.jitterPeakFrequencyHz >= 0.0 &&
                profile.jitterAmplitude.isFinite() && profile.jitterAmplitude >= 0.0) {
                "Driver profile requires a name of at most 256 characters, positive finite response values and nonnegative finite spectral metadata"
            }
        }

        private fun defaults(): Map<String, DriverProfile> = listOf(
            DriverProfile("Default Alpha", 1.2, 3.5), DriverProfile("Precision Mode", 1.5, 2.0),
            DriverProfile("Aggressive Mode", 1.0, Double.MAX_VALUE),
        ).sortedBy { it.name }.associateBy { it.name }
    }
}
