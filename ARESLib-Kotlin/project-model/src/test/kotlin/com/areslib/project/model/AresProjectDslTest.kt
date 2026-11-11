package com.areslib.project.model

import com.areslib.controls.*
import org.junit.jupiter.api.Assertions.assertEquals
import com.areslib.project.AresLeague
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.tuning.tuningComponent
import com.areslib.tuning.tuningProfile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AresProjectDslTest {

    @Test
    fun `aresProject assembles and validates complete FTC robot across all domains`() {
        val globalTuning = tuningComponent(uid = "robot.global", projectId = "ftc-2026-alpha") {
            val latency = double(
                key = "vision.maxLatencyMs",
                defaultValue = 120.0,
                unit = "ms",
                minimum = 10.0,
                maximum = 500.0,
            )
            assertTrue(latency.uid == "robot.global.vision-maxlatencyms")
        }

        val matchProfile = tuningProfile(profileId = "competition", projectId = "ftc-2026-alpha") {
            assignDouble("robot.global.vision-maxlatencyms", 95.0)
        }

        val project = aresProject("ftc-2026-alpha", AresLeague.FTC) {
            controllerProfile(reviewedProfile())
            identity(teamId = "23247", seasonId = "2026", robotId = "alpha", displayName = "Alpha")

            ftcMecanumDrivetrain {
                geometry(
                    wheelDiameterMeters = 0.096,
                    trackWidthMeters = 0.34,
                    wheelBaseMeters = 0.32,
                    driveGearRatio = 19.2,
                    maxLinearSpeedMetersPerSecond = 2.2,
                    maxAngularSpeedRadiansPerSecond = 5.0,
                )
            }

            subsystem("elevator", SubsystemPlatform.FTC) {
                val target = state.targetDouble("targetMeters", unit = "m", minimum = 0.0, maximum = 1.2)
                val pos = state.measurementDouble("positionMeters", unit = "m")
                val motor = hardware.motor("elevator") {
                    measurement(pos, SubsystemMeasurementSource.MOTOR_POSITION_NATIVE, scale = 0.0005)
                }
                control.positionPid("hold", motor, target, pos) { kP = 8.0 }
            }

            superstructure(
                id = "scoring-coordinator",
                initialStateId = "STOWED",
                faultStateId = "STOWED",
            ) {
                val elevTarget = port("elevator", "targetMeters")
                state("STOWED") {
                    targetDouble(elevTarget, 0.0)
                }
                state("HIGH") {
                    targetDouble(elevTarget, 1.05)
                }
                onActionTransition("to-high", from = "STOWED", to = "HIGH", actionKey = "superstructure.scoreHigh")
                onActionTransition("to-stow", from = "HIGH", to = "STOWED", actionKey = "superstructure.stow")
            }

            condition("elevator.ready", displayName = "Elevator Ready")

            routine("score-preload") {
                driveTo(xMeters = 0.8, yMeters = 0.2, headingDegrees = 90.0)
                action("superstructure.scoreHigh")
                waitUntil(condition = "elevator.ready", timeoutSeconds = 1.5)
                action("subsystem.elevator.set.targetMeters") {
                    number("value", 1.05)
                }
            }

            autonomousCatalog(defaultEntryId = "center-preload") {
                entry(id = "center-preload", routineId = "score-preload", xMeters = 0.0, yMeters = 0.0, headingDegrees = 90.0)
            }

            controlScheme("competition_default") {
                controller("driver", profile = "xbox-series", devicePort = 0) {
                    axis("left_stick_y").deadband(0.05).drive(DriveAxisKeys.VX)
                    button("a").onPress { action("superstructure.scoreHigh") }
                    button("b").onPress { routine("score-preload") }
                }
            }

            tuningComponent(globalTuning)
            tuningProfile(matchProfile)
        }

        assertTrue(project.isValid, project.formatValidationReport())
        val dsl = project.toKotlinDsl()
        assertTrue(dsl.contains("aresProject(\"ftc-2026-alpha\", AresLeague.FTC, projectRoot = \".\")"))
        assertTrue(dsl.contains("ftcMecanumDrivetrain"))
        assertTrue(dsl.contains("subsystem(\"elevator\", SubsystemPlatform.FTC)"))
        assertTrue(dsl.contains("superstructure("))
        assertTrue(dsl.contains("routine(\"score-preload\")"))
        assertTrue(dsl.contains("autonomousCatalog("))
        assertTrue(dsl.contains("controlScheme(\"competition_default\")"))
        assertTrue(dsl.contains("tuningProfile("))
    }

    @Test
    fun `aresProject catches cross-document errors and formats readable report`() {
        val broken = aresProject("ftc-2026-broken", AresLeague.FTC) {
            routine("bad-auto") {
                action("missing.subsystemAction")
            }
            controlScheme("teleop") {
                controller("driver", profile = "xbox-series", devicePort = 0) {
                    button("a").onPress { routine("nonexistent-routine") }
                }
            }
        }

        assertFalse(broken.isValid)
        val report = broken.formatValidationReport()
        assertTrue(report.contains("validation report"))
        assertTrue(report.contains("missing.subsystemAction"))
        assertTrue(report.contains("nonexistent-routine"))
    }

    @Test
    fun `missing profiles fail closed and explicit mappings do not depend on declaration order`() {
        fun project(order: List<String>, explicit: Boolean) = aresProject("controls-test", AresLeague.FTC) {
            action("test.trigger")
            if (explicit) controllerProfile(reviewedProfile())
            controlScheme("teleop") { controller("driver", "xbox-series", 0) {
                order.forEach { button(it).onPress { action("test.trigger") } }
            } }
        }
        for (order in listOf(listOf("a", "b"), listOf("b", "a"))) {
            val missing = project(order, false)
            assertFalse(missing.isValid)
            assertTrue(missing.raw.controllerProfiles.isEmpty())
            assertTrue(missing.formatValidationReport().contains("xbox-series"))
        }
        val first = project(listOf("a", "b"), true)
        val second = project(listOf("b", "a"), true)
        assertTrue(first.isValid, first.formatValidationReport())
        assertTrue(second.isValid, second.formatValidationReport())
        assertEquals(first.raw.controllerProfiles, second.raw.controllerProfiles)
        assertEquals(reviewedProfile(), first.raw.controllerProfiles.single())
    }

    private fun reviewedProfile() = ControllerProfileDocument(documentId = "xbox-series", displayName = "Reviewed controller", controls = listOf(
        ControllerControlDocument("a", "A", ControllerControlTypeDocument.BUTTON, anchor = ControllerAnchorDocument(0.5, 0.5),
            mappings = listOf(ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = 0), ControllerInputMappingDocument(ControllerInputPlatform.FRC, buttonIndex = 4))),
        ControllerControlDocument("b", "B", ControllerControlTypeDocument.BUTTON, anchor = ControllerAnchorDocument(0.6, 0.5),
            mappings = listOf(ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = 1), ControllerInputMappingDocument(ControllerInputPlatform.FRC, buttonIndex = 5))),
        ControllerControlDocument("left_stick_y", "Left Y", ControllerControlTypeDocument.AXIS, anchor = ControllerAnchorDocument(0.2, 0.5),
            mappings = listOf(ControllerInputMappingDocument(ControllerInputPlatform.FTC, axisIndex = 1)))
    ))
}
