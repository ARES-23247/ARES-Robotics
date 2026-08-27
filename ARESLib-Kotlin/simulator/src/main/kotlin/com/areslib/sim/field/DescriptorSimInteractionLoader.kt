package com.areslib.sim.field

import com.areslib.sim.SimInteractionModel
import com.areslib.subsystem.SimInteractionRole
import com.areslib.subsystem.SubsystemDocumentCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** Loads simulator-only interaction metadata from the selected robot project's canonical documents. */
object DescriptorSimInteractionLoader {
    fun loadFrom(startDirectory: Path): SimInteractionModel? {
        val projectRoot = findProjectRoot(startDirectory.toAbsolutePath().normalize()) ?: return null
        val subsystemDirectory = projectRoot.resolve(".ares").resolve("subsystems")
        val documents = Files.list(subsystemDirectory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "aressubsystem" }
                .sorted()
                .map { path -> SubsystemDocumentCodec.decode(Files.readString(path)) }
                .toList()
        }
        if (documents.none { it.implementation.simulation.interaction.role != SimInteractionRole.NONE }) return null
        return ConfigurableGamePieceInteractionModel.fromSubsystems(documents)
    }

    private fun findProjectRoot(start: Path): Path? {
        var candidate: Path? = start
        repeat(4) {
            val current = candidate ?: return null
            if (Files.isDirectory(current.resolve(".ares").resolve("subsystems"))) return current
            candidate = current.parent
        }
        return null
    }
}
