package com.ares.analytics.mcp.scaffold

/** Renders zero-GC, `com.areslib.util.RobotClock`-driven FTC Kotlin robot code scaffolds. */
object FtcKotlinScaffoldRenderer {
    data class FtcScaffoldInput(
        val packageName: String,
        val className: String,
        val subsystemName: String = "Mechanism",
        val opModeName: String = "AresTeleOp",
        val opModeGroup: String = "ARES",
    )

    fun renderHardwareIo(input: FtcScaffoldInput): String = """
        |// ARES OWNERSHIP: USER-OWNED
        |package ${input.packageName}
        |
        |import com.areslib.util.RobotClock
        |
        |/**
        | * User-owned FTC hardware IO adapter for ${input.className}.
        | * Caches inputs once per loop in readInputs() and neutralizes outputs on disable/stop/close/fault.
        | */
        |class ${input.className}(
        |    private val applyMotorPower: (Double) -> Unit,
        |    private val readEncoderTicks: () -> Double,
        |) {
        |    private var configured: Boolean = false
        |    private var enabled: Boolean = false
        |    private var faulted: Boolean = false
        |    private var cachedPositionTicks: Double = 0.0
        |    private var lastFeedbackMillis: Long = Long.MIN_VALUE
        |    private var requestedPower: Double = 0.0
        |
        |    fun configure() {
        |        configured = true
        |        faulted = false
        |    }
        |
        |    fun enable() {
        |        if (!configured || faulted) return
        |        enabled = true
        |    }
        |
        |    fun disable() {
        |        enabled = false
        |        requestedPower = 0.0
        |        applyMotorPower(0.0)
        |    }
        |
        |    fun stop() {
        |        disable()
        |    }
        |
        |    fun close() {
        |        disable()
        |    }
        |
        |    fun fault() {
        |        faulted = true
        |        disable()
        |    }
        |
        |    fun readInputs() {
        |        val ticks = readEncoderTicks()
        |        if (ticks.isFinite()) {
        |            cachedPositionTicks = ticks
        |            lastFeedbackMillis = RobotClock.currentTimeMillis()
        |        } else {
        |            fault()
        |        }
        |    }
        |
        |    fun setTargetPower(power: Double) {
        |        if (!power.isFinite()) {
        |            fault()
        |            return
        |        }
        |        requestedPower = power.coerceIn(-1.0, 1.0)
        |    }
        |
        |    fun writeOutputs(feedbackTimeoutMillis: Long = 250L) {
        |        val nowMillis = RobotClock.currentTimeMillis()
        |        val fresh = lastFeedbackMillis != Long.MIN_VALUE && (nowMillis - lastFeedbackMillis) <= feedbackTimeoutMillis
        |        if (!configured || !enabled || faulted || !fresh) {
        |            applyMotorPower(0.0)
        |            return
        |        }
        |        val safePower = if (requestedPower.isFinite()) requestedPower.coerceIn(-1.0, 1.0) else 0.0
        |        applyMotorPower(safePower)
        |    }
        |
        |    fun cachedPosition(): Double = cachedPositionTicks
        |}
        |
    """.trimMargin()

    fun renderSeasonReduxSlice(input: FtcScaffoldInput): String {
        val stateName = "${input.className}State"
        val actionName = "${input.className}Action"
        val reducerName = "${input.className}Reducer"
        return """
            |// ARES OWNERSHIP: USER-OWNED
            |package ${input.packageName}
            |
            |/** Immutable Redux state for ${input.subsystemName}. */
            |data class $stateName(
            |    val configured: Boolean = false,
            |    val enabled: Boolean = false,
            |    val faulted: Boolean = false,
            |    val targetPosition: Double = 0.0,
            |    val measuredPosition: Double = 0.0,
            |    val lastUpdateMillis: Long = 0L,
            |)
            |
            |sealed interface $actionName {
            |    data class Configure(val timestampMillis: Long) : $actionName
            |    data class Enable(val timestampMillis: Long) : $actionName
            |    data class Disable(val timestampMillis: Long) : $actionName
            |    data class Fault(val timestampMillis: Long) : $actionName
            |    data class SetTarget(val targetPosition: Double, val timestampMillis: Long) : $actionName
            |    data class SensorSample(val measuredPosition: Double, val timestampMillis: Long) : $actionName
            |}
            |
            |/** Pure allocation-free reducer for ${input.subsystemName}. */
            |object $reducerName {
            |    fun reduce(state: $stateName, action: $actionName): $stateName = when (action) {
            |        is $actionName.Configure -> state.copy(
            |            configured = true,
            |            faulted = false,
            |            lastUpdateMillis = action.timestampMillis,
            |        )
            |        is $actionName.Enable -> if (state.configured && !state.faulted) {
            |            state.copy(enabled = true, lastUpdateMillis = action.timestampMillis)
            |        } else {
            |            state.copy(enabled = false, lastUpdateMillis = action.timestampMillis)
            |        }
            |        is $actionName.Disable -> state.copy(
            |            enabled = false,
            |            targetPosition = 0.0,
            |            lastUpdateMillis = action.timestampMillis,
            |        )
            |        is $actionName.Fault -> state.copy(
            |            enabled = false,
            |            faulted = true,
            |            targetPosition = 0.0,
            |            lastUpdateMillis = action.timestampMillis,
            |        )
            |        is $actionName.SetTarget -> if (!action.targetPosition.isFinite()) {
            |            state.copy(enabled = false, faulted = true, lastUpdateMillis = action.timestampMillis)
            |        } else {
            |            state.copy(targetPosition = action.targetPosition, lastUpdateMillis = action.timestampMillis)
            |        }
            |        is $actionName.SensorSample -> if (!action.measuredPosition.isFinite()) {
            |            state.copy(enabled = false, faulted = true, lastUpdateMillis = action.timestampMillis)
            |        } else {
            |            state.copy(measuredPosition = action.measuredPosition, lastUpdateMillis = action.timestampMillis)
            |        }
            |    }
            |}
            |
        """.trimMargin()
    }

    fun renderOpMode(input: FtcScaffoldInput, autonomous: Boolean): String {
        val annotation = if (autonomous) {
            "@com.qualcomm.robotcore.eventloop.opmode.Autonomous(name = \"${input.opModeName}\", group = \"${input.opModeGroup}\")"
        } else {
            "@com.qualcomm.robotcore.eventloop.opmode.TeleOp(name = \"${input.opModeName}\", group = \"${input.opModeGroup}\")"
        }
        return """
            |// ARES OWNERSHIP: USER-OWNED
            |package ${input.packageName}
            |
            |import com.areslib.util.RobotClock
            |import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
            |
            |/**
            | * User-owned FTC OpMode shell for ${input.className}.
            | * Uses RobotClock for deterministic dt and neutralizes outputs cleanly on stop or fault.
            | */
            |$annotation
            |class ${input.className} : LinearOpMode() {
            |    private var configured: Boolean = false
            |    private var enabled: Boolean = false
            |
            |    override fun runOpMode() {
            |        configured = true
            |        waitForStart()
            |        if (isStopRequested) {
            |            stop()
            |            return
            |        }
            |        enabled = true
            |        var lastStepMillis = RobotClock.currentTimeMillis()
            |        try {
            |            while (opModeIsActive() && !isStopRequested) {
            |                val nowMillis = RobotClock.currentTimeMillis()
            |                val dtSeconds = ((nowMillis - lastStepMillis) / 1000.0).coerceIn(0.0, 0.05)
            |                lastStepMillis = nowMillis
            |                stepControl(dtSeconds)
            |            }
            |        } finally {
            |            stop()
            |        }
            |    }
            |
            |    private fun stepControl(dtSeconds: Double) {
            |        if (!configured || !enabled || !dtSeconds.isFinite()) {
            |            stop()
            |            return
            |        }
            |        val forward = (-gamepad1.left_stick_y).toDouble()
            |        val safeForward = if (forward.isFinite()) forward.coerceIn(-1.0, 1.0) else 0.0
            |        telemetry.addData("safeForward", safeForward)
            |        telemetry.update()
            |    }
            |
            |    fun stop() {
            |        enabled = false
            |    }
            |}
            |
        """.trimMargin()
    }
}
