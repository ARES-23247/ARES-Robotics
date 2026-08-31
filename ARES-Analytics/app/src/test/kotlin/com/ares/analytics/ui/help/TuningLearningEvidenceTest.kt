package com.ares.analytics.ui.help

import com.ares.analytics.service.tuning.TuningProfileChange
import com.ares.analytics.service.tuning.TuningProposalReview
import com.ares.analytics.service.tuning.TuningValueOwner
import com.ares.analytics.service.tuning.TuningValueProvenance
import com.ares.analytics.viewmodel.TuningState
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningAssignment
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuningLearningEvidenceTest {
    private val velocityFeedforward = TuningParameterDeclaration(
        uid = "practice-lift.feedforward.kv",
        key = "practiceLift.feedforward.kV",
        componentUid = "practice-lift",
        displayName = "Velocity feedforward kV",
        description = "Voltage required per unit of mechanism velocity.",
        type = TuningParameterType.DOUBLE,
        unit = "V per rot/s",
        minimum = 0.0,
        maximum = 12.0,
        defaultValue = TuningValue(doubleValue = 1.0),
        applyPolicy = TuningApplyPolicy.DISABLED_ONLY,
    )
    private val profile = TuningProfileDocument(
        uid = "practice-profile",
        profileId = "competition",
        displayName = "Competition",
        description = "Practice tuning profile",
        projectId = "student-robot",
        authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
        values = listOf(TuningAssignment(velocityFeedforward.uid, velocityFeedforward.defaultValue)),
    )
    private val proposal = TuningValue(doubleValue = 1.2)
    private val provenance = TuningValueProvenance(
        source = "Control-response teaching lab",
        note = "Predicted less steady-state velocity error in the simplified plant.",
    )

    @Test
    fun `typed catalog and feedforward evidence come from the live tuning state`() {
        val snapshot = baseState().toAcademyTuningSnapshot()

        assertTrue(snapshot.isAvailable)
        assertTrue(snapshot.hasTypedCatalog)
        assertTrue(snapshot.hasFeedforwardDeclaration)
        assertFalse(snapshot.hasValidProposal)
        assertFalse(snapshot.hasStructuredReview)
    }

    @Test
    fun `valid proposal provenance and promotable review remain distinct facts`() {
        val proposed = baseState().copy(
            proposals = mapOf(velocityFeedforward.key to proposal),
            proposalProvenance = mapOf(velocityFeedforward.key to provenance),
        )
        val beforeReview = proposed.toAcademyTuningSnapshot()
        assertTrue(beforeReview.hasValidProposal)
        assertTrue(beforeReview.hasProposalProvenance)
        assertFalse(beforeReview.hasStructuredReview)

        val change = TuningProfileChange(
            parameterUid = velocityFeedforward.uid,
            key = velocityFeedforward.key,
            displayName = velocityFeedforward.displayName,
            before = velocityFeedforward.defaultValue,
            after = proposal,
            unit = requireNotNull(velocityFeedforward.unit),
            owner = TuningValueOwner.ROBOT_PROFILE,
            policy = velocityFeedforward.applyPolicy,
            provenance = provenance,
        )
        val reviewed = proposed.copy(
            reviewerName = "Student reviewer",
            reviewSummary = "Reviewing a simulator-first feedforward proposal.",
            review = TuningProposalReview(
                profileId = profile.profileId,
                baseContentHash = "base-hash",
                changes = listOf(change),
                errors = emptyList(),
                confirmationToken = "confirmation-token",
                reviewedBy = "Student reviewer",
                reviewSummary = "Reviewing a simulator-first feedforward proposal.",
            ),
        ).toAcademyTuningSnapshot()

        assertTrue(reviewed.hasStructuredReview)
        assertTrue(reviewed.hasPromotableReview)
    }

    private fun baseState() = TuningState(
        projectPath = "C:/student-robot",
        catalog = listOf(velocityFeedforward),
        profiles = listOf(profile),
        selectedProfileId = profile.profileId,
    )
}
