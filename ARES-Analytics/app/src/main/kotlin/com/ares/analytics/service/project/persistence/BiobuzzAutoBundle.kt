package com.ares.analytics.service.project.persistence

import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStepKind
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Reads native documents without extracting archive paths or executing uploaded content. */
internal object BiobuzzAutoBundle {
    data class Draft(val routine: RoutineDocument, val entry: AutonomousCatalogEntry)
    fun read(file: File): Draft {
        require(file.isFile && file.length() in 1..1_048_576) { "Choose a BIOBUZZ auto ZIP smaller than 1 MiB." }
        val documents = mutableMapOf<String, String>()
        ZipFile(file).use { archive ->
            val entries = archive.entries().toList()
            require(entries.size <= 8) { "The auto archive contains too many entries." }
            var total = 0
            for (entry in entries) {
                val name = entry.name
                require(!name.startsWith("/") && !name.contains("\\") && name.split('/').none { it == ".." }) { "Unsafe archive path." }
                if (entry.isDirectory) {
                    require(name in setOf(".ares/", ".ares/routines/")) { "Unexpected archive directory." }
                    continue
                }
                require(name == "README.txt" || name == ".ares/autonomous-catalog.json" ||
                    Regex("\\.ares/routines/[a-z0-9._-]+\\.aresroutine").matches(name)) { "Unexpected file in auto archive." }
                require(!documents.containsKey(name)) { "Duplicate archive entry." }
                val bytes = archive.getInputStream(entry).use { it.readNBytes(262_145) }
                total += bytes.size
                require(bytes.size <= 262_144 && total <= 524_288) { "Auto documents are too large." }
                documents[name] = bytes.toString(Charsets.UTF_8)
            }
        }
        val routines = documents.filterKeys { it.endsWith(".aresroutine") }
        require(routines.size == 1) { "Import one autonomous routine at a time." }
        val source = AresRoutineCodec.decode(routines.values.single())
        require(routines.keys.single() == ".ares/routines/${source.documentId}.aresroutine") { "Routine filename does not match its identifier." }
        require(source.steps.size <= 4096 && source.steps.all {
            it.kind in setOf(RoutineStepKind.ACTION, RoutineStepKind.DRIVE_TO, RoutineStepKind.WAIT) &&
                it.children.isEmpty() && it.elseChildren.isEmpty() && it.deadline == null
        }) { "This import supports BIOBUZZ waypoint, action, and wait routines." }
        val catalog = AutonomousCatalogCodec.decode(requireNotNull(documents[".ares/autonomous-catalog.json"]) { "The starting-pose catalog is missing." })
        val entry = catalog.entries.singleOrNull() ?: error("The archive must have one match auto entry.")
        require(entry.routineId == source.documentId && !entry.mirrorForOppositeAlliance) { "Invalid routine reference or alliance mirroring." }
        val id = "biobuzz-${UUID.randomUUID()}"
        // Import as new: existing project documents and history cannot be overwritten.
        return Draft(source.copy(documentId = id, revision = 1, parentContentHash = null),
            entry.copy(entryId = id, routineId = id))
    }
}
