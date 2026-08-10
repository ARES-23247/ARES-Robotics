package com.areslib.sim.cli

import com.areslib.math.geometry.Vector3
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldManager
import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Class implementation for Sim Cli Args.
 *
 * Robotics framework control component.
 */
data class SimCliArgs(
    val fieldConfigArg: String? = null,
    val watchFieldConfig: Boolean = false,
    val headless: Boolean = false,
    val opModeClassName: String? = null,
    val replayCloudId: String? = null
)

/**
 * Handles CLI flag parsing, environment configuration, and ARESWEB REST API fetching for the desktop simulator.
 */
object SimCliParser {

    fun parseArgs(args: Array<String>): SimCliArgs {
        var fieldConfigArg: String? = null
        var watchFieldConfig = false
        var headless = false
        var opModeClassName: String? = null
        var replayCloudId: String? = null

        var argIdx = 0
        while (argIdx < args.size) {
            when (args[argIdx]) {
                "--field-config" -> {
                    if (argIdx + 1 < args.size) {
                        fieldConfigArg = args[argIdx + 1]
                        argIdx++
                    }
                }
                "--watch" -> {
                    watchFieldConfig = true
                }
                "--headless" -> {
                    headless = true
                }
                "--opmode" -> {
                    if (argIdx + 1 < args.size) {
                        opModeClassName = args[argIdx + 1]
                        argIdx++
                    }
                }
                "--replay-cloud" -> {
                    if (argIdx + 1 < args.size) {
                        replayCloudId = args[argIdx + 1]
                        argIdx++
                    }
                }
            }
            argIdx++
        }

        return SimCliArgs(
            fieldConfigArg = fieldConfigArg,
            watchFieldConfig = watchFieldConfig,
            headless = headless,
            opModeClassName = opModeClassName,
            replayCloudId = replayCloudId
        )
    }

    fun loadFieldConfig(fieldConfigArg: String?): RobotFieldConfig? {
        if (fieldConfigArg == null) return null
        val content = loadConfigContent(fieldConfigArg) ?: return null
        return try {
            val gson = Gson()
            val config = gson.fromJson(content, RobotFieldConfig::class.java)
            if (config != null) {
                println("[Simulator] Successfully loaded field config: ${config.name}")
                RobotFieldManager.setActiveConfig(config)
            }
            config
        } catch (e: Exception) {
            System.err.println("Failed to parse loaded field config: ${e.message}")
            null
        }
    }

    fun loadEkfOverrides(): Vector3? {
        var configFile = File("config_override.json")
        if (!configFile.exists()) {
            val parentFile = File("../config_override.json")
            if (parentFile.exists()) {
                configFile = parentFile
            }
        }
        if (configFile.exists()) {
            try {
                val configContent = configFile.readText()
                val gson = Gson()
                val configMap = gson.fromJson(configContent, Map::class.java)
                val overrideX = (configMap["visionStdDevX"] as? Number)?.toDouble()
                val overrideY = (configMap["visionStdDevY"] as? Number)?.toDouble()
                val overrideTheta = (configMap["visionStdDevTheta"] as? Number)?.toDouble()
                if (overrideX != null && overrideY != null && overrideTheta != null) {
                    println("[Simulator Config] Loaded EKF Standard Deviation overrides: X=$overrideX, Y=$overrideY, Theta=$overrideTheta")
                    return Vector3(overrideX, overrideY, overrideTheta)
                }
            } catch (e: Exception) {
                println("[Simulator Config] Failed to parse config_override.json: ${e.message}")
            }
        }
        return null
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
