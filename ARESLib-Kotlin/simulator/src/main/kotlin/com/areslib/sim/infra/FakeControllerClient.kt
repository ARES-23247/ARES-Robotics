package com.areslib.sim.infra

import edu.wpi.first.networktables.NetworkTableInstance
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Scanner

/**
 * Object implementation for Fake Controller Client.
 *
 * Robotics framework control component.
 */
object FakeControllerClient {
    @JvmStatic
    fun main(args: Array<String>) {
        val serverIp = args.firstOrNull() ?: "127.0.0.1"
        println("Starting Remote Controller Client (Server IP: $serverIp)...")
        val ntInst = NetworkTableInstance.getDefault()
        
        // Start client and connect to server
        ntInst.startClient4("FakeController")
        ntInst.setServer(serverIp)
        
        println("Connecting to NetworkTables server...")
        var connected = false
        for (i in 0 until 50) {
            if (ntInst.isConnected) {
                connected = true
                break
            }
            Thread.sleep(100)
        }
        
        if (!connected) {
            System.err.println("Error: Failed to connect to NetworkTables server at $serverIp. Make sure the robot/simulator is running.")
            return
        }
        
        println("Connected to NetworkTables successfully!")
        
        val driveFramePub = ntInst.getDoubleArrayTopic("ARES/Input/driveFrame").publish()
        val commandPub = ntInst.getStringTopic("ARES/Input/command").publish()
        val obstaclesPub = ntInst.getStringTopic("ARES/Input/obstacles").publish()
        
        // Subscribers for coordinate readings
        val poseSub = ntInst.getDoubleArrayTopic("ARES/EstimatedPose").subscribe(doubleArrayOf(0.0, 0.0, 0.0))
        val poseXSub = ntInst.getDoubleTopic("Drive/Pose_X").subscribe(0.0)
        val poseYSub = ntInst.getDoubleTopic("Drive/Pose_Y").subscribe(0.0)
        val headingSub = ntInst.getDoubleTopic("Drive/Pose_Heading").subscribe(0.0)
        
        val driveFrame = DoubleArray(8)
        val sessionNonce = java.util.concurrent.ThreadLocalRandom.current()
            .nextLong(1L, 9_007_199_254_740_991L)
        var sequence = 0L
        var clientMonotonicMs = 0L
        fun publishDriveFrame(vx: Double, vy: Double, omega: Double) {
            driveFrame[0] = 2.0
            driveFrame[1] = sessionNonce.toDouble()
            driveFrame[2] = sequence++.toDouble()
            driveFrame[3] = clientMonotonicMs.toDouble()
            driveFrame[4] = vx
            driveFrame[5] = vy
            driveFrame[6] = omega
            driveFrame[7] = 56.0 // teleop + field-centric + red alliance
            driveFramePub.set(driveFrame)
            clientMonotonicMs += 100L
        }
        // Every session begins with a neutral frame before any actuator or axis command.
        publishDriveFrame(0.0, 0.0, 0.0)

        val scanner = Scanner(System.`in`)
        println("\n==================================================================")
        println("ARES Interactive Remote Controller CLI Ready!")
        println("------------------------------------------------------------------")
        println("Commands:")
        println("  <vx> <vy> <omega> <duration>  - Drive (e.g. '0.5 0 0 1.5')")
        println("  cmd <string_command>         - Send custom command (e.g. 'cmd reset 0 0 0')")
        println("  obstacle <x> <y> <radius>    - Inject virtual obstacle (e.g. 'obstacle 1 1 0.3')")
        println("  tune <param> <value>         - Live tune parameters (e.g. 'tune pathTranslationKp 2.5')")
        println("  logs                         - Download latest .jsonl logs from port 5002")
        println("  exit / quit                  - Stop robot and exit")
        println("==================================================================\n")

        while (true) {
            print("> ")
            if (!scanner.hasNextLine()) break
            val line = scanner.nextLine().trim()
            if (line.isEmpty()) continue
            
            if (line == "exit" || line == "quit") {
                break
            }

            if (line == "logs") {
                println("Fetching logs list from http://$serverIp:5002/api/logs...")
                try {
                    val logsUrl = URL("http://$serverIp:5002/api/logs")
                    val conn = logsUrl.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    
                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        val regex = "\"name\":\"([^\"]+)\"".toRegex()
                        val match = regex.find(responseText)
                        val latestLog = match?.groupValues?.get(1)
                        if (latestLog != null) {
                            val safeName = safeLogBasename(latestLog)
                            println("Downloading latest log file: $safeName...")
                            val encodedName = URLEncoder.encode(safeName, StandardCharsets.UTF_8)
                            val downloadUrl = URL("http://$serverIp:5002/api/download?file=$encodedName")
                            val dlConn = downloadUrl.openConnection() as HttpURLConnection
                            dlConn.requestMethod = "GET"
                            if (dlConn.responseCode == 200) {
                                val downloadRoot = File("ares-downloads").canonicalFile.also { it.mkdirs() }
                                val destFile = File(downloadRoot, localLogFilename(safeName))
                                dlConn.inputStream.use { input ->
                                    destFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                println("Log saved to: ${destFile.absolutePath}")
                            } else {
                                println("Error: Failed to download file (HTTP ${dlConn.responseCode})")
                            }
                        } else {
                            println("No log files found on the server.")
                        }
                    } else {
                        println("Error: Failed to connect to log server (HTTP ${conn.responseCode})")
                    }
                } catch (e: Exception) {
                    println("Error downloading logs: ${e.message}")
                }
                continue
            }

            val parts = line.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val firstWord = parts.first()

            when {
                firstWord == "cmd" -> {
                    val cmdStr = parts.drop(1).joinToString(" ")
                    println("Publishing command: '$cmdStr'")
                    commandPub.set(cmdStr)
                }
                firstWord == "obstacle" -> {
                    if (parts.size != 4) {
                        println("Invalid obstacle format. Expected: obstacle <x> <y> <radius>")
                        continue
                    }
                    val x = parts[1].toDoubleOrNull()
                    val y = parts[2].toDoubleOrNull()
                    val radius = parts[3].toDoubleOrNull()
                    if (x?.isFinite() != true || y?.isFinite() != true || radius?.isFinite() != true || radius <= 0.0) {
                        println("Error: coordinates must be finite and radius must be positive.")
                        continue
                    }
                    val obstacleStr = "{\"x\":$x,\"y\":$y,\"radius\":$radius}"
                    println("Publishing obstacle: '$obstacleStr'")
                    obstaclesPub.set(obstacleStr)
                }
                firstWord == "tune" -> {
                    if (parts.size != 3) {
                        println("Invalid tune format. Expected: tune <parameter> <value>")
                        continue
                    }
                    val param = parts[1]
                    val value = parts[2].toDoubleOrNull()
                    if (value?.isFinite() != true) {
                        println("Error: value must be finite.")
                        continue
                    }
                    println("Publishing tuning parameter: $param = $value")
                    ntInst.getDoubleTopic("Tuning/$param").publish().set(value)
                }
                else -> {
                    if (parts.size != 4) {
                        println("Unknown command or invalid drive format. Expected: vx vy omega duration")
                        continue
                    }
                    val vx = parts[0].toDoubleOrNull()
                    val vy = parts[1].toDoubleOrNull()
                    val omega = parts[2].toDoubleOrNull()
                    val duration = parts[3].toDoubleOrNull()

                    if (
                        vx?.isFinite() != true || vy?.isFinite() != true || omega?.isFinite() != true ||
                        duration?.isFinite() != true || duration <= 0.0
                    ) {
                        println("Error: drive values must be finite and duration must be positive.")
                        continue
                    }

                    println(String.format("Driving vx=%.2f, vy=%.2f, omega=%.2f for %.2fs...", vx, vy, omega, duration))
                    val steps = (duration * 10).toInt().coerceAtLeast(1)
                    
                    for (step in 1..steps) {
                        publishDriveFrame(vx, vy, omega)
                        
                        Thread.sleep(100)
                        
                        val poseArr = poseSub.get()
                        val rx = if (poseArr.isNotEmpty() && poseArr[0] != 0.0) poseArr[0] else poseXSub.get()
                        val ry = if (poseArr.size > 1 && poseArr[1] != 0.0) poseArr[1] else poseYSub.get()
                        val rh = if (poseArr.size > 2 && poseArr[2] != 0.0) poseArr[2] else headingSub.get()
                        
                        println(String.format("  Step %02d/%02d | Pose: X=%.3f m, Y=%.3f m, Heading=%.3f rad", 
                            step, steps, rx, ry, rh))
                    }
                    
                    publishDriveFrame(0.0, 0.0, 0.0)
                    println("Drive command completed. Robot stopped.")
                }
            }
        }
        
        println("Stopping remote controller client...")
        publishDriveFrame(0.0, 0.0, 0.0)
        ntInst.stopClient()
    }

    internal fun safeLogBasename(serverName: String): String {
        require(serverName.isNotBlank()) { "Log filename must not be blank" }
        require(serverName == File(serverName).name) { "Log filename must be a basename" }
        require('/' !in serverName && '\\' !in serverName && ':' !in serverName) {
            "Log filename contains a path separator"
        }
        require(serverName.endsWith(".csv", ignoreCase = true) || serverName.endsWith(".jsonl", ignoreCase = true)) {
            "Unsupported log filename"
        }
        return serverName
    }

    internal fun localLogFilename(serverName: String): String = when {
        serverName.endsWith(".jsonl", ignoreCase = true) -> "downloaded-log.jsonl"
        serverName.endsWith(".csv", ignoreCase = true) -> "downloaded-log.csv"
        else -> error("A validated remote log must use a supported extension")
    }
}
