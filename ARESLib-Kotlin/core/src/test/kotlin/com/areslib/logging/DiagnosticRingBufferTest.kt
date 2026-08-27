package com.areslib.logging

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiagnosticRingBufferTest {

    @Test
    fun `test diagnostic ring buffer circular overwrite and zero allocation characteristics`() {
        val buffer = DiagnosticRingBuffer(capacity = 5)
        
        // 1. Log numeric values and check size increments
        buffer.log("ValA", 1.23)
        buffer.log("ValB", 4.56)
        buffer.log("ValC", 7.89)
        
        assertEquals(3, buffer.getNumericCount())
        
        val valBuffer = DoubleArray(1)
        val tag1 = buffer.getNumericEntry(0, valBuffer)
        assertEquals("ValA", tag1)
        assertEquals(1.23, valBuffer[0])
        
        // 2. Add more entries to trigger circular overwrite (capacity = 5)
        buffer.log("ValD", 10.11)
        buffer.log("ValE", 12.13)
        buffer.log("ValF", 14.15) // Overwrites ValA
        
        assertEquals(5, buffer.getNumericCount())
        
        val tag2 = buffer.getNumericEntry(0, valBuffer)
        assertEquals("ValB", tag2) // Oldest is now ValB
        assertEquals(4.56, valBuffer[0])
        
        val tagLast = buffer.getNumericEntry(4, valBuffer)
        assertEquals("ValF", tagLast)
        assertEquals(14.15, valBuffer[0])
    }

    @Test
    fun `test diagnostic ring buffer text message logging without string creation`() {
        val buffer = DiagnosticRingBuffer(capacity = 3)
        
        val msg1 = "SystemReady".toCharArray()
        val msg2 = "ElevatorFault".toCharArray()
        val msg3 = "IntakeEngaged".toCharArray()
        val msg4 = "BufferOverflow".toCharArray() // Overwrites SystemReady
        
        buffer.log(msg1)
        buffer.log(msg2)
        buffer.log(msg3)
        
        assertEquals(3, buffer.getMessageCount())
        
        buffer.log(msg4)
        
        assertEquals(3, buffer.getMessageCount())
        
        val readBuffer = CharArray(32)
        val len = buffer.getMessageEntry(0, readBuffer)
        
        // Oldest message should now be "ElevatorFault"
        val oldestStr = String(readBuffer, 0, len)
        assertEquals("ElevatorFault", oldestStr)
    }
}
