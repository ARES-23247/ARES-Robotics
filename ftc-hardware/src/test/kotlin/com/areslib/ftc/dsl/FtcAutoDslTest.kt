package com.areslib.ftc.dsl

import com.areslib.state.Alliance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FtcAutoDslTest {
    @Test
    fun `auto name and alliance are both explicit`() {
        assertThrows(IllegalArgumentException::class.java) {
            ftcAuto { alliance(Alliance.RED) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ftcAuto { aresAuto("two-piece") }
        }

        val definition = ftcAuto {
            aresAuto("two-piece")
            alliance(Alliance.BLUE)
        }
        assertEquals("two-piece", definition.documentId)
        assertEquals(Alliance.BLUE, definition.alliance)
        assertEquals(29.5, definition.maximumRuntimeSeconds)
    }

    @Test
    fun `unsafe asset names and invalid match durations fail during definition`() {
        assertThrows(IllegalArgumentException::class.java) {
            ftcAuto {
                aresAuto("../outside")
                alliance(Alliance.RED)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ftcAuto {
                aresAuto("safe")
                alliance(Alliance.RED)
                maximumRuntime(30.1)
            }
        }
    }
}
