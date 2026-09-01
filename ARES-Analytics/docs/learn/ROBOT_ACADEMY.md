# Learn with Robot Academy

Robot Academy is the in-app route from “I have never used ARES” to creating and reviewing robot behavior with evidence. It combines real application tasks with simplified interactive models. It works offline, and its first mission requires no physical robot.

## Start with the first simulator mission

Open **Robot Academy → Track 1: Drivetrains & Odometry → Launch Simulator & Drivetrain Telemetry**. A fresh workspace also shows a dismissible **Try your first simulator mission** suggestion that opens this lesson directly.

The first mission asks you to:

1. select **Local Sim**;
2. start one Analytics-managed simulator;
3. wait for the local simulator and NT4 connection to report ready;
4. open Dashboard, identify one changing value, and name its source; and
5. stop the simulator.

ARES can record process and connection facts such as “Local Sim selected” or “simulator process running.” It cannot tell whether you understood a graph, followed a team safety procedure, or proved hardware safe. Those checkpoints remain explicit student reflections.

## Choose a path

| Path | Use it to | Physical robot needed? |
| --- | --- | --- |
| **Track 1: Drivetrains & Odometry** | Start Local Sim, define a drivebase, and study kinematics and sensor fusion | No for the simulator-first material |
| **Track 2: Subsystems & Architecture** | Generate a bounded mechanism, trace state flow, and review hardware addresses | No for authoring and modeled faults; physical review comes later |
| **Track 3: Superstructure & Stateflow** | Coordinate mechanisms, design transitions, and reason about sizing | No for authoring and the deterministic teaching models |
| **Track 4: Control Theory & Guided Tuning** | Study controller response and run reversible, evidence-based simulation experiments | No for the included experiments; physical tuning comes later |
| **Track 5: Autonomous & Telemetry Forensics** | Author routines, validate field constraints, compare evidence, and graduate generated code | No for authoring, replay, and kinematic preview |

Prerequisites are recommendations, not hidden locks. You may preview a later lesson with a mentor. The status text says **Not started**, **In progress**, **Practiced**, or **Recommended later** so progress never depends on color alone.

If the first lesson in a track depends on an earlier foundation, Academy recommends that prerequisite even when it belongs to another track. After you practice it, return to the selected track and continue.

## Use learning checkpoints

Each lesson includes stable checkpoints. Two types are deliberately separate:

- **Observed by ARES** means the desktop observed a narrow fact such as a managed process or local NT4 connection.
- **Your reflection** means you recorded an interpretation in your own words.

Neither means graded, certified, code-reviewed, deployed, or physically safe. Student checkpoints now require a short written explanation instead of a bare checkbox. **Mark lesson practiced** remains a private reminder stored on this computer.

## Use the classroom toolkit

Open **Classroom & mentor toolkit** from Robot Academy to:

- resume the selected learning path on this computer;
- record a student display name without requiring a cloud account;
- review written student reflections separately from app-observed facts;
- add local mentor notes and evidence-based rubric ratings;
- keep separate switchable learner records on one classroom computer;
- create path-scoped assignments and printable prediction/evidence worksheets;
- save collision-safe local progress snapshots;
- restart one lesson or an entire path deliberately;
- create another verified starter workspace through first-run setup;
- install and directly import two clearly labeled synthetic CSV practice runs without overwriting files or duplicating prior imports; and
- export a Markdown learning record that contains no telemetry rows or credentials.

The synthetic baseline and stalled-arm files are exercises, not robot logs or simulator output. Follow the [classroom pilot guide](../mentor/CLASSROOM_PILOT.md) for setup, privacy, reset behavior, and a 60-minute pilot.

## Build one mechanism from idea to generated boundary

The Robot builder path now includes **Build a homed position mechanism**. This is not a disconnected
worksheet: it opens the real Subsystem Builder and records narrow facts from the canonical draft.
The mission deliberately follows one mechanism through the whole learning loop:

1. **Predict** what stale feedback, failed homing, and failed output writes should do.
2. **Build** a homed position mechanism in the real project editor.
3. **Connect** motor position, velocity, and current to explicit immutable state.
4. **Bound** target travel, feedback age, homing output/dwell/timeout, and neutral recovery.
5. **Experiment** in the hardware-free Homing & safe recovery lab.
6. **Review** Domain, Control, Hardware, Simulation, Generated Plumbing, and Verification artifacts.
7. **Save** the canonical `.ares/subsystems/*.aressubsystem` revision.
8. **Graduate** through the state-flow lab, generated Kotlin, controller binding, compilation, and
   simulator evidence.

For a complete practice robot, repeat the workflow with four deliberately different mechanisms:
a homed position elevator or arm, velocity flywheel, hysteretic intake, and positional servo. Then
coordinate them with one cross-mechanism interlock, bind one direct action and one routine, run the
routine in Local Sim, and use Dashboard's **Subsystem health** card to explain each mechanism's
configuration, freshness, validity, homing/calibration, fault, and recovery state. FTC and FRC use
the same canonical learning flow even though their hardware addresses and build products differ.

ARES may record that the draft has the expected typed fields, that its declared safety contract
passes local validation, that the generated artifact plan was opened, and that a descriptor was
saved. Those facts do not prove that generated code compiled or that a real motor, encoder, switch,
or current threshold behaves as declared. Compilation, simulator execution, and supervised
physical validation remain separate gates.

## Connect the mechanism to a controller

Continue with **Control the mechanism you created**. This mission uses the real action catalog,
controller profile, and control-scheme editor rather than an Academy-only worksheet:

1. find the generated `subsystem.<id>.set.<field>` capability for the saved mechanism;
2. select a named logical control and verify its explicit FTC or FRC mapping;
3. predict whether press, held, release, repeat, or analog-value behavior matches the intent;
4. apply a typed binding and review the in-editor runtime trace;
5. save the canonical `.ares/controls/*.arescontrols` revision; and
6. generate deterministic project bindings.

The trace explains the structural route from controller input to a typed action, Redux, the
subsystem controller, and cached IO. It does not press the controller, execute the reducer, run a
simulator, or command hardware. Desktop GLFW indexes are not FTC or FRC indexes. Runtime simulation
and supervised physical checks remain separate evidence gates.

## Turn a control idea into a reversible tuning proposal

Use **Propose one reversible tuning change** after the mechanism and control-response lessons. The
mission opens the live Tuning screen and preserves four distinct states:

1. **Source** is the checked-in canonical profile.
2. **Proposed** is a typed, bounded, session-only experiment.
3. **Live test** is an optional NT4 request that counts only after the robot acknowledges the exact
   request nonce and result; it still does not change Source.
4. **Promote** is an explicit, history-backed, atomic profile update that never pushes the robot.

Students find a unit-bearing feedforward declaration, state a model-backed prediction, change one
value, record provenance, classify its apply policy, and review the structured before/after diff.
They may discard rather than confirm. A simplified response model, valid proposal, or promoted
profile does not prove stability or physical safety.

## Coordinate mechanisms as complete postures

Use **Coordinate several mechanisms safely** after building at least two generated subsystems. The
mission works in the real Superstructure Studio and asks the student to:

1. predict one dangerous partial posture;
2. give every posture the same complete set of typed subsystem targets;
3. define explicit action/sensor/time transitions with priority, debounce, timeout, and fallback;
4. select disabled and fault-neutral behavior;
5. add an interlock or cached-port health fallback;
6. exercise the production state-machine evaluator in **Trace & fault lab**;
7. inject unhealthy cached evidence and explain the resulting rejection or fallback; and
8. review and save the canonical `.aressuperstructure` revision.

The trace uses production transition semantics and `RobotClock`, but its editable ports are not a
mechanism physics model. It cannot establish physical clearance, inertia, wiring, sensor polarity,
loads, or vendor-device behavior. Generated compilation, project simulation, and supervised
restrained-hardware checks remain separate evidence.

## Build one canonical autonomous routine

Use **Build your first bounded routine** after the autonomous-planning lab and drivebase blueprint.
The mission opens the real Autonomous editor. Students predict the starting footprint and resource
ownership, choose typed project capabilities, author bounded steps, resolve validation errors,
inspect the kinematic preview, configure a chooser entry, save the canonical revision, and generate
the project runtime.

A kinematic preview checks geometry, heading, and declared motion limits. It does not model wheel
slip, impacts, mechanism dynamics, localization failures, changing obstacles, or physical field
clearance. The final Academy reflection requires students to distinguish validation, preview,
generated compilation, physics simulation, replay evidence, and supervised field validation.

## Turn a saved run into bounded evidence

The operate/data path now uses the real workspace-scoped Guided Run Review in two stages:

1. **Bring in and identify one run** asks students to inspect import/quarantine status, select a run
   from the current workspace, and read the preserved source record rather than guessing origin.
2. **Explain one run with bounded evidence** asks students to read sourced metrics, distinguish
   observations from possible causes, use a compatible baseline only when one exists, state missing
   signals and limitations, choose a safe next action, and export the Markdown report.

ARES may observe that a run exists, that its source report loaded, that metrics/limitations are
present, and that an export succeeded. It cannot infer the student's conclusion or decide that a
possible cause is true. Historical evidence also cannot establish current configuration, freshness,
or physical safety.

## Graduate GUI work into a verified runtime

Use **Graduate a GUI robot into a verified runtime** after the mechanism and controller missions.
Robot Studio is the evidence spine for this lesson:

1. verify workspace and league identity;
2. resolve required authoring stages while leaving unused features explicitly Optional;
3. classify canonical documents, user-owned source, generated starters, generated plumbing, and
   disposable build products by their ownership headers;
4. run the project-matched **Verify & build** workflow without deployment;
5. use Developer Reference to locate one generated dependency and its nearest focused test;
6. trace one generated path through Redux, controller, cached IO, adapter, and telemetry;
7. start and stop the verified project simulator; and
8. preserve a run and Guided Run Review report.

This is the graduation from form completion to inspectable executable evidence—not graduation to
unrestricted hardware use. Generated contract tests, consumer compilation, deterministic runtime
preview, project simulation, replay analysis, and supervised physical validation are separate
gates with different claims.

## Use interactive labs well

Every lab starts with an outcome, model boundary, short experiment, reflection questions, and success description.

Use the same evidence loop each time:

1. Ask one question.
2. Predict what will change and name its unit.
3. Change one input.
4. Observe the result.
5. Compare it with the prediction.
6. State what the model cannot prove.
7. Reset before the next experiment.

The control-response, sensor-fusion, motion-profile, mechanism-sizing, homing-safety, state-flow, and autonomous-planning labs are teaching models. They do not run the production robot algorithms, command hardware, save project constants, validate a mechanism, or prove field clearance.

The **Homing & safe recovery** lab is the place to learn why a sensor edge, current spike, or stopped motor is not enough by itself. Students practice checking cached-measurement validity and freshness, requiring evidence for a bounded dwell, latching failed output writes, and clearing a fault only after a neutral write succeeds. It deliberately supports sensor, current-stall, velocity-stall, and combined-stall evidence without connecting to a robot.

The **Input, state & telemetry** lab traces a motor command, positional-servo command, or distance-sensor sample through a simplified ARES loop. It keeps the retained previous Redux snapshot visible beside the new immutable state, then shows the controller decision, mock IO result, topic, unit, validity, and freshness. Use it before Controller Bindings or the Subsystem Builder so students know which layer owns intent, state, hardware access, and observation.

The **Autonomous planning** lab validates a small two-step teaching plan before a student opens the real routine builder. It checks the complete robot footprint at the starting and target poses, estimates drive time, requires timeout margin, detects parallel resource conflicts, checks that named actions and conditions exist, and distinguishes stop-and-report from continue-after-optional failure behavior. A passing sandbox result is only preparation: the student must still use the canonical routine builder, review its structured validation and preview, generate code, run in simulation, and later complete supervised field checks.

For the **Build a safe first autonomous routine** lesson, open **Autonomous Builder** and choose
**Start guided first routine**. The guide creates one unsaved Safe-preset drive draft using the same
canonical routine model and validation as the full editor. Inspect its field preview before saving.
The guide does not generate code, start a simulator, or prove that a physical route is clear.

## Resume and recover

Academy stores local progress in `.ares-analytics/learning-progress.json` under the current operating-system user. Older practiced marks are preserved when the richer checkpoint format is loaded. Migration never invents checkpoint evidence.

If the progress file is unreadable, Academy starts with empty progress instead of blocking the app. This does not affect robot projects, imported runs, or cloud data.

The selected path, student display name, written reflections, mentor notes, rubric ratings, and assignments use one local classroom store. Each learner record remains separate and can be resumed from the roster. Use **Add separate student**—not a simple name edit—between students. **Save local snapshot** preserves a collision-safe Markdown record before a review or reset. Restarting a lesson or path is explicit and confirmed; shared lessons restart anywhere they appear.

If a simulator lesson gets stuck:

1. release controls;
2. confirm the selected target says **Local Sim**;
3. use Analytics **Stop** for its managed process;
4. preserve the terminal text; and
5. verify project or network settings against the team's documented configuration before changing them.

The Analytics Stop action is not a physical robot emergency stop.

## For mentors

Ask for observable answers instead of “Do you understand?” Good prompts include:

- “Show me the text proving this is Local Sim.”
- “Name the value, unit, source, and expected direction.”
- “Which checkpoint did ARES observe, and which one did you decide?”
- “What can this model not prove?”
- “What would you preserve before changing one thing?”

Continue with [Teaching with ARES](../mentor/TEACHING_WITH_ARES.md) for a group lesson plan and physical-robot gate.

When a lesson asks you to compare evidence, open **Analysis → Guided Run Review** and follow the source, timestamp, confidence, observation, hypothesis, and safe-next-action steps. The detailed workflow is documented in [Guided run review](../operate/GUIDED_RUN_REVIEW.md).
