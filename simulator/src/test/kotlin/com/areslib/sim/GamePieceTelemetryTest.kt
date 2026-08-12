package com.areslib.sim

import com.areslib.networktables.NT4Server
import com.areslib.sim.network.TelemetryPublisher
import com.areslib.telemetry.TelemetryTopicConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePieceTelemetryTest {
    @After
    fun stopServer() {
        NT4Server.getInstance()?.stop()
        NT4Server.resetSharedState()
    }

    @Test
    fun `zero count explicitly clears stale game piece payload`() {
        NT4Server.createInstance("127.0.0.1", 0)
        TelemetryPublisher.publishGamePieces(DoubleArray(14) { it.toDouble() }, count = 2)
        assertEquals(2.0, NT4Server.getDouble(TelemetryTopicConstants.GAME_PIECES_COUNT, -1.0), 0.0)

        TelemetryPublisher.publishGamePieces(DoubleArray(0), count = 0)

        assertEquals(0.0, NT4Server.getDouble(TelemetryTopicConstants.GAME_PIECES_COUNT, -1.0), 0.0)
        assertTrue(NT4Server.getDoubleArray(TelemetryTopicConstants.GAME_PIECES, doubleArrayOf(-1.0)).isEmpty())
    }
}
