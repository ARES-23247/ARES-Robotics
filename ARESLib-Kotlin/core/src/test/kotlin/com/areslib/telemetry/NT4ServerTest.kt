package com.areslib.telemetry

import com.areslib.networktables.NT4BufferPool
import com.areslib.networktables.NT4Entry
import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Json
import com.areslib.networktables.NT4Server
import com.areslib.networktables.NT4Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NT4ServerTest {

    @Test
    fun testServerInstanceAndTopicStorage() {
        val inst = NT4Instance.defaultInstance
        if (inst.defaultServer == null) {
            inst.startServer("0.0.0.0", 5810)
        }
        assertNotNull(inst.defaultServer)

        NT4Server.publishTopic("Test/Key", 123.45)
        val valOut = NT4Server.getDouble("Test/Key", 0.0)
        assertEquals(123.45, valOut, 0.0001)

        NT4Server.publishTopic("Test/StringKey", "ARES")
        val strOut = NT4Server.getString("Test/StringKey", "")
        assertEquals("ARES", strOut)

        NT4Server.publishTopic("Test/BoolKey", true)
        val boolOut = NT4Server.getBoolean("Test/BoolKey", false)
        assertTrue(boolOut)
    }

    @Test
    fun testNT4JsonPublishParsing() {
        val json = """[{"method":"publish","params":{"name":"/Drive/Pose_X","pubuid":42,"type":"double"}}]"""
        val parsed = NT4Json.parseMessages(json)
        assertEquals(1, parsed.size)
        val msg = parsed[0]
        assertEquals("publish", msg.method)
        assertEquals("/Drive/Pose_X", msg.topicName)
        assertEquals(42, msg.pubUid)
        assertEquals("double", msg.type)
    }

    @Test
    fun testNT4JsonSubscribeParsing() {
        val json = """{"method":"subscribe","params":{"topics":["/Drive/","/ARES/"]}}"""
        val parsed = NT4Json.parseMessages(json)
        assertEquals(1, parsed.size)
        val msg = parsed[0]
        assertEquals("subscribe", msg.method)
        assertEquals(listOf("/Drive/", "/ARES/"), msg.topics)
    }

    @Test
    fun testNT4JsonAnnounceFormatting() {
        val entry = NT4Entry(1, "Drive/Pose_X", NT4Value.DoubleVal(1.5))
        val json = NT4Json.buildAnnounceSingle(entry)
        assertTrue(json.contains("\"method\":\"announce\""))
        assertTrue(json.contains("\"name\":\"/Drive/Pose_X\""))
        assertTrue(json.contains("\"id\":1"))
        assertTrue(json.contains("\"type\":\"double\""))
    }

    @Test
    fun testBufferPoolAcquireAndRelease() {
        val buf1 = NT4BufferPool.acquireByteArray(1024)
        assertNotNull(buf1)
        assertTrue(buf1.size >= 1024)
        NT4BufferPool.releaseByteArray(buf1)

        val buf2 = NT4BufferPool.acquireByteArray(1024)
        assertEquals(buf1, buf2)
    }
}
