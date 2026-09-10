package com.areslib.telemetry

import com.areslib.networktables.NT4Server
import kotlin.test.Test
import kotlin.test.assertEquals

class DriveReceiverTimeAuditTest {
    @Test fun `wrong typed retained input disarms an active receiver`() {
        NT4Server.createInstance("127.0.0.1", 0)
        SimInputBridge.reset()
        try {
            NT4Server.publishTopic(TelemetryTopicConstants.DRIVE_INPUT_FRAME, doubleArrayOf(2.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 8.0))
            SimInputBridge.pollNetworkFrame(1000)
            NT4Server.publishTopic(TelemetryTopicConstants.DRIVE_INPUT_FRAME, doubleArrayOf(2.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 8.0))
            SimInputBridge.pollNetworkFrame(1020)
            NT4Server.publishTopic(TelemetryTopicConstants.DRIVE_INPUT_FRAME, "invalid")
            assertEquals(0.0, SimInputBridge.pollNetworkFrame(1040).vx)
        } finally {
            SimInputBridge.reset()
            NT4Server.getInstance()?.stop()
            NT4Server.resetSharedState()
        }
    }

    @Test fun `signed receiver time wrap cannot resurrect a live command`() {
        NT4Server.createInstance("127.0.0.1", 0)
        SimInputBridge.reset()
        try {
            NT4Server.publishTopic(TelemetryTopicConstants.DRIVE_INPUT_FRAME, doubleArrayOf(2.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 8.0))
            SimInputBridge.pollNetworkFrame(Long.MAX_VALUE - 3)
            NT4Server.publishTopic(TelemetryTopicConstants.DRIVE_INPUT_FRAME, doubleArrayOf(2.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 8.0))
            SimInputBridge.pollNetworkFrame(Long.MAX_VALUE - 2)
            assertEquals(0.0, SimInputBridge.currentFrame(Long.MIN_VALUE + 2).vx)
        } finally {
            SimInputBridge.reset()
            NT4Server.getInstance()?.stop()
            NT4Server.resetSharedState()
        }
    }
}
