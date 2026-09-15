package com.areslib.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class DsEventCleaningAuditTest {
    @Test fun `metadata fields and message markers are removed in one event`() {
        assertEquals("motor stopped", DsEventLogParser.cleanXmlTags("<TagVersion>1<time>10<count>2<flags>0<Code>5<location>drive<stack>trace<message> motor stopped"))
    }
    @Test fun `message and details preserve text and line breaks`() {
        assertEquals("warning\nextra details", DsEventLogParser.cleanXmlTags("<message> warning\n<details> extra details<stack>trace"))
    }
    @Test fun `literal angles unknown tags and plain text survive`() {
        assertEquals("x < y <custom>text", DsEventLogParser.cleanXmlTags("  x < y <custom>text  "))
        assertEquals("plain", DsEventLogParser.cleanXmlTags(" plain "))
        assertEquals("", DsEventLogParser.cleanXmlTags("<time>10"))
    }
    @Test fun `legacy marker spacing remains compatible`() {
        assertEquals("<message>no separator", DsEventLogParser.cleanXmlTags("<message>no separator"))
        assertEquals("<details>\ttab", DsEventLogParser.cleanXmlTags("<details>\ttab"))
    }
    @Test fun `large repeated events retain all message content`() {
        val input = "<time>20<message> event\n<Code>5<details> detail\n".repeat(10_000)
        assertEquals("event\ndetail\n".repeat(10_000).trim(), DsEventLogParser.cleanXmlTags(input))
    }
    @Test fun `severity is case insensitive with error precedence`() {
        assertEquals("ERROR", DsEventLogParser.classifySeverity("[WaRn] [ErRoR] failure"))
        assertEquals("ERROR", DsEventLogParser.classifySeverity("warning: failed", "/Errors"))
        assertEquals("WARN", DsEventLogParser.classifySeverity("WARNING: issue"))
        assertEquals("WARN", DsEventLogParser.classifySeverity("text", "/Warn/status"))
        assertEquals("INFO", DsEventLogParser.classifySeverity("normal"))
    }
}
