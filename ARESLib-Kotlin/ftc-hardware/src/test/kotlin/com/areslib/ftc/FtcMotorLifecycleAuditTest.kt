package com.areslib.ftc

import com.areslib.e2e.tier1.hardware.MockFtcMotorEx
import com.areslib.e2e.tier1.hardware.MockFtcCRServo
import com.areslib.ftc.core.FtcHardwareInitializer
import com.areslib.ftc.hardware.FtcCRServo
import com.areslib.ftc.hardware.rev.RevBulkDataReader
import com.areslib.ftc.hardware.rev.RevMotorController
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class FtcMotorLifecycleAuditTest {
    @AfterTest fun cleanup() {
        RevBulkDataReader.unregisterAll()
        RobotClock.useSystemTime()
    }

    @Test fun `motor zero bypasses tolerance for direct and scaled neutral commands`() {
        for (scaleStop in listOf(false, true)) {
            val hardware = MockFtcMotorEx()
            val motor = RevMotorController(hardware)
            try {
                motor.power = 0.0005
                assertEquals(0.0005, hardware.power)
                if (scaleStop) motor.powerScale = 0.0 else motor.power = 0.0
                assertEquals(0.0, hardware.power)
            } finally { motor.close() }
        }
    }

    @Test fun `CR servo zero bypasses tolerance for direct and scaled neutral commands`() {
        for (scaleStop in listOf(false, true)) {
            val hardware = MockFtcCRServo()
            val servo = FtcCRServo(hardware)
            try {
                servo.power = -0.0005
                assertEquals(-0.0005, hardware.power)
                if (scaleStop) servo.powerScale = 0.0 else servo.power = 0.0
                assertEquals(0.0, hardware.power, 0.0) // Signed zero is electrically neutral.
            } finally { servo.close() }
        }
    }

    @Test fun `motor close neutralizes and later commands cannot reactivate hardware`() {
        val hardware = MockFtcMotorEx()
        val motor = RevMotorController(hardware)
        try {
            motor.power = 0.4
            motor.close()
            assertEquals(0.0, hardware.power)
            motor.power = 0.8
            motor.powerScale = 0.5
            assertEquals(0.0, hardware.power)
            assertEquals(0.0, motor.power)
        } finally { hardware.power = 0.0; motor.close() }
    }

    @Test fun `CR servo participates in resource shutdown and rejects later commands`() {
        val hardware = MockFtcCRServo()
        val servo = FtcCRServo(hardware)
        try {
            servo.power = 0.4
            val resource = assertIs<AutoCloseable>(servo as Any)
            resource.close()
            assertEquals(0.0, hardware.power)
            servo.power = 0.7
            servo.powerScale = 0.5
            assertEquals(0.0, hardware.power)
            assertEquals(0.0, servo.power)
        } finally { hardware.power = 0.0 }
    }

    @Test fun `closing the last motor terminates its reader`() {
        val hardware = ObservedMotor()
        val motor = RevMotorController(hardware)
        try {
            assertTrue(hardware.sampled.await(2, TimeUnit.SECONDS))
            val reader = assertNotNull(hardware.reader.get())
            motor.close()
            reader.join(1500)
            assertFalse(reader.isAlive, "An empty motor reader must stop")
        } finally { motor.close() }
    }

    @Test fun `duplicate registration cannot keep a closed motor in the reader`() {
        val hardware = ObservedMotor()
        val motor = RevMotorController(hardware)
        try {
            assertTrue(hardware.sampled.await(2, TimeUnit.SECONDS))
            RevBulkDataReader.registerMotor(motor)
            val reader = assertNotNull(hardware.reader.get())
            motor.close()
            reader.join(1500)
            assertFalse(reader.isAlive)
        } finally { motor.close() }
    }

    @Test fun `sensor initializer shutdown does not unregister unrelated motors`() {
        val hardware = ObservedMotor()
        val motor = RevMotorController(hardware)
        val initializer = FtcHardwareInitializer(object : HardwareMap() {
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T = error("No sensors")
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        })
        try {
            assertTrue(hardware.sampled.await(2, TimeUnit.SECONDS))
            initializer.close()
            val next = CountDownLatch(1)
            hardware.nextSample.set(next)
            assertTrue(next.await(2, TimeUnit.SECONDS), "Unrelated motor must remain registered")
        } finally { initializer.close(); motor.close() }
    }

    @Test fun `an in-flight current read cannot republish feedback after motor close`() {
        RobotClock.useMockTime(1000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        val reader = AtomicReference<Thread?>()
        val hardware = object : DcMotorEx by MockFtcMotorEx() {
            override fun getCurrent(unit: CurrentUnit): Double {
                if (Thread.currentThread() !== caller) {
                    reader.set(Thread.currentThread())
                    entered.countDown()
                    // Model an SDK read that outlives an interrupt; release belongs to the fixture.
                    while (true) {
                        try { if (release.await(5, TimeUnit.SECONDS)) break else error("Fixture timed out") }
                        catch (_: InterruptedException) { }
                    }
                }
                return 3.0
            }
        }
        val motor = RevMotorController(hardware)
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val closer = Thread({
            try { motor.close() } catch (error: Throwable) { failure.set(error) }
            finally { returned.countDown() }
        }, "audit-owned-motor-close")
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            closer.start()
            assertTrue(returned.await(3, TimeUnit.SECONDS), "Close must bound an unresponsive SDK read")
            release.countDown()
            // A foreground sample must also remain inhibited after close.
            motor.pollCurrentSync()
            assertTrue(motor.currentAmps.isNaN())
            failure.get()?.let { throw it }
        } finally {
            release.countDown()
            closer.join(3000)
            motor.close()
            reader.get()?.join(3000)
            check(!closer.isAlive)
            check(reader.get()?.isAlive != true)
        }
    }

    @Test fun `a detached reader cannot adopt newly registered motors after an SDK read returns`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldReader = AtomicReference<Thread?>()
        val old = RevMotorController(object : DcMotorEx by MockFtcMotorEx() {
            override fun getCurrent(unit: CurrentUnit): Double {
                oldReader.set(Thread.currentThread())
                entered.countDown()
                while (true) {
                    try { if (release.await(5, TimeUnit.SECONDS)) break else error("Fixture timed out") }
                    catch (_: InterruptedException) { }
                }
                return 1.0
            }
        })
        var replacement: RevMotorController? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            RevBulkDataReader.unregisterAll() // Bounded join; old SDK call is still blocked.
            val next = ObservedMotor()
            replacement = RevMotorController(next)
            assertTrue(next.sampled.await(2, TimeUnit.SECONDS))
            val retired = assertNotNull(oldReader.get())
            assertNotSame(retired, next.reader.get())
            release.countDown()
            retired.join(2000)
            assertFalse(retired.isAlive, "A retired reader must not adopt the replacement registry")
            val sampledAgain = CountDownLatch(1)
            next.nextSample.set(sampledAgain)
            assertTrue(sampledAgain.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            old.close()
            replacement?.close()
            RevBulkDataReader.unregisterAll()
            oldReader.get()?.join(3000)
            check(oldReader.get()?.isAlive != true)
        }
    }

    @Test fun `ordinary motor write caching remains effective while neutral reaches hardware`() {
        val backing = MockFtcMotorEx()
        var writes = 0
        val motor = RevMotorController(object : DcMotorEx by backing {
            override var power: Double
                get() = backing.power
                set(value) { writes++; backing.power = value }
        })
        try {
            motor.power = 0.3
            motor.power = 0.3004
            assertEquals(1, writes)
            assertEquals(0.3, backing.power)
            motor.power = 0.0
            motor.power = 0.0
            assertEquals(2, writes)
            assertEquals(0.0, backing.power)
        } finally { motor.close() }
    }

    @Test fun `a later registration restarts a reader that was interrupted independently`() {
        val firstHardware = ObservedMotor()
        val first = RevMotorController(firstHardware)
        var second: RevMotorController? = null
        try {
            assertTrue(firstHardware.sampled.await(2, TimeUnit.SECONDS))
            val stopped = assertNotNull(firstHardware.reader.get())
            stopped.interrupt()
            stopped.join(2000)
            assertFalse(stopped.isAlive)
            val secondHardware = ObservedMotor()
            second = RevMotorController(secondHardware)
            assertTrue(secondHardware.sampled.await(2, TimeUnit.SECONDS))
            assertNotSame(stopped, secondHardware.reader.get())
        } finally { first.close(); second?.close() }
    }

    @Test fun `stall detection accepts timestamp zero and trips strictly after 500 milliseconds`() {
        RobotClock.useMockTime(0)
        val hardware = MockFtcMotorEx()
        val motor = RevMotorController(hardware)
        try {
            motor.power = 0.8
            RobotClock.useMockTime(500)
            motor.power = 0.8
            assertEquals(0.8, hardware.power)
            RobotClock.useMockTime(501)
            motor.power = 0.8
            assertEquals(0.0, hardware.power)
        } finally { motor.close() }
    }

    @Test fun `invalid stall elapsed time neutralizes until healthy input permits recovery`() {
        for ((start, end) in listOf(1000L to 900L, -1000L to Long.MAX_VALUE)) {
            RobotClock.useMockTime(start)
            val hardware = MockFtcMotorEx()
            val motor = RevMotorController(hardware)
            try {
                motor.power = 0.8
                RobotClock.useMockTime(end)
                motor.power = 0.8
                assertEquals(0.0, hardware.power)
                motor.power = 0.0
                motor.power = 0.8
                assertEquals(0.8, hardware.power)
            } finally { motor.close() }
        }
    }

    private class ObservedMotor : DcMotorEx by MockFtcMotorEx() {
        val sampled = CountDownLatch(1)
        val reader = AtomicReference<Thread?>()
        val nextSample = AtomicReference<CountDownLatch?>()
        override fun getCurrent(unit: CurrentUnit): Double {
            reader.set(Thread.currentThread())
            sampled.countDown()
            nextSample.getAndSet(null)?.countDown()
            return 2.0
        }
    }
}
