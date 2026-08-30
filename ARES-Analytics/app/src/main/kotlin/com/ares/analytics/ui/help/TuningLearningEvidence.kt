package com.ares.analytics.ui.help

import com.ares.analytics.viewmodel.TuningState
import com.areslib.tuning.TuningParameterDeclaration

private val feedforwardTokens = listOf(
    "feedforward",
    "static friction",
    "gravity compensation",
    ".ks",
    ".kv",
    ".ka",
    ".kg",
)

private fun TuningParameterDeclaration.isFeedforwardRelated(): Boolean {
    val searchable = "$key $displayName $description".lowercase()
    return feedforwardTokens.any(searchable::contains)
}

/** Maps the real typed tuning editor to narrow Academy evidence without inferring execution. */
fun TuningState.toAcademyTuningSnapshot(): AcademyTuningSnapshot {
    val profile = selectedProfile ?: return AcademyTuningSnapshot.Unavailable
    if (projectPath.isBlank() || catalog.isEmpty()) return AcademyTuningSnapshot.Unavailable

    val proposedRows = rows.filter { it.proposedTypedValue != null }
    val validProposal = proposedRows.isNotEmpty() && proposedRows.all { it.validationMessage == null }
    val hasProvenance = validProposal && proposedRows.all { row ->
        row.provenance?.let { it.source.isNotBlank() && it.note.isNotBlank() } == true
    }
    val currentReview = review
    return AcademyTuningSnapshot(
        isAvailable = profile.profileId.isNotBlank(),
        hasTypedCatalog = catalog.all { declaration ->
            declaration.uid.isNotBlank() && declaration.key.isNotBlank() && declaration.type.name.isNotBlank() &&
                declaration.defaultValue.let { value ->
                    listOf(value.doubleValue, value.intValue, value.booleanValue, value.textValue).count { it != null } == 1
                }
        },
        hasFeedforwardDeclaration = catalog.any { it.isFeedforwardRelated() && !it.unit.isNullOrBlank() },
        hasValidProposal = validProposal,
        hasProposalProvenance = hasProvenance,
        hasStructuredReview = currentReview?.changes?.isNotEmpty() == true,
        hasPromotableReview = currentReview?.canPromote == true,
    )
}
