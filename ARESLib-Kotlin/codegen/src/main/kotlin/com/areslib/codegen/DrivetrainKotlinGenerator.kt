package com.areslib.codegen

import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.drivetrain.VendorSourceOwnership
import com.areslib.drivetrain.validateDrivetrainDocument
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningValue
import com.areslib.tuning.resolveTuningProfiles

data class GeneratedDrivebaseFile(val relativePath: String, val content: String)

/** Deterministic typed plumbing. It never emits or copies editable/vendor-owned source. */
object DrivetrainKotlinGenerator {
    fun generate(
        document: DrivetrainDocument,
        profiles: List<TuningProfileDocument>,
        packageName: String,
        additionalDeclarations: List<TuningParameterDeclaration> = emptyList(),
    ): GeneratedDrivebaseFile {
        val issues = validateDrivetrainDocument(document)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
        require(packageName.matches(PACKAGE)) { "Invalid drivebase package '$packageName'" }
        require(profiles.all { it.authority == TuningProfileAuthority.CANONICAL_CHECKED_IN }) {
            "Build generation accepts only checked-in canonical tuning profiles"
        }
        require(profiles.map { it.projectId }.distinct().size == 1) { "Every tuning profile must target one robot project" }
        val projectId = profiles.singleProjectId()
        require(profiles.all { it.drivebaseUid == null || it.drivebaseUid == document.uid }) {
            "Drivebase-selected tuning profiles must target '${document.uid}'"
        }
        require(document.components.map { it.uid.constantName() }.distinct().size == document.components.size) {
            "Drivetrain component UIDs must remain distinct when converted to Kotlin identifiers"
        }
        require(document.modules.map { it.uid.constantName() }.distinct().size == document.modules.size) {
            "Drivetrain module UIDs must remain distinct when converted to Kotlin identifiers"
        }
        require(document.localization.visionFusion.map { it.uid.constantName() }.distinct().size == document.localization.visionFusion.size) {
            "Vision source UIDs must remain distinct when converted to Kotlin identifiers"
        }
        val declarations = document.parameters + additionalDeclarations
        require(declarations.map { it.uid }.distinct().size == declarations.size) { "Tuning parameter UIDs must be unique across the project" }
        require(declarations.map { it.key }.distinct().size == declarations.size) { "Tuning parameter keys must be unique across the project" }
        require(declarations.map { it.key.constantName() }.distinct().size == declarations.size) {
            "Tuning parameter keys must remain distinct when converted to Kotlin identifiers"
        }
        val resolved = resolveTuningProfiles(profiles, declarations)
        val canonical = requireNotNull(resolved[document.canonicalProfileUid]) {
            "Missing canonical profile '${document.canonicalProfileUid}'"
        }
        val source = buildString {
            appendLine("// ARES OWNERSHIP: GENERATED - DO NOT EDIT")
            appendLine("// Deterministic typed drivebase plumbing. Edit .aresdrivetrain/.arestuning documents instead.")
            appendLine("package $packageName")
            appendLine()
            appendLine("object GeneratedAresDrivebaseConfig {")
            appendLine("    const val DRIVEBASE_UID: String = ${document.uid.q()}")
            appendLine("    const val PROJECT_ID: String = ${projectId.q()}")
            appendLine("    const val DRIVEBASE_ID: String = ${document.drivebaseId.q()}")
            appendLine("    const val KIND: String = ${document.kind.name.q()}")
            appendLine("    const val DOCUMENT_SHA256: String = ${DrivetrainDocumentCodec.contentHash(document).q()}")
            appendLine("    const val CANONICAL_PROFILE_UID: String = ${document.canonicalProfileUid.q()}")
            appendLine("    const val WHEEL_DIAMETER_METERS: Double = ${document.geometry.wheelDiameterMeters.literal()}")
            appendLine("    const val TRACK_WIDTH_METERS: Double = ${document.geometry.trackWidthMeters.literal()}")
            appendLine("    const val WHEEL_BASE_METERS: Double = ${document.geometry.wheelBaseMeters.literal()}")
            appendLine("    const val DRIVE_GEAR_RATIO: Double = ${document.geometry.driveGearRatio.literal()}")
            document.geometry.steerGearRatio?.let { appendLine("    const val STEER_GEAR_RATIO: Double = ${it.literal()}") }
            appendLine("    const val MAX_LINEAR_SPEED_METERS_PER_SECOND: Double = ${document.geometry.maxLinearSpeedMetersPerSecond.literal()}")
            appendLine("    const val MAX_ANGULAR_SPEED_RADIANS_PER_SECOND: Double = ${document.geometry.maxAngularSpeedRadiansPerSecond.literal()}")
            appendLine("    const val STALE_FEEDBACK_TIMEOUT_MS: Long = ${document.safety.staleFeedbackTimeoutMs}L")
            appendLine("    const val CURRENT_VALIDITY_REQUIRED: Boolean = ${document.safety.currentValidityRequired}")
            appendLine("    const val ENABLED_NEUTRAL_MODE: String = ${document.safety.enabledNeutralMode.name.q()}")
            appendLine("    const val DISABLED_POLICY: String = ${document.safety.disabledPolicy.name.q()}")
            document.ctreImport?.let { imported ->
                require(imported.ownership == VendorSourceOwnership.READ_ONLY_VENDOR)
                appendLine("    const val CTRE_VENDOR_SOURCE: String = ${imported.vendorSourcePath.q()}")
                appendLine("    const val CTRE_VENDOR_SOURCE_SHA256: String = ${imported.sourceSha256.q()}")
                appendLine("    const val CTRE_CONSTANTS_CLASS: String = ${imported.drivetrainConstantsClassName.q()}")
                appendLine("    const val CTRE_CAN_BUS: String = ${imported.canBusName.q()}")
            }
            appendLine()
            appendLine("    /** Physical devices. Hardware identity and mounting direction are authored only in the drivebase document. */")
            appendLine("    object Components {")
            document.components.sortedBy { it.uid }.forEach { component ->
                appendLine("        /** ${component.displayName.escapeKdoc()}: ${component.role.name.lowercase().replace('_', ' ')}. */")
                appendLine("        object ${component.uid.constantName()} {")
                appendLine("            const val UID: String = ${component.uid.q()}")
                appendLine("            const val DISPLAY_NAME: String = ${component.displayName.q()}")
                appendLine("            const val ROLE: String = ${component.role.name.q()}")
                appendLine("            const val HARDWARE_ID: String = ${component.hardwareId.q()}")
                appendLine("            val MODULE_UID: String? = ${component.moduleUid?.q() ?: "null"}")
                appendLine("            val CONTROLLER_MODEL: String? = ${component.controllerModel?.q() ?: "null"}")
                appendLine("            val ENCODER_MODEL: String? = ${component.encoderModel?.q() ?: "null"}")
                appendLine("            const val CURRENT_MEASUREMENT_REQUIRED: Boolean = ${component.currentMeasurementRequired}")
                appendLine("            const val CURRENT_MEASUREMENT_AVAILABLE: Boolean = ${component.currentMeasurementAvailable}")
                appendLine("            val CURRENT_LIMIT_AMPS: Double? = ${component.currentLimitAmps?.literal() ?: "null"}")
                appendLine("            val X_METERS: Double? = ${component.xMeters?.literal() ?: "null"}")
                appendLine("            val Y_METERS: Double? = ${component.yMeters?.literal() ?: "null"}")
                appendLine("            const val INVERTED: Boolean = ${component.inverted}")
                appendLine("            const val REQUIRED: Boolean = ${component.required}")
                appendLine("            val LEADER_UID: String? = ${component.leaderUid?.q() ?: "null"}")
                appendLine("        }")
            }
            appendLine("    }")
            appendLine()
            appendLine("    /** Physical swerve/custom module associations and measured center positions. */")
            appendLine("    object Modules {")
            document.modules.sortedBy { it.uid }.forEach { module ->
                appendLine("        /** ${module.displayName.escapeKdoc()} module. */")
                appendLine("        object ${module.uid.constantName()} {")
                appendLine("            const val UID: String = ${module.uid.q()}")
                appendLine("            const val DISPLAY_NAME: String = ${module.displayName.q()}")
                appendLine("            val COMPONENT_UIDS: List<String> = listOf(${module.componentUids.sorted().joinToString { it.q() }})")
                appendLine("            const val X_METERS: Double = ${module.xMeters.literal()}")
                appendLine("            const val Y_METERS: Double = ${module.yMeters.literal()}")
                appendLine("        }")
            }
            appendLine("    }")
            appendLine()
            appendLine("    /** Localization ownership, polarity, and cached-input contract. */")
            appendLine("    object Localization {")
            appendLine("        const val HEADING_SOURCE_UID: String = ${document.localization.headingSourceUid.q()}")
            appendLine("        const val HEADING_CCW_POSITIVE: Boolean = ${document.localization.headingCcwPositive}")
            appendLine("        const val CACHED_INPUTS_REQUIRED: Boolean = ${document.localization.cachedInputsRequired}")
            appendLocalizationSource("PRIMARY_ODOMETRY", document.localization.primaryOdometry, 8)
            document.localization.visionFusion.sortedBy { it.uid }.forEach { source ->
                appendLocalizationSource("VISION_${source.uid.constantName()}", source, 8)
            }
            appendLine("    }")
            appendLine()
            appendLine("    /** Supported command models. Generated values do not implement control policy. */")
            appendLine("    object Control {")
            appendLine("        val SUPPORTED: List<String> = listOf(${document.control.supported.map(Enum<*>::name).sorted().joinToString { it.q() }})")
            appendLine("        const val DEFAULT: String = ${document.control.defaultControl.name.q()}")
            appendLine("        const val FIELD_CENTRIC: Boolean = ${document.control.fieldCentric}")
            appendLine("    }")
            appendLine()
            appendLine("    /** Simulator classes required to preserve robot geometry/profile behavior. */")
            appendLine("    object Simulation {")
            appendLine("        const val MODEL_CLASS: String = ${document.simulation.modelClassName.q()}")
            appendLine("        const val ADAPTER_CLASS: String = ${document.simulation.adapterClassName.q()}")
            appendLine("        const val USES_PHYSICAL_GEOMETRY: Boolean = ${document.simulation.usesPhysicalGeometry}")
            appendLine("        const val USES_CANONICAL_PROFILE: Boolean = ${document.simulation.usesCanonicalProfile}")
            appendLine("        const val BEHAVIORAL_PARITY_REQUIRED: Boolean = ${document.simulation.behavioralParityRequired}")
            appendLine("    }")
            appendLine()
            appendLine("    object Parameters {")
            document.parameters.sortedBy { it.uid }.forEach { declaration ->
                val value = canonical[declaration.uid] ?: declaration.defaultValue
                appendLine("        const val ${declaration.key.constantName()}: ${declaration.type.kotlinType()} = ${value.literal(declaration.type)}")
            }
            appendLine("    }")
            appendLine("}")
        }
        return GeneratedDrivebaseFile("GeneratedAresDrivebaseConfig.kt", source)
    }

    /**
     * Emits the mechanical FTC mecanum constructor/tuning adapter used by zero-code robot shells.
     *
     * Generation is intentionally narrower than the general drivetrain schema. Unsupported
     * localization layouts or incomplete tuning documents fail before source is written rather
     * than falling back to season constants or guessed hardware names.
     */
    fun generateFtcMecanumRuntime(
        document: DrivetrainDocument,
        profiles: List<TuningProfileDocument>,
        packageName: String,
        additionalDeclarations: List<TuningParameterDeclaration> = emptyList(),
    ): GeneratedDrivebaseFile {
        val issues = validateDrivetrainDocument(document)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
        require(document.kind == com.areslib.drivetrain.DrivetrainKind.FTC_MECANUM &&
            document.platform == com.areslib.drivetrain.DrivetrainPlatform.FTC
        ) { "Generated FTC mecanum runtime requires an FTC_MECANUM drivetrain" }
        require(packageName.matches(PACKAGE)) { "Invalid drivebase package '$packageName'" }
        require(profiles.all { it.authority == TuningProfileAuthority.CANONICAL_CHECKED_IN }) {
            "FTC runtime generation accepts only checked-in canonical tuning profiles"
        }

        val usesPinpoint = document.localization.primaryOdometry.source ==
            com.areslib.drivetrain.LocalizationSourceKind.PINPOINT
        val requiredParameterTypes = FTC_MECANUM_COMMON_PARAMETER_TYPES +
            if (usesPinpoint) FTC_MECANUM_PINPOINT_PARAMETER_TYPES else emptyMap()
        val declarations = document.parameters + additionalDeclarations
        val declarationByKey = declarations.associateBy(TuningParameterDeclaration::key)
        requiredParameterTypes.forEach { (key, type) ->
            val declaration = requireNotNull(declarationByKey[key]) {
                "FTC zero-code runtime requires tuning parameter '$key'"
            }
            require(declaration.type == type) {
                "FTC zero-code runtime parameter '$key' must be $type, not ${declaration.type}"
            }
        }
        val assistParameterKeys = FTC_MECANUM_ASSIST_PARAMETER_TYPES.keys.filter(declarationByKey::containsKey)
        require(assistParameterKeys.isEmpty() || assistParameterKeys.size == FTC_MECANUM_ASSIST_PARAMETER_TYPES.size) {
            "FTC drive-assist tuning must declare the complete heading-output and position-hold parameter group"
        }
        assistParameterKeys.forEach { key ->
            val declaration = requireNotNull(declarationByKey[key])
            require(declaration.type == FTC_MECANUM_ASSIST_PARAMETER_TYPES.getValue(key)) {
                "FTC zero-code runtime parameter '$key' has the wrong type"
            }
        }
        val canonical = requireNotNull(resolveTuningProfiles(profiles, declarations)[document.canonicalProfileUid]) {
            "Missing canonical profile '${document.canonicalProfileUid}'"
        }
        (requiredParameterTypes.keys + assistParameterKeys).forEach { key ->
            val declaration = requireNotNull(declarationByKey[key])
            require(canonical[declaration.uid] != null) { "Canonical profile has no value for '$key'" }
        }

        val driveMotors = document.components.filter {
            it.role == com.areslib.drivetrain.DrivetrainComponentRole.DRIVE_MOTOR
        }
        fun quadrant(front: Boolean, left: Boolean): com.areslib.drivetrain.DrivetrainComponentDocument {
            val matches = driveMotors.filter { motor ->
                val x = motor.xMeters
                val y = motor.yMeters
                x != null && y != null && (x > 0.0) == front && (y > 0.0) == left
            }
            require(matches.size == 1) {
                "FTC zero-code runtime requires exactly one required drive motor in each measured wheel quadrant"
            }
            return matches.single()
        }
        val frontLeft = quadrant(front = true, left = true)
        val frontRight = quadrant(front = true, left = false)
        val rearLeft = quadrant(front = false, left = true)
        val rearRight = quadrant(front = false, left = false)
        require(driveMotors.all { it.required }) { "FTC zero-code runtime requires all four drive motors at startup" }
        require(driveMotors.none { it.currentLimitAmps != null }) {
            "FTC drive motor currentLimitAmps cannot be claimed until the selected FTC adapter enforces it"
        }

        val primary = document.localization.primaryOdometry
        val pinpoint = when (primary.source) {
            com.areslib.drivetrain.LocalizationSourceKind.PINPOINT -> {
                val components = primary.componentUids.map { uid -> document.components.single { it.uid == uid } }
                require(components.size == 1 && components.single().role ==
                    com.areslib.drivetrain.DrivetrainComponentRole.ODOMETRY_SENSOR
                ) { "FTC Pinpoint localization requires exactly one odometry-sensor component" }
                require(components.single().required) {
                    "The primary FTC Pinpoint component must be required at startup"
                }
                components.single()
            }
            com.areslib.drivetrain.LocalizationSourceKind.WHEEL_ENCODERS_IMU -> null
            else -> error("FTC zero-code runtime supports PINPOINT or WHEEL_ENCODERS_IMU primary localization")
        }
        val gyro = document.components.filter {
            it.role == com.areslib.drivetrain.DrivetrainComponentRole.GYRO
        }.also { require(it.size <= 1) { "FTC zero-code runtime supports at most one gyro component" } }.singleOrNull()
        if (primary.source == com.areslib.drivetrain.LocalizationSourceKind.WHEEL_ENCODERS_IMU) {
            require(gyro?.required == true) { "Wheel-encoder FTC localization requires one startup-required IMU" }
        }
        val visionComponents = document.localization.visionFusion.flatMap { source ->
            require(source.source == com.areslib.drivetrain.LocalizationSourceKind.EXTERNAL &&
                source.implementationClassName == "com.areslib.ftc.vision.FtcLimelightIO"
            ) { "FTC zero-code runtime currently supports only FtcLimelightIO vision fusion" }
            source.componentUids.map { uid -> document.components.single { it.uid == uid } }
        }.distinctBy { it.uid }
        require(visionComponents.size <= 1) { "FTC zero-code runtime currently supports at most one Limelight" }
        val limelight = visionComponents.singleOrNull()

        val expectedAngularSpeed = document.geometry.maxLinearSpeedMetersPerSecond /
            ((document.geometry.trackWidthMeters + document.geometry.wheelBaseMeters) * 0.5)
        require(kotlin.math.abs(expectedAngularSpeed - document.geometry.maxAngularSpeedRadiansPerSecond) <= 1e-9) {
            "FTC mecanum maximum angular speed must equal maxLinearSpeed / ((trackWidth + wheelBase) / 2)"
        }

        fun constant(key: String): String = requireNotNull(declarationByKey[key]).key.constantName()
        fun hardwareConstant(component: com.areslib.drivetrain.DrivetrainComponentDocument): String =
            "GeneratedAresDrivebaseConfig.Components.${component.uid.constantName()}.HARDWARE_ID"
        fun optionalHardware(component: com.areslib.drivetrain.DrivetrainComponentDocument?): String =
            component?.let(::hardwareConstant) ?: "null"
        val requiredPreflight = buildList {
            pinpoint?.takeIf { it.required }?.let {
                add("hardwareMap.get(com.qualcomm.hardware.gobilda.GoBildaPinpointDriver::class.java, ${hardwareConstant(it)})")
            }
            gyro?.takeIf { it.required }?.let {
                add("hardwareMap.get(com.qualcomm.robotcore.hardware.IMU::class.java, ${hardwareConstant(it)})")
            }
            limelight?.takeIf { it.required }?.let {
                add("hardwareMap.get(com.qualcomm.hardware.limelightvision.Limelight3A::class.java, ${hardwareConstant(it)})")
            }
        }.joinToString("\n") { "        $it" }
        val reduxKeys = FTC_MECANUM_COMMON_REDUX_KEYS +
            FTC_MECANUM_ASSIST_PARAMETER_TYPES.keys.filter(declarationByKey::containsKey) +
            if (usesPinpoint) FTC_MECANUM_PINPOINT_REDUX_KEYS else emptySet()
        val reduxUids = reduxKeys.map { key ->
            requireNotNull(declarationByKey[key]).uid
        }.sorted()
        val neutral = when (document.safety.enabledNeutralMode) {
            com.areslib.drivetrain.DrivetrainNeutralMode.BRAKE -> "BRAKE"
            com.areslib.drivetrain.DrivetrainNeutralMode.COAST -> "FLOAT"
        }

        val source = buildString {
            appendLine("// ARES OWNERSHIP: GENERATED - DO NOT EDIT")
            appendLine("// Hardware-neutral FTC mecanum construction from canonical .ares documents.")
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.areslib.control.tuning.PIDFCoefficients")
            appendLine("import com.areslib.control.tuning.SimpleFeedforwardCoeffs")
            appendLine("import com.areslib.ftc.FtcMecanumRobot")
            appendLine("import com.areslib.state.*")
            appendLine("import com.areslib.tuning.TypedTuningRuntime")
            appendLine("import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver")
            appendLine("import com.qualcomm.robotcore.hardware.DcMotor")
            appendLine("import com.qualcomm.robotcore.hardware.DcMotorSimple")
            appendLine("import com.qualcomm.robotcore.hardware.HardwareMap")
            appendLine("import org.firstinspires.ftc.robotcore.external.Telemetry")
            appendLine()
            appendLine("/** Mechanical bridge from generated physical identity and tuning into ARESLib FTC runtime. */")
            appendLine("object GeneratedAresFtcMecanumRuntimeConfig {")
            appendLine("    private val values get() = GeneratedAresTuningConfig.Parameters")
            appendLine()
            appendLine("    /** Canonical motor direction for the front-left wheel. */")
            appendLine("    val frontLeftDirection: DcMotorSimple.Direction get() = direction(GeneratedAresDrivebaseConfig.Components.${frontLeft.uid.constantName()}.INVERTED)")
            appendLine("    /** Canonical motor direction for the front-right wheel. */")
            appendLine("    val frontRightDirection: DcMotorSimple.Direction get() = direction(GeneratedAresDrivebaseConfig.Components.${frontRight.uid.constantName()}.INVERTED)")
            appendLine("    /** Canonical motor direction for the rear-left wheel. */")
            appendLine("    val rearLeftDirection: DcMotorSimple.Direction get() = direction(GeneratedAresDrivebaseConfig.Components.${rearLeft.uid.constantName()}.INVERTED)")
            appendLine("    /** Canonical motor direction for the rear-right wheel. */")
            appendLine("    val rearRightDirection: DcMotorSimple.Direction get() = direction(GeneratedAresDrivebaseConfig.Components.${rearRight.uid.constantName()}.INVERTED)")
            if (usesPinpoint) {
                appendLine("    /** Pinpoint X-pod direction derived from the checked-in tuning profile. */")
                appendLine("    val pinpointXDirection: GoBildaPinpointDriver.EncoderDirection get() = encoderDirection(values.${constant("localization.pinpointXReversed")})")
                appendLine("    /** Pinpoint Y-pod direction derived from the checked-in tuning profile. */")
                appendLine("    val pinpointYDirection: GoBildaPinpointDriver.EncoderDirection get() = encoderDirection(values.${constant("localization.pinpointYReversed")})")
            }
            appendLine("    /** Neutral policy applied to every drive motor while output is zero. */")
            appendLine("    val driveZeroPowerBehavior: DcMotor.ZeroPowerBehavior get() = DcMotor.ZeroPowerBehavior.$neutral")
            appendLine()
            appendLine("    /** Creates the shared FTC drivetrain. Required hardware lookups fail before the loop starts. */")
            appendLine("    fun createRobot(")
            appendLine("        hardwareMap: HardwareMap,")
            appendLine("        localTelemetry: Telemetry? = null,")
            appendLine("        limelightProxyEnabled: Boolean = false,")
            appendLine("    ): FtcMecanumRobot {")
            if (requiredPreflight.isNotEmpty()) appendLine(requiredPreflight)
            appendLine("        val tuning = initialTuningState()")
            appendLine("        return FtcMecanumRobot(")
            appendLine("            hardwareMap = hardwareMap,")
            appendLine("            flName = ${hardwareConstant(frontLeft)},")
            appendLine("            frName = ${hardwareConstant(frontRight)},")
            appendLine("            rlName = ${hardwareConstant(rearLeft)},")
            appendLine("            rrName = ${hardwareConstant(rearRight)},")
            appendLine("            flDirection = frontLeftDirection,")
            appendLine("            frDirection = frontRightDirection,")
            appendLine("            rlDirection = rearLeftDirection,")
            appendLine("            rrDirection = rearRightDirection,")
            appendLine("            pinpointName = ${optionalHardware(pinpoint)},")
            appendLine("            limelightName = ${optionalHardware(limelight)},")
            appendLine("            imuName = ${optionalHardware(gyro)},")
            appendLine("            localTelemetry = localTelemetry,")
            appendLine("            trackWidthMeters = GeneratedAresDrivebaseConfig.TRACK_WIDTH_METERS,")
            appendLine("            wheelBaseMeters = GeneratedAresDrivebaseConfig.WHEEL_BASE_METERS,")
            appendLine("            maxWheelSpeedMetersPerSecond = GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND,")
            appendLine("            driveZeroPowerBehavior = driveZeroPowerBehavior,")
            appendLine("            limelightProxyEnabled = limelightProxyEnabled,")
            appendLine("            headingGains = tuning.drive.headingGains,")
            appendLine("            headingDeadzoneDeg = tuning.drive.headingDeadzoneDeg,")
            appendLine("            driveFeedforward = tuning.drive.driveFeedforward,")
            appendLine("            useClosedLoopVelocity = values.${constant("drive.closedLoopVelocity")},")
            appendLine("            pathTranslationGains = tuning.drive.pathTranslationGains,")
            appendLine("            pathRotationGains = tuning.drive.pathRotationGains,")
            appendLine("            odomQx = tuning.localization.ekfNoise.qX,")
            appendLine("            odomQy = tuning.localization.ekfNoise.qY,")
            appendLine("            odomQtheta = tuning.localization.ekfNoise.qTheta,")
            if (usesPinpoint) {
                appendLine("            pinpointXOffsetMm = values.${constant("localization.pinpointXOffsetMm")},")
                appendLine("            pinpointYOffsetMm = values.${constant("localization.pinpointYOffsetMm")},")
                appendLine("            pinpointEncoderResolution = pinpointEncoderResolution,")
                appendLine("            pinpointXDirection = pinpointXDirection,")
                appendLine("            pinpointYDirection = pinpointYDirection,")
                appendLine("            pinpointIsCcwPositive = values.${constant("localization.pinpointCcwPositive")},")
            } else {
                appendLine("            pinpointXOffsetMm = 0.0,")
                appendLine("            pinpointYOffsetMm = 0.0,")
                appendLine("            pinpointEncoderResolution = null,")
                appendLine("            pinpointXDirection = encoderDirection(false),")
                appendLine("            pinpointYDirection = encoderDirection(false),")
                appendLine("            pinpointIsCcwPositive = true,")
            }
            appendLine("            motorGains = tuning.drive.ftc.motorGains?.takeUnless(::isDefaultMotorPidf),")
            appendLine("            ticksPerMeter = values.${constant("drive.ticksPerMeter")},")
            appendLine("            initialTuningState = tuning,")
            appendLine("        )")
            appendLine("    }")
            appendLine()
            if (usesPinpoint) {
                appendLine("    val pinpointEncoderResolution: Double?")
                appendLine("        get() = values.${constant("localization.pinpointEncoderResolution")}.takeIf { it > 0.0 }")
                appendLine()
            }
            appendLine("    /** True only when [withRuntimeValues] consumes the approved live value. */")
            appendLine("    fun supportsRuntimeParameter(parameterUid: String): Boolean = parameterUid in reduxParameterUids")
            appendLine()
            appendLine("    fun initialTuningState(): TuningState = tuningState(TuningState(), null)")
            appendLine()
            appendLine("    /** Zero custom gains mean: retain the motor type's FTC SDK controller defaults. */")
            appendLine("    private fun isDefaultMotorPidf(gains: PIDFCoefficients): Boolean =")
            appendLine("        gains.kP == 0.0 && gains.kI == 0.0 && gains.kD == 0.0 && gains.kF == 0.0")
            appendLine()
            appendLine("    fun withRuntimeValues(current: TuningState, runtime: TypedTuningRuntime): TuningState =")
            appendLine("        tuningState(current, runtime)")
            appendLine()
            appendLine("    private fun tuningState(current: TuningState, runtime: TypedTuningRuntime?): TuningState {")
            appendLine("        fun number(uid: String, canonical: Double): Double = runtime?.double(uid) ?: canonical")
            appendLine("        val drive = current.drive.copy(")
            appendLine("            trackWidthMeters = GeneratedAresDrivebaseConfig.TRACK_WIDTH_METERS,")
            appendLine("            wheelBaseMeters = GeneratedAresDrivebaseConfig.WHEEL_BASE_METERS,")
            appendLine("            pathTranslationGains = PIDFCoefficients(number(${declarationByKey.getValue("drive.pathTranslationKp").uid.q()}, values.${constant("drive.pathTranslationKp")}), 0.0, number(${declarationByKey.getValue("drive.pathTranslationKd").uid.q()}, values.${constant("drive.pathTranslationKd")})),")
            appendLine("            pathRotationGains = PIDFCoefficients(number(${declarationByKey.getValue("drive.pathRotationKp").uid.q()}, values.${constant("drive.pathRotationKp")}), 0.0, number(${declarationByKey.getValue("drive.pathRotationKd").uid.q()}, values.${constant("drive.pathRotationKd")})),")
            appendLine("            headingGains = PIDFCoefficients(number(${declarationByKey.getValue("drive.headingKp").uid.q()}, values.${constant("drive.headingKp")}), number(${declarationByKey.getValue("drive.headingKi").uid.q()}, values.${constant("drive.headingKi")}), number(${declarationByKey.getValue("drive.headingKd").uid.q()}, values.${constant("drive.headingKd")})),")
            appendLine("            headingDeadzoneDeg = number(${declarationByKey.getValue("drive.headingDeadzoneDeg").uid.q()}, values.${constant("drive.headingDeadzoneDeg")}),")
            if (assistParameterKeys.isNotEmpty()) {
                appendLine("            headingMaxOutputLimit = number(${declarationByKey.getValue("drive.headingMaxOutputLimit").uid.q()}, values.${constant("drive.headingMaxOutputLimit")}),")
                appendLine("            positionHoldGains = PIDFCoefficients(number(${declarationByKey.getValue("drive.positionHoldKp").uid.q()}, values.${constant("drive.positionHoldKp")}), number(${declarationByKey.getValue("drive.positionHoldKi").uid.q()}, values.${constant("drive.positionHoldKi")}), number(${declarationByKey.getValue("drive.positionHoldKd").uid.q()}, values.${constant("drive.positionHoldKd")})),")
                appendLine("            positionHoldDeadzoneMeters = number(${declarationByKey.getValue("drive.positionHoldDeadzoneMeters").uid.q()}, values.${constant("drive.positionHoldDeadzoneMeters")}),")
                appendLine("            positionHoldMaxOutputLimit = number(${declarationByKey.getValue("drive.positionHoldMaxOutputLimit").uid.q()}, values.${constant("drive.positionHoldMaxOutputLimit")}),")
            }
            appendLine("            driveFeedforward = SimpleFeedforwardCoeffs(number(${declarationByKey.getValue("drive.feedforwardKs").uid.q()}, values.${constant("drive.feedforwardKs")}), number(${declarationByKey.getValue("drive.feedforwardKv").uid.q()}, values.${constant("drive.feedforwardKv")}), number(${declarationByKey.getValue("drive.feedforwardKa").uid.q()}, values.${constant("drive.feedforwardKa")})),")
            appendLine("            pathVelocityScale = number(${declarationByKey.getValue("drive.pathVelocityScale").uid.q()}, values.${constant("drive.pathVelocityScale")}),")
            appendLine("            pathAccelerationLimit = number(${declarationByKey.getValue("drive.pathAccelerationLimit").uid.q()}, values.${constant("drive.pathAccelerationLimit")}),")
            appendLine("            ftc = FtcDriveTuningState(")
            appendLine("                ticksPerMeter = number(${declarationByKey.getValue("drive.ticksPerMeter").uid.q()}, values.${constant("drive.ticksPerMeter")}),")
            appendLine("                motorGains = PIDFCoefficients(number(${declarationByKey.getValue("drive.motorKp").uid.q()}, values.${constant("drive.motorKp")}), number(${declarationByKey.getValue("drive.motorKi").uid.q()}, values.${constant("drive.motorKi")}), number(${declarationByKey.getValue("drive.motorKd").uid.q()}, values.${constant("drive.motorKd")}), number(${declarationByKey.getValue("drive.motorKf").uid.q()}, values.${constant("drive.motorKf")})),")
            appendLine("            ),")
            appendLine("        )")
            appendLine("        val localization = current.localization.copy(")
            appendLine("            ekfNoise = EkfProcessNoiseTuningState(number(${declarationByKey.getValue("localization.ekfQx").uid.q()}, values.${constant("localization.ekfQx")}), number(${declarationByKey.getValue("localization.ekfQy").uid.q()}, values.${constant("localization.ekfQy")}), number(${declarationByKey.getValue("localization.ekfQtheta").uid.q()}, values.${constant("localization.ekfQtheta")})),")
            if (usesPinpoint) {
                appendLine("            ftcPinpoint = FtcPinpointTuningState(number(${declarationByKey.getValue("localization.pinpointXOffsetMm").uid.q()}, values.${constant("localization.pinpointXOffsetMm")}), number(${declarationByKey.getValue("localization.pinpointYOffsetMm").uid.q()}, values.${constant("localization.pinpointYOffsetMm")}), number(${declarationByKey.getValue("localization.pinpointEncoderResolution").uid.q()}, values.${constant("localization.pinpointEncoderResolution")})),")
            }
            appendLine("        )")
            appendLine("        return current.copy(drive = drive, localization = localization)")
            appendLine("    }")
            appendLine()
            appendLine("    private fun direction(inverted: Boolean): DcMotorSimple.Direction =")
            appendLine("        if (inverted) DcMotorSimple.Direction.REVERSE else DcMotorSimple.Direction.FORWARD")
            appendLine()
            appendLine("    private fun encoderDirection(reversed: Boolean): GoBildaPinpointDriver.EncoderDirection {")
            appendLine("        val directions = GoBildaPinpointDriver.EncoderDirection.values()")
            appendLine("        return if (reversed) directions.last() else directions.first()")
            appendLine("    }")
            appendLine()
            appendLine("    private val reduxParameterUids: Set<String> = setOf(")
            reduxUids.forEach { appendLine("        ${it.q()},") }
            appendLine("    )")
            appendLine("}")
        }
        return GeneratedDrivebaseFile("GeneratedAresFtcMecanumRuntimeConfig.kt", source)
    }

    fun generateProjectTuning(
        projectId: String,
        canonicalProfileUid: String,
        drivebaseUid: String?,
        declarations: List<TuningParameterDeclaration>,
        profiles: List<TuningProfileDocument>,
        packageName: String,
    ): GeneratedDrivebaseFile {
        require(profiles.all { it.projectId == projectId && it.authority == TuningProfileAuthority.CANONICAL_CHECKED_IN })
        require(declarations.map { it.uid }.distinct().size == declarations.size) { "Project tuning parameter UIDs are duplicated" }
        require(declarations.map { it.key }.distinct().size == declarations.size) { "Project tuning parameter keys are duplicated" }
        require(declarations.map { it.key.constantName() }.distinct().size == declarations.size) {
            "Project tuning parameter keys collide as Kotlin identifiers"
        }
        val canonical = requireNotNull(resolveTuningProfiles(profiles, declarations)[canonicalProfileUid]) {
            "Missing canonical profile '$canonicalProfileUid'"
        }
        require(profiles.firstOrNull { it.uid == canonicalProfileUid }?.drivebaseUid == drivebaseUid) {
            "Canonical profile '$canonicalProfileUid' selects a different drivebase"
        }
        val source = buildString {
            appendLine("// ARES OWNERSHIP: GENERATED - DO NOT EDIT")
            appendLine("// Project-wide typed tuning metadata and canonical values.")
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.areslib.tuning.*")
            appendLine()
            appendLine("object GeneratedAresTuningConfig {")
            appendLine("    const val PROJECT_ID: String = ${projectId.q()}")
            appendLine("    val DRIVEBASE_UID: String? = ${drivebaseUid?.q() ?: "null"}")
            appendLine("    const val CANONICAL_PROFILE_UID: String = ${canonicalProfileUid.q()}")
            appendLine("    object Parameters {")
            declarations.sortedBy { it.uid }.forEach { declaration ->
                val value = canonical[declaration.uid] ?: declaration.defaultValue
                appendLine("        const val ${declaration.key.constantName()}: ${declaration.type.kotlinType()} = ${value.literal(declaration.type)}")
            }
            appendLine("    }")
            appendLine()
            appendLine("    fun metadata(): TuningMetadataSnapshot = TuningMetadataSnapshot(")
            appendLine("        projectId = PROJECT_ID, drivebaseUid = DRIVEBASE_UID,")
            appendLine("        canonicalProfileUid = CANONICAL_PROFILE_UID,")
            appendLine("        declarations = declarations(),")
            appendLine("        profileUids = listOf(${profiles.sortedBy { it.uid }.joinToString { it.uid.q() }}),")
            appendLine("    )")
            appendLine()
            appendLine("    fun createRuntime(): TypedTuningRuntime = TypedTuningRuntime(")
            appendLine("        declarations = declarations(),")
            appendLine("        canonicalValues = mapOf(")
            declarations.sortedBy { it.uid }.forEach { declaration ->
                val value = canonical[declaration.uid] ?: declaration.defaultValue
                appendLine("            ${declaration.uid.q()} to ${value.constructor()},")
            }
            appendLine("        ),")
            appendLine("        metadata = metadata(),")
            appendLine("    )")
            appendLine()
            appendLine("    private fun declarations(): List<TuningParameterDeclaration> = listOf(")
            declarations.sortedBy { it.uid }.forEach { declaration ->
                appendLine("        TuningParameterDeclaration(")
                appendLine("            uid = ${declaration.uid.q()}, key = ${declaration.key.q()}, componentUid = ${declaration.componentUid.q()},")
                appendLine("            displayName = ${declaration.displayName.q()}, description = ${declaration.description.q()},")
                appendLine("            type = TuningParameterType.${declaration.type}, unit = ${declaration.unit?.q() ?: "null"},")
                appendLine("            minimum = ${declaration.minimum?.literal() ?: "null"}, maximum = ${declaration.maximum?.literal() ?: "null"},")
                appendLine("            defaultValue = ${declaration.defaultValue.constructor()},")
                appendLine("            enumOptions = listOf(${declaration.enumOptions.joinToString { it.q() }}),")
                appendLine("            applyPolicy = TuningApplyPolicy.${declaration.applyPolicy},")
                appendLine("        ),")
            }
            appendLine("    )")
            appendLine("}")
        }
        return GeneratedDrivebaseFile("GeneratedAresTuningConfig.kt", source)
    }
}

private fun List<TuningProfileDocument>.singleProjectId(): String {
    val projectIds = map { it.projectId }.distinct()
    require(projectIds.size == 1) { "At least one canonical profile for exactly one robot project is required" }
    return projectIds.single()
}

private val FTC_MECANUM_COMMON_PARAMETER_TYPES: Map<String, TuningParameterType> = linkedMapOf(
    "drive.closedLoopVelocity" to TuningParameterType.BOOLEAN,
    "drive.feedforwardKs" to TuningParameterType.DOUBLE,
    "drive.feedforwardKv" to TuningParameterType.DOUBLE,
    "drive.feedforwardKa" to TuningParameterType.DOUBLE,
    "drive.motorKp" to TuningParameterType.DOUBLE,
    "drive.motorKi" to TuningParameterType.DOUBLE,
    "drive.motorKd" to TuningParameterType.DOUBLE,
    "drive.motorKf" to TuningParameterType.DOUBLE,
    "drive.headingKp" to TuningParameterType.DOUBLE,
    "drive.headingKi" to TuningParameterType.DOUBLE,
    "drive.headingKd" to TuningParameterType.DOUBLE,
    "drive.headingDeadzoneDeg" to TuningParameterType.DOUBLE,
    "drive.pathTranslationKp" to TuningParameterType.DOUBLE,
    "drive.pathTranslationKd" to TuningParameterType.DOUBLE,
    "drive.pathRotationKp" to TuningParameterType.DOUBLE,
    "drive.pathRotationKd" to TuningParameterType.DOUBLE,
    "drive.pathVelocityScale" to TuningParameterType.DOUBLE,
    "drive.pathAccelerationLimit" to TuningParameterType.DOUBLE,
    "drive.ticksPerMeter" to TuningParameterType.DOUBLE,
    "localization.ekfQx" to TuningParameterType.DOUBLE,
    "localization.ekfQy" to TuningParameterType.DOUBLE,
    "localization.ekfQtheta" to TuningParameterType.DOUBLE,
)

private val FTC_MECANUM_PINPOINT_PARAMETER_TYPES: Map<String, TuningParameterType> = linkedMapOf(
    "localization.pinpointCcwPositive" to TuningParameterType.BOOLEAN,
    "localization.pinpointXOffsetMm" to TuningParameterType.DOUBLE,
    "localization.pinpointYOffsetMm" to TuningParameterType.DOUBLE,
    "localization.pinpointEncoderResolution" to TuningParameterType.DOUBLE,
    "localization.pinpointXReversed" to TuningParameterType.BOOLEAN,
    "localization.pinpointYReversed" to TuningParameterType.BOOLEAN,
)

/** Optional as a group so already-authored projects retain their library defaults until reviewed. */
private val FTC_MECANUM_ASSIST_PARAMETER_TYPES: Map<String, TuningParameterType> = linkedMapOf(
    "drive.headingMaxOutputLimit" to TuningParameterType.DOUBLE,
    "drive.positionHoldKp" to TuningParameterType.DOUBLE,
    "drive.positionHoldKi" to TuningParameterType.DOUBLE,
    "drive.positionHoldKd" to TuningParameterType.DOUBLE,
    "drive.positionHoldDeadzoneMeters" to TuningParameterType.DOUBLE,
    "drive.positionHoldMaxOutputLimit" to TuningParameterType.DOUBLE,
)

private val FTC_MECANUM_COMMON_REDUX_KEYS: Set<String> = setOf(
    "drive.feedforwardKs",
    "drive.feedforwardKv",
    "drive.feedforwardKa",
    "drive.motorKp",
    "drive.motorKi",
    "drive.motorKd",
    "drive.motorKf",
    "drive.headingKp",
    "drive.headingKi",
    "drive.headingKd",
    "drive.headingDeadzoneDeg",
    "drive.pathTranslationKp",
    "drive.pathTranslationKd",
    "drive.pathRotationKp",
    "drive.pathRotationKd",
    "drive.pathVelocityScale",
    "drive.pathAccelerationLimit",
    "drive.ticksPerMeter",
    "localization.ekfQx",
    "localization.ekfQy",
    "localization.ekfQtheta",
)

private val FTC_MECANUM_PINPOINT_REDUX_KEYS: Set<String> = setOf(
    "localization.pinpointXOffsetMm",
    "localization.pinpointYOffsetMm",
    "localization.pinpointEncoderResolution",
)

private val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
private fun String.q() = buildString {
    append('"')
    this@q.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            else -> append(character)
        }
    }
    append('"')
}
private fun String.constantName() = replace(Regex("[^A-Za-z0-9]+"), "_").uppercase()
private fun String.escapeKdoc() = replace("*/", "* /").replace('\n', ' ')
private fun StringBuilder.appendLocalizationSource(
    objectName: String,
    source: com.areslib.drivetrain.DrivetrainLocalizationSourceDocument,
    indent: Int,
) {
    val pad = " ".repeat(indent)
    appendLine("${pad}/** ${source.source.name.lowercase().replace('_', ' ')} localization source. */")
    appendLine("${pad}object $objectName {")
    appendLine("$pad    const val UID: String = ${source.uid.q()}")
    appendLine("$pad    const val KIND: String = ${source.source.name.q()}")
    appendLine("$pad    val COMPONENT_UIDS: List<String> = listOf(${source.componentUids.sorted().joinToString { it.q() }})")
    appendLine("$pad    val IMPLEMENTATION_CLASS: String? = ${source.implementationClassName?.q() ?: "null"}")
    appendLine("${pad}}")
}
private fun Double.literal() = if (toString().contains('.') || toString().contains('E')) toString() else "${this}.0"
private fun TuningParameterType.kotlinType() = when (this) {
    TuningParameterType.DOUBLE -> "Double"
    TuningParameterType.INT -> "Int"
    TuningParameterType.BOOLEAN -> "Boolean"
    TuningParameterType.TEXT, TuningParameterType.ENUM -> "String"
}
private fun TuningValue.literal(type: TuningParameterType) = when (type) {
    TuningParameterType.DOUBLE -> requireNotNull(doubleValue).literal()
    TuningParameterType.INT -> requireNotNull(intValue).toString()
    TuningParameterType.BOOLEAN -> requireNotNull(booleanValue).toString()
    TuningParameterType.TEXT, TuningParameterType.ENUM -> requireNotNull(textValue).q()
}
private fun TuningValue.constructor() = when {
    doubleValue != null -> "TuningValue(doubleValue = ${requireNotNull(doubleValue).literal()})"
    intValue != null -> "TuningValue(intValue = ${requireNotNull(intValue)})"
    booleanValue != null -> "TuningValue(booleanValue = ${requireNotNull(booleanValue)})"
    textValue != null -> "TuningValue(textValue = ${requireNotNull(textValue).q()})"
    else -> error("Invalid empty tuning value")
}
