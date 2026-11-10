package com.areslib.ftc.vision

import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Quaternion
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.VisionMeasurement
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.vision.apriltag.AprilTagPoseFtc

/** Per-camera SE(3) workspace. No geometry allocations during polling. */
internal class FtcVisionPoseMath {
    private val tagRotation = Rotation3d()
    private val cameraTagRotation = Rotation3d()
    private val fieldCamera = Quaternion()
    private val tagRobot = Quaternion()
    private val rotated = Translation3d()

    fun solve(tag: RobotFieldAprilTag, mount: Pose3d, sdk: AprilTagPoseFtc, scale: Double,
              angleUnit: AngleUnit, convention: FtcTagPoseConvention, output: VisionMeasurement) {
        tagRotation.setEulerAngles(Math.toRadians(tag.roll), Math.toRadians(tag.pitch), Math.toRadians(tag.yaw))
        if (convention == FtcTagPoseConvention.FTC_OPTICAL) {
            // Stored FTC rotations orient the optical tag axes. Convert locally to the
            // outward/left/up tag basis used by this SE(3) workspace; leave the field document intact.
            val tq = tagRotation.q
            val tw = tq.w; val tx = tq.x; val ty = tq.y; val tz = tq.z
            tq.w = 0.5*(tw-tx-ty+tz); tq.x = 0.5*(tw+tx-ty-tz)
            tq.y = 0.5*(tw+tx+ty+tz); tq.z = 0.5*(-tw+tx-ty+tz)
        }
        // Raw intrinsic YXZ -> body ZYX: yaw, -pitch, roll; face-on tag adds local Z pi.
        val radians = if (angleUnit == AngleUnit.RADIANS) 1.0 else Math.PI / 180.0
        cameraTagRotation.setEulerAngles(sdk.roll*radians, -sdk.pitch*radians, sdk.yaw*radians)
        val q = cameraTagRotation.q
        val w = q.w; val x = q.x; val y = q.y; val z = q.z
        q.w = -z; q.x = y; q.y = -x; q.z = w
        product(fieldCamera, tagRotation.q, q, inverseB = true)
        rotate(rotated, fieldCamera, sdk.y*scale, -sdk.x*scale, sdk.z*scale)
        val cx = tag.x - rotated.x; val cy = tag.y - rotated.y; val cz = tag.z - rotated.z
        val robot = output.targetPose
        product(robot.rotation.q, fieldCamera, mount.rotation.q, inverseB = true)
        rotate(rotated, robot.rotation.q, mount.translation.x, mount.translation.y, mount.translation.z)
        robot.translation.x = cx - rotated.x
        robot.translation.y = cy - rotated.y
        robot.translation.z = cz - rotated.z
        // Target frame +X outward/+Y left/+Z up -> Limelight +X right/+Y up/+Z outward.
        product(tagRobot, tagRotation.q, robot.rotation.q, inverseA = true)
        rotate(rotated, tagRotation.q, robot.translation.x-tag.x, robot.translation.y-tag.y,
            robot.translation.z-tag.z, inverse = true)
        val target = output.robotPoseTargetSpace
        target.translation.x = rotated.y; target.translation.y = rotated.z; target.translation.z = rotated.x
        // Face-on target yaw is zero, with shared heading = -rotation.y.
        val tq = target.rotation.q
        tq.w = -tagRobot.z; tq.x = tagRobot.x; tq.y = -tagRobot.w; tq.z = -tagRobot.y
    }

    private fun product(out: Quaternion, a: Quaternion, b: Quaternion,
                        inverseA: Boolean = false, inverseB: Boolean = false) {
        val ax = if (inverseA) -a.x else a.x; val ay = if (inverseA) -a.y else a.y; val az = if (inverseA) -a.z else a.z
        val bx = if (inverseB) -b.x else b.x; val by = if (inverseB) -b.y else b.y; val bz = if (inverseB) -b.z else b.z
        val aw = a.w; val bw = b.w
        out.w = aw*bw - ax*bx - ay*by - az*bz
        out.x = aw*bx + ax*bw + ay*bz - az*by
        out.y = aw*by - ax*bz + ay*bw + az*bx
        out.z = aw*bz + ax*by - ay*bx + az*bw
    }

    private fun rotate(out: Translation3d, q: Quaternion, x: Double, y: Double, z: Double, inverse: Boolean = false) {
        val qx = if (inverse) -q.x else q.x; val qy = if (inverse) -q.y else q.y; val qz = if (inverse) -q.z else q.z
        val tx = 2.0*(qy*z-qz*y); val ty = 2.0*(qz*x-qx*z); val tz = 2.0*(qx*y-qy*x)
        out.x = x + q.w*tx + qy*tz-qz*ty
        out.y = y + q.w*ty + qz*tx-qx*tz
        out.z = z + q.w*tz + qx*ty-qy*tx
    }
}
