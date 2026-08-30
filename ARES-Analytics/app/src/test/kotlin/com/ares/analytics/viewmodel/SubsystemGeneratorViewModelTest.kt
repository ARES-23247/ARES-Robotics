package com.ares.analytics.viewmodel

import com.ares.analytics.service.AresGenerationState
import com.ares.analytics.service.AresProjectGenerator
import com.ares.analytics.service.SubsystemDesignAssistant
import com.ares.analytics.service.SubsystemDesignProposal
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.help.toAcademySubsystemSnapshot
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.persistence.CapabilityCatalogProjectRepository
import com.ares.analytics.service.project.persistence.SubsystemProjectRepository
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemContinuousInputDocument
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.SubsystemHardwareConnection
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemMeasurementDocument
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemSafetyDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import com.areslib.codegen.SubsystemStarterPlan
import com.areslib.codegen.SubsystemArtifactGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubsystemGeneratorViewModelTest {
    @Test
    fun `new FTC templates receive non-colliding hardware map names`() {
        val root = Files.createTempDirectory("ares-subsystem-unique-ftc-addresses").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)

        viewModel.newSubsystem(SubsystemTemplate.SIMPLE_ACTUATOR)
        val firstName = viewModel.state.value.draft!!.document.hardware.single().connection.hardwareMapName
        viewModel.save()
        viewModel.newSubsystem(SubsystemTemplate.FLYWHEEL_SHOOTER)
        val second = viewModel.state.value.draft!!.document
        val secondName = second.hardware.single().connection.hardwareMapName

        assertEquals("motor", firstName)
        assertTrue(secondName != firstName)
        assertTrue(secondName!!.contains("new_subsystem_2_motor"))
        assertTrue(viewModel.state.value.canSave)
        viewModel.close()
    }

    @Test
    fun `cross subsystem address collision is rejected in the builder before save`() {
        val root = Files.createTempDirectory("ares-subsystem-address-collision").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)

        viewModel.newSubsystem(SubsystemTemplate.SIMPLE_ACTUATOR)
        viewModel.save()
        viewModel.newSubsystem(SubsystemTemplate.FLYWHEEL_SHOOTER)
        viewModel.edit { document ->
            document.copy(hardware = document.hardware.map { device ->
                device.copy(connection = device.connection.copy(hardwareMapName = "motor"))
            })
        }

        assertFalse(viewModel.state.value.canSave)
        assertTrue(viewModel.state.value.problems.any {
            it.severity == SubsystemProblemSeverity.ERROR && it.message.contains("already owned")
        })
        viewModel.close()
    }

    @Test
    fun `FRC GUI templates reserve distinct mechanism CAN IDs outside the common drivetrain range`() {
        val root = Files.createTempDirectory("ares-subsystem-unique-frc-addresses").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FRC)

        viewModel.newSubsystem(SubsystemTemplate.DUAL_MOTOR_FOLLOWER)
        val firstIds = viewModel.state.value.draft!!.document.hardware.mapNotNull { it.connection.canId }
        viewModel.save()
        viewModel.newSubsystem(SubsystemTemplate.FLYWHEEL_SHOOTER)
        val secondIds = viewModel.state.value.draft!!.document.hardware.mapNotNull { it.connection.canId }

        assertTrue(firstIds.all { it in 20..62 })
        assertEquals(firstIds.size, firstIds.distinct().size)
        assertTrue(secondIds.none { it in firstIds })
        assertTrue(viewModel.state.value.canSave)
        viewModel.close()
    }

    @Test
    fun `academy evidence follows the real homed mechanism draft review and save`() {
        val root = Files.createTempDirectory("ares-subsystem-academy").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)

        viewModel.newSubsystem(SubsystemTemplate.HOMED_MECHANISM)
        viewModel.edit { it.copy(displayName = "Practice Lift", kotlinTypeName = "PracticeLift") }
        var evidence = viewModel.state.value.toAcademySubsystemSnapshot()
        assertTrue(evidence.hasPositionMechanismDraft)
        assertFalse(evidence.hasNaturalStateContract)
        assertFalse(evidence.hasCompleteSafetyContract)
        assertFalse(evidence.hasSimulationAndVerification)

        viewModel.selectStage(SubsystemBuilderStage.STATE_AND_BEHAVIOR)
        viewModel.selectStage(SubsystemBuilderStage.SAFETY)
        viewModel.selectStage(SubsystemBuilderStage.SIMULATION_AND_TESTING)
        evidence = viewModel.state.value.toAcademySubsystemSnapshot()
        assertTrue(evidence.hasNaturalStateContract)
        assertTrue(evidence.hasCompleteSafetyContract)
        assertTrue(evidence.hasSimulationAndVerification)
        assertFalse(evidence.isReviewingGeneratedArtifacts)
        assertFalse(evidence.hasSavedCanonicalDescriptor)

        viewModel.selectStage(SubsystemBuilderStage.REVIEW)
        evidence = viewModel.state.value.toAcademySubsystemSnapshot()
        assertTrue(evidence.isReviewingGeneratedArtifacts)

        viewModel.save()
        evidence = viewModel.state.value.toAcademySubsystemSnapshot()
        assertTrue(evidence.hasSavedCanonicalDescriptor)
        viewModel.close()
    }

    @Test
    fun `register existing Kotlin creates protected hand-authored metadata without starter previews`() {
        val root = Files.createTempDirectory("ares-hand-authored-registration").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)

        viewModel.registerHandAuthoredSubsystem()

        val state = viewModel.state.value
        assertEquals(
            com.areslib.subsystem.SubsystemImplementationKind.HAND_AUTHORED,
            state.draft?.document?.implementation?.kind,
        )
        assertEquals(com.areslib.subsystem.SubsystemSourceOwnership.USER_OWNED, state.draft?.document?.implementation?.ownership)
        assertEquals(":TeamCode", state.draft?.document?.implementation?.modulePath)
        assertTrue(state.draft?.document?.implementation?.sourceFiles.orEmpty().all { it.startsWith("TeamCode/src/main/java/") })
        assertTrue(state.previewFiles.isEmpty(), "Hand-authored source must never enter starter replacement preview")
        assertTrue(state.dirty)

        viewModel.close()
    }

    @Test
    fun `hand-authored subsystem can declare reorder and delete tuning metadata without generating source`() {
        val root = Files.createTempDirectory("ares-hand-authored-tuning").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.registerHandAuthoredSubsystem()

        viewModel.addTuningParameter()
        val first = viewModel.state.value.draft!!.document.tuningParameters.single()
        viewModel.addTuningParameter()
        val second = viewModel.state.value.draft!!.document.tuningParameters.last()
        viewModel.moveTuningParameter(second.uid, -1)

        var state = viewModel.state.value
        assertEquals(SubsystemBuilderStage.PURPOSE, state.activeStage)
        assertEquals(listOf(second.uid, first.uid), state.draft!!.document.tuningParameters.map { it.uid })
        assertTrue(state.previewFiles.isEmpty(), "Tuning metadata must not create hand-authored Kotlin starters")

        viewModel.navigateToProblem("tuningParameters[0].key")
        assertEquals(SubsystemBuilderStage.TUNING, viewModel.state.value.activeStage)
        assertEquals(second.uid, viewModel.state.value.selectedTuningParameterUid)
        viewModel.removeTuningParameter(second.uid)
        state = viewModel.state.value
        assertEquals(listOf(first.uid), state.draft!!.document.tuningParameters.map { it.uid })
        viewModel.close()
    }

    @Test
    fun `guided builder stages advance deterministically and remain directly selectable`() {
        val root = Files.createTempDirectory("ares-subsystem-stages").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem()

        assertEquals(SubsystemBuilderStage.PURPOSE, viewModel.state.value.activeStage)
        viewModel.previousStage()
        assertEquals(SubsystemBuilderStage.PURPOSE, viewModel.state.value.activeStage)

        viewModel.nextStage()
        assertEquals(SubsystemBuilderStage.HARDWARE, viewModel.state.value.activeStage)
        viewModel.selectStage(SubsystemBuilderStage.SIMULATION_AND_TESTING)
        assertEquals(SubsystemBuilderStage.SIMULATION_AND_TESTING, viewModel.state.value.activeStage)
        viewModel.nextStage()
        assertEquals(SubsystemBuilderStage.REVIEW, viewModel.state.value.activeStage)
        viewModel.nextStage()
        assertEquals(SubsystemBuilderStage.REVIEW, viewModel.state.value.activeStage)

        viewModel.close()
    }

    @Test
    fun `new project previews DSL saves revision and invokes offline generation`() {
        val root = Files.createTempDirectory("ares-subsystem-editor").toFile()
        val generator = FakeGenerator()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC, projectGenerator = generator)
        viewModel.newSubsystem()

        val initial = viewModel.state.value
        assertTrue(initial.dirty)
        assertTrue(initial.previewFiles.any { it.content.contains("val document = subsystem(") })
        assertTrue(initial.canSave)

        viewModel.generate()

        val saved = viewModel.state.value
        assertFalse(saved.dirty)
        assertEquals(1, saved.draft?.document?.revision)
        assertEquals(root.canonicalPath, generator.projectPath)
        assertEquals(League.FTC, generator.league)
        assertTrue(root.resolve(".ares/subsystems/new-subsystem.aressubsystem").isFile)

        CapabilityCatalogProjectRepository().save(
            root.path,
            CapabilityCatalogDocument(projectId = "test-project"),
        )
        val mergedActions = AresProjectDocuments().load(root.path).query.actions
        assertTrue(mergedActions.any { it.key == "subsystem.new-subsystem.set.target" })
        viewModel.close()
    }

    @Test
    fun `repository creates immutable revisions for subsystem DSL documents`() {
        val root = Files.createTempDirectory("ares-subsystem-revisions").toFile()
        val repository = SubsystemProjectRepository()
        val original = minimalSubsystem("Indexer")
        val first = repository.save(root.path, original)
        val second = repository.save(root.path, original.copy(displayName = "Indexer V2"))

        assertEquals(1, first.document.revision)
        assertEquals(2, second.document.revision)
        assertEquals(2, repository.listRevisions(root.path, original.documentId).size)
        assertEquals("Indexer V2", repository.load(root.path, original.documentId).displayName)
    }

    @Test
    fun `saved subsystem removal is hash bound recoverable and preserves source`() {
        val root = Files.createTempDirectory("ares-subsystem-removal").toFile()
        val generator = FakeGenerator()
        val source = root.resolve("TeamCode/src/main/java/example/IndexerSubsystem.kt").apply {
            parentFile.mkdirs()
            writeText("// ARES OWNERSHIP: USER-OWNED\nclass IndexerSubsystem")
        }
        val repository = SubsystemProjectRepository()
        val saved = repository.save(root.path, minimalSubsystem("Indexer"))
        val viewModel = SubsystemGeneratorViewModel(
            root.path,
            League.FTC,
            documents = AresProjectDocuments(subsystems = repository),
            projectGenerator = generator,
        )

        viewModel.requestRemoveSubsystem()
        val request = viewModel.state.value.pendingRemoval
        assertTrue(request?.persisted == true)
        assertEquals(saved.contentHash, request?.contentHash)
        assertTrue(request?.recoveryPath?.startsWith(".ares/recovery/subsystems/indexer/") == true)

        viewModel.confirmRemoveSubsystem()

        assertFalse(root.resolve(".ares/subsystems/indexer.aressubsystem").exists())
        assertTrue(root.resolve(request!!.recoveryPath!!).isFile)
        assertTrue(source.isFile, "Removing metadata must never remove user-owned or starter source")
        assertTrue(viewModel.state.value.documents.isEmpty())
        assertNull(viewModel.state.value.draft)
        assertEquals(request.recoveryPath, viewModel.state.value.recentRecovery?.recoveryPath)
        assertEquals(root.canonicalPath, generator.projectPath)

        viewModel.restoreRemovedSubsystem()

        assertTrue(root.resolve(".ares/subsystems/indexer.aressubsystem").isFile)
        assertFalse(root.resolve(request.recoveryPath!!).exists())
        assertTrue(source.isFile, "Restoring metadata must never replace user-owned or starter source")
        assertEquals("indexer", viewModel.state.value.draft?.document?.documentId)
        assertNull(viewModel.state.value.recentRecovery)
        assertTrue(viewModel.state.value.status.orEmpty().contains("Restored Indexer"))
        viewModel.close()
    }

    @Test
    fun `subsystem recovery refuses to overwrite a replacement descriptor`() {
        val root = Files.createTempDirectory("ares-subsystem-recovery-conflict").toFile()
        val repository = SubsystemProjectRepository()
        repository.save(root.path, minimalSubsystem("Indexer"))
        val viewModel = SubsystemGeneratorViewModel(
            root.path,
            League.FTC,
            documents = AresProjectDocuments(subsystems = repository),
        )

        viewModel.requestRemoveSubsystem()
        val recoveryPath = viewModel.state.value.pendingRemoval!!.recoveryPath!!
        viewModel.confirmRemoveSubsystem()
        repository.save(root.path, minimalSubsystem("Replacement"))

        viewModel.restoreRemovedSubsystem()

        assertEquals("Replacement", repository.load(root.path, "indexer").displayName)
        assertTrue(root.resolve(recoveryPath).isFile)
        assertTrue(viewModel.state.value.status.orEmpty().contains("already exists", ignoreCase = true))
        assertTrue(viewModel.state.value.recentRecovery != null)
        viewModel.close()
    }

    @Test
    fun `subsystem removal refuses a descriptor changed after review`() {
        val root = Files.createTempDirectory("ares-subsystem-stale-removal").toFile()
        val repository = SubsystemProjectRepository()
        repository.save(root.path, minimalSubsystem("Indexer"))
        val viewModel = SubsystemGeneratorViewModel(
            root.path,
            League.FTC,
            documents = AresProjectDocuments(subsystems = repository),
        )

        viewModel.requestRemoveSubsystem()
        repository.save(root.path, minimalSubsystem("Indexer").copy(displayName = "Changed elsewhere"))
        viewModel.confirmRemoveSubsystem()

        assertTrue(root.resolve(".ares/subsystems/indexer.aressubsystem").isFile)
        assertTrue(viewModel.state.value.status.orEmpty().contains("changed after review", ignoreCase = true))
        assertNull(viewModel.state.value.pendingRemoval)
        viewModel.close()
    }

    @Test
    fun `unsaved subsystem removal discards only the draft`() {
        val root = Files.createTempDirectory("ares-unsaved-subsystem-removal").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem()

        viewModel.requestRemoveSubsystem()
        assertFalse(viewModel.state.value.pendingRemoval!!.persisted)
        viewModel.confirmRemoveSubsystem()

        assertTrue(viewModel.state.value.documents.isEmpty())
        assertNull(viewModel.state.value.draft)
        assertFalse(root.resolve(".ares/subsystems").exists())
        viewModel.close()
    }

    @Test
    fun `empty project remains a valid drive-only project after reload`() {
        val root = Files.createTempDirectory("ares-drive-only-project").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)

        assertTrue(viewModel.state.value.documents.isEmpty())
        assertNull(viewModel.state.value.draft)
        assertFalse(viewModel.state.value.dirty)

        viewModel.newSubsystem()
        viewModel.requestRemoveSubsystem()
        viewModel.confirmRemoveSubsystem()
        viewModel.reload()

        assertTrue(viewModel.state.value.documents.isEmpty())
        assertNull(viewModel.state.value.draft)
        assertFalse(viewModel.state.value.dirty)
        viewModel.close()
    }

    @Test
    fun `capability template selection creates a safe explicit draft`() {
        val root = Files.createTempDirectory("ares-subsystem-template").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)

        viewModel.selectTemplate(SubsystemTemplate.HOMED_MECHANISM)
        viewModel.newSubsystem()

        val state = viewModel.state.value
        assertEquals(SubsystemTemplate.HOMED_MECHANISM, state.draft?.document?.template)
        assertEquals(SubsystemHomingMethod.DIGITAL_SENSOR, state.draft?.document?.safety?.homing?.method)
        assertTrue(state.draft?.document?.generateMockIo == true)
        assertTrue(state.draft?.document?.generateTest == true)
        assertFalse(state.generatedPlumbingExpanded)
        assertTrue(state.previewFiles.all { it.description.isNotBlank() })
        assertTrue(state.previewFiles.all { it.moduleName.isNotBlank() && it.projectRelativePath.isNotBlank() })
        assertEquals(
            SubsystemArtifactGroup.entries.toSet(),
            state.previewFiles.mapTo(linkedSetOf()) { it.group },
        )
        viewModel.close()
    }

    @Test
    fun `template picker creates the requested archetype and closes`() {
        val root = Files.createTempDirectory("ares-subsystem-picker").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)

        viewModel.setTemplatePickerVisible(true)
        viewModel.newSubsystem(SubsystemTemplate.SENSOR_ONLY_SUBSYSTEM)

        val state = viewModel.state.value
        assertFalse(state.showTemplatePicker)
        assertEquals(SubsystemTemplate.SENSOR_ONLY_SUBSYSTEM, state.draft?.document?.template)
        assertNull(state.selectedHardwareUid)
        viewModel.close()
    }

    @Test
    fun `builder rejects hardware without a generated adapter for the selected league`() {
        val ftcRoot = Files.createTempDirectory("ares-ftc-hardware-support").toFile()
        val frcRoot = Files.createTempDirectory("ares-frc-hardware-support").toFile()
        val ftc = SubsystemGeneratorViewModel(ftcRoot.path, League.FTC)
        val frc = SubsystemGeneratorViewModel(frcRoot.path, League.FRC)
        ftc.newSubsystem()
        frc.newSubsystem()

        assertFailsWith<IllegalArgumentException> { ftc.addHardware(SubsystemHardwareKind.SOLENOID) }
        assertFailsWith<IllegalArgumentException> { frc.addHardware(SubsystemHardwareKind.COLOR_SENSOR) }
        ftc.addHardware(SubsystemHardwareKind.IMU)
        frc.addHardware(SubsystemHardwareKind.SOLENOID)

        assertTrue(ftc.state.value.draft!!.document.hardware.any { it.kind == SubsystemHardwareKind.IMU })
        assertTrue(frc.state.value.draft!!.document.hardware.any { it.kind == SubsystemHardwareKind.SOLENOID })
        ftc.close()
        frc.close()
    }

    @Test
    fun `sandbox gains update the selected controller and reject nonfinite input`() {
        val root = Files.createTempDirectory("ares-subsystem-gains").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem()
        val loopId = viewModel.state.value.draft!!.document.controlLoops.single().loopId

        viewModel.applyControlLoopGains(loopId, 1.2, 0.3, 0.04, 0.5, 2.1, 0.7)

        val loop = viewModel.state.value.draft!!.document.controlLoops.single()
        assertEquals(1.2, loop.kP)
        assertEquals(0.3, loop.kI)
        assertEquals(0.04, loop.kD)
        assertEquals(0.5, loop.feedforward.kS)
        assertEquals(2.1, loop.feedforward.kV)
        assertEquals(0.7, loop.feedforward.kG)
        assertTrue(viewModel.state.value.dirty)
        assertFailsWith<IllegalArgumentException> {
            viewModel.applyControlLoopGains(loopId, Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        viewModel.close()
    }

    @Test
    fun `unsafe opt-outs are visible warnings without hiding structural errors`() {
        val root = Files.createTempDirectory("ares-subsystem-safety-warnings").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem()

        viewModel.edit { document ->
            document.copy(
                safety = document.safety.copy(
                    requiresConfigurationHealth = false,
                    latchOutputFaults = false,
                    requiresExplicitNeutralRecovery = false,
                    telemetryEnabled = false,
                    zeroAllocationPeriodic = false,
                )
            )
        }

        val warnings = viewModel.state.value.problems.filter { it.severity == SubsystemProblemSeverity.WARNING }
        assertTrue(warnings.any { it.path == "safety.requiresConfigurationHealth" })
        assertTrue(warnings.any { it.path == "safety.latchOutputFaults" })
        assertTrue(warnings.any { it.path == "safety.requiresExplicitNeutralRecovery" })
        assertTrue(warnings.any { it.path == "safety.telemetryEnabled" })
        assertTrue(warnings.any { it.path == "safety.zeroAllocationPeriodic" })
        viewModel.close()
    }

    @Test
    fun `one novice action saves the descriptor and creates missing starter files`() {
        val root = Files.createTempDirectory("ares-subsystem-save-and-create").toFile()
        val generator = FakeGenerator()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC, projectGenerator = generator)
        viewModel.newSubsystem()
        val draft = requireNotNull(viewModel.state.value.draft?.document)

        viewModel.generate()

        assertFalse(viewModel.state.value.dirty)
        assertTrue(root.resolve(".ares/subsystems/${draft.documentId}.aressubsystem").isFile)
        assertEquals(root.canonicalPath, generator.projectPath)
        assertEquals(League.FTC, generator.league)
        assertNull(generator.replacementToken)
        viewModel.close()
    }

    @Test
    fun `changed starter requires a structured diff and explicit confirmation`() {
        val root = Files.createTempDirectory("ares-subsystem-starter-diff").toFile()
        val generator = FakeGenerator()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC, projectGenerator = generator)
        viewModel.newSubsystem()
        viewModel.edit { document ->
            document.copy(
                implementation = document.implementation.copy(
                    kind = com.areslib.subsystem.SubsystemImplementationKind.GENERATED_STARTER,
                    ownership = com.areslib.subsystem.SubsystemSourceOwnership.GENERATED_STARTER,
                ),
            )
        }
        val starter = viewModel.state.value.previewFiles.first {
            it.ownership == com.areslib.codegen.SubsystemArtifactOwnership.GENERATED_STARTER
        }
        val existing = root.resolve(starter.projectRelativePath)
        existing.parentFile.mkdirs()
        val customized = starter.content.lines().toMutableList().also {
            it[1] = "// reviewed team customization"
        }.joinToString("\n")
        existing.writeText(customized)

        viewModel.edit { it.copy(description = "Force preview refresh") }
        viewModel.generate()

        val pending = viewModel.state.value.pendingStarterReplacements
        assertTrue(pending.any { it.path == starter.path })
        assertTrue(
            pending.flatMap { it.diff }.any { it.kind == SubsystemDiffLineKind.REMOVED },
            "Expected removed lines in ${pending.map { it.path to it.diff }}",
        )
        assertTrue(
            pending.flatMap { it.diff }.any { it.kind == SubsystemDiffLineKind.ADDED },
            "Expected added lines in ${pending.map { it.path to it.diff }}",
        )
        assertEquals(null, generator.projectPath)

        viewModel.confirmStarterReplacement()
        assertEquals(root.canonicalPath, generator.projectPath)
        assertEquals(viewModel.state.value.starterConfirmationToken, null)
        assertTrue(generator.replacementToken?.isNotBlank() == true)
        viewModel.close()
    }

    @Test
    fun `user-owned source without starter header is protected from replacement`() {
        val root = Files.createTempDirectory("ares-subsystem-user-owned").toFile()
        val generator = FakeGenerator()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC, projectGenerator = generator)
        viewModel.newSubsystem()
        viewModel.edit { document ->
            document.copy(
                implementation = document.implementation.copy(
                    kind = com.areslib.subsystem.SubsystemImplementationKind.GENERATED_STARTER,
                    ownership = com.areslib.subsystem.SubsystemSourceOwnership.GENERATED_STARTER,
                ),
            )
        }
        val starter = viewModel.state.value.previewFiles.first {
            it.ownership == com.areslib.codegen.SubsystemArtifactOwnership.GENERATED_STARTER
        }
        val existing = root.resolve(starter.projectRelativePath)
        existing.parentFile.mkdirs()
        existing.writeText("// ARES OWNERSHIP: USER-OWNED\nclass TeamMechanism\n")

        viewModel.edit { it.copy(description = "Refresh protection plan") }
        viewModel.generate()

        val state = viewModel.state.value
        assertTrue(state.hasProtectedUserOwnedConflict)
        assertTrue(state.pendingStarterReplacements.isEmpty())
        assertEquals(null, generator.projectPath)
        assertTrue(state.status.orEmpty().contains("USER-OWNED"))
        viewModel.close()
    }

    @Test
    fun `structured diff is deterministic and bounds unchanged context`() {
        val diff = structuredLineDiff("a\nb\nold\ny\nz", "a\nb\nnew\ny\nz", contextLines = 1)

        assertEquals(
            listOf(
                SubsystemDiffLine(SubsystemDiffLineKind.CONTEXT, "b"),
                SubsystemDiffLine(SubsystemDiffLineKind.REMOVED, "old"),
                SubsystemDiffLine(SubsystemDiffLineKind.ADDED, "new"),
                SubsystemDiffLine(SubsystemDiffLineKind.CONTEXT, "y"),
            ),
            diff,
        )
    }

    @Test
    fun `adding a motor scaffolds natural cached state and undo restores the prior document`() {
        val root = Files.createTempDirectory("ares-subsystem-natural-state").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem()
        val before = viewModel.state.value.draft?.document

        viewModel.addHardware(SubsystemHardwareKind.MOTOR)

        val edited = viewModel.state.value
        val fields = edited.draft?.document?.stateFields.orEmpty()
        assertTrue(fields.any { it.fieldId.endsWith("Position") })
        assertTrue(fields.any { it.fieldId.endsWith("Velocity") })
        assertTrue(fields.any { it.fieldId.endsWith("CurrentAmps") })
        assertTrue(fields.any { it.role == SubsystemFieldRole.TARGET })
        assertTrue(edited.canUndo)

        viewModel.undo()
        assertEquals(before, viewModel.state.value.draft?.document)
        assertTrue(viewModel.state.value.canRedo)
        viewModel.close()
    }

    @Test
    fun `controller rule can be documented and renamed without changing editor identity`() {
        val root = Files.createTempDirectory("ares-subsystem-controller-identity").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem()
        val original = viewModel.state.value.draft!!.document.controlLoops.first()

        viewModel.updateControlLoop(original.loopId) { it.copy(description = "Holds the elevator position") }
        viewModel.renameControlLoopId(original.loopId, "elevatorControl")

        val renamed = viewModel.state.value.draft!!.document.controlLoops.single { it.uid == original.uid }
        assertEquals("elevatorControl", renamed.loopId)
        assertEquals("Holds the elevator position", renamed.description)
        assertEquals(original.uid, renamed.uid)
        viewModel.undo()
        assertEquals(original.loopId, viewModel.state.value.draft!!.document.controlLoops.single { it.uid == original.uid }.loopId)
        viewModel.close()
    }

    @Test
    fun `builder refuses a second controller for an already controlled actuator`() {
        val root = Files.createTempDirectory("ares-subsystem-controller-owner").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem(SubsystemTemplate.POSITION_CONTROLLED_MECHANISM)
        val before = viewModel.state.value.draft!!.document.controlLoops

        viewModel.addControlLoop()

        val state = viewModel.state.value
        assertEquals(before, state.draft!!.document.controlLoops)
        assertTrue(state.status.orEmpty().contains("already has a controller", ignoreCase = true))
        viewModel.close()
    }

    @Test
    fun `changing a controller target clears incompatible feedback`() {
        val root = Files.createTempDirectory("ares-subsystem-controller-units").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem(SubsystemTemplate.ARM_PIVOT)
        val loop = viewModel.state.value.draft!!.document.controlLoops.single()
        viewModel.edit { document ->
            document.copy(stateFields = document.stateFields + SubsystemStateFieldDocument(
                fieldId = "linearTarget",
                displayName = "Linear target",
                type = SubsystemValueType.DOUBLE,
                role = SubsystemFieldRole.TARGET,
                unit = "m",
                defaultNumber = 0.0,
            ))
        }

        viewModel.changeControlLoopTarget(loop.loopId, "linearTarget")

        val changed = viewModel.state.value.draft!!.document.controlLoops.single()
        assertEquals("linearTarget", changed.targetFieldId)
        assertNull(changed.measurementFieldId)
        assertFalse(viewModel.state.value.canSave)
        viewModel.close()
    }

    @Test
    fun `strategy changes preserve only compatible continuous input and hysteresis settings`() {
        val root = Files.createTempDirectory("ares-subsystem-controller-modes").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem(SubsystemTemplate.ARM_PIVOT)
        val loopId = viewModel.state.value.draft!!.document.controlLoops.single().loopId
        viewModel.updateControlLoop(loopId) {
            it.copy(continuousInput = SubsystemContinuousInputDocument(enabled = true))
        }

        viewModel.changeControlLoopStrategy(loopId, SubsystemControlStrategy.POSITION_PID)
        assertTrue(viewModel.state.value.draft!!.document.controlLoops.single().continuousInput.enabled)

        viewModel.changeControlLoopStrategy(loopId, SubsystemControlStrategy.BANG_BANG)
        var changed = viewModel.state.value.draft!!.document.controlLoops.single()
        assertFalse(changed.continuousInput.enabled)
        viewModel.updateControlLoop(loopId) { it.copy(hysteresis = 0.05) }

        viewModel.changeControlLoopStrategy(loopId, SubsystemControlStrategy.VELOCITY_PID)
        changed = viewModel.state.value.draft!!.document.controlLoops.single()
        assertEquals(0.0, changed.hysteresis)
        assertFalse(changed.continuousInput.enabled)
        viewModel.close()
    }

    @Test
    fun `invalid AI proposal remains review only and cannot be applied`() {
        val root = Files.createTempDirectory("ares-subsystem-ai-invalid-").toFile()
        File(root, ".ares/subsystems").mkdirs()
        val assistant = SubsystemDesignAssistant { current, _ ->
            SubsystemDesignProposal(
                summary = "Unsafe incomplete proposal",
                explanations = emptyList(),
                candidate = current.copy(displayName = ""),
            )
        }
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC, designAssistant = assistant)
        viewModel.newSubsystem()
        val before = viewModel.state.value.draft!!.document

        viewModel.requestAiProposal("Make a mechanism")
        waitFor { !viewModel.state.value.aiProposalInProgress }

        val review = viewModel.state.value.aiProposal!!
        assertFalse(review.canApply)
        assertTrue(review.problems.any { it.severity == SubsystemProblemSeverity.ERROR })
        viewModel.applyAiProposal()
        assertEquals(before, viewModel.state.value.draft!!.document)
        assertTrue(viewModel.state.value.aiProposalError!!.contains("validation", ignoreCase = true))
        viewModel.close()
    }

    @Test
    fun `stall homing selection creates bounded current evidence and navigates safety errors`() {
        val root = Files.createTempDirectory("ares-subsystem-stall-homing").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem()
        viewModel.addHardware(SubsystemHardwareKind.MOTOR)

        viewModel.setHomingMethod(SubsystemHomingMethod.CURRENT_STALL)

        val homing = viewModel.state.value.draft?.document?.safety?.homing
        assertEquals(SubsystemHomingMethod.CURRENT_STALL, homing?.method)
        assertEquals(-2.0, homing?.searchOutput)
        assertEquals(250L, homing?.dwellMs)
        assertEquals(3_000L, homing?.timeoutMs)
        assertTrue(homing?.evidence.orEmpty().any { it.fieldId.endsWith("currentAmps", ignoreCase = true) })
        assertTrue(viewModel.state.value.draft?.document?.safety?.requiresCurrentMonitoring == true)

        viewModel.navigateToProblem("safety.homing.evidence")
        assertEquals(SubsystemBuilderStage.SAFETY, viewModel.state.value.activeStage)
        viewModel.close()
    }

    @Test
    fun `hardware reversal and follower direction remain separate and survive leader rename`() {
        val root = Files.createTempDirectory("ares-subsystem-follower-direction").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem()
        while (viewModel.state.value.draft!!.document.hardware.count { it.kind == SubsystemHardwareKind.MOTOR } < 2) {
            viewModel.addHardware(SubsystemHardwareKind.MOTOR)
        }
        val motors = viewModel.state.value.draft!!.document.hardware.filter { it.kind == SubsystemHardwareKind.MOTOR }
        val leader = motors.first()
        val follower = motors.last()

        viewModel.updateHardware(follower.hardwareId) { it.copy(inverted = true) }
        viewModel.setHardwareFollower(follower.hardwareId, leader.hardwareId, SubsystemFollowerTransform.INVERTED)

        var document = viewModel.state.value.draft!!.document
        assertTrue(document.hardware.single { it.uid == follower.uid }.inverted)
        assertEquals(
            SubsystemFollowerTransform.INVERTED,
            document.hardware.single { it.uid == follower.uid }.following?.transform,
        )
        assertTrue(document.controlLoops.none { it.actuatorId == follower.hardwareId })

        viewModel.renameHardwareId(leader.hardwareId, "primaryMotor")
        document = viewModel.state.value.draft!!.document
        assertEquals("primaryMotor", document.hardware.single { it.uid == follower.uid }.following?.leaderId)
        viewModel.close()
    }

    @Test
    fun `AI proposal is review only preserves ownership and applies as one undoable form edit`() {
        val root = Files.createTempDirectory("ares-subsystem-ai-proposal").toFile()
        lateinit var requestedBase: com.areslib.subsystem.SubsystemDocument
        val assistant = SubsystemDesignAssistant { current, request ->
            requestedBase = current
            assertEquals("Add a safe reversed follower motor", request)
            SubsystemDesignProposal(
                summary = "Add a clearly named mechanism proposal.",
                explanations = listOf("The form remains locally validated."),
                candidate = current.copy(
                    displayName = "AI Proposed Mechanism",
                    uid = "untrusted-replacement",
                    implementation = current.implementation.copy(
                        ownership = com.areslib.subsystem.SubsystemSourceOwnership.USER_OWNED,
                    ),
                    tuningParameters = current.tuningParameters.map {
                        it.copy(uid = "untrusted.parameter", key = "untrusted.parameter", componentUid = "untrusted.owner")
                    },
                ),
            )
        }
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC, designAssistant = assistant)
        viewModel.newSubsystem()
        viewModel.addTuningParameter()
        val before = viewModel.state.value.draft!!.document

        viewModel.requestAiProposal("Add a safe reversed follower motor")
        waitFor { !viewModel.state.value.aiProposalInProgress }

        val review = viewModel.state.value.aiProposal
        assertTrue(review != null)
        assertTrue(review!!.canApply)
        assertEquals(before.uid, review.proposal.candidate.uid)
        assertEquals(before.implementation, review.proposal.candidate.implementation)
        assertEquals(
            before.tuningParameters.map { Triple(it.uid, it.key, it.componentUid) },
            review.proposal.candidate.tuningParameters.map { Triple(it.uid, it.key, it.componentUid) },
        )
        assertTrue(review.diff.any { it.kind == SubsystemDiffLineKind.ADDED })
        assertEquals(before, requestedBase)
        assertEquals(before, viewModel.state.value.draft!!.document, "Review must not mutate the form")

        viewModel.applyAiProposal()
        assertEquals("AI Proposed Mechanism", viewModel.state.value.draft!!.document.displayName)
        assertTrue(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.canUndo)
        viewModel.undo()
        assertEquals(before, viewModel.state.value.draft!!.document)
        viewModel.close()
    }

    @Test
    fun `interlock authoring and fault recovery editing updates draft and permits undo`() {
        val root = Files.createTempDirectory("ares-interlock-test").toFile()
        val viewModel = SubsystemGeneratorViewModel(root.path, League.FTC)
        viewModel.newSubsystem()
        viewModel.registerHandAuthoredSubsystem()

        viewModel.addInterlock()
        val draftWithInterlock = viewModel.state.value.draft!!.document
        assertEquals(1, draftWithInterlock.interlocks.size)
        val interlockId = draftWithInterlock.interlocks.single().interlockId

        viewModel.updateInterlock(interlockId) {
            it.copy(
                targetSubsystemUid = "elevator",
                targetFieldId = "height",
                comparison = com.areslib.subsystem.InterlockComparison.GREATER_THAN,
                thresholdValue = 0.5,
                forbiddenZoneDescription = "Lockout arm when elevator is high",
            )
        }
        val updated = viewModel.state.value.draft!!.document.interlocks.single()
        assertEquals("elevator", updated.targetSubsystemUid)
        assertEquals(0.5, updated.thresholdValue)

        viewModel.edit {
            it.copy(
                safety = it.safety.copy(
                    faultRecovery = com.areslib.subsystem.SubsystemFaultRecoveryDocument(
                        enabled = true,
                        currentThresholdAmps = 22.0,
                        currentDurationMs = 300L,
                        recoveryAction = com.areslib.subsystem.FaultRecoveryActionKind.REVERSE_BRIEFLY,
                    )
                )
            )
        }
        assertTrue(viewModel.state.value.draft!!.document.safety.faultRecovery.enabled)
        assertEquals(22.0, viewModel.state.value.draft!!.document.safety.faultRecovery.currentThresholdAmps)

        viewModel.removeInterlock(interlockId)
        assertEquals(0, viewModel.state.value.draft!!.document.interlocks.size)

        viewModel.undo()
        assertEquals(1, viewModel.state.value.draft!!.document.interlocks.size)
        viewModel.close()
    }

    private fun minimalSubsystem(kotlinTypeName: String) = com.areslib.subsystem.SubsystemDocument(
        documentId = "indexer",
        displayName = kotlinTypeName.replace(Regex("(?<=[a-z])(?=[A-Z])"), " "),
        kotlinTypeName = kotlinTypeName,
        platform = SubsystemPlatform.FTC,
        hardware = listOf(
            SubsystemHardwareDocument(
                "beam", "Beam break", SubsystemHardwareKind.DIGITAL_INPUT,
                SubsystemHardwareConnection(hardwareMapName = "beam"),
                measurements = listOf(SubsystemMeasurementDocument("hasPiece", SubsystemMeasurementSource.DIGITAL_STATE)),
            )
        ),
        stateFields = listOf(
            SubsystemStateFieldDocument(
                "hasPiece", "Has piece", SubsystemValueType.BOOLEAN, SubsystemFieldRole.STATUS,
                defaultBoolean = false,
            )
        ),
        template = SubsystemTemplate.SENSOR_ONLY_SUBSYSTEM,
        safety = SubsystemSafetyDocument(
            latchOutputFaults = false,
            requiresExplicitNeutralRecovery = false,
            requiresCurrentMonitoring = false,
        ),
    )

    private class FakeGenerator : AresProjectGenerator {
        override val aresGenerationState: StateFlow<AresGenerationState> = MutableStateFlow(AresGenerationState())
        var projectPath: String? = null
        var league: League? = null
        var replacementToken: String? = null

        override fun generateAresProject(projectPath: String, league: League) {
            this.projectPath = java.io.File(projectPath).canonicalPath
            this.league = league
        }

        override fun previewSubsystemStarters(projectPath: String, league: League) = SubsystemStarterPlan(emptyList(), null)

        override fun applySubsystemStarters(projectPath: String, league: League, confirmationToken: String?) {
            this.projectPath = java.io.File(projectPath).canonicalPath
            this.league = league
            replacementToken = confirmationToken
        }
    }

    private fun waitFor(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for asynchronous view-model work" }
            Thread.sleep(10L)
        }
    }
}
