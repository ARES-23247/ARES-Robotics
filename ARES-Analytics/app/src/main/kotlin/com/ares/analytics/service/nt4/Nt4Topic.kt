package com.ares.analytics.service.nt4

import kotlinx.serialization.Serializable

/**
 * Data container representing a NetworkTables 4 (NT4) topic definition published or subscribed by the robot host.
 *
 * Represents an active metadata record in the NT4 schema registry, describing data types, numeric topic IDs,
 * topic names, and property key-value maps (such as persistent flags, units, or target publishing frequency).
 *
 * ### Physical Units & Properties:
 * - Topic ID: Non-negative identifier assigned during NT4 handshake ([id])
 * - Name: Hierarchical slash-delimited path identifier (e.g. `"Drive/Pose_X"`, `"Hardware/Motors/fl/Power"`)
 * - Type: Protocol payload data type string (`"double"`, `"int"`, `"boolean"`, `"string"`, `"double[]"`, `"msgpack"`)
 *
 * ### Thread Safety & Performance Guarantees:
 * Immutable Kotlin data class. Instances can be safely shared across concurrent coroutine contexts and threads
 * without external locking overhead.
 *
 * @property id The numeric topic identifier assigned by the NetworkTables server during announce handshake.
 * @property name The fully qualified topic key path (e.g., `"Drive/Odom_Heading"`).
 * @property type The string data type descriptor mapped to NT4 payload serialization formats.
 * @property properties Metadata properties dictionary associated with the topic (e.g., `"unit"` -> `"rad"`).
 *
 * @see com.ares.analytics.service.Nt4ClientService
 */
@Serializable
data class Nt4Topic(
    val id: Int,
    val name: String,
    val type: String,
    val properties: Map<String, String> = emptyMap()
)

