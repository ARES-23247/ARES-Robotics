package com.areslib.drivetrain

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterType

/**
 * Renders this canonical [DrivetrainDocument] into idiomatic, copy-pasteable Kotlin Text DSL code
 * ([drivetrain], [ftcMecanumDrivetrain], or [frcCtreSwerveDrivetrain]).
 *
 * Default arguments are omitted where possible so the resulting snippet reads like hand-authored
 * Kotlin code.
 */
fun DrivetrainDocument.toKotlinDsl(): String {
    val issues = validateDrivetrainDocument(this)
    require(issues.isEmpty()) { formatDrivetrainValidationReport(issues) }
    // A starter is a shortcut only when every field it seeds still matches. Custom hardware,
    // modules, localization, provenance and non-double tuning use the lossless canonical codec.
    if (!canRenderStarter()) {
        return "com.areslib.drivetrain.DrivetrainDocumentCodec.decode(\n" +
            DrivetrainDocumentCodec.encode(this).lines().joinToString(" +\n") { (it + "\n").quote().prependIndent("    ") } +
            "\n)"
    }
    return renderStarter()
}

private fun DrivetrainDocument.canRenderStarter(): Boolean {
    val ctre = ctreImport
    val starter = when {
        kind == DrivetrainKind.FTC_MECANUM && platform == DrivetrainPlatform.FTC ->
            ftcMecanumDrivetrain(drivebaseId, displayName, uid, canonicalProfileUid)
        kind == DrivetrainKind.FRC_CTRE_SWERVE && ctre != null ->
            frcCtreSwerveDrivetrain(
                id = drivebaseId, displayName = displayName, uid = uid,
                canonicalProfileUid = canonicalProfileUid, vendorSourcePath = ctre.vendorSourcePath,
                sourceSha256 = ctre.sourceSha256, drivetrainConstantsClassName = ctre.drivetrainConstantsClassName,
                canBusName = ctre.canBusName, generatorName = ctre.generatorName, generatorVersion = ctre.generatorVersion,
            )
        else -> return false
    }
    if (parameters.any { it.type != TuningParameterType.DOUBLE || it.defaultValue.doubleValue == null }) return false
    val renderedParameters = ftcMecanumDrivetrain {
        parameters.forEach { param ->
            tuningDouble(param.key, requireNotNull(param.defaultValue.doubleValue),
                componentUid = param.componentUid, unit = param.unit, minimum = param.minimum,
                maximum = param.maximum, applyPolicy = param.applyPolicy)
        }
    }.parameters
    return this == starter.copy(description = description, geometry = geometry, control = control,
        safety = safety, parameters = renderedParameters)
}

private fun DrivetrainDocument.renderStarter(): String = buildString {
    val defaultUid = "drive.${drivebaseId.lowercase().replace('_', '-')}"
    val defaultDisplayName = defaultDrivetrainDisplayName(drivebaseId)
    val ctre = ctreImport
    when {
        kind == DrivetrainKind.FRC_CTRE_SWERVE && ctre != null -> {
            appendLine("frcCtreSwerveDrivetrain(")
            appendLine("    id = ${drivebaseId.quote()},")
            if (displayName != defaultDisplayName) appendLine("    displayName = ${displayName.quote()},")
            if (uid != defaultUid) appendLine("    uid = ${uid.quote()},")
            if (canonicalProfileUid != "profile.competition") {
                appendLine("    canonicalProfileUid = ${canonicalProfileUid.quote()},")
            }
            appendLine("    vendorSourcePath = ${ctre.vendorSourcePath.quote()},")
            appendLine("    sourceSha256 = ${ctre.sourceSha256.quote()},")
            appendLine("    drivetrainConstantsClassName = ${ctre.drivetrainConstantsClassName.quote()},")
            if (ctre.canBusName != "CANivore") appendLine("    canBusName = ${ctre.canBusName.quote()},")
            if (ctre.generatorName != "CTRE Tuner X") appendLine("    generatorName = ${ctre.generatorName.quote()},")
            if (ctre.generatorVersion != "2026.1") appendLine("    generatorVersion = ${ctre.generatorVersion.quote()},")
            appendLine(") {")
        }
        kind == DrivetrainKind.FTC_MECANUM && isStandardFtcMecanumComponents() -> {
            val headerArgs = buildList {
                if (drivebaseId != "main-drive") add("id = ${drivebaseId.quote()}")
                if (displayName != defaultDisplayName) add("displayName = ${displayName.quote()}")
                if (uid != defaultUid) add("uid = ${uid.quote()}")
                if (canonicalProfileUid != "profile.competition") {
                    add("canonicalProfileUid = ${canonicalProfileUid.quote()}")
                }
            }
            if (headerArgs.isEmpty()) {
                appendLine("ftcMecanumDrivetrain {")
            } else {
                appendLine("ftcMecanumDrivetrain(${headerArgs.joinToString(", ")}) {")
            }
        }
        else -> {
            appendLine("drivetrain(")
            appendLine("    id = ${drivebaseId.quote()},")
            appendLine("    kind = DrivetrainKind.${kind.name},")
            appendLine("    platform = DrivetrainPlatform.${platform.name},")
            if (displayName != defaultDisplayName) appendLine("    displayName = ${displayName.quote()},")
            if (uid != defaultUid) appendLine("    uid = ${uid.quote()},")
            if (canonicalProfileUid != "profile.competition") {
                appendLine("    canonicalProfileUid = ${canonicalProfileUid.quote()},")
            }
            appendLine(") {")
        }
    }

    val defaultDesc = when {
        kind == DrivetrainKind.FRC_CTRE_SWERVE && ctre != null -> "Four-module FRC CTRE Phoenix 6 swerve drivebase."
        kind == DrivetrainKind.FTC_MECANUM && isStandardFtcMecanumComponents() -> "Four-wheel FTC mecanum drivebase."
        else -> "$displayName drivetrain."
    }
    if (description != defaultDesc && description.isNotBlank()) {
        appendLine("    description = ${description.quote()}")
    }

    renderGeometryBlock(this)

    val usedPreseededStarter =
        (kind == DrivetrainKind.FRC_CTRE_SWERVE && ctre != null) ||
            (kind == DrivetrainKind.FTC_MECANUM && isStandardFtcMecanumComponents())

    if (!usedPreseededStarter) {
        val moduleComponentUids = modules.flatMapTo(linkedSetOf()) { it.componentUids }
        modules.forEach { mod ->
            appendLine(
                "    swerveModule(${mod.uid.removePrefix("module.").quote()}, " +
                    "xMeters = ${mod.xMeters}, yMeters = ${mod.yMeters}, " +
                    "displayName = ${mod.displayName.quote()}, uid = ${mod.uid.quote()})"
            )
        }
        components.filter { it.uid !in moduleComponentUids }.forEach { comp ->
            renderComponentLine(this, comp)
        }
        val loc = localization
        val primary = loc.primaryOdometry
        appendLine("    localization(")
        appendLine("        primarySource = LocalizationSourceKind.${primary.source.name},")
        appendLine("        primaryComponentUids = listOf(${primary.componentUids.joinToString(", ") { it.quote() }}),")
        appendLine("        headingSourceUid = ${loc.headingSourceUid.quote()},")
        appendLine("        primaryUid = ${primary.uid.quote()},")
        if (primary.implementationClassName != null) {
            appendLine("        implementationClassName = ${primary.implementationClassName.quote()},")
        }
        appendLine("    )")
        appendLine(
            "    simulation(modelClassName = ${simulation.modelClassName.quote()}, " +
                "adapterClassName = ${simulation.adapterClassName.quote()})"
        )
        if (ctre != null) {
            appendLine("    ctreImport(")
            appendLine("        vendorSourcePath = ${ctre.vendorSourcePath.quote()},")
            appendLine("        sourceSha256 = ${ctre.sourceSha256.quote()},")
            appendLine("        drivetrainConstantsClassName = ${ctre.drivetrainConstantsClassName.quote()},")
            appendLine("        canBusName = ${ctre.canBusName.quote()},")
            appendLine("    )")
        }
    }

    val defaultControl = DrivetrainControlDocument(
        supported = listOf(
            DrivetrainControlKind.OPEN_LOOP,
            DrivetrainControlKind.CHASSIS_VELOCITY,
            DrivetrainControlKind.TRAJECTORY,
        ),
        defaultControl = DrivetrainControlKind.CHASSIS_VELOCITY,
        fieldCentric = true,
    )
    if (control != defaultControl) {
        val supportedLiteral = control.supported.joinToString(", ") { "DrivetrainControlKind.${it.name}" }
        appendLine(
            "    control(defaultControl = DrivetrainControlKind.${control.defaultControl.name}, " +
                "supported = listOf($supportedLiteral), fieldCentric = ${control.fieldCentric})"
        )
    }

    val defaultSafety = DrivetrainSafetyDocument()
    if (safety != defaultSafety) {
        appendLine(
            "    safety(staleFeedbackTimeoutMs = ${safety.staleFeedbackTimeoutMs}L, " +
                "currentValidityRequired = ${safety.currentValidityRequired}, " +
                "enabledNeutralMode = DrivetrainNeutralMode.${safety.enabledNeutralMode.name}, " +
                "disabledPolicy = DisabledDrivePolicy.${safety.disabledPolicy.name})"
        )
    }

    parameters.filter { it.type == TuningParameterType.DOUBLE }.forEach { param ->
        val defaultVal = param.defaultValue.doubleValue ?: 0.0
        val args = buildList {
            add("key = ${param.key.quote()}")
            add("defaultValue = $defaultVal")
            if (param.componentUid != uid) add("componentUid = ${param.componentUid.quote()}")
            if (param.unit != null) add("unit = ${param.unit.quote()}")
            if (param.minimum != null) add("minimum = ${param.minimum}")
            if (param.maximum != null) add("maximum = ${param.maximum}")
            if (param.applyPolicy != TuningApplyPolicy.LIVE_SAFE) {
                add("applyPolicy = TuningApplyPolicy.${param.applyPolicy.name}")
            }
        }
        appendLine("    tuningDouble(${args.joinToString(", ")})")
    }

    append("}")
}

private fun DrivetrainDocument.isStandardFtcMecanumComponents(): Boolean {
    val motorIds = components.filter { it.role == DrivetrainComponentRole.DRIVE_MOTOR }.map { it.hardwareId }
    val odomIds = components.filter { it.role == DrivetrainComponentRole.ODOMETRY_SENSOR }.map { it.hardwareId }
    return motorIds == listOf("fl", "fr", "rl", "rr") &&
        odomIds == listOf("pinpoint") &&
        localization.primaryOdometry.source == LocalizationSourceKind.PINPOINT
}

private fun DrivetrainDocument.renderGeometryBlock(out: StringBuilder) {
    val g = geometry
    out.appendLine("    geometry(")
    out.appendLine("        wheelDiameterMeters = ${g.wheelDiameterMeters},")
    out.appendLine("        trackWidthMeters = ${g.trackWidthMeters},")
    out.appendLine("        wheelBaseMeters = ${g.wheelBaseMeters},")
    out.appendLine("        driveGearRatio = ${g.driveGearRatio},")
    if (g.steerGearRatio != null) {
        out.appendLine("        steerGearRatio = ${g.steerGearRatio},")
    }
    out.appendLine("        maxLinearSpeedMetersPerSecond = ${g.maxLinearSpeedMetersPerSecond},")
    out.appendLine("        maxAngularSpeedRadiansPerSecond = ${g.maxAngularSpeedRadiansPerSecond},")
    out.appendLine("    )")
}

private fun renderComponentLine(out: StringBuilder, comp: DrivetrainComponentDocument) {
    when (comp.role) {
        DrivetrainComponentRole.DRIVE_MOTOR -> {
            val args = buildList {
                add(comp.hardwareId.quote())
                add("uid = ${comp.uid.quote()}")
                add("displayName = ${comp.displayName.quote()}")
                if (comp.inverted) add("inverted = true")
                if (comp.currentLimitAmps != null) add("currentLimitAmps = ${comp.currentLimitAmps}")
            }
            out.appendLine("    driveMotor(${args.joinToString(", ")})")
        }
        DrivetrainComponentRole.STEER_MOTOR -> {
            out.appendLine(
                "    steerMotor(${comp.hardwareId.quote()}, uid = ${comp.uid.quote()}, " +
                    "displayName = ${comp.displayName.quote()})"
            )
        }
        DrivetrainComponentRole.ABSOLUTE_ENCODER -> {
            out.appendLine(
                "    absoluteEncoder(${comp.hardwareId.quote()}, uid = ${comp.uid.quote()}, " +
                    "displayName = ${comp.displayName.quote()})"
            )
        }
        DrivetrainComponentRole.ODOMETRY_SENSOR -> {
            out.appendLine(
                "    odometrySensor(${comp.hardwareId.quote()}, uid = ${comp.uid.quote()}, " +
                    "displayName = ${comp.displayName.quote()})"
            )
        }
        DrivetrainComponentRole.GYRO -> {
            out.appendLine(
                "    gyro(${comp.hardwareId.quote()}, uid = ${comp.uid.quote()}, " +
                    "displayName = ${comp.displayName.quote()})"
            )
        }
        DrivetrainComponentRole.WHEEL_MODULE, DrivetrainComponentRole.OTHER -> {
            out.appendLine(
                "    // ${comp.role.name}: ${comp.displayName} (uid = ${comp.uid}, hardwareId = ${comp.hardwareId})"
            )
        }
    }
}

private fun String.quote(): String = buildString {
    append('"')
    for (ch in this@quote) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(ch)
        }
    }
    append('"')
}
