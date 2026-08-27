package com.ares.analytics.service.drivebase

import com.areslib.drivetrain.DrivetrainControlKind
import com.areslib.drivetrain.DrivetrainComponentDocument
import com.areslib.drivetrain.DrivetrainComponentRole
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainKind
import com.areslib.drivetrain.LocalizationSourceKind
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue

internal const val CLOSED_LOOP_VELOCITY_KEY: String = "drive.closedLoopVelocity"

/**
 * Owns the complete zero-code FTC mecanum parameter surface used by ARESLib code generation.
 *
 * The desktop builder uses this catalog when it creates a drivebase or when a student changes
 * localization. Keeping the declarations here prevents the form from saving a document that is
 * schema-valid but cannot generate the FTC runtime.
 */
internal object FtcMecanumRuntimeParameters {
    fun requiredFor(document: DrivetrainDocument): List<TuningParameterDeclaration> {
        require(document.kind == DrivetrainKind.FTC_MECANUM) {
            "FTC mecanum runtime parameters require an FTC mecanum drivebase"
        }
        val declaredComponentUids = document.components.mapTo(HashSet()) { it.uid }
        val localizationComponentUid = document.localization.primaryOdometry.componentUids.singleOrNull()
            ?.takeIf { it in declaredComponentUids }
            ?: document.localization.headingSourceUid.takeIf { it in declaredComponentUids }
            ?: document.uid
        val closedLoop = document.control.defaultControl != DrivetrainControlKind.OPEN_LOOP
        val commonParameters = common(closedLoop, localizationComponentUid).map { declaration ->
            if (declaration.componentUid == "ftc.drivebase") declaration.copy(componentUid = document.uid)
            else declaration
        }
        return commonParameters + if (
            document.localization.primaryOdometry.source == LocalizationSourceKind.PINPOINT
        ) pinpoint(localizationComponentUid) else emptyList()
    }

    fun reconcile(document: DrivetrainDocument): DrivetrainDocument {
        if (document.kind != DrivetrainKind.FTC_MECANUM) return document
        val topologyReady = reconcileVisionTopology(reconcilePinpointTopology(document))
        val required = requiredFor(topologyReady)
        val requiredKeys = required.mapTo(HashSet()) { it.key }
        val existingByKey = topologyReady.parameters.associateBy { it.key }
        val mergedRequired = required.map { expected ->
            val existing = existingByKey[expected.key]
            when {
                existing == null -> expected
                existing.type != expected.type -> existing // Preserve evidence; validation fails visibly.
                expected.key == CLOSED_LOOP_VELOCITY_KEY -> existing.copy(defaultValue = expected.defaultValue)
                else -> existing
            }
        }
        val extras = topologyReady.parameters.filterNot { it.key in requiredKeys }
        return topologyReady.copy(parameters = (mergedRequired + extras).sortedBy { it.uid })
    }

    /**
     * Reports whether a previously saved document needs a reviewed runtime-contract repair.
     * Generation remains fail-closed; the desktop app surfaces this before starting Gradle and
     * the normal structured-diff save path performs the repair together with its tuning profile.
     */
    fun repairMessage(document: DrivetrainDocument): String? {
        if (document.kind != DrivetrainKind.FTC_MECANUM) return null
        val repaired = reconcile(document)
        val currentByKey = document.parameters.associateBy { it.key }
        val repairedByKey = repaired.parameters.associateBy { it.key }
        val pinpointTopologyIncomplete = document.localization.primaryOdometry.source == LocalizationSourceKind.PINPOINT &&
            !hasCompletePinpointTopology(document)
        val legacyVisionPlaceholder = document.localization.visionFusion.any(::isUnboundLegacyVisionPlaceholder)
        if (currentByKey == repairedByKey && !pinpointTopologyIncomplete && !legacyVisionPlaceholder) return null
        val missingKeys = repairedByKey.keys.minus(currentByKey.keys).sorted()
        val details = buildList {
            if (missingKeys.isNotEmpty()) add("missing runtime parameters: ${missingKeys.joinToString()}")
            if (pinpointTopologyIncomplete) add("Pinpoint is not bound to exactly one odometry device")
            if (legacyVisionPlaceholder) add("an unbound legacy vision placeholder cannot run on FTC")
            if (isEmpty()) add("saved runtime defaults are out of date")
        }
        return "FTC drivebase runtime contract needs a reviewed repair (${details.joinToString("; ")}). Open Drivetrain, review Safety & Review, and save before Verify & build."
    }

    /**
     * Restores the mechanical Pinpoint binding used by the generated FTC constructor. This only
     * changes the in-memory review draft; persistence still requires the normal structured-diff
     * confirmation. A single existing odometry device is reused. With none, the documented FTC
     * hardware-map default (`pinpoint`) is added. Multiple candidates remain fail-closed because
     * choosing between real devices would be an unsafe guess.
     */
    private fun reconcilePinpointTopology(document: DrivetrainDocument): DrivetrainDocument {
        val primary = document.localization.primaryOdometry
        if (primary.source != LocalizationSourceKind.PINPOINT || hasCompletePinpointTopology(document)) {
            return document
        }
        val odometryComponents = document.components.filter { it.role == DrivetrainComponentRole.ODOMETRY_SENSOR }
        val selected = when (odometryComponents.size) {
            0 -> {
                val occupied = document.components.mapTo(HashSet()) { it.uid }
                val uid = generateSequence("drive.pinpoint" to 1) { (_, suffix) -> "drive.pinpoint-${suffix + 1}" to suffix + 1 }
                    .map { it.first }
                    .first { it !in occupied }
                DrivetrainComponentDocument(
                    uid = uid,
                    displayName = "goBILDA Pinpoint",
                    role = DrivetrainComponentRole.ODOMETRY_SENSOR,
                    hardwareId = "pinpoint",
                    required = true,
                )
            }
            1 -> odometryComponents.single()
            else -> return document
        }
        val components = if (selected in document.components) document.components else document.components + selected
        return document.copy(
            components = components,
            localization = document.localization.copy(
                primaryOdometry = primary.copy(componentUids = listOf(selected.uid)),
            ),
        )
    }

    private fun hasCompletePinpointTopology(document: DrivetrainDocument): Boolean {
        val componentUid = document.localization.primaryOdometry.componentUids.singleOrNull() ?: return false
        return document.components.singleOrNull { it.uid == componentUid }?.role ==
            DrivetrainComponentRole.ODOMETRY_SENSOR
    }

    /** Removes only the pre-runtime generic placeholder that had no physical camera binding. */
    private fun reconcileVisionTopology(document: DrivetrainDocument): DrivetrainDocument {
        val repaired = document.localization.visionFusion.filterNot(::isUnboundLegacyVisionPlaceholder)
        return if (repaired.size == document.localization.visionFusion.size) document else document.copy(
            localization = document.localization.copy(visionFusion = repaired),
        )
    }

    private fun isUnboundLegacyVisionPlaceholder(
        source: com.areslib.drivetrain.DrivetrainLocalizationSourceDocument,
    ): Boolean = source.source == LocalizationSourceKind.EXTERNAL &&
        source.componentUids.isEmpty() &&
        source.implementationClassName == "com.areslib.vision.VisionTracker"

    private fun common(
        closedLoop: Boolean,
        localizationComponentUid: String,
    ): List<TuningParameterDeclaration> = listOf(
        double("ftc.drive.ticks-per-meter", "drive.ticksPerMeter", "Encoder ticks per meter", "Calibrated wheel-encoder conversion used by hardware, fallback odometry, and simulation.", 2000.0, "ticks/m", 1.0, 100000.0, TuningApplyPolicy.CALIBRATION_ONLY),
        boolean("ftc.drive.closed-loop-velocity", CLOSED_LOOP_VELOCITY_KEY, "Closed-loop wheel velocity", "Uses FTC SDK encoder velocity control. When all four custom PIDF gains are zero, ARES retains the motor-controller defaults.", closedLoop, TuningApplyPolicy.RESTART_REQUIRED),
        double("ftc.drive.feedforward.ks", "drive.feedforwardKs", "Drive static feedforward", "Simulation baseline; measure the voltage needed to overcome drivetrain static friction before physical use.", 0.0, "V", 0.0, 12.0, TuningApplyPolicy.DISABLED_ONLY),
        double("ftc.drive.feedforward.kv", "drive.feedforwardKv", "Drive velocity feedforward", "Simulation baseline in volts per meter per second; calibrate before physical deployment.", 12.0, "V/(m/s)", 0.000001, 20.0, TuningApplyPolicy.DISABLED_ONLY),
        double("ftc.drive.feedforward.ka", "drive.feedforwardKa", "Drive acceleration feedforward", "Simulation baseline in volts per meter per second squared; calibrate before physical deployment.", 0.0, "V/(m/s^2)", 0.0, 20.0, TuningApplyPolicy.DISABLED_ONLY),
        double("ftc.drive.motor.kp", "drive.motorKp", "Wheel velocity proportional gain", "Optional FTC SDK velocity PIDF proportional gain. Leave every custom PIDF gain at zero to retain the controller defaults.", 0.0, null, 0.0, 100.0, TuningApplyPolicy.DISABLED_ONLY),
        double("ftc.drive.motor.ki", "drive.motorKi", "Wheel velocity integral gain", "Optional FTC SDK velocity PIDF integral gain.", 0.0, null, 0.0, 100.0, TuningApplyPolicy.DISABLED_ONLY),
        double("ftc.drive.motor.kd", "drive.motorKd", "Wheel velocity derivative gain", "Optional FTC SDK velocity PIDF derivative gain.", 0.0, null, 0.0, 100.0, TuningApplyPolicy.DISABLED_ONLY),
        double("ftc.drive.motor.kf", "drive.motorKf", "Wheel velocity feedforward gain", "Optional FTC SDK velocity PIDF feedforward gain.", 0.0, null, 0.0, 100.0, TuningApplyPolicy.DISABLED_ONLY),
        double("ftc.drive.heading.kp", "drive.headingKp", "Heading proportional gain", "Field-centric heading-hold proportional gain.", 1.8, null, 0.0, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.heading.ki", "drive.headingKi", "Heading integral gain", "Field-centric heading-hold integral gain.", 0.0, null, 0.0, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.heading.kd", "drive.headingKd", "Heading derivative gain", "Field-centric heading-hold derivative gain.", 0.08, null, 0.0, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.heading.deadzone", "drive.headingDeadzoneDeg", "Heading deadzone", "Angular error ignored by heading hold.", 2.5, "deg", 0.0, 30.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.heading.max-output", "drive.headingMaxOutputLimit", "Rotation-lock maximum output", "Maximum fraction of rotational output that automatic heading hold may command.", 0.4, null, 0.05, 1.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.position-hold.kp", "drive.positionHoldKp", "Anti-push proportional gain", "Translation correction gain used while anti-push position hold is engaged.", 1.5, null, 0.0, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.position-hold.ki", "drive.positionHoldKi", "Anti-push integral gain", "Translation integral gain used while anti-push position hold is engaged.", 0.0, null, 0.0, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.position-hold.kd", "drive.positionHoldKd", "Anti-push derivative gain", "Translation derivative gain used while anti-push position hold is engaged.", 0.1, null, 0.0, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.position-hold.deadzone", "drive.positionHoldDeadzoneMeters", "Anti-push deadzone", "Position error below this distance is treated as settled.", 0.02, "m", 0.0, 0.5, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.position-hold.max-output", "drive.positionHoldMaxOutputLimit", "Anti-push maximum output", "Maximum fraction of translation output that position hold may command.", 0.5, null, 0.05, 1.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.path-translation.kp", "drive.pathTranslationKp", "Path translation proportional gain", "Simulation-first translation feedback gain for autonomous path following.", 2.0, null, 0.0, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.path-translation.kd", "drive.pathTranslationKd", "Path translation derivative gain", "Simulation-first translation derivative gain for autonomous path following.", 0.2, null, 0.0, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.path-rotation.kp", "drive.pathRotationKp", "Path rotation proportional gain", "Simulation-first heading feedback gain for autonomous path following.", 2.5, null, 0.0, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.path-rotation.kd", "drive.pathRotationKd", "Path rotation derivative gain", "Simulation-first heading derivative gain for autonomous path following.", 0.2, null, 0.0, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.path.velocity-scale", "drive.pathVelocityScale", "Autonomous velocity scale", "Conservative fraction of modeled speed available to simulation trajectories.", 0.25, null, 0.05, 1.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.drive.path.acceleration-limit", "drive.pathAccelerationLimit", "Autonomous acceleration limit", "Conservative simulation trajectory acceleration bound.", 0.5, "m/s^2", 0.05, 20.0, TuningApplyPolicy.LIVE_SAFE),
        double("ftc.localization.ekf.qx", "localization.ekfQx", "EKF X process noise", "Pose-estimator X-axis process variance.", 0.02, "m^2", 0.0, 10.0, TuningApplyPolicy.LIVE_SAFE, localizationComponentUid),
        double("ftc.localization.ekf.qy", "localization.ekfQy", "EKF Y process noise", "Pose-estimator Y-axis process variance.", 0.02, "m^2", 0.0, 10.0, TuningApplyPolicy.LIVE_SAFE, localizationComponentUid),
        double("ftc.localization.ekf.qtheta", "localization.ekfQtheta", "EKF heading process noise", "Pose-estimator heading process variance.", 0.02, "rad^2", 0.0, 10.0, TuningApplyPolicy.LIVE_SAFE, localizationComponentUid),
    )

    private fun pinpoint(componentUid: String): List<TuningParameterDeclaration> = listOf(
        boolean("ftc.localization.pinpoint.ccw", "localization.pinpointCcwPositive", "Pinpoint heading is CCW positive", "Declares mounting polarity at the hardware boundary; no later sign correction is permitted.", true, TuningApplyPolicy.RESTART_REQUIRED, componentUid),
        double("ftc.localization.pinpoint.x-offset", "localization.pinpointXOffsetMm", "Pinpoint X offset", "Measured Pinpoint mounting offset on the robot X axis.", 0.0, "mm", -1000.0, 1000.0, TuningApplyPolicy.CALIBRATION_ONLY, componentUid),
        double("ftc.localization.pinpoint.y-offset", "localization.pinpointYOffsetMm", "Pinpoint Y offset", "Measured Pinpoint mounting offset on the robot Y axis.", 0.0, "mm", -1000.0, 1000.0, TuningApplyPolicy.CALIBRATION_ONLY, componentUid),
        double("ftc.localization.pinpoint.encoder-resolution", "localization.pinpointEncoderResolution", "Pinpoint encoder resolution override", "Ticks per millimeter; zero retains the SDK named pod preset.", 0.0, "ticks/mm", 0.0, 10000.0, TuningApplyPolicy.CALIBRATION_ONLY, componentUid),
        boolean("ftc.localization.pinpoint.x-reversed", "localization.pinpointXReversed", "Reverse Pinpoint X pod", "Corrects the physical X odometry pod direction at the hardware boundary.", false, TuningApplyPolicy.RESTART_REQUIRED, componentUid),
        boolean("ftc.localization.pinpoint.y-reversed", "localization.pinpointYReversed", "Reverse Pinpoint Y pod", "Corrects the physical Y odometry pod direction at the hardware boundary.", false, TuningApplyPolicy.RESTART_REQUIRED, componentUid),
    )

    private fun double(
        uid: String,
        key: String,
        name: String,
        description: String,
        value: Double,
        unit: String?,
        minimum: Double,
        maximum: Double,
        policy: TuningApplyPolicy,
        componentUid: String = "ftc.drivebase",
    ) = TuningParameterDeclaration(uid, key, componentUid, name, description, TuningParameterType.DOUBLE, unit, minimum, maximum, TuningValue(doubleValue = value), applyPolicy = policy)

    private fun boolean(
        uid: String,
        key: String,
        name: String,
        description: String,
        value: Boolean,
        policy: TuningApplyPolicy,
        componentUid: String = "ftc.drivebase",
    ) = TuningParameterDeclaration(uid, key, componentUid, name, description, TuningParameterType.BOOLEAN, defaultValue = TuningValue(booleanValue = value), applyPolicy = policy)
}

/** Returns a reviewed draft with every generator-required declaration represented explicitly. */
internal fun DrivebaseDocument.withRuntimeRequirements(): DrivebaseDocument {
    if (kind != DrivebaseKind.FTC_MECANUM) return this
    return FtcMecanumRuntimeParameters.reconcile(toCanonicalDrivebase()).toUiDrivebase()
}
