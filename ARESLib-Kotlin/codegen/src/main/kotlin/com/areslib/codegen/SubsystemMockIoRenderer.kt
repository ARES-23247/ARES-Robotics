package com.areslib.codegen

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemMeasurementSource

/** Renders deterministic mock adapters with physical-adapter safety and recovery parity. */
internal object SubsystemMockIoRenderer {
    fun render(document: SubsystemDocument, pkg: String): String {
        val measurements = document.hardware.flatMap { it.measurements }.mapNotNull { document.field(it.fieldId) }.distinctBy { it.fieldId }
        val fields = measurements.joinToString("\n") { field ->
            "    override var ${field.fieldId}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}"
        }
        val commandFields = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") { device ->
            "    var ${device.hardwareId}Command: Double = ${requireNotNull(device.safeOutput).kotlinDouble()}\n        private set"
        }
        val simSignalFields = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") { device ->
            "    private val ${device.hardwareId}SimSignal = com.areslib.simulation.SimAppliedOutputRegistry.register(${document.uid.quoted()}, ${device.hardwareId.quoted()})"
        }
        val linkagePlantFields = if (document.linkage.enabled) {
            val linkage = document.linkage
            """
                /** Fixed deterministic step used by the generated desktop mechanism plant. */
                var simulationStepSeconds: Double = 0.02
                private val linkagePlant = com.areslib.math.kinematics.TwoDofLinkagePlant(
                    com.areslib.math.kinematics.TwoDofLinkagePlantParameters(
                        linkage = com.areslib.math.kinematics.TwoDofLinkageParameters(
                            l1 = ${linkage.link1LengthMeters.kotlinDouble()},
                            l2 = ${linkage.link2LengthMeters.kotlinDouble()},
                            m1 = ${linkage.link1MassKg.kotlinDouble()},
                            m2 = ${linkage.link2MassKg.kotlinDouble()},
                            rc1 = ${linkage.link1CenterOfMassMeters.kotlinDouble()},
                            rc2 = ${linkage.link2CenterOfMassMeters.kotlinDouble()},
                        ),
                        joint1TorquePerVoltNm = ${linkage.joint1TorquePerVoltNm.kotlinDouble()},
                        joint2TorquePerVoltNm = ${linkage.joint2TorquePerVoltNm.kotlinDouble()},
                        joint1ViscousDampingNmPerRadPerSec = ${linkage.joint1DampingNmPerRadPerSec.kotlinDouble()},
                        joint2ViscousDampingNmPerRadPerSec = ${linkage.joint2DampingNmPerRadPerSec.kotlinDouble()},
                        joint1MinimumRad = ${linkage.joint1MinRad.kotlinDouble()},
                        joint1MaximumRad = ${linkage.joint1MaxRad.kotlinDouble()},
                        joint2MinimumRad = ${linkage.joint2MinRad.kotlinDouble()},
                        joint2MaximumRad = ${linkage.joint2MaxRad.kotlinDouble()},
                    ),
                )
            """.trimIndent()
        } else ""
        val linkageRefresh = if (document.linkage.enabled) {
            val linkage = document.linkage
            """
                linkagePlant.step(
                    ${requireNotNull(linkage.joint1ActuatorId)}Command,
                    ${requireNotNull(linkage.joint2ActuatorId)}Command,
                    simulationStepSeconds,
                )
                ${requireNotNull(linkage.joint1AngleFieldId)} = linkagePlant.joint1PositionRad
                ${requireNotNull(linkage.joint2AngleFieldId)} = linkagePlant.joint2PositionRad
            """.trimIndent()
        } else ""
        val commands = document.actuatorLeaders().joinToString("\n\n") { device ->
            val neutral = requireNotNull(device.safeOutput).kotlinDouble()
            val assignments = (listOf(device to "requested") + document.followersOf(device.hardwareId).map { follower ->
                follower to follower.following!!.transformedExpression("requested")
            }).joinToString("\n        ") { (target, expression) ->
                val applied = target.invertedExpression(expression)
                val bounded = when (target.kind) {
                    SubsystemHardwareKind.MOTOR -> "($applied).coerceIn(-12.0, 12.0)"
                    SubsystemHardwareKind.POSITIONAL_SERVO,
                    SubsystemHardwareKind.INDICATOR_LIGHT -> "($applied).coerceIn(0.0, 1.0)"
                    SubsystemHardwareKind.PRISM_DRIVER -> "($applied).coerceIn(500.0, 2500.0)"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "($applied).coerceIn(-1.0, 1.0)"
                    SubsystemHardwareKind.SOLENOID -> "($applied).coerceIn(0.0, 1.0)"
                    else -> error("Not an actuator")
                }
                "${target.hardwareId}Command = $bounded\n        ${target.hardwareId}SimSignal.publish(${target.hardwareId}Command)"
            }
            """    override fun ${device.commandName()}(value: Double) {
        val requested = value.takeIf(Double::isFinite) ?: $neutral
        if (outputFaultLatched && requested != $neutral) return
        if (requested != $neutral && (!configurationHealthy || !homed || !calibrated ||
                !feedbackValid || !currentReadingValid)) return
        if (failNextWrite) {
            failNextWrite = false
            outputFaultLatched = ${document.safety.latchOutputFaults}
            safe()
            return
        }
        $assignments
    }"""
        }
        val safe = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") {
            val neutral = requireNotNull(it.safeOutput).kotlinDouble()
            "        ${it.hardwareId}Command = ${it.invertedExpression(neutral)}\n        ${it.hardwareId}SimSignal.publish(${it.hardwareId}Command)"
        }
        val currentFields = document.hardware.flatMap { device ->
            device.measurements.filter { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
        }.map { it.fieldId }.distinct()
        val currentValidity = if (document.safety.requiresCurrentMonitoring) {
            currentFields.joinToString(" && ") { "$it.isFinite() && $it >= 0.0" }.ifBlank { "false" }
        } else "true"
        val telemetry = SubsystemIoRenderingSupport.telemetryBody(document)
        val mockHomingCondition = SubsystemIoRenderingSupport.homingConditionExpression(document, "")
        return """
            package $pkg

            import com.areslib.util.RobotClock

            /**
             * Deterministic desktop adapter with hardware-parity fault, freshness, homing,
             * calibration, configuration-health, neutral-recovery, and cleanup controls.
             */
            class Mock${document.kotlinTypeName}IO : ${document.kotlinTypeName}IO {
            $fields
            $commandFields
            $simSignalFields
            ${linkagePlantFields.prependIndent("    ")}
                init {
                    com.areslib.hardware.HardwareRegistry.registerTelemetryDevice(${("Subsystems/${document.documentId}").quoted()}, this)
                }

                override var feedbackValid: Boolean = false
                override var feedbackTimestampMs: Long = 0L
                /** Simulated wiring starts configured; tests and fault injection may set this false. */
                override var configurationHealthy: Boolean = true
                override var homed: Boolean = ${(!document.requiresHoming())}
                override var homingConditionMet: Boolean = false
                override var homingFaultLatched: Boolean = false
                override var calibrated: Boolean = ${(!document.safety.requiresCalibration)}
                override var currentReadingValid: Boolean = ${(!document.safety.requiresCurrentMonitoring)}
                override var outputFaultLatched: Boolean = false
                /** Makes the next snapshot invalid without changing its retained cached values. */
                var failNextRefresh: Boolean = false
                /** Makes the next output/neutral attempt fail and exercise latch behavior. */
                var failNextWrite: Boolean = false
                /** Number of explicit neutral-recovery attempts, including failed writes. */
                var neutralRecoveryAttempts: Int = 0
                    private set
                /** Number of explicit calibration-establishment attempts. */
                var calibrationEstablishmentAttempts: Int = 0
                    private set
                /** Number of fail-closed neutral holds commanded through [safe]. */
                var safeCalls: Int = 0
                    private set
                /** True after idempotent resource cleanup. */
                var closed: Boolean = false
                    private set

                override fun refresh() {
                    if (closed) return
                    if (failNextRefresh) {
                        failNextRefresh = false
                        feedbackValid = false
                        currentReadingValid = ${(!document.safety.requiresCurrentMonitoring)}
                        return
                    }
            ${linkageRefresh.prependIndent("        ")}
                    feedbackTimestampMs = RobotClock.currentTimeMillis()
                    feedbackValid = true
                    currentReadingValid = $currentValidity
                    homingConditionMet = $mockHomingCondition
                }

            $commands

                override fun safe() {
                    safeCalls++
            $safe
                }

                override fun recoverWithNeutral(): Boolean {
                    neutralRecoveryAttempts++
                    if (failNextWrite) {
                        failNextWrite = false
                        outputFaultLatched = ${document.safety.latchOutputFaults}
                        return false
                    }
                    safe()
                    outputFaultLatched = false
                    return true
                }

${SubsystemIoRenderingSupport.automaticRecoveryMethods(document)}

                override fun establishCalibration() {
                    calibrationEstablishmentAttempts++
                    if (configurationHealthy) calibrated = true
                }

${SubsystemIoRenderingSupport.mockHomingMethods(document)}

                override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
$telemetry
                }

                override fun close() {
                    if (closed) return
                    safe()
                    closed = true
                }
            }
        """.trimIndent() + "\n"
    }
}
