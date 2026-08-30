package org.firstinspires.ftc.teamcode

import com.areslib.ftc.FtcBaseRobot
import com.areslib.ftc.photon.AresFtcRuntimeOptionsProvider
import com.areslib.networktables.NT4Instance
import com.areslib.state.Alliance
import com.areslib.telemetry.RobotStatusTracker
import com.areslib.util.PoseStorage
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.limelightvision.Limelight3A
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.Gamepad
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Servo
import com.qualcomm.robotcore.hardware.VoltageSensor
import java.io.File
import org.firstinspires.ftc.robotcore.external.Telemetry
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeServices
import org.firstinspires.ftc.teamcode.dsl.AresAutoBase
import org.firstinspires.ftc.teamcode.opmodes.ARESAuto
import org.firstinspires.ftc.teamcode.opmodes.TestAutoBlue
import org.firstinspires.ftc.teamcode.opmodes.TestAutoRed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * Integration and lifecycle tests for [AresAutoBase] verifying clean initialization,
 * loop execution, and stop lifecycles with telemetry and NT4 resource management.
 */
class AresAutoBaseTest {

    @Before
    fun setUp() {
        RobotStatusTracker.isEnabled = false
        PoseStorage.hasValidPose = false
    }

    @After
    fun tearDown() {
        NT4Instance.defaultInstance.closeServer()
    }

    private fun findFieldJsonFile(): File {
        val candidates = listOf(
            File("src/main/assets/paths/field.json"),
            File("TeamCode/src/main/assets/paths/field.json"),
            File("../TeamCode/src/main/assets/paths/field.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: File(
                generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile, File::getParentFile)
                    .firstOrNull { File(it, "TeamCode/src/main/assets/paths/field.json").isFile }
                    ?: error("Could not find field.json for test assets"),
                "TeamCode/src/main/assets/paths/field.json",
            )
    }

    private fun createMockHardwareMap(): Pair<HardwareMap, Telemetry> {
        val fl = Mockito.mock(DcMotorEx::class.java)
        val fr = Mockito.mock(DcMotorEx::class.java)
        val bl = Mockito.mock(DcMotorEx::class.java)
        val br = Mockito.mock(DcMotorEx::class.java)
        val pinpoint = Mockito.mock(GoBildaPinpointDriver::class.java)
        val limelight = Mockito.mock(Limelight3A::class.java)
        val voltageSensor = Mockito.mock(VoltageSensor::class.java)
        val leftIndicator = Mockito.mock(Servo::class.java)
        val rightIndicator = Mockito.mock(Servo::class.java)
        val prism = Mockito.mock(Servo::class.java)
        Mockito.`when`(voltageSensor.voltage).thenReturn(12.5)

        val mockHardwareMap = Mockito.mock(HardwareMap::class.java)
        Mockito.`when`(mockHardwareMap.get(DcMotorEx::class.java, "fl")).thenReturn(fl)
        Mockito.`when`(mockHardwareMap.get(DcMotorEx::class.java, "fr")).thenReturn(fr)
        Mockito.`when`(mockHardwareMap.get(DcMotorEx::class.java, "rl")).thenReturn(bl)
        Mockito.`when`(mockHardwareMap.get(DcMotorEx::class.java, "rr")).thenReturn(br)
        Mockito.`when`(mockHardwareMap.get(GoBildaPinpointDriver::class.java, "pinpoint")).thenReturn(pinpoint)
        Mockito.`when`(mockHardwareMap.get(Limelight3A::class.java, "limelight")).thenReturn(limelight)
        Mockito.`when`(mockHardwareMap.get(Servo::class.java, "indicator")).thenReturn(leftIndicator)
        Mockito.`when`(mockHardwareMap.get(Servo::class.java, "indicator2")).thenReturn(rightIndicator)
        Mockito.`when`(mockHardwareMap.get(Servo::class.java, "prism")).thenReturn(prism)

        @Suppress("UNCHECKED_CAST")
        Mockito.`when`(mockHardwareMap.getAll(VoltageSensor::class.java)).thenReturn(listOf(voltageSensor))
        val mockTelemetry = Mockito.mock(Telemetry::class.java)

        val mockContext = Mockito.mock(android.content.Context::class.java)
        val mockAssetManager = Mockito.mock(android.content.res.AssetManager::class.java)
        val fieldJsonFile = findFieldJsonFile()
        Mockito.`when`(mockAssetManager.open("paths/field.json")).thenAnswer {
            fieldJsonFile.inputStream()
        }
        Mockito.`when`(mockContext.assets).thenReturn(mockAssetManager)
        try {
            val field = HardwareMap::class.java.getField("appContext")
            field.isAccessible = true
            field.set(mockHardwareMap, mockContext)
        } catch (_: Throwable) {
            val field = HardwareMap::class.java.getDeclaredField("appContext")
            field.isAccessible = true
            field.set(mockHardwareMap, mockContext)
        }

        return Pair(mockHardwareMap, mockTelemetry)
    }

    private fun attachMockOpModeServices(opMode: AresAutoBase): OpModeServices {
        val mockServices = Mockito.mock(OpModeServices::class.java)
        var currentClass: Class<*>? = opMode.javaClass
        while (currentClass != null) {
            try {
                val field = currentClass.getDeclaredField("internalOpModeServices")
                field.isAccessible = true
                field.set(opMode, mockServices)
                break
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            }
        }
        return mockServices
    }

    @Test
    fun `auto opmodes inherit generated runtime policy`() {
        assertTrue(AresFtcRuntimeOptionsProvider::class.java.isAssignableFrom(AresAutoBase::class.java))
        assertTrue(AresFtcRuntimeOptionsProvider::class.java.isAssignableFrom(ARESAuto::class.java))
        assertTrue(AresFtcRuntimeOptionsProvider::class.java.isAssignableFrom(TestAutoRed::class.java))
        assertTrue(AresFtcRuntimeOptionsProvider::class.java.isAssignableFrom(TestAutoBlue::class.java))
    }

    @Test
    fun testAresAutoBaseLifecycleClosesTelemetryAndNt4Cleanly() {
        val (mockHardwareMap, mockTelemetry) = createMockHardwareMap()

        val opMode = object : AresAutoBase() {
            init {
                this.hardwareMap = mockHardwareMap
                this.telemetry = mockTelemetry
            }
        }
        attachMockOpModeServices(opMode)

        val gamepad = Gamepad()
        opMode.gamepad1 = gamepad
        opMode.gamepad2 = Gamepad()

        try {
            assertNull("FtcBaseRobot.activeInstance should be null before init", FtcBaseRobot.activeInstance)
            opMode.init()
            assertNotNull("FtcBaseRobot.activeInstance should be set after init", FtcBaseRobot.activeInstance)
            Mockito.verify(mockTelemetry, Mockito.atLeastOnce()).addData(Mockito.anyString(), Mockito.any())
            Mockito.verify(mockTelemetry, Mockito.atLeastOnce()).update()

            opMode.init_loop()
            opMode.start()
            assertEquals("Auto", RobotStatusTracker.activeOpMode)

            opMode.loop()
        } finally {
            opMode.stop()
            assertNull("FtcBaseRobot.activeInstance should be cleared after stop", FtcBaseRobot.activeInstance)
            NT4Instance.defaultInstance.closeServer()
        }

        // Verify stop is idempotent
        opMode.stop()
        assertNull(FtcBaseRobot.activeInstance)
    }

    @Test
    fun testLockedAutonomousLifecycle() {
        val (mockHardwareMap, mockTelemetry) = createMockHardwareMap()

        val opMode = object : AresAutoBase() {
            override val lockedAutonomousEntryId = "test-auto"
            override val lockedAutonomousAlliance = Alliance.RED

            init {
                this.hardwareMap = mockHardwareMap
                this.telemetry = mockTelemetry
            }
        }
        attachMockOpModeServices(opMode)
        opMode.gamepad1 = Gamepad()
        opMode.gamepad2 = Gamepad()

        try {
            opMode.init()
            assertNotNull(FtcBaseRobot.activeInstance)
            opMode.init_loop()
            opMode.start()
            opMode.loop()
        } finally {
            opMode.stop()
            assertNull(FtcBaseRobot.activeInstance)
            NT4Instance.defaultInstance.closeServer()
        }
    }

    @Test
    fun `maximumRuntimeSeconds must be positive and bounded by 30 seconds`() {
        val (mockHardwareMap, mockTelemetry) = createMockHardwareMap()

        val invalidZero = object : AresAutoBase() {
            override val maximumRuntimeSeconds = 0.0
            init {
                this.hardwareMap = mockHardwareMap
                this.telemetry = mockTelemetry
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            invalidZero.init()
        }

        val invalidExcessive = object : AresAutoBase() {
            override val maximumRuntimeSeconds = 35.0
            init {
                this.hardwareMap = mockHardwareMap
                this.telemetry = mockTelemetry
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            invalidExcessive.init()
        }
    }

    @Test
    fun `TestAutoBlue and TestAutoRed initialize with locked alliances and 30s max runtime`() {
        val (mockHardwareMap, mockTelemetry) = createMockHardwareMap()

        // 1. Verify TestAutoBlue initializes with locked BLUE alliance
        val blueOpMode = TestAutoBlue().apply {
            hardwareMap = mockHardwareMap
            telemetry = mockTelemetry
            gamepad1 = Gamepad()
            gamepad2 = Gamepad()
        }
        attachMockOpModeServices(blueOpMode)
        try {
            blueOpMode.init()
            assertNotNull("FtcBaseRobot active instance should be initialized for TestAutoBlue", FtcBaseRobot.activeInstance)
            assertEquals(blueOpMode.configurationErrorForTest(), Alliance.BLUE, FtcBaseRobot.activeInstance?.store?.state?.drive?.alliance)
        } finally {
            blueOpMode.stop()
            assertNull(FtcBaseRobot.activeInstance)
            NT4Instance.defaultInstance.closeServer()
        }

        // 2. Verify TestAutoRed initializes with locked RED alliance
        val redOpMode = TestAutoRed().apply {
            hardwareMap = mockHardwareMap
            telemetry = mockTelemetry
            gamepad1 = Gamepad()
            gamepad2 = Gamepad()
        }
        attachMockOpModeServices(redOpMode)
        try {
            redOpMode.init()
            assertNotNull("FtcBaseRobot active instance should be initialized for TestAutoRed", FtcBaseRobot.activeInstance)
            assertEquals(redOpMode.configurationErrorForTest(), Alliance.RED, FtcBaseRobot.activeInstance?.store?.state?.drive?.alliance)
        } finally {
            redOpMode.stop()
            assertNull(FtcBaseRobot.activeInstance)
            NT4Instance.defaultInstance.closeServer()
        }

        // 3. Verify maximum allowable runtime of exactly 30.0 seconds initializes without throwing
        val maxBoundaryOpMode = object : AresAutoBase() {
            override val maximumRuntimeSeconds = 30.0
            init {
                hardwareMap = mockHardwareMap
                telemetry = mockTelemetry
                gamepad1 = Gamepad()
                gamepad2 = Gamepad()
            }
        }
        attachMockOpModeServices(maxBoundaryOpMode)
        try {
            maxBoundaryOpMode.init()
            assertNotNull("FtcBaseRobot active instance should be initialized for 30.0s max runtime", FtcBaseRobot.activeInstance)
        } finally {
            maxBoundaryOpMode.stop()
            assertNull(FtcBaseRobot.activeInstance)
            NT4Instance.defaultInstance.closeServer()
        }
    }

    private fun AresAutoBase.configurationErrorForTest(): String? {
        var type: Class<*>? = AresAutoBase::class.java
        var field: java.lang.reflect.Field? = null
        while (type != null && field == null) {
            val current = type
            field = runCatching { current.getDeclaredField("configurationError") }.getOrNull()
            type = current.superclass
        }
        field = requireNotNull(field)
        field.isAccessible = true
        return field.get(this) as? String
    }
}
