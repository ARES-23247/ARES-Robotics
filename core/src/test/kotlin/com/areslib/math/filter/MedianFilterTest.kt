package com.areslib.math.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MedianFilterTest {

    @Test
    fun testOddWindowSize() {
        val filter = MedianFilter(5)

        // Initial values: buffer builds up
        assertEquals(10.0, filter.calculate(10.0), 1e-6) // [10.0] -> 10.0
        assertEquals(15.0, filter.calculate(20.0), 1e-6) // [10.0, 20.0] -> median of 10,20 is 15.0
        assertEquals(15.0, filter.calculate(15.0), 1e-6) // [10.0, 20.0, 15.0] -> sorted [10, 15, 20] -> 15.0
        assertEquals(12.5, filter.calculate(5.0), 1e-6)  // [10.0, 20.0, 15.0, 5.0] -> sorted [5, 10, 15, 20] -> (10+15)/2 = 12.5
        assertEquals(12.5, filter.value, 1e-6)
        
        assertEquals(15.0, filter.calculate(30.0), 1e-6) // [10.0, 20.0, 15.0, 5.0, 30.0] -> sorted [5, 10, 15, 20, 30] -> median is 15.0
    }

    @Test
    fun testEvenWindowSize() {
        val filter = MedianFilter(4)

        assertEquals(2.0, filter.calculate(2.0), 1e-6)
        assertEquals(3.5, filter.calculate(5.0), 1e-6) // [2, 5] -> median is 3.5
        assertEquals(5.0, filter.calculate(8.0), 1e-6) // [2, 5, 8] -> median is 5.0
        assertEquals(4.5, filter.calculate(4.0), 1e-6) // [2, 4, 5, 8] -> median is (4+5)/2 = 4.5
    }

    @Test
    fun testOutlierRejection() {
        val filter = MedianFilter(5)
        
        // Populate filter with stable measurements
        filter.calculate(10.0)
        filter.calculate(10.2)
        filter.calculate(9.8)
        filter.calculate(10.1)
        filter.calculate(9.9)

        assertEquals(10.0, filter.value, 1e-6)

        // Sudden massive outlier noise spike
        val out = filter.calculate(100.0) // [10.2, 9.8, 10.1, 9.9, 100.0] -> sorted [9.8, 9.9, 10.1, 10.2, 100.0]
        assertEquals(10.1, out, 1e-6) // Outlier is completely rejected
    }

    @Test
    fun testReset() {
        val filter = MedianFilter(3)
        filter.calculate(5.0)
        filter.calculate(15.0)

        filter.reset(42.0)
        assertEquals(42.0, filter.value, 1e-6)

        // Next calculate should replace first element of pre-filled buffer
        // [42.0, 42.0, 42.0] -> writeIndex=0. calculate(10.0) -> [10.0, 42.0, 42.0] -> sorted [10, 42, 42] -> median is 42.0
        assertEquals(42.0, filter.calculate(10.0), 1e-6)
        // calculate(12.0) -> writeIndex=1 -> [10.0, 12.0, 42.0] -> sorted [10, 12, 42] -> median is 12.0
        assertEquals(12.0, filter.calculate(12.0), 1e-6)
    }

    @Test
    fun testClear() {
        val filter = MedianFilter(3)
        filter.calculate(5.0)
        filter.calculate(15.0)
        filter.clear()

        assertEquals(0.0, filter.value, 1e-6)

        // Subsequent calculations should build history from scratch
        assertEquals(10.0, filter.calculate(10.0), 1e-6)
    }
}
