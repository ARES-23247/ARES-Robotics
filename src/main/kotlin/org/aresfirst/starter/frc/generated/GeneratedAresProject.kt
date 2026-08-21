@file:Suppress("MagicNumber", "LongMethod")

package org.aresfirst.starter.frc.generated

import com.areslib.codegen.CapabilityArgumentReader
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveMarker
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineRuntimeBindings
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import com.areslib.routine.RoutineManager
import com.areslib.input.ControllerBindingRuntime
import com.areslib.routine.RoutineStartPolicy
import com.areslib.input.AnalogBinding
import com.areslib.input.AnalogBindingListener
import com.areslib.input.AnalogEmissionPolicy
import com.areslib.input.AnalogZone
import com.areslib.input.AnalogZoneListener
import com.areslib.input.AxisThresholdSource
import com.areslib.input.AxisTransform
import com.areslib.input.BindingReleaseReason
import com.areslib.input.ButtonSuppressionState
import com.areslib.input.ChordSource
import com.areslib.input.DigitalBinding
import com.areslib.input.DigitalBindingListener
import com.areslib.input.DigitalBindingTiming
import com.areslib.input.RawButtonSource
import com.areslib.input.SuppressibleButtonSource
import com.areslib.input.SuppressingButtonChordSource
import com.areslib.input.ThresholdDirection
import com.areslib.sequencer.Task
import com.areslib.state.RobotState

/** Stable robot boundary for capabilities referenced by generated project documents. */
interface GeneratedAresProjectCapabilities {
    /** Creates a hand-authored or season action by its catalog key, or null when unavailable. */
    fun createActionTask(actionKey: String, arguments: Map<String, String>): Task? = null

    /**
     * Receives the combined teleop drivetrain command once per frame. Values are normalized
     * (-1..1) after each axis binding's transform, field-centric with CCW-positive rotation;
     * the implementation scales them by drivetrain limits and applies alliance mirroring.
     * [active] is false when the scheme has no drive bindings, so sinks without generated
     * drivetrain control stay inert.
     */
    fun onDriveCommand(vx: Double, vy: Double, omega: Double, active: Boolean) = Unit

    /** Creates a hand-authored condition predicate by its catalog key, or null when unavailable. */
    fun createCondition(conditionKey: String, arguments: Map<String, String>): ((RobotState) -> Boolean)? = null

    /** Platform trajectory adapter; returning null rejects a drive step safely. */
    fun createDriveTask(step: RoutineDriveStep): Task? = null
}

/** Robot scheduler boundary used by generated direct-action controller bindings. */
fun interface GeneratedAresProjectControlTaskSink {
    fun submit(bindingId: String, task: Task)
}

/** Generated from the project's checked-in ARES documents. Do not edit by hand. */
object GeneratedAresProject {
    const val GENERATOR_VERSION: Int = 8
    const val CATALOG_SHA256: String = "579ff4b4c2fca23a876979c92254d669ecf2b27b227377c94a221c5c260ff6cb"
    const val CONTENT_SHA256: String = "3b5a2a29183a9bd83703bc40bfdd2904b9d8023a4841b289bac13f648641aad9"
    const val SOURCE_SHA256: String = "1c4daccebdd19c3389d6a033fabb0d434525c0a60cb7584f2aad20267d887eeb"

    const val PROJECT_ID: String = "ares-frc-starter"
    const val PROJECT_LEAGUE: String = "FRC"
    const val COORDINATE_CONVENTION: String = "BLUE_CORNER_ORIGIN_CCW"
    const val ROBOT_LENGTH_METERS: Double = 0.75
    const val ROBOT_WIDTH_METERS: Double = 0.65
    const val FIELD_LENGTH_METERS: Double = 16.54175
    const val FIELD_WIDTH_METERS: Double = 8.21055

    val knownActionKeys: Set<String> = emptySet()
    val knownConditionKeys: Set<String> = emptySet()

    val routines: Map<String, RoutineDocument> = linkedMapOf(
        "do-nothing" to RoutineDocument(
            schemaVersion = 2,
            documentId = "do-nothing",
            revision = 1,
            parentContentHash = null,
            name = "Do Nothing",
            description = "Match-safe routine that intentionally leaves the robot stationary.",
            steps = listOf(
                RoutineStep(
                    kind = RoutineStepKind.WAIT,
                    stepId = "step-hold-position",
                    durationSeconds = 0.0,
                ),
            ),
        ),
    )

    val autonomousEntries: List<AutonomousCatalogEntry> = listOf(
        AutonomousCatalogEntry(
            entryId = "do-nothing",
            displayName = "Do Nothing",
            description = "Match-safe starter routine that leaves every output neutral.",
            routineId = "do-nothing",
            startingPose = RoutinePose(
                xMeters = 1.0,
                yMeters = 1.0,
                headingRadians = 0.0,
            ),
            authoredAlliance = com.areslib.routine.RoutineAlliance.BLUE,
            mirrorForOppositeAlliance = true,
            sortOrder = 0,
            enabled = true,
        ),
    )
    val DEFAULT_AUTONOMOUS_ENTRY_ID: String? = "do-nothing"

    fun runtimeBindings(registry: GeneratedAresProjectCapabilities): RoutineRuntimeBindings =
        RoutineRuntimeBindings(
            createActionTask = { _, _ -> null },
            createCondition = { _, _ -> null },
            createDriveTask = registry::createDriveTask,
            isActionKnown = knownActionKeys::contains,
            isConditionKnown = knownConditionKeys::contains,
            resourcesForAction = { emptySet() },
        )

    val knownControlSchemeIds: Set<String> = setOf("driver")
    val DEFAULT_CONTROL_SCHEME_ID: String? = "driver"

    /** True when the active scheme binds at least one drivetrain axis. */
    val HAS_GENERATED_DRIVE_BINDINGS: Boolean = true
    private val driveAxisValues = DoubleArray(3)

    /**
     * Publishes the latest drive-axis listener values as one combined command. Disconnects emit
     * zeros and the analog rearm policy holds that neutral until every axis passes through its
     * deadband, so a deflected stick cannot lurch the robot across a controller reconnect.
     */
    fun emitDriveCommand(registry: GeneratedAresProjectCapabilities) {
        registry.onDriveCommand(driveAxisValues[0], driveAxisValues[1], driveAxisValues[2], HAS_GENERATED_DRIVE_BINDINGS)
    }

    /**
     * Builds one allocation-free update runtime per zero-based Driver Station port. Suppressing chords are
     * ordered before constituent buttons and raise their effective press debounce to the chord
     * window, preventing a near-simultaneous chord from leaking a single-button action.
     */
    @Suppress("UNUSED_PARAMETER")
    fun createControllerRuntimes(
        schemeId: String?,
        registry: GeneratedAresProjectCapabilities,
        routineManager: RoutineManager,
        taskSink: GeneratedAresProjectControlTaskSink,
    ): Map<Int, ControllerBindingRuntime> {
        val activeSchemeId = requireNotNull(schemeId) { "A generated control scheme is required" }
        return when (activeSchemeId) {
        "driver" -> run {
            val buttonSuppression_driver_b4def821 = ButtonSuppressionState(buttonCapacity = 128)
            linkedMapOf(
                0 to ControllerBindingRuntime(
                    digitalBindings = emptyList(),
                    analogBindings = listOf(
                        AnalogBinding(
                            axisIndex = 1,
                            transform = AxisTransform(
                                inputMin = -1.0,
                                inputCenter = 0.0,
                                inputMax = 1.0,
                                deadband = 0.1,
                                exponent = 1.0,
                                inverted = true,
                                outputMin = -1.0,
                                outputMax = 1.0,
                            ),
                            listener = object : AnalogBindingListener {
                                override fun onValue(value: Double) {
                                    driveAxisValues[0] = value
                                }
                            },
                            zones = emptyList(),
                            emissionPolicy = AnalogEmissionPolicy.EVERY_UPDATE,
                            changeEpsilon = 1.0E-6,
                            riseRatePerSecond = Double.POSITIVE_INFINITY,
                            fallRatePerSecond = Double.POSITIVE_INFINITY,
                            rearmNeutralThreshold = 0.05,
                        ),
                        AnalogBinding(
                            axisIndex = 4,
                            transform = AxisTransform(
                                inputMin = -1.0,
                                inputCenter = 0.0,
                                inputMax = 1.0,
                                deadband = 0.1,
                                exponent = 1.0,
                                inverted = true,
                                outputMin = -1.0,
                                outputMax = 1.0,
                            ),
                            listener = object : AnalogBindingListener {
                                override fun onValue(value: Double) {
                                    driveAxisValues[2] = value
                                }
                            },
                            zones = emptyList(),
                            emissionPolicy = AnalogEmissionPolicy.EVERY_UPDATE,
                            changeEpsilon = 1.0E-6,
                            riseRatePerSecond = Double.POSITIVE_INFINITY,
                            fallRatePerSecond = Double.POSITIVE_INFINITY,
                            rearmNeutralThreshold = 0.05,
                        ),
                        AnalogBinding(
                            axisIndex = 0,
                            transform = AxisTransform(
                                inputMin = -1.0,
                                inputCenter = 0.0,
                                inputMax = 1.0,
                                deadband = 0.1,
                                exponent = 1.0,
                                inverted = true,
                                outputMin = -1.0,
                                outputMax = 1.0,
                            ),
                            listener = object : AnalogBindingListener {
                                override fun onValue(value: Double) {
                                    driveAxisValues[1] = value
                                }
                            },
                            zones = emptyList(),
                            emissionPolicy = AnalogEmissionPolicy.EVERY_UPDATE,
                            changeEpsilon = 1.0E-6,
                            riseRatePerSecond = Double.POSITIVE_INFINITY,
                            fallRatePerSecond = Double.POSITIVE_INFINITY,
                            rearmNeutralThreshold = 0.05,
                        ),
                    ),
                ),
            )
        }
            else -> throw IllegalArgumentException("Unknown control scheme '$activeSchemeId'")
        }
    }
}
