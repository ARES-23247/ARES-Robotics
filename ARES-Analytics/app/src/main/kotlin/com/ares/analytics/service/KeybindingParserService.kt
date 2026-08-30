package com.ares.analytics.service

import com.ares.analytics.shared.models.ControllerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service for parsing Kotlin and Java source code AST annotations to discover FTC/FRC gamepad controller keybindings.
 *
 * Scans robot source files for ARESLib controller DSL method invocations (`gamepad1.a.onPress("Intake Release")`),
 * building a structured list of [ControllerBinding] records for driver station visual overlays.
 *
 * ### DSL Matching Expression:
 * `Regex("""\b([a-zA-Z0-9_]+)\.([a-zA-Z_]+)\.(onPress|onRelease|whilePressed|label)\(\s*"([^"]+)"\s*\)""")`
 *
 * ### Thread Safety & Performance Guarantees:
 * Executes source file tree traversal asynchronously on `Dispatchers.IO`.
 *
 * @see com.ares.analytics.shared.models.ControllerBinding
 */
class KeybindingParserService {
    /**
     * Scans project directory source code files for gamepad button DSL bindings.
     *
     * @param projectPath Absolute directory path of the target robot project.
     * @return List of parsed [ControllerBinding] objects.
     */
    suspend fun parseBindings(projectPath: String): List<ControllerBinding> = withContext(Dispatchers.IO) {
        val root = File(projectPath)
        val bindings = mutableListOf<ControllerBinding>()
        if (!root.exists()) return@withContext emptyList()
        val ktFiles = root.walkTopDown()
            .filter { it.extension == "kt" || it.extension == "java" }
            .filter { !it.absolutePath.contains("build") }
            .toList()
        val dslRegex = Regex("""\b([a-zA-Z0-9_]+)\.([a-zA-Z_]+)\.(onPress|onRelease|whilePressed|label)\(\s*"([^"]+)"\s*\)""")

        for (file in ktFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val match = dslRegex.find(line)
                if (match != null) {
                    val varName = match.groupValues[1]
                    val button = match.groupValues[2]
                    val eventType = match.groupValues[3] // onPress etc
                    val description = match.groupValues[4]
                    val gamepadId = if (varName.contains("operator") || varName.contains("2")) "gamepad2" else "gamepad1"
                    val actionLabel = "[$eventType] $description"

                    bindings.add(ControllerBinding(gamepadId, button, actionLabel, file.name, index + 1))
                }
            }
        }

        bindings
    }
}
