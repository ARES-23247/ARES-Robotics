package com.areslib.logging

/**
 * Driver Station `.dsevents` XML cleaning and log severity parser.
 */
object DsEventLogParser {

    private val xmlTags = listOf("<TagVersion>", "<time>", "<count>", "<flags>", "<Code>", "<location>", "<stack>")

    /**
     * Cleans legacy tag-delimited Driver Station messages (not a general XML document).
     * Metadata runs end at the next opening angle bracket; unknown tags and the legacy
     * space after message/details markers retain their existing interpretation.
     */
    fun cleanXmlTags(rawMessage: String): String {
        val text = StringBuilder(rawMessage.length)
        var cursor = 0
        while (cursor < rawMessage.length) {
            val tagIndex = rawMessage.indexOf('<', cursor)
            if (tagIndex < 0) {
                text.append(rawMessage, cursor, rawMessage.length)
                break
            }
            text.append(rawMessage, cursor, tagIndex)
            val metadataTag = xmlTags.firstOrNull { rawMessage.startsWith(it, tagIndex) }
            cursor = when {
                metadataTag != null -> rawMessage.indexOf('<', tagIndex + metadataTag.length)
                    .let { if (it < 0) rawMessage.length else it }
                rawMessage.startsWith("<message> ", tagIndex) -> tagIndex + "<message> ".length
                rawMessage.startsWith("<details> ", tagIndex) -> tagIndex + "<details> ".length
                else -> { text.append('<'); tagIndex + 1 }
            }
        }
        return text.toString().trim()
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
