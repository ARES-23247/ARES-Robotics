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
        require(profiles.map { it.projectUid }.distinct().size == 1) { "Every tuning profile must target one robot project" }
        val projectUid = profiles.singleProjectUid()
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
            appendLine("    const val PROJECT_UID: String = ${projectUid.q()}")
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

    fun generateProjectTuning(
        projectUid: String,
        canonicalProfileUid: String,
        drivebaseUid: String?,
        declarations: List<TuningParameterDeclaration>,
        profiles: List<TuningProfileDocument>,
        packageName: String,
    ): GeneratedDrivebaseFile {
        require(profiles.all { it.projectUid == projectUid && it.authority == TuningProfileAuthority.CANONICAL_CHECKED_IN })
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
            appendLine("    const val PROJECT_UID: String = ${projectUid.q()}")
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
            appendLine("        projectUid = PROJECT_UID, drivebaseUid = DRIVEBASE_UID,")
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

private fun List<TuningProfileDocument>.singleProjectUid(): String {
    val projectUids = map { it.projectUid }.distinct()
    require(projectUids.size == 1) { "At least one canonical profile for exactly one robot project is required" }
    return projectUids.single()
}

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
    doubleValue != null -> "TuningValue(doubleValue = ${doubleValue.literal()})"
    intValue != null -> "TuningValue(intValue = $intValue)"
    booleanValue != null -> "TuningValue(booleanValue = $booleanValue)"
    textValue != null -> "TuningValue(textValue = ${textValue.q()})"
    else -> error("Invalid empty tuning value")
}
