package com.ares.analytics.service

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

/**
 * Grid layout configuration for a single dashboard widget card.
 *
 * @property id Unique widget instance identifier.
 * @property type Widget view type string (`"runs_index"`, `"alerts"`, `"telemetry_chart"`, `"motor_health"`, `"vision_quality"`, `"ai_coach"`, `"match_schedule"`, `"console_viewer"`).
 * @property row Zero-indexed grid row position.
 * @property col Zero-indexed grid column position.
 * @property rowSpan Row span count ($1 \dots N$).
 * @property colSpan Column span count ($1 \dots N$).
 * @property isLocked `true` if widget position is locked against user dragging.
 * @property properties Custom key-value properties dictionary for the widget.
 */
@Serializable
data class WidgetConfig(
    val id: String,
    val type: String, // "runs_index", "alerts", "telemetry_chart", "motor_health", "vision_quality", "ai_coach", "match_schedule", "console_viewer"
    val row: Int,
    val col: Int,
    val rowSpan: Int,
    val colSpan: Int,
    val isLocked: Boolean = false,
    val properties: Map<String, String> = emptyMap()
)

/**
 * Dashboard layout container holding configured widgets.
 *
 * @property widgets List of [WidgetConfig] records.
 */
@Serializable
data class DashboardLayoutConfig(
    val widgets: List<WidgetConfig>
)

class LayoutPreferenceService(
    private val baseDir: String = AppDataPaths.file("layouts").path,
    private val beforeAtomicReplace: ((temporary: Path, destination: Path) -> Unit)? = null
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val canonicalRoot = File(baseDir).canonicalFile.toPath()

    init {
        Files.createDirectories(canonicalRoot)
        require(Files.isDirectory(canonicalRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Dashboard layout root is not a directory"
        }
    }

    private fun profileKey(profileName: String): String {
        val error = layoutProfileNameError(profileName)
        require(error == null) { error ?: "Invalid dashboard layout name" }
        return profileName.lowercase(Locale.ROOT).replace(' ', '_')
    }

    private fun pathForProfile(profileName: String): Path {
        val candidate = canonicalRoot.resolve("${profileKey(profileName)}.json").normalize()
        require(candidate.parent == canonicalRoot) { "Layout path escaped its storage directory" }

        // canonicalFile also rejects a pre-existing direct-child symlink that targets elsewhere.
        val resolved = candidate.toFile().canonicalFile.toPath()
        require(resolved.parent == canonicalRoot && resolved.startsWith(canonicalRoot)) {
            "Layout path escaped its storage directory"
        }
        return candidate
    }

    private fun writeAtomically(destination: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(canonicalRoot, ".layout-", ".tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
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
                StandardCopyOption.REPLACE_EXISTING
            )
            // Directory fsync is supported on Unix-like hosts; Windows may reject directory
            // channels, while the file itself and atomic rename remain durable guarantees.
            runCatching {
                FileChannel.open(canonicalRoot, StandardOpenOption.READ).use { it.force(true) }
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    suspend fun saveLayout(profileName: String, config: DashboardLayoutConfig) = withContext(Dispatchers.IO) {
        val destination = pathForProfile(profileName)
        writeAtomically(destination, json.encodeToString(config).toByteArray(Charsets.UTF_8))
    }

    suspend fun loadLayout(profileName: String): DashboardLayoutConfig = withContext(Dispatchers.IO) {
        val path = pathForProfile(profileName)
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                return@withContext json.decodeFromString<DashboardLayoutConfig>(Files.readString(path))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Fallback to default layouts
        getDefaultLayout(profileName)
    }

    fun getDefaultLayout(profileName: String): DashboardLayoutConfig {
        return when (profileKey(profileName)) {
            "student" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("field_viewer", "field_viewer", 0, 0, 5, 7),
                    WidgetConfig("system_health", "system_health", 0, 7, 3, 5),
                    WidgetConfig("autonomous_selector", "autonomous_selector", 3, 7, 2, 5),
                    WidgetConfig("telemetry_chart", "telemetry_chart", 5, 0, 5, 7),
                    WidgetConfig("alerts", "alerts", 5, 7, 5, 5),
                    WidgetConfig("subsystem_health", "subsystem_health", 10, 0, 4, 7)
                )
            )
            "driver" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("field_viewer", "field_viewer", 0, 0, 6, 8),
                    WidgetConfig("joystick_visualizer", "joystick_visualizer", 0, 8, 3, 4),
                    WidgetConfig("system_health", "system_health", 3, 8, 3, 4),
                    WidgetConfig("telemetry_chart", "telemetry_chart", 6, 0, 4, 8),
                    WidgetConfig("alerts", "alerts", 6, 8, 4, 4)
                )
            )
            "builder" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("hardware_topology", "hardware_topology", 0, 0, 5, 7),
                    WidgetConfig("system_health", "system_health", 0, 7, 3, 5),
                    WidgetConfig("motor_health", "motor_health", 3, 7, 4, 5),
                    WidgetConfig("power_distribution", "power_distribution", 5, 0, 4, 4),
                    WidgetConfig("battery_health", "battery_health", 5, 4, 4, 3),
                    WidgetConfig("alerts", "alerts", 7, 7, 2, 5),
                    WidgetConfig("subsystem_health", "subsystem_health", 9, 0, 4, 7)
                )
            )
            "autonomous" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("field_viewer", "field_viewer", 0, 0, 6, 7),
                    WidgetConfig("autonomous_selector", "autonomous_selector", 0, 7, 3, 5),
                    WidgetConfig("pose_viewer", "pose_viewer", 3, 7, 3, 5),
                    WidgetConfig("path_tuning", "path_tuning", 6, 0, 5, 6),
                    WidgetConfig("ekf_telemetry", "ekf_telemetry", 6, 6, 5, 6)
                )
            )
            "analyst" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("runs_index", "runs_index", 0, 0, 3, 12),
                    WidgetConfig("telemetry_chart", "telemetry_chart", 3, 0, 5, 8),
                    WidgetConfig("session_summary", "session_summary", 3, 8, 5, 4),
                    WidgetConfig("advanced_analytics", "advanced_analytics", 8, 0, 5, 6),
                    WidgetConfig("trends_card", "trends_card", 8, 6, 5, 6)
                )
            )
            "mentor" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("system_health", "system_health", 0, 0, 3, 6),
                    WidgetConfig("alerts", "alerts", 0, 6, 3, 6),
                    WidgetConfig("pit_evidence_checklist", "pit_evidence_checklist", 3, 0, 5, 6),
                    WidgetConfig("ai_coach", "ai_coach", 3, 6, 5, 6),
                    WidgetConfig("runs_index", "runs_index", 8, 0, 4, 6),
                    WidgetConfig("control_profiler", "control_profiler", 8, 6, 4, 6)
                )
            )
            "driver_coach" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("field_viewer", "field_viewer", 0, 0, 5, 7),
                    WidgetConfig("autonomous_selector", "autonomous_selector", 0, 7, 3, 5),
                    WidgetConfig("system_health", "system_health", 3, 7, 2, 5),
                    WidgetConfig("telemetry_chart", "telemetry_chart", 5, 0, 5, 8),
                    WidgetConfig("alerts", "alerts", 5, 8, 5, 4)
                )
            )
            "programmer" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("telemetry_chart", "telemetry_chart", 0, 0, 6, 8),
                    WidgetConfig("console_viewer_0", "console_viewer", 0, 8, 6, 4),
                    WidgetConfig("system_health", "system_health", 6, 0, 3, 4),
                    WidgetConfig("profiling_diagnostics", "profiling_diagnostics", 6, 4, 3, 4),
                    WidgetConfig("ekf_telemetry", "ekf_telemetry", 6, 8, 3, 4)
                )
            )
            "pit_crew" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("runs_index", "runs_index", 0, 0, 3, 8),
                    WidgetConfig("system_health", "system_health", 0, 8, 3, 4),
                    WidgetConfig("motor_health", "motor_health", 3, 0, 4, 4),
                    WidgetConfig("vision_quality", "vision_quality", 3, 4, 4, 4),
                    WidgetConfig("alerts", "alerts", 3, 8, 4, 4),
                    WidgetConfig("advanced_analytics", "advanced_analytics", 7, 0, 4, 6),
                    WidgetConfig("ai_coach", "ai_coach", 7, 6, 4, 6)
                )
            )
            "replay", "match_review" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("runs_index", "runs_index", 0, 0, 3, 12),
                    WidgetConfig("telemetry_chart", "telemetry_chart", 3, 0, 5, 8),
                    WidgetConfig("field_viewer", "field_viewer", 3, 8, 5, 4),
                    WidgetConfig("advanced_analytics", "advanced_analytics", 8, 0, 5, 6),
                    WidgetConfig("alerts", "alerts", 8, 6, 5, 3),
                    WidgetConfig("system_health", "system_health", 8, 9, 5, 3)
                )
            )
            "pit_diagnostics" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("system_health", "system_health", 0, 0, 3, 6),
                    WidgetConfig("alerts", "alerts", 0, 6, 3, 6),
                    WidgetConfig("motor_health", "motor_health", 3, 0, 4, 4),
                    WidgetConfig("battery_health", "battery_health", 3, 4, 4, 4),
                    WidgetConfig("vision_quality", "vision_quality", 3, 8, 4, 4),
                    WidgetConfig("ai_coach", "ai_coach", 7, 0, 5, 6),
                    WidgetConfig("advanced_analytics", "advanced_analytics", 7, 6, 5, 6)
                )
            )
            "driver_practice" -> DashboardLayoutConfig(
                listOf(
                    WidgetConfig("field_viewer", "field_viewer", 0, 0, 6, 8),
                    WidgetConfig("joystick_visualizer", "joystick_visualizer", 0, 8, 3, 4),
                    WidgetConfig("system_health", "system_health", 3, 8, 3, 4),
                    WidgetConfig("telemetry_chart", "telemetry_chart", 6, 0, 4, 8),
                    WidgetConfig("alerts", "alerts", 6, 8, 4, 4)
                )
            )
            else -> DashboardLayoutConfig( // Default standard layout
                listOf(
                    WidgetConfig("runs_index", "runs_index", 0, 0, 3, 7),
                    WidgetConfig("system_health", "system_health", 0, 7, 3, 5),
                    WidgetConfig("field_viewer", "field_viewer", 3, 0, 5, 7),
                    WidgetConfig("telemetry_chart", "telemetry_chart", 3, 7, 5, 5),
                    WidgetConfig("advanced_analytics", "advanced_analytics", 8, 0, 5, 6),
                    WidgetConfig("alerts", "alerts", 8, 6, 5, 3),
                    WidgetConfig("joystick_visualizer", "joystick_visualizer", 8, 9, 5, 3),
                    WidgetConfig("subsystem_health", "subsystem_health", 13, 0, 4, 6)
                )
            )
        }
    }

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

    fun getAvailableLayouts(): List<String> {
        val defaults = listOf(
            "Student", "Driver", "Builder", "Autonomous", "Analyst", "Mentor",
            "Standard", "Driver Coach", "Programmer", "Pit Crew", "Match Review",
            "Pit Diagnostics", "Driver Practice", "Replay"
        )
        val saved = getSavedLayouts()
        return (defaults + saved).distinct()
    }

    suspend fun deleteLayout(profileName: String): Boolean = withContext(Dispatchers.IO) {
        Files.deleteIfExists(pathForProfile(profileName))
    }
}
