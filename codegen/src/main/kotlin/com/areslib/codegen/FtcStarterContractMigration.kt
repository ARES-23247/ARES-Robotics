package com.areslib.codegen

import com.areslib.controls.ControllerInputPlatform
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Migrates only byte-for-byte known obsolete FTC starter contracts.
 *
 * Early starter releases copied a test into each new project that asserted mutable defaults such
 * as the exact action set and wheel/IMU localization. Legitimate GUI edits therefore made a valid
 * project fail its build. The path is marked GENERATED STARTER, but this migration still refuses
 * to touch an edited or unknown file: only reviewed legacy SHA-256 values are eligible.
 */
internal object FtcStarterContractMigration {
    private const val RELATIVE_PATH =
        "TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/StarterProjectContractTest.kt"

    private val recognizedLegacyHashes = setOf(
        "bf022f03d112c36a184609d4ebb90d25ddcc21bbd75d04b867e9a1b2a94cc0bd",
        "f82e20109fae8f5ffa650557f416cbc152cdc9f07e07133468ab68914e25f2a3",
    )

    internal val currentSource: String = """
        // ARES OWNERSHIP: GENERATED STARTER
        package org.firstinspires.ftc.teamcode

        import org.firstinspires.ftc.teamcode.generated.GeneratedAresProject
        import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig
        import org.junit.Assert.assertTrue
        import org.junit.Test

        class StarterProjectContractTest {
            @Test
            fun generatedProjectAlwaysKeepsTheExplicitNeutralRecoveryPath() {
                assertTrue("drivetrain.recoverNeutral" in GeneratedAresProject.knownActionKeys)
                assertTrue(GeneratedAresProject.knownActionKeys.all(String::isNotBlank))
            }

            @Test
            fun generatedMecanumDrivebaseKeepsFourDistinctMotorsAndSupportedLocalization() {
                val motorHardwareIds = listOf(
                    GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FL.HARDWARE_ID,
                    GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FR.HARDWARE_ID,
                    GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RL.HARDWARE_ID,
                    GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RR.HARDWARE_ID,
                )
                assertTrue(motorHardwareIds.all(String::isNotBlank))
                assertTrue(motorHardwareIds.distinct().size == 4)
                assertTrue(
                    GeneratedAresDrivebaseConfig.Localization.PRIMARY_ODOMETRY.KIND in
                        setOf("WHEEL_ENCODERS_IMU", "PINPOINT"),
                )
                assertTrue(GeneratedAresDrivebaseConfig.Localization.PRIMARY_ODOMETRY.COMPONENT_UIDS.isNotEmpty())
            }
        }
    """.trimIndent() + "\n"

    fun reconcile(projectRoot: Path, platform: ControllerInputPlatform?, checkOnly: Boolean) {
        if (platform != ControllerInputPlatform.FTC) return
        val path = projectRoot.resolve(RELATIVE_PATH).normalize()
        require(path.startsWith(projectRoot)) { "FTC starter contract path escaped the selected project" }
        if (!Files.isRegularFile(path)) return

        val current = normalize(Files.readString(path))
        if (current == currentSource) return
        if (sha256(current) !in recognizedLegacyHashes) return
        require(!checkOnly) {
            "The generated FTC starter contract is obsolete at $path. Run generateAresProject to migrate the known unedited starter test."
        }
        writeAtomically(path, currentSource)
    }

    private fun normalize(source: String): String = source.replace("\r\n", "\n").trimEnd() + "\n"

    private fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun writeAtomically(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, content)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
