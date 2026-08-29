package com.ares.analytics.service

import com.ares.analytics.service.dashboard.BuiltInDashboardLayoutProfiles
import com.ares.analytics.service.dashboard.DashboardLayoutProfileCatalog
import com.ares.analytics.service.dashboard.DashboardWidgetCatalog
import com.ares.analytics.service.dashboard.dashboardLayoutValidationErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale

private val SAFE_LAYOUT_PROFILE_NAME =
    Regex("[A-Za-z0-9](?:[A-Za-z0-9 _-]{0,62}[A-Za-z0-9])?")
private val RESERVED_LAYOUT_PROFILE_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL", "CLOCK\$"))
    (1..9).forEach { index ->
        add("COM$index")
        add("LPT$index")
    }
}
/** Returns a user-facing validation error, or `null` when [profileName] is safe to persist. */
fun layoutProfileNameError(profileName: String): String? {
    if (profileName.isBlank()) return "Layout name cannot be blank."
    if (profileName != profileName.trim()) return "Remove leading or trailing spaces."
    if (!SAFE_LAYOUT_PROFILE_NAME.matches(profileName)) {
        return "Use 1–64 letters, numbers, spaces, underscores, or hyphens."
    }
    if (profileName.uppercase(Locale.ROOT) in RESERVED_LAYOUT_PROFILE_NAMES) {
        return "That layout name is reserved by the operating system."
    }
    return null
}

/** Grid layout configuration for a single dashboard widget card. */
@Serializable
data class WidgetConfig(
    val id: String,
    /** Stable serialized dashboard widget type registered by DashboardWidgetRegistry. */
    val type: String,
    val row: Int,
    val col: Int,
    val rowSpan: Int,
    val colSpan: Int,
    val isLocked: Boolean = false,
    val properties: Map<String, String> = emptyMap(),
)

@Serializable
data class DashboardLayoutConfig(
    val widgets: List<WidgetConfig>,
)

class LayoutPreferenceService(
    private val baseDir: String = AppDataPaths.file("layouts").path,
    private val widgetCatalog: DashboardWidgetCatalog,
    private val profileCatalog: DashboardLayoutProfileCatalog = BuiltInDashboardLayoutProfiles,
    private val beforeAtomicReplace: ((temporary: Path, destination: Path) -> Unit)? = null,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val canonicalRoot = File(baseDir).canonicalFile.toPath()

    init {
        Files.createDirectories(canonicalRoot)
        require(Files.isDirectory(canonicalRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Dashboard layout root is not a directory"
        }
        widgetCatalog.requireValid()
        val errors = profileCatalog.validationErrors(widgetCatalog)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    private fun profileKey(profileName: String): String {
        val error = layoutProfileNameError(profileName)
        require(error == null) { error ?: "Invalid dashboard layout name" }
        return profileName.lowercase(Locale.ROOT).replace(' ', '_')
    }

    private fun pathForProfile(profileName: String): Path {
        val candidate = canonicalRoot.resolve("${profileKey(profileName)}.json").normalize()
        require(candidate.parent == canonicalRoot) { "Layout path escaped its storage directory" }
        val resolved = candidate.toFile().canonicalFile.toPath()
        require(resolved.parent == canonicalRoot && resolved.startsWith(canonicalRoot)) {
            "Layout path escaped its storage directory"
        }
        return candidate
    }

    private fun validateLayout(owner: String, config: DashboardLayoutConfig) {
        val errors = dashboardLayoutValidationErrors(owner, config, widgetCatalog)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    private fun writeAtomically(destination: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(canonicalRoot, ".layout-", ".tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeAtomicReplace?.invoke(temporary, destination)
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            runCatching {
                FileChannel.open(canonicalRoot, StandardOpenOption.READ).use { it.force(true) }
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    suspend fun saveLayout(profileName: String, config: DashboardLayoutConfig) = withContext(Dispatchers.IO) {
        validateLayout(profileName, config)
        val destination = pathForProfile(profileName)
        writeAtomically(destination, json.encodeToString(config).toByteArray(Charsets.UTF_8))
    }

    suspend fun loadLayout(profileName: String): DashboardLayoutConfig = withContext(Dispatchers.IO) {
        val path = pathForProfile(profileName)
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                val decoded = json.decodeFromString<DashboardLayoutConfig>(Files.readString(path))
                validateLayout(profileName, decoded)
                return@withContext decoded
            } catch (error: Exception) {
                System.err.println("[Dashboard] Ignoring invalid saved layout '$profileName': ${error.message}")
            }
        }
        getDefaultLayout(profileName)
    }

    fun getDefaultLayout(profileName: String): DashboardLayoutConfig =
        profileCatalog.getDefaultLayout(profileName)

    fun getSavedLayouts(): List<String> {
        val files = canonicalRoot.toFile().listFiles { file ->
            file.name.endsWith(".json") &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                file.canonicalFile.parentFile?.toPath() == canonicalRoot
        } ?: return emptyList()
        return files.map { file ->
            file.nameWithoutExtension.split("_").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        }.filter { layoutProfileNameError(it) == null }
    }

    fun getAvailableLayouts(): List<String> =
        (profileCatalog.availableNames() + getSavedLayouts()).distinct()

    suspend fun deleteLayout(profileName: String): Boolean = withContext(Dispatchers.IO) {
        Files.deleteIfExists(pathForProfile(profileName))
    }
}
