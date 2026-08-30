package com.areslib.codegen

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

enum class SubsystemStarterChangeKind { ADD, UNCHANGED, REPLACE, PROTECTED }

data class SubsystemStarterChange(
    val relativePath: String,
    val kind: SubsystemStarterChangeKind,
    val description: String,
    val currentSha256: String?,
    val proposedSha256: String,
    val diff: String,
)

data class SubsystemStarterPlan(
    val changes: List<SubsystemStarterChange>,
    val confirmationToken: String?,
) {
    val hasReplacements: Boolean get() = changes.any { it.kind == SubsystemStarterChangeKind.REPLACE }

    fun render(): String = buildString {
        appendLine("ARES subsystem starter plan")
        changes.forEach { change ->
            appendLine("${change.kind} ${change.relativePath} — ${change.description}")
            if (change.diff.isNotBlank()) append(change.diff.prependIndent("  ")).append('\n')
        }
        confirmationToken?.let { appendLine("REPLACEMENT CONFIRMATION TOKEN: $it") }
    }.trimEnd()
}

/** Plans and applies editable starters without ever silently replacing user-owned source. */
object SubsystemStarterReconciler {
    fun plan(root: Path, files: Collection<GeneratedSubsystemFile>): SubsystemStarterPlan {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val starters = files.filter { it.ownership == SubsystemArtifactOwnership.GENERATED_STARTER }
            .sortedBy { it.relativePath }
        val changes = starters.map { file ->
            val path = safePath(normalizedRoot, file.relativePath)
            val current = path.takeIf(Files::isRegularFile)?.let(Files::readString)
            val kind = when {
                current == null -> SubsystemStarterChangeKind.ADD
                current == file.content -> SubsystemStarterChangeKind.UNCHANGED
                current.lineSequence().firstOrNull() == "// ARES OWNERSHIP: GENERATED STARTER" ->
                    SubsystemStarterChangeKind.REPLACE
                else -> SubsystemStarterChangeKind.PROTECTED
            }
            SubsystemStarterChange(
                file.relativePath.replace('\\', '/'),
                kind,
                file.description,
                current?.let(::sha256),
                sha256(file.content),
                when (kind) {
                    SubsystemStarterChangeKind.UNCHANGED -> ""
                    SubsystemStarterChangeKind.ADD -> unifiedDiff(emptyList(), file.content.lines())
                    SubsystemStarterChangeKind.REPLACE -> unifiedDiff(requireNotNull(current).lines(), file.content.lines())
                    SubsystemStarterChangeKind.PROTECTED ->
                        "Protected existing source is not eligible for generated replacement."
                },
            )
        }
        val replacements = changes.filter { it.kind == SubsystemStarterChangeKind.REPLACE }
        val token = replacements.takeIf(List<*>::isNotEmpty)?.joinToString("\n") {
            "${it.relativePath}:${it.currentSha256}:${it.proposedSha256}"
        }?.let(::sha256)
        return SubsystemStarterPlan(changes, token)
    }

    fun apply(
        root: Path,
        files: Collection<GeneratedSubsystemFile>,
        confirmationToken: String? = null,
    ): SubsystemStarterPlan {
        val plan = plan(root, files)
        val protected = plan.changes.filter { it.kind == SubsystemStarterChangeKind.PROTECTED }
        require(protected.isEmpty()) {
            "Generated starters collide with protected user-owned source: ${protected.joinToString { it.relativePath }}"
        }
        if (plan.hasReplacements) {
            require(confirmationToken != null && confirmationToken == plan.confirmationToken) {
                "Existing generated starters differ from the proposal. Review the structured diff and supply the exact replacement token.\n${plan.render()}"
            }
        }
        val byPath = files.filter { it.ownership == SubsystemArtifactOwnership.GENERATED_STARTER }
            .associateBy { it.relativePath.replace('\\', '/') }
        plan.changes.filter { it.kind != SubsystemStarterChangeKind.UNCHANGED }.forEach { change ->
            val source = requireNotNull(byPath[change.relativePath])
            val path = safePath(root.toAbsolutePath().normalize(), change.relativePath)
            GeneratedFileWriter.writeAtomically(path, source.content)
        }
        return plan
    }

    fun requirePresent(root: Path, files: Collection<GeneratedSubsystemFile>) {
        val missing = plan(root, files).changes.filter { it.kind == SubsystemStarterChangeKind.ADD }
        require(missing.isEmpty()) {
            "Editable subsystem starters are missing: ${missing.joinToString { it.relativePath }}. " +
                "Run the explicit generateSubsystemStarters task, review its output, and commit the user-owned files."
        }
    }

    private fun safePath(root: Path, relative: String): Path {
        val path = root.resolve(relative).normalize()
        require(relative.isNotBlank() && path.startsWith(root)) { "Invalid subsystem starter path '$relative'" }
        return path
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unifiedDiff(before: List<String>, after: List<String>): String {
        if (before == after) return ""
        val prefix = before.indices.takeWhile { it < after.size && before[it] == after[it] }.count()
        var suffix = 0
        while (suffix < before.size - prefix && suffix < after.size - prefix &&
            before[before.lastIndex - suffix] == after[after.lastIndex - suffix]
        ) suffix++
        val oldChanged = before.subList(prefix, before.size - suffix)
        val newChanged = after.subList(prefix, after.size - suffix)
        return buildString {
            appendLine("@@ line ${prefix + 1} @@")
            oldChanged.forEach { appendLine("-$it") }
            newChanged.forEach { appendLine("+$it") }
        }.trimEnd()
    }
}
