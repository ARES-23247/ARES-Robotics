package com.ares.analytics.viewmodel

import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemValueType

internal fun safetyWarnings(document: SubsystemDocument): List<SubsystemProblem> = buildList {
    fun warn(path: String, message: String) = add(SubsystemProblem(SubsystemProblemSeverity.WARNING, path, message))
    val hasActuators = document.hardware.any { it.kind.isActuator() }
    if (!hasActuators) return@buildList
    if (!document.safety.requiresConfigurationHealth) {
        warn("safety.requiresConfigurationHealth", "Configuration health is not gating actuator output.")
    }
    if (!document.safety.latchOutputFaults) {
        warn("safety.latchOutputFaults", "Failed output writes will not latch a fault; verify this is intentional.")
    }
    if (!document.safety.requiresExplicitNeutralRecovery) {
        warn("safety.requiresExplicitNeutralRecovery", "Fault recovery does not require a successful explicit neutral command.")
    }
    if (!document.safety.zeroAllocationPeriodic) {
        warn("safety.zeroAllocationPeriodic", "The periodic-path zero-allocation contract is disabled.")
    }
    if (!document.safety.telemetryEnabled) {
        warn("safety.telemetryEnabled", "Safety telemetry is disabled, reducing pit-side fault visibility.")
    }
    if (document.safety.requiresCurrentMonitoring && document.hardware.none { device ->
            device.measurements.any { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
        }
    ) {
        warn("safety.requiresCurrentMonitoring", "Current monitoring is required but no cached current measurement is configured.")
    }
    document.hardware.forEachIndexed { hardwareIndex, device ->
        if (device.kind != SubsystemHardwareKind.MOTOR) return@forEachIndexed
        device.measurements.forEachIndexed { measurementIndex, measurement ->
            if (measurement.source !in setOf(
                    SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
                    SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND,
                ) || measurement.scale != 1.0
            ) return@forEachIndexed
            val field = document.stateFields.firstOrNull { it.fieldId == measurement.fieldId }
            if (!field?.unit.isNullOrBlank()) {
                warn(
                    "hardware[$hardwareIndex].measurements[$measurementIndex].scale",
                    "${field.displayName} is labeled '${field.unit}' but still uses a 1:1 native motor scale. Review gearing/encoder conversion before tuning or physical use.",
                )
            }
        }
    }
}

internal fun SubsystemHardwareKind.isActuator(): Boolean = this == SubsystemHardwareKind.MOTOR ||
    this == SubsystemHardwareKind.POSITIONAL_SERVO || this == SubsystemHardwareKind.CONTINUOUS_SERVO ||
    this == SubsystemHardwareKind.INDICATOR_LIGHT || this == SubsystemHardwareKind.PRISM_DRIVER ||
    this == SubsystemHardwareKind.SOLENOID

internal fun SubsystemValueType.isNumeric(): Boolean = this == SubsystemValueType.DOUBLE || this == SubsystemValueType.INT

internal fun SubsystemControlStrategy.requiresMeasurement(): Boolean = this == SubsystemControlStrategy.POSITION_PID ||
    this == SubsystemControlStrategy.PROFILED_POSITION_PID || this == SubsystemControlStrategy.VELOCITY_PID ||
    this == SubsystemControlStrategy.BANG_BANG

/** Assigns newly applied GUI templates addresses that do not collide with another subsystem. */
internal fun SubsystemDocument.withAvailableTemplateConnections(
    existingDocuments: Collection<SubsystemDocument>,
): SubsystemDocument {
    val existingHardware = existingDocuments
        .filterNot { it.documentId == documentId }
        .flatMap { it.hardware }
    val usedNames = existingHardware.mapNotNullTo(linkedSetOf()) {
        it.connection.hardwareMapName?.trim()?.takeIf(String::isNotEmpty)
    }
    val usedCan = existingHardware.mapNotNullTo(linkedSetOf()) { device ->
        device.connection.canId?.let { device.connection.canBus.trim().lowercase() to it }
    }
    val usedChannels = existingHardware.flatMapTo(linkedSetOf()) { device ->
        buildList {
            device.connection.channel?.let { add(device.kind.channelNamespace() to it) }
            device.connection.secondaryChannel?.let { add(device.kind.channelNamespace() to it) }
        }
    }
    val namePrefix = documentId
        .lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .ifBlank { "subsystem" }

    return copy(hardware = hardware.map { device ->
        var connection = device.connection
        if (platform == SubsystemPlatform.FTC) {
            val currentName = connection.hardwareMapName?.trim()
            if (!currentName.isNullOrEmpty()) {
                val chosen = if (usedNames.add(currentName)) {
                    currentName
                } else {
                    uniqueTextValue("${namePrefix}_${device.hardwareId}", usedNames)
                }
                connection = connection.copy(hardwareMapName = chosen)
            }
        } else {
            connection.canId?.let { requested ->
                val bus = connection.canBus.trim().lowercase()
                val chosen = requested.takeIf { it in 20..62 && (bus to it) !in usedCan }
                    ?: (20..62).firstOrNull { (bus to it) !in usedCan }
                    ?: requested
                usedCan += bus to chosen
                connection = connection.copy(canId = chosen)
            }
            connection.channel?.let { requested ->
                val namespace = device.kind.channelNamespace()
                val chosen = requested.takeIf { (namespace to it) !in usedChannels }
                    ?: (0..31).firstOrNull { (namespace to it) !in usedChannels }
                    ?: requested
                usedChannels += namespace to chosen
                connection = connection.copy(channel = chosen)
            }
            connection.secondaryChannel?.let { requested ->
                val namespace = device.kind.channelNamespace()
                val chosen = requested.takeIf { (namespace to it) !in usedChannels }
                    ?: (0..31).firstOrNull { (namespace to it) !in usedChannels }
                    ?: requested
                usedChannels += namespace to chosen
                connection = connection.copy(secondaryChannel = chosen)
            }
        }
        device.copy(connection = connection)
    })
}

private fun uniqueTextValue(base: String, used: MutableSet<String>): String {
    var candidate = base
    var suffix = 2
    while (!used.add(candidate)) candidate = "${base}_${suffix++}"
    return candidate
}

private fun SubsystemHardwareKind.channelNamespace(): String = when (this) {
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.CONTINUOUS_SERVO,
    SubsystemHardwareKind.INDICATOR_LIGHT,
    SubsystemHardwareKind.PRISM_DRIVER -> "pwm"
    SubsystemHardwareKind.DIGITAL_INPUT,
    SubsystemHardwareKind.QUADRATURE_ENCODER -> "dio"
    SubsystemHardwareKind.ANALOG_INPUT,
    SubsystemHardwareKind.ABSOLUTE_ENCODER,
    SubsystemHardwareKind.DISTANCE_SENSOR -> "analog"
    SubsystemHardwareKind.SOLENOID -> "solenoid"
    else -> name.lowercase()
}

/** Cross-document ownership is a builder error, not a surprise deferred to Verify & build. */
internal fun projectConnectionProblems(
    document: SubsystemDocument,
    savedDocuments: Collection<SubsystemDocument>,
): List<SubsystemProblem> {
    val others = savedDocuments.filterNot { it.documentId == document.documentId }
    val nameOwners = mutableMapOf<String, String>()
    val canOwners = mutableMapOf<Pair<String, Int>, String>()
    val channelOwners = mutableMapOf<Pair<String, Int>, String>()
    others.forEach { owner ->
        owner.hardware.forEach { device ->
            val label = "${owner.displayName} / ${device.displayName}"
            device.connection.hardwareMapName?.trim()?.takeIf(String::isNotEmpty)?.let { nameOwners.putIfAbsent(it, label) }
            device.connection.canId?.let { canOwners.putIfAbsent(device.connection.canBus.trim().lowercase() to it, label) }
            val namespace = device.kind.channelNamespace()
            device.connection.channel?.let { channelOwners.putIfAbsent(namespace to it, label) }
            device.connection.secondaryChannel?.let { channelOwners.putIfAbsent(namespace to it, label) }
        }
    }
    return buildList {
        document.hardware.forEachIndexed { index, device ->
            device.connection.hardwareMapName?.trim()?.takeIf(String::isNotEmpty)?.let { name ->
                nameOwners[name]?.let { owner ->
                    add(SubsystemProblem(
                        SubsystemProblemSeverity.ERROR,
                        "hardware[$index].connection.hardwareMapName",
                        "Hardware-map name '$name' is already owned by $owner. Give every subsystem device a unique configured name.",
                    ))
                }
            }
            device.connection.canId?.let { canId ->
                val bus = device.connection.canBus.trim().lowercase()
                canOwners[bus to canId]?.let { owner ->
                    add(SubsystemProblem(
                        SubsystemProblemSeverity.ERROR,
                        "hardware[$index].connection.canId",
                        "CAN ID $canId on ${device.connection.canBus} is already owned by $owner. Choose an unused device ID.",
                    ))
                }
            }
            val namespace = device.kind.channelNamespace()
            listOfNotNull(device.connection.channel, device.connection.secondaryChannel).forEach { channel ->
                channelOwners[namespace to channel]?.let { owner ->
                    add(SubsystemProblem(
                        SubsystemProblemSeverity.ERROR,
                        "hardware[$index].connection.channel",
                        "${namespace.uppercase()} channel $channel is already owned by $owner. Choose an unused channel.",
                    ))
                }
            }
        }
    }
}
