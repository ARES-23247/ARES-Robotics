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
            val replyDir = File(project, "simulator/build/tuning-wire-reply")
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

            // Step 1: Studio explicitly arms; generated robot acknowledges fresh STOP session and owns neutral output.
            robot.enableCalibrationMode()
            deliverStudioTuningFrames(server, wire, "wire-arm")
            RobotClock.useMockTime(1_040L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            server.flush()
            saveReplyFrames(server, wire, replyDir, "wire-arm-reply")
            assertTrue(robot.isCalibrationModeArmed)
            assertTrue(robot.isCalibrationNeutralOutputHoldActive)
            assertNeutral() // Joystick is still nonzero, so this is actual inhibition.
            assertTrue(NT4Server.getBoolean("SysId/Armed", false))
            val firstEnableToken = NT4Server.getString("SysId/EnableToken", "")
            assertTrue(firstEnableToken.startsWith("ares-"))

            var leaseSeq = 1
            fun tickFreshHold(time: Long, nextLease: Boolean = false) {
                RobotClock.useMockTime(time)
                if (nextLease) {
                    deliverStudioTuningFrames(server, wire, "wire-lease-" + (++leaseSeq))
                }
                awaitImuSample(robot, 0.0)
                lifecycle.tick()
                assertTrue(robot.isCalibrationModeArmed)
                assertTrue(robot.isCalibrationNeutralOutputHoldActive)
                assertNeutral()
            }
            fun holdUntil(time: Long) {
                while (RobotClock.currentTimeMillis() < time) {
                    val nextTime = RobotClock.currentTimeMillis() + 20L
                    val deliverHeartbeat = nextTime % 200L == 40L
                    tickFreshHold(nextTime, deliverHeartbeat)
                }
            }

            // Step 2: Valid typed heading request accepted under established authorization.
            holdUntil(1_500L)
            RobotClock.useMockTime(1_520L)
            deliverStudioTuningFrames(server, wire, "apply")
            tickFreshHold(1_520L)
            assertTuningResult(2L, "APPLIED", 3.2)
            assertEquals(3.2, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertTrue(robot.isCalibrationNeutralOutputHoldActive)
            assertNeutral()
            val appliedAck = NT4Server.getString(rootTopic + "/Acknowledgement", "")

            // Replay same nonce with conflicting value 4.0: rejected without changing gain.
            holdUntil(2_000L)
            RobotClock.useMockTime(2_020L)
            deliverStudioTuningFrames(server, wire, "replay")
            tickFreshHold(2_020L)
            assertTrue(robot.isCalibrationModeArmed)
            assertEquals(appliedAck, NT4Server.getString(rootTopic + "/Acknowledgement", ""))
            assertTuningResult(2L, "APPLIED", 3.2)
            assertEquals(3.2, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertNeutral()

            // Invalid value -5.0 below declared minimum: rejected.
            holdUntil(2_500L)
            RobotClock.useMockTime(2_520L)
            deliverStudioTuningFrames(server, wire, "invalid")
            tickFreshHold(2_520L)
            assertTrue(robot.isCalibrationModeArmed)
            assertTuningResult(3L, "INVALID_VALUE", 3.2)
            assertEquals(3.2, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertNeutral()
            assertEquals(canonicalBefore, canonicalTuningSnapshot(project))

            // Step 3: Studio explicitly disarms; its real STOP/token revocation reaches the robot.
            RobotClock.useMockTime(2_540L)
            deliverStudioTuningFrames(server, wire, "wire-disarm")
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            server.flush()
            saveReplyFrames(server, wire, replyDir, "wire-disarm-reply")
            assertFalse(robot.isCalibrationModeArmed)
            assertFalse(robot.isCalibrationNeutralOutputHoldActive)
            assertEquals("ENABLE_TOKEN_CHANGED", NT4Server.getString("SysId/Error", ""))
            assertNeutral() // Neutralized during disarm transition frame.
            assertFalse(NT4Server.getBoolean("SysId/Armed", true))
            assertEquals("", NT4Server.getString("SysId/EnableToken", "missing"))
            val disarmedLease = NT4Server.getDouble("SysId/EnableLease", -1.0)

            // Following frames (2_560L..3_000L): manual repositioning resumes with held joystick.
            for (time in 2_560L..3_000L step 20L) {
                RobotClock.useMockTime(time)
                awaitImuSample(robot, 0.0)
                lifecycle.tick()
                assertTrue(robot.store.state.drive.imuMeasurementsValid)
                assertFalse(robot.isCalibrationModeArmed)
                assertEquals(disarmedLease, NT4Server.getDouble("SysId/EnableLease", -1.0), 0.0)
                assertTrue("Manual repositioning active while disarmed", motors.any { abs(it.power) > 0.01 })
            }

            // Unarmed tuning request is rejected with SESSION_NOT_ARMED.
            // 3_020L is eligible for TuningManager update (3_020 - 2_520 = 500 ms).
            RobotClock.useMockTime(3_020L)
            deliverStudioTuningFrames(server, wire, "disarmed")
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertTrue(robot.store.state.drive.imuMeasurementsValid)
            assertTuningResult(4L, "SESSION_NOT_ARMED", 3.2)
            assertEquals(3.2, robot.store.state.tuning.drive.headingGains.kP, 1e-9)

            // Step 4: Explicit fresh arming recovers and a new nonce succeeds.
            RobotClock.useMockTime(3_040L)
            deliverStudioTuningFrames(server, wire, "wire-rearm")
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertTrue(robot.isCalibrationModeArmed)
            assertTrue(robot.isCalibrationNeutralOutputHoldActive)
            assertNeutral()

            assertTrue(NT4Server.getBoolean("SysId/Armed", false))
            val secondEnableToken = NT4Server.getString("SysId/EnableToken", "")
            assertTrue(secondEnableToken.startsWith("ares-") && secondEnableToken != firstEnableToken)

            // Keep lease fresh until next poll eligibility at 3_520L (3_020 + 500 ms).
            // Leases 10.0 and 11.0 delivered at 3_240L and 3_440L.
            for (time in 3_060L..3_500L step 20L) {
                RobotClock.useMockTime(time)
                if (time == 3_240L) deliverStudioTuningFrames(server, wire, "wire-lease-10")
                if (time == 3_440L) deliverStudioTuningFrames(server, wire, "wire-lease-11")
                awaitImuSample(robot, 0.0)
                lifecycle.tick()
                assertTrue(robot.isCalibrationModeArmed)
                assertTrue(robot.isCalibrationNeutralOutputHoldActive)
                assertNeutral()
            }

            saveReplyFrames(server, wire, replyDir, "wire-ready-reply")
            RobotClock.useMockTime(3_520L)
            deliverStudioTuningFrames(server, wire, "wire-rearm-apply")
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            server.flush()
            saveReplyFrames(server, wire, replyDir, "wire-rearm-apply-reply")
            assertTuningResult(5L, "APPLIED", 2.6)
            assertEquals(2.6, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertNeutral()

            // Capture a chronological operator disarm from the just-applied session.
            deliverStudioTuningFrames(server, wire, "wire-disarm")
            RobotClock.useMockTime(3_540L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            saveReplyFrames(server, wire, replyDir, "wire-final-disarm-reply")
            assertFalse(robot.isCalibrationModeArmed)
            assertFalse(NT4Server.getBoolean("SysId/Armed", true))
            assertNeutral()

            // Return to normal control and verify heading controller response.
            lifecycle.gamepad1.left_stick_y = 0.0f
            robot.disableCalibrationMode()
            assertFalse(robot.isCalibrationModeArmed)
            assertFalse(robot.isCalibrationNeutralOutputHoldActive)
            robot.resetPose(Pose2d())
            lifecycle.gamepad1.y = true
            RobotClock.useMockTime(3_560L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            lifecycle.gamepad1.y = false
            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            RobotClock.useMockTime(3_580L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertEquals(0.0, robot.store.state.drive.headingLockTargetRadians ?: Double.NaN, 1e-4)
            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 0.0, -0.10)
            RobotClock.useMockTime(3_600L)
            awaitImuSample(robot, -0.10)
            lifecycle.tick()
            RobotClock.useMockTime(3_620L)
            awaitImuSample(robot, -0.10)
            lifecycle.tick()

            // Independent P-only oracle after the recovered transaction: 2.6 * (0 - -0.10) = +0.26 rad/s.
            val drive = robot.store.state.drive
            assertTrue(drive.imuMeasurementsValid)
            assertEquals(0.0, robot.store.state.tuning.drive.headingGains.kI, 1e-9)
            assertEquals(0.0, robot.store.state.tuning.drive.headingGains.kD, 1e-9)
            assertEquals(-0.10, drive.poseEstimator.estimatedPose.heading.radians, 1e-3)
            assertEquals(0.0, drive.xVelocityMetersPerSecond, 1e-9)
            assertEquals(0.0, drive.yVelocityMetersPerSecond, 1e-9)
            assertEquals(0.26, drive.angularVelocityRadiansPerSecond, 1e-3)
            println("TUNING_OUTPUT omega=" + drive.angularVelocityRadiansPerSecond)
            val right = robotDouble.fr.power
            assertTrue("Positive CCW effort below saturation", right > 0.01 && right < 1.0)
            assertEquals(right, robotDouble.rr.power, 1e-3)
            assertEquals(-right, robotDouble.fl.power, 1e-3)
            assertEquals(-right, robotDouble.rl.power, 1e-3)

            // Keep the accepted gain/output evidence independent of Studio replay.
            File(replyDir, "controller-evidence.txt").writeText(
                "gain=" + robot.store.state.tuning.drive.headingGains.kP + "\n" +
                "heading=" + drive.poseEstimator.estimatedPose.heading.radians + "\n" +
                "omega=" + drive.angularVelocityRadiansPerSecond + "\n")
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
            assertEquals(2.6, overlay.values.single { it.parameterUid == "ftc.drive.heading.kp" }.value.doubleValue!!, 1e-9)
            val overlayBytes = File(cwd, ".ares/local/tuning/runtime.arestuning").readBytes()

            RobotClock.useMockTime(4_200L)
            deliverStudioTuningFrames(server, wire, "wire-late")
            // The simulator NT4 server remains owned by this test after OpMode stop. Polling the
            // retained manager additionally verifies its close fence, beyond the stopped lifecycle guard.
            assertEquals(6.0, NT4Server.getDouble(rootTopic + "/RequestNonce", -1.0), 1e-9)
            lifecycle.gamepad1.left_stick_y = -1.0f
            lifecycle.tick()
            manager.update()
            assertEquals(2.6, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertEquals(finalAck, NT4Server.getString(rootTopic + "/Acknowledgement", ""))
            assertEquals(5.0, NT4Server.getDouble(rootTopic + "/ProcessedNonce", -1.0), 1e-9)
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
            for (time in 1_060L..1_500L step 20L) {
                tickFreshHold(time)
            }

            // Keep real 20 ms robot frames and fresh sensor/gamepad input, but stop lease writes.
            // Last heartbeat: 1,500 ms. At exactly 2,000 ms the 500 ms lease is still valid.
            // The idle poll at 1,520 ms makes the first expired frame (2,020 ms) poll-eligible.
            for (time in 1_520L..2_000L step 20L) {
                RobotClock.useMockTime(time)
                awaitImuSample(robot, 0.0)
                lifecycle.tick()
                assertTrue(robot.store.state.drive.imuMeasurementsValid)
                assertTrue("Lease remains valid through exactly 500 ms", robot.isCalibrationModeArmed)
                assertTrue(robot.isCalibrationNeutralOutputHoldActive)
                assertEquals(leaseSequence, NT4Server.getDouble("SysId/EnableLease", -1.0), 0.0)
                assertNeutral()
            }
            println("TUNING_LEASE at=2000 ageMs=500 armed=true neutral=true")

            // First expired frame must disarm before the same-frame tuning request is authorized.
            RobotClock.useMockTime(2_020L)
            awaitImuSample(robot, 0.0)
            deliverStudioTuningFrames(server, wire, "expired")
            lifecycle.tick()

            assertTrue(robot.store.state.drive.imuMeasurementsValid)
            assertNeutral()
            println("TUNING_LEASE at=2020 ageMs=520 armed=" + robot.isCalibrationModeArmed + " neutral=true")
            assertFalse(robot.isCalibrationModeArmed)
            assertFalse(robot.isCalibrationNeutralOutputHoldActive)
            assertEquals("ENABLE_LEASE_EXPIRED", NT4Server.getString("SysId/Error", ""))
            assertTuningResult(2L, "SESSION_NOT_ARMED", $gain)
            assertEquals($gain, robot.store.state.tuning.drive.headingGains.kP, 1e-9)
            assertEquals(canonicalBefore, canonicalTuningSnapshot(project))

            // Step 3: Show retained/replayed token/lease data cannot accidentally rearm.
            // 3a. Replaying the previous token cannot rearm.
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-1")
            NT4Server.publishTopic("SysId/EnableLease", leaseSequence + 1.0)
            NT4Server.publishTopic("SysId/Command", "STOP")
            RobotClock.useMockTime(2_040L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertFalse("Replaying old token must not rearm", robot.isCalibrationModeArmed)
            assertTrue(robot.store.state.drive.imuMeasurementsValid)
            assertTrue("Manual repositioning resumes on the frame after disarming",
                motors.any { abs(it.power) > 0.01 })
            assertTuningResult(2L, "SESSION_NOT_ARMED", $gain)
            println("TUNING_LEASE at=2040 armed=false manualOutput=true")

            // 3b. The last accepted lease cannot rearm even with a new token.
            // New sessions require a DIFFERENT valid sequence; they need not exceed the old session.
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-2")
            NT4Server.publishTopic("SysId/EnableLease", leaseSequence)
            NT4Server.publishTopic("SysId/Command", "STOP")
            RobotClock.useMockTime(2_060L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertFalse("Retained lease sequence must not rearm", robot.isCalibrationModeArmed)

            // 3c. Non-STOP command cannot rearm even with new token and fresh lease.
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-2")
            NT4Server.publishTopic("SysId/EnableLease", leaseSequence + 1.0)
            NT4Server.publishTopic("SysId/Command", "START_LINEAR_DRIVE")
            RobotClock.useMockTime(2_080L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertFalse("Non-STOP command must not rearm", robot.isCalibrationModeArmed)

            // Perform documented fresh rearm procedure: new token, fresh lease, STOP command.
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-2")
            NT4Server.publishTopic("SysId/EnableLease", leaseSequence + 1.0)
            NT4Server.publishTopic("SysId/Command", "STOP")
            leaseSequence += 1.0
            RobotClock.useMockTime(2_100L)
            awaitImuSample(robot, 0.0)
            lifecycle.tick()
            assertTrue("Fresh token and lease with STOP command must rearm", robot.isCalibrationModeArmed)
            assertTrue(robot.isCalibrationNeutralOutputHoldActive)
            assertNeutral()
            assertEquals("", NT4Server.getString("SysId/Error", ""))

            // Next poll is 2,020 + 500 = 2,520 ms. Maintain hold at every 20 ms frame.
            while (RobotClock.currentTimeMillis() < 2_500L) {
                tickFreshHold(RobotClock.currentTimeMillis() + 20L)
            }

            // Apply valid request with new nonce (3.2, nonce 4L).
            deliverStudioTuningFrames(server, wire, "rearm-apply")
            tickFreshHold(2_520L)
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

            RobotClock.useMockTime(3_200L)
            // Retained session controls and a late request must not restart a stopped OpMode.
            NT4Server.publishTopic("SysId/EnableToken", "consumer-live-2")
            NT4Server.publishTopic("SysId/EnableLease", ++leaseSequence)
            NT4Server.publishTopic("SysId/Command", "START_LINEAR_DRIVE")
            deliverStudioTuningFrames(server, wire, "lease-late")
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

