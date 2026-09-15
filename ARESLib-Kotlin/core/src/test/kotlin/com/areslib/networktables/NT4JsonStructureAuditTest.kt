package com.areslib.networktables

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.*

class NT4JsonStructureAuditTest {
    private val valid = """{"method":"publish","params":{"name":"/Good","pubuid":7,"type":"double"}}"""

    @Test fun `nested method and params fields cannot impersonate control envelope`() {
        val nested = """{"metadata":$valid}"""
        assertTrue(NT4Json.parseMessages(nested).isEmpty())
        assertTrue(NT4Json.parseMessages("""{"method":"publish","metadata":{"params":{"name":"/Bad","pubuid":1,"type":"double"}}}""").isEmpty())
    }

    @Test fun `properties cannot override publish names types or subscription options`() {
        val published = NT4Json.parseMessages("""{"method":"publish","params":{"properties":{"name":"/Wrong","pubuid":9,"type":"string"},"name":"/Right","pubuid":7,"type":"double"}}""").single()
        assertEquals("/Right", published.topicName); assertEquals(7, published.pubUid); assertEquals("double", published.type)
        val subscription = NT4Json.parseMessages("""{"method":"subscribe","params":{"properties":{"prefix":true},"topics":["/Exact"],"subuid":1,"options":{"prefix":false}}}""").single()
        assertFalse(subscription.prefix)
    }

    @Test fun `brackets escapes unicode and whitespace remain inside topic strings`() {
        val names = listOf("/a]b", "/a\"b", "/café", "/line\n", "/slash\\")
        val encoded = com.google.gson.Gson().toJson(names)
        val messages = NT4Json.parseMessages("""[{"params":{"topics":$encoded,"options":{"prefix":true},"subuid":2},"method":"subscribe"}]""")
        assertEquals(names, messages.single().topics); assertTrue(messages.single().prefix)
    }

    @Test fun `fractional and out of range identifiers cannot target integer publishers`() {
        for (value in listOf("7.5", "7.000000000000001", "2147483648", "\"7\"", "true")) {
            assertTrue(NT4Json.parseMessages(valid.replace("\"pubuid\":7", "\"pubuid\":$value")).isEmpty(), value)
        }
        assertEquals(7, NT4Json.parseMessages(valid.replace("\"pubuid\":7", "\"pubuid\":7e0")).single().pubUid)
    }

    @Test fun `missing and mistyped required control fields are ignored`() {
        for (json in listOf("""{"method":3,"params":{}}""", """{"method":"publish","params":[]}""",
            """{"method":"publish","params":{"name":42,"pubuid":7,"type":"double"}}""",
            """{"method":"publish","params":{"name":"/Good","pubuid":7}}""")) {
            assertTrue(NT4Json.parseMessages(json).isEmpty(), json)
        }
    }

    @Test fun `malformed frame cannot return an already parsed prefix`() {
        for (json in listOf("[$valid,", "[$valid] trailing", "[$valid $valid]", "$valid$valid", "[$valid,]")) {
            assertFailsWith<IllegalArgumentException>(json) { NT4Json.parseMessages(json) }
        }
    }

    @Test fun `unknown messages are skipped without losing subsequent valid messages`() {
        assertEquals(listOf(7), NT4Json.parseMessages("""[true,{},null,{"method":"unknown","params":{}},$valid]""").map { it.pubUid })
    }

    @Test fun `duplicate control keys and excessive unknown nesting are rejected`() {
        assertFailsWith<IllegalArgumentException> { NT4Json.parseMessages(valid.replace("\"pubuid\":7", "\"pubuid\":7,\"pubuid\":8")) }
        val deep = "[".repeat(70) + "0" + "]".repeat(70)
        assertFailsWith<IllegalArgumentException> { NT4Json.parseMessages("""[{"extra":$deep},$valid]""") }
    }

    @Test fun `public extraction helpers respect object ownership type and range`() {
        val json = """{"nested":{"name":"bad","uid":3,"prefix":true},"name":"good","uid":7.5,"prefix":false,"topics":["a]b"]}"""
        assertEquals("good", NT4Json.extractStringField(json, "name", 0, json.lastIndex))
        assertNull(NT4Json.extractIntField(json, "uid", 0, json.lastIndex))
        assertEquals(false, NT4Json.extractBooleanField(json, "prefix", 0, json.lastIndex))
        assertEquals(listOf("a]b"), NT4Json.extractStringArrayField(json, "topics", 0, json.lastIndex))
        assertNull(NT4Json.extractStringField(json, "name", -1, Int.MAX_VALUE))
    }

    @Test fun `announcements use valid escaped json and normalized topics`() {
        val topic = "control/quote\"line\n"
        val entry = NT4Entry(5, topic, NT4Value.DoubleVal(1.0))
        val announce = JsonParser.parseString(NT4Json.buildAnnounceSingle(entry, 7)).asJsonArray[0].asJsonObject["params"].asJsonObject
        assertEquals("/$topic", announce["name"].asString); assertEquals(7, announce["pubuid"].asInt)
        assertEquals(5, JsonParser.parseString(NT4Json.buildUnannounceSingle(entry)).asJsonArray[0].asJsonObject["params"].asJsonObject["id"].asInt)
        assertEquals("[]", NT4Json.buildAnnounceArray(emptyList()))
    }

    @Test fun `message topic and character budgets reject oversized inputs without partial results`() {
        assertFailsWith<IllegalArgumentException> { NT4Json.parseMessages(" ".repeat(NT4Json.MAX_JSON_CHARS + 1)) }
        assertFailsWith<IllegalArgumentException> { NT4Json.parseMessages(List(NT4Json.MAX_MESSAGES + 1) { "{}" }.joinToString(",", "[", "]")) }
        val topics = List(NT4Json.MAX_TOPICS + 1) { "\"/one\"" }.joinToString(",", "[", "]")
        assertFailsWith<IllegalArgumentException> { NT4Json.parseMessages("""{"method":"subscribe","params":{"subuid":1,"topics":$topics}}""") }
        assertEquals(70, NT4Json.parseMessages(valid.replace("\"pubuid\":7", "\"pubuid\":7e1")).single().pubUid)
    }
}
