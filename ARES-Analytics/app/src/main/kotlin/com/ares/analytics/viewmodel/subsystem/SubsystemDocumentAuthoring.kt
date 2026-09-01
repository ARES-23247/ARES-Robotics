package com.ares.analytics.viewmodel.subsystem

import com.ares.analytics.viewmodel.isActuator
import com.ares.analytics.viewmodel.isNumeric
import com.ares.analytics.viewmodel.requiresMeasurement
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemContinuousInputDocument
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFeedforwardDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHardwareScaffolding
import com.areslib.subsystem.SubsystemHomingComparison
import com.areslib.subsystem.SubsystemHomingDocument
import com.areslib.subsystem.SubsystemHomingEvidenceDocument
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemInterlockDocument
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemUnits
import com.areslib.subsystem.SubsystemValueType
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.supportsPlatform

/** Pure form edits for subsystem hardware, homing, control, and interlock authoring. */
internal object SubsystemDocumentAuthoring {
    fun setHomingMethod(
        document: SubsystemDocument,
        method: SubsystemHomingMethod,
    ): SubsystemDocument {
        if (method == SubsystemHomingMethod.NONE) {
            return document.copy(safety = document.safety.copy(homing = SubsystemHomingDocument()))
        }
        val motor = document.hardware.firstOrNull { it.kind == SubsystemHardwareKind.MOTOR }
        val measurements = document.hardware.flatMap { it.measurements }
        val digital = measurements.firstOrNull { it.source == SubsystemMeasurementSource.DIGITAL_STATE }
        val current = measurements.firstOrNull { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
        val velocity = measurements.firstOrNull {
            it.source == SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND
        }
        val evidence = when (method) {
            SubsystemHomingMethod.DIGITAL_SENSOR -> listOfNotNull(
                digital?.let { SubsystemHomingEvidenceDocument(it.fieldId, SubsystemHomingComparison.TRUE) },
            )

            SubsystemHomingMethod.CURRENT_STALL -> listOfNotNull(
                current?.let {
                    SubsystemHomingEvidenceDocument(it.fieldId, SubsystemHomingComparison.AT_OR_ABOVE, 5.0)
                },
            )

            SubsystemHomingMethod.VELOCITY_STALL -> listOfNotNull(
                velocity?.let {
                    SubsystemHomingEvidenceDocument(it.fieldId, SubsystemHomingComparison.ABS_AT_OR_BELOW, 0.5)
                },
            )

            SubsystemHomingMethod.CURRENT_AND_VELOCITY_STALL -> listOfNotNull(
                current?.let {
                    SubsystemHomingEvidenceDocument(it.fieldId, SubsystemHomingComparison.AT_OR_ABOVE, 5.0)
                },
                velocity?.let {
                    SubsystemHomingEvidenceDocument(it.fieldId, SubsystemHomingComparison.ABS_AT_OR_BELOW, 0.5)
                },
            )

            SubsystemHomingMethod.CUSTOM_MEASUREMENT -> emptyList()
            SubsystemHomingMethod.NONE -> error("NONE is handled before evidence generation")
        }
        return document.copy(
            safety = document.safety.copy(
                homing = SubsystemHomingDocument(
                    method = method,
                    actuatorId = motor?.hardwareId,
                    searchOutput = -2.0,
                    evidence = evidence,
                ),
                requiresCurrentMonitoring = document.safety.requiresCurrentMonitoring ||
                    method == SubsystemHomingMethod.CURRENT_STALL ||
                    method == SubsystemHomingMethod.CURRENT_AND_VELOCITY_STALL,
            ),
        )
    }

    fun changeHardwareKind(
        document: SubsystemDocument,
        hardwareId: String,
        kind: SubsystemHardwareKind,
        platform: SubsystemPlatform,
    ): SubsystemDocument {
        require(kind.supportsPlatform(platform)) {
            "Generated ${kind.name.lowercase().replace('_', ' ')} hardware is not supported for $platform projects"
        }
        val existing = document.hardware.firstOrNull { it.hardwareId == hardwareId } ?: return document
        if (existing.kind == kind) return document
        val ownedLoops = document.controlLoops.filter { it.actuatorId == hardwareId }
        val providedFieldIds = existing.measurements.mapTo(linkedSetOf()) { it.fieldId }.apply {
            addAll(ownedLoops.map { it.targetFieldId })
        }
        val scaffold = SubsystemHardwareScaffolding.create(
            kind,
            hardwareId,
            existing.displayName,
            platform,
            hardwareMapName = existing.connection.hardwareMapName ?: hardwareId,
            canId = existing.connection.canId ?: nextCanId(document),
            channel = existing.connection.channel ?: nextChannel(document),
        )
        return document.copy(
            hardware = document.hardware.map {
                when {
                    it.hardwareId == hardwareId -> scaffold.hardware.copy(uid = existing.uid)
                    it.following?.leaderId == hardwareId -> it.copy(following = null)
                    else -> it
                }
            },
            stateFields = document.stateFields.filterNot { it.fieldId in providedFieldIds } + scaffold.stateFields,
            controlLoops = document.controlLoops.filterNot { it.actuatorId == hardwareId } + scaffold.controlLoops,
            safety = if (document.safety.homing.actuatorId == hardwareId) {
                document.safety.copy(homing = SubsystemHomingDocument())
            } else {
                document.safety
            },
        )
    }

    fun changeStateFieldType(
        document: SubsystemDocument,
        fieldId: String,
        type: SubsystemValueType,
    ): SubsystemDocument = document.copy(
        stateFields = document.stateFields.map { field ->
            if (field.fieldId != fieldId) return@map field
            field.copy(
                type = type,
                defaultNumber = if (type == SubsystemValueType.DOUBLE) field.defaultNumber ?: 0.0 else null,
                defaultBoolean = if (type == SubsystemValueType.BOOLEAN) field.defaultBoolean ?: false else null,
                defaultInt = if (type == SubsystemValueType.INT) field.defaultInt ?: 0 else null,
                defaultText = if (type == SubsystemValueType.STRING) field.defaultText.orEmpty() else null,
                unit = field.unit.takeIf { type.isNumeric() },
                minimum = field.minimum.takeIf { type.isNumeric() },
                maximum = field.maximum.takeIf { type.isNumeric() },
            )
        },
    )

    fun createControlLoop(document: SubsystemDocument, loopId: String): SubsystemControlLoopDocument? {
        val owned = document.controlLoops.mapTo(mutableSetOf()) { it.actuatorId }
        val actuator = document.hardware.firstOrNull {
            it.kind.isActuator() && it.following == null && it.hardwareId !in owned
        } ?: return null
        val target = document.stateFields.firstOrNull {
            it.role == SubsystemFieldRole.TARGET && it.type.isNumeric()
        } ?: return null
        val measurement = document.stateFields.firstOrNull {
            it.role == SubsystemFieldRole.MEASUREMENT && it.type.isNumeric() &&
                SubsystemUnits.controlUnitsCompatible(target.unit, it.unit)
        }
        val strategy = when {
            actuator.kind == SubsystemHardwareKind.POSITIONAL_SERVO -> SubsystemControlStrategy.SERVO_POSITION
            measurement != null -> SubsystemControlStrategy.POSITION_PID
            else -> SubsystemControlStrategy.DIRECT
        }
        return SubsystemControlLoopDocument(
            loopId = loopId,
            displayName = "New control",
            strategy = strategy,
            actuatorId = actuator.hardwareId,
            targetFieldId = target.fieldId,
            measurementFieldId = if (strategy.requiresMeasurement()) measurement?.fieldId else null,
            minimumOutput = if (actuator.kind == SubsystemHardwareKind.MOTOR) -12.0 else -1.0,
            maximumOutput = if (actuator.kind == SubsystemHardwareKind.MOTOR) 12.0 else 1.0,
        )
    }

    fun changeControlLoopActuator(
        document: SubsystemDocument,
        loopId: String,
        actuatorId: String,
    ): SubsystemDocument {
        val currentLoop = document.controlLoops.firstOrNull { it.loopId == loopId } ?: return document
        val currentActuator = document.hardware.firstOrNull { it.hardwareId == currentLoop.actuatorId } ?: return document
        val claimedByAnother = document.controlLoops.any { it.loopId != loopId && it.actuatorId == actuatorId }
        val actuator = document.hardware.firstOrNull {
            it.hardwareId == actuatorId && it.kind == currentActuator.kind && it.kind.isActuator() && it.following == null
        }
        if (claimedByAnother || actuator == null) return document
        return document.copy(controlLoops = document.controlLoops.map { loop ->
            if (loop.loopId == loopId) loop.copy(actuatorId = actuatorId) else loop
        })
    }

    fun changeControlLoopTarget(
        document: SubsystemDocument,
        loopId: String,
        targetFieldId: String,
    ): SubsystemDocument {
        val target = document.stateFields.firstOrNull {
            it.fieldId == targetFieldId &&
                it.role in setOf(SubsystemFieldRole.TARGET, SubsystemFieldRole.CONFIGURATION) &&
                it.type.isNumeric()
        } ?: return document
        return document.copy(controlLoops = document.controlLoops.map { loop ->
            if (loop.loopId != loopId) return@map loop
            val currentMeasurement = loop.measurementFieldId?.let { measurementId ->
                document.stateFields.firstOrNull { it.fieldId == measurementId }
            }
            val compatibleMeasurement = currentMeasurement?.takeIf {
                SubsystemUnits.controlUnitsCompatible(target.unit, it.unit)
            } ?: document.stateFields.firstOrNull {
                it.role == SubsystemFieldRole.MEASUREMENT && it.type.isNumeric() &&
                    SubsystemUnits.controlUnitsCompatible(target.unit, it.unit)
            }
            loop.copy(
                targetFieldId = targetFieldId,
                measurementFieldId = if (loop.strategy.requiresMeasurement()) compatibleMeasurement?.fieldId else null,
                continuousInput = loop.continuousInput.copy(
                    enabled = loop.continuousInput.enabled && SubsystemUnits.isCanonicalAngle(target.unit) &&
                        SubsystemUnits.isCanonicalAngle(compatibleMeasurement?.unit),
                ),
            )
        })
    }

    fun changeControlLoopStrategy(
        document: SubsystemDocument,
        loopId: String,
        strategy: SubsystemControlStrategy,
    ): SubsystemDocument {
        val loop = document.controlLoops.firstOrNull { it.loopId == loopId } ?: return document
        val actuator = document.hardware.firstOrNull { it.hardwareId == loop.actuatorId }
        val preferredSource = when (strategy) {
            SubsystemControlStrategy.VELOCITY_PID -> SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
            SubsystemControlStrategy.BANG_BANG,
            -> SubsystemMeasurementSource.MOTOR_POSITION_NATIVE

            else -> null
        }
        val target = document.stateFields.firstOrNull { it.fieldId == loop.targetFieldId }
        val preferredMeasurement = preferredSource?.let { source ->
            actuator?.measurements?.firstOrNull { it.source == source }?.fieldId
        }?.let { fieldId -> document.stateFields.firstOrNull { it.fieldId == fieldId } }
            ?.takeIf { target == null || SubsystemUnits.controlUnitsCompatible(target.unit, it.unit) }
            ?.fieldId
            ?: document.stateFields.firstOrNull {
                it.role == SubsystemFieldRole.MEASUREMENT && it.type.isNumeric() &&
                    (target == null || SubsystemUnits.controlUnitsCompatible(target.unit, it.unit))
            }?.fieldId
        val supportsFeedforward = strategy in setOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
            SubsystemControlStrategy.VELOCITY_PID,
        )
        val supportsContinuousInput = strategy in setOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
        )
        return document.copy(controlLoops = document.controlLoops.map { candidate ->
            if (candidate.loopId != loopId) return@map candidate
            candidate.copy(
                strategy = strategy,
                measurementFieldId = preferredMeasurement.takeIf { strategy.requiresMeasurement() },
                feedforward = candidate.feedforward.takeIf { supportsFeedforward } ?: SubsystemFeedforwardDocument(),
                continuousInput = candidate.continuousInput.takeIf { supportsContinuousInput }
                    ?: SubsystemContinuousInputDocument(),
                hysteresis = candidate.hysteresis.takeIf { strategy == SubsystemControlStrategy.BANG_BANG } ?: 0.0,
            )
        })
    }

    fun createInterlock(
        current: SubsystemDocument,
        documents: Collection<SubsystemDocument>,
        interlockId: String,
    ): SubsystemInterlockDocument? {
        val target = documents.asSequence()
            .filter { it.uid != current.uid }
            .filter { it.implementation.kind.isAresGenerated() }
            .filter { it.stateFields.isNotEmpty() }
            .sortedBy { it.displayName.lowercase() }
            .firstOrNull() ?: return null
        val field = target.stateFields.first()
        return SubsystemInterlockDocument(
            interlockId = interlockId,
            targetSubsystemUid = target.uid,
            targetFieldId = field.fieldId,
            comparison = if (field.type.isNumeric()) InterlockComparison.LESS_THAN else InterlockComparison.EQUALS_STATE,
            thresholdValue = 0.0,
            targetStateName = when (field.type) {
                SubsystemValueType.BOOLEAN -> "false"
                SubsystemValueType.STRING -> ""
                else -> null
            },
            forbiddenZoneDescription = "Prevent mechanism collision",
        )
    }

    private fun nextChannel(document: SubsystemDocument): Int {
        val used = document.hardware.mapNotNullTo(hashSetOf()) { it.connection.channel }
        return (0..31).firstOrNull { it !in used } ?: 0
    }

    private fun nextCanId(document: SubsystemDocument): Int {
        val used = document.hardware.mapNotNullTo(hashSetOf()) { it.connection.canId }
        return (1..62).firstOrNull { it !in used } ?: 1
    }
}
