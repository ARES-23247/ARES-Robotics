# Build one robot with Robot Studio

Robot Academy's **Graduate a GUI robot into a verified runtime** mission uses Robot Studio as its
project-matched evidence spine. It observes identity, authoring readiness, successful Verify &
build, an active project simulator, and persisted run evidence. Ownership explanations, runtime
flow, and validation boundaries remain student reflection checkpoints.

Robot Studio is the guided front door to ARES robot authoring. It does not replace the Drivebase Builder, Subsystem Builder, TeleOp Controls, Auto Builder, Tuning, simulator, or Run History. It reads their canonical project documents, explains the current evidence, and sends you to the correct specialized tool.

## Read the stage labels literally

Every stage includes a text label and icon. Color is supplemental.

| Status | Meaning |
| --- | --- |
| **Ready** | The required canonical document exists and passed the checks Robot Studio can perform locally. |
| **Needs action** | A required student step or explicit build/simulation action has not happened yet. |
| **Optional** | The robot can omit this stage for the current workflow. |
| **Blocked** | Fix an earlier required stage before continuing. |
| **Invalid** | A present document failed decoding, identity, reference, platform, or safety validation. |
| **Code required** | The descriptor is understandable, but this season project has no matching no-code runtime adapter. |
| **Running now** | Analytics observes its managed build or simulator process running. This is not a success result. |

Robot Studio never marks a build ready merely because an old generated file exists. Run **Verify & build** and read the correlated result. The action first regenerates the deterministic project bridge, then verifies generated ownership, runs project and simulator tests, and creates the normal FTC/FRC package. It never connects to ADB, installs an APK, deploys to a RoboRIO, or starts a robot. Robot Studio enables **Start simulator** only after that selected workspace has a successful current-session build. A successful build or simulator run still is not physical-robot validation.

## Follow the workflow

1. **Project & robot identity** — open [Project Identity](PROJECT_IDENTITY.md), confirm the repository, assign team identity, enter measured robot geometry, and review `.ares/project.json` before creation.
2. **Robot hardware & mechanisms** — describe physical identity, kinematics, wheel geometry, localization (odometry/vision), mechanism subsystems, and verify physical port/CAN bus allocations.
3. **Superstructure coordination** — coordinate multiple generated mechanisms through complete presets, guarded transitions, interlocks, and dynamic lookup tables.
4. **Autonomous catalog & routines** — build bounded routines from named actions, path segments, triggers, and scoring sequences. Authored before controls so auto-routines are available for gamepad binding.
5. **Driver & operator controls** — map real controller inputs to drive axes, named mechanism actions, and automated TeleOp sub-routines.
6. **Tuning & calibration** — keep structural identity separate from reviewed canonical values and local experiments.
7. **Verify & build** — preview generated work in its owning builder, preserve USER-OWNED source, then run verification, tests, and packaging without deployment.
8. **Simulate** — run the actual robot project against desktop adapters and identify the telemetry source.
9. **1-Click Deploy to robot** — connect over Wi-Fi, compile, and flash APK/binary directly to the physical robot.
10. **Import & analyze a run** — preserve a simulator or robot run before making claims about behavior.

## Read one Verification report

Select **Verification** in the Robot Studio structure tree after **Verify & build**. The report
keeps the evidence layers visible instead of reducing them to one green button:

- **Configuration checks** decode the current project, drivetrain, subsystem, superstructure, and
  autonomous documents.
- **Robot Builder behavior tests** are disposable tests generated from each subsystem's selected
  controls and safety rules under Gradle `build/generated` directories.
- **Simulator checks** exercise the project simulator and its telemetry boundary.
- **ARES platform integration** retains hand-written lifecycle, Redux, autonomous, coordinate,
  migration, and transport tests that no individual robot definition owns.
- **Build result** proves current generated source compiled and packaged without deployment.
- **Physical validation boundary** remains a supervised checklist; simulation does not prove
  wiring, polarity, mechanical clearance, or actual LED output.

Leave **Advanced details** off for a student-readable explanation. Turn it on to see exact test
identities, result files, and process evidence while diagnosing a failure.

## Follow one capability all the way through

A saved generated subsystem contributes typed named actions to the same project catalog used by
TeleOp Controls, autonomous routines, and Superstructure Studio. Do not create three separate
commands for the same intent. Select the generated capability in each editor and follow this one
runtime path:

`controller/routine/transition → typed action or task → Redux reducer → immutable subsystem state → controller → cached IO → FTC/FRC or simulated adapter`

On Dashboard, add **Subsystem health** (included in the Student, Builder, and Standard layouts).
It discovers `Subsystems/<id>/...` topics and reports configuration, freshness, feedback/current
validity, homing/calibration, output faults, and neutral recovery using text as well as color. A
healthy simulator result proves the generated and simulated path; it does not prove wiring,
mechanical clearance, sensor polarity, or physical gain stability.

## Know what is stored where

Robot Studio shows the exact destination on each stage. The important boundaries are:

- `.ares/project.json` owns project identity and league.
- `.ares/drivetrains/*.aresdrivetrain` owns structural drivebase configuration.
- `.ares/subsystems/*.aressubsystem` owns subsystem contracts; editable starters remain explicit source files.
- `.ares/controllers/*.arescontroller` and `.ares/controls/*.arescontrols` own controller identity and bindings.
- `.ares/routines/*.aresroutine` and `.ares/autonomous-catalog.json` own autonomous behavior.
- `.ares/tuning/*.arestuning` owns reviewed canonical tuning; local experiments stay under `.ares/local/tuning`.
- mechanical generated plumbing belongs under Gradle `build/generated` directories.
- imported run evidence belongs in the local Analytics database and may optionally be synchronized to the workspace-selected Google Drive destination.

Changing a display name must not change a stable document or action ID. Generated starter replacement still requires a structured diff and explicit confirmation. USER-OWNED or unknown source is never an eligible replacement target.

## When Robot Studio blocks you

Read the stage issue before editing files manually:

- **Missing identity:** use Project Identity. Workspace setup selects a repository but does not silently invent measured geometry or create `.ares/project.json`.
- **Wrong platform:** select the correct workspace or repair the canonical metadata; Project Identity will not rewrite the league of an existing project.
- **Code required:** use a supported no-code drivebase for this season project, or ask a mentor/developer to implement and verify the missing runtime adapter.
- **Invalid catalog or binding:** open the linked builder and fix the referenced stable ID or conflict.
- **Incomplete controls:** create both a controller profile and a control scheme, or remove both to
  return to the reviewed season baseline. One without the other is blocked.
- **Build failure:** keep the terminal output visible, fix the first reported error, and retry. The generated file already on disk is not proof of freshness.
- **Canceled build:** no pass/fail result exists. Retry after confirming no other managed build is running.
- **Result from another workspace:** Robot Studio ignores it. Build evidence is matched to the selected project and league.

## Build is not deploy

The student-facing Build action is deliberately compile-only. For FTC it runs deterministic project
generation, generation verification, TeamCode unit tests, simulator tests, and debug APK assembly.
For FRC it runs deterministic project generation, generation verification, tests, and the normal
build. Neither path performs physical deployment.

Installing an FTC APK or deploying FRC code uses the separate **Deploy to robot** workflow. It shows the exact project and physical target, reruns generation, verification, project tests, simulator tests where available, and packaging, then requires a second explicit confirmation before the install/deploy command. FTC deployment pins every ADB command to `192.168.43.1:5555`, checks the connected Android device identity, and verifies the Robot Controller package after installation. FRC deployment runs the standard project deploy task only after the same verification boundary.

A green build never grants deployment permission. A mentor remains responsible for wiring checks, restrained mechanisms, emergency-stop readiness, and supervision. Closing or canceling the dialog must not deploy anything.

Use [Robot Academy](ROBOT_ACADEMY.md) when a concept is unfamiliar. Use [Drivebase Builder](../DRIVEBASE_BUILDER.md), [Subsystem Builder](../SUBSYSTEM_BUILDER.md), and [Routines and controls](../ROUTINES_AND_CONTROLS.md) for deeper task instructions.

## Physical validation boundary

Robot Studio reports documents, local validation, managed processes, simulator connections, and imported-run evidence. It does not inspect wiring, mechanical clearance, emergency-stop readiness, field setup, or human supervision. Complete the team’s restrained and supervised hardware checklist before enabling a physical mechanism.
