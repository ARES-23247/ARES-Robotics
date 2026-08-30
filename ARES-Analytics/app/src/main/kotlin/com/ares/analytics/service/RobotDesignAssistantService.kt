package com.ares.analytics.service

import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSchemeDocument
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.subsystem.SubsystemDocument
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI form assistant for GUI-owned robot descriptors.
 *
 * It can propose reviewed form changes only. Persistence, source generation, hardware control, and
 * project ownership remain with the Builder view models and repositories.
 */
class RobotDesignAssistantService(
    private val generativeAiService: GenerativeAiService,
) {
    private val subsystemDocumentGson = GsonBuilder().create()

    suspend fun requestSubsystemDesignProposal(
        current: SubsystemDocument,
        studentRequest: String,
    ): SubsystemDesignProposal = withContext(Dispatchers.IO) {
        require(studentRequest.isNotBlank()) { "Describe what you want the subsystem to do first." }
        require(studentRequest.length <= 4_000) { "Subsystem design request is limited to 4,000 characters." }

        // This descriptor contains form data only. No Kotlin source, credentials, logs, or robot
        // network data are sent to Gemini by this workflow.
        val currentJson = subsystemDocumentGson.toJson(current)
        val prompt = """
            You are the ARES Subsystem Builder form assistant for novice FTC and FRC students.
            Propose edits to the supplied ARES subsystem descriptor. Do not write Kotlin source.

            Safety and contract rules:
             - Return a complete schemaVersion ${current.schemaVersion} descriptor using the exact JSON shape supplied.
            - Preserve schemaVersion, documentId, uid, platform, revision, parentContentHash,
              implementation, and capabilityActionKeys exactly. The desktop app also enforces this.
            - Preserve existing uid values for existing hardware, state, and control entries.
            - Use only enum names already visible in the descriptor or these supported choices:
              homing NONE, DIGITAL_SENSOR, CURRENT_STALL, VELOCITY_STALL,
              CURRENT_AND_VELOCITY_STALL, CUSTOM_MEASUREMENT; feedforward NONE, SIMPLE_MOTOR,
              ELEVATOR, ARM; follower transforms SAME_DIRECTION, INVERTED, MIRRORED_POSITION.
            - Hardware reads are cached once per loop. Unknown current is invalid, never zero.
            - Keep configuration health, safe neutral output, failed-write latching, explicit neutral
              recovery, telemetry, and zero-allocation periodic paths enabled for actuators.
            - Sensorless homing requires bounded search output, fresh evidence, dwell, and timeout.
            - Followers share one compatible leader and cannot own a controller or homing sequence.
            - Device inversion corrects physical mounting; follower direction is a separate transform.
            - Feedforward units and referenced state fields must be internally consistent.
            - Do not invent unsupported hardware APIs, source paths, catalog actions, or secrets.

            Respond only with one JSON object:
            {
              "summary": "one plain-language sentence",
              "explanations": ["why change 1 helps", "why change 2 is safe"],
               "proposedDocument": { complete descriptor object matching the supplied schema }
            }

            Student request:
            $studentRequest

            Current descriptor:
            $currentJson
        """.trimIndent()

        requestDesignProposalWithRepair(prompt, { generativeAiService.requestStructuredJson(it) }) {
            parseSubsystemDesignProposalResponse(
                current = current,
                responseText = it,
                gson = subsystemDocumentGson,
            )
        }
    }

    suspend fun requestDrivebaseDesignProposal(
        current: DrivetrainDocument,
        studentRequest: String,
    ): DrivebaseDesignProposal = withContext(Dispatchers.IO) {
        require(studentRequest.isNotBlank()) { "Describe the drivebase or change you want first." }
        require(studentRequest.length <= 4_000) { "Drivebase design request is limited to 4,000 characters." }
        val prompt = """
            You are the ARES Drivebase Builder form assistant for novice FTC and FRC students.
            Propose edits to the supplied canonical drivetrain document. Do not write source code,
            edit vendor files, invent calibration evidence, or command hardware.

            Rules:
            - Return the complete JSON document using the exact supplied schema and enum names.
            - Preserve schemaVersion, uid, drivebaseId, kind, platform, canonicalProfileUid,
              parameters, calibrationProvenance, and ctreImport exactly.
            - Preserve existing component/module uid values when they represent the same device.
            - Keep one primary localization source; vision may only be a secondary source.
            - Keep CCW-positive heading, cached inputs, safe neutral, disabled neutral output,
              configuration health, feedback freshness, fault latching, explicit neutral recovery,
              and zero-allocation periodic requirements enabled.
            - A follower must reference one direct drive-motor leader; no follower chains.
            - Inversion describes physical mounting and remains independent from following.
            - Unknown current is invalid, not zero. Do not claim a current limit unless the
              controller actually enforces it.

            Respond only with:
            {"summary":"one sentence","explanations":["reason"],"proposedDocument":{}}

            Student request:
            $studentRequest

            Current drivetrain document:
            ${DrivetrainDocumentCodec.encode(current)}
        """.trimIndent()
        requestDesignProposalWithRepair(prompt, { generativeAiService.requestStructuredJson(it) }) {
            parseDrivebaseDesignProposalResponse(current, it)
        }
    }

    suspend fun requestControlsDesignProposal(
        current: ControlSchemeDocument,
        context: ControlsDesignContext,
        studentRequest: String,
    ): ControlsDesignProposal = withContext(Dispatchers.IO) {
        require(studentRequest.isNotBlank()) { "Describe the controls you want first." }
        require(studentRequest.length <= 4_000) { "Controller design request is limited to 4,000 characters." }
        val controls = context.profileControls.entries.joinToString("\n") { (profile, ids) ->
            "$profile: ${ids.sorted().joinToString()}"
        }
        val prompt = """
            You are the ARES Controller Bindings form assistant for novice FTC and FRC students.
            Propose edits to the supplied control scheme. Do not write source code, save files, or
            invent action/routine/control keys.

            Rules:
            - Return a complete control-scheme JSON document matching the schema below.
            - Preserve schemaVersion, documentId, revision, parentContentHash, and controllers.
            - Targets may use only the allowed action keys or routine IDs below.
            - Sources may use only controls belonging to that controller's assigned profile.
            - Prefer PRESS for one-shot actions, HELD only for actions safe while held, VALUE for
              analog actions, and explicit maximum-active/cooldown policies for risky mechanisms.
            - Do not bind the same input ambiguously. Give chords higher priority and suppress
              constituent bindings when appropriate.
            - Preserve all existing valid bindings unless the student explicitly asks to replace them.

            Binding schema — every binding must have exactly this shape:
            {"bindingId":"stable unique id","displayName":"plain language name",
             "source":{"kind":"BUTTON|CHORD|AXIS_THRESHOLD|AXIS_VALUE|AXIS_ZONE",
                       "controllerSlot":"a slot from controllers below","controlIds":["one allowed control id"],
                       "transform":null,"pressThreshold":null,"releaseThreshold":null,
                       "thresholdDirection":"ABOVE","zoneMinimum":null,"zoneMaximum":null,
                       "zoneHysteresis":0.0,"chordWindowSeconds":0.075},
             "event":"PRESS|RELEASE|HELD|HOLD|REPEAT|VALUE|ZONE_ENTER|ZONE_ACTIVE|ZONE_EXIT",
             "target":{"kind":"ACTION|ROUTINE|CANCEL_ROUTINE|DRIVE","key":"an allowed action key or routine id",
                       "arguments":{},"routinePolicy":"IGNORE_IF_RUNNING"},
             "timing":{"pressDebounceSeconds":0.0,"releaseDebounceSeconds":0.0,"holdAfterSeconds":null,
                       "repeatAfterSeconds":null,"repeatEverySeconds":null,"cooldownSeconds":0.0,
                       "maximumActiveSeconds":null},
             "analogPolicy":null,"priority":0,"suppressConstituentBindings":false,"enabled":true}
            "target" is always a JSON object with "kind" and "key" fields — never a plain string.
            Drivetrain control uses DRIVE targets: {"kind":"DRIVE","key":"vx"} with key one of
            vx (forward), vy (strafe), or omega (rotate); the source must be AXIS_VALUE with event
            VALUE, an analogPolicy is required, and each axis may be bound at most once. When the
            student asks how a stick drives the robot, propose the three DRIVE bindings.
            Example binding:
            {"bindingId":"b-intake-run","displayName":"Run intake","source":{"kind":"BUTTON","controllerSlot":"operator","controlIds":["<an allowed control id>"]},"event":"PRESS","target":{"kind":"ACTION","key":"<an allowed action key>","arguments":{}}}

            Allowed actions: ${context.actionKeys.sorted().joinToString()}
            Allowed routines: ${context.routineIds.sorted().joinToString()}
            Profile controls:
            $controls

            Respond only with:
            {"summary":"one sentence","explanations":["reason"],"proposedDocument":{}}

            Student request:
            $studentRequest

            Current control scheme:
            ${ControlSchemeCodec.encode(current)}
        """.trimIndent()
        requestDesignProposalWithRepair(prompt, { generativeAiService.requestStructuredJson(it) }) {
            parseControlsDesignProposalResponse(current, context, it)
        }
    }


}

