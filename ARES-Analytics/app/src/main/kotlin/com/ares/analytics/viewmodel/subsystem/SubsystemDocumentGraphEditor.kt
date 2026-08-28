package com.ares.analytics.viewmodel.subsystem

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFollowerDocument
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.SubsystemFaultRecoveryDocument
import com.areslib.subsystem.SubsystemHomingDocument

/**
 * Pure, referentially safe edits for the canonical subsystem document graph.
 *
 * The Compose ViewModel owns selection and persistence. This editor owns the harder invariant that
 * renaming or removing a node must update every descriptor reference in the same transaction.
 */
internal object SubsystemDocumentGraphEditor {
    fun removeHardware(document: SubsystemDocument, hardwareId: String): SubsystemDocument = document.copy(
        hardware = document.hardware.filterNot { it.hardwareId == hardwareId }.map { device ->
            if (device.following?.leaderId == hardwareId) device.copy(following = null) else device
        },
        controlLoops = document.controlLoops.filterNot { it.actuatorId == hardwareId },
        safety = if (document.safety.homing.actuatorId == hardwareId) {
            document.safety.copy(homing = SubsystemHomingDocument())
        } else {
            document.safety
        }.let { safety ->
            if (safety.faultRecovery.actuatorId == hardwareId) {
                safety.copy(faultRecovery = SubsystemFaultRecoveryDocument())
            } else {
                safety
            }
        },
        linkage = document.linkage.copy(
            joint1ActuatorId = document.linkage.joint1ActuatorId.takeUnless { it == hardwareId },
            joint2ActuatorId = document.linkage.joint2ActuatorId.takeUnless { it == hardwareId },
        ),
        implementation = document.implementation.copy(
            simulation = document.implementation.simulation.copy(
                interaction = document.implementation.simulation.interaction.copy(
                    triggerActuatorId = document.implementation.simulation.interaction.triggerActuatorId
                        .takeUnless { it == hardwareId },
                ),
            ),
        ),
    )

    fun setFollower(
        document: SubsystemDocument,
        hardwareId: String,
        leaderId: String?,
        transform: SubsystemFollowerTransform,
    ): SubsystemDocument = document.copy(
        hardware = document.hardware.map { device ->
            if (device.hardwareId == hardwareId) {
                device.copy(following = leaderId?.let { SubsystemFollowerDocument(it, transform) })
            } else {
                device
            }
        },
        controlLoops = if (leaderId == null) {
            document.controlLoops
        } else {
            document.controlLoops.filterNot { it.actuatorId == hardwareId }
        },
        safety = if (leaderId != null && document.safety.homing.actuatorId == hardwareId) {
            document.safety.copy(homing = SubsystemHomingDocument())
        } else {
            document.safety
        },
    )

    fun renameHardware(
        document: SubsystemDocument,
        oldId: String,
        newId: String,
    ): SubsystemDocument = document.copy(
        hardware = document.hardware.map { device ->
            when {
                device.hardwareId == oldId -> device.copy(hardwareId = newId)
                device.following?.leaderId == oldId -> device.copy(
                    following = requireNotNull(device.following).copy(leaderId = newId),
                )
                else -> device
            }
        },
        controlLoops = document.controlLoops.map { loop ->
            if (loop.actuatorId == oldId) loop.copy(actuatorId = newId) else loop
        },
        safety = document.safety.copy(
            homing = document.safety.homing.copy(
                actuatorId = document.safety.homing.actuatorId?.let { if (it == oldId) newId else it },
            ),
            faultRecovery = document.safety.faultRecovery.copy(
                actuatorId = document.safety.faultRecovery.actuatorId?.let { if (it == oldId) newId else it },
            ),
        ),
        linkage = document.linkage.copy(
            joint1ActuatorId = document.linkage.joint1ActuatorId?.let { if (it == oldId) newId else it },
            joint2ActuatorId = document.linkage.joint2ActuatorId?.let { if (it == oldId) newId else it },
        ),
        implementation = document.implementation.copy(
            simulation = document.implementation.simulation.copy(
                interaction = document.implementation.simulation.interaction.copy(
                    triggerActuatorId = document.implementation.simulation.interaction.triggerActuatorId
                        ?.let { if (it == oldId) newId else it },
                ),
            ),
        ),
    )

    fun removeStateField(document: SubsystemDocument, fieldId: String): SubsystemDocument = document.copy(
        stateFields = document.stateFields.filterNot { it.fieldId == fieldId },
        hardware = document.hardware.map { device ->
            device.copy(measurements = device.measurements.filterNot { it.fieldId == fieldId })
        },
        controlLoops = document.controlLoops.filterNot { loop ->
            loop.targetFieldId == fieldId ||
                loop.measurementFieldId == fieldId ||
                loop.feedforward.velocityFieldId == fieldId ||
                loop.feedforward.accelerationFieldId == fieldId ||
                loop.feedforward.gravityAngleFieldId == fieldId
        },
        safety = document.safety.copy(
            homing = document.safety.homing.copy(
                evidence = document.safety.homing.evidence.filterNot { it.fieldId == fieldId },
            ),
            faultRecovery = if (document.safety.faultRecovery.currentFieldId == fieldId) {
                SubsystemFaultRecoveryDocument()
            } else {
                document.safety.faultRecovery
            },
        ),
        linkage = document.linkage.copy(
            joint1AngleFieldId = document.linkage.joint1AngleFieldId.takeUnless { it == fieldId },
            joint2AngleFieldId = document.linkage.joint2AngleFieldId.takeUnless { it == fieldId },
        ),
        implementation = document.implementation.copy(
            simulation = document.implementation.simulation.copy(
                interaction = document.implementation.simulation.interaction.copy(
                    beamBreakFieldId = document.implementation.simulation.interaction.beamBreakFieldId
                        .takeUnless { it == fieldId },
                ),
            ),
        ),
    )

    fun renameStateField(
        document: SubsystemDocument,
        oldId: String,
        newId: String,
    ): SubsystemDocument = document.copy(
        stateFields = document.stateFields.map { field ->
            if (field.fieldId == oldId) field.copy(fieldId = newId) else field
        },
        hardware = document.hardware.map { device ->
            device.copy(measurements = device.measurements.map { measurement ->
                if (measurement.fieldId == oldId) measurement.copy(fieldId = newId) else measurement
            })
        },
        controlLoops = document.controlLoops.map { loop ->
            loop.copy(
                targetFieldId = if (loop.targetFieldId == oldId) newId else loop.targetFieldId,
                measurementFieldId = loop.measurementFieldId?.let { if (it == oldId) newId else it },
                feedforward = loop.feedforward.copy(
                    velocityFieldId = loop.feedforward.velocityFieldId?.let { if (it == oldId) newId else it },
                    accelerationFieldId = loop.feedforward.accelerationFieldId?.let { if (it == oldId) newId else it },
                    gravityAngleFieldId = loop.feedforward.gravityAngleFieldId?.let { if (it == oldId) newId else it },
                ),
            )
        },
        safety = document.safety.copy(
            homing = document.safety.homing.copy(
                evidence = document.safety.homing.evidence.map { evidence ->
                    if (evidence.fieldId == oldId) evidence.copy(fieldId = newId) else evidence
                },
            ),
            faultRecovery = document.safety.faultRecovery.copy(
                currentFieldId = document.safety.faultRecovery.currentFieldId
                    ?.let { if (it == oldId) newId else it },
            ),
        ),
        linkage = document.linkage.copy(
            joint1AngleFieldId = document.linkage.joint1AngleFieldId?.let { if (it == oldId) newId else it },
            joint2AngleFieldId = document.linkage.joint2AngleFieldId?.let { if (it == oldId) newId else it },
        ),
        implementation = document.implementation.copy(
            simulation = document.implementation.simulation.copy(
                interaction = document.implementation.simulation.interaction.copy(
                    beamBreakFieldId = document.implementation.simulation.interaction.beamBreakFieldId
                        ?.let { if (it == oldId) newId else it },
                ),
            ),
        ),
    )
}
