package com.ares.analytics.viewmodel.drivebase

import com.ares.analytics.service.drivebase.defaultDrivebase
import com.ares.analytics.service.drivebase.defaultNoCodeDrivebaseKind
import com.ares.analytics.service.drivebase.diffDrivebase
import com.ares.analytics.service.drivebase.DrivebaseChange
import com.ares.analytics.service.drivebase.DrivebaseDocument
import com.ares.analytics.service.drivebase.DrivebaseIssue
import com.ares.analytics.service.drivebase.DrivebaseIssueSeverity
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.DrivebaseProjectRepository
import com.ares.analytics.service.drivebase.DriveGeometry
import com.ares.analytics.service.drivebase.DriveHardwareDeclaration
import com.ares.analytics.service.drivebase.DriveHardwareRole
import com.ares.analytics.service.drivebase.DriveSafetyDeclaration
import com.ares.analytics.service.drivebase.LocalizationKind
import com.ares.analytics.service.drivebase.validateDrivebaseForLeague
import com.ares.analytics.service.DrivebaseDesignProposal
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.shared.models.League
import com.ares.analytics.util.Sha256
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresProjectMetadataCodec
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Curated 1-click real-world COTS chassis presets so students can populate realistic wheel radius,
 * trackwidth, wheelbase, and max speed limits without manual unit conversion.
 */
enum class DrivebaseChassisPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val league: League,
    val kind: DrivebaseKind,
    val wheelRadiusMeters: Double,
    val trackWidthMeters: Double,
    val wheelBaseMeters: Double,
    val maxLinearSpeedMetersPerSecond: Double,
    val maxAngularSpeedRadiansPerSecond: Double,
    val measurementSummary: String,
) {
    GOBILDA_STRAFER_MECANUM(
        id = "gobilda_strafer_mecanum",
        title = "goBILDA Strafer Mecanum",
        subtitle = "104 mm mecanum wheels • 312 RPM Yellow Jackets • 36×34 cm",
        league = League.FTC,
        kind = DrivebaseKind.FTC_MECANUM,
        wheelRadiusMeters = 0.052,
        trackWidthMeters = 0.36,
        wheelBaseMeters = 0.34,
        maxLinearSpeedMetersPerSecond = 1.72,
        maxAngularSpeedRadiansPerSecond = 6.8,
        measurementSummary = "Wheel radius 52 mm (104 mm Ø), trackwidth 360 mm, wheelbase 340 mm.",
    ),
    REV_STARTER_BOT_DIFFERENTIAL(
        id = "rev_starter_bot_differential",
        title = "REV Starter Bot Differential",
        subtitle = "90 mm traction/omni wheels • HD Hex • 35×30 cm",
        league = League.FTC,
        kind = DrivebaseKind.DIFFERENTIAL,
        wheelRadiusMeters = 0.045,
        trackWidthMeters = 0.35,
        wheelBaseMeters = 0.30,
        maxLinearSpeedMetersPerSecond = 1.41,
        maxAngularSpeedRadiansPerSecond = 6.0,
        measurementSummary = "Wheel radius 45 mm (90 mm Ø), trackwidth 350 mm, wheelbase 300 mm.",
    ),
    SDS_MK4I_L2_SWERVE(
        id = "sds_mk4i_l2_swerve",
        title = "SDS MK4i L2 Swerve",
        subtitle = "4 in billet wheels • 6.75:1 L2 ratio • 58×58 cm square frame",
        league = League.FRC,
        kind = DrivebaseKind.FRC_CTRE_SWERVE,
        wheelRadiusMeters = 0.0508,
        trackWidthMeters = 0.58,
        wheelBaseMeters = 0.58,
        maxLinearSpeedMetersPerSecond = 4.65,
        maxAngularSpeedRadiansPerSecond = 10.5,
        measurementSummary = "Wheel radius 50.8 mm (4 in Ø), trackwidth 580 mm, wheelbase 580 mm.",
    ),
    WEST_COAST_DRIVE_DIFFERENTIAL(
        id = "west_coast_drive_differential",
        title = "West Coast Drive (WCD)",
        subtitle = "6 in pneumatic/HiGrip wheels • 54×62 cm tank frame",
        league = League.FRC,
        kind = DrivebaseKind.DIFFERENTIAL,
        wheelRadiusMeters = 0.0762,
        trackWidthMeters = 0.62,
        wheelBaseMeters = 0.54,
        maxLinearSpeedMetersPerSecond = 4.20,
        maxAngularSpeedRadiansPerSecond = 8.5,
        measurementSummary = "Wheel radius 76.2 mm (6 in Ø), trackwidth 620 mm, wheelbase 540 mm.",
    ),
    SPARKFUN_STANDARD_XRP(
        id = "sparkfun_standard_xrp",
        title = "SparkFun Standard XRP",
        subtitle = "60 mm snap-fit wheels • 155 mm trackwidth • 120 mm wheelbase",
        league = League.XRP,
        kind = DrivebaseKind.DIFFERENTIAL,
        wheelRadiusMeters = 0.030,
        trackWidthMeters = 0.155,
        wheelBaseMeters = 0.120,
        maxLinearSpeedMetersPerSecond = 0.55,
        maxAngularSpeedRadiansPerSecond = 5.5,
        measurementSummary = "Wheel radius 30 mm (60 mm Ø), trackwidth 155 mm, wheelbase 120 mm.",
    ),
}

fun availableChassisPresets(league: League): List<DrivebaseChassisPreset> =
    DrivebaseChassisPreset.entries.filter { it.league == league }

fun applyChassisPreset(
    state: DrivebaseBuilderState,
    preset: DrivebaseChassisPreset,
): DrivebaseDocument {
    val base = if (state.draft.kind == preset.kind) {
        state.draft
    } else {
        drivebaseForKind(state, preset.kind)
    }
    return base.copy(
        displayName = preset.title,
        geometry = DriveGeometry(
            wheelRadiusMeters = preset.wheelRadiusMeters,
            trackWidthMeters = preset.trackWidthMeters,
            wheelBaseMeters = preset.wheelBaseMeters,
        ),
        safety = base.safety.copy(
            maxLinearSpeedMetersPerSecond = preset.maxLinearSpeedMetersPerSecond,
            maxAngularSpeedRadiansPerSecond = preset.maxAngularSpeedRadiansPerSecond,
        ),
    )
}

internal fun League.targetPlatform() = when (this) {
    League.FTC -> com.areslib.controls.ControllerInputPlatform.FTC
    League.FRC -> com.areslib.controls.ControllerInputPlatform.FRC
    League.XRP -> com.areslib.controls.ControllerInputPlatform.XRP
}

internal fun canonicalRuntimeProjectId(projectPath: String, fallback: String, league: League): String {
    val identity = File(projectPath, ".ares/project.json")
    return runCatching { AresProjectMetadataCodec.decode(identity.readText()).projectId }
        .getOrElse { fallback }
}

internal fun drivebaseForKind(state: DrivebaseBuilderState, kind: DrivebaseKind): DrivebaseDocument {
    val replacement = defaultDrivebase(state.projectId, kind, state.league)
    val replacementCanonical = replacement.canonical ?: return replacement
    val currentCanonical = state.draft.canonical ?: return replacement
    val reboundUid = currentCanonical.uid
    return replacement.copy(
        canonical = replacementCanonical.copy(
            uid = reboundUid,
            canonicalProfileUid = currentCanonical.canonicalProfileUid,
            parameters = replacementCanonical.parameters.map { parameter ->
                if (parameter.componentUid == replacementCanonical.uid) parameter.copy(componentUid = reboundUid) else parameter
            },
        ),
    )
}

internal fun DrivebaseDocument.requireCanonicalProjectIdentity(projectId: String): DrivebaseDocument {
    val canonicalDocument = requireNotNull(canonical) { "The drivebase must use the canonical document contract." }
    val profileId = canonicalDocument.canonicalProfileUid.substringAfter(".profile.", "competition")
    val expectedProfileUid = "$projectId.profile.$profileId"
    require(this.projectId == projectId) {
        "The drivebase belongs to project '$projectId', but its projectId is '${this.projectId}'."
    }
    require(canonicalDocument.canonicalProfileUid == expectedProfileUid) {
        "The drivebase canonical profile must be '$expectedProfileUid'."
    }
    return this
}

fun evaluateDriveLab(
    kind: DrivebaseKind,
    input: DriveLabState,
    geometry: DriveGeometry = DriveGeometry(),
    hardware: List<DriveHardwareDeclaration> = emptyList(),
): DriveLabResult {
    val heading = input.headingDegrees * PI / 180.0
    val forward = if (input.fieldRelative) input.forward * cos(heading) + input.strafe * sin(heading) else input.forward
    val strafe = if (input.fieldRelative) -input.forward * sin(heading) + input.strafe * cos(heading) else input.strafe
    var moduleAngles = emptyMap<String, Double>()
    val raw = when (kind) {
        DrivebaseKind.FTC_MECANUM, DrivebaseKind.CUSTOM -> linkedMapOf(
            "frontLeft" to forward + strafe + input.rotate,
            "frontRight" to forward - strafe - input.rotate,
            "rearLeft" to forward - strafe + input.rotate,
            "rearRight" to forward + strafe - input.rotate,
        )
        DrivebaseKind.FRC_CTRE_SWERVE -> {
            val positions = linkedMapOf(
                "frontLeft" to (geometry.wheelBaseMeters / 2.0 to geometry.trackWidthMeters / 2.0),
                "frontRight" to (geometry.wheelBaseMeters / 2.0 to -geometry.trackWidthMeters / 2.0),
                "rearLeft" to (-geometry.wheelBaseMeters / 2.0 to geometry.trackWidthMeters / 2.0),
                "rearRight" to (-geometry.wheelBaseMeters / 2.0 to -geometry.trackWidthMeters / 2.0),
            )
            val vectors = positions.mapValues { (_, position) ->
                val (x, y) = position
                val moduleForward = forward - input.rotate * y
                val moduleStrafe = strafe + input.rotate * x
                moduleForward to moduleStrafe
            }
            moduleAngles = vectors.mapValues { (_, vector) -> Math.toDegrees(atan2(vector.second, vector.first)) }
            vectors.mapValues { (_, vector) -> hypot(vector.first, vector.second) }.toMap(LinkedHashMap())
        }
        DrivebaseKind.DIFFERENTIAL -> linkedMapOf("left" to forward + input.rotate, "right" to forward - input.rotate)
    }
    val inversionRoles = mapOf(
        "frontLeft" to setOf(DriveHardwareRole.FRONT_LEFT, DriveHardwareRole.FRONT_LEFT_DRIVE),
        "frontRight" to setOf(DriveHardwareRole.FRONT_RIGHT, DriveHardwareRole.FRONT_RIGHT_DRIVE),
        "rearLeft" to setOf(DriveHardwareRole.REAR_LEFT, DriveHardwareRole.REAR_LEFT_DRIVE),
        "rearRight" to setOf(DriveHardwareRole.REAR_RIGHT, DriveHardwareRole.REAR_RIGHT_DRIVE),
        "left" to setOf(DriveHardwareRole.LEFT_LEADER),
        "right" to setOf(DriveHardwareRole.RIGHT_LEADER),
    )
    val afterInversion = raw.mapValues { (name, output) ->
        val inverted = hardware.any { it.inverted && it.role in inversionRoles[name].orEmpty() }
        if (inverted) -output else output
    }.toMutableMap()
    hardware.filter { it.role in setOf(DriveHardwareRole.LEFT_FOLLOWER, DriveHardwareRole.RIGHT_FOLLOWER) }.forEach { follower ->
        val side = if (follower.role == DriveHardwareRole.LEFT_FOLLOWER) "left" else "right"
        val leaderOutput = afterInversion[side] ?: 0.0
        afterInversion["follower:${follower.id}"] = if (follower.inverted) -leaderOutput else leaderOutput
    }
    val scale = afterInversion.values.maxOfOrNull { abs(it) }?.coerceAtLeast(1.0) ?: 1.0
    return DriveLabResult(
        robotForward = forward,
        robotStrafe = strafe,
        wheelOutputs = afterInversion.mapValues { it.value / scale },
        moduleAnglesDegrees = moduleAngles,
        explanation = "Simulation only: text includes each declared drive inversion after field-to-robot transformation. This does not connect to or move hardware.",
    )
}

fun evaluateGeometryLab(
    geometry: DriveGeometry,
    linearCommand: Double,
    angularCommand: Double,
    configuredMaxLinearSpeedMps: Double,
    useCornerModuleRadius: Boolean,
): GeometryLabResult {
    val radius = if (abs(angularCommand) < 1e-9) null else abs(linearCommand / angularCommand)
    val maxLinearSpeed = configuredMaxLinearSpeedMps.takeIf { it.isFinite() && it > 0.0 }
    val rotationalRadius = if (useCornerModuleRadius) {
        hypot(geometry.trackWidthMeters / 2.0, geometry.wheelBaseMeters / 2.0)
    } else {
        geometry.trackWidthMeters / 2.0
    }
    val maxAngularSpeed = maxLinearSpeed?.takeIf { rotationalRadius > 0.005 }?.div(rotationalRadius)
    return GeometryLabResult(
        turningRadiusMeters = radius,
        trackCircleDiameterMeters = radius?.let { 2.0 * (it + geometry.trackWidthMeters / 2.0) },
        maxLinearSpeedMps = maxLinearSpeed,
        maxAngularSpeedRadPerSec = maxAngularSpeed,
        explanation = if (radius == null) {
            "Zero turn command predicts a straight path. The configured safety limit is ${maxLinearSpeed?.let { "%.2f m/s".format(it) } ?: "not valid"}."
        } else {
            "The chassis center follows a ${"%.2f".format(radius)} m radius. The configured linear limit implies ${maxAngularSpeed?.let { "%.1f rad/s".format(it) } ?: "no valid angular estimate"}; the outside wheel/module travels a larger circle."
        },
    )
}

fun evaluateLocalizationFailure(scenario: LocalizationFailureScenario): LocalizationLabResult = when (scenario) {
    LocalizationFailureScenario.ALL_HEALTHY -> LocalizationLabResult(true, true, "Primary odometry and heading are fresh. Valid vision may correct drift.")
    LocalizationFailureScenario.PRIMARY_STALE -> LocalizationLabResult(false, false, "Primary odometry is stale, so closed-loop driving fails closed. Vision is not a substitute for fresh primary motion feedback.")
    LocalizationFailureScenario.HEADING_INVALID -> LocalizationLabResult(false, false, "Heading is invalid, so field-relative and closed-loop rotation are blocked.")
    LocalizationFailureScenario.VISION_REJECTED -> LocalizationLabResult(true, false, "Primary odometry and heading remain usable. Rejected vision is ignored rather than snapping the pose.")
}

internal fun reviewToken(contentHash: String?, changes: List<DrivebaseChange>): String {
    val content = "$contentHash|" + changes.joinToString("|") { "${it.path}:${it.before}->${it.after}" }
    return Sha256.prefixHex(content, byteCount = 8)
}
