package com.areslib.action

import com.areslib.state.Alliance
import com.areslib.state.SubsystemState
import com.google.gson.annotations.SerializedName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class AliasAction(val values: List<Int>, override val timestampMs: Long) : RobotAction
private data class NamedAction(val value: Int, override val timestampMs: Long) : RobotAction
private data class CollisionAction(val value: Int, override val timestampMs: Long) : RobotAction
private data class BlankAction(override val timestampMs: Long) : RobotAction
private data class ReservedAction(override val timestampMs: Long) : RobotAction
private data class UnregisteredAction(override val timestampMs: Long) : RobotAction
private data class GetterAction(val value: Int) : RobotAction
private data class OffsetTimestampAction(@SerializedName("timestampMs") val recorded: Long) : RobotAction {
    override val timestampMs: Long get() = recorded + 1
}
private data class NullableAction(
    val label: String? = "default",
    override val timestampMs: Long = 10L
) : RobotAction
private data class AliasState(val value: Int) : SubsystemState
private data class NamedState(val value: Int) : SubsystemState
private data class CollisionState(val value: Int) : SubsystemState
private data class BlankState(val value: Int) : SubsystemState
private data class NullableState(val label: String? = "default") : SubsystemState

class ReplayRegistrationAuditTest {
    @TempDir lateinit var directory: File

    private fun record(action: RobotAction): File {
        val encoded = ActionReplay.encodeForLog(action)
        return File(directory, "action.jsonl").also { it.writeText(
            """{"schema_version":1,"type":"${encoded.type}","payload":${encoded.payload}}"""
        ) }
    }

    @Test fun `custom aliases are idempotent and snapshot mutable nested values`() {
        ActionReplay.registerAction("audit93.alias", AliasAction::class.java)
        ActionReplay.registerAction("audit93.alias", AliasAction::class.java)
        val values = mutableListOf(1, 2)
        val source = AliasAction(values, Long.MIN_VALUE)
        val file = record(source)
        values[0] = 99
        assertTrue(file.readText().contains("\"type\":\"audit93.alias\""))
        assertEquals(AliasAction(listOf(1, 2), Long.MIN_VALUE), ActionReplay.parseActions(file).single())
    }

    @Test fun `class name overload round trips a custom action`() {
        ActionReplay.registerAction(NamedAction::class.java)
        val source = NamedAction(7, Long.MAX_VALUE)
        assertEquals(NamedAction::class.java.name, ActionReplay.encodeForLog(source).type)
        assertEquals(source, ActionReplay.parseActions(record(source)).single())
    }

    @Test fun `failed action collisions leave both registry directions intact`() {
        val alias = "audit93.collision"
        ActionReplay.registerAction(alias, CollisionAction::class.java)
        assertFailsWith<IllegalArgumentException> { ActionReplay.registerAction(alias, BlankAction::class.java) }
        assertFailsWith<IllegalArgumentException> { ActionReplay.registerAction("audit93.other", CollisionAction::class.java) }
        val source = CollisionAction(3, 41L)
        assertEquals(alias, ActionReplay.encodeForLog(source).type)
        assertEquals(source, ActionReplay.parseActions(record(source)).single())
        assertEquals(BlankAction::class.java.name, ActionReplay.encodeForLog(BlankAction(2L)).type)
    }

    @Test fun `blank and reserved action names or classes cannot shadow core codecs`() {
        for (alias in listOf("", " \t\n")) {
            assertFailsWith<IllegalArgumentException> { ActionReplay.registerAction(alias, BlankAction::class.java) }
        }
        assertFailsWith<IllegalArgumentException> { ActionReplay.registerAction("SetAlliance", ReservedAction::class.java) }
        assertFailsWith<IllegalArgumentException> {
            ActionReplay.registerAction("audit93.core", RobotAction.SetAlliance::class.java)
        }
        ActionReplay.registerAction("SetAlliance", RobotAction.SetAlliance::class.java)
        val source = RobotAction.SetAlliance(Alliance.RED, 7L)
        assertEquals(source, ActionReplay.parseActions(record(source)).single())
    }

    @Test fun `unregistered custom actions can be recorded but fail replay visibly`() {
        val failure = assertFailsWith<ActionReplayException> {
            ActionReplay.parseActions(record(UnregisteredAction(1L)))
        }
        assertTrue(failure.message.orEmpty().contains("registerAction"))
    }

    @Test fun `subsystem alias and class name overloads round trip both update forms`() {
        ActionReplay.registerSubsystemState("audit93.state", AliasState::class.java)
        ActionReplay.registerSubsystemState("audit93.state", AliasState::class.java)
        ActionReplay.registerSubsystemState(NamedState::class.java)
        val actions = listOf(RobotAction.UpdateSubsystemState(AliasState(8), 1L),
            RobotAction.UpdateNamedSubsystemState("arm", NamedState(9), 2L))
        for (source in actions) assertEquals(source, ActionReplay.parseActions(record(source)).single())
    }

    @Test fun `subsystem collisions and blank names preserve the existing codec`() {
        val alias = "audit93.state.collision"
        ActionReplay.registerSubsystemState(alias, CollisionState::class.java)
        assertFailsWith<IllegalArgumentException> { ActionReplay.registerSubsystemState(alias, BlankState::class.java) }
        assertFailsWith<IllegalArgumentException> {
            ActionReplay.registerSubsystemState("audit93.state.other", CollisionState::class.java)
        }
        for (name in listOf("", " \t\n")) {
            assertFailsWith<IllegalArgumentException> { ActionReplay.registerSubsystemState(name, BlankState::class.java) }
        }
        val source = RobotAction.UpdateSubsystemState(CollisionState(4), 2L)
        assertEquals(source, ActionReplay.parseActions(record(source)).single())
        val blank = ActionReplay.encodeForLog(RobotAction.UpdateSubsystemState(BlankState(5), 3L))
        assertEquals(BlankState::class.java.name, blank.payload["_ares_subsystem_state_type"].asString)
    }

    @Test fun `default clock getter actions are rejected before entering the writer queue`() {
        val logger = ActionLogger(logDirectory = directory)
        try {
            logger.logAction(GetterAction(1))
            logger.logAction(RobotAction.SetAlliance(Alliance.BLUE, 7L))
        } finally { logger.stop() }
        assertEquals(1L, logger.droppedActionCount)
        val file = directory.listFiles().orEmpty().single { it.extension == "jsonl" }
        assertEquals(listOf(RobotAction.SetAlliance(Alliance.BLUE, 7L)), ActionReplay.parseActions(file))
    }

    @Test fun `timestamp payload must agree with the observed custom action epoch when encoding`() {
        assertFailsWith<ActionReplayException> { ActionReplay.encodeForLog(OffsetTimestampAction(100L)) }
    }

    @Test fun `timestamp payload must agree with the decoded custom action epoch`() {
        ActionReplay.registerAction(OffsetTimestampAction::class.java)
        val file = File(directory, "mismatch.jsonl").also { it.writeText(
            """{"schema_version":1,"type":"${OffsetTimestampAction::class.java.name}","payload":{"timestampMs":100}}"""
        ) }
        assertFailsWith<ActionReplayException> { ActionReplay.parseActions(file) }
    }

    @Test fun `custom action nulls do not become constructor defaults through the logger`() {
        ActionReplay.registerAction(NullableAction::class.java)
        val source = NullableAction(label = null, timestampMs = 12L)
        val logger = ActionLogger(logDirectory = directory)
        try { logger.logAction(source) } finally { logger.stop() }
        assertEquals(0L, logger.droppedActionCount)
        val file = directory.listFiles().orEmpty().single { it.extension == "jsonl" }
        assertEquals(source, ActionReplay.parseActions(file).single())
    }

    @Test fun `custom subsystem nulls do not become constructor defaults through the logger`() {
        ActionReplay.registerSubsystemState(NullableState::class.java)
        val source = RobotAction.UpdateNamedSubsystemState("arm", NullableState(null), 12L)
        val logger = ActionLogger(logDirectory = directory)
        try { logger.logAction(source) } finally { logger.stop() }
        assertEquals(0L, logger.droppedActionCount)
        val file = directory.listFiles().orEmpty().single { it.extension == "jsonl" }
        assertEquals(source, ActionReplay.parseActions(file).single())
    }
}
