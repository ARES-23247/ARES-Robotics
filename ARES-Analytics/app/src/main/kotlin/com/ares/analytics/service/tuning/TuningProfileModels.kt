package com.ares.analytics.service.tuning

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningValue

enum class TuningValueOwner { ROBOT_PROFILE, VENDOR_SOURCE }
enum class TuningValueStatus { INHERITED, PROFILE, PROPOSED, INVALID, LIVE_ONLY, UNDECLARED }

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
    doubleValue != null -> "%.5f".format(doubleValue).trimEnd('0').trimEnd('.')
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
    val byUid = profiles.associateBy { it.uid }
    val resolved = com.areslib.tuning.resolveTuningProfiles(profiles, declarations).getValue(profile.uid)
    val direct = profile.values.associateBy { it.parameterUid }
    val parent = profile.baseProfileUid?.let(byUid::get)
    return declarations.sortedWith(compareBy({ it.componentUid }, { it.displayName })).map { declaration ->
        val sourceTyped = resolved[declaration.uid]
        val source = sourceTyped?.numericValue()
        val liveTyped = liveTypedValues[declaration.key] ?: liveValues[declaration.key]?.let { TuningValue(doubleValue = it) }
        val proposed = proposals[declaration.key]
        val error = proposed?.let {
            when {
                !it.matches(declaration.type) -> "Value does not match declared ${declaration.type.name.lowercase()} type."
                it.numericValue()?.isFinite() == false -> "Enter a finite number."
                declaration.type == TuningParameterType.INT && it.intValue == null -> "Enter a whole number."
                declaration.minimum != null && it.numericValue() != null && it.numericValue()!! < declaration.minimum!! -> "Must be at least ${declaration.minimum} ${declaration.unit.orEmpty()}."
                declaration.maximum != null && it.numericValue() != null && it.numericValue()!! > declaration.maximum!! -> "Must be at most ${declaration.maximum} ${declaration.unit.orEmpty()}."
                declaration.type == TuningParameterType.ENUM && it.textValue !in declaration.enumOptions -> "Choose one of ${declaration.enumOptions.joinToString()}."
                declaration.applyPolicy == TuningApplyPolicy.READ_ONLY_VENDOR -> "This value is vendor-owned and read-only. Re-import its source instead."
                else -> null
            }
        }
        val directValue = direct[declaration.uid]
        val sourceUid = when { directValue != null -> profile.uid; parent?.values?.any { it.parameterUid == declaration.uid } == true -> parent.uid; else -> null }
        ResolvedTuningValue(
            declaration, source, sourceTyped, sourceUid, liveTyped?.numericValue(), liveTyped, proposed, proposalProvenance[declaration.key],
            when { error != null -> TuningValueStatus.INVALID; proposed != null -> TuningValueStatus.PROPOSED; directValue != null -> TuningValueStatus.PROFILE; sourceUid != null -> TuningValueStatus.INHERITED; liveTyped != null -> TuningValueStatus.LIVE_ONLY; else -> TuningValueStatus.UNDECLARED }, error
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
        if (provenance == null) { errors += "${row.declaration.displayName}: explain where this proposed value came from."; return@mapNotNull null }
        TuningProfileChange(row.declaration.uid, row.declaration.key, row.declaration.displayName, row.sourceTypedValue, proposed, row.declaration.unit.orEmpty(), row.declaration.owner(), row.declaration.applyPolicy, provenance)
    }
    proposals.keys.filter { key -> declarations.none { it.key == key } }.forEach { errors += "$it is not declared by a robot component." }
    return changes to errors.distinct()
}

private fun TuningValue.matches(type: TuningParameterType): Boolean = when (type) {
    TuningParameterType.DOUBLE -> doubleValue != null && intValue == null && booleanValue == null && textValue == null
    TuningParameterType.INT -> intValue != null && doubleValue == null && booleanValue == null && textValue == null
    TuningParameterType.BOOLEAN -> booleanValue != null && doubleValue == null && intValue == null && textValue == null
    TuningParameterType.TEXT, TuningParameterType.ENUM -> textValue != null && doubleValue == null && intValue == null && booleanValue == null
}
