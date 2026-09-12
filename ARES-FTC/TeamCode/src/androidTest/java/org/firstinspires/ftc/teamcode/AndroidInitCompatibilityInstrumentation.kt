package org.firstinspires.ftc.teamcode

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import com.areslib.logging.ARESDataLogger
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/** Runs the failing INIT file APIs on the real Android runtime without creating robot hardware. */
class AndroidInitCompatibilityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            verifyImuSdkLinkage()
            // Use only a unique test directory; never touch the robot's actual logs or tuning.
            val directory = Files.createTempDirectory(targetContext.cacheDir.toPath(), "ares-init-test-")
            check(Paths.get(directory.toString()).toAbsolutePath() == directory)
            val logger = ARESDataLogger("AndroidInitCompatibility", directory.toFile())
            try {
                logger.logFrame(mapOf("CompatibilityMarker" to "api25-init-ok"))
            } finally {
                logger.stop()
            }
            val completed = directory.toFile().listFiles().orEmpty().filter {
                it.name.endsWith(".csv") || it.name.endsWith(".csv.gz")
            }
            check(completed.size == 1) { "Logger did not finalize exactly one log: ${completed.size}" }
            val logFile = completed.single()
            check(Files.size(logFile.toPath()) > 0L)
            val contents = if (logFile.name.endsWith(".gz")) {
                GZIPInputStream(logFile.inputStream()).bufferedReader().use { it.readText() }
            } else logFile.readText()
            check(contents.contains("api25-init-ok")) { "Logger failed to persist the test frame" }
            check(directory.toFile().listFiles().orEmpty().none { it.name.endsWith(".active") })
            Files.delete(logFile.toPath())
            Files.delete(directory)
            result.putString("stream", "PASS: Android API ${android.os.Build.VERSION.SDK_INT}; real SDK IMU linkage and cached heading/rates; logger INIT, file lock, frame write, finalization, Path/Files operations\n")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("stream", "FAIL: ${Log.getStackTraceString(failure)}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verifyImuSdkLinkage() {
        // This proxy implements the real APK SDK interface, not the desktop ftc-mocks interface.
        // Constructing FtcImu catches incompatible IMU.Parameters descriptors and invented getters.
        val sdkImu = java.lang.reflect.Proxy.newProxyInstance(
            com.qualcomm.robotcore.hardware.IMU::class.java.classLoader,
            arrayOf(com.qualcomm.robotcore.hardware.IMU::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "initialize" -> true
                "getRobotYawPitchRollAngles" -> org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles(
                    org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.RADIANS, 0.75, 0.1, -0.2, 1L,
                )
                "getRobotAngularVelocity" -> org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity(
                    org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.RADIANS, 1f, 2f, 3f, 1L,
                )
                else -> null
            }
        } as com.qualcomm.robotcore.hardware.IMU
        val adapter = com.areslib.ftc.hardware.FtcImu(sdkImu)
        try {
            val inputs = com.areslib.hardware.sensor.ImuInputs()
            adapter.updateInputs(inputs)
            check(inputs.timestampMs > 0L && inputs.headingRadians == 0.75)
            check(inputs.yawVelocityRadPerSec == 3.0 && inputs.pitchVelocityRadPerSec == 2.0 && inputs.rollVelocityRadPerSec == 1.0)
        } finally {
            adapter.close()
        }
    }
}
