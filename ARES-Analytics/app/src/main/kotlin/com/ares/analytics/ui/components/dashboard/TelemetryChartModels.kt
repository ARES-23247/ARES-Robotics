package com.ares.analytics.ui.components.dashboard

fun buildSignalTree(keys: List<String>): SignalNode {
    val root = SignalNode("", "", false)
    for (topic in keys) {
        val parts = topic.split("/").filter { it.isNotEmpty() }
        var current = root
        var currentPath = ""
        for (i in parts.indices) {
            val part = parts[i]
            currentPath += "/$part"
            val isLeaf = (i == parts.lastIndex)
            current = current.children.getOrPut(part) {
                SignalNode(part, currentPath, isLeaf)
            }
        }
    }
    return root
}

data class TelemetryPoint(val timestampMs: Long, val value: Double)
