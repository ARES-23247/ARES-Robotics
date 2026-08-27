package com.areslib.sim.cli

import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Parsed simulator startup options. */
data class SimCliArgs(
    val fieldConfigArg: String? = null,
    val headless: Boolean = false,
    /** Binary-compatibility shim; live field updates use the canonical NT4 document topic. */
    @Deprecated("The --watch option never reloaded fields; publish ARES/Input/fieldConfig instead")
    val watchFieldConfig: Boolean = false,
    val opModeClassName: String? = null
)

/**
 * Handles CLI flag parsing, environment configuration, and ARESWEB REST API fetching for the desktop simulator.
 */
object SimCliParser {

    fun parseArgs(args: Array<String>): SimCliArgs {
        var fieldConfigArg: String? = null
        var headless = false
        var opModeClassName: String? = null

        var argIdx = 0
        while (argIdx < args.size) {
            val argument = args[argIdx]
            when {
                argument.startsWith("--field-config=") -> {
                    fieldConfigArg = argument.substringAfter('=').requireValue("--field-config")
                }
                argument.startsWith("--opmode=") -> {
                    opModeClassName = argument.substringAfter('=').requireValue("--opmode")
                }
                argument == "--field-config" -> {
                    fieldConfigArg = args.getOrNull(++argIdx)?.requireValue(argument)
                        ?: throw IllegalArgumentException("$argument requires a value")
                }
                argument == "--headless" -> headless = true
                argument == "--opmode" -> {
                    opModeClassName = args.getOrNull(++argIdx)?.requireValue(argument)
                        ?: throw IllegalArgumentException("$argument requires a value")
                }
                else -> throw IllegalArgumentException("Unknown simulator option: $argument")
            }
            argIdx++
        }

        return SimCliArgs(
            fieldConfigArg = fieldConfigArg,
            headless = headless,
            opModeClassName = opModeClassName
        )
    }

    private fun String.requireValue(option: String): String =
        trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("$option requires a non-empty value")

    fun loadFieldConfig(fieldConfigArg: String?): RobotFieldConfig? {
        if (fieldConfigArg == null) return null
        val content = loadConfigContent(fieldConfigArg) ?: return null
        return try {
            val config = RobotFieldDocument.decode(content)
            println("[Simulator] Successfully loaded field config: ${config.name}")
            RobotFieldManager.setActiveConfig(config)
            config
        } catch (e: Exception) {
            System.err.println("Failed to parse loaded field config: ${e.message}")
            null
        }
    }

    private fun loadConfigContent(arg: String): String? {
        val file = File(arg)
        if (file.exists()) {
            return file.readText()
        }
        val envBaseUrl = System.getenv("ARESWEB_API_URL") ?: System.getProperty("aresweb.api.url")
        val baseUrl = envBaseUrl ?: "http://localhost:5001/aresfirst-portal/us-central1/api"
        return try {
            val url = URL("$baseUrl/simulations/field-config/$arg")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                System.err.println("Failed to fetch field config $arg from ARESWEB: HTTP $code")
                null
            }
        } catch (e: Exception) {
            System.err.println("Error fetching field config $arg: ${e.message}")
            null
        }
    }
}
