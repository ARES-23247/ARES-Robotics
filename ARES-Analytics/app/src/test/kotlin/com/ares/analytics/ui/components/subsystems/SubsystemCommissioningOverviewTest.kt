package com.ares.analytics.ui.components.subsystems

import com.ares.analytics.service.commissioning.CommissioningSimulationStatus
import com.ares.analytics.service.commissioning.CommissioningVerificationService
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubsystemCommissioningOverviewTest {

    @Test
    fun `formatSubsystemTuningMarkdown renders parameters controller rules and safety policy`() {
        val template = SubsystemTemplate.entries.firstOrNull {
            it.name.contains("ARM") || it.name.contains("ELEVATOR") || it.name.contains("POSITION")
        } ?: SubsystemTemplate.entries.first()
        val baseDoc = SubsystemTemplates.create(
            template = template,
            documentId = "pivot-arm",
            kotlinTypeName = "PivotArm",
            platform = SubsystemPlatform.FTC,
            displayName = "Pivot Arm",
        )
        val docWithTuning = baseDoc.copy(
            tuningParameters = listOf(
                TuningParameterDeclaration(
                    uid = "param-kp",
                    key = "pivot.kP",
                    displayName = "Pivot kP",
                    description = "Proportional gain",
                    componentUid = baseDoc.uid,
                    type = TuningParameterType.DOUBLE,
                    unit = "V/rad",
                    minimum = 0.0,
                    maximum = 15.0,
                    defaultValue = TuningValue(doubleValue = 4.5),
                    applyPolicy = TuningApplyPolicy.LIVE_SAFE,
                )
            )
        )

        val markdown = formatSubsystemTuningMarkdown(docWithTuning)
        assertTrue(markdown.contains("# Pivot Arm — Pit Tuning & Safety Reference"))
        assertTrue(markdown.contains("| Pivot kP | `pivot.kP` | `DOUBLE` | `4.5 V/rad` | [0.0, 15.0] V/rad | `LIVE_SAFE` |"))
        assertTrue(markdown.contains("## Controller Rules"))
        assertTrue(markdown.contains("## Safety & Fault Neutralization Policy"))
        assertTrue(markdown.contains("Feedback Freshness Timeout"))

        val summary = CommissioningVerificationService().verify(listOf(docWithTuning))
        assertEquals(CommissioningSimulationStatus.VERIFIED, summary.status)
        assertTrue(summary.controllerCount >= 1)
    }
}
