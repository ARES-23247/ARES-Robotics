package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.RobotAction

/**
 * A highly simplified, student-facing modular facade for a Swerve drivetrain subsystem.
 *
 * Inherits standard coordinate transformations, heading locking, and driving math from [HolonomicDriveFacade],
 * and adds swerve-specific features like active braking configuration.
 */
class SwerveDriveFacade(store: Store) : HolonomicDriveFacade(store) {
    /**
     * Commands all swerve modules to lock into an "X" configuration (orthogonal angles)
     * with exactly 0.0 speed. This resists pushes from opponent robots.
     */
    fun brake() {
        store.dispatch(RobotAction.SetDriveMode(com.areslib.state.DriveMode.X_BRAKE))
    }
}
