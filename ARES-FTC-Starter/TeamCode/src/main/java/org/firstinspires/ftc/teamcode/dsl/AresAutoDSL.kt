package org.firstinspires.ftc.teamcode.dsl

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.photon.AresFtcRuntimeOptions
import com.areslib.ftc.runtime.FtcAutonomousProjectDefinition
import com.areslib.ftc.runtime.FtcGeneratedAutonomousOpMode
import com.areslib.ftc.runtime.FtcGeneratedAutonomousRuntime
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.state.Alliance
import org.firstinspires.ftc.teamcode.config.AresRuntimePolicy
import org.firstinspires.ftc.teamcode.generated.GeneratedAresProject
import org.firstinspires.ftc.teamcode.opmodes.AresRobot

/**
 * Thin starter adapter over ARESLib's FTC generated-autonomous lifecycle.
 *
 * Teams customize canonical `.ares` documents and explicit hardware extensions. Deterministic
 * selection, deadlines, routine lifecycle, fail-closed stop behavior, and pose handoff remain
 * library-owned mechanical behavior rather than copied starter implementation.
 */
abstract class AresAutoBase : FtcGeneratedAutonomousOpMode<AresRobot>() {
    final override val configuredRuntimeOptions: AresFtcRuntimeOptions
        get() = AresRuntimePolicy.options

    final override val autonomousProject = FtcAutonomousProjectDefinition(
        entries = GeneratedAresProject.autonomousEntries,
        defaultEntryId = GeneratedAresProject.DEFAULT_AUTONOMOUS_ENTRY_ID,
        contentSha256 = GeneratedAresProject.CONTENT_SHA256,
    )

    protected override fun buildRobot(): AresRobot = AresRobot(hardwareMap, telemetry)

    protected override fun getMecanumRobot(robot: AresRobot): FtcMecanumRobot = robot.base

    protected override fun updateRobot(robot: AresRobot) = robot.update()

    protected override fun closeRobot(robot: AresRobot) = robot.close()

    protected override fun fatalUpdateFailure(robot: AresRobot): Throwable? = robot.fatalUpdateFailure

    protected override fun createGeneratedRuntime(
        robot: AresRobot,
        entry: AutonomousCatalogEntry?,
        alliance: Alliance,
    ): FtcGeneratedAutonomousRuntime = FtcGeneratedProjectRuntime(
        robot = robot,
        autonomousEntry = entry,
        selectedAlliance = alliance,
    )

    protected override fun validateAutonomousSelection(
        robot: AresRobot,
        entry: AutonomousCatalogEntry,
        alliance: Alliance,
    ): List<String> = validateFtcAutonomousBounds(
        entry = entry,
        routines = GeneratedAresProject.routines,
        envelope = ftcFieldEnvelopeForRobot(robot),
        selectedAlliance = alliance,
    )
}
