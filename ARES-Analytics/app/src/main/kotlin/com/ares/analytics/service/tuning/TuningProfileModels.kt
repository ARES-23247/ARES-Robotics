package com.ares.analytics.service.tuning

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningValue

enum class TuningValueOwner { ROBOT_PROFILE, VENDOR_SOURCE }
enum class TuningValueStatus { INHERITED, PROFILE, PROPOSED, INVALID, DEFAULT, LIVE_ONLY, UNDECLARED }

data class TuningValueProvenance(
    val source: String,
    val note: String,
    /** Project-relative evidence file. Canonical promotion never accepts an external path. */
    val evidencePath: String? = null,
    /** SHA-256 of [evidencePath], captured when the proposal was produced. */
    val evidenceSha256: String? = null
)

data class ResolvedTuningValue(
    val declaration: TuningParameterDeclaration,
    val sourceValue: Double?,
    val sourceTypedValue: TuningValue?,
    /** Stable profile UID (not its human-facing profileId); null means the declaration default. */
    val sourceProfileId: String?,
    val liveValue: Double?,
    val liveTypedValue: TuningValue?,
    val proposedTypedValue: TuningValue?,
    val provenance: TuningValueProvenance?,
    val status: TuningValueStatus,
    val validationMessage: String? = null
) {
    val proposedValue: Double? get() = proposedTypedValue?.numericValue()
}

fun TuningValue.displayValue(): String = when {
    // This string also initializes editable fields. Keep an exact, locale-independent round trip,
    // including subnormals and signed zero, without hundreds of fixed-point digits for large values.
    doubleValue != null -> doubleValue.toString().removeSuffix(".0")
    intValue != null -> intValue.toString()
    booleanValue != null -> booleanValue.toString()
    textValue != null -> requireNotNull(textValue)
    else -> "unavailable"
}

data class TuningProfileChange(
    val parameterUid: String,
    val key: String,
    val displayName: String,
    val before: TuningValue?,
    val after: TuningValue,
    val unit: String,
    val owner: TuningValueOwner,
    val policy: TuningApplyPolicy,
    val provenance: TuningValueProvenance
)

data class TuningProposalReview(
    val profileId: String,
    val baseContentHash: String,
    val changes: List<TuningProfileChange>,
    val errors: List<String>,
    val confirmationToken: String,
    val reviewedBy: String,
    val reviewSummary: String
) { val canPromote: Boolean get() = changes.isNotEmpty() && errors.isEmpty() && reviewedBy.isNotBlank() && reviewSummary.isNotBlank() }

fun TuningParameterDeclaration.owner(): TuningValueOwner =
    if (applyPolicy == TuningApplyPolicy.READ_ONLY_VENDOR) TuningValueOwner.VENDOR_SOURCE else TuningValueOwner.ROBOT_PROFILE

fun TuningValue.numericValue(): Double? = doubleValue ?: intValue?.toDouble()

fun resolveTuningProfile(
    profile: TuningProfileDocument,
    profiles: List<TuningProfileDocument>,
    declarations: List<TuningParameterDeclaration>,
    liveValues: Map<String, Double>,
    proposals: Map<String, TuningValue>,
    liveTypedValues: Map<String, TuningValue> = emptyMap(),
    proposalProvenance: Map<String, TuningValueProvenance> = emptyMap()
): List<ResolvedTuningValue> {
    val declarationIssues = com.areslib.tuning.validateTuningParameterDeclarations(declarations)
    require(declarationIssues.isEmpty()) { "Invalid tuning declarations: $declarationIssues" }
    val byUid = profiles.associateBy { it.uid }
    require(byUid[profile.uid] == profile) { "Selected tuning profile does not match the loaded snapshot." }
    val resolved = com.areslib.tuning.resolveTuningProfiles(profiles, declarations).getValue(profile.uid)
    val direct = profile.values.associateBy { it.parameterUid }
    val parent = profile.baseProfileUid?.let(byUid::get)
    val inheritedUids = parent?.values?.mapTo(hashSetOf()) { it.parameterUid }.orEmpty()
    return declarations.sortedWith(declarationOrder).map { declaration ->
        val sourceTyped = resolved[declaration.uid] ?: declaration.defaultValue
        val source = sourceTyped.numericValue()
        val observation = liveTypedValues[declaration.key]
        val liveTyped = if (observation != null) observation.takeIf {
            it.matches(declaration.type) && it.numericValue()?.isFinite() != false
        } else liveValues[declaration.key]?.let { numericObservation(it, declaration.type) }
        val proposed = proposals[declaration.key]
        val error = proposed?.let {
            val numeric = it.numericValue()
            val minimum = declaration.minimum
            val maximum = declaration.maximum
            when {
                !it.matches(declaration.type) -> "Value does not match declared ${declaration.type.name.lowercase()} type."
                numeric?.isFinite() == false -> "Enter a finite number."
                minimum != null && numeric != null && numeric < minimum -> "Must be at least $minimum ${declaration.unit.orEmpty()}."
                maximum != null && numeric != null && numeric > maximum -> "Must be at most $maximum ${declaration.unit.orEmpty()}."
                declaration.type == TuningParameterType.ENUM && it.textValue !in declaration.enumOptions -> "Choose one of ${declaration.enumOptions.joinToString()}."
                declaration.applyPolicy == TuningApplyPolicy.READ_ONLY_VENDOR -> "This value is vendor-owned and read-only. Re-import its source instead."
                else -> null
            }
        }
        val directValue = direct[declaration.uid]
        val sourceUid = when { directValue != null -> profile.uid; declaration.uid in inheritedUids -> parent?.uid; else -> null }
        ResolvedTuningValue(
            declaration, source, sourceTyped, sourceUid, liveTyped?.numericValue(), liveTyped, proposed, proposalProvenance[declaration.key],
            when { error != null -> TuningValueStatus.INVALID; proposed != null -> TuningValueStatus.PROPOSED; directValue != null -> TuningValueStatus.PROFILE; sourceUid != null -> TuningValueStatus.INHERITED; else -> TuningValueStatus.DEFAULT }, error
        )
    }
}

fun buildTuningReview(
    profile: TuningProfileDocument,
    profiles: List<TuningProfileDocument>,
    declarations: List<TuningParameterDeclaration>,
    proposals: Map<String, TuningValue>,
    proposalProvenance: Map<String, TuningValueProvenance>
): Pair<List<TuningProfileChange>, List<String>> {
    val rows = resolveTuningProfile(profile, profiles, declarations, emptyMap(), proposals, proposalProvenance = proposalProvenance)
    val errors = rows.mapNotNull { row -> row.validationMessage?.let { "${row.declaration.displayName}: $it" } }.toMutableList()
    val changes = rows.mapNotNull { row ->
        val proposed = row.proposedTypedValue ?: return@mapNotNull null
        if (row.validationMessage != null || proposed == row.sourceTypedValue) return@mapNotNull null
        val provenance = proposalProvenance[row.declaration.key]
        if (provenance == null || provenance.source.isBlank()) { errors += "${row.declaration.displayName}: explain where this proposed value came from."; return@mapNotNull null }
        TuningProfileChange(row.declaration.uid, row.declaration.key, row.declaration.displayName, row.sourceTypedValue, proposed, row.declaration.unit.orEmpty(), row.declaration.owner(), row.declaration.applyPolicy, provenance)
    }
    val declaredKeys = declarations.mapTo(hashSetOf()) { it.key }
    proposals.keys.filterNot { it in declaredKeys }.forEach { errors += "$it is not declared by a robot component." }
    return changes to errors.distinct()
}

private val declarationOrder = compareBy<TuningParameterDeclaration>({ it.componentUid }, { it.displayName })

/** Numeric-only legacy observations must retain their declared type; never truncate an integer. */
private fun numericObservation(value: Double, type: TuningParameterType): TuningValue? {
    if (!value.isFinite()) return null
    return when (type) {
        TuningParameterType.DOUBLE -> TuningValue(doubleValue = value)
        TuningParameterType.INT -> if (value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
            TuningValue(intValue = value.toInt()) else null
        else -> null
    }
}

private fun TuningValue.matches(type: TuningParameterType): Boolean = when (type) {
    TuningParameterType.DOUBLE -> doubleValue != null && intValue == null && booleanValue == null && textValue == null
    TuningParameterType.INT -> intValue != null && doubleValue == null && booleanValue == null && textValue == null
    TuningParameterType.BOOLEAN -> booleanValue != null && doubleValue == null && intValue == null && textValue == null
    TuningParameterType.TEXT, TuningParameterType.ENUM -> textValue != null && doubleValue == null && intValue == null && booleanValue == null
}
