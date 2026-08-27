package com.ares.analytics.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RobotLogDownloaderTest {
    @Test
    fun `download encodes filename and verifies exact content`() = runTest {
        val payload = "timestamp,key,value\n1,Drive Pose,2\n".toByteArray()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    assertEquals("telemetry run.csv", request.url.parameters["file"])
                    respond(
                        payload,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength, payload.size.toString()),
                    )
                }
            }
        }
        val downloader = RobotLogDownloader(client)
        val directory = Files.createTempDirectory("ares-robot-download").toFile()
        val destination = directory.resolve("staged.partial")
        try {
            val digest = downloader.download(
                "http://127.0.0.1:5002",
                RobotLogSource("telemetry run.csv", payload.size.toLong(), 1234L),
                destination,
            )

            assertContentEquals(payload, destination.readBytes())
            assertEquals(1234L, destination.lastModified())
            assertEquals(sha256(payload), digest)
        } finally {
            downloader.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `download rejects an oversized response and removes the partial file`() = runTest {
        val payload = "too many bytes".toByteArray()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(payload, HttpStatusCode.OK)
                }
            }
        }
        val downloader = RobotLogDownloader(client)
        val directory = Files.createTempDirectory("ares-robot-download-limit").toFile()
        val destination = directory.resolve("staged.partial")
        try {
            assertFailsWith<java.io.IOException> {
                downloader.download(
                    "http://127.0.0.1:5002",
                    RobotLogSource("telemetry.csv", 3L, 1234L),
                    destination,
                )
            }
            assertFalse(destination.exists())
        } finally {
            downloader.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `source validation rejects traversal and unsupported files`() {
        assertFailsWith<IllegalArgumentException> {
            validateRobotLogSource(RobotLogSource("../telemetry.csv", 10L, 1L))
        }
        assertFailsWith<IllegalArgumentException> {
            validateRobotLogSource(RobotLogSource("telemetry.zip", 10L, 1L))
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
