# ARES Robotics Studio documentation

Cycle evidence: [product improvement cycle log](CYCLE_LOG.md).

Start here if ARES Robotics Studio is new to you. You do not need to understand every screen before you begin.

## Choose what you are trying to do

| I want to... | Start with | What you will use |
| --- | --- | --- |
| Open ARES Robotics Studio for the first time | [First launch](start/FIRST_LAUNCH.md) | A local robot workspace; JDK 17 or 21 is optional until build/simulation |
| Create a buildable simulation project without code | [Create a robot project](start/CREATE_ROBOT_PROJECT.md) | A parent folder; official installers contain the verified starter |
| Save versions or connect a personal/team GitHub backup | [Project Backup](start/PROJECT_BACKUP.md) | A local robot project; GitHub is optional |
| Compare canonical addresses with a physical robot | [Hardware Setup](start/HARDWARE_SETUP.md) | The robot wiring/configuration and a supervised review |
| Understand ARES colors, logo, and product styling | [ARES product design system](DESIGN_SYSTEM.md) | None |
| Upgrade, repair, or reinstall the desktop app | [Branding, upgrades, and repair](BRANDING_AND_UPGRADES.md) | The downloaded installer; projects and credentials stay in place |
| Enable optional Google Drive sync | [Google Drive setup](start/GOOGLE_DRIVE_SETUP.md) | One-click sign-in and a personal/team destination |
| Understand multi-team Drive isolation | [OAuth and Drive architecture](GOOGLE_DRIVE_ARCHITECTURE.md) | Application identity, ownership, permissions, migration |
| Review cloud privacy | [Privacy and cloud data](PRIVACY_AND_CLOUD.md) | Local-first behavior and token storage |
| Administer Google OAuth | [Google Cloud OAuth administration](admin/GOOGLE_CLOUD_OAUTH.md) | Production branding, scopes, custom clients, and release gates |
| Learn where screens and controls are | [App tour](start/APP_TOUR.md) | A map of the window, status language, and common workflows |
| See a robot without risking hardware | [Connect the simulator](start/CONNECT_SIMULATOR.md) | Live simulator telemetry on this computer |
| Review a practice or match log | [Bring in a run](operate/BRING_IN_A_RUN.md) | An imported, persistent run and replay controls |
| Understand replay timing and missing data | [Deterministic replay](DETERMINISTIC_REPLAY.md) | Atomic snapshots, source labels, timeline markers, and recovery |
| Compare runs and inspect evidence | [Run comparison and guided diagnosis](RUN_COMPARISON_AND_GUIDED_DIAGNOSIS.md) | Shared anchors, unit-safe overlays, exact replay links, and mentor/student reports |
| Understand one run step by step | [Guided run review](operate/GUIDED_RUN_REVIEW.md) | Source identity, timestamps, units, confidence, same-robot comparison, and safe next actions |
| Understand an unfamiliar word | [Glossary](learn/GLOSSARY.md) | Short definitions with mentor notes |
| Follow a complete beginner-to-builder path | [Robot Academy](learn/ROBOT_ACADEMY.md) | Guided missions, checkpoints, interactive labs, and local progress |
| Create or review one complete robot project | [Robot Studio](learn/ROBOT_STUDIO.md) | One guided route through existing drivebase, subsystem, controls, auto, build, simulation, and analysis tools |
| Understand what belongs in a clean robot repository | [Clean project structure](PROJECT_STRUCTURE.md) | Canonical inputs, ownership, generated output, evidence, dependencies, and what to commit |
| Create the canonical robot and field identity | [Project Identity](learn/PROJECT_IDENTITY.md) | Stable ID, measured footprint, field frame, structured diff, and recovery history |
| Make the interface easier to read | [Accessibility and contrast](learn/ACCESSIBILITY_AND_CONTRAST.md) | Colorblind, contrast, larger-text, and touch settings |
| Find the right screen or owning source file | [Find help and current source](learn/FIND_HELP_AND_SOURCE.md) | Contextual lessons, connection labels, and developer reference |
| Lead a student activity | [Teaching with ARES](mentor/TEACHING_WITH_ARES.md) | A safe simulator-first lesson sequence |
| Pilot Robot Academy with a class | [Classroom pilot](mentor/CLASSROOM_PILOT.md) | Offline practice runs, written evidence, mentor rubric, reset, and export |
| Build autonomous routines or controller bindings | [Routines and controls](ROUTINES_AND_CONTROLS.md) | Offline project authoring |
| Add a robot mechanism | [Subsystem Builder](SUBSYSTEM_BUILDER.md) | Generated IO, state, actions, reducers, and controllers |
| Coordinate several mechanisms | [Superstructure Studio](SUPERSTRUCTURE_STUDIO.md) | Complete postures, project actions, cached sensor guards, interlocks, and lookup tables |
| Configure how the robot moves | [Drivebase Builder](DRIVEBASE_BUILDER.md) | Drive type, hardware, geometry, localization, safety, and simulation labs |
| Ask Gemini to help fill an authoring form | [AI design assistants](learn/AI_DESIGN_ASSISTANTS.md) | Review-only subsystem, drivebase, and binding proposals |
| Tune from evidence without overwriting source | [Robot-owned tuning profiles](TUNING_PROFILES.md) | Source/live/proposed values, policies, provenance, diff review, and atomic promotion |
| Run one controlled simulator tuning experiment | [Guided tuning experiments](GUIDED_TUNING_EXPERIMENTS.md) | Question, hypothesis, held constants, threshold, bounded typed change, paired-run evidence, rollback, and report |
| Safely hand a simulated controller to a real-robot checklist | [Guided commissioning](GUIDED_COMMISSIONING.md) | Fault injection, explicit SysId capabilities, hash-bound review, and honest physical-validation evidence |
| Register existing subsystem Kotlin | [Hand-authored subsystem prototype](SUBSYSTEM_HAND_AUTHORED_PROTOTYPE.md) | USER-OWNED registration, lighting examples, and migration evidence |
| Diagnose or administer the application | [Operations guide](OPERATIONS.md) | Ports, storage, recovery, and release checks |
| Understand telemetry storage and cold-start recovery | [Telemetry storage architecture](DATABASE_STORAGE_ARCHITECTURE.md) | DuckDB, WAL hardening, and the partitioned Parquet migration |
| Find the code behind an ARES concept | **Developer Mode → Developer Reference** in the app | Current source path, units, invariants, and nearby tests |
| Improve the in-app help itself | [Documentation improvement goal](DOCUMENTATION_GOAL.md) | Verified baseline, prioritized workstreams, acceptance criteria |

## Know which world you are looking at

ARES Robotics Studio can display data from several places. They are related, but they are not interchangeable.

| Mode | Where the data comes from | Is it happening now? | Can it move hardware? | Does it require internet? |
| --- | --- | --- | --- | --- |
| **Live robot** | An FTC Control Hub or FRC RoboRIO over NT4 | Yes | Some explicit control/tuning tools can; treat the robot as active | No |
| **Local simulator** | A robot program and physics simulator running on this computer | Yes | No physical robot hardware | No |
| **Imported run / replay** | A completed log stored in the local database | No | No; replay is historical | No |
| **Cloud Sync** | Optional desktop-owned copies in Google Drive/cloud services | No | No | Yes |

The robot never uploads directly to the cloud. The laptop receives or pulls data, analyzes it locally, and may synchronize a copy later.

> **New student rule:** if you are unsure which mode to use, choose **Local Sim**. Ask a mentor before using a live robot control, autonomous selection, or tuning action.

## A good first 15 minutes

1. Complete [First launch](start/FIRST_LAUNCH.md).
2. Open **Help & Learn → First mission**. The lesson coach keeps the current checkpoint visible.
3. Follow [Connect the simulator](start/CONNECT_SIMULATOR.md).
4. On **Dashboard**, watch the field pose or a telemetry chart change.
5. Stop the simulator with the square **Stop** control in the execution toolbar.
6. Say out loud which mode supplied the data: **local simulator**, not replay and not a live robot.

You have succeeded when you can identify the active workspace, the selected target, and one changing telemetry value.

## Safety and recovery

- A green connection indicator means data is available. It does **not** mean a physical robot is safe to approach.
- Do not enable a robot, arm a routine, push tuning values, or use remote drive without the team's normal safety process.
- Stop a simulator or build with the toolbar's square **Stop** control. If a terminal reports a failure, keep the text visible for a mentor.
- Import and replay are local operations. A cloud-sync warning does not erase a successfully imported local run.
- Never delete the only copy of a competition log while troubleshooting. Work from a copy.

## For mentors and developers

The student guides stay task-focused. Protocol, persistence, and implementation detail live in:

- [Architecture](../ARCHITECTURE.md)
- [Telemetry contract](TELEMETRY_CONTRACT.md)
- [Operations](OPERATIONS.md)
- [Automated validation](VALIDATION.md)

## Built-in help

- Select **Help** beside the execution toolbar on supported screens to open the lesson for that workflow.
- Use the sidebar **Help & Learn** button for the full task catalog.
- Open the command palette with **Ctrl+K** and search for a task or symptom, such as `bring in a run`, `disconnected`, or `gamepad`.
- With Developer Mode enabled, **Developer Reference** maps core concepts to their live ARESLib source paths. It is a curated locator, not a complete generated API reference.
