package com.ares.analytics.viewmodel

import com.ares.analytics.shared.models.League
import com.areslib.codegen.GeneratedSubsystemSourceSet
import com.areslib.codegen.SubsystemArtifactOwnership
import com.areslib.codegen.SubsystemKotlinCodegenTarget
import com.areslib.codegen.SubsystemKotlinGenerator
import com.areslib.codegen.SubsystemStarterChangeKind
import com.areslib.codegen.SubsystemStarterReconciler
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemSchema
import com.areslib.subsystem.isAresGenerated
import java.io.File

/**
 * Owns validation and generated-artifact preview planning for one subsystem Builder session.
 * The view model coordinates state; this class owns codegen and project-path interpretation.
 */
internal class SubsystemBuilderPreviewPlanner(
    private val league: League,
    private val platform: SubsystemPlatform,
    private val basePackage: String,
) {
    fun plan(
        state: SubsystemGeneratorState,
        external: List<SubsystemProblem> = state.problems.filter { it.path.startsWith("project:") },
    ): SubsystemGeneratorState {
        val document = state.draft?.document ?: return state.copy(previewFiles = emptyList(), problems = external)
        val validation = SubsystemSchema.validate(document).map {
            SubsystemProblem(SubsystemProblemSeverity.ERROR, it.path, it.message)
        } + projectConnectionProblems(document, state.documents)
        val generated = if (validation.isEmpty() && document.implementation.kind.isAresGenerated()) {
            val sourceFiles = SubsystemKotlinGenerator.generate(document, SubsystemKotlinCodegenTarget(platform, basePackage))
            val starterPlan = SubsystemStarterReconciler.plan(starterRoot(state).toPath(), sourceFiles)
            val starterChanges = starterPlan.changes.associateBy { it.relativePath }
            sourceFiles.map { file ->
                val destination = artifactDestination(file.relativePath, file.sourceSet, file.ownership)
                val existing = safeExistingFile(state, destination)?.takeIf(File::isFile)?.readText()
                val planned = starterChanges[file.relativePath.replace('\\', '/')]
                val change = when (planned?.kind) {
                    SubsystemStarterChangeKind.ADD -> SubsystemFileChange.CREATE
                    SubsystemStarterChangeKind.UNCHANGED -> SubsystemFileChange.UNCHANGED
                    SubsystemStarterChangeKind.REPLACE -> SubsystemFileChange.REPLACE_STARTER
                    SubsystemStarterChangeKind.PROTECTED -> SubsystemFileChange.PROTECTED_USER_OWNED
                    null -> when {
                        existing == null -> SubsystemFileChange.CREATE
                        existing == file.content -> SubsystemFileChange.UNCHANGED
                        file.ownership == SubsystemArtifactOwnership.USER_OWNED -> SubsystemFileChange.PROTECTED_USER_OWNED
                        else -> SubsystemFileChange.UPDATE_GENERATED
                    }
                }
                SubsystemPreviewFile(
                    path = file.relativePath,
                    sourceSet = file.sourceSet,
                    content = file.content,
                    artifact = file.artifact,
                    group = file.group,
                    ownership = file.ownership,
                    description = file.description,
                    moduleName = if (league == League.FTC) "ARES-FTC · :TeamCode" else "ARES-FRC · root",
                    projectRelativePath = destination,
                    change = change,
                    diff = planned?.diff?.takeIf(String::isNotBlank)?.let(::parseUnifiedDiff)
                        ?: existing?.takeIf { it != file.content }?.let { structuredLineDiff(it, file.content) }.orEmpty(),
                )
            }
        } else emptyList()
        val token = if (validation.isEmpty() && document.implementation.kind == SubsystemImplementationKind.GENERATED_STARTER) {
            val sources = SubsystemKotlinGenerator.generate(document, SubsystemKotlinCodegenTarget(platform, basePackage))
            SubsystemStarterReconciler.plan(starterRoot(state).toPath(), sources).confirmationToken
        } else null
        return state.copy(
            previewFiles = generated,
            starterConfirmationToken = token,
            problems = (external + validation + safetyWarnings(document))
                .distinctBy { Triple(it.severity, it.path, it.message) },
        )
    }

    private fun artifactDestination(
        relativePath: String,
        sourceSet: GeneratedSubsystemSourceSet,
        ownership: SubsystemArtifactOwnership,
    ): String {
        val packagePath = basePackage.replace('.', '/')
        val sourceKind = if (sourceSet == GeneratedSubsystemSourceSet.TEST) "test" else "main"
        val root = when {
            ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT && league == League.FTC ->
                "TeamCode/build/generated/ares/$sourceKind/kotlin/$packagePath"
            ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT ->
                "build/generated/ares/$sourceKind/kotlin/$packagePath"
            league == League.FTC && sourceSet == GeneratedSubsystemSourceSet.TEST -> "TeamCode/src/test/java/$packagePath"
            league == League.FTC -> "TeamCode/src/main/java/$packagePath"
            sourceSet == GeneratedSubsystemSourceSet.TEST -> "src/test/kotlin/$packagePath"
            else -> "src/main/kotlin/$packagePath"
        }
        return "$root/${relativePath.replace('\\', '/')}"
    }

    private fun safeExistingFile(state: SubsystemGeneratorState, projectRelativePath: String): File? {
        val root = File(state.projectPath).canonicalFile
        val candidate = File(root, projectRelativePath).canonicalFile
        return candidate.takeIf { it.toPath().startsWith(root.toPath()) }
    }

    private fun starterRoot(state: SubsystemGeneratorState): File {
        val relative = if (league == League.FTC) {
            "TeamCode/src/main/java/${basePackage.replace('.', '/')}"
        } else {
            "src/main/kotlin/${basePackage.replace('.', '/')}"
        }
        val root = File(state.projectPath).canonicalFile
        return File(root, relative).canonicalFile.also {
            require(it.toPath().startsWith(root.toPath())) { "Subsystem starter root escaped the project" }
        }
    }
}
