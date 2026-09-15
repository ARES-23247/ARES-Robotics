package com.ares.analytics.service

import com.ares.analytics.shared.models.ThresholdRule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.*
import kotlin.test.*

class AlertRuleConfigurationTest {
    private val defaults = listOf(ThresholdRule("Default", "Default", maxValue = 10.0))
    private val custom = listOf(ThresholdRule("Custom", "Custom", minValue = 1.0))
    private fun bytes(rules: List<ThresholdRule>) = Json.encodeToString(rules).toByteArray(Charsets.UTF_8)
    private class FilesStub(
        val reader: (Path) -> ByteArray,
        val creator: (Path, ByteArray) -> Unit = { _, _ -> error("unexpected create") },
    ) : AlertRuleFiles {
        override fun read(path: Path) = reader(path)
        override fun create(path: Path, contents: ByteArray) = creator(path, contents)
    }
    private fun load(contents: ByteArray) = AlertRuleConfiguration.load("thresholds.json", defaults, FilesStub({ contents }))

    @Test fun `valid empty and boundless scalar configurations remain intentional`() {
        assertEquals(emptyList(), load(bytes(emptyList())).rules)
        val rules = listOf(ThresholdRule("Source", "Unicode α", minValue = null, maxValue = null),
            ThresholdRule("Other", "Exact point", minValue = 2.0, maxValue = 2.0))
        assertEquals(LoadedAlertRules(rules), load(bytes(rules)))
    }
    @Test fun `invalid rows cannot partially register a valid prefix`() {
        val invalid = listOf(
            ThresholdRule("", "Empty"), ThresholdRule("/", "Slash"), ThresholdRule("bad\u0000key", "Control"),
            ThresholdRule("BlankName", " "), ThresholdRule("BadName", "name\ncontrol"),
            ThresholdRule("Reversed", "Range", minValue = 5.0, maxValue = 2.0),
            custom.single().copy(key = "/Custom"),
        )
        for (rule in invalid) {
            val result = load(bytes(custom + rule))
            assertEquals(defaults, result.rules); assertNotNull(result.warning)
        }
    }
    @Test fun `unknown fields malformed JSON and nonfinite numeric input are visible fallbacks`() {
        for (text in listOf("", "[", "{}", "null",
            """[{"key":"A","displayName":"A","maxValu":5}]""",
            """[{"key":"A","displayName":"A","maxValue":1e999}]""",
            """[{"key":"A","displayName":"A","minValue":-1e999}]""")) {
            val result = load(text.toByteArray())
            assertEquals(defaults, result.rules); assertNotNull(result.warning)
            assertFalse(result.warning.contains(text.takeIf { it.length > 10 } ?: "secret-input"))
        }
    }
    @Test fun `strict UTF8 rejects malformed bytes and accepts a leading BOM`() {
        assertNotNull(load(byteArrayOf(0xC3.toByte(), 0x28)).warning)
        assertEquals(custom, load(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes(custom)).rules)
    }
    @Test fun `catalog aliases preserve their distinct source provenance`() {
        val rules = listOf(ThresholdRule("Robot/BatteryVoltage", "Canonical", minValue = 10.5),
            ThresholdRule("Battery/Voltage", "Alias", minValue = 10.5))
        assertEquals(LoadedAlertRules(rules), load(bytes(rules)))
    }
    @Test fun `read and creation failures return an independent default snapshot`() {
        val supplied = defaults.toMutableList()
        val denied = AlertRuleConfiguration.load("rules", supplied, FilesStub({ throw AccessDeniedException(it.toString()) }))
        supplied.clear(); assertEquals(defaults, denied.rules); assertNotNull(denied.warning)
        val createFailed = AlertRuleConfiguration.load("rules", defaults, FilesStub(
            { throw NoSuchFileException(it.toString()) }, { _, _ -> throw IOException("disk full") }))
        assertEquals(defaults, createFailed.rules); assertTrue(createFailed.warning!!.contains("created"))
    }
    @Test fun `concurrent creation reloads the other owners configuration without replacement`() {
        var reads = 0; var creates = 0
        val result = AlertRuleConfiguration.load("rules", defaults, FilesStub(
            { if (++reads == 1) throw NoSuchFileException(it.toString()) else bytes(custom) },
            { path, _ -> creates++; throw FileAlreadyExistsException(path.toString()) }))
        assertEquals(LoadedAlertRules(custom), result); assertEquals(2, reads); assertEquals(1, creates)
    }
    @Test fun `failed concurrent reload does not recurse or retry creation`() {
        for (failure in listOf<Exception>(IOException("gone"), SecurityException("denied"))) {
            var reads = 0; var creates = 0
            val result = AlertRuleConfiguration.load("rules", defaults, FilesStub(
                { if (++reads == 1) throw NoSuchFileException(it.toString()) else throw failure },
                { path, _ -> creates++; throw FileAlreadyExistsException(path.toString()) }))
            assertEquals(defaults, result.rules); assertNotNull(result.warning)
            assertEquals(2, reads); assertEquals(1, creates)
        }
    }
    @Test fun `invalid concurrently created data preserves default fallback`() {
        var reads = 0
        val result = AlertRuleConfiguration.load("rules", defaults, FilesStub(
            { if (++reads == 1) throw NoSuchFileException(it.toString()) else "invalid".toByteArray() },
            { path, _ -> throw FileAlreadyExistsException(path.toString()) }))
        assertEquals(defaults, result.rules); assertNotNull(result.warning)
    }
    @Test fun `security denials are recoverable but fatal programming errors propagate`() {
        assertNotNull(AlertRuleConfiguration.load("rules", defaults, FilesStub({ throw SecurityException() })).warning)
        assertNotNull(AlertRuleConfiguration.load("rules", defaults, FilesStub(
            { throw NoSuchFileException(it.toString()) }, { _, _ -> throw SecurityException() })).warning)
        assertFailsWith<AssertionError> { AlertRuleConfiguration.load("rules", defaults, FilesStub({ throw AssertionError("fatal") })) }
        assertNotNull(AlertRuleConfiguration.load("bad\u0000path", defaults).warning)
    }
    @Test fun `configuration has bounded bytes and rule count`() {
        assertNotNull(load(ByteArray(AlertRuleConfiguration.MAX_BYTES + 1)).warning)
        val rules = (0 until AlertRuleConfiguration.MAX_RULES).map { ThresholdRule("Key/$it", "Rule", maxValue = 1.0) }
        assertNull(load(bytes(rules)).warning)
        assertNotNull(load(bytes(rules + ThresholdRule("Extra", "Extra", maxValue = 1.0))).warning)
    }
    @Test fun `real default creation and existing file preservation use UTF8 exclusively`() {
        val directory = Files.createTempDirectory("rule-config")
        val file = directory.resolve("thresholds.json")
        try {
            assertEquals(LoadedAlertRules(defaults), AlertRuleConfiguration.load(file.toString(), defaults))
            assertContentEquals(bytes(defaults), bytes(AlertRuleConfiguration.load(file.toString(), custom).rules))
            assertFailsWith<FileAlreadyExistsException> { NioAlertRuleFiles.create(file, bytes(custom)) }
            assertEquals(defaults, AlertRuleConfiguration.load(file.toString(), custom).rules)
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
    }
    @Test fun `native file reads stop at the configured byte bound`() {
        val file = Files.createTempFile("large-rules", ".json")
        try {
            Files.write(file, ByteArray(AlertRuleConfiguration.MAX_BYTES + 1_000))
            assertEquals(AlertRuleConfiguration.MAX_BYTES + 1, NioAlertRuleFiles.read(file).size)
            assertNotNull(AlertRuleConfiguration.load(file.toString(), defaults).warning)
        } finally { Files.deleteIfExists(file) }
    }
}
