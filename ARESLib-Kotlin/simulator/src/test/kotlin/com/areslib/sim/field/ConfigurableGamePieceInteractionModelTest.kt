package com.areslib.sim.field

import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Geometry
import org.dyn4j.world.World
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigurableGamePieceInteractionModelTest {

    @Test
    fun testIntakeCollectsPieceWithinRangeAndRespectsMaxCapacity() {
        val world = World<Body>()
        val robot = Body()
        val model = ConfigurableGamePieceInteractionModel(
            maxCapacity = 1,
            intakeRangeMeters = 0.35,
            intakeRadiusMeters = 0.15,
        )

        val piece1 = Body().apply {
            addFixture(Geometry.createCircle(0.05))
            translate(0.35, 0.0) // Positioned directly in intake zone
        }
        val piece2 = Body().apply {
            addFixture(Geometry.createCircle(0.05))
            translate(0.36, 0.0) // Also in zone
        }
        world.addBody(piece1)
        world.addBody(piece2)
        val gamePieces = mutableListOf(piece1, piece2)

        // Tick 1: Intake applied -> captures piece 1, reaches max capacity (1)
        val count1 = model.update(
            world = world,
            robotBody = robot,
            gamePieces = gamePieces,
            intakeApplied = true,
            flywheelApplied = false,
            transferApplied = false,
            currentInventoryCount = 0,
            robotHeading = 0.0,
            robotX = 0.0,
            robotY = 0.0,
        )
        assertEquals(1, count1)
        assertEquals(1, gamePieces.size)

        // Tick 2: Intake applied with capacity already full -> rejects piece 2
        val count2 = model.update(
            world = world,
            robotBody = robot,
            gamePieces = gamePieces,
            intakeApplied = true,
            flywheelApplied = false,
            transferApplied = false,
            currentInventoryCount = 1,
            robotHeading = 0.0,
            robotX = 0.0,
            robotY = 0.0,
        )
        assertEquals(1, count2)
        assertEquals(1, gamePieces.size)
    }

    @Test
    fun testLaunchingPieceDecrementsInventoryAndSpawnsProjectile() {
        val world = World<Body>()
        val robot = Body()
        val model = ConfigurableGamePieceInteractionModel(maxCapacity = 2)
        val gamePieces = mutableListOf<Body>()

        val newCount = model.update(
            world = world,
            robotBody = robot,
            gamePieces = gamePieces,
            intakeApplied = false,
            flywheelApplied = true,
            transferApplied = true,
            currentInventoryCount = 1,
            robotHeading = 0.0,
            robotX = 0.0,
            robotY = 0.0,
        )
        assertEquals(0, newCount)
        assertEquals(1, gamePieces.size)
        assertEquals(8.0, gamePieces.single().linearVelocity.x, 1e-6)
        assertEquals(0.0, gamePieces.single().linearVelocity.y, 1e-6)
    }

    @Test
    fun capturedPieceMetadataSurvivesInventoryAndEjection() {
        val world = World<Body>()
        val robot = Body()
        val model = ConfigurableGamePieceInteractionModel(maxCapacity = 1)
        val metadata = SimGamePieceBodyFactory.fallback(
            instanceId = "authored-piece-7",
            typeId = "purple-ball",
            diameterMeters = 0.22,
            massKg = 0.31,
        )
        val captured = SimGamePieceBodyFactory.createBody(metadata, 0.35, 0.0)
        world.addBody(captured)
        val gamePieces = mutableListOf(captured)

        val inventory = model.update(
            world, robot, gamePieces,
            intakeApplied = true,
            flywheelApplied = false,
            transferApplied = false,
            currentInventoryCount = 0,
            robotHeading = 0.0,
            robotX = 0.0,
            robotY = 0.0,
        )
        model.update(
            world, robot, gamePieces,
            intakeApplied = false,
            flywheelApplied = true,
            transferApplied = true,
            currentInventoryCount = inventory,
            robotHeading = 0.0,
            robotX = 0.0,
            robotY = 0.0,
        )

        val ejected = SimGamePieceBodyFactory.metadata(gamePieces.single())!!
        assertEquals(metadata.instanceKey, ejected.instanceKey)
        assertEquals(metadata.typeKey, ejected.typeKey)
        assertEquals(0.22, ejected.widthMeters, 1e-9)
    }

    @Test
    fun testFromSubsystemsAggregatesIntakeAndShooterParameters() {
        val intakeDoc = com.areslib.subsystem.SubsystemDocument(
            documentId = "intake",
            displayName = "Intake Subsystem",
            kotlinTypeName = "IntakeSubsystem",
            platform = com.areslib.subsystem.SubsystemPlatform.FTC,
            hardware = listOf(
                com.areslib.subsystem.SubsystemHardwareDocument(
                    hardwareId = "motor",
                    displayName = "Intake Motor",
                    kind = com.areslib.subsystem.SubsystemHardwareKind.MOTOR,
                    safeOutput = 0.0,
                ),
            ),
            stateFields = listOf(
                com.areslib.subsystem.SubsystemStateFieldDocument(
                    fieldId = "power",
                    displayName = "Intake Power",
                    type = com.areslib.subsystem.SubsystemValueType.DOUBLE,
                    role = com.areslib.subsystem.SubsystemFieldRole.TARGET,
                ),
            ),
            implementation = com.areslib.subsystem.SubsystemImplementationDocument(
                simulation = com.areslib.subsystem.SubsystemSimulationDocument(
                    interaction = com.areslib.subsystem.SubsystemSimInteractionDocument(
                        role = com.areslib.subsystem.SimInteractionRole.INTAKE_COLLECTOR,
                        triggerActuatorId = "motor",
                        storageCapacity = 2,
                        intakeDistanceMeters = 0.40,
                        captureRadiusMeters = 0.20,
                    ),
                ),
            ),
        )

        val shooterDoc = com.areslib.subsystem.SubsystemDocument(
            documentId = "shooter",
            displayName = "Shooter Subsystem",
            kotlinTypeName = "ShooterSubsystem",
            platform = com.areslib.subsystem.SubsystemPlatform.FTC,
            hardware = listOf(
                com.areslib.subsystem.SubsystemHardwareDocument(
                    hardwareId = "motor",
                    displayName = "Shooter Motor",
                    kind = com.areslib.subsystem.SubsystemHardwareKind.MOTOR,
                    safeOutput = 0.0,
                ),
            ),
            stateFields = listOf(
                com.areslib.subsystem.SubsystemStateFieldDocument(
                    fieldId = "power",
                    displayName = "Shooter Power",
                    type = com.areslib.subsystem.SubsystemValueType.DOUBLE,
                    role = com.areslib.subsystem.SubsystemFieldRole.TARGET,
                ),
            ),
            implementation = com.areslib.subsystem.SubsystemImplementationDocument(
                simulation = com.areslib.subsystem.SubsystemSimulationDocument(
                    interaction = com.areslib.subsystem.SubsystemSimInteractionDocument(
                        role = com.areslib.subsystem.SimInteractionRole.PROJECTILE_LAUNCHER,
                        triggerActuatorId = "motor",
                        launchSpeedMps = 10.5,
                    ),
                ),
            ),
        )

        val synthesized = ConfigurableGamePieceInteractionModel.fromSubsystems(listOf(intakeDoc, shooterDoc))
        assertEquals(2, synthesized.maxCapacity)
        assertEquals(0.40, synthesized.intakeRangeMeters, 1e-4)
        assertEquals(0.20, synthesized.intakeRadiusMeters, 1e-4)
        assertEquals(10.5, synthesized.launchSpeedMps, 1e-4)
    }
}
