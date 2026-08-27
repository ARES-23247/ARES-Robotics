package com.ares.analytics.ui.theme

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class BrightButtonForegroundTest {
    @Test
    fun `bright filled button declarations always specify the shared dark foreground`() {
        val sourceRoot = Path.of("src", "main", "kotlin")
        assertTrue(sourceRoot.isDirectory(), "Expected app source root at ${sourceRoot.toAbsolutePath()}")

        val failures = mutableListOf<String>()
        Files.walk(sourceRoot).use { paths ->
            paths.filter { it.extension == "kt" }.forEach { path ->
                extractButtonColorCalls(path.readText()).forEachIndexed { index, call ->
                    if (BRIGHT_CONTAINER.containsMatchIn(call) && !ON_ACCENT.containsMatchIn(call)) {
                        failures += "${path.toString().replace('\\', '/')}: buttonColors call ${index + 1}"
                    }
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Bright button fills must use contentColor = AresOnAccent; found:\n${failures.joinToString("\n")}",
        )
    }

    private fun extractButtonColorCalls(source: String): List<String> {
        val calls = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val start = source.indexOf("ButtonDefaults.buttonColors(", searchFrom)
            if (start < 0) return calls
            var depth = 0
            var cursor = source.indexOf('(', start)
            val callStart = cursor
            while (cursor < source.length) {
                when (source[cursor]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            calls += source.substring(callStart + 1, cursor)
                            searchFrom = cursor + 1
                            break
                        }
                    }
                }
                cursor++
            }
            if (cursor >= source.length) return calls
        }
    }

    private companion object {
        val BRIGHT_CONTAINER = Regex(
            """containerColor\s*=\s*(AresCyan|AresGold|AresAmber|AresGreen|AresError)\b""",
        )
        val ON_ACCENT = Regex("""contentColor\s*=\s*AresOnAccent\b""")
    }
}
