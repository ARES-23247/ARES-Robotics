package com.ares.analytics.service.project

/** Test source compiled inside the exported consumer against its actual generated runtime. */
internal fun consumerHeadingBehavior(gain: Double, expectedOmega: Double): String = """
    @Test
    fun `saved canonical gain governs heading output`() {
        assertEquals($gain, GeneratedAresTuningConfig.Parameters.DRIVE_HEADINGKP, 1e-9)
        assertEquals(0.0, GeneratedAresTuningConfig.Parameters.DRIVE_HEADINGKD, 1e-9)
        RobotClock.useMockTime(1_000L)
        val robotDouble = MecanumRobotDouble()
        val lifecycle = requireNotNull(SimOpModeRunner.createOpModeInstance(null, ARESStarterTeleOp::class.java.name))
        try {
            lifecycle.initialize(robotDouble.hardwareMap)
            val robot = FtcBaseRobot.activeInstance as FtcMecanumRobot
            assertEquals($gain, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertEquals(0.0, robot.store.state.tuning.drive.headingGains.kI, 1e-9)
            lifecycle.gamepad1.id = 1
            lifecycle.tick()
            lifecycle.start()
            robot.resetPose(Pose2d())
            lifecycle.gamepad1.y = true
            RobotClock.useMockTime(1_010L)
            lifecycle.tick()
            lifecycle.gamepad1.y = false

            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            RobotClock.useMockTime(1_030L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertEquals(0.0, robot.store.state.drive.headingLockTargetRadians ?: Double.NaN, 1e-4)

            // Independent P-only oracle: target 0, measured heading -0.10 => error +0.10 rad.
            // Expected omega is a literal supplied by the test, never read from generated output.
            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 0.0, -0.10)
            RobotClock.useMockTime(1_050L)
            awaitImuSample(robot, -0.10)
            lifecycle.tick() // Read actual simulated sensors into Redux.
            RobotClock.useMockTime(1_070L)
            awaitImuSample(robot, -0.10)
            lifecycle.tick() // Controls now consume the measured heading.
            val drive = robot.store.state.drive
            assertTrue(drive.imuMeasurementsValid)
            assertEquals(-0.10, drive.poseEstimator.estimatedPose.heading.radians, 1e-3)
            assertEquals(0.0, drive.xVelocityMetersPerSecond, 1e-9)
            assertEquals(0.0, drive.yVelocityMetersPerSecond, 1e-9)
            assertEquals($expectedOmega, drive.angularVelocityRadiansPerSecond, 1e-3)
            val right = robotDouble.fr.power
            assertTrue("Positive CCW effort below saturation", right > 0.01 && right < 1.0)
            assertEquals(right, robotDouble.rr.power, 1e-3)
            assertEquals(-right, robotDouble.fl.power, 1e-3)
            assertEquals(-right, robotDouble.rl.power, 1e-3)
        } finally {
            lifecycle.stop()
        }
        assertTrue(listOf(robotDouble.fl, robotDouble.fr, robotDouble.rl, robotDouble.rr).all { abs(it.power) < 1e-9 })
    }

    private fun awaitImuSample(robot: FtcMecanumRobot, heading: Double) {
        val input = com.areslib.hardware.sensor.ImuInputs()
        val imu = requireNotNull(robot.imuIO)
        // Wall time only bounds waiting for the real asynchronous sensor worker. Robot time is mocked.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            imu.updateInputs(input)
            if (input.timestampMs == RobotClock.currentTimeMillis() && abs(input.headingRadians - heading) < 1e-6) return
            check(System.nanoTime() < deadline) { "Asynchronous IMU did not publish the requested sample: " + input }
            Thread.sleep(1)
        }
    }
""".trimIndent()

internal fun consumerFeedbackBehavior(biobuzz: Boolean): String {
    val type = if (biobuzz) "BiobuzzIntake" else "Gripper"
    val device = if (biobuzz) "SimDcMotorEx::class.java, \"intake\"" else "SimServo::class.java, \"gripper-servo\""
    val output = if (biobuzz) "device.power" else "device.position"
    val neutral = if (biobuzz) 0.0 else 0.5
    val setter = if (biobuzz) "setIntakeVoltage" else "setTarget"
    val target = if (biobuzz) 12.0 else 1.0
    val field = if (biobuzz) "intakeVoltage" else "target"
    val timeout = if (biobuzz) 120L else 180L
    return """
    @Test
    fun `saved feedback timeout gates generated subsystem and controller`() {
        RobotClock.useMockTime(1_000L)
        val robotDouble = MecanumRobotDouble()
        val device = robotDouble.hardwareMap.get($device)
        val io = Ftc${type}IO(robotDouble.hardwareMap)
        val subsystem = ${type}Subsystem(io)
        val controller = ${type}Controller(io)
        val store = Store(RobotState())
        try {
            subsystem.$setter(store, $target)
            // No forged healthy flags: the generated IO and readSensors must establish them.
            subsystem.readSensors(store, 1_000L)
            assertFalse(${type}Subsystem.state(store.state).feedbackValid)
            subsystem.writeOutputs(store.state, 1.0)
            assertEquals($neutral, $output, 1e-6)

            RobotClock.useMockTime(2_000L)
            io.refresh()
            subsystem.readSensors(store, 2_000L)
            val freshState = ${type}Subsystem.state(store.state)
            assertTrue(freshState.configurationHealthy && freshState.homed && freshState.calibrated)
            assertTrue(freshState.currentReadingValid && freshState.feedbackValid)
            assertEquals(2_000L, freshState.feedbackTimestampMs)
            subsystem.writeOutputs(store.state, 1.0)
            assertEquals(1.0, $output, 1e-6)

            // Power-budget inhibition is separate from the actual OpMode STOP test below.
            subsystem.writeOutputs(store.state, 0.0)
            assertEquals($neutral, $output, 1e-6)
            subsystem.writeOutputs(store.state, 1.0)
            assertEquals(1.0, $output, 1e-6)

            for (time in listOf(${2000 + timeout - 1}L, ${2000 + timeout}L)) {
                RobotClock.useMockTime(time)
                subsystem.readSensors(store, time) // Deliberately do not refresh IO.
                assertTrue(${type}Subsystem.state(store.state).feedbackValid)
                subsystem.writeOutputs(store.state, 1.0)
                assertEquals(1.0, $output, 1e-6)
            }
            RobotClock.useMockTime(${2000 + timeout + 1}L)
            // Independently check the controller rejects an expired, previously-valid snapshot.
            controller.update(freshState, 1.0)
            assertEquals($neutral, $output, 1e-6)
            subsystem.readSensors(store, ${2000 + timeout + 1}L)
            assertFalse(${type}Subsystem.state(store.state).feedbackValid)
            subsystem.writeOutputs(store.state, 1.0)
            assertEquals($neutral, $output, 1e-6)

            // Freshness recovery preserves the held command; it is not fault-latch rearming.
            RobotClock.useMockTime(2_250L)
            io.refresh()
            subsystem.readSensors(store, 2_250L)
            assertTrue(${type}Subsystem.state(store.state).feedbackValid)
            assertEquals($target, ${type}Subsystem.state(store.state).$field, 1e-6)
            subsystem.writeOutputs(store.state, 1.0)
            assertEquals(1.0, $output, 1e-6)
        } finally {
            subsystem.close()
        }
        assertEquals($neutral, $output, 1e-6)
        subsystem.writeOutputs(store.state, 1.0)
        assertEquals($neutral, $output, 1e-6)
    }

    @Test
    fun `real OpMode start permits output and stop neutralizes an active command`() {
        RobotClock.useMockTime(3_000L)
        val robotDouble = MecanumRobotDouble()
        val device = robotDouble.hardwareMap.get($device)
        val lifecycle = requireNotNull(SimOpModeRunner.createOpModeInstance(null, ARESStarterTeleOp::class.java.name))
        try {
            lifecycle.initialize(robotDouble.hardwareMap)
            lifecycle.gamepad1.id = 1
            lifecycle.tick()
            assertFalse(lifecycle.isStarted)
            assertEquals($neutral, $output, 1e-6)
            lifecycle.start()
            assertTrue(lifecycle.isStarted)
            ${if (biobuzz) "lifecycle.gamepad1.a = true" else """
            val robot = FtcBaseRobot.activeInstance as FtcMecanumRobot
            val gripper = robot.getRegisteredSubsystems().filterIsInstance<GripperSubsystem>().single()
            gripper.setTarget(robot.store, 1.0)
            """.trimIndent()}
            lifecycle.gamepad1.left_stick_y = -1.0f
            RobotClock.useMockTime(3_020L)
            lifecycle.tick()
            assertEquals(1.0, $output, 1e-6)
            assertTrue(listOf(robotDouble.fl, robotDouble.fr, robotDouble.rl, robotDouble.rr).any { abs(it.power) > 0.01 })

            // Stop with both mechanism and drive commands still active, without a neutral input first.
            lifecycle.stop()
            assertFalse(lifecycle.isStarted)
            assertEquals(com.areslib.sim.opmode.SimOpModeState.DISABLED, lifecycle.publishedState)
            assertEquals($neutral, $output, 1e-6)
            assertTrue(listOf(robotDouble.fl, robotDouble.fr, robotDouble.rl, robotDouble.rr).all { abs(it.power) < 1e-9 })
            RobotClock.useMockTime(3_040L)
            lifecycle.tick()
            assertEquals($neutral, $output, 1e-6)
            assertTrue(listOf(robotDouble.fl, robotDouble.fr, robotDouble.rl, robotDouble.rr).all { abs(it.power) < 1e-9 })
        } finally {
            lifecycle.stop()
        }
    }
    """.trimIndent()
}
