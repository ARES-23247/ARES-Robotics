package com.areslib.hardware.sensor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ThreadedSensorsTest {

    @Test
    fun testThreadedColorSensor() {
        val mockPhysical = object : ColorSensorIO {
            override val red: Int = 10
            override val green: Int = 20
            override val blue: Int = 30
            override val alpha: Int = 40
            override val normalizedRgb: DoubleArray = doubleArrayOf(0.1, 0.2, 0.3, 0.4)
        }

        val threaded = ThreadedColorSensor(mockPhysical, pollIntervalMs = 5)
        for (i in 0 until 50) {
            if (threaded.red == 10) break
            Thread.sleep(20)
        }

        assertEquals(10, threaded.red)
        assertEquals(20, threaded.green)
        assertEquals(30, threaded.blue)
        assertEquals(40, threaded.alpha)
        assertArrayEquals(doubleArrayOf(0.1, 0.2, 0.3, 0.4), threaded.normalizedRgb, 1e-6)

        threaded.close()
    }

    @Test
    fun testThreadedDistanceSensor() {
        val mockPhysical = object : DistanceSensorIO {
            override val distanceMeters: Double = 1.25
        }

        val threaded = ThreadedDistanceSensor(mockPhysical, pollIntervalMs = 5)
        for (i in 0 until 50) {
            if (threaded.distanceMeters > 0.0) break
            Thread.sleep(20)
        }

        assertEquals(1.25, threaded.distanceMeters, 1e-6)

        threaded.close()
    }

    @Test
    fun testThreadedMultizoneDistanceSensor() {
        val mockPhysical = object : MultizoneDistanceSensorIO {
            override val rows: Int = 2
            override val columns: Int = 2
            override val distancesMeters: DoubleArray = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        }

        val threaded = ThreadedMultizoneDistanceSensor(mockPhysical, pollIntervalMs = 5)
        for (i in 0 until 50) {
            if (threaded.distancesMeters.isNotEmpty() && threaded.distancesMeters[0] > 0.0) break
            Thread.sleep(20)
        }

        assertEquals(2, threaded.rows)
        assertEquals(2, threaded.columns)
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), threaded.distancesMeters, 1e-6)

        threaded.close()
    }
}
