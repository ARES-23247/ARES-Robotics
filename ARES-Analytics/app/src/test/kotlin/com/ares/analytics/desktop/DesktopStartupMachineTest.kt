package com.ares.analytics.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue




class DesktopStartupMachineTest {
    @Test
    fun `happy path walks creating through settled to closed`() {
        val machine = DesktopStartupMachine()
        machine.transitionTo(DesktopStartupState.OPENED)
        machine.transitionTo(DesktopStartupState.PRESENTED)
        machine.transitionTo(DesktopStartupState.SETTLED)
        machine.beginClosing()
        machine.markClosed()
        assertEquals(DesktopStartupState.CLOSED, machine.state)
        assertTrue(machine.isTerminal)
    }

    @Test
    fun `illegal transitions fail loudly instead of being accepted`() {
        val machine = DesktopStartupMachine()
        assertFailsWith<IllegalArgumentException> {
            machine.transitionTo(DesktopStartupState.SETTLED)
        }
        machine.transitionTo(DesktopStartupState.OPENED)
        assertFailsWith<IllegalArgumentException> {
            // Settled requires passing through PRESENTED first.
            machine.transitionTo(DesktopStartupState.SETTLED)
        }
        assertFailsWith<IllegalArgumentException> {
            machine.transitionTo(DesktopStartupState.CREATING)
        }
    }

    @Test
    fun `settled window may be lost and recovered to presented`() {
        val machine = DesktopStartupMachine()
        machine.transitionTo(DesktopStartupState.OPENED)
        machine.transitionTo(DesktopStartupState.PRESENTED)
        machine.transitionTo(DesktopStartupState.SETTLED)
        assertTrue(machine.windowExpected)

        assertTrue(machine.recordWindowLoss(), "first loss still allows recovery")
        assertEquals(DesktopStartupState.WINDOW_LOST, machine.state)
        assertFalse(machine.windowExpected)

        machine.recordWindowRecovered(DesktopStartupState.PRESENTED)
        assertEquals(DesktopStartupState.PRESENTED, machine.state)
        assertTrue(machine.windowExpected)
    }

    @Test
    fun `recovery budget is enforced and resets only on a fully settled window`() {
        val machine = DesktopStartupMachine(maxRecoveryAttempts = 2)
        machine.transitionTo(DesktopStartupState.OPENED)
        machine.transitionTo(DesktopStartupState.PRESENTED)

        assertTrue(machine.recordWindowLoss())
        assertTrue(machine.recordWindowLoss(), "second loss is still within budget")
        assertFalse(machine.recordWindowLoss(), "third loss exceeds the recovery policy")

        machine.recordWindowRecovered(DesktopStartupState.PRESENTED)
        assertEquals(
            3,
            machine.attemptsUsed,
            "an intermediate recovery to PRESENTED keeps the budget accountable",
        )
        machine.transitionTo(DesktopStartupState.SETTLED)
        assertEquals(0, machine.attemptsUsed, "reaching SETTLED is the only full-health reset")
        assertTrue(machine.recordWindowLoss(), "budget resets after the window fully settled")
        assertEquals(1, machine.attemptsUsed)
    }

    @Test
    fun `recovery without an argument resumes the state the window was lost from`() {
        val machine = DesktopStartupMachine()
        machine.transitionTo(DesktopStartupState.OPENED)
        machine.transitionTo(DesktopStartupState.PRESENTED)
        machine.recordWindowLoss()
        machine.recordWindowRecovered()
        assertEquals(DesktopStartupState.PRESENTED, machine.state)

        machine.transitionTo(DesktopStartupState.SETTLED)
        machine.recordWindowLoss()
        machine.recordWindowRecovered()
        assertEquals(DesktopStartupState.SETTLED, machine.state)
        assertEquals(0, machine.attemptsUsed, "resuming SETTLED is full health and resets the budget")
    }

    @Test
    fun `opened and presented observations are idempotent and tolerate fallback ordering`() {
        val machine = DesktopStartupMachine()
        machine.observePresented() // startup fallback before any windowOpened event
        assertEquals(DesktopStartupState.PRESENTED, machine.state)
        machine.observeOpened() // late windowOpened after the fallback presented
        machine.observePresented() // duplicate observation
        assertEquals(DesktopStartupState.PRESENTED, machine.state)
        machine.transitionTo(DesktopStartupState.SETTLED)
        machine.observeOpened()
        machine.observePresented() // late events after settlement are no-ops
        assertEquals(DesktopStartupState.SETTLED, machine.state)
        machine.recordWindowLoss()
        machine.observePresented() // presentation observation resumes from WINDOW_LOST
        assertEquals(DesktopStartupState.PRESENTED, machine.state)
    }

    @Test
    fun `shutdown states ignore window loss and presentation observations`() {
        val machine = DesktopStartupMachine()
        machine.beginClosing()
        assertTrue(
            machine.recordWindowLoss(),
            "a loss recorded during shutdown must not demand termination",
        )
        assertEquals(DesktopStartupState.CLOSING, machine.state)
        assertEquals(0, machine.attemptsUsed)
        machine.observeOpened()
        machine.observePresented()
        assertEquals(DesktopStartupState.CLOSING, machine.state)
    }

    @Test
    fun `closing is reachable from every live state and idempotent`() {
        for (start in listOf(
            DesktopStartupState.CREATING,
            DesktopStartupState.OPENED,
            DesktopStartupState.PRESENTED,
            DesktopStartupState.SETTLED,
            DesktopStartupState.WINDOW_LOST,
        )) {
            val machine = DesktopStartupMachine()
            while (machine.state != start) {
                machine.transitionTo(
                    when (machine.state) {
                        DesktopStartupState.CREATING -> DesktopStartupState.OPENED
                        DesktopStartupState.OPENED -> DesktopStartupState.PRESENTED
                        else -> start
                    }
                )
            }
            machine.beginClosing()
            assertEquals(DesktopStartupState.CLOSING, machine.state, "from $start")
            machine.beginClosing()
            assertEquals(DesktopStartupState.CLOSING, machine.state, "second beginClosing is a no-op")
        }
    }
}

class DesktopPresentationPolicyTest {
    @Test
    fun `startup topmost releases only after the bounded interval`() {
        assertFalse(DesktopPresentationPolicy.shouldReleaseStartupTopmost(0L))
        assertFalse(DesktopPresentationPolicy.shouldReleaseStartupTopmost(WINDOW_STARTUP_TOPMOST_MS - 1L))
        assertTrue(DesktopPresentationPolicy.shouldReleaseStartupTopmost(WINDOW_STARTUP_TOPMOST_MS))
    }

    @Test
    fun `settled requires compose-owned topmost to have actually returned false`() {
        assertFalse(DesktopPresentationPolicy.isSettled(alwaysOnTop = true))
        assertTrue(DesktopPresentationPolicy.isSettled(alwaysOnTop = false))
    }

    @Test
    fun `opt-in capture stays inactive unless the environment requests it`() {
        assertFalse(DesktopPresentationPolicy.captureRequested(null))
        assertFalse(DesktopPresentationPolicy.captureRequested(""))
        assertFalse(DesktopPresentationPolicy.captureRequested("   "))
        assertTrue(DesktopPresentationPolicy.captureRequested("C:/tmp/startup.png"))
        assertFalse(DesktopPresentationPolicy.closeAfterCaptureRequested(null))
        assertTrue(DesktopPresentationPolicy.closeAfterCaptureRequested("true"))
    }
}
