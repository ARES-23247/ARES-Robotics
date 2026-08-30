package com.ares.analytics.viewmodel.controls

import com.ares.analytics.service.GamepadState
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.AresGenerationState
import com.ares.analytics.service.AresProjectGenerator
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.help.toAcademyControlsSnapshot
import com.ares.analytics.service.project.AresProjectDocuments
import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.catalog.CapabilityParameterDescriptor
import com.areslib.catalog.CapabilityParameterType
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControllerAnchorDocument
import com.areslib.controls.ControllerAssignment
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputMappingDocument
import com.areslib.controls.ControllerProfileDocument
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.subsystemCalibrationConfirmationActionKey
import com.areslib.subsystem.subsystemNeutralRecoveryActionKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlsEditorViewModelTest {
    @Test
    fun `missing capability starts a reviewed draft on the explicitly selected button`() = withProject { project ->
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, seededDocuments(project))

        assertEquals(1, viewModel.state.value.coverage.totalCount)
        assertEquals(0, viewModel.state.value.coverage.boundCount)
        viewModel.createBindingForAction("intake.run")
        assertNull(viewModel.state.value.draftBinding)
        assertTrue(viewModel.state.value.status.orEmpty().contains("Select the button"))

        viewModel.selectControl("a")
        viewModel.createBindingForAction("intake.run")

        val draft = assertNotNull(viewModel.state.value.draftBinding)
        assertEquals(listOf("a"), draft.source.controlIds)
        assertEquals(ControlTargetKind.ACTION, draft.target.kind)
        assertEquals("intake.run", draft.target.key)
        assertTrue(viewModel.state.value.draftHasUnappliedChanges)
        assertEquals(0, viewModel.state.value.coverage.boundCount)

        viewModel.applyDraft()
        assertEquals(1, viewModel.state.value.coverage.boundCount)
        assertTrue(viewModel.state.value.dirty)
    }

    @Test
    fun `selecting an action replaces only the untouched generic binding name`() = withProject { project ->
        val documents = seededDocuments(project)
        documents.capabilities.save(
            project.path,
            CapabilityCatalogDocument(
                projectId = "student-robot",
                actions = listOf(
                    ActionDescriptor(
                        key = "intake.run",
                        displayName = "Run intake",
                        description = "Runs the intake.",
                        category = "Intake",
                    ),
                    ActionDescriptor(
                        key = "drive.reset",
                        displayName = "Reset drive",
                        description = "Resets the drivetrain.",
                        category = "Drive",
                    ),
                ),
            ),
        )
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)
        viewModel.selectControl("a")
        viewModel.createBinding()

        assertEquals("A binding", viewModel.state.value.draftBinding?.displayName)
        viewModel.setTarget(ControlTargetKind.ACTION, "intake.run")
        assertEquals("Run intake", viewModel.state.value.draftBinding?.displayName)

        viewModel.setTarget(ControlTargetKind.ACTION, "drive.reset")
        assertEquals("Reset drive", viewModel.state.value.draftBinding?.displayName)

        viewModel.updateDraft { it.copy(displayName = "Student's intake control") }
        viewModel.setTarget(ControlTargetKind.ACTION, "intake.run")
        assertEquals("Student's intake control", viewModel.state.value.draftBinding?.displayName)
    }

    @Test
    fun `momentary voltage action creates held output and zero release bindings atomically`() = withProject { project ->
        val documents = seededDocuments(project)
        documents.capabilities.save(
            project.path,
            CapabilityCatalogDocument(
                projectId = "student-robot",
                actions = listOf(
                    ActionDescriptor(
                        key = "intake.voltage",
                        displayName = "Set intake voltage",
                        description = "Commands the intake motor voltage.",
                        category = "Intake",
                        parameters = listOf(
                            CapabilityParameterDescriptor(
                                key = "value",
                                displayName = "Voltage",
                                description = "Requested motor voltage.",
                                type = CapabilityParameterType.NUMBER,
                                unit = "V",
                                defaultNumber = 6.0,
                            )
                        ),
                    )
                ),
            ),
        )
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)
        viewModel.selectControl("a")
        viewModel.createBinding()
        viewModel.setTarget(ControlTargetKind.ACTION, "intake.voltage")

        viewModel.addSafeMomentaryPair()

        val bindings = viewModel.state.value.selectedScheme?.bindings.orEmpty()
            .filter { it.target.key == "intake.voltage" }
        assertEquals(2, bindings.size)
        assertEquals("6.0", bindings.single { it.event == ControlEvent.HELD }.target.arguments["value"])
        assertEquals("0", bindings.single { it.event == ControlEvent.RELEASE }.target.arguments["value"])
        assertTrue(bindings.all { it.source.controlIds == listOf("a") })
        assertNull(viewModel.state.value.draftBinding)
        assertTrue(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.status.orEmpty().contains("safe pair"))
    }

    @Test
    fun `editor is explicitly project backed and does not require a robot`() {
        val viewModel = ControlsEditorViewModel("", League.FTC)

        assertTrue(viewModel.state.value.loadError.orEmpty().contains("project directory"))
        assertFalse(viewModel.state.value.canSave)
    }

    @Test
    fun `desktop learning changes only the desktop mapping`() = withProject { project ->
        val documents = seededDocuments(project)
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)
        viewModel.selectControl("m1")
        val baseline = GamepadState(
            connected = true,
            name = "Flydigi Vader 5 Pro",
            rawButtons = List(20) { false },
            rawAxes = List(6) { 0f }
        )

        viewModel.beginDesktopLearning(baseline)
        viewModel.observeDesktopInput(baseline.copy(rawButtons = List(20) { it == 17 }))

        val control = viewModel.state.value.selectedProfile?.controls?.first { it.controlId == "m1" }
        assertNotNull(control)
        assertEquals(17, control.mappings.single { it.platform == ControllerInputPlatform.DESKTOP_GLFW }.buttonIndex)
        assertEquals(20, control.mappings.single { it.platform == ControllerInputPlatform.FTC }.buttonIndex)
        assertNull(control.mappings.firstOrNull { it.platform == ControllerInputPlatform.FRC })
        assertTrue(viewModel.state.value.status.orEmpty().contains("FTC/FRC mappings were not changed"))
    }

    @Test
    fun `standard controls have novice ready platform mappings`() = withProject { project ->
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, seededDocuments(project))
        val profile = viewModel.state.value.selectedProfile ?: error("Expected built-in controller profile")

        assertTrue(viewModel.state.value.profiles.any { it.documentId == "xbox-standard" })
        val a = profile.controls.first { it.controlId == "a" }
        val rightTrigger = profile.controls.first { it.controlId == "right_trigger" }
        assertEquals(0, a.mappings.single { it.platform == ControllerInputPlatform.FTC }.buttonIndex)
        assertEquals(0, a.mappings.single { it.platform == ControllerInputPlatform.FRC }.buttonIndex)
        assertEquals(5, rightTrigger.mappings.single { it.platform == ControllerInputPlatform.FTC }.axisIndex)
        assertEquals(3, rightTrigger.mappings.single { it.platform == ControllerInputPlatform.FRC }.axisIndex)
    }

    @Test
    fun `binding edits save typed project documents after repairing a missing target mapping`() = withProject { project ->
        val documents = seededDocuments(project)
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)

        viewModel.selectControl("a")
        viewModel.setMapping("a", ControllerInputPlatform.FTC, null)
        viewModel.createBinding()
        viewModel.updateDraft { binding ->
            binding.copy(
                displayName = "Intake while held",
                event = ControlEvent.HELD,
                timing = binding.timing.copy(pressDebounceSeconds = .04, maximumActiveSeconds = 2.0)
            )
        }
        assertTrue(viewModel.state.value.problems.any {
            it.severity == ControlsProblemSeverity.ERROR && it.message.contains("FTC mapping")
        })
        viewModel.applyDraft()
        assertTrue(viewModel.state.value.selectedScheme?.bindings.orEmpty().isEmpty())
        val desktopBaseline = GamepadState(connected = true, rawButtons = List(4) { false })
        viewModel.beginDesktopLearning(desktopBaseline)
        viewModel.observeDesktopInput(desktopBaseline.copy(rawButtons = List(4) { it == 0 }))
        viewModel.setMapping("a", ControllerInputPlatform.FTC, 0)
        assertTrue(viewModel.state.value.problems.none { it.message.contains("FTC mapping") })
        viewModel.applyDraft()
        viewModel.save()

        val savedScheme = documents.controls.load(project.path, "competition-controls")
        val savedProfile = documents.controllers.load(project.path, "flydigi-vader-5-pro")
        assertEquals(ControlEvent.HELD, savedScheme.bindings.single().event)
        assertEquals(.04, savedScheme.bindings.single().timing.pressDebounceSeconds)
        assertEquals(2.0, savedScheme.bindings.single().timing.maximumActiveSeconds)
        val mappings = savedProfile.controls.first { it.controlId == "a" }.mappings
        assertEquals(0, mappings.single { it.platform == ControllerInputPlatform.FTC }.buttonIndex)
        assertEquals(0, mappings.single { it.platform == ControllerInputPlatform.DESKTOP_GLFW }.buttonIndex)
        assertEquals(0, mappings.single { it.platform == ControllerInputPlatform.FRC }.buttonIndex)
        assertEquals(0, savedScheme.controllers.single { it.slot == "driver" }.devicePort)
        assertEquals(1, savedScheme.controllers.single { it.slot == "operator" }.devicePort)
        assertFalse(viewModel.state.value.dirty)
    }

    @Test
    fun `drive axis bindings author save and validate through the editor`() = withProject { project ->
        val documents = seededDocuments(project)
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)

        viewModel.selectControl("left_stick_y")
        viewModel.createBinding()
        viewModel.updateDraft { binding ->
            binding.copy(
                displayName = "Drive forward/back",
                event = ControlEvent.VALUE,
                source = binding.source.copy(kind = ControlSourceKind.AXIS_VALUE),
                analogPolicy = com.areslib.controls.AnalogControlPolicyDocument(),
            )
        }
        viewModel.setTarget(ControlTargetKind.DRIVE, "vx")
        assertTrue(
            viewModel.state.value.problems.none {
                it.severity == ControlsProblemSeverity.ERROR && it.bindingId == viewModel.state.value.draftBinding?.bindingId
            },
            viewModel.state.value.problems.joinToString { it.message },
        )
        viewModel.applyDraft()
        viewModel.save()

        val savedScheme = documents.controls.load(project.path, "competition-controls")
        val saved = savedScheme.bindings.single()
        assertEquals(ControlTargetKind.DRIVE, saved.target.kind)
        assertEquals("vx", saved.target.key)
        assertEquals(ControlEvent.VALUE, saved.event)
        assertTrue(
            com.areslib.controls.validateControlScheme(
                savedScheme,
                com.areslib.controls.ControlValidationContext(
                    actionKeys = setOf("intake.run"),
                    profileControls = mapOf(
                        "flydigi-vader-5-pro" to setOf("left_stick_x", "left_stick_y", "right_stick_x"),
                    ),
                ),
            ).none { it.severity == com.areslib.controls.ControlValidationSeverity.ERROR },
        )
    }

    @Test
    fun `source editor covers chords analog values zones and repeat timing`() = withProject { project ->
        val viewModel = ControlsEditorViewModel(project.path, League.FRC, seededDocuments(project))
        viewModel.selectControl("left_trigger")
        viewModel.createBinding()
        viewModel.setSourceKind(ControlSourceKind.AXIS_VALUE)
        assertEquals(ControlEvent.VALUE, viewModel.state.value.draftBinding?.event)
        assertNotNull(viewModel.state.value.draftBinding?.analogPolicy)

        viewModel.setSourceKind(ControlSourceKind.AXIS_ZONE)
        assertEquals(ControlEvent.ZONE_ENTER, viewModel.state.value.draftBinding?.event)
        assertNotNull(viewModel.state.value.draftBinding?.source?.zoneMinimum)

        viewModel.selectControl("a")
        viewModel.createBinding()
        viewModel.setSourceKind(ControlSourceKind.CHORD)
        viewModel.selectControl("b", appendToChord = true)
        viewModel.updateDraft { binding ->
            binding.copy(
                event = ControlEvent.REPEAT,
                timing = binding.timing.copy(repeatAfterSeconds = .3, repeatEverySeconds = .1, cooldownSeconds = .2)
            )
        }
        assertEquals(listOf("a", "b"), viewModel.state.value.draftBinding?.source?.controlIds)
        assertEquals(.3, viewModel.state.value.draftBinding?.timing?.repeatAfterSeconds)
        assertEquals(.1, viewModel.state.value.draftBinding?.timing?.repeatEverySeconds)
    }

    @Test
    fun `typed catalog arguments fail closed in the editor`() = withProject { project ->
        val documents = AresProjectDocuments()
        saveMetadata(documents, project)
        documents.capabilities.save(
            project.path,
            CapabilityCatalogDocument(
                projectId = "typed-project",
                actions = listOf(
                    ActionDescriptor(
                        key = "flywheel.set",
                        displayName = "Set flywheel",
                        description = "Sets normalized output.",
                        parameters = listOf(
                            CapabilityParameterDescriptor(
                                key = "output",
                                displayName = "Output",
                                description = "Normalized flywheel output.",
                                type = CapabilityParameterType.NUMBER,
                                minimum = 0.0,
                                maximum = 1.0
                            )
                        )
                    )
                )
            )
        )
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)
        viewModel.selectControl("a")
        viewModel.createBinding()

        assertTrue(viewModel.state.value.problems.any { it.message == "Output is required." })
        viewModel.setTargetArgument("output", "2.0")
        assertTrue(viewModel.state.value.problems.any { it.message.contains("at most 1.0") })
        viewModel.setTargetArgument("output", "0.7")
        assertTrue(viewModel.state.value.problems.none { it.message.startsWith("Output ") })
    }

    @Test
    fun `generated safety handshakes are visible and saveable without Kotlin`() = withProject { project ->
        val documents = seededDocuments(project)
        val lift = SubsystemTemplates.create(
            template = SubsystemTemplate.HOMED_MECHANISM,
            documentId = "lift",
            kotlinTypeName = "Lift",
            platform = SubsystemPlatform.FTC,
        ).let { document ->
            document.copy(safety = document.safety.copy(requiresCalibration = true))
        }
        documents.subsystems.save(project.path, lift)
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)
        val recoveryKey = subsystemNeutralRecoveryActionKey("lift")
        val calibrationKey = subsystemCalibrationConfirmationActionKey("lift")

        assertTrue(viewModel.state.value.actions.any { it.key == recoveryKey && "neutral" in it.displayName.lowercase() })
        assertTrue(viewModel.state.value.actions.any { it.key == calibrationKey && "calibration" in it.displayName.lowercase() })

        viewModel.selectControl("a")
        viewModel.createBinding()
        viewModel.setTarget(ControlTargetKind.ACTION, recoveryKey)
        assertTrue(viewModel.state.value.problems.any { it.message.contains("Recover with neutral is required") })
        viewModel.setTargetArgument("value", "true")
        viewModel.applyDraft()
        viewModel.selectControl("b")
        viewModel.createBinding()
        viewModel.setTarget(ControlTargetKind.ACTION, calibrationKey)
        assertTrue(viewModel.state.value.problems.any { it.message.contains("Calibration is complete is required") })
        viewModel.setTargetArgument("value", "true")
        viewModel.applyDraft()
        assertTrue(
            viewModel.state.value.canSave,
            viewModel.state.value.problems.joinToString(" | ") { it.message } +
                " status=" + viewModel.state.value.status,
        )
        viewModel.save()

        assertEquals(
            setOf(recoveryKey, calibrationKey),
            documents.controls.load(project.path, "competition-controls").bindings.map { it.target.key }.toSet(),
        )
    }

    @Test
    fun `academy evidence follows a real generated target binding through canonical save and generation`() = withProject { project ->
        val documents = seededDocuments(project)
        documents.subsystems.save(
            project.path,
            SubsystemTemplates.create(
                template = SubsystemTemplate.HOMED_MECHANISM,
                documentId = "practice-lift",
                kotlinTypeName = "PracticeLift",
                platform = SubsystemPlatform.FTC,
            ),
        )
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)
        val targetAction = viewModel.state.value.actions.firstOrNull {
            it.key == "subsystem.practice-lift.set.target" && it.parameters.any { parameter -> parameter.key == "value" }
        } ?: error("Expected generated subsystem target action; found ${viewModel.state.value.actions.map { it.key }}")

        assertTrue(viewModel.state.value.toAcademyControlsSnapshot().hasGeneratedSubsystemCapability)
        viewModel.selectControl("a")
        assertTrue(viewModel.state.value.toAcademyControlsSnapshot().hasMappedControlSelection)
        viewModel.createBinding()
        viewModel.setTarget(ControlTargetKind.ACTION, targetAction.key)
        viewModel.setTargetArgument("value", "0.25")
        viewModel.applyDraft()
        assertTrue(
            viewModel.state.value.toAcademyControlsSnapshot().hasValidAppliedBinding,
            "bindings=${viewModel.state.value.selectedScheme?.bindings}; problems=${viewModel.state.value.problems}; " +
                "draft=${viewModel.state.value.draftBinding}",
        )
        assertFalse(viewModel.state.value.toAcademyControlsSnapshot().hasSavedControlScheme)

        viewModel.save()
        val saved = viewModel.state.value.toAcademyControlsSnapshot()
        assertTrue(saved.hasSavedControlScheme)
        assertFalse(saved.hasGeneratedBindings)

        val generated = viewModel.state.value.copy(
            generationPhase = AresGenerationPhase.SUCCEEDED,
            generatedContentHash = "generated-hash",
        ).toAcademyControlsSnapshot()
        assertTrue(generated.hasGeneratedBindings)
        assertEquals(
            targetAction.key,
            documents.controls.load(project.path, "competition-controls").bindings.single().target.key,
        )
    }

    @Test
    fun `switching schemes preserves and saves every applied edit`() = withProject { project ->
        val documents = seededDocuments(project)
        val profile = ControllerProfileDocument(
            documentId = "test-pad",
            displayName = "Test pad",
            controls = listOf(
                ControllerControlDocument(
                    controlId = "a",
                    displayName = "A",
                    type = ControllerControlTypeDocument.BUTTON,
                    anchor = ControllerAnchorDocument(.5, .5),
                    mappings = listOf(ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = 0))
                )
            )
        )
        documents.controllers.save(project.path, profile)
        listOf("alpha", "beta").forEach { id ->
            documents.controls.save(
                project.path,
                ControlSchemeDocument(
                    documentId = id,
                    name = id.replaceFirstChar(Char::uppercase),
                    controllers = listOf(ControllerAssignment("driver", "Driver", profile.documentId, devicePort = 0)),
                    bindings = emptyList()
                )
            )
        }
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)
        listOf("alpha", "beta").forEach { id ->
            viewModel.selectScheme(id)
            viewModel.selectControl("a")
            viewModel.createBinding()
            viewModel.applyDraft()
        }
        assertEquals(setOf("alpha", "beta"), viewModel.state.value.dirtySchemeIds)
        viewModel.save()
        assertEquals(1, documents.controls.load(project.path, "alpha").bindings.size)
        assertEquals(1, documents.controls.load(project.path, "beta").bindings.size)
    }

    @Test
    fun `scheme switch refuses to discard an unapplied draft`() = withProject { project ->
        val documents = seededDocuments(project)
        val initial = ControlsEditorViewModel(project.path, League.FTC, documents)
        initial.save()
        val base = documents.controls.load(project.path, "competition-controls")
        documents.controls.save(project.path, base.copy(documentId = "practice-controls", name = "Practice"))
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)
        val originalId = viewModel.state.value.selectedSchemeId
        viewModel.selectControl("a")
        viewModel.createBinding()
        viewModel.selectScheme("practice-controls")
        assertEquals(originalId, viewModel.state.value.selectedSchemeId)
        assertTrue(viewModel.state.value.status.orEmpty().contains("Apply or discard"))
    }

    @Test
    fun `controller switch refuses to strand an unapplied draft`() = withProject { project ->
        val documents = seededDocuments(project)
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)
        val scheme = viewModel.state.value.selectedScheme ?: error("Expected seeded scheme")
        assertTrue(scheme.controllers.any { it.slot == "operator" })
        viewModel.selectControl("a")
        viewModel.createBinding()

        viewModel.selectController("operator")

        assertEquals("driver", viewModel.state.value.selectedControllerSlot)
        assertNotNull(viewModel.state.value.draftBinding)
        assertTrue(viewModel.state.value.draftHasUnappliedChanges)
        assertTrue(viewModel.state.value.status.orEmpty().contains("Apply or discard"))
    }

    @Test
    fun `new binding cannot overwrite an unapplied draft`() = withProject { project ->
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, seededDocuments(project))
        viewModel.selectControl("a")
        viewModel.createBinding()
        val originalDraftId = viewModel.state.value.draftBinding?.bindingId
        viewModel.updateDraft { it.copy(displayName = "Unsaved intake") }
        viewModel.selectControl("b")

        viewModel.createBinding()

        assertEquals(originalDraftId, viewModel.state.value.draftBinding?.bindingId)
        assertEquals("Unsaved intake", viewModel.state.value.draftBinding?.displayName)
        assertTrue(viewModel.state.value.status.orEmpty().contains("Apply or discard"))
    }

    @Test
    fun `deleteBinding removes binding from scheme and sets dirty state`() = withProject { project ->
        val documents = seededDocuments(project)
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)

        viewModel.selectControl("a")
        viewModel.createBinding()
        viewModel.applyDraft()
        val bindingId = viewModel.state.value.selectedScheme?.bindings?.firstOrNull()?.bindingId
        assertNotNull(bindingId)

        viewModel.deleteBinding(bindingId)
        assertTrue(viewModel.state.value.selectedScheme?.bindings.orEmpty().isEmpty())
        assertTrue(viewModel.state.value.dirty)
    }

    @Test
    fun `custom control schemes and button binding configurations can be added updated and removed`() = withProject { project ->
        val documents = seededDocuments(project)
        val profile = ControllerProfileDocument(
            documentId = "custom-pad",
            displayName = "Custom Pad",
            controls = listOf(
                ControllerControlDocument(
                    controlId = "button_x",
                    displayName = "X",
                    type = ControllerControlTypeDocument.BUTTON,
                    anchor = ControllerAnchorDocument(.5, .5),
                    mappings = listOf(
                        ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = 2),
                        ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, buttonIndex = 2)
                    )
                ),
                ControllerControlDocument(
                    controlId = "button_y",
                    displayName = "Y",
                    type = ControllerControlTypeDocument.BUTTON,
                    anchor = ControllerAnchorDocument(.5, .3),
                    mappings = listOf(
                        ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = 3),
                        ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, buttonIndex = 3)
                    )
                )
            )
        )
        documents.controllers.save(project.path, profile)

        val customTeleopScheme = ControlSchemeDocument(
            documentId = "custom-teleop",
            name = "Custom Teleop",
            controllers = listOf(
                ControllerAssignment("driver", "Driver", profile.documentId, devicePort = 0),
                ControllerAssignment("operator", "Operator", profile.documentId, devicePort = 1)
            ),
            bindings = emptyList()
        )
        val customEndgameScheme = ControlSchemeDocument(
            documentId = "custom-endgame",
            name = "Custom Endgame",
            controllers = listOf(
                ControllerAssignment("driver", "Driver", profile.documentId, devicePort = 0)
            ),
            bindings = emptyList()
        )
        documents.controls.save(project.path, customTeleopScheme)
        documents.controls.save(project.path, customEndgameScheme)

        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents)

        val initialSchemeIds = viewModel.state.value.schemes.map { it.documentId }
        assertTrue(initialSchemeIds.contains("custom-teleop"))
        assertTrue(initialSchemeIds.contains("custom-endgame"))

        viewModel.selectScheme("custom-teleop")
        assertEquals("custom-teleop", viewModel.state.value.selectedSchemeId)
        assertEquals("Custom Teleop", viewModel.state.value.selectedScheme?.name)

        viewModel.selectControl("button_x")
        viewModel.createBinding()
        assertNotNull(viewModel.state.value.draftBinding)
        assertTrue(viewModel.state.value.draftHasUnappliedChanges)

        viewModel.updateDraft { draft ->
            draft.copy(
                displayName = "Intake Pulse",
                event = ControlEvent.PRESS,
                timing = draft.timing.copy(pressDebounceSeconds = 0.05, releaseDebounceSeconds = 0.02)
            )
        }
        viewModel.setTarget(ControlTargetKind.ACTION, "intake.run")
        viewModel.applyDraft()

        val teleopBindings = viewModel.state.value.selectedScheme?.bindings.orEmpty()
        assertEquals(1, teleopBindings.size)
        val addedBinding = teleopBindings.single()
        assertEquals("Intake Pulse", addedBinding.displayName)
        assertEquals(ControlEvent.PRESS, addedBinding.event)
        assertEquals(0.05, addedBinding.timing.pressDebounceSeconds)
        assertEquals(0.02, addedBinding.timing.releaseDebounceSeconds)
        assertEquals("intake.run", addedBinding.target.key)
        assertTrue(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.dirtySchemeIds.contains("custom-teleop"))

        viewModel.save()
        assertFalse(viewModel.state.value.dirty)
        val savedTeleop = documents.controls.load(project.path, "custom-teleop")
        assertEquals(1, savedTeleop.bindings.size)
        assertEquals("Intake Pulse", savedTeleop.bindings.single().displayName)

        viewModel.selectScheme("custom-teleop")
        val bindingId = addedBinding.bindingId
        viewModel.editBinding(bindingId)
        assertEquals(bindingId, viewModel.state.value.selectedBindingId)
        assertEquals("Intake Pulse", viewModel.state.value.draftBinding?.displayName)

        viewModel.updateDraft { draft ->
            draft.copy(
                displayName = "Intake While Held",
                event = ControlEvent.HELD,
                timing = draft.timing.copy(
                    pressDebounceSeconds = 0.08,
                    holdAfterSeconds = 0.45,
                    maximumActiveSeconds = 5.0
                )
            )
        }
        viewModel.applyDraft()

        val updatedBindings = viewModel.state.value.selectedScheme?.bindings.orEmpty()
        assertEquals(1, updatedBindings.size)
        val updatedBinding = updatedBindings.single()
        assertEquals(bindingId, updatedBinding.bindingId)
        assertEquals("Intake While Held", updatedBinding.displayName)
        assertEquals(ControlEvent.HELD, updatedBinding.event)
        assertEquals(0.08, updatedBinding.timing.pressDebounceSeconds)
        assertEquals(0.45, updatedBinding.timing.holdAfterSeconds)
        assertEquals(5.0, updatedBinding.timing.maximumActiveSeconds)

        viewModel.save()
        val reloadedTeleop = documents.controls.load(project.path, "custom-teleop")
        assertEquals(ControlEvent.HELD, reloadedTeleop.bindings.single().event)
        assertEquals(0.45, reloadedTeleop.bindings.single().timing.holdAfterSeconds)
        assertEquals("Intake While Held", reloadedTeleop.bindings.single().displayName)

        viewModel.selectScheme("custom-endgame")
        assertEquals("custom-endgame", viewModel.state.value.selectedSchemeId)
        viewModel.selectControl("button_y")
        viewModel.createBinding()
        viewModel.updateDraft { draft ->
            draft.copy(
                displayName = "Endgame Action",
                event = ControlEvent.RELEASE
            )
        }
        viewModel.setTarget(ControlTargetKind.ACTION, "intake.run")
        viewModel.applyDraft()
        viewModel.save()

        val savedEndgame = documents.controls.load(project.path, "custom-endgame")
        assertEquals(1, savedEndgame.bindings.size)
        assertEquals("Endgame Action", savedEndgame.bindings.single().displayName)
        assertEquals(ControlEvent.RELEASE, savedEndgame.bindings.single().event)

        viewModel.selectScheme("custom-teleop")
        assertEquals(1, viewModel.state.value.selectedScheme?.bindings.orEmpty().size)
        viewModel.deleteBinding(bindingId)

        assertTrue(viewModel.state.value.selectedScheme?.bindings.orEmpty().isEmpty())
        assertNull(viewModel.state.value.selectedBindingId)
        assertNull(viewModel.state.value.draftBinding)
        assertTrue(viewModel.state.value.dirty)

        viewModel.save()
        val clearedTeleop = documents.controls.load(project.path, "custom-teleop")
        assertTrue(clearedTeleop.bindings.isEmpty())

        val teleopFile = File(project, ".ares/controls/custom-teleop.arescontrols")
        assertTrue(teleopFile.exists())
        assertTrue(teleopFile.delete())

        viewModel.reload()
        val remainingSchemeIds = viewModel.state.value.schemes.map { it.documentId }
        assertFalse(remainingSchemeIds.contains("custom-teleop"))
        assertTrue(remainingSchemeIds.contains("custom-endgame"))
        assertEquals("custom-endgame", viewModel.state.value.selectedSchemeId)
    }

    @Test
    fun `save and generate uses the selected local project without requiring robot state`() = withProject { project ->
        val documents = seededDocuments(project)
        val generator = RecordingGenerator()
        val viewModel = ControlsEditorViewModel(project.path, League.FTC, documents, generator)
        viewModel.saveAndGenerate()
        assertEquals(project.canonicalPath, generator.projectPath?.let { File(it) }?.canonicalPath)
        assertEquals(League.FTC, generator.league)
    }

    private fun seededDocuments(project: File): AresProjectDocuments = AresProjectDocuments().also { documents ->
        saveMetadata(documents, project)
        documents.capabilities.save(
            project.path,
            CapabilityCatalogDocument(
                projectId = "student-robot",
                actions = listOf(
                    ActionDescriptor(
                        key = "intake.run",
                        displayName = "Run intake",
                        description = "Runs the intake.",
                        category = "Intake"
                    )
                )
            )
        )
    }

    private fun saveMetadata(documents: AresProjectDocuments, project: File) {
        documents.metadata.save(
            project.path,
            AresProjectMetadataDocument(
                projectId = "student-robot",
                identity = com.areslib.project.AresProjectIdentityDocument("99999", "2026", "student-robot", "Student Robot"),
                league = AresLeague.FTC,
                coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                robotLengthMeters = .45,
                robotWidthMeters = .45,
                fieldLengthMeters = 3.6576,
                fieldWidthMeters = 3.6576
            )
        )
    }

    private class RecordingGenerator : AresProjectGenerator {
        override val aresGenerationState: StateFlow<AresGenerationState> = MutableStateFlow(AresGenerationState())
        var projectPath: String? = null
        var league: League? = null
        override fun generateAresProject(projectPath: String, league: League) {
            this.projectPath = projectPath
            this.league = league
        }
        override fun previewSubsystemStarters(projectPath: String, league: League) =
            com.areslib.codegen.SubsystemStarterPlan(emptyList(), null)
        override fun applySubsystemStarters(projectPath: String, league: League, confirmationToken: String?) {
            this.projectPath = projectPath
            this.league = league
        }
    }

    private inline fun withProject(block: (File) -> Unit) {
        val project = Files.createTempDirectory("ares-controls-editor-").toFile()
        try {
            block(project)
        } finally {
            project.deleteRecursively()
        }
    }
}
