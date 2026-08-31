package com.ares.analytics.ui.input

import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.GamepadState
import com.ares.analytics.shared.models.League
import com.areslib.telemetry.schema.DesktopDriveProtocol
import com.areslib.telemetry.schema.DesktopDriveReceiverStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDriveInputPublisherTest {
    @Test
    fun `receiver acknowledgement prevents false armed session after accepted queue stalls`() {
        var nowMs = 1_000L
        val session = DesktopDriveFrameSession(sessionNonce = 44.0) { nowMs }
        session.markTransmitted()

        assertFalse(session.needsReceiverRehandshake(acknowledgementContractAvailable = true))
        session.observeReceiverAcknowledgement(receiverSession = 44L, receiverSequence = 0L)
        nowMs += RECEIVER_ACK_TIMEOUT_MS - 1L
        assertFalse(session.needsReceiverRehandshake(acknowledgementContractAvailable = true))
        nowMs += 1L
        assertTrue(session.needsReceiverRehandshake(acknowledgementContractAvailable = true))
    }

    @Test
    fun `receiver acknowledgement contract is mandatory before motion`() {
        var nowMs = 1_000L
        val session = DesktopDriveFrameSession(sessionNonce = 45.0) { nowMs }
        session.markTransmitted()
        nowMs += RECEIVER_ACK_STARTUP_TIMEOUT_MS * 2

        assertTrue(session.needsReceiverRehandshake(acknowledgementContractAvailable = false))
    }

    @Test
    fun `inactive simulator waiting for teleop does not churn neutral sessions`() {
        var nowMs = 1_000L
        val session = DesktopDriveFrameSession(sessionNonce = 46.0) { nowMs }
        session.markTransmitted()
        nowMs += RECEIVER_ACK_STARTUP_TIMEOUT_MS * 4

        assertFalse(
            session.needsReceiverRehandshake(
                acknowledgementContractAvailable = true,
                receiverStatusCode = DesktopDriveReceiverStatus.WAITING_FOR_FRAME.code,
            )
        )
    }

    @Test
    fun `keyboard motion requires only an armed local simulator surface`() {
        val keyboard = KeyboardDriveState().apply {
            enabled = true
            isWPressed = true
        }

        val inactiveSurface = desktopDriveIntent(
            keyboard,
            GamepadState(),
            controlSurfaceActive = false,
            league = League.FTC,
            isRedAlliance = true,
        )
        assertEquals(DesktopFieldDriveCommand(0.0, 0.0, 0.0), inactiveSurface.command)

        val armed = desktopDriveIntent(
            keyboard,
            GamepadState(),
            controlSurfaceActive = true,
            league = League.FTC,
            isRedAlliance = true,
        )
        assertEquals(DesktopFieldDriveCommand(0.0, 4.0, 0.0), armed.command)
    }

    @Test
    fun `publisher gap forces a fresh neutral handshake session`() {
        var now = 1_000L
        val session = DesktopDriveFrameSession(sessionNonce = 88.0, clockMs = { now })
        val intent = DesktopDriveIntent(
            command = DesktopFieldDriveCommand(1.0, 0.0, 0.0),
            modeFlags = desktopDriveModeFlags(isRedAlliance = true),
            actuationFlags = 0L,
        )

        session.frameFor(intent)
        session.markTransmitted()
        now += REHANDSHAKE_AFTER_GAP_MS - 1L
        assertEquals(REHANDSHAKE_AFTER_GAP_MS - 1L, session.successfulTransmissionAgeMs())
        assertTrue(!session.needsRehandshake())
        now += 1L
        assertEquals(REHANDSHAKE_AFTER_GAP_MS, session.successfulTransmissionAgeMs())
        assertTrue(session.needsRehandshake())

        val replacement = DesktopDriveFrameSession(sessionNonce = 89.0, clockMs = { now })
        assertEquals(listOf(0.0, 0.0, 0.0), replacement.frameFor(intent).slice(4..6))
    }

    @Test
    fun `first accepted frame is neutral before motion and mechanism flags`() {
        val intent = DesktopDriveIntent(
            command = DesktopFieldDriveCommand(1.0, 2.0, 3.0),
            modeFlags = desktopDriveModeFlags(isRedAlliance = true),
            actuationFlags = (1L shl 0) or (1L shl 6),
        )
        val session = DesktopDriveFrameSession(sessionNonce = 77.0, clockMs = { 1234L })

        val neutral = session.frameFor(intent).copyOf()
        assertEquals(listOf(0.0, 0.0, 0.0), neutral.slice(4..6))
        assertEquals(intent.modeFlags.toDouble(), neutral[7])

        repeat(NEUTRAL_HANDSHAKE_FRAME_COUNT) {
            assertEquals(listOf(0.0, 0.0, 0.0), session.frameFor(intent).slice(4..6))
            session.markTransmitted()
        }
        assertEquals(listOf(0.0, 0.0, 0.0), session.frameFor(intent).slice(4..6))
        session.observeReceiverAcknowledgement(receiverSession = 77L, receiverSequence = 4L)
        val active = session.frameFor(intent).copyOf()
        assertEquals(listOf(1.0, 2.0, 3.0), active.slice(4..6))
        assertEquals((intent.modeFlags or intent.actuationFlags).toDouble(), active[7])
        assertEquals(NEUTRAL_HANDSHAKE_FRAME_COUNT.toDouble(), active[2])
    }

    @Test
    fun `unaccepted transport attempt neither arms nor advances sequence`() {
        val session = DesktopDriveFrameSession(sessionNonce = 9.0, clockMs = { 50L })
        val intent = DesktopDriveIntent(
            command = DesktopFieldDriveCommand(4.0, 0.0, 0.0),
            modeFlags = desktopDriveModeFlags(isRedAlliance = false),
            actuationFlags = DesktopDriveProtocol.FLAG_FLYWHEEL,
        )

        val firstAttempt = session.frameFor(intent).copyOf()
        val retry = session.frameFor(intent).copyOf()

        assertEquals(0.0, firstAttempt[2])
        assertEquals(0.0, retry[2])
        assertEquals(0.0, retry[4])
        assertEquals(intent.modeFlags.toDouble(), retry[7])
    }

    @Test
    fun `connected gamepad drives directly while local simulator control is armed`() {
        val keyboard = KeyboardDriveState().apply {
            enabled = true
            useGamepad = true
        }
        val gamepad = GamepadState(
            connected = true,
            leftStickY = 1.0f,
            leftBumper = true,
            a = true,
        )

        val intent = desktopDriveIntent(
            keyboard,
            gamepad,
            controlSurfaceActive = true,
            league = League.FRC,
            isRedAlliance = false,
        )

        assertTrue(intent.command.vxMetersPerSecond > 0.0)
        assertTrue(intent.actuationFlags and DesktopDriveProtocol.FLAG_INTAKE != 0L)
        assertTrue(intent.actuationFlags and DesktopDriveProtocol.FLAG_BUTTON_A != 0L)
    }
}
