package com.ares.analytics.viewmodel.subsystem

import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.SubsystemBuilderStage
import com.ares.analytics.viewmodel.SubsystemEditorDraft
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemImplementationDocument
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemSimulationDocument
import com.areslib.subsystem.SubsystemSimulationSupport
import com.areslib.subsystem.SubsystemSourceOwnership
import com.areslib.subsystem.SubsystemTeachingDocument
import com.areslib.subsystem.SubsystemTeachingLevel
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.supportsPlatform
import com.ares.analytics.viewmodel.withAvailableTemplateConnections
import java.util.concurrent.atomic.AtomicLong

internal class SubsystemCreationOps(
    private val league: League,
    private val platform: SubsystemPlatform,
    private val basePackage: String,
    private val aiProposalGeneration: AtomicLong,
    private val getState: () -> SubsystemGeneratorState,
    private val updateState: ((SubsystemGeneratorState) -> SubsystemGeneratorState) -> Unit,
    private val revalidate: (SubsystemGeneratorState) -> SubsystemGeneratorState,
    private val blockDraftReplacement: () -> Boolean,
) {
    fun newSubsystem(template: SubsystemTemplate = getState().selectedTemplate) {
        if (blockDraftReplacement()) return
        require(template.supportsPlatform(platform)) { "${template.name} is not supported for $platform projects" }
        aiProposalGeneration.incrementAndGet()
        val used = getState().documents.mapTo(hashSetOf()) { it.documentId }
        var suffix = 1
        var id = "new-subsystem"
        while (id in used) id = "new-subsystem-${++suffix}"
        val name = if (suffix == 1) "NewSubsystem" else "NewSubsystem$suffix"
        val document = SubsystemTemplates.create(template, id, name, platform)
            .withAvailableTemplateConnections(getState().documents)
        updateState { current ->
            revalidate(
                current.copy(
                    documents = current.documents + document,
                    selectedDocumentId = document.documentId,
                    draft = SubsystemEditorDraft(document),
                    selectedHardwareUid = null,
                    selectedFieldUid = null,
                    selectedLoopUid = null,
                    selectedTuningParameterUid = null,
                    activeStage = SubsystemBuilderStage.PURPOSE,
                    visitedStages = setOf(SubsystemBuilderStage.PURPOSE),
                    selectedTemplate = document.template,
                    dirty = true,
                    status = "New ${template.name.lowercase().replace('_', ' ')} draft created.",
                    aiProposalInProgress = false,
                    aiProposal = null,
                    aiProposalError = null,
                    showTemplatePicker = false,
                )
            )
        }
    }

    fun applyTemplate(template: SubsystemTemplate) {
        require(template.supportsPlatform(platform)) { "${template.name} is not supported for $platform projects" }
        val currentDocument = getState().draft?.document ?: return
        val templateDocument = SubsystemTemplates.create(
            template = template,
            documentId = currentDocument.documentId,
            kotlinTypeName = currentDocument.kotlinTypeName,
            platform = currentDocument.platform,
            displayName = currentDocument.displayName,
        ).withAvailableTemplateConnections(
            getState().documents.filterNot { it.documentId == currentDocument.documentId },
        ).copy(
            revision = currentDocument.revision,
            parentContentHash = currentDocument.parentContentHash,
            uid = currentDocument.uid,
        )

        updateState { current ->
            val draft = current.draft ?: return@updateState current
            revalidate(
                current.copy(
                    draft = draft.edit { templateDocument },
                    selectedHardwareUid = null,
                    selectedFieldUid = null,
                    selectedLoopUid = null,
                    selectedTuningParameterUid = null,
                    selectedTemplate = template,
                    dirty = true,
                    status = "Applied ${template.name.lowercase().replace('_', ' ')} starter template.",
                )
            )
        }
    }

    fun registerHandAuthoredSubsystem() {
        if (blockDraftReplacement()) return
        aiProposalGeneration.incrementAndGet()
        val used = getState().documents.mapTo(hashSetOf()) { it.documentId }
        var suffix = 1
        var id = "existing-subsystem"
        while (id in used) id = "existing-subsystem-${++suffix}"
        val name = if (suffix == 1) "ExistingSubsystem" else "ExistingSubsystem$suffix"
        val safeId = id.replace('-', '_')
        val packageName = "$basePackage.$safeId"
        val sourceRoot = when (league) {
            League.FTC -> "TeamCode/src/main/java"
            League.FRC -> "src/main/kotlin"
            League.XRP -> "src"
        }
        val implementation = if (league == League.XRP) {
            SubsystemImplementationDocument(
                kind = SubsystemImplementationKind.HAND_AUTHORED,
                ownership = SubsystemSourceOwnership.USER_OWNED,
                sourceFiles = listOf("extensions/$safeId.py"),
                pythonModuleName = "extensions.$safeId",
                pythonFactoryName = "create_subsystem",
                pythonSimulationFactoryName = "create_simulated_subsystem",
                simulation = SubsystemSimulationDocument(SubsystemSimulationSupport.HAND_AUTHORED_SIMULATOR),
                teaching = SubsystemTeachingDocument(
                    level = SubsystemTeachingLevel.INTERMEDIATE,
                    summary = "Team-owned Python subsystem registered explicitly with ARES.",
                ),
            )
        } else {
            SubsystemImplementationDocument(
                kind = SubsystemImplementationKind.HAND_AUTHORED,
                ownership = SubsystemSourceOwnership.USER_OWNED,
                modulePath = if (league == League.FTC) ":TeamCode" else ":",
                sourceFiles = listOf("$sourceRoot/${packageName.replace('.', '/')}/${name}Subsystem.kt"),
                subsystemClassName = "$packageName.${name}Subsystem",
                ioContractClassName = "$packageName.${name}IO",
                hardwareAdapterClassName = "$packageName.${if (league == League.FTC) "Ftc" else "Frc"}${name}IO",
                simulation = SubsystemSimulationDocument(SubsystemSimulationSupport.UNAVAILABLE),
                teaching = SubsystemTeachingDocument(
                    level = SubsystemTeachingLevel.INTERMEDIATE,
                    summary = "Existing team-owned subsystem registered with ARES.",
                ),
            )
        }
        val document = SubsystemTemplates.create(SubsystemTemplate.ADVANCED_CUSTOM, id, name, platform).copy(
            generateMockIo = false,
            generateTest = false,
            implementation = implementation,
        )
        updateState { current ->
            revalidate(
                current.copy(
                    documents = current.documents + document,
                    selectedDocumentId = id,
                    draft = SubsystemEditorDraft(document),
                    selectedHardwareUid = null,
                    selectedFieldUid = null,
                    selectedLoopUid = null,
                    selectedTuningParameterUid = document.tuningParameters.firstOrNull()?.uid,
                    activeStage = SubsystemBuilderStage.PURPOSE,
                    visitedStages = setOf(SubsystemBuilderStage.PURPOSE),
                    selectedTemplate = SubsystemTemplate.ADVANCED_CUSTOM,
                    dirty = true,
                    status = "Hand-authored subsystem registration created. Review its source and runtime contract.",
                    aiProposalInProgress = false,
                    aiProposal = null,
                    aiProposalError = null,
                    showTemplatePicker = false,
                )
            )
        }
    }
}
