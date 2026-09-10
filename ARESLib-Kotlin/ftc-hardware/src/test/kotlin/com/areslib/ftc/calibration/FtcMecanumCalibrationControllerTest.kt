package com.areslib.ftc.calibration

import com.areslib.Store
import com.areslib.ftc.drivetrain.MecanumHardwareIO
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.hardware.HardwareRegistry
import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtcMecanumCalibrationControllerTest {
    @Test
    fun `switching from drive to flywheel characterization neutralizes the drivetrain`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.step(1000L)
        assertMotorPowers(fixture.io, 0.25, 0.25, 0.25, 0.25)
        clientWrite(fixture.server, fixture.client, COMMAND_PUBLISHER, "START_FLYWHEEL_DYNAMIC")
        fixture.step(1020L)
        assertEquals(6.0, fixture.flywheel.voltage)
        assertMotorPowers(fixture.io, 0.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun `flywheel characterization stops when global power is derated`() {
        val fixture = ArmedSysId("FLYWHEEL")
        fixture.step(1000L)
        assertEquals(6.0, fixture.flywheel.voltage)
        fixture.io.flIO.powerScale = 0.5
        fixture.step(1020L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertEquals(0.0, fixture.flywheel.voltage)
        assertEquals(0, fixture.publish(1020L).size)
    }

    @Test
    fun `drive characterization refuses voltage above the available supply`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.batteryVoltage = 2.0
        fixture.step(1000L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertMotorPowers(fixture.io, 0.0, 0.0, 0.0, 0.0)
        assertEquals(0, fixture.publish(1000L).size)
    }

    @Test
    fun `flywheel characterization refuses voltage above the available supply`() {
        val fixture = ArmedSysId("FLYWHEEL")
        fixture.batteryVoltage = 5.0
        fixture.step(1000L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertEquals(0.0, fixture.flywheel.voltage)
        assertEquals(0, fixture.publish(1000L).size)
    }

    @Test
    fun `unsupported mechanism commands cannot characterize the attached flywheel`() {
        val fixture = ArmedSysId("ARM")
        fixture.step(1000L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertEquals(0.0, fixture.flywheel.voltage)
        assertMotorPowers(fixture.io, 0.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun `linear sysid samples measured robot forward velocity instead of commanded field intent`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.heading = Math.PI / 2.0
        fixture.measuredY = 2.0
        fixture.step(1000L)
        fixture.step(1100L)
        assertEquals(0.2, fixture.controller.sysIdManager.accumulatedPosition, 1e-12)
        assertEquals(0.0, fixture.controller.sysIdManager.calculatedAcceleration, 1e-12)
        val sample = fixture.publish(1105L)
        assertEquals(1100.0, sample[0])
        assertEquals(2.0, sample[3], 1e-12)
    }

    @Test
    fun `angular sysid logs signed measured motion during reverse travel`() {
        val fixture = ArmedSysId("ANGULAR")
        fixture.measuredAngular = -2.0
        fixture.step(1000L)
        fixture.step(1100L)
        val sample = fixture.publish(1100L)
        assertEquals(-0.2, sample[2], 1e-12)
        assertEquals(-2.0, sample[3], 1e-12)
    }

    @Test
    fun `linear reverse samples retain a negative displacement`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.measuredX = -1.0
        fixture.step(1000L)
        fixture.step(1100L)
        val sample = fixture.publish(1100L)
        assertEquals(-0.1, sample[2], 1e-12)
        assertEquals(-1.0, sample[3], 1e-12)
    }

    @Test
    fun `drive sysid stops on stale or explicitly invalid measured motion`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.step(1000L)
        fixture.refreshMotion = false
        fixture.step(1100L)
        assertTrue(fixture.controller.sysIdManager.isActive())
        fixture.step(1101L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertMotorPowers(fixture.io, 0.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun `invalid measured motion cannot start drive output`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.motionValid = false
        fixture.step(1000L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertMotorPowers(fixture.io, 0.0, 0.0, 0.0, 0.0)
        assertEquals(0, fixture.publish(1000L).size)
    }

    @Test
    fun `invalid supply voltage cannot energize characterization`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.batteryVoltage = Double.NaN
        fixture.step(1000L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertMotorPowers(fixture.io, 0.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun `negative supply voltage cannot reverse characterization output`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.batteryVoltage = -12.0
        fixture.step(1000L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertMotorPowers(fixture.io, 0.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun `drive characterization stops when motor output is derated`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.step(1000L)
        fixture.io.flIO.powerScale = 0.5
        fixture.step(1020L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertMotorPowers(fixture.io, 0.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun `logging reuses the sampled custom velocity without reinvoking its provider`() {
        val fixture = ArmedSysId("FLYWHEEL")
        var reads = 0
        fixture.controller.customSysIdVelocityProvider = { reads++; 10.0 * reads }
        fixture.step(1000L)
        val first = fixture.publish(1005L).copyOf()
        val second = fixture.publish(1010L)
        assertEquals(1, reads)
        assertEquals(10.0, first[3])
        kotlin.test.assertContentEquals(first, second)
    }

    @Test
    fun `drive sysid observes each cached motor current without polling output hardware`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.motors.forEach { it.measuredCurrent = 15.0 }
        fixture.step(1000L)
        assertMotorPowers(fixture.io, 0.25, 0.25, 0.25, 0.25)
        // Limit is per motor: 60A combined must not trip a 40A motor limit.
        fixture.step(1200L)
        assertTrue(fixture.controller.sysIdManager.isActive())
        fixture.motors[2].measuredCurrent = 50.0
        fixture.step(1220L)
        fixture.step(1420L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertMotorPowers(fixture.io, 0.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun `drive sysid stops when a motor current sample is invalid`() {
        val fixture = ArmedSysId("LINEAR")
        fixture.step(1000L)
        assertTrue(fixture.controller.sysIdManager.isActive())
        fixture.motors[1].measuredCurrent = Double.NaN
        fixture.step(1020L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertMotorPowers(fixture.io, 0.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun `flywheel sysid uses current validity and stall feedback`() {
        val fixture = ArmedSysId("FLYWHEEL")
        fixture.flywheel.measuredCurrent = 50.0
        fixture.step(1000L)
        assertEquals(6.0, fixture.flywheel.voltage)
        fixture.step(1200L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertEquals(0.0, fixture.flywheel.voltage)
        assertEquals(2, fixture.flywheel.currentReads)
    }

    @Test
    fun `flywheel sysid refuses stale finite current`() {
        val fixture = ArmedSysId("FLYWHEEL")
        fixture.step(1000L)
        fixture.flywheel.currentFresh = false
        fixture.step(1020L)
        assertFalse(fixture.controller.sysIdManager.isActive())
        assertEquals(0.0, fixture.flywheel.voltage)
        assertEquals(2, fixture.flywheel.currentReads)
    }

    private inner class ArmedSysId(mechanism: String) {
        val motors = Array(4) { CalibrationMotor() }
        val flywheel = CurrentFlywheel()
        val store = Store(com.areslib.state.RobotState(drive = com.areslib.state.DriveState(
            xVelocityMetersPerSecond = 99.0, angularVelocityRadiansPerSecond = 99.0)))
        val telemetry = FtcTelemetryManager(store, hardwareRegistry)
        val io = MecanumHardwareIO(motorHardwareMap(motors), hardwareRegistry)
        val controller = FtcMecanumCalibrationController().apply { flywheelIO = flywheel }
        val server: NT4Server
        val client = webSocketProxy()
        var sequence = 12.0
        var heading = 0.0
        var measuredX = 0.0
        var measuredY = 0.0
        var measuredAngular = 0.0
        var refreshMotion = true
        var motionValid = true
        var batteryVoltage = 12.0

        init {
            RobotClock.useMockTime(1000L)
            server = NT4Instance.defaultInstance.startServer("127.0.0.1", 0)
            server.onOpen(client, proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) })
            publishString(server, client, COMMAND_TOPIC, COMMAND_PUBLISHER)
            publishString(server, client, ENABLE_TOKEN_TOPIC, TOKEN_PUBLISHER)
            publishDouble(server, client, ENABLE_LEASE_TOPIC, LEASE_PUBLISHER)
            clientWrite(server, client, COMMAND_PUBLISHER, STOP_COMMAND)
            clientWrite(server, client, TOKEN_PUBLISHER, "retained-token")
            clientWriteDouble(server, client, LEASE_PUBLISHER, 10.0)
            controller.enableMode(telemetry, io)
            clientWrite(server, client, TOKEN_PUBLISHER, "new-token")
            clientWriteDouble(server, client, LEASE_PUBLISHER, 11.0)
            controller.updateHardwareInputs(store, telemetry, io, null) {}
            controller.updateSubsystems(store, 12.0, io, telemetry) {}
            assertTrue(controller.networkArmed)
            clientWrite(server, client, COMMAND_PUBLISHER, "START_${mechanism}_DYNAMIC")
        }

        fun step(timestamp: Long) {
            RobotClock.useMockTime(timestamp)
            if (refreshMotion) store.dispatch(com.areslib.action.RobotAction.PoseUpdate(
                0.0, 0.0, heading, timestamp,
                xVelocityMetersPerSecond = measuredX, yVelocityMetersPerSecond = measuredY,
                angularVelocityRadiansPerSecond = measuredAngular, motionMeasurementsValid = motionValid,
                isExternalEstimate = true))
            io.flIO.pollSync(); io.frIO.pollSync(); io.rlIO.pollSync(); io.rrIO.pollSync()
            val reads = motors.sumOf { it.currentReads }
            clientWriteDouble(server, client, LEASE_PUBLISHER, sequence++)
            controller.updateHardwareInputs(store, telemetry, io, null) {}
            controller.updateSubsystems(store, batteryVoltage, io, telemetry) {}
            assertEquals(reads, motors.sumOf { it.currentReads }, "Output path must only consume cached current")
        }

        fun publish(timestamp: Long): DoubleArray {
            controller.publishRobotTelemetry(timestamp, store, telemetry, io,
                com.areslib.ftc.vision.FtcVisionTracker(store, null, null), 2000.0, 2000.0)
            return NT4Server.getDoubleArray("SysId/Data", doubleArrayOf())
        }
    }

    private class CurrentFlywheel : com.areslib.hardware.actuator.FlywheelIO {
        var voltage = 0.0
        var measuredCurrent = 5.0
        var currentFresh = true
        var currentReads = 0
        override val velocityRpm = 1000.0
        override val velocityValid = true
        override val currentAmps: Double get() { currentReads++; return measuredCurrent }
        override fun isCurrentReadingValid(readingAmps: Double) =
            currentFresh && readingAmps.isFinite() && readingAmps >= 0.0
        override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) = Unit
        override fun setAppliedVoltage(volts: Double) { voltage = volts }
    }

    private lateinit var hardwareRegistry: HardwareRegistry

    @BeforeEach
    fun setUp() {
        hardwareRegistry = HardwareRegistry()
    }

    @AfterEach
    fun cleanUp() {
        hardwareRegistry.closeAll()
        NT4Instance.defaultInstance.closeServer()
        RobotClock.useSystemTime()
    }

    @Test
    fun `enabled but unarmed relinquishes drivetrain after one-shot neutral`() {
        val telemetry = FtcTelemetryManager(Store(), hardwareRegistry)
        val mecanumIO = MecanumHardwareIO(motorHardwareMap(), hardwareRegistry)
        val controller = FtcMecanumCalibrationController()

        mecanumIO.setMotorPowers(0.6, -0.5, 0.4, -0.3)
        controller.enableMode(telemetry, mecanumIO)
        assertMotorPowers(mecanumIO, 0.0, 0.0, 0.0, 0.0)

        // This represents the tuning OpMode's manual command after calibration's enable boundary.
        mecanumIO.setMotorPowers(0.6, -0.5, 0.4, -0.3)
        controller.updateHardwareInputs(Store(), telemetry, mecanumIO, pinpointIO = null) {}
        assertFalse(controller.updateSubsystems(Store(), 12.0, mecanumIO, telemetry) {})
        assertMotorPowers(mecanumIO, 0.6, -0.5, 0.4, -0.3)

        controller.disableMode(telemetry, mecanumIO)
        assertMotorPowers(mecanumIO, 0.0, 0.0, 0.0, 0.0)
        assertFalse(controller.updateSubsystems(Store(), 12.0, mecanumIO, telemetry) {})
    }

    @Test
    fun `automatic completion leaves command client owned so another routine can start`() {
        RobotClock.useMockTime(1_000L)
        val server = NT4Instance.defaultInstance.startServer("127.0.0.1", 0)
        val store = Store()
        val telemetry = FtcTelemetryManager(store, hardwareRegistry)
        val mecanumIO = MecanumHardwareIO(motorHardwareMap(), hardwareRegistry)
        val controller = FtcMecanumCalibrationController()
        val client = webSocketProxy()
        val handshake = proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) }
        server.onOpen(client, handshake)

        publishString(server, client, COMMAND_TOPIC, COMMAND_PUBLISHER)
        publishString(server, client, ENABLE_TOKEN_TOPIC, TOKEN_PUBLISHER)
        publishDouble(server, client, ENABLE_LEASE_TOPIC, LEASE_PUBLISHER)
        clientWrite(server, client, COMMAND_PUBLISHER, STOP_COMMAND)
        clientWrite(server, client, TOKEN_PUBLISHER, "retained-token")
        clientWriteDouble(server, client, LEASE_PUBLISHER, 10.0)

        controller.enableMode(telemetry, mecanumIO)
        clientWrite(server, client, TOKEN_PUBLISHER, "fresh-session-token")
        clientWriteDouble(server, client, LEASE_PUBLISHER, 11.0)
        controller.updateHardwareInputs(store, telemetry, mecanumIO, pinpointIO = null) {}
        assertTrue(controller.networkArmed)
        assertTrue(controller.neutralOutputHoldActive)
        assertTrue(
            controller.updateSubsystems(store, 12.0, mecanumIO, telemetry) {},
            "fresh arming neutral must own its current output pass"
        )
        assertTrue(
            controller.updateSubsystems(store, 12.0, mecanumIO, telemetry) {},
            "armed STOP must retain ownership so stale Redux drive intent cannot be reapplied"
        )
        assertMotorPowers(mecanumIO, 0.0, 0.0, 0.0, 0.0)

        clientWrite(server, client, COMMAND_PUBLISHER, "START_LINEAR_DRIVE")
        clientWriteDouble(server, client, LEASE_PUBLISHER, 12.0)
        controller.updateHardwareInputs(store, telemetry, mecanumIO, pinpointIO = null) {}
        assertEquals("LINEAR_DRIVE", controller.activeCalibration)
        assertFalse(controller.neutralOutputHoldActive)

        RobotClock.useMockTime(4_001L)
        assertTrue(controller.updateSubsystems(store, 12.0, mecanumIO, telemetry) {})

        assertEquals("NONE", controller.activeCalibration)
        assertEquals(
            "START_LINEAR_DRIVE",
            telemetry.nt4.getString(COMMAND_TOPIC, ""),
            "completion must not overwrite or claim the dashboard-owned command topic"
        )
        assertEquals(
            "NONE",
            telemetry.nt4.getString(STATUS_TOPIC, "")
        )

        // The verifier acknowledges completion with STOP, then starts the next routine using the
        // same armed client session. Both writes must still reach the controller.
        clientWrite(server, client, COMMAND_PUBLISHER, STOP_COMMAND)
        clientWriteDouble(server, client, LEASE_PUBLISHER, 13.0)
        controller.updateHardwareInputs(store, telemetry, mecanumIO, pinpointIO = null) {}
        assertTrue(controller.neutralOutputHoldActive)
        clientWrite(server, client, COMMAND_PUBLISHER, "START_TRACK_WIDTH_SPIN")
        clientWriteDouble(server, client, LEASE_PUBLISHER, 14.0)
        controller.updateHardwareInputs(store, telemetry, mecanumIO, pinpointIO = null) {}

        assertEquals("TRACK_WIDTH_SPIN", controller.activeCalibration)
        assertTrue(controller.networkArmed)

        mecanumIO.setMotorPowers(0.5, 0.5, 0.5, 0.5)
        clientWrite(server, client, TOKEN_PUBLISHER, "rotated-session-token")
        controller.updateHardwareInputs(store, telemetry, mecanumIO, pinpointIO = null) {}
        assertFalse(controller.networkArmed)
        assertMotorPowers(mecanumIO, 0.0, 0.0, 0.0, 0.0)
        assertTrue(controller.updateSubsystems(store, 12.0, mecanumIO, telemetry) {})

        // The token fault owns only its neutral frame. Manual tuning authority returns afterward.
        mecanumIO.setMotorPowers(0.2, -0.2, 0.2, -0.2)
        assertFalse(controller.updateSubsystems(store, 12.0, mecanumIO, telemetry) {})
        assertMotorPowers(mecanumIO, 0.2, -0.2, 0.2, -0.2)
    }

    @Test
    fun `armed calibration expires and neutralizes when dashboard lease stops`() {
        RobotClock.useMockTime(1_000L)
        val server = NT4Instance.defaultInstance.startServer("127.0.0.1", 0)
        val store = Store()
        val telemetry = FtcTelemetryManager(store, hardwareRegistry)
        val mecanumIO = MecanumHardwareIO(motorHardwareMap(), hardwareRegistry)
        val controller = FtcMecanumCalibrationController()
        val client = webSocketProxy()
        server.onOpen(client, proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) })

        publishString(server, client, COMMAND_TOPIC, COMMAND_PUBLISHER)
        publishString(server, client, ENABLE_TOKEN_TOPIC, TOKEN_PUBLISHER)
        publishDouble(server, client, ENABLE_LEASE_TOPIC, LEASE_PUBLISHER)
        clientWrite(server, client, COMMAND_PUBLISHER, STOP_COMMAND)
        clientWrite(server, client, TOKEN_PUBLISHER, "retained")
        clientWriteDouble(server, client, LEASE_PUBLISHER, 1.0)
        controller.enableMode(telemetry, mecanumIO)

        clientWrite(server, client, TOKEN_PUBLISHER, "fresh")
        clientWriteDouble(server, client, LEASE_PUBLISHER, 2.0)
        controller.updateHardwareInputs(store, telemetry, mecanumIO, null) {}
        assertTrue(controller.networkArmed)

        clientWrite(server, client, COMMAND_PUBLISHER, "START_LINEAR_DRIVE")
        controller.updateHardwareInputs(store, telemetry, mecanumIO, null) {}
        assertEquals("LINEAR_DRIVE", controller.activeCalibration)

        RobotClock.useMockTime(1_490L)
        clientWriteDouble(server, client, LEASE_PUBLISHER, 3.0)
        controller.updateHardwareInputs(store, telemetry, mecanumIO, null) {}
        assertTrue(controller.networkArmed)

        RobotClock.useMockTime(1_991L)
        controller.updateHardwareInputs(store, telemetry, mecanumIO, null) {}
        assertFalse(controller.networkArmed)
        assertEquals("NONE", controller.activeCalibration)
        assertEquals("ENABLE_LEASE_EXPIRED", telemetry.nt4.getString("SysId/Error", ""))
        assertMotorPowers(mecanumIO, 0.0, 0.0, 0.0, 0.0)
    }

    private fun assertMotorPowers(
        mecanumIO: MecanumHardwareIO,
        fl: Double,
        fr: Double,
        rl: Double,
        rr: Double,
    ) {
        assertEquals(fl, mecanumIO.flIO.power, 0.0)
        assertEquals(fr, mecanumIO.frIO.power, 0.0)
        assertEquals(rl, mecanumIO.rlIO.power, 0.0)
        assertEquals(rr, mecanumIO.rrIO.power, 0.0)
    }

    private fun publishString(server: NT4Server, client: WebSocket, topic: String, publisherId: Int) {
        server.onMessage(
            client,
            """{"method":"publish","params":{"name":"$topic","pubuid":$publisherId,"type":"string"}}"""
        )
    }

    private fun publishDouble(server: NT4Server, client: WebSocket, topic: String, publisherId: Int) {
        server.onMessage(
            client,
            """{"method":"publish","params":{"name":"$topic","pubuid":$publisherId,"type":"double"}}"""
        )
    }

    private fun clientWrite(server: NT4Server, client: WebSocket, publisherId: Int, value: String) {
        server.onMessage(
            client,
            server.encodeNT4Message(
                RobotClock.currentTimeMillis() * 1_000L,
                publisherId.toLong(),
                publisherId.toLong(),
                STRING_TYPE,
                value
            )
        )
    }

    private fun clientWriteDouble(server: NT4Server, client: WebSocket, publisherId: Int, value: Double) {
        server.onMessage(
            client,
            server.encodeNT4Message(
                RobotClock.currentTimeMillis() * 1_000L,
                publisherId.toLong(),
                publisherId.toLong(),
                DOUBLE_TYPE,
                value
            )
        )
    }

    private fun motorHardwareMap(motors: Array<CalibrationMotor> = Array(4) { CalibrationMotor() }): HardwareMap {
        return object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T = when (deviceName) {
                "fl" -> motors[0] as T
                "fr" -> motors[1] as T
                "rl" -> motors[2] as T
                "rr" -> motors[3] as T
                else -> throw IllegalArgumentException("Unknown motor $deviceName")
            }

            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }
    }

    private class CalibrationMotor : DcMotorEx {
        @Volatile var measuredCurrent = 0.0
        private val ownerThreadId = Thread.currentThread().id
        var currentReads = 0
        override val currentPosition: Int = 0
        override var velocity: Double = 0.0
        override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
        override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
        override var zeroPowerBehavior: DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.FLOAT
        override var power: Double = 0.0
        override fun getCurrent(unit: CurrentUnit): Double {
            // The registry legitimately polls on its daemon; detect extra IO only on the output thread.
            if (Thread.currentThread().id == ownerThreadId) currentReads++
            return measuredCurrent
        }
    }

    private fun webSocketProxy(): WebSocket = proxy { method, _ -> defaultValue(method.returnType) }

    private inline fun <reified T> proxy(
        crossinline handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "FtcMecanumCalibrationControllerTestProxy"
            else -> handler(method, args)
        }
    } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private companion object {
        const val COMMAND_PUBLISHER = 1
        const val TOKEN_PUBLISHER = 2
        const val LEASE_PUBLISHER = 3
        const val STRING_TYPE = 4
        const val DOUBLE_TYPE = 1
        const val COMMAND_TOPIC = "SysId/Command"
        const val STATUS_TOPIC = "SysId/Status"
        const val ENABLE_TOKEN_TOPIC = "SysId/EnableToken"
        const val ENABLE_LEASE_TOPIC = "SysId/EnableLease"
        const val STOP_COMMAND = "STOP"
    }
}
