package com.areslib.subsystem

internal object SubsystemBoundaryFixtures {
    fun motor(): SubsystemDocument = SubsystemDocument(
        documentId = "arm", displayName = "Arm", kotlinTypeName = "Arm", platform = SubsystemPlatform.FTC,
        hardware = listOf(SubsystemHardwareDocument(
            "motor", "Motor", SubsystemHardwareKind.MOTOR,
            SubsystemHardwareConnection(hardwareMapName = "motor"), safeOutput = 0.0,
            measurements = listOf(
                SubsystemMeasurementDocument("angle", SubsystemMeasurementSource.MOTOR_POSITION_NATIVE),
                SubsystemMeasurementDocument("velocity", SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND),
                SubsystemMeasurementDocument("currentAmps", SubsystemMeasurementSource.MOTOR_CURRENT_AMPS),
            ),
        )),
        stateFields = listOf(
            SubsystemStateFieldDocument("command", "Command", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET, "V", defaultNumber = 0.0, minimum = -12.0, maximum = 12.0),
            SubsystemStateFieldDocument("angle", "Angle", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, "rad", defaultNumber = 0.0),
            SubsystemStateFieldDocument("velocity", "Velocity", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, "rad/s", defaultNumber = 0.0),
            SubsystemStateFieldDocument("currentAmps", "Current", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, "A", defaultNumber = 0.0),
        ),
        controlLoops = listOf(SubsystemControlLoopDocument("control", "Control", SubsystemControlStrategy.DIRECT, "motor", "command")),
        implementation = SubsystemImplementationDocument(
            kind = SubsystemImplementationKind.DECLARATIVE_GENERATED,
            ownership = SubsystemSourceOwnership.GENERATED_DO_NOT_EDIT,
        ),
    )

    fun linkage(): SubsystemDocument {
        val first = motor()
        return first.copy(
            hardware = first.hardware + first.hardware.single().copy(
                hardwareId = "elbow", uid = "elbow", connection = SubsystemHardwareConnection(hardwareMapName = "elbow"),
                measurements = listOf(SubsystemMeasurementDocument("elbowAngle", SubsystemMeasurementSource.MOTOR_POSITION_NATIVE)),
            ),
            stateFields = first.stateFields + first.stateFields.single { it.fieldId == "angle" }.copy(fieldId = "elbowAngle", uid = "elbowAngle"),
            controlLoops = first.controlLoops + first.controlLoops.single().copy(loopId = "elbowControl", uid = "elbowControl", actuatorId = "elbow"),
            linkage = SubsystemLinkageDocument(
                enabled = true, joint1ActuatorId = "motor", joint2ActuatorId = "elbow",
                joint1AngleFieldId = "angle", joint2AngleFieldId = "elbowAngle",
            ),
        )
    }
}
