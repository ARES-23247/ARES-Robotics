package com.areslib.sim.infra

import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JFrame
import javax.swing.JPanel

/**
 * Swing virtual driver station combining keyboard, physical-gamepad, and dashboard input.
 *
 * Swing callbacks own painting and keyboard state; the gamepad manager owns its polling thread.
 * Losing window focus clears held keys to prevent a stale drive command. Mode and web-input
 * properties delegate directly to [SimGamepadManager]. Construct only in graphical simulation; the
 * headless launcher does not require this window.
 */
class VirtualDriverStation(
    private val gamepadManager: SimGamepadManager,
    private val onClose: () -> Unit
) : JFrame("ARES Virtual Driver Station"), KeyListener {

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        preferredSize = Dimension(500, 380)
        isResizable = false
        
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                // Background
                g2d.color = Color(25, 25, 25)
                g2d.fillRect(0, 0, width, height)

                // Status Labels
                g2d.font = Font("Segoe UI", Font.BOLD, 14)
                g2d.color = if (gamepadManager.effectiveIsTeleopMode) Color(50, 200, 50) else Color(200, 50, 50)
                g2d.drawString("MODE: ${if (gamepadManager.effectiveIsTeleopMode) "TELEOP" else "AUTO (Path)"}", 20, 25)
                
                g2d.color = if (gamepadManager.appliedIsFieldCentric) Color(100, 200, 255) else Color(255, 200, 100)
                g2d.drawString("DRIVE: ${if (gamepadManager.appliedIsFieldCentric) "FIELD-CENTRIC" else "ROBOT-CENTRIC"}", 200, 25)

                g2d.color = if (gamepadManager.effectiveIsRedAlliance) Color(255, 100, 100) else Color(100, 150, 255)
                g2d.drawString("ALLIANCE: ${if (gamepadManager.effectiveIsRedAlliance) "RED" else "BLUE"}", 380, 25)

                g2d.color = Color(150, 150, 150)
                g2d.font = Font("Segoe UI", Font.PLAIN, 11)
                g2d.drawString("Toggles: [SPACE]=Mode, [C]=Drive, [R]=Alliance", 20, 45)
                
                g2d.color = if (gamepadManager.isGamepadConnected) Color(50, 200, 50) else Color(150, 150, 150)
                g2d.drawString("Gamepad: ${gamepadManager.gamepadName}", 20, 60)

                // Draw Gamepad Overlay
                drawGamepadOverlay(g2d)
            }
            
            private fun drawGamepadOverlay(g2d: Graphics2D) {
                val cx = width / 2
                val cy = height / 2 + 30
                
                // Gamepad Body
                g2d.color = Color(50, 50, 55)
                g2d.fillRoundRect(cx - 160, cy - 80, 320, 160, 80, 80)
                g2d.fillRoundRect(cx - 180, cy - 20, 100, 120, 50, 50) // Left handle
                g2d.fillRoundRect(cx + 80, cy - 20, 100, 120, 50, 50)  // Right handle

                // Left Bumper (Intake) - SHIFT / LB
                val isLbActive = gamepadManager.appliedIsIntaking
                g2d.color = if (isLbActive) Color(100, 200, 255) else Color(30, 30, 35)
                g2d.fillRoundRect(cx - 140, cy - 100, 80, 30, 15, 15)
                g2d.color = if (isLbActive) Color.BLACK else Color.WHITE
                g2d.font = Font("Segoe UI", Font.BOLD, 12)
                g2d.drawString("LB / SHIFT", cx - 130, cy - 80)
                g2d.drawString("INTAKE", cx - 120, cy - 110)

                // Right Bumper (Flywheel) - F / RB
                val isRbActive = gamepadManager.appliedIsFlywheelOn
                g2d.color = if (isRbActive) Color(255, 200, 50) else Color(30, 30, 35)
                g2d.fillRoundRect(cx + 60, cy - 100, 80, 30, 15, 15)
                g2d.color = if (isRbActive) Color.BLACK else Color.WHITE
                g2d.drawString("RB / F", cx + 75, cy - 80)
                g2d.drawString("FLYWHEEL", cx + 70, cy - 110)

                // Right Trigger (Transfer/Shoot) - ENTER / RT
                val isRtActive = gamepadManager.effectiveIsTransferring
                g2d.color = if (isRtActive) Color(255, 100, 100) else Color(30, 30, 35)
                g2d.fillRoundRect(cx + 60, cy - 140, 80, 35, 15, 15)
                g2d.color = if (isRtActive) Color.BLACK else Color.WHITE
                g2d.drawString("RT / ENTER", cx + 65, cy - 118)
                g2d.drawString("SHOOT", cx + 80, cy - 150)

                // Left Stick (WASD)
                var lxOffset = (gamepadManager.gamepadLx * 20).toInt()
                var lyOffset = (gamepadManager.gamepadLy * 20).toInt()
                if (gamepadManager.pressedKeys.contains(KeyEvent.VK_A)) lxOffset = -20
                if (gamepadManager.pressedKeys.contains(KeyEvent.VK_D)) lxOffset = 20
                if (gamepadManager.pressedKeys.contains(KeyEvent.VK_W)) lyOffset = -20
                if (gamepadManager.pressedKeys.contains(KeyEvent.VK_S)) lyOffset = 20

                g2d.color = Color(30, 30, 35)
                g2d.fillOval(cx - 120, cy - 40, 70, 70) // Base
                g2d.color = Color(120, 130, 140)
                g2d.fillOval(cx - 105 + lxOffset, cy - 25 + lyOffset, 40, 40) // Stick
                g2d.color = Color.WHITE
                g2d.drawString("WASD", cx - 102, cy + 50)

                // Right Stick (QE)
                var rxOffset = (gamepadManager.gamepadRx * 20).toInt()
                var ryOffset = (gamepadManager.gamepadRy * 20).toInt()
                if (gamepadManager.pressedKeys.contains(KeyEvent.VK_Q)) rxOffset = -20
                if (gamepadManager.pressedKeys.contains(KeyEvent.VK_E)) rxOffset = 20

                g2d.color = Color(30, 30, 35)
                g2d.fillOval(cx + 50, cy + 10, 70, 70) // Base
                g2d.color = Color(140, 120, 120)
                g2d.fillOval(cx + 65 + rxOffset, cy + 25 + ryOffset, 40, 40) // Stick
                g2d.color = Color.WHITE
                g2d.drawString("Q / E", cx + 70, cy + 100)

                // Status info
                g2d.font = Font("Segoe UI", Font.BOLD, 11)
                g2d.color = Color(80, 80, 80)
                g2d.drawString("SHIFT=Intake  F=Flywheel  ENTER=Shoot", cx - 130, cy + 120)
            }
        }
        
        add(panel)
        pack()
        setLocationRelativeTo(null)
        
        isFocusable = true
        focusableWindowState = true
        addKeyListener(this)
        isAlwaysOnTop = true
        
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowOpened(e: java.awt.event.WindowEvent?) {
                requestFocus()
            }
            override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                gamepadManager.pressedKeys.clear()
            }
            override fun windowClosing(e: java.awt.event.WindowEvent?) {
                onClose()
            }
        })

        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                gamepadManager.pressedKeys.clear()
            }
        })

        gamepadManager.startPolling { repaint() }
    }

    /** Unused: controls respond to press/release transitions rather than typed characters. */
    override fun keyTyped(e: KeyEvent?) {}

    /** Forwards a non-null key press to the gamepad model and schedules repaint. */
    override fun keyPressed(e: KeyEvent?) {
        e?.let { gamepadManager.handleKeyPressed(it.keyCode) { repaint() } }
    }

    /** Forwards a non-null key release to the gamepad model and schedules repaint. */
    override fun keyReleased(e: KeyEvent?) {
        e?.let { gamepadManager.handleKeyReleased(it.keyCode) { repaint() } }
    }

}
