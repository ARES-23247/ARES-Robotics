package com.ares.analytics.viewmodel.routine

import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.hardware.actuator.PrismPwmPreset
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutineLightingPreviewTest {
    @Test
    fun `generated lighting actions visibly update descriptor placed preview outputs`() {
        val project = Files.createTempDirectory("ares-light-preview-").toFile()
        try {
            val directory = project.resolve(".ares/subsystems").apply { mkdirs() }
            val indicators = SubsystemTemplates.create(
                SubsystemTemplate.INDICATOR_LIGHT_PWM,
                "status",
                "StatusLights",
                SubsystemPlatform.FTC,
            )
            val prism = SubsystemTemplates.create(
                SubsystemTemplate.PRISM_LED_DRIVER,
                "prism",
                "PrismLights",
                SubsystemPlatform.FTC,
            )
            directory.resolve("status.aressubsystem").writeText(SubsystemDocumentCodec.encode(indicators))
            directory.resolve("prism.aressubsystem").writeText(SubsystemDocumentCodec.encode(prism))

            val indicatorField = indicators.controlLoops.single().targetFieldId
            val prismField = prism.controlLoops.single().targetFieldId
            val preview = RoutineLightingPreviewModel.load(project.path).at(
                actions = listOf(
                    RoutinePreviewAction(
                        0.0,
                        "green",
                        "subsystem.status.set.$indicatorField",
                        mapOf("value" to "GREEN"),
                    ),
                    RoutinePreviewAction(
                        0.5,
                        "cycle",
                        "subsystem.status.cycleForward.$indicatorField",
                        emptyMap(),
                    ),
                    RoutinePreviewAction(
                        1.0,
                        "timer",
                        "subsystem.prism.set.$prismField",
                        mapOf("value" to "FTC_TIMER"),
                    ),
                ),
                timeSeconds = 1.0,
            )

            assertEquals(IndicatorLightColor.CYAN.position, preview.indicators.single().position)
            assertEquals(PrismPwmPreset.FTC_TIMER.pulseWidthUs.toDouble(), preview.prismPulseWidthUs)
        } finally {
            project.deleteRecursively()
        }
    }
}
