// ARES OWNERSHIP: GENERATED STARTER
// Runtime choices are authored in .ares/project.json and regenerated into GeneratedAresProject.
package org.firstinspires.ftc.teamcode.config

import com.areslib.ftc.photon.AresFtcRuntimeOptions
import com.areslib.ftc.photon.FtcHubCommandTransport
import org.firstinspires.ftc.teamcode.generated.GeneratedAresProject

/** Typed bridge from reviewed project metadata into FTC pre-init and robot startup. */
object AresRuntimePolicy {
    val options: AresFtcRuntimeOptions = AresFtcRuntimeOptions(
        hubCommandTransport = FtcHubCommandTransport.valueOf(
            GeneratedAresProject.RuntimeOptions.FTC_HUB_COMMAND_TRANSPORT,
        ),
        limelightProxyEnabled = GeneratedAresProject.RuntimeOptions.FTC_LIMELIGHT_PROXY_ENABLED,
    )
}
