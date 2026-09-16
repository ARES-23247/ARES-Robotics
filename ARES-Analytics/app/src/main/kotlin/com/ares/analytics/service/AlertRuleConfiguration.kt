package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.ThresholdRule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.*

internal data class LoadedAlertRules(val rules: List<ThresholdRule>, val warning: String? = null)

internal interface AlertRuleFiles {
    fun read(path: Path): ByteArray
    fun create(path: Path, contents: ByteArray)
}

/** Reads a bounded snapshot and exclusively creates defaults; never replaces an existing file. */
internal object NioAlertRuleFiles : AlertRuleFiles {
    override fun read(path: Path): ByteArray = Files.newInputStream(path).use {
        it.readNBytes(AlertRuleConfiguration.MAX_BYTES + 1)
    }
    override fun create(path: Path, contents: ByteArray) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.write(path, contents, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}

internal object AlertRuleConfiguration {
    const val MAX_BYTES = 1_048_576
    const val MAX_RULES = 4_096
    private val json = Json { prettyPrint = true }
    private class InvalidRules(message: String) : Exception(message)

    fun load(path: String, defaults: List<ThresholdRule>, files: AlertRuleFiles = NioAlertRuleFiles): LoadedAlertRules {
        val fallbackRules = defaults.toList()
        val location = try { Path.of(path) } catch (_: InvalidPathException) {
            return fallback(fallbackRules, "The threshold path is invalid.")
        }
        try {
            return LoadedAlertRules(decode(files.read(location)))
        } catch (_: NoSuchFileException) {
            try {
                files.create(location, json.encodeToString(fallbackRules).toByteArray(Charsets.UTF_8))
                return LoadedAlertRules(fallbackRules)
            } catch (_: FileAlreadyExistsException) {
                // Another owner created a configuration after our read; consume it once, never overwrite it.
                return readExisting(location, fallbackRules, files)
            } catch (_: IOException) {
                return fallback(fallbackRules, "The default threshold file could not be created.")
            } catch (_: SecurityException) {
                return fallback(fallbackRules, "Creating the default threshold file was denied.")
            }
        } catch (invalid: InvalidRules) {
            return fallback(fallbackRules, invalid.message!!)
        } catch (_: IOException) {
            return fallback(fallbackRules, "The threshold file could not be read.")
        } catch (_: SecurityException) {
            return fallback(fallbackRules, "Reading the threshold file was denied.")
        }
    }

    private fun readExisting(path: Path, defaults: List<ThresholdRule>, files: AlertRuleFiles): LoadedAlertRules = try {
        LoadedAlertRules(decode(files.read(path)))
    } catch (invalid: InvalidRules) {
        fallback(defaults, invalid.message!!)
    } catch (_: IOException) {
        fallback(defaults, "The concurrently created threshold file could not be read.")
    } catch (_: SecurityException) {
        fallback(defaults, "Reading the concurrently created threshold file was denied.")
    }

    private fun decode(bytes: ByteArray): List<ThresholdRule> {
        if (bytes.size > MAX_BYTES) throw InvalidRules("The threshold file exceeds 1 MiB.")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { throw InvalidRules("The threshold file is not valid UTF-8.") }
        val rules = try { json.decodeFromString<List<ThresholdRule>>(text.removePrefix("\uFEFF")) }
        catch (_: SerializationException) { throw InvalidRules("Threshold JSON is invalid or contains unsupported fields.") }
        if (rules.size > MAX_RULES) throw InvalidRules("The threshold file exceeds 4096 rules.")
        val keys = HashSet<String>()
        rules.forEachIndexed { index, rule ->
            val row = index + 1
            val key = TelemetryMetricCatalog.normalizeTopic(rule.key)
            if (key.isBlank() || rule.key.any { it.isISOControl() }) throw InvalidRules("Rule $row has an invalid topic key.")
            if (rule.displayName.isBlank() || rule.displayName.any { it.isISOControl() }) throw InvalidRules("Rule $row has an invalid display name.")
            val minimum = rule.minValue; val maximum = rule.maxValue
            if ((minimum != null && !minimum.isFinite()) || (maximum != null && !maximum.isFinite()))
                throw InvalidRules("Rule $row has a nonfinite bound.")
            if (minimum != null && maximum != null && minimum > maximum) throw InvalidRules("Rule $row has reversed bounds.")
            if (!keys.add(key)) throw InvalidRules("Rule $row duplicates a normalized topic key.")
            AlertRuleSemantics.configurationProblem(key, rule)?.let { throw InvalidRules("Rule $row: $it") }
        }
        return rules
    }

    private fun fallback(defaults: List<ThresholdRule>, reason: String) =
        LoadedAlertRules(defaults, "Using built-in alert rules. $reason")
}

/** Scalar rules use inclusive allowed bounds, including derived binary diagnostic values. */
internal object AlertRuleSemantics {
    fun violates(value: Double, rule: ThresholdRule): Boolean = value.isFinite() &&
        ((rule.minValue?.let { value < it } == true) || (rule.maxValue?.let { value > it } == true))

    /** Loop bounds describe a fixed temporal detector, not an arbitrary scalar comparator. */
    fun configurationProblem(normalizedKey: String, rule: ThresholdRule): String? {
        if (normalizedKey !in TelemetryMetricCatalog.LOOP_TIME.keys) return null
        if (rule.minValue == null &&
            (rule.maxValue == null || rule.maxValue == LoopOverrunWindow.MODERATE_THRESHOLD_MS)) return null
        return "Loop alerts require maxValue 25 and no minimum, or no bounds to disable; " +
            "the temporal detector uses 3 samples above 25 ms or one at least 100 ms."
    }
}

