package com.areslib.logging

import com.google.gson.Gson
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ARESDataLoggerIntegrityContractTest {

    @Test
    fun `late non-finite and escaped values remain valid JSON inside valid CSV`() {
        val mode = "LateJson_${System.nanoTime()}"
        val logger = ARESDataLogger(mode, policy = testLoggingPolicy())
        var logFile: File? = null
        try {
            logger.logFrame(hashMapOf("TimestampMs" to 1L, "Stable" to "first"))
            logger.logFrame(hashMapOf(
                "TimestampMs" to 2L,
                "Stable" to "second",
                "LateNaN" to Double.NaN,
                "LatePositiveInfinity" to Double.POSITIVE_INFINITY,
                "LateNegativeInfinity" to Double.NEGATIVE_INFINITY,
                "LateBoolean" to true,
                "LateText" to "line one\nline two \\ \"quoted\""
            ))
            logger.stop()

            logFile = findLog(mode)
            val rows = logFile.readLines()
            assertEquals(3, rows.size)
            val header = parseCsvLine(rows[0])
            val second = parseCsvLine(rows[2])
            assertEquals(header.size, second.size, "embedded newline and quotes must not change CSV width")

            @Suppress("UNCHECKED_CAST")
            val extras = Gson().fromJson(
                second[header.indexOf(ARESDataLogger.EXTRA_FIELDS_COLUMN)],
                Map::class.java
            ) as Map<String, Any>
            assertEquals("NaN", extras["LateNaN"])
            assertEquals("Infinity", extras["LatePositiveInfinity"])
            assertEquals("-Infinity", extras["LateNegativeInfinity"])
            assertEquals(true, extras["LateBoolean"])
            assertEquals("line one\nline two \\ \"quoted\"", extras["LateText"])
        } finally {
            logger.stop()
            logFile?.delete()
        }
    }

    @Test
    fun `stop is idempotent and an empty session closes to an empty file`() {
        val mode = "EmptyStop_${System.nanoTime()}"
        val logger = ARESDataLogger(mode, policy = testLoggingPolicy())
        var logFile: File? = null
        try {
            logger.stop()
            logger.stop()

            logFile = findLog(mode)
            assertEquals(0L, logFile.length())
            assertEquals(emptyList(), logFile.readLines())
        } finally {
            logger.stop()
            logFile?.delete()
        }
    }

    private fun findLog(mode: String): File {
        val matches = File("./logs/").listFiles { _, name -> name.endsWith("_${mode}.csv") }.orEmpty()
        assertTrue(matches.isNotEmpty(), "logger did not create a file for $mode")
        return matches.single()
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    value.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields.add(value.toString())
                    value.setLength(0)
                }
                else -> value.append(char)
            }
            index++
        }
        fields.add(value.toString())
        return fields
    }
}
