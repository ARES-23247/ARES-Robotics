package com.areslib.codegen

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Validates an entire disposable source set before changing any of its files or manifest. */
internal object GeneratedSourceSetSynchronizer {
    fun sync(root: Path, expected: Map<String, String>, checkOnly: Boolean, manifestName: String) {
        val directory = root.toAbsolutePath().normalize()
        val manifest = resolve(directory, manifestName)
        val destinations = mutableSetOf<Path>()
        val normalized = expected.entries.associate { (relative, content) ->
            val path = resolve(directory, relative)
            require(path != manifest && destinations.add(path)) { "Generated paths collide at '$path'" }
            path to content
        }
        val plannedFiles = destinations + setOf(manifest)
        for (path in plannedFiles) {
            var parent = path.parent
            while (parent != null && parent.startsWith(directory)) {
                require(parent !in plannedFiles) { "Generated file is also an output directory: '$parent'" }
                parent = parent.parent
            }
        }
        val expectedManifest = normalized.keys.map { directory.relativize(it).toString().replace('\\', '/') }
            .sorted().joinToString("\n", postfix = if (normalized.isEmpty()) "" else "\n")
        val currentManifest = readFile(manifest).orEmpty()
        val previous = currentManifest.lineSequence().filter(String::isNotBlank).map { relative ->
            val path = resolve(directory, relative)
            require(path != manifest) { "Generated manifest cannot own itself: '$manifest'" }
            path
        }.toList()
        require(previous.distinct().size == previous.size) { "Generated manifest contains duplicate destinations at '$manifest'" }
        if (checkOnly) {
            require(currentManifest == expectedManifest) { "Generated file list is stale at '$directory'" }
            normalized.forEach { (path, content) ->
                require(readFile(path) == content) { "Generated source is stale at '$path'" }
            }
            return
        }

        val obsolete = previous.filterNot(normalized::containsKey)
        val current = (obsolete + normalized.keys).associateWith(::readFile)
        obsolete.forEach { path ->
            require(current[path] == null || owns(current.getValue(path)!!)) {
                "Refusing to delete protected source at '$path'"
            }
        }
        normalized.forEach { (path, content) ->
            val existing = current[path]
            require(existing == null || existing == content || path in previous && owns(existing)) {
                "Refusing to overwrite protected or unowned source at '$path'"
            }
        }
        // All ownership/path conflicts are checked before any deletion or replacement in this set.
        obsolete.forEach { Files.deleteIfExists(it) }
        normalized.forEach { (path, content) ->
            if (current[path] != content) GeneratedFileWriter.writeAtomically(path, content)
        }
        if (normalized.isEmpty()) Files.deleteIfExists(manifest)
        else if (currentManifest != expectedManifest) GeneratedFileWriter.writeAtomically(manifest, expectedManifest)
    }

    private fun owns(content: String): Boolean =
        content.lineSequence().firstOrNull()?.trimStart() == "// ARES OWNERSHIP: GENERATED - DO NOT EDIT"

    private fun readFile(path: Path): String? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Generated output collides with a non-file at '$path'" }
        return Files.readString(path)
    }

    private fun resolve(root: Path, relative: String): Path = GeneratedOutputPaths.resolve(root, relative)
}
