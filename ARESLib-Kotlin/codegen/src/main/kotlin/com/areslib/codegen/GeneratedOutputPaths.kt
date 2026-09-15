package com.areslib.codegen

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Checks normalized and physical containment, including existing symlink/junction parents. */
internal object GeneratedOutputPaths {
    fun resolve(root: Path, relative: String): Path {
        val directory = root.toAbsolutePath().normalize()
        val component = directory.fileSystem.getPath(relative.replace('\\', '/'))
        val path = directory.resolve(component).normalize()
        require(relative.isNotBlank() && component.root == null && path != directory) {
            "Invalid generated relative path '$relative'"
        }
        return requireWithin(directory, path)
    }

    fun requireWithin(root: Path, path: Path): Path {
        val directory = root.toAbsolutePath().normalize()
        val candidate = path.toAbsolutePath().normalize()
        require(candidate.startsWith(directory) && physicalLocation(candidate).startsWith(physicalLocation(directory))) {
            "Generated output leaves selected root '$directory': '$candidate'"
        }
        return candidate
    }

    /** Resolves an existing ancestor, then appends the not-yet-created suffix without writing. */
    private fun physicalLocation(path: Path): Path {
        var existing = path
        while (!Files.exists(existing, NOFOLLOW_LINKS)) {
            existing = requireNotNull(existing.parent) { "No existing ancestor for generated output '$path'" }
        }
        require(existing == path || Files.isDirectory(existing)) {
            "Generated output parent is not a directory: '$existing'"
        }
        return existing.toRealPath().resolve(existing.relativize(path)).normalize()
    }
}
