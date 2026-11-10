package org.firstinspires.ftc.teamcode.sdk

import com.areslib.ftc.hardware.CachedServo
import com.qualcomm.robotcore.hardware.Servo
import com.qualcomm.robotcore.hardware.ServoController
import com.qualcomm.robotcore.hardware.ServoImpl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito

/** Uses SDK 12's actual direction/range mapping, without a servo or Android device. */
class FtcSdk12ServoCacheIntegrationTest {
    private class Fixture {
        var physicalPosition = 0.0
        var writes = 0
        val controller: ServoController = Mockito.mock(ServoController::class.java)
        val servo: ServoImpl
        init {
            Mockito.doAnswer { invocation ->
                physicalPosition = invocation.getArgument<Double>(1)
                writes++
                null
            }.`when`(controller).setServoPosition(anyInt(), anyDouble())
            Mockito.`when`(controller.getServoPosition(anyInt())).thenAnswer { physicalPosition }
            servo = ServoImpl(controller, 0)
        }
    }

    @Test fun directionChangeReissuesIdenticalLogicalPosition() {
        val device = Fixture()
        val cached = CachedServo(device.servo)
        cached.position = 0.4
        cached.direction = Servo.Direction.REVERSE
        val remappedPosition = cached.position
        cached.position = 0.4
        cached.position = 0.4
        assertEquals(0.6, device.physicalPosition, 1e-9)
        assertEquals(0.6, remappedPosition, 1e-9)
        assertEquals(2, device.writes)
    }

    @Test fun rangeChangeReissuesIdenticalLogicalPosition() {
        val device = Fixture()
        val cached = CachedServo(device.servo)
        cached.position = 0.4
        cached.scaleRange(0.2, 0.8)
        val remappedPosition = cached.position
        cached.position = 0.4
        cached.position = 0.4
        assertEquals(0.44, device.physicalPosition, 1e-9)
        assertEquals(1.0 / 3.0, remappedPosition, 1e-9)
        assertEquals(2, device.writes)
    }
}
