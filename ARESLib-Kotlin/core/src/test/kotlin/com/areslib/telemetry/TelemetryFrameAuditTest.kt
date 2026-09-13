package com.areslib.telemetry

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.areslib.state.PathState
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import com.areslib.state.VisionMeasurementSnapshot
import com.areslib.state.VisionState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class TelemetryFrameAuditTest {
    @AfterEach fun restoreClock() = RobotClock.useSystemTime()

    @Test fun `removed and replaced indicator names are explicitly extinguished once`() {
        val out = AuditRecordingTelemetry(); val publisher = ARESNetworkStatePublisher(out)
        fun frame(lights: Map<String, Double>) = publisher.publish(RobotState(superstructure = SuperstructureState(indicatorLights = lights)), flush = false)
        frame(mapOf("ready" to 1.0, "fault" to 0.7))
        frame(mapOf("ready" to 0.5, "new" to 0.3))
        assertEquals(0.0, out.numbers["Superstructure/IndicatorLight/fault"])
        assertEquals(0.5, out.numbers["Superstructure/IndicatorLight/ready"])
        frame(emptyMap())
        assertEquals(0.0, out.numbers["Superstructure/IndicatorLight/ready"])
        assertEquals(0.0, out.numbers["Superstructure/IndicatorLight/new"])
        val writes = out.numberWrites
        frame(emptyMap())
        assertEquals(1, out.writesByKey["Superstructure/IndicatorLight/fault"]?.count { it == 0.0 })
        assertTrue(out.numberWrites > writes) // Ordinary frame topics continue to publish.
    }

    @Test fun `invalid loop periods replace old timing and frequency with unknown`() {
        val out = AuditRecordingTelemetry(); val publisher = ARESNetworkStatePublisher(out)
        for (invalid in listOf(0.0, -0.02, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Double.MAX_VALUE)) {
            publisher.publish(RobotState(), dtSeconds = 0.02, flush = false)
            assertEquals(20.0, out.numbers["Robot/LoopTimeMs"])
            assertEquals(50.0, out.numbers["Profiling/Hz"])
            publisher.publish(RobotState(), dtSeconds = invalid, flush = false)
            for (topic in listOf("Robot/LoopTimeMs", "Profiling/LoopTime_ms", "Profiling/Hz")) {
                assertTrue(out.numbers.getValue(topic).isNaN(), "$topic must be unknown for $invalid")
            }
        }
    }

    @Test fun `omitted optional diagnostics preserve the caller owned values`() {
        val out = AuditRecordingTelemetry(); val publisher = ARESNetworkStatePublisher(out)
        publisher.publish(RobotState(), dtSeconds = 0.01, batteryVoltage = 11.5, flush = false)
        publisher.publish(RobotState(), flush = false)
        assertEquals(10.0, out.numbers["Robot/LoopTimeMs"])
        assertEquals(100.0, out.numbers["Profiling/Hz"])
        assertEquals(11.5, out.numbers["Robot/BatteryVoltage"])
        assertEquals(0, out.flushes)
        publisher.publish(RobotState())
        assertEquals(1, out.flushes)
    }

    @Test fun `future vision timestamps cannot become fresh through signed subtraction wrap`() {
        val out = AuditRecordingTelemetry(); val publisher = ARESNetworkStatePublisher(out)
        RobotClock.useMockTime(Long.MIN_VALUE)
        publisher.publish(RobotState(vision = VisionState(hasTarget = true, measurements = listOf(VisionMeasurementSnapshot(timestampMs = Long.MAX_VALUE)))), flush = false)
        assertEquals(false, out.booleans["Vision/HasTarget"])
        assertContentEquals(doubleArrayOf(), out.arrays["Vision/PoseArray"])
    }

    @Test fun `vision freshness accepts the boundary and rejects old or future observations`() {
        val out = AuditRecordingTelemetry(); val publisher = ARESNetworkStatePublisher(out)
        RobotClock.useMockTime(1000)
        for ((timestamp, fresh) in listOf(500L to true, 499L to false, 1000L to true, 1001L to false, Long.MIN_VALUE to false)) {
            publisher.publish(RobotState(vision = VisionState(hasTarget = true, measurements = listOf(VisionMeasurementSnapshot(timestampMs = timestamp)))), flush = false)
            assertEquals(fresh, out.booleans["Vision/HasTarget"], "timestamp=$timestamp")
        }
    }

    @Test fun `path replacement and removal preserve cache ownership and snapshots`() {
        val out = AuditRecordingTelemetry(); val publisher = ARESNetworkStatePublisher(out)
        val first = Path(listOf(PathPoint(Pose2d(1.0, 2.0, Rotation2d(0.5)), 1.0)))
        val firstState = RobotState(pathState = PathState(activePath = first))
        publisher.publish(firstState, flush = false)
        val retained = out.arrays.getValue("Path/Points")
        val buffer = out.arrayInputs.getValue("Path/Points")
        publisher.publish(firstState, flush = false)
        assertSame(buffer, out.arrayInputs["Path/Points"])
        val next = Path(listOf(PathPoint(Pose2d(-3.0, 4.0, Rotation2d(1.0)), 1.0)))
        publisher.publish(RobotState(pathState = PathState(activePath = next)), flush = false)
        assertContentEquals(doubleArrayOf(-3.0, 4.0, 1.0), out.arrays["Path/Points"])
        assertContentEquals(doubleArrayOf(1.0, 2.0, 0.5), retained)
        publisher.publish(RobotState(), flush = false)
        assertContentEquals(doubleArrayOf(), out.arrays["Path/Points"])
        assertEquals(false, out.booleans["Path/Active"])
    }

    @Test fun `pose helpers preserve units yaw quaternion and retained samples`() {
        val out = AuditRecordingTelemetry()
        out.logPoseArray2d("pose", Pose2d(2.0, -3.0, Rotation2d(0.5)))
        val first = out.arrays.getValue("pose"); val buffer = out.arrayInputs.getValue("pose")
        out.logPoseArray2d("pose", Pose2d())
        assertSame(buffer, out.arrayInputs["pose"])
        assertContentEquals(doubleArrayOf(2.0, -3.0, 0.5), first)
        out.logPose3d("pose3", 2.0, -3.0, Math.PI)
        val pose3 = out.arrays.getValue("pose3")
        assertEquals(7, pose3.size)
        assertContentEquals(doubleArrayOf(2.0, -3.0, 0.0), pose3.copyOfRange(0, 3))
        assertEquals(0.0, pose3[3], 1e-15); assertEquals(1.0, pose3[6], 1e-15)
        assertEquals(0.0, pose3[4]); assertEquals(0.0, pose3[5])
        out.logPose2d("scalar", Pose2d(2.0, -3.0, Rotation2d(0.5)), useUnderscores = true, lowercase = true)
        assertEquals(2.0, out.numbers["scalar_x"]); assertEquals(-3.0, out.numbers["scalar_y"])
        assertEquals(0.5, out.numbers["scalar_heading"])
    }

    @Test fun `frame sequence wraps before losing integer precision`() {
        val out = AuditRecordingTelemetry(); val publisher = ARESNetworkStatePublisher(out)
        val maximum = 9_007_199_254_740_991L
        publisher.javaClass.getDeclaredField("frameSequence").apply { isAccessible = true }.setLong(publisher, maximum)
        publisher.publish(RobotState(), flush = false)
        assertEquals(maximum.toDouble(), out.numbers[TelemetryTopicConstants.TELEMETRY_FRAME_SEQUENCE])
        publisher.publish(RobotState(), flush = false)
        assertEquals(0.0, out.numbers[TelemetryTopicConstants.TELEMETRY_FRAME_SEQUENCE])
    }

    @Test fun `topology and calibration have explicit flush and snapshot ownership`() {
        val out = AuditRecordingTelemetry(); val publisher = ARESNetworkStatePublisher(out)
        publisher.publishTopology("{}", flush = false)
        assertEquals(0, out.flushes)
        assertEquals("{}", out.strings[com.areslib.telemetry.schema.HARDWARE_TOPOLOGY_TOPIC])
        publisher.publishTopology("[]")
        assertEquals(1, out.flushes)
        val camera = doubleArrayOf(1.0, 2.0, 3.0)
        publisher.publishCalibration(true, 0.5, 7, 2, camera)
        camera[0] = 99.0
        assertEquals(2, out.flushes)
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0), out.arrays["Calibration/CameraToTag"])
        assertTrue(out.arrays.getValue("Calibration/TagField").all { it.isNaN() })
        assertEquals(7.0, out.numbers["Calibration/TagIndex"])
        assertEquals(2.0, out.numbers["Calibration/CameraIndex"])
        assertEquals(0.5, out.numbers["Calibration/GyroHeading"])
        publisher.publishCalibration(false, 0.0, -1, -1, doubleArrayOf(), doubleArrayOf(4.0, 5.0, 6.0))
        assertEquals(false, out.booleans["Calibration/IsActive"])
        assertContentEquals(doubleArrayOf(4.0, 5.0, 6.0), out.arrays["Calibration/TagField"])
    }

    @Test fun `motor and CAN helpers preserve cached sampling counts units and topic names`() {
        val out = AuditRecordingTelemetry(); val calls = mutableListOf<String>()
        val motor = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(com.areslib.hardware.actuator.MotorIO::class.java)) { _, method, _ ->
            calls.add(method.name)
            when (method.name) {
                "getPower" -> 0.8; "getPowerScale" -> 0.25; "getPosition" -> 123.0
                "getVelocity" -> -20.0; "getCurrentAmps" -> 3.0
                else -> error("Unexpected hardware operation: ${method.name}")
            }
        } as com.areslib.hardware.actuator.MotorIO
        out.logDriveMotor("front", motor)
        assertEquals(listOf("getPower", "getPowerScale", "getPosition", "getVelocity", "getCurrentAmps"), calls)
        assertEquals(mapOf("Hardware/Motors/front/Power" to 0.2, "Hardware/Motors/front/Position" to 123.0, "Hardware/Motors/front/Velocity" to -20.0, "Hardware/Motors/front/CurrentAmps" to 3.0), out.numbers)
        out.logCanBusStatus("main", 0.4, 3, 1, 2, 0, 2.5)
        val expected = mapOf("Utilization" to 0.4, "ErrorCount" to 3.0, "TxErrors" to 1.0, "RxErrors" to 2.0, "BusOffCount" to 0.0, "SignalLatencyMs" to 2.5)
        expected.forEach { (key, value) -> assertEquals(value, out.numbers["Diagnostics/CANBus/main/$key"]) }
    }

    @Test fun `command catalog updates only with registry revision without creating tasks`() {
        val registry = com.areslib.pathing.NamedCommands
        val lock = registry.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(registry)
        val revision = registry.javaClass.getDeclaredField("revision").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val commands = registry.javaClass.getDeclaredField("commands").apply { isAccessible = true }.get(registry) as MutableMap<Any, Any>
        synchronized(lock) {
            val saved = commands.toMap(); val savedRevision = revision.getLong(registry)
            try {
                registry.clear()
                val out = AuditRecordingTelemetry(); val publisher = ARESNetworkStatePublisher(out)
                publisher.publish(RobotState(), flush = false)
                assertEquals("[]", out.strings["ARES/Auto/CommandCatalog"])
                val key = com.areslib.pathing.CommandKey("Audit.Command")
                val descriptor = com.areslib.pathing.NamedCommandDescriptor(key, "Quoted \"Name\"", "description", requiredResources = 5L)
                registry.register(descriptor) { error("Catalog publication must not construct a task") }
                publisher.publish(RobotState(), flush = false)
                val json = out.strings.getValue("ARES/Auto/CommandCatalog")
                val row = com.google.gson.JsonParser.parseString(json).asJsonArray.single().asJsonObject
                assertEquals("Audit.Command", row["key"].asString)
                assertEquals("Quoted \"Name\"", row["displayName"].asString)
                assertEquals("0x5", row["requiredResources"].asString)
                publisher.publish(RobotState(), flush = false)
                assertSame(json, out.strings["ARES/Auto/CommandCatalog"])
                registry.clear(); publisher.publish(RobotState(), flush = false)
                assertEquals("[]", out.strings["ARES/Auto/CommandCatalog"])
            } finally { commands.clear(); commands.putAll(saved); revision.setLong(registry, savedRevision) }
        }
    }
}

internal class AuditRecordingTelemetry : ITelemetry {
    val numbers = mutableMapOf<String, Double>(); val booleans = mutableMapOf<String, Boolean>()
    val strings = mutableMapOf<String, String>(); val arrays = mutableMapOf<String, DoubleArray>()
    val arrayInputs = mutableMapOf<String, DoubleArray>(); val writesByKey = mutableMapOf<String, MutableList<Double>>()
    var flushes = 0; var numberWrites = 0
    override fun putNumber(key: String, value: Double) { numbers[key] = value; numberWrites++; writesByKey.getOrPut(key) { mutableListOf() }.add(value) }
    override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun putDoubleArray(key: String, value: DoubleArray) { arrayInputs[key] = value; arrays[key] = value.copyOf() }
    override fun getNumber(key: String, defaultValue: Double) = numbers[key] ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean) = booleans[key] ?: defaultValue
    override fun getString(key: String, defaultValue: String) = strings[key] ?: defaultValue
    override fun update() { flushes++ }
}
