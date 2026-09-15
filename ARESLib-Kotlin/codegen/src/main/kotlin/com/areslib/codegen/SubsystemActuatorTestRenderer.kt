package com.areslib.codegen

import com.areslib.subsystem.*

/** Behavioral test source for actuator writes, followers, and fault-neutral recovery. */
internal object SubsystemActuatorTestRenderer {
    fun renderAssertions(document: SubsystemDocument, firstActuator: SubsystemHardwareDocument?): String = firstActuator?.let { device ->
            val command = "io.${device.commandName()}"
            val observed = "io.${device.hardwareId}Command"
            val neutral = device.invertedExpression(requireNotNull(device.safeOutput).kotlinDouble())
            val validCommand = when (device.kind) {
                SubsystemHardwareKind.MOTOR -> 6.0
                SubsystemHardwareKind.POSITIONAL_SERVO -> 0.75
                SubsystemHardwareKind.CONTINUOUS_SERVO -> 0.5
                SubsystemHardwareKind.INDICATOR_LIGHT -> 0.75
                SubsystemHardwareKind.PRISM_DRIVER -> 1500.0
                SubsystemHardwareKind.SOLENOID -> 1.0
                else -> error("Not actuator")
            }.kotlinDouble()
            val followerActiveAssertions = document.followersOf(device.hardwareId).joinToString("\n") { follower ->
                val expected = follower.invertedExpression(
                    follower.following!!.transformedExpression(validCommand),
                )
                "        assertEquals($expected, io.${follower.hardwareId}Command, 0.0)"
            }
            val followerNeutralAssertions = document.followersOf(device.hardwareId).joinToString("\n") { follower ->
                val expected = follower.invertedExpression(requireNotNull(follower.safeOutput).kotlinDouble())
                "        assertEquals($expected, io.${follower.hardwareId}Command, 0.0)"
            }
            val postFailureAssertions = if (document.safety.latchOutputFaults) {
                """
                    assertTrue(io.outputFaultLatched)
                    $command(3.0)
                    assertEquals($neutral, $observed, 0.0)
                """.trimIndent()
            } else {
                """
                    assertFalse(io.outputFaultLatched)
                    $command($validCommand)
                    assertEquals(${device.invertedExpression(validCommand)}, $observed, 0.0)
                """.trimIndent()
            }
            """
                    $command($validCommand)
                    assertEquals(${device.invertedExpression(validCommand)}, $observed, 0.0)
            $followerActiveAssertions
                    io.failNextWrite = true
                    $command(4.0)
                    assertEquals($neutral, $observed, 0.0)
            $followerNeutralAssertions
            $postFailureAssertions
                    assertTrue(io.recoverWithNeutral())
                    assertFalse(io.outputFaultLatched)
            """.trimIndent()
        }.orEmpty()
}
