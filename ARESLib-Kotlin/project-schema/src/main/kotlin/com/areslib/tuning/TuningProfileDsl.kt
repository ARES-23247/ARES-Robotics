package com.areslib.tuning

@DslMarker
annotation class AresTuningDsl

internal fun defaultTuningDisplayName(id: String): String {
    val trimmed = id.trim()
    if (trimmed.isEmpty()) return id
    return trimmed
        .substringAfterLast('.')
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .split(Regex("[_\\-\\s.]+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { token ->
            token.lowercase().replaceFirstChar(Char::titlecase)
        }
}

/**
 * Builds a validated [TuningComponentDocument] declaring project/global tuning parameters.
 *
 * ### Complete Example
 * ```kotlin
 * val globalTuning = tuningComponent(uid = "robot.global", projectId = "ftc-2026-alpha") {
 *     double(
 *         key = "vision.maxLatencyMs",
 *         defaultValue = 120.0,
 *         unit = "ms",
 *         minimum = 10.0,
 *         maximum = 500.0,
 *     )
 *     boolean(key = "telemetry.verbose", defaultValue = false)
 * }
 * ```
 */
fun tuningComponent(
    uid: String,
    projectId: String,
    displayName: String = defaultTuningDisplayName(uid),
    description: String = "$displayName tuning parameters",
    block: TuningComponentBuilder.() -> Unit,
): TuningComponentDocument =
    TuningComponentBuilder(uid, projectId, displayName, description).apply(block).build()

@AresTuningDsl
class TuningComponentBuilder internal constructor(
    val uid: String,
    val projectId: String,
    var displayName: String,
    var description: String,
) {
    private val parameters = mutableListOf<TuningParameterDeclaration>()

    fun double(
        key: String,
        defaultValue: Double,
        uid: String = "${this.uid}.${key.lowercase().replace('.', '-')}",
        displayName: String = defaultTuningDisplayName(key),
        description: String = "$displayName parameter ($key)",
        unit: String? = null,
        minimum: Double? = null,
        maximum: Double? = null,
        applyPolicy: TuningApplyPolicy = TuningApplyPolicy.LIVE_SAFE,
    ): TuningParameterDeclaration = addParameter(
        TuningParameterDeclaration(
            uid = uid,
            key = key,
            componentUid = this.uid,
            displayName = displayName,
            description = description,
            type = TuningParameterType.DOUBLE,
            unit = unit,
            minimum = minimum,
            maximum = maximum,
            defaultValue = TuningValue(doubleValue = defaultValue),
            applyPolicy = applyPolicy,
        )
    )

    fun int(
        key: String,
        defaultValue: Int,
        uid: String = "${this.uid}.${key.lowercase().replace('.', '-')}",
        displayName: String = defaultTuningDisplayName(key),
        description: String = "$displayName parameter ($key)",
        unit: String? = null,
        minimum: Double? = null,
        maximum: Double? = null,
        applyPolicy: TuningApplyPolicy = TuningApplyPolicy.LIVE_SAFE,
    ): TuningParameterDeclaration = addParameter(
        TuningParameterDeclaration(
            uid = uid,
            key = key,
            componentUid = this.uid,
            displayName = displayName,
            description = description,
            type = TuningParameterType.INT,
            unit = unit,
            minimum = minimum,
            maximum = maximum,
            defaultValue = TuningValue(intValue = defaultValue),
            applyPolicy = applyPolicy,
        )
    )

    fun boolean(
        key: String,
        defaultValue: Boolean,
        uid: String = "${this.uid}.${key.lowercase().replace('.', '-')}",
        displayName: String = defaultTuningDisplayName(key),
        description: String = "$displayName parameter ($key)",
        applyPolicy: TuningApplyPolicy = TuningApplyPolicy.LIVE_SAFE,
    ): TuningParameterDeclaration = addParameter(
        TuningParameterDeclaration(
            uid = uid,
            key = key,
            componentUid = this.uid,
            displayName = displayName,
            description = description,
            type = TuningParameterType.BOOLEAN,
            defaultValue = TuningValue(booleanValue = defaultValue),
            applyPolicy = applyPolicy,
        )
    )

    fun text(
        key: String,
        defaultValue: String,
        uid: String = "${this.uid}.${key.lowercase().replace('.', '-')}",
        displayName: String = defaultTuningDisplayName(key),
        description: String = "$displayName parameter ($key)",
        applyPolicy: TuningApplyPolicy = TuningApplyPolicy.LIVE_SAFE,
    ): TuningParameterDeclaration = addParameter(
        TuningParameterDeclaration(
            uid = uid,
            key = key,
            componentUid = this.uid,
            displayName = displayName,
            description = description,
            type = TuningParameterType.TEXT,
            defaultValue = TuningValue(textValue = defaultValue),
            applyPolicy = applyPolicy,
        )
    )

    fun enum(
        key: String,
        defaultValue: String,
        options: List<String>,
        uid: String = "${this.uid}.${key.lowercase().replace('.', '-')}",
        displayName: String = defaultTuningDisplayName(key),
        description: String = "$displayName parameter ($key)",
        applyPolicy: TuningApplyPolicy = TuningApplyPolicy.LIVE_SAFE,
    ): TuningParameterDeclaration = addParameter(
        TuningParameterDeclaration(
            uid = uid,
            key = key,
            componentUid = this.uid,
            displayName = displayName,
            description = description,
            type = TuningParameterType.ENUM,
            defaultValue = TuningValue(textValue = defaultValue),
            enumOptions = options.toList(),
            applyPolicy = applyPolicy,
        )
    )

    private fun addParameter(declaration: TuningParameterDeclaration): TuningParameterDeclaration {
        parameters += declaration
        return declaration
    }

    internal fun build(): TuningComponentDocument {
        val doc = TuningComponentDocument(
            uid = uid,
            projectId = projectId,
            displayName = displayName,
            description = description,
            parameters = parameters.toList(),
        )
        val issues = validateTuningParameterDeclarations(doc.parameters)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
        return doc
    }
}

/**
 * Builds a [TuningProfileDocument] assigning values to declared tuning parameters.
 *
 * ### Complete Example
 * ```kotlin
 * val matchProfile = tuningProfile(profileId = "competition", projectId = "ftc-2026-alpha") {
 *     assignDouble("subsystem.elevator.elevator-kp", 9.5)
 *     assignDouble("drivebase.ftc-mecanum.drive-heading-kp", 2.1)
 * }
 * ```
 */
fun tuningProfile(
    profileId: String,
    projectId: String,
    uid: String = "tuning.profile.$profileId",
    displayName: String = defaultTuningDisplayName(profileId),
    description: String = "$displayName tuning profile",
    authority: TuningProfileAuthority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
    baseProfileUid: String? = null,
    drivebaseUid: String? = null,
    block: TuningProfileBuilder.() -> Unit,
): TuningProfileDocument =
    TuningProfileBuilder(
        uid = uid,
        profileId = profileId,
        projectId = projectId,
        displayName = displayName,
        description = description,
        authority = authority,
        baseProfileUid = baseProfileUid,
        drivebaseUid = drivebaseUid,
    ).apply(block).build()

@AresTuningDsl
class TuningProfileBuilder internal constructor(
    val uid: String,
    val profileId: String,
    val projectId: String,
    var displayName: String,
    var description: String,
    var authority: TuningProfileAuthority,
    var baseProfileUid: String?,
    var drivebaseUid: String?,
) {
    private val assignments = mutableListOf<TuningAssignment>()
    private var promotion: TuningPromotionData? = null

    fun assignDouble(parameterUid: String, value: Double) {
        assignments += TuningAssignment(parameterUid, TuningValue(doubleValue = value))
    }

    fun assignInt(parameterUid: String, value: Int) {
        assignments += TuningAssignment(parameterUid, TuningValue(intValue = value))
    }

    fun assignBoolean(parameterUid: String, value: Boolean) {
        assignments += TuningAssignment(parameterUid, TuningValue(booleanValue = value))
    }

    fun assignText(parameterUid: String, value: String) {
        assignments += TuningAssignment(parameterUid, TuningValue(textValue = value))
    }

    fun promoteFromLocal(
        sourceLocalProfileUid: String,
        sourceContentSha256: String,
        evidencePaths: List<String>,
        evidenceSha256: List<String>,
        reviewedBy: String,
        reviewSummary: String,
    ) {
        promotion = TuningPromotionData(
            sourceLocalProfileUid = sourceLocalProfileUid,
            sourceContentSha256 = sourceContentSha256,
            evidencePaths = evidencePaths,
            evidenceSha256 = evidenceSha256,
            reviewedBy = reviewedBy,
            reviewSummary = reviewSummary,
        )
    }

    internal fun build(): TuningProfileDocument = TuningProfileDocument(
        uid = uid,
        profileId = profileId,
        displayName = displayName,
        description = description,
        projectId = projectId,
        drivebaseUid = drivebaseUid,
        authority = authority,
        baseProfileUid = baseProfileUid,
        values = assignments.toList(),
        promotion = promotion,
    )
}

/**
 * Renders this [TuningProfileDocument] into copy-pasteable Kotlin Text DSL code ([tuningProfile]).
 */
fun TuningProfileDocument.toKotlinDsl(): String = buildString {
    val defaultUid = "tuning.profile.$profileId"
    val defaultDisplay = defaultTuningDisplayName(profileId)
    val defaultDesc = "$defaultDisplay tuning profile"
    val headerArgs = buildList {
        add("profileId = ${profileId.quote()}")
        add("projectId = ${projectId.quote()}")
        if (uid != defaultUid) add("uid = ${uid.quote()}")
        if (displayName != defaultDisplay) add("displayName = ${displayName.quote()}")
        if (description != defaultDesc) add("description = ${description.quote()}")
        if (authority != TuningProfileAuthority.CANONICAL_CHECKED_IN) {
            add("authority = TuningProfileAuthority.${authority.name}")
        }
        baseProfileUid?.let { add("baseProfileUid = ${it.quote()}") }
        drivebaseUid?.let { add("drivebaseUid = ${it.quote()}") }
    }
    appendLine("tuningProfile(${headerArgs.joinToString(", ")}) {")
    values.forEach { assignment ->
        val pUid = assignment.parameterUid.quote()
        val v = assignment.value
        when {
            v.doubleValue != null -> appendLine("    assignDouble($pUid, ${v.doubleValue})")
            v.intValue != null -> appendLine("    assignInt($pUid, ${v.intValue})")
            v.booleanValue != null -> appendLine("    assignBoolean($pUid, ${v.booleanValue})")
            v.textValue != null -> appendLine("    assignText($pUid, ${v.textValue.quote()})")
        }
    }
    promotion?.let { p ->
        val paths = p.evidencePaths.joinToString(", ") { it.quote() }
        val hashes = p.evidenceSha256.joinToString(", ") { it.quote() }
        appendLine(
            "    promoteFromLocal(sourceLocalProfileUid = ${p.sourceLocalProfileUid.quote()}, " +
                "sourceContentSha256 = ${p.sourceContentSha256.quote()}, " +
                "evidencePaths = listOf($paths), evidenceSha256 = listOf($hashes), " +
                "reviewedBy = ${p.reviewedBy.quote()}, reviewSummary = ${p.reviewSummary.quote()})"
        )
    }
    append("}")
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
