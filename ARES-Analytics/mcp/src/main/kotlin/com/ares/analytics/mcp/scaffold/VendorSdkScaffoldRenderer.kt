package com.ares.analytics.mcp.scaffold

import com.areslib.project.AresLeague

/** Supported real vendor-SDK hardware/subsystem scaffold profiles for Wave 2. */
enum class VendorSdkProfile(
    val requiredLeague: AresLeague,
    val unitLabel: String,
    val defaultSafeOutput: Double,
    val defaultMinTarget: Double,
    val defaultMaxTarget: Double,
    val requiredVendordepId: String?,
) {
    FRC_TALONFX_POSITION(
        requiredLeague = AresLeague.FRC,
        unitLabel = "rotations",
        defaultSafeOutput = 0.0,
        defaultMinTarget = -50.0,
        defaultMaxTarget = 50.0,
        requiredVendordepId = "Phoenix6",
    ),
    FRC_TALONFX_VELOCITY(
        requiredLeague = AresLeague.FRC,
        unitLabel = "rps",
        defaultSafeOutput = 0.0,
        defaultMinTarget = -100.0,
        defaultMaxTarget = 100.0,
        requiredVendordepId = "Phoenix6",
    ),
    FRC_SPARKMAX_BRUSHLESS(
        requiredLeague = AresLeague.FRC,
        unitLabel = "volts",
        defaultSafeOutput = 0.0,
        defaultMinTarget = -12.0,
        defaultMaxTarget = 12.0,
        requiredVendordepId = "REVLib",
    ),
    FRC_SPARKFLEX_VORTEX(
        requiredLeague = AresLeague.FRC,
        unitLabel = "volts",
        defaultSafeOutput = 0.0,
        defaultMinTarget = -12.0,
        defaultMaxTarget = 12.0,
        requiredVendordepId = "REVLib",
    ),
    FRC_PHOTONVISION_APRILTAG(
        requiredLeague = AresLeague.FRC,
        unitLabel = "rad",
        defaultSafeOutput = 0.0,
        defaultMinTarget = -Math.PI,
        defaultMaxTarget = Math.PI,
        requiredVendordepId = "PhotonLib",
    ),
    FTC_DCMOTOREX_PIDF(
        requiredLeague = AresLeague.FTC,
        unitLabel = "ticks/s",
        defaultSafeOutput = 0.0,
        defaultMinTarget = -3000.0,
        defaultMaxTarget = 3000.0,
        requiredVendordepId = null,
    ),
    FTC_SERVO_ACTUATOR(
        requiredLeague = AresLeague.FTC,
        unitLabel = "normalized",
        defaultSafeOutput = 0.0,
        defaultMinTarget = 0.0,
        defaultMaxTarget = 1.0,
        requiredVendordepId = null,
    ),
    FTC_LIMELIGHT3A_PINPOINT(
        requiredLeague = AresLeague.FTC,
        unitLabel = "rad",
        defaultSafeOutput = 0.0,
        defaultMinTarget = -Math.PI,
        defaultMaxTarget = Math.PI,
        requiredVendordepId = null,
    ),
    ;

    companion object {
        fun resolve(raw: String): VendorSdkProfile {
            val normalized = raw.trim().uppercase()
            entries.firstOrNull { it.name == normalized }?.let { return it }
            return when (normalized) {
                "FRC_PHOENIX6_TALONFX_FOC" -> FRC_TALONFX_POSITION
                "FRC_REV_SPARK_FLEX" -> FRC_SPARKFLEX_VORTEX
                "FRC_PIGEON2_CANRANGE" -> FRC_TALONFX_POSITION
                "FRC_PHOTON_VISION" -> FRC_PHOTONVISION_APRILTAG
                "FTC_DCMOTOR_EX_SERVO" -> FTC_DCMOTOREX_PIDF
                "FTC_LIMELIGHT_3A", "FTC_VISION_PORTAL_APRILTAG", "FTC_PINPOINT_OTOS" -> FTC_LIMELIGHT3A_PINPOINT
                else -> throw IllegalArgumentException(
                    "Unknown vendorProfile '$raw'. Expected one of: ${entries.joinToString(", ") { it.name }}",
                )
            }
        }
    }
}

data class RenderedScaffoldBundleFile(
    val relativePath: String,
    val content: String,
)

data class VendorSdkBundleRenderResult(
    val profile: VendorSdkProfile,
    val packageName: String,
    val baseClassName: String,
    val subsystemClassName: String,
    val ioContractClassName: String,
    val hardwareAdapterClassName: String,
    val mockAdapterClassName: String,
    val testClassName: String,
    val defaultCanId: Int,
    val defaultHardwareMapName: String,
    val files: List<RenderedScaffoldBundleFile>,
)

/**
 * Generates clean 4-file USER-OWNED Kotlin mechanism bundles (`<Name>IO.kt`, `Mock<Name>IO.kt`,
 * `<Name>Subsystem.kt`, `<Name>SubsystemTest.kt`) that satisfy all ARES zero-GC, `RobotClock`,
 * Redux purity, neutral-on-disable, and coordinate invariants.
 */
internal object VendorSdkScaffoldRenderer {
    fun renderBundle(
        profile: VendorSdkProfile,
        packageName: String,
        baseClassName: String,
        deviceNameOrCanId: String? = null,
    ): VendorSdkBundleRenderResult {
        val cleanPkg = packageName.trim()
        require(cleanPkg.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))) {
            "packageName '$packageName' must be a multi-segment Kotlin package."
        }
        val base = baseClassName.trim()
        require(base.matches(Regex("[A-Z][A-Za-z0-9]{0,63}"))) {
            "className '$baseClassName' must be a PascalCase Kotlin class prefix."
        }
        val pkgPath = cleanPkg.replace('.', '/')
        val mainSourceRoot = when (profile.requiredLeague) {
            AresLeague.FTC -> "TeamCode/src/main/java"
            AresLeague.FRC -> "src/main/kotlin"
            AresLeague.XRP -> throw IllegalArgumentException("Vendor SDK Kotlin bundles only target FTC and FRC.")
        }
        val testSourceRoot = when (profile.requiredLeague) {
            AresLeague.FTC -> "TeamCode/src/test/kotlin"
            AresLeague.FRC -> "src/test/kotlin"
            AresLeague.XRP -> error("Unreachable")
        }
        val hardwareAdapterName = (if (profile.requiredLeague == AresLeague.FTC) "Ftc" else "Frc") + "${base}IO"
        val mockAdapterName = "Mock${base}IO"
        val ioName = "${base}IO"
        val subsystemName = "${base}Subsystem"
        val testName = "${base}SubsystemTest"

        val deviceArg = deviceNameOrCanId?.trim()?.takeIf { it.isNotBlank() }
        val defaultCanId = deviceArg?.toIntOrNull()?.takeIf { it in 0..62 } ?: 20
        val defaultHardwareMapName = deviceArg
            ?.replace(Regex("""[\r\n\t]"""), "")
            ?.takeIf { it.isNotBlank() }
            ?: base.replaceFirstChar(Char::lowercase)

        val ioFile = RenderedScaffoldBundleFile(
            relativePath = "$mainSourceRoot/$pkgPath/$ioName.kt",
            content = VendorSdkHardwareAdapterRenderer.renderIoFile(
                profile = profile,
                pkg = cleanPkg,
                base = base,
                ioName = ioName,
                adapterName = hardwareAdapterName,
                defaultCanId = defaultCanId,
                defaultHardwareMapName = defaultHardwareMapName,
            ),
        )
        val mockFile = RenderedScaffoldBundleFile(
            relativePath = "$mainSourceRoot/$pkgPath/$mockAdapterName.kt",
            content = renderMockIoFile(pkg = cleanPkg, ioName = ioName, mockName = mockAdapterName),
        )
        val subsystemFile = RenderedScaffoldBundleFile(
            relativePath = "$mainSourceRoot/$pkgPath/$subsystemName.kt",
            content = renderSubsystemFile(profile = profile, pkg = cleanPkg, base = base, ioName = ioName, subsystemName = subsystemName),
        )
        val testFile = RenderedScaffoldBundleFile(
            relativePath = "$testSourceRoot/$pkgPath/$testName.kt",
            content = renderUnitTestFile(
                league = profile.requiredLeague,
                pkg = cleanPkg,
                base = base,
                mockName = mockAdapterName,
                subsystemName = subsystemName,
                testName = testName,
            ),
        )

        return VendorSdkBundleRenderResult(
            profile = profile,
            packageName = cleanPkg,
            baseClassName = base,
            subsystemClassName = "$cleanPkg.$subsystemName",
            ioContractClassName = "$cleanPkg.$ioName",
            hardwareAdapterClassName = "$cleanPkg.$hardwareAdapterName",
            mockAdapterClassName = "$cleanPkg.$mockAdapterName",
            testClassName = "$cleanPkg.$testName",
            defaultCanId = defaultCanId,
            defaultHardwareMapName = defaultHardwareMapName,
            files = listOf(ioFile, mockFile, subsystemFile, testFile),
        )
    }

    private fun renderMockIoFile(pkg: String, ioName: String, mockName: String): String = """
        |// ARES OWNERSHIP: USER-OWNED
        |package $pkg
        |
        |import com.areslib.util.RobotClock
        |
        |/** Deterministic zero-GC fake IO implementation of [$ioName] for unit & desktop simulation tests. */
        |class $mockName : $ioName {
        |    var configuredCount: Int = 0
        |        private set
        |    var neutralCount: Int = 0
        |        private set
        |    var lastCommandedOutput: Double = 0.0
        |        private set
        |    var simulatedMeasurement: Double = 0.0
        |    var feedbackHealthy: Boolean = true
        |    private var cachedStampMs: Long = Long.MIN_VALUE
        |
        |    override fun configure(): Boolean {
        |        configuredCount += 1
        |        lastCommandedOutput = 0.0
        |        return true
        |    }
        |
        |    override fun readInputs() {
        |        if (feedbackHealthy && simulatedMeasurement.isFinite()) {
        |            cachedStampMs = RobotClock.currentTimeMillis()
        |        }
        |    }
        |
        |    override fun writeOutput(target: Double) {
        |        if (!target.isFinite()) {
        |            applyNeutralOutputs()
        |            return
        |        }
        |        lastCommandedOutput = target
        |    }
        |
        |    override fun applyNeutralOutputs() {
        |        lastCommandedOutput = 0.0
        |        neutralCount += 1
        |    }
        |
        |    override fun stop() = applyNeutralOutputs()
        |    override fun close() = applyNeutralOutputs()
        |    override fun cachedMeasurement(): Double = simulatedMeasurement
        |    override fun cachedTimestampMillis(): Long = cachedStampMs
        |    override fun isFeedbackValid(): Boolean = feedbackHealthy && cachedStampMs != Long.MIN_VALUE
        |}
        |
    """.trimMargin()

    private fun renderSubsystemFile(
        profile: VendorSdkProfile,
        pkg: String,
        base: String,
        ioName: String,
        subsystemName: String,
    ): String {
        val stateName = "${base}State"
        val actionName = "${base}Action"
        val reducerName = "${base}Reducer"
        return """
            |// ARES OWNERSHIP: USER-OWNED
            |package $pkg
            |
            |import com.areslib.util.RobotClock
            |
            |/** Immutable Redux state for [$subsystemName]. Contains zero hardware SDK references. */
            |data class $stateName(
            |    val configured: Boolean = false,
            |    val enabled: Boolean = false,
            |    val faulted: Boolean = false,
            |    val targetValue: Double = ${profile.defaultSafeOutput},
            |    val measuredValue: Double = 0.0,
            |    val appliedOutput: Double = ${profile.defaultSafeOutput},
            |    val lastFeedbackMillis: Long = Long.MIN_VALUE,
            |    val feedbackValid: Boolean = false,
            |)
            |
            |sealed interface $actionName {
            |    data class Configured(val ok: Boolean) : $actionName
            |    data object Enable : $actionName
            |    data object Disable : $actionName
            |    data object Fault : $actionName
            |    data class SetTarget(val target: Double) : $actionName
            |    data class Feedback(val value: Double, val timestampMillis: Long, val valid: Boolean) : $actionName
            |    data class AppliedOutput(val output: Double) : $actionName
            |}
            |
            |object $reducerName {
            |    private const val MIN_TARGET: Double = ${profile.defaultMinTarget}
            |    private const val MAX_TARGET: Double = ${profile.defaultMaxTarget}
            |    private const val SAFE_OUTPUT: Double = ${profile.defaultSafeOutput}
            |
            |    fun reduce(state: $stateName, action: $actionName): $stateName = when (action) {
            |        is $actionName.Configured -> state.copy(configured = action.ok, faulted = !action.ok)
            |        is $actionName.Enable -> if (state.configured && !state.faulted) state.copy(enabled = true) else state
            |        is $actionName.Disable -> state.copy(enabled = false, targetValue = SAFE_OUTPUT, appliedOutput = SAFE_OUTPUT)
            |        is $actionName.Fault -> state.copy(enabled = false, faulted = true, targetValue = SAFE_OUTPUT, appliedOutput = SAFE_OUTPUT)
            |        is $actionName.SetTarget -> if (!action.target.isFinite()) {
            |            state.copy(enabled = false, faulted = true, targetValue = SAFE_OUTPUT, appliedOutput = SAFE_OUTPUT)
            |        } else {
            |            state.copy(targetValue = action.target.coerceIn(MIN_TARGET, MAX_TARGET))
            |        }
            |        is $actionName.Feedback -> state.copy(
            |            measuredValue = if (action.value.isFinite()) action.value else state.measuredValue,
            |            lastFeedbackMillis = action.timestampMillis,
            |            feedbackValid = action.valid && action.value.isFinite(),
            |        )
            |        is $actionName.AppliedOutput -> state.copy(appliedOutput = action.output)
            |    }
            |}
            |
            |/** Fail-closed subsystem controller coordinating [$ioName] and [$reducerName]. */
            |class $subsystemName(private val io: $ioName) {
            |    var state: $stateName = $stateName()
            |        private set
            |
            |    fun configure(): Boolean {
            |        val ok = io.configure()
            |        state = $reducerName.reduce(state, $actionName.Configured(ok))
            |        if (!ok) io.applyNeutralOutputs()
            |        return ok
            |    }
            |
            |    fun enable() {
            |        state = $reducerName.reduce(state, $actionName.Enable)
            |    }
            |
            |    fun disable() {
            |        state = $reducerName.reduce(state, $actionName.Disable)
            |        io.applyNeutralOutputs()
            |    }
            |
            |    fun stop() = disable()
            |
            |    fun close() {
            |        disable()
            |        io.close()
            |    }
            |
            |    fun fault() {
            |        state = $reducerName.reduce(state, $actionName.Fault)
            |        io.applyNeutralOutputs()
            |    }
            |
            |    fun setTarget(target: Double) {
            |        state = $reducerName.reduce(state, $actionName.SetTarget(target))
            |    }
            |
            |    fun periodic(feedbackTimeoutMs: Long = 250L) {
            |        io.readInputs()
            |        val nowMs = RobotClock.currentTimeMillis()
            |        val stamp = io.cachedTimestampMillis()
            |        val measured = io.cachedMeasurement()
            |        val fresh = io.isFeedbackValid() && stamp != Long.MIN_VALUE && nowMs >= stamp && (nowMs - stamp) <= feedbackTimeoutMs
            |        state = $reducerName.reduce(state, $actionName.Feedback(measured, stamp, fresh))
            |        if (!state.configured || !state.enabled || state.faulted || !fresh) {
            |            if (state.enabled && !fresh) {
            |                // Latch disabled on stale feedback so mechanism cannot auto-resume after a sensor glitch.
            |                state = $reducerName.reduce(state, $actionName.Disable)
            |            }
            |            io.applyNeutralOutputs()
            |            if (state.appliedOutput != ${profile.defaultSafeOutput}) {
            |                state = $reducerName.reduce(state, $actionName.AppliedOutput(${profile.defaultSafeOutput}))
            |            }
            |            return
            |        }
            |        io.writeOutput(state.targetValue)
            |        if (state.appliedOutput != state.targetValue) {
            |            state = $reducerName.reduce(state, $actionName.AppliedOutput(state.targetValue))
            |        }
            |    }
            |}
            |
        """.trimMargin()
    }

    private fun renderUnitTestFile(
        league: AresLeague,
        pkg: String,
        base: String,
        mockName: String,
        subsystemName: String,
        testName: String,
    ): String {
        val testImports = if (league == AresLeague.FTC) {
            """
            |import org.junit.Assert.assertEquals
            |import org.junit.Assert.assertTrue
            |import org.junit.Test
            """.trimMargin()
        } else {
            """
            |import org.junit.jupiter.api.Assertions.assertEquals
            |import org.junit.jupiter.api.Assertions.assertTrue
            |import org.junit.jupiter.api.Test
            """.trimMargin()
        }
        return """
            |// ARES OWNERSHIP: USER-OWNED
            |package $pkg
            |
            |import com.areslib.util.RobotClock
            |$testImports
            |
            |/** Deterministic MockIO safety contract tests for [$subsystemName]. */
            |class $testName {
            |    @Test
            |    fun staleFeedbackAndDisableForceNeutralOutputFor$base() {
            |        RobotClock.useMockTime(1_000L)
            |        try {
            |            val mockIo = $mockName()
            |            val subsystem = $subsystemName(mockIo)
            |
            |            // Never outputs non-zero before configure + enable + fresh feedback
            |            subsystem.setTarget(0.5)
            |            subsystem.periodic()
            |            assertEquals(0.0, mockIo.lastCommandedOutput, 1e-9)
            |
            |            assertTrue(subsystem.configure())
            |            subsystem.enable()
            |            subsystem.setTarget(0.5)
            |            subsystem.periodic()
            |            assertEquals(0.5, mockIo.lastCommandedOutput, 1e-9)
            |
            |            // Stale feedback timeout (>250ms without fresh IO) fails closed to 0.0
            |            RobotClock.useMockTime(2_000L)
            |            mockIo.feedbackHealthy = false
            |            subsystem.periodic()
            |            assertEquals(0.0, mockIo.lastCommandedOutput, 1e-9)
            |
            |            // Disable explicitly neutralizes
            |            mockIo.feedbackHealthy = true
            |            subsystem.disable()
            |            assertEquals(0.0, mockIo.lastCommandedOutput, 1e-9)
            |            assertTrue(mockIo.neutralCount >= 2)
            |        } finally {
            |            RobotClock.useSystemTime()
            |        }
            |    }
            |}
            |
        """.trimMargin()
    }
}
