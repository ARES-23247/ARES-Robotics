package org.firstinspires.ftc.teamcode.dsl

import com.areslib.pathing.CommandKey
import com.areslib.pathing.NamedCommandDescriptor
import com.areslib.pathing.NamedCommands
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskResources
import com.areslib.sequencer.TaskStateMachine
import com.areslib.state.RobotState

/** Season-owned capabilities that are not generated from Robot Builder documents. */
object FtcAutoCapabilities {
    val DRIVE_RECOVER_NEUTRAL = NamedCommandDescriptor(
        key = CommandKey("drivetrain.recoverNeutral"),
        displayName = "Recover drive after a fault",
        description = "Requires released drive controls, writes neutral to all four motors, then clears the drive fault latch.",
        category = "Drive safety",
        requiredResources = TaskResources.DRIVE,
    )

    /** Registers the explicit, neutral-first recovery required by the generated drive contract. */
    fun registerDriveRecovery(recoverWithNeutral: () -> Boolean) {
        NamedCommands.register(DRIVE_RECOVER_NEUTRAL) {
            object : Task {
                override val name: String = DRIVE_RECOVER_NEUTRAL.displayName
                override val requiredResources: Long = DRIVE_RECOVER_NEUTRAL.requiredResources
                private var recovered = false

                override fun initialize(state: RobotState): List<com.areslib.action.RobotAction> {
                    super.initialize(state)
                    recovered = recoverWithNeutral()
                    if (!recovered) TaskStateMachine.markFailed(this)
                    return emptyList()
                }

                override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = recovered

                override fun releaseRuntimeState() {
                    recovered = false
                    super.releaseRuntimeState()
                }
            }
        }
    }
}
