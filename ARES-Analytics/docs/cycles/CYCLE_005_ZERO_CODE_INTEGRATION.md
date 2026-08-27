# Cycle 005 — Representative zero-code robot integration

## Outcome

This cycle proves that a student can author mechanisms in Robot Studio and carry their canonical
documents through generated Kotlin, controls, autonomous routines, superstructure coordination,
verification, and simulation without hand-editing generated source. The proof covers FTC and FRC;
physical hardware commissioning remains deliberately unverified and unarmed.

## Representative robot

The automated fixture contains:

- a starter drivebase;
- a current-stall-homed elevator with cached position, velocity, and current;
- a velocity-controlled flywheel with feedforward;
- an intake using measured velocity and explicit bang-bang hysteresis;
- a positional wrist servo;
- a fail-closed intake/elevator interlock;
- latched output faults and explicit neutral recovery;
- TeleOp action and routine bindings;
- a reusable score-cycle autonomous routine; and
- a STOW → READY → SCORE superstructure with a FAULT recovery transition.

`RepresentativeZeroCodeStarterIntegrationTest` copies both official starter projects to temporary
directories, writes the same canonical documents the GUI owns, runs starter generation, generates
the project runtime, validates it, executes platform and simulator tests, and builds the final FTC
APK/FRC distribution against one isolated ARESLib candidate. It never resolves an ambient
`mavenLocal()` artifact.

## Desktop workflow evidence

The visible Compose Desktop workflow was exercised through the opt-in loopback test-control
surface at the normal 1440 × 900 window size. The FTC path created a mechanism, reviewed generated
starter changes, discovered its generated actions in TeleOp Controls without a manual reload,
completed **Verify & build**, launched the correct local OpMode, armed only the simulator, and drove
the rendered robot with live telemetry. The FRC path created a fresh project and mechanism,
confirmed platform-specific CAN assignment, generated its starter, and discovered both generated
actions in the controls editor.

Deterministic Compose render tests cover the full Robot Studio layouts, explicit subsystem-health
status text, and collision-free controller axis callouts. Service/view-model tests cover template
creation, edits, reviewed persistence, address collision rejection, process ownership, generation,
commissioning guidance, and dashboard layout registration. This division keeps pixel checks
deterministic while the visible walkthrough still exercises the real native window and process
lifecycle.

## Safety and commissioning boundary

Hardware Setup derives exact names/ports, read-only signals, control strategies, homing evidence,
calibration/current requirements, and follower relationships from canonical descriptors. It may
show a bounded **UNARMED PULSE PROPOSAL**, but Analytics does not send that proposal to a robot.
Physical motion still requires explicit team authorization, a disabled and supported mechanism,
student-recorded configuration review and the platform diagnostic workflow. No result in this cycle is physical-robot
validation.

## Local verification matrix

- ARESLib: full tests and binary API checks.
- FTC starter: generation, project verification, TeamCode tests, simulator tests, APK assembly.
- FRC starter: generation, project verification, tests, and build.
- ARES-FTC season project: the same full matrix from a disposable copy so unrelated work remained
  untouched.
- ARES-FRC season project: verification, tests, coverage gate, and packaging.
- Analytics: app/shared/gateway tests, dashboard smoke/performance validation, and packaged-runtime
  canonical-document loading.

All dependency-consuming checks use an explicit file-based validation repository and one candidate
version. This cycle intentionally does not publish Maven artifacts, installers, or remote branches.
