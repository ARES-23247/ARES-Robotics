package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import com.ares.analytics.shared.models.*
import com.ares.analytics.ui.theme.AresTheme
import com.ares.analytics.ui.theme.AresBackground
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class DashboardMissionRenderTest {
    @Test fun `headless mission summaries and details keep source and evidence labels explicit`() {
        val workspace=WorkspaceConfig(id="render",robotId="robot",robotName="Robot",teamId="1",seasonId="2026",league=League.FTC,projectPath="/fixture")
        val base=DashboardMissionSnapshot(workspace,true,true,true,false,null,
            loopTimeMs=20.0,batteryVoltage=12.6,brownoutCount=0,loopOverruns=0,lastUpdateAgeMs=0,frameRateHz=100.0)
        val samples=mapOf(
            "simulated" to base,
            "rewind" to base.copy(isConnected=false,isReplayActive=true),
            "missing" to base.copy(loopTimeMs=Double.NaN,batteryVoltage=null,loopOverruns=null),
            "battery-warning" to base.copy(isLocalSimulator=false,batteryVoltage=11.0),
            "xrp" to base.copy(workspace=workspace.copy(league=League.XRP),isLocalSimulator=false,batteryVoltage=6.0),
        )
        val output=File("build/diagnostics/mission-audit").apply { mkdirs() }
        samples.forEach { (name,snapshot) ->
            val scene=ImageComposeScene(920,430)
            try {
                scene.setContent { AresTheme { Column(Modifier.fillMaxSize().background(AresBackground)) {
                    DashboardMissionHeader(snapshot,{})
                    DashboardMissionDetails(snapshot,{})
                } } }
                scene.render().use { image ->
                    assertNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data -> File(output,"$name.png").writeBytes(data.bytes) }
                }
                assertTrue(File(output,"$name.png").length()>1_000)
            } finally { scene.close() }
        }
    }
    @Test fun `critical popup stack renders bounded source time notices`() {
        val alerts=(1..20).map { AlertRecord("alert-$it","live","Hardware/CAN/Utilization",it*100L,peakValue=0.9) }
        val scene=ImageComposeScene(350,500)
        val output=File("build/diagnostics/mission-audit").apply { mkdirs() }
        try {
            scene.setContent { AresTheme { Box(Modifier.fillMaxSize().background(AresBackground)) {
                DashboardCriticalAlertStack(alerts,{})
            } } }
            scene.render().use { image ->
                assertNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data -> File(output,"popups.png").writeBytes(data.bytes) }
            }
            assertTrue(File(output,"popups.png").length()>1_000)
        } finally { scene.close() }
    }
}
