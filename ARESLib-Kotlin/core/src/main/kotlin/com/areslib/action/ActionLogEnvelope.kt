package com.areslib.action

/**
 * Unified data envelope for JSONL action log records.
 */
data class ActionLogEnvelope(
    val run_id: String = "",
    val robot_id: String = "",
    val match_number: Int = 0,
    val alliance: String = "RED",
    val op_mode: String = "TeleOp",
    val type: String = "Unknown",
    val payloadJson: String = "{}"
)
