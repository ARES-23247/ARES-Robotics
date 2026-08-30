package com.areslib.subsystem

import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.util.parseJsonElement
import com.areslib.util.sha256Hex
import com.google.gson.GsonBuilder

object SubsystemDocumentCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(document: SubsystemDocument): String {
        requireValid(document)
        return gson.toJson(document)
    }

    @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL", "UNNECESSARY_NOT_NULL_ASSERTION") // Gson/Unsafe can deliver null for non-null fields; see the note below
    fun decode(json: String): SubsystemDocument {
        val document = try {
            val root = parseJsonElement(json).asJsonObject
            val schemaVersion = root.get("schemaVersion")?.asInt
            require(schemaVersion == ARES_SUBSYSTEM_SCHEMA_VERSION) {
                "Unsupported subsystem schema $schemaVersion"
            }
            require(root.get("implementation")?.isJsonObject == true) {
                "Subsystem implementation metadata is required"
            }
            require(root.get("displayName")?.isJsonPrimitive == true &&
                root.get("kotlinTypeName")?.isJsonPrimitive == true
            ) {
                "Subsystem displayName and kotlinTypeName are required"
            }
            require(root.getAsJsonObject("safety")?.get("homing")?.isJsonObject == true) {
                "Subsystem homing metadata is required"
            }
            require(root.get("tuningParameters")?.isJsonArray == true) {
                "Subsystem tuningParameters are required (use an empty array when none are declared)"
            }
            val implementation = root.getAsJsonObject("implementation")
            require(implementation.has("kind") && implementation.has("ownership")) {
                "Subsystem implementation kind and ownership are required"
            }
            // The normalization below is load-bearing: Gson allocates via Unsafe
            // without calling constructors, leaving omitted or defaulted fields null at runtime.
            // We fully normalize and re-instantiate each model with non-null defaults.
            val parsed = gson.fromJson(json, SubsystemDocument::class.java)
                ?: throw IllegalArgumentException("Subsystem document is empty")
            normalizeSubsystemDocument(
                parsed,
                feedbackTimeoutWasDeclared = root.getAsJsonObject("safety")?.has("feedbackTimeoutMs") == true,
                generateMockIoWasDeclared = root.has("generateMockIo"),
                generateTestWasDeclared = root.has("generateTest"),
            )
        } catch (error: Exception) {
            throw IllegalArgumentException("Subsystem document is not valid JSON: ${error.message}", error)
        }
        requireValid(document)
        return document
    }

    @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL", "UNNECESSARY_NOT_NULL_ASSERTION")
    private fun normalizeSubsystemDocument(
        doc: SubsystemDocument,
        feedbackTimeoutWasDeclared: Boolean,
        generateMockIoWasDeclared: Boolean,
        generateTestWasDeclared: Boolean,
    ): SubsystemDocument {
        val hardware = (doc.hardware ?: emptyList()).map { h ->
            val conn = h.connection
            SubsystemHardwareDocument(
                hardwareId = h.hardwareId ?: "",
                displayName = h.displayName ?: h.hardwareId ?: "",
                kind = h.kind ?: SubsystemHardwareKind.MOTOR,
                connection = SubsystemHardwareConnection(
                    hardwareMapName = conn?.hardwareMapName,
                    canId = conn?.canId,
                    canBus = conn?.canBus ?: "rio",
                    channel = conn?.channel,
                    secondaryChannel = conn?.secondaryChannel,
                    pneumaticsModuleType = conn?.pneumaticsModuleType,
                ),
                required = h.required ?: true,
                inverted = h.inverted ?: false,
                measurements = (h.measurements ?: emptyList()).map { m ->
                    SubsystemMeasurementDocument(
                        fieldId = m.fieldId ?: "",
                        source = m.source ?: SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
                        scale = m.scale ?: 1.0,
                        offset = m.offset ?: 0.0,
                        maxAgeMs = m.maxAgeMs,
                        validMinimum = m.validMinimum,
                        validMaximum = m.validMaximum,
                    )
                },
                currentLimitAmps = h.currentLimitAmps,
                safeOutput = h.safeOutput,
                description = h.description ?: "",
                uid = h.uid ?: h.hardwareId ?: "",
                following = h.following?.let { f ->
                    SubsystemFollowerDocument(
                        leaderId = f.leaderId ?: "",
                        transform = f.transform ?: SubsystemFollowerTransform.SAME_DIRECTION,
                    )
                },
                encoderCountsPerRevolution = h.encoderCountsPerRevolution,
                distanceMetersPerVolt = h.distanceMetersPerVolt,
                imuLogoFacingDirection = h.imuLogoFacingDirection,
                imuUsbFacingDirection = h.imuUsbFacingDirection,
                visualPlacement = h.visualPlacement?.let { placement ->
                    SubsystemVisualPlacementDocument(
                        anchor = placement.anchor ?: SubsystemVisualAnchor.UNSPECIFIED,
                        forwardFraction = placement.forwardFraction ?: 0.0,
                        leftFraction = placement.leftFraction ?: 0.0,
                    )
                },
            )
        }

        val stateFields = (doc.stateFields ?: emptyList()).map { f ->
            SubsystemStateFieldDocument(
                fieldId = f.fieldId ?: "",
                displayName = f.displayName ?: f.fieldId ?: "",
                type = f.type ?: SubsystemValueType.DOUBLE,
                role = f.role ?: SubsystemFieldRole.TARGET,
                unit = f.unit,
                defaultNumber = f.defaultNumber,
                defaultBoolean = f.defaultBoolean,
                defaultInt = f.defaultInt,
                defaultText = f.defaultText,
                minimum = f.minimum,
                maximum = f.maximum,
                description = f.description ?: "",
                uid = f.uid ?: f.fieldId ?: "",
            )
        }

        val controlLoops = (doc.controlLoops ?: emptyList()).map { l ->
            val ff = l.feedforward
            SubsystemControlLoopDocument(
                loopId = l.loopId ?: "",
                displayName = l.displayName ?: l.loopId ?: "",
                strategy = l.strategy ?: SubsystemControlStrategy.DIRECT,
                actuatorId = l.actuatorId ?: "",
                targetFieldId = l.targetFieldId ?: "",
                measurementFieldId = l.measurementFieldId,
                kP = l.kP ?: 0.0,
                kI = l.kI ?: 0.0,
                kD = l.kD ?: 0.0,
                motionProfile = SubsystemMotionProfileDocument(
                    maximumVelocity = l.motionProfile?.maximumVelocity ?: 1.0,
                    maximumAcceleration = l.motionProfile?.maximumAcceleration ?: 2.0,
                ),
                feedforward = SubsystemFeedforwardDocument(
                    kind = ff?.kind ?: SubsystemFeedforwardKind.NONE,
                    kS = ff?.kS ?: 0.0,
                    kV = ff?.kV ?: 0.0,
                    kA = ff?.kA ?: 0.0,
                    kG = ff?.kG ?: 0.0,
                    velocityFieldId = ff?.velocityFieldId,
                    accelerationFieldId = ff?.accelerationFieldId,
                    gravityAngleFieldId = ff?.gravityAngleFieldId,
                    linkageJoint = ff?.linkageJoint,
                ),
                derivativeFilterTimeConstantSeconds = l.derivativeFilterTimeConstantSeconds ?: 0.02,
                continuousInput = SubsystemContinuousInputDocument(
                    enabled = l.continuousInput?.enabled ?: false,
                    minimumInput = l.continuousInput?.minimumInput ?: -Math.PI,
                    maximumInput = l.continuousInput?.maximumInput ?: Math.PI,
                ),
                tolerance = l.tolerance ?: 0.0,
                hysteresis = l.hysteresis ?: 0.0,
                minimumOutput = l.minimumOutput ?: -12.0,
                maximumOutput = l.maximumOutput ?: 12.0,
                description = l.description ?: "",
                uid = l.uid ?: l.loopId ?: "",
            )
        }

        val tuningParameters = (doc.tuningParameters ?: emptyList()).map { p ->
            TuningParameterDeclaration(
                uid = p.uid ?: "",
                key = p.key ?: "",
                componentUid = p.componentUid ?: "",
                displayName = p.displayName ?: p.key ?: "",
                description = p.description ?: "",
                type = p.type ?: com.areslib.tuning.TuningParameterType.DOUBLE,
                unit = p.unit,
                minimum = p.minimum,
                maximum = p.maximum,
                defaultValue = p.defaultValue ?: com.areslib.tuning.TuningValue(0.0),
                enumOptions = p.enumOptions ?: emptyList(),
                applyPolicy = p.applyPolicy ?: com.areslib.tuning.TuningApplyPolicy.LIVE_SAFE,
            )
        }

        val interlocks = (doc.interlocks ?: emptyList()).map { i ->
            SubsystemInterlockDocument(
                interlockId = i.interlockId ?: "",
                targetSubsystemUid = i.targetSubsystemUid ?: "",
                targetFieldId = i.targetFieldId ?: "",
                comparison = i.comparison ?: InterlockComparison.LESS_THAN,
                thresholdValue = i.thresholdValue ?: 0.0,
                targetStateName = i.targetStateName,
                forbiddenZoneDescription = i.forbiddenZoneDescription ?: "",
                safeFallbackValue = i.safeFallbackValue,
            )
        }

        val link = doc.linkage
        val linkage = SubsystemLinkageDocument(
            enabled = link?.enabled ?: false,
            link1LengthMeters = link?.link1LengthMeters ?: 0.35,
            link2LengthMeters = link?.link2LengthMeters ?: 0.25,
            link1MassKg = link?.link1MassKg ?: 0.5,
            link2MassKg = link?.link2MassKg ?: 0.3,
            link1CenterOfMassMeters = link?.link1CenterOfMassMeters ?: ((link?.link1LengthMeters ?: 0.35) / 2.0),
            link2CenterOfMassMeters = link?.link2CenterOfMassMeters ?: ((link?.link2LengthMeters ?: 0.25) / 2.0),
            joint1MinRad = link?.joint1MinRad ?: -Math.PI,
            joint1MaxRad = link?.joint1MaxRad ?: Math.PI,
            joint2MinRad = link?.joint2MinRad ?: -Math.PI,
            joint2MaxRad = link?.joint2MaxRad ?: Math.PI,
            joint1ActuatorId = link?.joint1ActuatorId,
            joint2ActuatorId = link?.joint2ActuatorId,
            joint1AngleFieldId = link?.joint1AngleFieldId,
            joint2AngleFieldId = link?.joint2AngleFieldId,
            joint1TorquePerVoltNm = link?.joint1TorquePerVoltNm ?: 0.5,
            joint2TorquePerVoltNm = link?.joint2TorquePerVoltNm ?: 0.35,
            joint1DampingNmPerRadPerSec = link?.joint1DampingNmPerRadPerSec ?: 0.08,
            joint2DampingNmPerRadPerSec = link?.joint2DampingNmPerRadPerSec ?: 0.05,
        )

        val impl = doc.implementation
        val sim = impl?.simulation
        val inter = sim?.interaction
        val teach = impl?.teaching
        val genMock = when {
            generateMockIoWasDeclared -> doc.generateMockIo
            impl?.kind == SubsystemImplementationKind.HAND_AUTHORED -> false
            sim?.support == SubsystemSimulationSupport.UNAVAILABLE -> false
            else -> true
        }
        val genTest = when {
            generateTestWasDeclared -> doc.generateTest
            impl?.kind == SubsystemImplementationKind.HAND_AUTHORED -> false
            else -> true
        }
        val simSupport = when (impl?.kind) {
            SubsystemImplementationKind.DECLARATIVE_GENERATED,
            SubsystemImplementationKind.GENERATED_STARTER -> if (genMock) SubsystemSimulationSupport.GENERATED_MOCK else SubsystemSimulationSupport.UNAVAILABLE
            SubsystemImplementationKind.HAND_AUTHORED -> sim?.support ?: SubsystemSimulationSupport.UNAVAILABLE
            null -> if (genMock) SubsystemSimulationSupport.GENERATED_MOCK else SubsystemSimulationSupport.UNAVAILABLE
        }

        val implementation = SubsystemImplementationDocument(
            kind = impl?.kind ?: SubsystemImplementationKind.GENERATED_STARTER,
            ownership = impl?.ownership ?: SubsystemSourceOwnership.GENERATED_STARTER,
            modulePath = impl?.modulePath,
            sourceFiles = impl?.sourceFiles ?: emptyList(),
            subsystemClassName = impl?.subsystemClassName,
            ioContractClassName = impl?.ioContractClassName,
            hardwareAdapterClassName = impl?.hardwareAdapterClassName,
            simulation = SubsystemSimulationDocument(
                support = simSupport,
                adapterClassName = if (impl?.kind?.isAresGenerated() != false) null else sim?.adapterClassName,
                interaction = SubsystemSimInteractionDocument(
                    role = inter?.role ?: SimInteractionRole.NONE,
                    triggerActuatorId = inter?.triggerActuatorId,
                    triggerThreshold = inter?.triggerThreshold ?: 1.0,
                    storageCapacity = inter?.storageCapacity ?: 1,
                    intakeDistanceMeters = inter?.intakeDistanceMeters ?: 0.35,
                    captureRadiusMeters = inter?.captureRadiusMeters ?: 0.15,
                    launchSpeedMps = inter?.launchSpeedMps ?: 8.0,
                    launchElevationDeg = inter?.launchElevationDeg ?: 45.0,
                    beamBreakFieldId = inter?.beamBreakFieldId,
                ),
            ),
            teaching = SubsystemTeachingDocument(
                level = teach?.level ?: SubsystemTeachingLevel.INTERMEDIATE,
                summary = teach?.summary ?: "",
                documentationPath = teach?.documentationPath,
                concepts = teach?.concepts ?: emptyList(),
            ),
        )

        val s = doc.safety
        val homing = s?.homing
        val fault = s?.faultRecovery
        val safety = SubsystemSafetyDocument(
            // Gson supplies the Kotlin field initializer when this nullable property is omitted.
            // Preserve an intentionally absent lease without changing an explicitly declared one.
            feedbackTimeoutMs = if (feedbackTimeoutWasDeclared) s?.feedbackTimeoutMs else null,
            homing = SubsystemHomingDocument(
                method = homing?.method ?: SubsystemHomingMethod.NONE,
                actuatorId = homing?.actuatorId,
                searchOutput = homing?.searchOutput,
                evidence = (homing?.evidence ?: emptyList()).map { e ->
                    SubsystemHomingEvidenceDocument(
                        fieldId = e.fieldId ?: "",
                        comparison = e.comparison ?: SubsystemHomingComparison.TRUE,
                        threshold = e.threshold,
                    )
                },
                dwellMs = homing?.dwellMs ?: 250L,
                timeoutMs = homing?.timeoutMs ?: 3_000L,
                zeroPosition = homing?.zeroPosition ?: 0.0,
            ),
            faultRecovery = SubsystemFaultRecoveryDocument(
                enabled = fault?.enabled ?: false,
                actuatorId = fault?.actuatorId,
                currentFieldId = fault?.currentFieldId,
                currentThresholdAmps = fault?.currentThresholdAmps ?: 18.0,
                currentDurationMs = fault?.currentDurationMs ?: 250L,
                recoveryAction = fault?.recoveryAction ?: FaultRecoveryActionKind.REVERSE_BRIEFLY,
                reverseDurationMs = fault?.reverseDurationMs ?: 400L,
                reverseDutyCycle = fault?.reverseDutyCycle ?: -0.40,
                maxRetries = fault?.maxRetries ?: 3,
            ),
            requiresCalibration = s?.requiresCalibration ?: false,
            requiresConfigurationHealth = s?.requiresConfigurationHealth ?: true,
            requiresCurrentMonitoring = s?.requiresCurrentMonitoring ?: false,
            latchOutputFaults = s?.latchOutputFaults ?: true,
            requiresExplicitNeutralRecovery = s?.requiresExplicitNeutralRecovery ?: true,
            telemetryEnabled = s?.telemetryEnabled ?: true,
            zeroAllocationPeriodic = s?.zeroAllocationPeriodic ?: true,
        )

        return SubsystemDocument(
            schemaVersion = doc.schemaVersion ?: ARES_SUBSYSTEM_SCHEMA_VERSION,
            documentId = doc.documentId ?: "",
            displayName = doc.displayName ?: doc.documentId ?: "",
            kotlinTypeName = doc.kotlinTypeName ?: "Subsystem",
            description = doc.description ?: "",
            platform = doc.platform ?: SubsystemPlatform.FTC,
            revision = doc.revision ?: 1,
            parentContentHash = doc.parentContentHash,
            hardware = hardware,
            stateFields = stateFields,
            controlLoops = controlLoops,
            tuningParameters = tuningParameters,
            template = doc.template ?: SubsystemTemplate.ADVANCED_CUSTOM,
            implementation = implementation,
            capabilityActionKeys = doc.capabilityActionKeys ?: emptyList(),
            safety = safety,
            interlocks = interlocks,
            linkage = linkage,
            autonomousResourceKey = doc.autonomousResourceKey,
            requiredAtStartup = doc.requiredAtStartup ?: true,
            generateMockIo = genMock,
            generateTest = genTest,
            uid = doc.uid ?: doc.documentId ?: "",
        )
    }

    fun contentHash(document: SubsystemDocument): String = sha256Hex(encode(document))

    private fun requireValid(document: SubsystemDocument) {
        val issues = SubsystemSchema.validate(document)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
    }
}
