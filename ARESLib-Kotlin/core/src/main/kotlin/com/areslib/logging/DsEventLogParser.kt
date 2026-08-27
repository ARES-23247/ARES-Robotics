package com.areslib.logging

/**
 * Driver Station `.dsevents` XML cleaning and log severity parser.
 */
object DsEventLogParser {

    private val xmlTags = listOf("<TagVersion>", "<time>", "<count>", "<flags>", "<Code>", "<location>", "<stack>")

    /**
     * Cleans XML tags from raw Driver Station event log messages.
     */
    fun cleanXmlTags(rawMessage: String): String {
        var text = rawMessage
        for (tag in xmlTags) {
            while (text.contains(tag)) {
                val tagIndex = text.indexOf(tag)
                val nextIndex = text.indexOf("<", tagIndex + 1)
                text = if (nextIndex != -1) {
                    text.substring(0, tagIndex) + text.substring(nextIndex)
                } else {
                    text.substring(0, tagIndex)
                }
            }
        }
        return text.replace("<message> ", "").replace("<details> ", "").trim()
    }

    /**
     * Extracts log severity (INFO, WARN, ERROR) from message text and topic names.
     */
    fun classifySeverity(messageText: String, topicName: String = ""): String {
        val lowerText = messageText.lowercase()
        val lowerTopic = topicName.lowercase()
        return when {
            lowerText.contains("[error]") || lowerText.contains("error:") || lowerTopic.contains("error") -> "ERROR"
            lowerText.contains("[warn]") || lowerText.contains("warning:") || lowerTopic.contains("warn") -> "WARN"
            else -> "INFO"
        }
    }
}
