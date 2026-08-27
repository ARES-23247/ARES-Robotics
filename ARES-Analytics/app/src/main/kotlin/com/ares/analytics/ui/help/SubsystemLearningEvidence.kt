package com.ares.analytics.ui.help

import com.ares.analytics.viewmodel.SubsystemBuilderStage
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemProblemSeverity
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemSimulationSupport
import com.areslib.subsystem.SubsystemTemplate

/** Maps the real canonical builder state to the narrow facts Robot Academy may record. */
fun SubsystemGeneratorState.toAcademySubsystemSnapshot(): AcademySubsystemSnapshot {
    val document = draft?.document ?: return AcademySubsystemSnapshot.Unavailable
    val errorsAbsent = loadError == null && problems.none { it.severity == SubsystemProblemSeverity.ERROR }
    val motor = document.hardware.firstOrNull { it.kind == SubsystemHardwareKind.MOTOR && it.following == null }
    val motorMeasurements = motor?.measurements.orEmpty()
    val positionLoop = document.controlLoops.firstOrNull {
        it.strategy in setOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
        ) && it.actuatorId == motor?.hardwareId
    }
    val target = positionLoop?.targetFieldId?.let { id -> document.stateFields.firstOrNull { it.fieldId == id } }
    val measuredPosition = positionLoop?.measurementFieldId?.let { id ->
        document.stateFields.firstOrNull { it.fieldId == id && it.role == SubsystemFieldRole.MEASUREMENT }
    }
    val positionMeasurement = motorMeasurements.firstOrNull {
        it.source == SubsystemMeasurementSource.MOTOR_POSITION_NATIVE && it.fieldId == measuredPosition?.fieldId
    }
    val velocityMeasurement = motorMeasurements.firstOrNull {
        it.source == SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND
    }
    val currentMeasurement = motorMeasurements.firstOrNull {
        it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS
    }
    val homing = document.safety.homing
    val hasBoundedHoming = homing.method != SubsystemHomingMethod.NONE &&
        homing.actuatorId == motor?.hardwareId &&
        homing.searchOutput?.let { it.isFinite() && it != 0.0 } == true &&
        homing.evidence.isNotEmpty() &&
        homing.dwellMs > 0L && homing.timeoutMs > homing.dwellMs
    val hasSoftLimits = target?.minimum?.isFinite() == true &&
        target.maximum?.isFinite() == true && target.minimum!! < target.maximum!!
    val hasSafeNeutral = motor?.safeOutput?.let { it.isFinite() && it == 0.0 } == true
    val hasCurrentContract = document.safety.requiresCurrentMonitoring &&
        currentMeasurement != null &&
        currentMeasurement.validMinimum?.let { it.isFinite() && it >= 0.0 } == true
    val naturalStateReady = target?.role == SubsystemFieldRole.TARGET &&
        measuredPosition != null && positionMeasurement != null &&
        velocityMeasurement?.let { measurement ->
            document.stateFields.any { it.fieldId == measurement.fieldId && it.role == SubsystemFieldRole.MEASUREMENT }
        } == true &&
        currentMeasurement?.let { measurement ->
            document.stateFields.any { it.fieldId == measurement.fieldId && it.role == SubsystemFieldRole.MEASUREMENT }
        } == true
    val safetyReady = errorsAbsent && hasSafeNeutral && hasSoftLimits && hasBoundedHoming &&
        hasCurrentContract && document.safety.feedbackTimeoutMs?.let { it > 0L } == true &&
        document.safety.requiresConfigurationHealth && document.safety.latchOutputFaults &&
        document.safety.requiresExplicitNeutralRecovery
    val matchingSavedDocument = documents.any {
        it.uid == document.uid && it.documentId == document.documentId && it.revision == document.revision
    }

    return AcademySubsystemSnapshot(
        isAvailable = true,
        hasPositionMechanismDraft = errorsAbsent &&
            !document.displayName.filter(Char::isLetterOrDigit).startsWith("NewSubsystem", ignoreCase = true) &&
            document.template in setOf(
                SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
                SubsystemTemplate.HOMED_MECHANISM,
                SubsystemTemplate.ELEVATOR_LIFT,
                SubsystemTemplate.ARM_PIVOT,
                SubsystemTemplate.CURRENT_HOMED_MECHANISM,
                SubsystemTemplate.VELOCITY_HOMED_MECHANISM,
            ) && positionLoop != null,
        hasNaturalStateContract = errorsAbsent && naturalStateReady &&
            SubsystemBuilderStage.STATE_AND_BEHAVIOR in visitedStages,
        hasCompleteSafetyContract = safetyReady && SubsystemBuilderStage.SAFETY in visitedStages,
        hasSimulationAndVerification = errorsAbsent && document.generateMockIo && document.generateTest &&
            document.implementation.simulation.support == SubsystemSimulationSupport.GENERATED_MOCK &&
            SubsystemBuilderStage.SIMULATION_AND_TESTING in visitedStages,
        isReviewingGeneratedArtifacts = errorsAbsent && activeStage == SubsystemBuilderStage.REVIEW &&
            previewFiles.isNotEmpty(),
        hasSavedCanonicalDescriptor = errorsAbsent && !dirty && matchingSavedDocument,
    )
}
