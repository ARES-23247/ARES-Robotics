package com.ares.analytics.ui.components.dashboard

import androidx.compose.ui.ImageComposeScene
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.AresTheme
import org.jetbrains.skia.EncodedImageFormat
import org.mockito.Mockito.*
import java.io.File
import kotlin.test.*

class ControllerHealthRenderTest {
    @Test fun `headless cards render unknown offline and selected replay observations`() {
        val nt = mock(Nt4ClientService::class.java)
        val observations = mapOf(
            "offline" to ControllerHealthObservation(),
            "runtime-unknown" to ControllerHealthObservation(
                ControllerHealthSnapshot(ftcRuntime=FtcRuntimeDashboardState("ARES_PHOTON",null,true,null)),
                source=ControllerHealthSource.LIVE,
            ),
            "replay" to resolveControllerHealth(ControllerHealthObservation(),ReplayFrame(0,
                mapOf("Robot/LoopTimeMs" to 20.0,"Robot/BatteryVoltage" to 12.4)),true,true),
        )
        val output = File("build/diagnostics/health-audit").apply { mkdirs() }
        observations.forEach { (name, observation) ->
            val scene = ImageComposeScene(700,350)
            try {
                scene.setContent { AresTheme {
                    SystemHealthCard(nt, league=League.FTC, isRobotLinkConnected=false, controllerHealth=observation)
                } }
                scene.render().use { image ->
                    assertNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                        File(output,"$name.png").writeBytes(data.bytes)
                    }
                }
                assertTrue(File(output,"$name.png").length()>1_000)
            } finally { scene.close() }
        }
    }
}
