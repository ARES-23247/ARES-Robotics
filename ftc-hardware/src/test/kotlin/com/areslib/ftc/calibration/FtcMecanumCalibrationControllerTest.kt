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
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtcMecanumCalibrationControllerTest {
    @AfterEach
    fun cleanUp() {
        HardwareRegistry.clear()
        NT4Instance.defaultInstance.closeServer()
        RobotClock.useSystemTime()
    }

    @Test
    fun `enabled but unarmed relinquishes drivetrain after one-shot neutral`() {
        val telemetry = FtcTelemetryManager(Store())
        val mecanumIO = MecanumHardwareIO(motorHardwareMap())
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
        val telemetry = FtcTelemetryManager(store)
        val mecanumIO = MecanumHardwareIO(motorHardwareMap())
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
        val telemetry = FtcTelemetryManager(store)
        val mecanumIO = MecanumHardwareIO(motorHardwareMap())
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

    private fun motorHardwareMap(): HardwareMap {
        val motors = Array(4) { CalibrationMotor() }
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
        override val currentPosition: Int = 0
        override var velocity: Double = 0.0
        override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
        override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
        override var zeroPowerBehavior: DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.FLOAT
        override var power: Double = 0.0
        override fun getCurrent(unit: CurrentUnit): Double = 0.0
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
