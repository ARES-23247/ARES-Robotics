package com.areslib.routine

import com.areslib.action.RobotAction
import com.areslib.sequencer.*
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RoutineCompilerStructureAuditTest {
    private val tasks = mutableListOf<Task>()
    @BeforeEach fun setClock() { RobotClock.useMockTime(1_000L) }
    @AfterEach fun cleanup() { tasks.forEach { it.reset() }; RobotClock.useSystemTime() }

    @Test fun aCalledDocumentMustBeValidBeforeAnyFactoriesRun() {
        val root = routine("root", "Root") { action("valid"); call("invalid") }
        val invalid = RoutineDocument(documentId = "invalid", name = "Invalid", steps = emptyList())
        var factories = 0
        val result = RoutineCompiler(mapOf("root" to root, "invalid" to invalid), bindings {
            factories++; instant()
        }).compile("root", 1L)
        assertFalse(result.isSuccess)
        assertTrue(result.issues.any { it.documentId == "invalid" && it.code == "empty_routine" })
        assertEquals(0, factories)
        result.task?.releaseRuntimeState()
    }

    @Test fun everyControlFlowKindPreservesActionOrderingAndInterruptedCompanionCleanup() {
        val trace = mutableListOf<String>()
        val helper = routine("helper", "Helper") { action("called") }
        val root = routine("root", "Root") {
            together { action("together"); waitSeconds(0.0) }
            firstToFinish { action("race"); action("hold") }
            deadline(main = { action("deadline") }, companions = { action("hold") })
            repeatTimes(2) { action("repeat") }
            call("helper")
            branch("ready") { then { action("true") }; otherwise { action("false") } }
            waitUntil("ready", 0.1)
            driveTo(2.0, 1.0, 90.0)
        }
        val bindings = bindings { key ->
            object : Task {
                override val name = key
                override fun initialize(state: RobotState): List<RobotAction> {
                    super.initialize(state); if (key != "hold") trace.add(key); return emptyList()
                }
                override fun isCompleted(state: RobotState, elapsedMs: Long) = key != "hold"
                override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                    if (key == "hold") { assertTrue(interrupted); trace.add("cleanup") }
                    return super.end(state, interrupted)
                }
            }.also(tasks::add)
        }.copy(createDriveTask = { drive ->
            assertEquals(Math.PI / 2, drive.target.headingRadians, 1e-12)
            instant { trace.add("drive") }
        })
        val result = RoutineCompiler(mapOf("root" to root, "helper" to helper), bindings).compile("root", 7L)
        assertTrue(result.isSuccess, result.issues.toString())
        val compiled = requireNotNull(result.task).also(tasks::add)
        val executor = TaskExecutor(); executor.addTask(compiled)
        repeat(3) { executor.update(RobotState(), 1_000L) }
        assertEquals(listOf("together", "race", "cleanup", "deadline", "cleanup", "repeat", "repeat", "called", "true", "drive"), trace)
        assertEquals(0, executor.size)
        assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(compiled))
    }

    @Test fun resourcesIncludeDriveMarkersCompanionsArrivalAndCalledRoutines() {
        val helper = routine("helper", "Helper") { action("called") }
        val root = routine("root", "Root") {
            call("helper")
            driveTo(1.0, 2.0, 0.0) {
                atProgress(0.5, "marker"); whileDriving("during"); onArrival("arrival")
            }
        }
        val bindings = bindings { instant() }.copy(
            createDriveTask = { instant() }, resourcesForAction = { setOf("resource.$it") }
        )
        val result = RoutineCompiler(mapOf("root" to root, "helper" to helper), bindings).compile("root", 1L)
        assertTrue(result.isSuccess)
        assertEquals(setOf("drivetrain", "resource.called", "resource.marker", "resource.during", "resource.arrival"), result.resourceKeys)
        requireNotNull(result.task).also(tasks::add).releaseRuntimeState()
    }

    @Test fun primitiveParallelConflictsFailCompilationAndReleaseBothFactoryTasks() {
        var callbacks = 0
        val root = routine("root", "Root") { together { action("one"); action("two") } }
        val result = RoutineCompiler(mapOf("root" to root), bindings {
            object : Task {
                override val name = "Drive"
                override val requiredResources = TaskResources.DRIVE
                override fun isCompleted(state: RobotState, elapsedMs: Long) = true
            }.also(tasks::add).onComplete { callbacks++ }
        }).compile("root", 1L)
        assertFalse(result.isSuccess)
        tasks.forEach(TaskCallbacks::invokeComplete)
        assertEquals(0, callbacks)
    }

    @Test fun falseBranchesAndConditionFactoriesUseTheProvidedArguments() {
        var chosen = ""
        val root = routine("root", "Root") {
            branch("choice", arguments = { text("side", "false") }) {
                then { action("true") }; otherwise { action("false") }
            }
        }
        val bindings = bindings { key -> instant { chosen = key } }.copy(createCondition = { key, arguments ->
            assertEquals("choice", key); assertEquals("false", arguments["side"])
            val predicate: (RobotState) -> Boolean = { false }
            predicate
        })
        val result = RoutineCompiler(mapOf("root" to root), bindings).compile("root", 1L)
        val executor = TaskExecutor(); executor.addTask(requireNotNull(result.task).also(tasks::add))
        executor.update(RobotState(), 1_000L)
        assertEquals("false", chosen)
    }

    @Test fun missingRecursiveAndUnknownConditionRequestsReturnDiagnosticsWithoutExecutableTasks() {
        val root = routine("root", "Root") { call("missing") }
        val compiler = RoutineCompiler(mapOf("root" to root), bindings { instant() })
        assertFalse(compiler.compile("absent", 1L).isSuccess)
        assertFalse(compiler.compile("root", 2L).isSuccess)
        val recursive = root.copy(steps = listOf(RoutineStep.call("root")))
        assertFalse(RoutineCompiler(mapOf("root" to recursive), bindings { instant() }).compile("root", 3L).isSuccess)
        val condition = routine("condition", "Condition") { waitUntil("unknown", 1.0) }
        val noConditions = bindings { instant() }.copy(createCondition = { _, _ -> null })
        assertFalse(RoutineCompiler(mapOf("condition" to condition), noConditions).compile("condition", 4L).isSuccess)
        assertTrue(tasks.isEmpty())
    }

    @Test fun dslBuildersOwnTheirSnapshotsAndValidateSingleStepAndTypedArgumentBoundaries() {
        lateinit var retained: RoutineArgumentsBuilder
        val document = routine("dsl", "DSL") {
            identified("custom-id") {
                action("typed") {
                    retained = this; integer("count", 9L); boolean("enabled", true); option("mode", RoutineStartPolicy.QUEUE)
                }
            }
        }
        retained.text("later", "value")
        assertEquals("custom-id", document.steps.single().stepId)
        assertEquals(mapOf("count" to "9", "enabled" to "true", "mode" to "QUEUE"), document.steps.single().arguments)
        assertThrows(IllegalArgumentException::class.java) {
            routine("invalid", "Invalid") { identified("many") { waitSeconds(0.0); waitSeconds(1.0) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            routine("invalid", "Invalid") { deadline(main = {}, companions = {}) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            routine("invalid", "Invalid") { branch("ready") { otherwise { waitSeconds(0.0) } } }
        }
    }

    @Test fun typedReaderHandlesMissingAndMalformedBooleansEnumsTextAndFiniteExtremes() {
        val reader = CapabilityArgumentReader("typed", mapOf("bool" to "FALSE", "enum" to "B", "text" to "", "number" to "1.7976931348623157E308"), setOf("bool", "enum", "text", "number"))
        assertFalse(reader.requiredBoolean("bool"))
        assertEquals("B", reader.requiredEnum("enum", setOf("A", "B")))
        assertEquals("", reader.requiredText("text"))
        assertEquals(Double.MAX_VALUE, reader.requiredNumber("number"))
        val empty = CapabilityArgumentReader("typed", emptyMap(), emptySet())
        assertNull(empty.optionalBoolean("missing")); assertNull(empty.optionalEnum("missing", setOf("A")))
        assertThrows(IllegalArgumentException::class.java) { empty.requiredBoolean("missing") }
        assertThrows(IllegalArgumentException::class.java) { empty.requiredText("missing") }
        assertThrows(IllegalArgumentException::class.java) { empty.requiredEnum("missing", setOf("A")) }
        assertThrows(IllegalArgumentException::class.java) { empty.optionalEnum("missing", setOf("A"), "B") }
        assertThrows(IllegalArgumentException::class.java) {
            CapabilityArgumentReader("typed", mapOf("bool" to "1"), setOf("bool")).requiredBoolean("bool")
        }
    }

    private fun bindings(factory: (String) -> Task?) = RoutineRuntimeBindings(
        createActionTask = { key, _ -> factory(key) }, createCondition = { _, _ -> { _ -> true } }
    )
    private fun instant(start: () -> Unit = {}): Task = object : Task {
        override val name = "Instant"
        override fun initialize(state: RobotState): List<RobotAction> { super.initialize(state); start(); return emptyList() }
        override fun isCompleted(state: RobotState, elapsedMs: Long) = true
    }.also(tasks::add)
}
