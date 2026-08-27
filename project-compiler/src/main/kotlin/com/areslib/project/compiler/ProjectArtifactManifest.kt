package com.areslib.project.compiler

import com.areslib.project.schema.AresControllerTarget
import com.areslib.project.schema.AresSimulatorTarget
import com.areslib.project.schema.ProjectId
import com.areslib.simulation.SimulationProductId
import java.security.MessageDigest

@JvmInline
value class ProjectArtifactId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9._-]{0,159}"))) { "Invalid generated artifact ID '$value'" }
    }
}

enum class ProjectArtifactSourceSet { MAIN, TEST, METADATA }

enum class ProjectArtifactOwnership { USER_OWNED, GENERATED_STARTER, GENERATED_DO_NOT_EDIT }

enum class ProjectArtifactKind {
    PROJECT_RUNTIME,
    PROJECT_VERIFICATION,
    SUBSYSTEM_DOMAIN,
    SUBSYSTEM_CONTROL,
    SUBSYSTEM_HARDWARE,
    SUBSYSTEM_SIMULATION,
    SUBSYSTEM_PLUMBING,
    SUBSYSTEM_VERIFICATION,
    DRIVEBASE_CONFIG,
    DRIVEBASE_RUNTIME,
    TUNING_CONFIG,
    SUPERSTRUCTURE_RUNTIME,
    SUPERSTRUCTURE_REGISTRY,
    VERIFICATION_MANIFEST,
}

/** Content-free artifact plan produced before any filesystem mutation. */
data class ProjectArtifactPlan(
    val id: ProjectArtifactId,
    val relativePath: String,
    val sourceSet: ProjectArtifactSourceSet,
    val ownership: ProjectArtifactOwnership,
    val kind: ProjectArtifactKind,
    val description: String,
) {
    init {
        require(relativePath.isNotBlank()) { "Generated artifact path cannot be blank" }
        val normalized = relativePath.replace('\\', '/')
        require(!normalized.startsWith('/') && normalized.split('/').none { it == ".." }) {
            "Generated artifact path must remain relative: $relativePath"
        }
        require(description.isNotBlank()) { "Generated artifact '${id.value}' requires a description" }
    }
}

/** Hash evidence for one rendered artifact. */
data class ProjectArtifactManifestEntry(
    val id: ProjectArtifactId,
    val relativePath: String,
    val sourceSet: ProjectArtifactSourceSet,
    val ownership: ProjectArtifactOwnership,
    val kind: ProjectArtifactKind,
    val contentSha256: String,
) {
    init {
        require(contentSha256.matches(Regex("[0-9a-f]{64}"))) { "Artifact content hash must be SHA-256" }
    }
}

data class ProjectVerificationManifest(
    val schemaVersion: Int = 2,
    val compilerIrVersion: Int,
    val projectId: ProjectId,
    val controllerTarget: AresControllerTarget,
    val simulatorTarget: AresSimulatorTarget,
    val simulationProduct: SimulationProductId,
    val canonicalProjectSha256: String,
    val artifacts: List<ProjectArtifactManifestEntry>,
    val manifestSha256: String,
)

object ProjectVerificationManifestBuilder {
    @JvmStatic
    fun build(
        project: RobotProjectIr,
        entries: Collection<ProjectArtifactManifestEntry>,
    ): ProjectVerificationManifest {
        val sorted = entries.sortedBy { it.relativePath.replace('\\', '/') }
        require(sorted.map { it.id }.distinct().size == sorted.size) { "Generated artifact IDs must be unique" }
        require(sorted.map { it.relativePath.replace('\\', '/') }.distinct().size == sorted.size) {
            "Generated artifact paths must be unique"
        }
        val unsigned = ProjectVerificationManifest(
            compilerIrVersion = project.irVersion,
            projectId = project.projectId,
            controllerTarget = project.target.controller,
            simulatorTarget = project.target.simulator,
            simulationProduct = project.simulationProduct,
            canonicalProjectSha256 = project.canonicalProjectSha256,
            artifacts = sorted,
            manifestSha256 = "",
        )
        return unsigned.copy(manifestSha256 = sha256(ProjectVerificationManifestCodec.encode(unsigned, includeHash = false)))
    }
}

/** Minimal deterministic JSON codec so build evidence has no renderer-specific dependency. */
object ProjectVerificationManifestCodec {
    @JvmStatic
    fun encode(manifest: ProjectVerificationManifest): String = encode(manifest, includeHash = true)

    internal fun encode(manifest: ProjectVerificationManifest, includeHash: Boolean): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": ").append(manifest.schemaVersion).append(",\n")
        append("  \"compilerIrVersion\": ").append(manifest.compilerIrVersion).append(",\n")
        append("  \"projectId\": ").append(manifest.projectId.value.json()).append(",\n")
        append("  \"controllerTarget\": ").append(manifest.controllerTarget.name.json()).append(",\n")
        append("  \"simulatorTarget\": ").append(manifest.simulatorTarget.name.json()).append(",\n")
        append("  \"simulationProduct\": ").append(manifest.simulationProduct.stableId.json()).append(",\n")
        append("  \"canonicalProjectSha256\": ").append(manifest.canonicalProjectSha256.json()).append(",\n")
        append("  \"artifacts\": [")
        if (manifest.artifacts.isNotEmpty()) append('\n')
        manifest.artifacts.forEachIndexed { index, entry ->
            append("    {\"id\": ").append(entry.id.value.json())
            append(", \"path\": ").append(entry.relativePath.replace('\\', '/').json())
            append(", \"sourceSet\": ").append(entry.sourceSet.name.json())
            append(", \"ownership\": ").append(entry.ownership.name.json())
            append(", \"kind\": ").append(entry.kind.name.json())
            append(", \"sha256\": ").append(entry.contentSha256.json()).append('}')
            if (index != manifest.artifacts.lastIndex) append(',')
            append('\n')
        }
        append("  ]")
        if (includeHash) append(",\n  \"manifestSha256\": ").append(manifest.manifestSha256.json())
        append("\n}\n")
    }
}

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun String.json(): String = buildString(length + 2) {
    append('"')
    this@json.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
