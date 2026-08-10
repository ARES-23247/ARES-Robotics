package com.areslib.ftc.dsl

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FtcTeleOpDslTest {
    @Test
    fun `minimal definition requires explicit periodic behavior`() {
        assertThrows(IllegalArgumentException::class.java) {
            teleOp<Any> { setup { } }
        }

        assertDoesNotThrow {
            teleOp<Any> { everyLoop { } }
        }
    }

    @Test
    fun `duplicate lifecycle phases fail instead of silently replacing callbacks`() {
        assertThrows(IllegalStateException::class.java) {
            teleOp<Any> {
                everyLoop { }
                everyLoop { }
            }
        }

        assertThrows(IllegalStateException::class.java) {
            teleOp<Any> {
                controls { }
                controls { }
                everyLoop { }
            }
        }
    }
}
