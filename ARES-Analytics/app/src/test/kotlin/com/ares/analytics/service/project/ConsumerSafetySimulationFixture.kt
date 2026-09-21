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

internal fun consumerLiveTuningBehavior(gain: Double): String = """
    @Test
    fun `Studio wire requests reach generated tuning consumer with hold replay and close gates`() {
        RobotClock.useMockTime(1_000L)
        val robotDouble = MecanumRobotDouble()
        val lifecycle = requireNotNull(SimOpModeRunner.createOpModeInstance(null, ARESStarterTeleOp::class.java.name))
        var receiver: NT4Server? = null
        var connection: org.java_websocket.WebSocket? = null
        val cwd = File("").canonicalFile
        val project = if (File(cwd, ".ares/project.json").isFile) cwd else cwd.parentFile
        assertTrue(File(project, ".ares/project.json").isFile)
        val canonicalBefore = canonicalTuningSnapshot(project)
        val rootTopic = "Tuning/Parameters/ftc.drive.heading.kp"
        val motors = listOf(robotDouble.fl, robotDouble.fr, robotDouble.rl, robotDouble.rr)
        fun assertNeutral() = assertTrue("Every drive output must be neutral", motors.all { abs(it.power) < 1e-9 })
        try {
            lifecycle.initialize(robotDouble.hardwareMap)
            val robot = FtcBaseRobot.activeInstance as FtcMecanumRobot
            val manager = requireNotNull(robot.tuningManager)
            val server = requireNotNull(NT4Server.getInstance())
            val wire = consumerWireProxy(org.java_websocket.WebSocket::class.java)
            receiver = server
            connection = wire
            server.onOpen(wire, consumerWireProxy(org.java_websocket.handshake.ClientHandshake::class.java))
            lifecycle.gamepad1.id = 1
            lifecycle.tick()
            lifecycle.start()
            robot.isLiveTuningEnabled = true // Supported local OpMode opt-in; this does not grant network arm.
            assertFalse(robot.isCalibrationModeArmed)
            assertEquals("LIVE_SAFE", NT4Server.getString(rootTopic + "/ApplyPolicy", ""))
            assertEquals(GeneratedAresTuningConfig.CANONICAL_PROFILE_UID,
                NT4Server.getString("Tuning/CanonicalProfileUid", ""))
            assertEquals($gain, robot.store.state.tuning.drive.headingGains.kP, 1e-9)

            // Replay Studio's real registration and binary frames through the production server parser.
            RobotClock.useMockTime(1_020L)
            deliverStudioTuningFrames(server, wire, "unarmed")
            lifecycle.gamepad1.left_stick_y = -1.0f
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertTuningResult(1L, "SESSION_NOT_ARMED", $gain)
            assertEquals($gain, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertTrue("Prove the same held joystick drives before calibration owns outputs", motors.any { abs(it.power) > 0.01 })
            assertEquals(canonicalBefore, canonicalTuningSnapshot(project))

            robot.enableCalibrationMode()
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-1")
            NT4Server.publishTopic("SysId/EnableLease", 1.0)
            NT4Server.publishTopic("SysId/Command", "STOP")
            RobotClock.useMockTime(1_040L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertTrue(robot.isCalibrationModeArmed)
            assertTrue(robot.isCalibrationNeutralOutputHoldActive)
            assertNeutral() // Joystick is still nonzero, so this is actual inhibition.

            var leaseSequence = 1.0
            fun tickFreshHold(time: Long) {
                RobotClock.useMockTime(time)
                NT4Server.publishTopic("SysId/EnableLease", ++leaseSequence)
                awaitImuSample(robot, 0.0)
                lifecycle.tick()
                assertTrue(robot.isCalibrationModeArmed)
                assertTrue(robot.isCalibrationNeutralOutputHoldActive)
                assertNeutral()
            }
            fun holdUntil(time: Long) {
                while (RobotClock.currentTimeMillis() < time) {
                    tickFreshHold(RobotClock.currentTimeMillis() + 20L)
                }
            }
            // Keep real 20 ms frames, observed sensors and lease heartbeats between 500 ms polls.
            // A long clock jump could otherwise make another safety gate explain neutral output.
            holdUntil(1_500L)
            RobotClock.useMockTime(1_520L)
            deliverStudioTuningFrames(server, wire, "apply")
            tickFreshHold(1_520L)
            assertTuningResult(2L, "APPLIED", 3.2)
            assertEquals(3.2, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertTrue(robot.isCalibrationNeutralOutputHoldActive)
            assertNeutral()
            val appliedAck = NT4Server.getString(rootTopic + "/Acknowledgement", "")

            // Keep the real session armed and fresh so a rejection cannot be caused by another gate.
            holdUntil(2_000L)
            RobotClock.useMockTime(2_020L)
            deliverStudioTuningFrames(server, wire, "replay") // Same nonce, conflicting value 4.0.
            tickFreshHold(2_020L)
            assertTrue(robot.isCalibrationModeArmed)
            assertEquals(appliedAck, NT4Server.getString(rootTopic + "/Acknowledgement", ""))
            assertTuningResult(2L, "APPLIED", 3.2)
            assertEquals(3.2, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertNeutral()

            holdUntil(2_500L)
            RobotClock.useMockTime(2_520L)
            deliverStudioTuningFrames(server, wire, "invalid") // Fresh nonce, value -5 below the declared minimum.
            tickFreshHold(2_520L)
            assertTrue(robot.isCalibrationModeArmed)
            assertTuningResult(3L, "INVALID_VALUE", 3.2)
            assertEquals(3.2, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertNeutral()
            assertEquals(canonicalBefore, canonicalTuningSnapshot(project))

            lifecycle.gamepad1.left_stick_y = 0.0f
            robot.disableCalibrationMode()
            assertFalse(robot.isCalibrationModeArmed)
            assertFalse(robot.isCalibrationNeutralOutputHoldActive)
            robot.resetPose(Pose2d())
            lifecycle.gamepad1.y = true
            RobotClock.useMockTime(2_540L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            lifecycle.gamepad1.y = false
            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            RobotClock.useMockTime(2_560L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertEquals(0.0, robot.store.state.drive.headingLockTargetRadians ?: Double.NaN, 1e-4)
            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 0.0, -0.10)
            RobotClock.useMockTime(2_580L)
            awaitImuSample(robot, -0.10)
            lifecycle.tick()
            RobotClock.useMockTime(2_600L)
            awaitImuSample(robot, -0.10)
            lifecycle.tick()

            // Independent P-only oracle after rejected requests: 3.2 * (0 - -0.10) = +0.32 rad/s.
            val drive = robot.store.state.drive
            assertTrue(drive.imuMeasurementsValid)
            assertEquals(0.0, robot.store.state.tuning.drive.headingGains.kI, 1e-9)
            assertEquals(0.0, robot.store.state.tuning.drive.headingGains.kD, 1e-9)
            assertEquals(-0.10, drive.poseEstimator.estimatedPose.heading.radians, 1e-3)
            assertEquals(0.0, drive.xVelocityMetersPerSecond, 1e-9)
            assertEquals(0.0, drive.yVelocityMetersPerSecond, 1e-9)
            assertEquals(0.32, drive.angularVelocityRadiansPerSecond, 1e-3)
            println("TUNING_OUTPUT omega=" + drive.angularVelocityRadiansPerSecond)
            val right = robotDouble.fr.power
            assertTrue("Positive CCW effort below saturation", right > 0.01 && right < 1.0)
            assertEquals(right, robotDouble.rr.power, 1e-3)
            assertEquals(-right, robotDouble.fl.power, 1e-3)
            assertEquals(-right, robotDouble.rl.power, 1e-3)

            // Stop while the tuned controller is actively producing output.
            val finalAck = NT4Server.getString(rootTopic + "/Acknowledgement", "")
            lifecycle.stop()
            assertFalse(lifecycle.isStarted)
            assertEquals(com.areslib.sim.opmode.SimOpModeState.DISABLED, lifecycle.publishedState)
            assertNeutral()
            assertEquals(null, manager.localOverlayPersistenceFailure)
            // close() drains the accepted experimental overlay; it must not rewrite any canonical file.
            val overlay = com.areslib.tuning.TuningProfileDocumentCodec.decode(
                File(cwd, ".ares/local/tuning/runtime.arestuning").readText(),
                GeneratedAresTuningConfig.metadata().declarations)
            assertEquals(com.areslib.tuning.TuningProfileAuthority.LOCAL_EXPERIMENTAL, overlay.authority)
            assertEquals(GeneratedAresTuningConfig.CANONICAL_PROFILE_UID, overlay.baseProfileUid)
            assertEquals(3.2, overlay.values.single { it.parameterUid == "ftc.drive.heading.kp" }.value.doubleValue!!, 1e-9)
            val overlayBytes = File(cwd, ".ares/local/tuning/runtime.arestuning").readBytes()

            RobotClock.useMockTime(3_200L)
            deliverStudioTuningFrames(server, wire, "late")
            // The simulator NT4 server remains owned by this test after OpMode stop. Polling the
            // retained manager additionally verifies its close fence, beyond the stopped lifecycle guard.
            assertEquals(5.0, NT4Server.getDouble(rootTopic + "/RequestNonce", -1.0), 1e-9)
            lifecycle.gamepad1.left_stick_y = -1.0f
            lifecycle.tick()
            manager.update()
            assertEquals(3.2, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertEquals(finalAck, NT4Server.getString(rootTopic + "/Acknowledgement", ""))
            assertEquals(3.0, NT4Server.getDouble(rootTopic + "/ProcessedNonce", -1.0), 1e-9)
            assertNeutral()
            assertTrue(overlayBytes.contentEquals(File(cwd, ".ares/local/tuning/runtime.arestuning").readBytes()))
            assertEquals(canonicalBefore, canonicalTuningSnapshot(project))
        } finally {
            try { lifecycle.stop() }
            finally { connection?.let { receiver?.onClose(it, 1000, "owned test connection", false) } }
        }
    }
""".trimIndent()

internal fun consumerLeaseRecoveryBehavior(gain: Double): String = """
    @Test
    fun `calibration lease expiry and recovery gates live heading parameter in generated consumer`() {
        RobotClock.useMockTime(1_000L)
        val robotDouble = MecanumRobotDouble()
        val lifecycle = requireNotNull(SimOpModeRunner.createOpModeInstance(null, ARESStarterTeleOp::class.java.name))
        var receiver: NT4Server? = null
        var connection: org.java_websocket.WebSocket? = null
        val cwd = File("").canonicalFile
        val project = if (File(cwd, ".ares/project.json").isFile) cwd else cwd.parentFile
        assertTrue(File(project, ".ares/project.json").isFile)
        val canonicalBefore = canonicalTuningSnapshot(project)
        val rootTopic = "Tuning/Parameters/ftc.drive.heading.kp"
        val motors = listOf(robotDouble.fl, robotDouble.fr, robotDouble.rl, robotDouble.rr)
        fun assertNeutral() = assertTrue("Every drive output must be neutral", motors.all { abs(it.power) < 1e-9 })
        try {
            lifecycle.initialize(robotDouble.hardwareMap)
            val robot = FtcBaseRobot.activeInstance as FtcMecanumRobot
            val manager = requireNotNull(robot.tuningManager)
            val server = requireNotNull(NT4Server.getInstance())
            val wire = consumerWireProxy(org.java_websocket.WebSocket::class.java)
            receiver = server
            connection = wire
            server.onOpen(wire, consumerWireProxy(org.java_websocket.handshake.ClientHandshake::class.java))
            lifecycle.gamepad1.id = 1
            lifecycle.tick()
            lifecycle.start()
            robot.isLiveTuningEnabled = true
            assertFalse(robot.isCalibrationModeArmed)
            assertEquals($gain, robot.store.state.tuning.drive.headingGains.kP, 1e-9)

            // Step 1: Establish fresh calibration STOP session with local opt-in + new token + advancing lease.
            // With competing drive joystick command, prove neutral output ownership.
            lifecycle.gamepad1.left_stick_y = -1.0f
            RobotClock.useMockTime(1_020L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertTrue("Competing joystick drives before calibration mode is established", motors.any { abs(it.power) > 0.01 })

            robot.enableCalibrationMode()
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-1")
            NT4Server.publishTopic("SysId/EnableLease", 1.0)
            NT4Server.publishTopic("SysId/Command", "STOP")
            RobotClock.useMockTime(1_040L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertTrue(robot.isCalibrationModeArmed)
            assertTrue(robot.isCalibrationNeutralOutputHoldActive)
            assertNeutral()

            var leaseSequence = 1.0
            fun tickFreshHold(time: Long) {
                RobotClock.useMockTime(time)
                NT4Server.publishTopic("SysId/EnableLease", ++leaseSequence)
                awaitImuSample(robot, 0.0)
                lifecycle.tick()
                assertTrue(robot.isCalibrationModeArmed)
                assertTrue(robot.isCalibrationNeutralOutputHoldActive)
                assertNeutral()
            }
            for (time in listOf(1_060L, 1_080L, 1_100L)) {
                tickFreshHold(time)
            }

            // Step 2: Withhold lease updates across the documented 500 ms expiry boundary.
            // Last lease was at 1,100 ms. At 1,620 ms (520 ms delta > 500 ms timeout), lease is expired.
            // Tuning poll throttle from 1,040 ms is also satisfied (580 ms delta >= 500 ms).
            // Deliver a valid heading request (value 3.6, nonce 2L) on this eligible tick.
            // Prove the same frame processes lease expiry before tuning authorization, rejects with
            // SESSION_NOT_ARMED, and leaves confirmed gain and canonical files unchanged.
            RobotClock.useMockTime(1_620L)
            awaitImuSample(robot, 0.0)
            deliverStudioTuningFrames(server, wire, "expired")
            lifecycle.tick()

            assertFalse(robot.isCalibrationModeArmed)
            assertFalse(robot.isCalibrationNeutralOutputHoldActive)
            assertEquals("ENABLE_LEASE_EXPIRED", NT4Server.getString("SysId/Error", ""))
            assertTuningResult(2L, "SESSION_NOT_ARMED", $gain)
            assertEquals($gain, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertEquals(canonicalBefore, canonicalTuningSnapshot(project))

            // Step 3: Show retained/replayed token/lease data cannot accidentally rearm.
            // 3a. Replaying the previous token cannot rearm.
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-1")
            NT4Server.publishTopic("SysId/EnableLease", 5.0)
            NT4Server.publishTopic("SysId/Command", "STOP")
            RobotClock.useMockTime(1_640L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertFalse("Replaying old token must not rearm", robot.isCalibrationModeArmed)

            // 3b. Stale lease sequence cannot rearm even with a new token.
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-2")
            NT4Server.publishTopic("SysId/EnableLease", 4.0)
            NT4Server.publishTopic("SysId/Command", "STOP")
            RobotClock.useMockTime(1_660L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertFalse("Stale lease sequence must not rearm", robot.isCalibrationModeArmed)

            // 3c. Non-STOP command cannot rearm even with new token and fresh lease.
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-2")
            NT4Server.publishTopic("SysId/EnableLease", 10.0)
            NT4Server.publishTopic("SysId/Command", "START_LINEAR_DRIVE")
            RobotClock.useMockTime(1_680L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertFalse("Non-STOP command must not rearm", robot.isCalibrationModeArmed)

            // Perform documented fresh rearm procedure: new token, fresh lease, STOP command.
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-2")
            NT4Server.publishTopic("SysId/EnableLease", 10.0)
            NT4Server.publishTopic("SysId/Command", "STOP")
            leaseSequence = 10.0
            RobotClock.useMockTime(1_700L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertTrue("Fresh token and lease with STOP command must rearm", robot.isCalibrationModeArmed)
            assertTrue(robot.isCalibrationNeutralOutputHoldActive)
            assertNeutral()
            assertEquals("", NT4Server.getString("SysId/Error", ""))

            // Advance lease until next tuning poll is eligible (1,620 ms + 500 ms = 2,120 ms).
            while (RobotClock.currentTimeMillis() < 2_100L) {
                tickFreshHold(RobotClock.currentTimeMillis() + 20L)
            }

            // Apply valid request with new nonce (3.2, nonce 4L).
            RobotClock.useMockTime(2_140L)
            deliverStudioTuningFrames(server, wire, "rearm-apply")
            tickFreshHold(2_140L)
            assertTrue(robot.isCalibrationModeArmed)
            assertTuningResult(4L, "APPLIED", 3.2)
            assertEquals(3.2, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertTrue(robot.isCalibrationNeutralOutputHoldActive)
            assertNeutral()

            // Transition back to normal control and demonstrate intended controller effect.
            lifecycle.gamepad1.left_stick_y = 0.0f
            robot.disableCalibrationMode()
            assertFalse(robot.isCalibrationModeArmed)
            assertFalse(robot.isCalibrationNeutralOutputHoldActive)
            robot.resetPose(Pose2d())
            lifecycle.gamepad1.y = true
            RobotClock.useMockTime(2_160L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            lifecycle.gamepad1.y = false
            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            RobotClock.useMockTime(2_180L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertEquals(0.0, robot.store.state.drive.headingLockTargetRadians ?: Double.NaN, 1e-4)

            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 0.0, -0.10)
            RobotClock.useMockTime(2_200L)
            awaitImuSample(robot, -0.10)
            lifecycle.tick()
            RobotClock.useMockTime(2_220L)
            awaitImuSample(robot, -0.10)
            lifecycle.tick()

            // Independent P-only oracle: error = 0 - (-0.10) = +0.10 rad, omega = 3.2 * 0.10 = 0.32 rad/s.
            val drive = robot.store.state.drive
            assertTrue(drive.imuMeasurementsValid)
            assertEquals(0.0, robot.store.state.tuning.drive.headingGains.kI, 1e-9)
            assertEquals(0.0, robot.store.state.tuning.drive.headingGains.kD, 1e-9)
            assertEquals(-0.10, drive.poseEstimator.estimatedPose.heading.radians, 1e-3)
            assertEquals(0.0, drive.xVelocityMetersPerSecond, 1e-9)
            assertEquals(0.0, drive.yVelocityMetersPerSecond, 1e-9)
            assertEquals(0.32, drive.angularVelocityRadiansPerSecond, 1e-3)
            println("TUNING_OUTPUT omega=" + drive.angularVelocityRadiansPerSecond)
            val right = robotDouble.fr.power
            assertTrue("Positive CCW effort below saturation", right > 0.01 && right < 1.0)
            assertEquals(right, robotDouble.rr.power, 1e-3)
            assertEquals(-right, robotDouble.fl.power, 1e-3)
            assertEquals(-right, robotDouble.rl.power, 1e-3)

            // Step 4: Stop while output is active, prove stale session traffic cannot reactivate output.
            val finalAck = NT4Server.getString(rootTopic + "/Acknowledgement", "")
            lifecycle.stop()
            assertFalse(lifecycle.isStarted)
            assertEquals(com.areslib.sim.opmode.SimOpModeState.DISABLED, lifecycle.publishedState)
            assertNeutral()
            assertEquals(null, manager.localOverlayPersistenceFailure)
            val overlay = com.areslib.tuning.TuningProfileDocumentCodec.decode(
                File(cwd, ".ares/local/tuning/runtime.arestuning").readText(),
                GeneratedAresTuningConfig.metadata().declarations)
            assertEquals(com.areslib.tuning.TuningProfileAuthority.LOCAL_EXPERIMENTAL, overlay.authority)
            assertEquals(GeneratedAresTuningConfig.CANONICAL_PROFILE_UID, overlay.baseProfileUid)
            assertEquals(3.2, overlay.values.single { it.parameterUid == "ftc.drive.heading.kp" }.value.doubleValue!!, 1e-9)
            val overlayBytes = File(cwd, ".ares/local/tuning/runtime.arestuning").readBytes()

            RobotClock.useMockTime(3_000L)
            deliverStudioTuningFrames(server, wire, "late")
            assertEquals(5.0, NT4Server.getDouble(rootTopic + "/RequestNonce", -1.0), 1e-9)
            lifecycle.gamepad1.left_stick_y = -1.0f
            lifecycle.tick()
            manager.update()
            assertEquals(3.2, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertEquals(finalAck, NT4Server.getString(rootTopic + "/Acknowledgement", ""))
            assertEquals(4.0, NT4Server.getDouble(rootTopic + "/ProcessedNonce", -1.0), 1e-9)
            assertNeutral()
            assertTrue(overlayBytes.contentEquals(File(cwd, ".ares/local/tuning/runtime.arestuning").readBytes()))
            assertEquals(canonicalBefore, canonicalTuningSnapshot(project))
        } finally {
            try { lifecycle.stop() }
            finally { connection?.let { receiver?.onClose(it, 1000, "owned test connection", false) } }
        }
    }
""".trimIndent()

internal fun consumerTuningHelpers(): String = """
    private fun assertTuningResult(nonce: Long, result: String, gain: Double) {
        val root = "Tuning/Parameters/ftc.drive.heading.kp"
        val ack = TuningAcknowledgementCodec.decode(NT4Server.getString(root + "/Acknowledgement", ""))
        assertNotNull(ack)
        assertEquals(nonce, ack!!.nonce)
        assertEquals(result, ack.result)
        assertEquals(result, NT4Server.getString(root + "/LastResult", ""))
        assertEquals(nonce.toDouble(), NT4Server.getDouble(root + "/ProcessedNonce", -1.0), 1e-9)
        assertEquals(gain, NT4Server.getDouble(root + "/Current", -1.0), 1e-9)
        println("TUNING_ACK " + TuningAcknowledgementCodec.encode(ack) + " current=" + NT4Server.getDouble(root + "/Current", -1.0))
    }

    private fun canonicalTuningSnapshot(project: File): Map<String, String> {
        val root = File(project, ".ares")
        return root.walkTopDown().filter { it.isFile && !it.relativeTo(root).invariantSeparatorsPath.startsWith("local/") }
            .associate { it.relativeTo(root).invariantSeparatorsPath to java.util.Base64.getEncoder().encodeToString(it.readBytes()) }
    }

    private fun deliverStudioTuningFrames(server: NT4Server, connection: org.java_websocket.WebSocket, case: String) {
        val resource = requireNotNull(javaClass.getResourceAsStream("/tuning-wire/" + case + ".frames"))
        resource.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val bytes = java.util.Base64.getDecoder().decode(line.substring(2))
                when (line.substring(0, 2)) {
                    "T|" -> server.onMessage(connection, String(bytes, Charsets.UTF_8))
                    "B|" -> server.onMessage(connection, java.nio.ByteBuffer.wrap(bytes))
                    else -> error("Unexpected captured frame")
                }
            }
        }
    }

    private fun <T> consumerWireProxy(type: Class<T>): T = type.cast(java.lang.reflect.Proxy.newProxyInstance(
        type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "OwnedConsumerTuningConnection"
                "isOpen" -> true
                "hasBufferedData" -> false
                else -> null
            }
        })
""".trimIndent()
