package com.areslib.codegen

import com.areslib.controls.ControllerInputPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FtcStarterContractMigrationTest {
    @TempDir
    lateinit var projectRoot: Path

    @Test
    fun `known unedited legacy contract migrates to document tolerant invariants`() {
        val path = contractPath()
        Files.createDirectories(path.parent)
        Files.writeString(path, LEGACY_SOURCE)

        FtcStarterContractMigration.reconcile(projectRoot, ControllerInputPlatform.FTC, checkOnly = false)

        assertEquals(FtcStarterContractMigration.currentSource, Files.readString(path))
        assertTrue(Files.readString(path).contains("setOf(\"WHEEL_ENCODERS_IMU\", \"PINPOINT\")"))
    }

    @Test
    fun `check mode reports known legacy contract without changing it`() {
        val path = contractPath()
        Files.createDirectories(path.parent)
        Files.writeString(path, LEGACY_SOURCE)

        val failure = assertThrows<IllegalArgumentException> {
            FtcStarterContractMigration.reconcile(projectRoot, ControllerInputPlatform.FTC, checkOnly = true)
        }

        assertTrue(failure.message.orEmpty().contains("Run generateAresProject"))
        assertEquals(LEGACY_SOURCE, Files.readString(path))
    }

    @Test
    fun `edited generated starter and non FTC projects remain untouched`() {
        val path = contractPath()
        Files.createDirectories(path.parent)
        val edited = LEGACY_SOURCE + "// mentor customization\n"
        Files.writeString(path, edited)

        FtcStarterContractMigration.reconcile(projectRoot, ControllerInputPlatform.FTC, checkOnly = false)
        assertEquals(edited, Files.readString(path))

        Files.writeString(path, LEGACY_SOURCE)
        FtcStarterContractMigration.reconcile(projectRoot, ControllerInputPlatform.FRC, checkOnly = false)
        assertEquals(LEGACY_SOURCE, Files.readString(path))
    }

    private fun contractPath(): Path = projectRoot.resolve(
        "TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/StarterProjectContractTest.kt",
    )

    private companion object {
        val LEGACY_SOURCE: String = """
            // ARES OWNERSHIP: GENERATED STARTER
            package org.firstinspires.ftc.teamcode

            import org.firstinspires.ftc.teamcode.generated.GeneratedAresProject
            import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig
            import org.junit.Assert.assertEquals
            import org.junit.Assert.assertFalse
            import org.junit.Assert.assertTrue
            import org.junit.Test

            class StarterProjectContractTest {
                @Test
                fun genericProjectHasOnlyTheSafeStarterActionAndNoAutonomousRoutine() {
                    assertEquals(setOf("drivetrain.recoverNeutral"), GeneratedAresProject.knownActionKeys)
                    assertTrue(GeneratedAresProject.routines.isEmpty())
                    assertTrue(GeneratedAresProject.autonomousEntries.isEmpty())
                }

                @Test
                fun genericDrivebaseUsesFourNamedMotorsAndWheelImuLocalization() {
                    assertEquals("fl", GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FL.HARDWARE_ID)
                    assertEquals("fr", GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FR.HARDWARE_ID)
                    assertEquals("rl", GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RL.HARDWARE_ID)
                    assertEquals("rr", GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RR.HARDWARE_ID)
                    assertEquals("imu", GeneratedAresDrivebaseConfig.Components.FTC_LOCALIZATION_IMU.HARDWARE_ID)
                    assertEquals("WHEEL_ENCODERS_IMU", GeneratedAresDrivebaseConfig.Localization.PRIMARY_ODOMETRY.KIND)
                    assertTrue(GeneratedAresDrivebaseConfig.Components.FTC_LOCALIZATION_IMU.REQUIRED)
                    assertFalse(GeneratedAresDrivebaseConfig.Localization.PRIMARY_ODOMETRY.COMPONENT_UIDS.isEmpty())
                }
            }
        """.trimIndent() + "\n"
    }
}
