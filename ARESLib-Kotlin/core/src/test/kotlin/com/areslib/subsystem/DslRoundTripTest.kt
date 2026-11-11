package com.areslib.subsystem

import com.areslib.controls.DriveAxisKeys
import com.areslib.controls.RoutineInvocationPolicy
import com.areslib.controls.controlScheme
import com.areslib.controls.toKotlinDsl
import com.areslib.drivetrain.ftcMecanumDrivetrain
import com.areslib.drivetrain.toKotlinDsl
import com.areslib.routine.routine
import com.areslib.routine.toKotlinDsl
import com.areslib.superstructure.superstructure
import com.areslib.superstructure.toKotlinDsl
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DslRoundTripTest {

    @Test
    fun `SubsystemDocument toKotlinDsl emits concise role shorthands and control blocks`() {
        val doc = subsystem("elevator", SubsystemPlatform.FTC) {
            val target = state.targetDouble("targetMeters", default = 0.0, unit = "m", minimum = 0.0, maximum = 1.2)
            val pos = state.measurementDouble("positionMeters", default = 0.0, unit = "m")
            val leader = hardware.motor("elevator") {
                measurement(pos, SubsystemMeasurementSource.MOTOR_POSITION_NATIVE, scale = 0.0005)
            }
            val follower = hardware.motor("elevatorFollower") {
                follows(leader, inverted = true)
            }
            control.profiledPositionPid("liftPid", leader, target, pos) {
                kP = 8.0
                maximumVelocity = 0.8
                maximumAcceleration = 1.5
                feedforward.elevator(kG = 0.35, kV = 4.2)
            }
            tuningDouble(key = "elevator.kp", defaultValue = 8.0, minimum = 0.0, maximum = 30.0)
        }

        val dsl = doc.toKotlinDsl()
        assertTrue(dsl.contains("subsystem(\"elevator\", SubsystemPlatform.FTC)"))
        assertTrue(dsl.contains("val targetMeters = state.targetDouble(\"targetMeters\", unit = \"m\", minimum = 0.0, maximum = 1.2)"))
        assertTrue(dsl.contains("val elevator = hardware.motor(\"elevator\")"))
        assertTrue(dsl.contains("follows(elevator, inverted = true)"))
        assertTrue(dsl.contains("control.profiledPositionPid(\"liftPid\", elevator, targetMeters, positionMeters)"))
        assertTrue(dsl.contains("feedforward.elevator(kV = 4.2, kG = 0.35)"))
        assertTrue(dsl.contains("tuningDouble(key = \"elevator.kp\", defaultValue = 8.0"))
    }

    @Test
    fun `RoutineDocument toKotlinDsl emits clean driveTo parallel and waitUntil blocks`() {
        val doc = routine("score-preload") {
            driveTo(xMeters = 1.25, yMeters = 0.45, headingDegrees = 90.0) {
                whileDriving("intake.collect")
                onArrival("arm.scoreHeight")
            }
            waitUntil(condition = "arm.atTarget", timeoutSeconds = 1.5)
            parallel {
                action("outtake.release")
                waitSeconds(0.4)
            }
        }

        val dsl = doc.toKotlinDsl()
        assertTrue(dsl.contains("routine(\"score-preload\")"))
        assertTrue(dsl.contains("driveTo(xMeters = 1.25, yMeters = 0.45, headingDegrees = 90.0)"))
        assertTrue(dsl.contains("whileDriving(\"intake.collect\")"))
        assertTrue(dsl.contains("onArrival(\"arm.scoreHeight\")"))
        assertTrue(dsl.contains("waitUntil(condition = \"arm.atTarget\", timeoutSeconds = 1.5)"))
        assertTrue(dsl.contains("parallel {"))
    }

    @Test
    fun `ControlSchemeDocument toKotlinDsl emits fluent axis and button chains`() {
        val doc = controlScheme("competition_default") {
            controller("driver", profile = "xbox-series", devicePort = 0) {
                axis("left_stick_y").deadband(0.05).curve(2.0).invert().slewRate(3.5).drive(DriveAxisKeys.VX)
                button("a").debounce(0.03).onPress { action("intake.collect") }
                trigger("right_trigger", pressAt = 0.65, releaseAt = 0.50).whilePressed {
                    action("shooter.spin") { number("rpm", 4200.0) }
                }
                chord("left_bumper", "right_bumper").onPress {
                    routine("score.sequence", RoutineInvocationPolicy.TOGGLE_CANCEL)
                }
            }
        }

        val dsl = doc.toKotlinDsl()
        assertTrue(dsl.contains("controlScheme(\"competition_default\")"))
        assertTrue(dsl.contains("controller(\"driver\", profile = \"xbox-series\", devicePort = 0)"))
        assertTrue(dsl.contains("axis(\"left_stick_y\").deadband(0.05).curve(2.0).invert().slewRate(3.5).drive(DriveAxisKeys.VX)"))
        assertTrue(dsl.contains("button(\"a\").debounce(0.03).onPress { action(\"intake.collect\") }"))
        assertTrue(dsl.contains("chord(\"left_bumper\", \"right_bumper\").onPress { routine(\"score.sequence\", RoutineInvocationPolicy.TOGGLE_CANCEL) }"))
    }

    @Test
    fun `SuperstructureDocument and DrivetrainDocument toKotlinDsl emit idiomatic blocks`() {
        val superDoc = superstructure(
            id = "scoring-coordinator",
            initialStateId = "STOWED",
            faultStateId = "STOWED",
        ) {
            val elevPort = port("subsystem.elevator", "elevator-target-height")
            val distPort = port("subsystem.vision", "vision-distance-meters")
            val lutId = lut("distance-to-height", inputUnit = "m", outputUnit = "m") {
                point(1.0, 0.35)
                point(3.0, 0.85)
            }
            state("STOWED") {
                targetDouble(elevPort, 0.0)
            }
            state("AIMING") {
                targetLut(elevPort, lutId, source = distPort)
            }
            onActionTransition("request-aim", from = "STOWED", to = "AIMING", actionKey = "superstructure.aim")
            onActionTransition("request-stow", from = "AIMING", to = "STOWED", actionKey = "superstructure.stow")
        }

        val superDsl = superDoc.toKotlinDsl()
        assertTrue(superDsl.contains("superstructure("))
        assertTrue(superDsl.contains("lut(\"distance-to-height\", inputUnit = \"m\", outputUnit = \"m\")"))
        assertTrue(superDsl.contains("targetLut(SuperstructureFieldReference(\"subsystem.elevator\", \"elevator-target-height\"), \"distance-to-height\""))
        assertTrue(superDsl.contains("onActionTransition(\"request-aim\", from = \"STOWED\", to = \"AIMING\", actionKey = \"superstructure.aim\")"))

        val driveDoc = ftcMecanumDrivetrain {
            geometry(
                wheelDiameterMeters = 0.096,
                trackWidthMeters = 0.34,
                wheelBaseMeters = 0.32,
                driveGearRatio = 19.2,
                maxLinearSpeedMetersPerSecond = 2.2,
                maxAngularSpeedRadiansPerSecond = 5.0,
            )
            tuningDouble(key = "drive.heading.kp", defaultValue = 1.8)
        }

        val driveDsl = driveDoc.toKotlinDsl()
        assertTrue(driveDsl.contains("ftcMecanumDrivetrain {"))
        assertTrue(driveDsl.contains("wheelDiameterMeters = 0.096"))
        assertTrue(driveDsl.contains("tuningDouble(key = \"drive.heading.kp\", defaultValue = 1.8)"))
    }

    @Test
    fun `autonomousCatalog auto-defaults first enabled entry when defaultEntryId is omitted`() {
        val cat = com.areslib.routine.autonomousCatalog("season-2026") {
            entry(id = "first-auto", routineId = "score-preload")
            entry(id = "second-auto", routineId = "park")
        }
        org.junit.jupiter.api.Assertions.assertEquals("first-auto", cat.defaultEntryId)
    }
}
