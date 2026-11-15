package com.ares.analytics.mcp.scaffold

/** Renders zero-GC, `com.areslib.util.RobotClock`-driven FRC Kotlin scaffolds. */
object FrcKotlinScaffoldRenderer {
    data class FrcScaffoldInput(
        val packageName: String,
        val className: String,
        val subsystemName: String = "Mechanism",
    )

    fun renderHardwareIo(input: FrcScaffoldInput): String = """
        |// ARES OWNERSHIP: USER-OWNED
        |package ${input.packageName}
        |
        |import com.areslib.util.RobotClock
        |
        |/**
        | * User-owned FRC hardware IO adapter for ${input.className}.
        | * Reads sensors once in readInputs() and forces neutral output in disable()/stop()/close().
        | */
        |class ${input.className}(
        |    private val applyVoltage: (Double) -> Unit,
        |    private val readPositionRadians: () -> Double,
        |) {
        |    private var configured: Boolean = false
        |    private var enabled: Boolean = false
        |    private var faulted: Boolean = false
        |    private var cachedPositionRadians: Double = 0.0
        |    private var lastFeedbackMillis: Long = Long.MIN_VALUE
        |    private var requestedVolts: Double = 0.0
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
        |        requestedVolts = 0.0
        |        applyVoltage(0.0)
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
        |        val angleRadians = readPositionRadians()
        |        if (angleRadians.isFinite()) {
        |            cachedPositionRadians = angleRadians
        |            lastFeedbackMillis = RobotClock.currentTimeMillis()
        |        } else {
        |            fault()
        |        }
        |    }
        |
        |    fun setTargetVoltage(volts: Double) {
        |        if (!volts.isFinite()) {
        |            fault()
        |            return
        |        }
        |        requestedVolts = volts.coerceIn(-12.0, 12.0)
        |    }
        |
        |    fun writeOutputs(feedbackTimeoutMillis: Long = 250L) {
        |        val nowMillis = RobotClock.currentTimeMillis()
        |        val fresh = lastFeedbackMillis != Long.MIN_VALUE && (nowMillis - lastFeedbackMillis) <= feedbackTimeoutMillis
        |        if (!configured || !enabled || faulted || !fresh) {
        |            applyVoltage(0.0)
        |            return
        |        }
        |        val safeVolts = if (requestedVolts.isFinite()) requestedVolts.coerceIn(-12.0, 12.0) else 0.0
        |        applyVoltage(safeVolts)
        |    }
        |
        |    fun cachedPosition(): Double = cachedPositionRadians
        |}
        |
    """.trimMargin()

    fun renderTimedRobotHook(input: FrcScaffoldInput): String = """
        |// ARES OWNERSHIP: USER-OWNED
        |package ${input.packageName}
        |
        |import com.areslib.util.RobotClock
        |
        |/**
        | * User-owned TimedRobot / RobotContainer hook for ${input.className}.
        | * Enforces RobotClock timing and explicit disabled-mode output neutralization.
        | */
        |class ${input.className}(
        |    private val applyNeutralOutputs: () -> Unit,
        |    private val stepSubsystems: (Double) -> Unit,
        |) {
        |    private var configured: Boolean = false
        |    private var enabled: Boolean = false
        |    private var lastPeriodicMillis: Long = Long.MIN_VALUE
        |
        |    fun robotInit() {
        |        configured = true
        |        enabled = false
        |        applyNeutralOutputs()
        |    }
        |
        |    fun disabledInit() {
        |        enabled = false
        |        applyNeutralOutputs()
        |    }
        |
        |    fun disabledPeriodic() {
        |        enabled = false
        |        applyNeutralOutputs()
        |    }
        |
        |    fun teleopInit() {
        |        if (!configured) {
        |            disable()
        |            return
        |        }
        |        enabled = true
        |        lastPeriodicMillis = RobotClock.currentTimeMillis()
        |    }
        |
        |    fun teleopPeriodic() {
        |        if (!configured || !enabled) {
        |            applyNeutralOutputs()
        |            return
        |        }
        |        val nowMillis = RobotClock.currentTimeMillis()
        |        val dtSeconds = if (lastPeriodicMillis != Long.MIN_VALUE) {
        |            ((nowMillis - lastPeriodicMillis) / 1000.0).coerceIn(1e-4, 0.1)
        |        } else {
        |            0.02
        |        }
        |        lastPeriodicMillis = nowMillis
        |        stepSubsystems(dtSeconds)
        |    }
        |
        |    fun disable() {
        |        enabled = false
        |        applyNeutralOutputs()
        |    }
        |
        |    fun close() {
        |        disable()
        |    }
        |}
        |
    """.trimMargin()
}
