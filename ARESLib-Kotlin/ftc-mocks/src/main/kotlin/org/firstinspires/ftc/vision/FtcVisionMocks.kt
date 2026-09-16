@file:Suppress("UNUSED_PARAMETER")
package org.firstinspires.ftc.vision

/**
 * Class implementation for [VisionPortal].
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators
 * into immutable Redux state representations.
 *
 * In desktop simulation, this provides the base double for the FTC VisionPortal
 * subsystem, enabling camera lifecycle management, portal streaming checks,
 * and AprilTag processor attachments without requiring physical webcam drivers
 * or Android video pipeline services.
 *
 * Test harnesses and simulated robot controllers interact with this portal mock
 * during autonomous trajectory verification and vision pipeline tests.
 *
 * Concrete simulation fixtures or test doubles may extend this class to simulate
 * live camera stream states, multi-portal view layouts, or camera calibration routines.
 *
 * The portal double is safely inert and safe for continuous execution in unit tests.
 * It produces zero side effects and leaks no background threads.
 */
open class VisionPortal {
    /** Constructs a desktop mock instance of [VisionPortal]. */
    constructor()
}
