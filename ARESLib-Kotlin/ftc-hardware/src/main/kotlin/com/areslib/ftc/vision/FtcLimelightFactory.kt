package com.areslib.ftc.vision

import com.areslib.hardware.vision.CompositeVisionIO
import com.areslib.hardware.vision.VisionIO
import com.qualcomm.hardware.limelightvision.Limelight3A
import com.qualcomm.robotcore.hardware.HardwareMap

/**
 * Builds one or more FTC Limelight IOs with transactional ownership.
 *
 * Each [FtcLimelightIO] starts its SDK camera during construction. If any later lookup or wrapper
 * construction fails, every earlier child is closed in reverse order before the original failure
 * escapes. Both public FTC hardware initialization paths delegate here so their rollback semantics
 * cannot drift.
 */
internal object FtcLimelightFactory {
    fun create(
        hardwareMap: HardwareMap,
        deviceNames: List<String>,
        ioFactory: (Limelight3A, String) -> VisionIO = { camera, name ->
            FtcLimelightIO(camera, sourceId = name)
        }
    ): VisionIO? {
        if (deviceNames.isEmpty()) return null
        val initialized = ArrayList<VisionIO>(deviceNames.size)
        try {
            for (name in deviceNames) {
                val camera = hardwareMap.get(Limelight3A::class.java, name)
                initialized.add(ioFactory(camera, name))
            }
            return if (initialized.size == 1) initialized[0] else CompositeVisionIO(initialized)
        } catch (failure: Throwable) {
            for (index in initialized.lastIndex downTo 0) {
                try {
                    (initialized[index] as? AutoCloseable)?.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
            }
            throw failure
        }
    }
}
