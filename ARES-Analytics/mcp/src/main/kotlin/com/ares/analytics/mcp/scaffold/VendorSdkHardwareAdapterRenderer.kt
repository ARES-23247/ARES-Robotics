package com.ares.analytics.mcp.scaffold

/**
 * Renders the hardware IO contract and vendor-SDK adapter bodies for [VendorSdkScaffoldRenderer]
 * while keeping each file well within the 750-line maintainability gate.
 */
internal object VendorSdkHardwareAdapterRenderer {
    fun renderIoFile(
        profile: VendorSdkProfile,
        pkg: String,
        base: String,
        ioName: String,
        adapterName: String,
        defaultCanId: Int,
        defaultHardwareMapName: String,
    ): String {
        val header = """
            |// ARES OWNERSHIP: USER-OWNED
            |package $pkg
            |
            |import com.areslib.util.RobotClock
        """.trimMargin()

        val contract = """
            |/** IO contract decoupling pure Redux logic from physical or simulated hardware for $base. */
            |interface $ioName {
            |    fun configure(): Boolean
            |    fun readInputs()
            |    fun writeOutput(target: Double)
            |    fun applyNeutralOutputs()
            |    fun stop()
            |    fun close()
            |    fun cachedMeasurement(): Double
            |    fun cachedTimestampMillis(): Long
            |    fun isFeedbackValid(): Boolean
            |}
        """.trimMargin()

        val impl = when (profile) {
            VendorSdkProfile.FRC_TALONFX_POSITION,
            VendorSdkProfile.FRC_TALONFX_VELOCITY -> renderTalonFxIo(profile, ioName, adapterName, defaultCanId)
            VendorSdkProfile.FRC_SPARKMAX_BRUSHLESS,
            VendorSdkProfile.FRC_SPARKFLEX_VORTEX -> renderRevSparkIo(profile, ioName, adapterName, defaultCanId)
            VendorSdkProfile.FRC_PHOTONVISION_APRILTAG -> renderPhotonVisionIo(ioName, adapterName, defaultHardwareMapName)
            VendorSdkProfile.FTC_DCMOTOREX_PIDF -> renderFtcDcMotorExIo(ioName, adapterName, defaultHardwareMapName)
            VendorSdkProfile.FTC_SERVO_ACTUATOR -> renderFtcServoIo(ioName, adapterName, defaultHardwareMapName)
            VendorSdkProfile.FTC_LIMELIGHT3A_PINPOINT -> renderFtcLimelightPinpointIo(ioName, adapterName, defaultHardwareMapName)
        }

        return "$header\n\n$impl\n"
            .replace("// __IO_CONTRACT__", contract)
    }

    private fun renderTalonFxIo(
        profile: VendorSdkProfile,
        ioName: String,
        adapterName: String,
        defaultCanId: Int,
    ): String {
        val isPosition = profile == VendorSdkProfile.FRC_TALONFX_POSITION
        val requestType = if (isPosition) "PositionVoltage" else "VelocityVoltage"
        val requestMethod = if (isPosition) "withPosition(clamped)" else "withVelocity(clamped)"
        val signalAccessor = if (isPosition) "motor.position" else "motor.velocity"
        return """
            |import com.ctre.phoenix6.BaseStatusSignal
            |import com.ctre.phoenix6.configs.TalonFXConfiguration
            |import com.ctre.phoenix6.controls.NeutralOut
            |import com.ctre.phoenix6.controls.$requestType
            |import com.ctre.phoenix6.hardware.TalonFX
            |
            |// __IO_CONTRACT__
            |
            |/** Phoenix 6 TalonFX adapter (${profile.name}) using batched [BaseStatusSignal.refreshAll]. Requires Phoenix6 vendordep. */
            |class $adapterName(
            |    private val motor: TalonFX = TalonFX($defaultCanId, "rio"),
            |    private val minTarget: Double = ${profile.defaultMinTarget},
            |    private val maxTarget: Double = ${profile.defaultMaxTarget},
            |) : $ioName {
            |    private val feedbackSignal: BaseStatusSignal = $signalAccessor
            |    private val statusSignals: Array<BaseStatusSignal> = arrayOf(feedbackSignal)
            |    private val controlRequest = $requestType(0.0)
            |    private val neutralRequest = NeutralOut()
            |    private var cachedValue: Double = 0.0
            |    private var cachedStampMs: Long = Long.MIN_VALUE
            |    private var healthy: Boolean = false
            |
            |    override fun configure(): Boolean {
            |        val cfg = TalonFXConfiguration()
            |        cfg.Slot0.kP = 1.0
            |        val status = motor.configurator.apply(cfg)
            |        BaseStatusSignal.setUpdateFrequencyForAll(50.0, *statusSignals)
            |        healthy = status.isOK
            |        return healthy
            |    }
            |
            |    override fun readInputs() {
            |        BaseStatusSignal.refreshAll(*statusSignals)
            |        val value = feedbackSignal.valueAsDouble
            |        if (feedbackSignal.status.isOK && value.isFinite()) {
            |            cachedValue = value
            |            cachedStampMs = RobotClock.currentTimeMillis()
            |            healthy = true
            |        } else {
            |            healthy = false
            |        }
            |    }
            |
            |    override fun writeOutput(target: Double) {
            |        if (!target.isFinite()) {
            |            applyNeutralOutputs()
            |            return
            |        }
            |        val clamped = target.coerceIn(minTarget, maxTarget)
            |        motor.setControl(controlRequest.$requestMethod)
            |    }
            |
            |    override fun applyNeutralOutputs() {
            |        motor.setControl(neutralRequest)
            |    }
            |
            |    override fun stop() = applyNeutralOutputs()
            |    override fun close() {
            |        applyNeutralOutputs()
            |        motor.close()
            |    }
            |    override fun cachedMeasurement(): Double = cachedValue
            |    override fun cachedTimestampMillis(): Long = cachedStampMs
            |    override fun isFeedbackValid(): Boolean = healthy && cachedStampMs != Long.MIN_VALUE
            |}
        """.trimMargin()
    }

    private fun renderRevSparkIo(
        profile: VendorSdkProfile,
        ioName: String,
        adapterName: String,
        defaultCanId: Int,
    ): String {
        val isFlex = profile == VendorSdkProfile.FRC_SPARKFLEX_VORTEX
        val motorClass = if (isFlex) "SparkFlex" else "SparkMax"
        val cfgClass = if (isFlex) "SparkFlexConfig" else "SparkMaxConfig"
        return """
            |import com.revrobotics.PersistMode
            |import com.revrobotics.REVLibError
            |import com.revrobotics.ResetMode
            |import com.revrobotics.spark.$motorClass
            |import com.revrobotics.spark.SparkLowLevel.MotorType
            |import com.revrobotics.spark.config.$cfgClass
            |import com.revrobotics.spark.config.SparkBaseConfig.IdleMode
            |
            |// __IO_CONTRACT__
            |
            |/** REVLib 2026 $motorClass adapter (${profile.name}). Requires REVLib vendordep. */
            |class $adapterName(
            |    private val motor: $motorClass = $motorClass($defaultCanId, MotorType.kBrushless),
            |) : $ioName {
            |    private val encoder = motor.encoder
            |    private var cachedValue: Double = 0.0
            |    private var cachedStampMs: Long = Long.MIN_VALUE
            |    private var healthy: Boolean = false
            |
            |    override fun configure(): Boolean {
            |        val config = $cfgClass()
            |        config.idleMode(IdleMode.kBrake)
            |        config.smartCurrentLimit(40)
            |        val error = motor.configure(config, ResetMode.kResetSafeParameters, PersistMode.kPersistParameters)
            |        healthy = error == REVLibError.kOk
            |        return healthy
            |    }
            |
            |    override fun readInputs() {
            |        val reading = encoder.position
            |        if (reading.isFinite()) {
            |            cachedValue = reading
            |            cachedStampMs = RobotClock.currentTimeMillis()
            |            healthy = true
            |        } else {
            |            healthy = false
            |        }
            |    }
            |
            |    override fun writeOutput(target: Double) {
            |        if (!target.isFinite()) {
            |            applyNeutralOutputs()
            |            return
            |        }
            |        motor.setVoltage(target.coerceIn(-12.0, 12.0))
            |    }
            |
            |    override fun applyNeutralOutputs() {
            |        motor.setVoltage(0.0)
            |        motor.stopMotor()
            |    }
            |
            |    override fun stop() = applyNeutralOutputs()
            |    override fun close() {
            |        applyNeutralOutputs()
            |        motor.close()
            |    }
            |    override fun cachedMeasurement(): Double = cachedValue
            |    override fun cachedTimestampMillis(): Long = cachedStampMs
            |    override fun isFeedbackValid(): Boolean = healthy && cachedStampMs != Long.MIN_VALUE
            |}
        """.trimMargin()
    }

    private fun renderPhotonVisionIo(
        ioName: String,
        adapterName: String,
        defaultCameraName: String,
    ): String {
        val safeCamera = escapeKotlinString(defaultCameraName)
        return """
        |import edu.wpi.first.apriltag.AprilTagFieldLayout
        |import edu.wpi.first.math.geometry.Pose2d
        |import edu.wpi.first.math.geometry.Transform3d
        |import org.photonvision.PhotonCamera
        |import org.photonvision.PhotonPoseEstimator
        |
        |// __IO_CONTRACT__
        |
        |/**
        | * PhotonVision CCW-positive blue-corner AprilTag IO adapter (PhotonLib 2026).
        | * Requires PhotonLib vendordep. Never substitutes simulator ground-truth poses.
        | */
        |class $adapterName(
        |    private val camera: PhotonCamera,
        |    private val poseEstimator: PhotonPoseEstimator,
        |) : $ioName {
        |    constructor(
        |        fieldLayout: AprilTagFieldLayout,
        |        robotToCamera: Transform3d,
        |        cameraName: String = "$safeCamera",
        |    ) : this(
        |        PhotonCamera(cameraName),
        |        PhotonPoseEstimator(fieldLayout, robotToCamera),
        |    )
        |
        |    private var cachedHeadingRad: Double = 0.0
        |    private var cachedStampMs: Long = Long.MIN_VALUE
        |    private var configured: Boolean = false
        |    private var hasFreshEstimate: Boolean = false
        |    private var lastPose: Pose2d = Pose2d()
        |
        |    override fun configure(): Boolean {
        |        configured = true
        |        return true
        |    }
        |
        |    override fun readInputs() {
        |        val results = camera.allUnreadResults
        |        var updated = false
        |        for (i in 0 until results.size) {
        |            val estimate = poseEstimator.estimateCoprocMultiTagPose(results[i])
        |            if (estimate.isPresent) {
        |                val pose2d = estimate.get().estimatedPose.toPose2d()
        |                val heading = pose2d.rotation.radians
        |                if (heading.isFinite()) {
        |                    lastPose = pose2d
        |                    cachedHeadingRad = heading
        |                    cachedStampMs = RobotClock.currentTimeMillis()
        |                    updated = true
        |                }
        |            }
        |        }
        |        hasFreshEstimate = updated || cachedStampMs != Long.MIN_VALUE
        |    }
        |
        |    override fun writeOutput(target: Double) = Unit
        |    override fun applyNeutralOutputs() = Unit
        |    override fun stop() = applyNeutralOutputs()
        |    override fun close() = camera.close()
        |    fun cachedEstimatedPose(): Pose2d = lastPose
        |    override fun cachedMeasurement(): Double = cachedHeadingRad
        |    override fun cachedTimestampMillis(): Long = cachedStampMs
        |    override fun isFeedbackValid(): Boolean = configured && hasFreshEstimate && cachedStampMs != Long.MIN_VALUE
        |}
    """.trimMargin()
    }

    private fun renderFtcDcMotorExIo(
        ioName: String,
        adapterName: String,
        defaultHardwareMapName: String,
    ): String {
        val safeName = escapeKotlinString(defaultHardwareMapName)
        return """
        |import com.qualcomm.robotcore.hardware.DcMotor
        |import com.qualcomm.robotcore.hardware.DcMotorEx
        |import com.qualcomm.robotcore.hardware.HardwareMap
        |
        |// __IO_CONTRACT__
        |
        |/** Zero-GC FTC DcMotorEx IO adapter with single-read caching and fail-closed zeroing. */
        |class $adapterName(
        |    hardwareMap: HardwareMap,
        |    deviceName: String = "$safeName",
        |) : $ioName {
        |    private val motor: DcMotorEx = hardwareMap.get(DcMotorEx::class.java, deviceName)
        |    private var cachedVelocityTicks: Double = 0.0
        |    private var cachedStampMs: Long = Long.MIN_VALUE
        |    private var configured: Boolean = false
        |
        |    override fun configure(): Boolean {
        |        motor.zeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE
        |        motor.mode = DcMotor.RunMode.RUN_USING_ENCODER
        |        motor.velocity = 0.0
        |        motor.power = 0.0
        |        configured = true
        |        return true
        |    }
        |
        |    override fun readInputs() {
        |        val velocity = motor.velocity
        |        if (velocity.isFinite()) {
        |            cachedVelocityTicks = velocity
        |            cachedStampMs = RobotClock.currentTimeMillis()
        |        }
        |    }
        |
        |    override fun writeOutput(target: Double) {
        |        if (!configured || !target.isFinite()) {
        |            applyNeutralOutputs()
        |            return
        |        }
        |        motor.velocity = target.coerceIn(-3000.0, 3000.0)
        |    }
        |
        |    override fun applyNeutralOutputs() {
        |        motor.velocity = 0.0
        |        motor.power = 0.0
        |    }
        |
        |    override fun stop() = applyNeutralOutputs()
        |    override fun close() = applyNeutralOutputs()
        |    override fun cachedMeasurement(): Double = cachedVelocityTicks
        |    override fun cachedTimestampMillis(): Long = cachedStampMs
        |    override fun isFeedbackValid(): Boolean = configured && cachedStampMs != Long.MIN_VALUE
        |}
    """.trimMargin()
    }

    private fun renderFtcServoIo(
        ioName: String,
        adapterName: String,
        defaultHardwareMapName: String,
    ): String {
        val safeName = escapeKotlinString(defaultHardwareMapName)
        return """
        |import com.qualcomm.robotcore.hardware.HardwareMap
        |import com.qualcomm.robotcore.hardware.Servo
        |
        |// __IO_CONTRACT__
        |
        |/** Bounded FTC positional Servo adapter with explicit neutral stow on disable/fault. */
        |class $adapterName(
        |    hardwareMap: HardwareMap,
        |    deviceName: String = "$safeName",
        |    private val neutralPosition: Double = 0.0,
        |) : $ioName {
        |    private val servo: Servo = hardwareMap.get(Servo::class.java, deviceName)
        |    private var cachedPos: Double = neutralPosition
        |    private var cachedStampMs: Long = Long.MIN_VALUE
        |    private var configured: Boolean = false
        |
        |    override fun configure(): Boolean {
        |        servo.position = neutralPosition.coerceIn(0.0, 1.0)
        |        configured = true
        |        return true
        |    }
        |
        |    override fun readInputs() {
        |        cachedStampMs = RobotClock.currentTimeMillis()
        |    }
        |
        |    override fun writeOutput(target: Double) {
        |        if (!configured || !target.isFinite()) {
        |            applyNeutralOutputs()
        |            return
        |        }
        |        val safe = target.coerceIn(0.0, 1.0)
        |        cachedPos = safe
        |        servo.position = safe
        |    }
        |
        |    override fun applyNeutralOutputs() {
        |        val safe = neutralPosition.coerceIn(0.0, 1.0)
        |        cachedPos = safe
        |        servo.position = safe
        |    }
        |
        |    override fun stop() = applyNeutralOutputs()
        |    override fun close() = applyNeutralOutputs()
        |    override fun cachedMeasurement(): Double = cachedPos
        |    override fun cachedTimestampMillis(): Long = cachedStampMs
        |    override fun isFeedbackValid(): Boolean = configured && cachedStampMs != Long.MIN_VALUE
        |}
    """.trimMargin()
    }

    private fun renderFtcLimelightPinpointIo(
        ioName: String,
        adapterName: String,
        defaultHardwareMapName: String,
    ): String {
        val safeName = escapeKotlinString(defaultHardwareMapName)
        return """
        |import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
        |import com.qualcomm.hardware.limelightvision.Limelight3A
        |import com.qualcomm.robotcore.hardware.HardwareMap
        |import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
        |
        |// __IO_CONTRACT__
        |
        |/**
        | * FTC Limelight 3A + goBILDA Pinpoint CCW-positive sensor IO adapter.
        | * Uses -robotPoseTargetSpace.orientation.getPitch(RADIANS) (-rotation.y) for planar CCW+ yaw.
        | */
        |class $adapterName(
        |    hardwareMap: HardwareMap,
        |    limelightName: String = "${safeName}Camera",
        |    pinpointName: String = "$safeName",
        |) : $ioName {
        |    private val limelight: Limelight3A = hardwareMap.get(Limelight3A::class.java, limelightName)
        |    private val pinpoint: GoBildaPinpointDriver = hardwareMap.get(GoBildaPinpointDriver::class.java, pinpointName)
        |    private var cachedTargetYawRad: Double = 0.0
        |    private var cachedHeadingRad: Double = 0.0
        |    private var cachedStampMs: Long = Long.MIN_VALUE
        |    private var configured: Boolean = false
        |
        |    override fun configure(): Boolean {
        |        limelight.pipelineSwitch(0)
        |        limelight.start()
        |        configured = true
        |        return true
        |    }
        |
        |    override fun readInputs() {
        |        pinpoint.update()
        |        val ccwHeadingRad = pinpoint.getHeading(AngleUnit.RADIANS)
        |        val llResult = limelight.latestResult
        |        val fiducials = llResult?.fiducialResults
        |        val targetPose = if (fiducials != null && fiducials.isNotEmpty()) fiducials[0].robotPoseTargetSpace else null
        |        // Limelight target-space planar CCW+ yaw is -rotation.y (never rotation.z / getYaw which is camera roll).
        |        val rawPitch = targetPose?.orientation?.getPitch(AngleUnit.RADIANS)
        |        val yawRad = if (rawPitch != null && rawPitch.isFinite()) -rawPitch else Double.NaN
        |        if (ccwHeadingRad.isFinite()) {
        |            cachedHeadingRad = ccwHeadingRad
        |            cachedStampMs = RobotClock.currentTimeMillis()
        |        }
        |        if (yawRad.isFinite()) {
        |            cachedTargetYawRad = yawRad
        |        }
        |    }
        |
        |    override fun writeOutput(target: Double) = Unit
        |    override fun applyNeutralOutputs() = Unit
        |    override fun stop() = limelight.stop()
        |    override fun close() = limelight.stop()
        |    fun cachedRobotHeadingRadians(): Double = cachedHeadingRad
        |    override fun cachedMeasurement(): Double = cachedTargetYawRad
        |    override fun cachedTimestampMillis(): Long = cachedStampMs
        |    override fun isFeedbackValid(): Boolean = configured && cachedStampMs != Long.MIN_VALUE
        |}
    """.trimMargin()
    }

    private fun escapeKotlinString(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("\n", "")
        .replace("\r", "")
}
