# Teaching with ARES Robotics Studio

This guide helps a mentor run a simulator-first lesson in which students learn to identify evidence before they control anything.

For a ready-to-run offline session using the in-app mentor view, synthetic comparison runs, written reflections, reset controls, rubric, and Markdown export, start with the [Robot Academy classroom pilot](CLASSROOM_PILOT.md).

## Recommended learning sequence

1. **Simulator:** observe current data without physical hardware risk.
2. **Imported replay:** make claims from repeatable historical evidence.
3. **Live robot:** apply the same observations under the team's normal enable/disable and field safety rules.
4. **Cloud Sync:** discuss collaboration/backup only after students understand that local data is authoritative.

Do not start a new student with a live robot merely because its connection is convenient. The simulator teaches the same target/topic/mode distinctions with better reset and recovery.

## A 45-minute first lesson

### Learning outcomes

By the end, a student should be able to:

- choose the correct robot workspace;
- identify **Live Robot**, **Local Sim**, and **Replay** without guessing;
- name one telemetry topic/value and its unit;
- stop the Analytics-managed simulator;
- explain why a live stream is not yet a persistent run; and
- find successful or quarantined import evidence.

### Roles for a group

- **Navigator:** reads the task guide and states the next step.
- **Operator:** uses the mouse/keyboard only after repeating the requested action.
- **Observer:** watches target, mode, connection, and one chosen value.
- **Data steward:** records the workspace, source, time, units, and result.
- **Safety lead:** controls the physical safety checklist. In a simulator-only lesson, this student verifies that **Local Sim** remains selected.

Rotate roles rather than letting the most experienced student perform every action.

### Activity

1. Use [First launch](../start/FIRST_LAUNCH.md) to create/select one workspace.
2. Open **Help & Learn → First mission** and assign one student to read the lesson coach aloud.
3. Before launching anything, ask each student to predict which indicators will change.
4. Follow [Connect the simulator](../start/CONNECT_SIMULATOR.md).
5. Choose one value, such as X pose, heading, battery, or mechanism state. Record:
   - source: simulator;
   - topic/widget;
   - unit;
   - expected behavior;
   - observed behavior.
6. Pause and ask: “Would this same number mean the same thing in replay?” The unit and topic can be the same, but the time/source is historical.
7. Stop the simulator cleanly.
8. If the activity created a completed log, follow [Bring in a run](../operate/BRING_IN_A_RUN.md), then replay the same evidence.
9. End with a one-minute student handoff: they must name the workspace, source mode, success signal, and recovery action.

Robot Academy deliberately separates **Observed by ARES** checkpoints from **Your reflection**.
A process or connection fact may be recorded automatically; source interpretation, learning, code
quality, and physical safety must never be inferred from it. Practice marks are local reminders, not
grades or certification.

## The evidence loop

Use this loop for every lab, fault, or tuning discussion:

1. **Question:** What behavior are we trying to understand?
2. **Prediction:** Which topic should change, in which direction, and in what unit?
3. **Source check:** Live robot, simulator, or replay?
4. **Observe:** Capture the value, time, and operating state.
5. **Compare:** Did the evidence match the prediction?
6. **Change one thing:** Code, parameter, condition, or test—not several at once.
7. **Repeat or recover:** Stop safely, preserve evidence, and reset.

This keeps “the graph looks strange” from becoming an untraceable sequence of changes.

## A 30-minute homing and safe-recovery lab

### Learning outcomes

Students should be able to:

- distinguish a cached measurement from a direct hardware read;
- explain why freshness and validity are separate checks;
- compare digital-sensor, current-stall, velocity-stall, and combined-stall homing evidence;
- explain why evidence must persist for a bounded dwell; and
- explain why a latched output fault clears only after a neutral write succeeds.

### Activity

1. Open **Help & Learn -> Robot builder -> Lab: establish home and recover safely**.
2. Use the digital-sensor method. Activate the sensor and advance only half the required dwell. Ask why the home reference is still not established.
3. Make the cached feedback stale, advance again, and observe that the evidence dwell resets.
4. Compare current-stall and velocity-stall evidence. Make the applicable measurement invalid even while its numeric value appears convincing.
5. Try combined evidence with high current but a moving mechanism, then with high current and low speed.
6. Establish home, simulate a failed output write, and attempt recovery while the neutral write is set to fail.
7. Allow the modeled neutral write to succeed and ask the student to explain every condition that now permits motion.

### Misconceptions to challenge

- "The number looks reasonable, so the measurement must be valid."
- "One current spike proves the mechanism hit the hard stop."
- "Homed means every later command is safe."
- "Resetting a fault flag proves the actuator is neutral."

The lab is a pure teaching model. It does not select real thresholds, account for every mechanical failure, command hardware, or approve a physical homing routine. A real mechanism still needs documented limits, supervised low-authority testing, and an independent stop plan.

## A 60-minute project-backed mechanism mission

This activity connects Academy to the real Subsystem Builder instead of ending in a sandbox.

### Learning outcomes

Students should be able to:

- distinguish a target from cached position, velocity, and current measurements;
- connect a position controller to the measurement it actually uses;
- justify neutral output, target limits, feedback timeout, homing evidence, dwell, and timeout;
- distinguish generated starter code from deterministic plumbing and user-owned source;
- state which evidence came from form validation, a teaching model, generated tests, simulation,
  or physical hardware; and
- find the generated target capability that Controller Bindings can use after generation.

### Activity

1. Open **Help & Learn → Robot builder → Build a homed position mechanism** and start the lesson.
2. Before opening the builder, assign Navigator, Builder, Safety reviewer, and Evidence recorder roles.
3. Have the group predict the response to stale position, a homing timeout, and a failed neutral write.
4. Choose **Homed mechanism** in the real Subsystem Builder. Use a practice mechanism name and units.
5. Ask the Builder to point from the motor's cached measurements to immutable state, then from the
   target and position state fields into `POSITION_PID`.
6. Ask the Safety reviewer to read every permit and recovery field aloud. Do not accept an unknown
   physical limit or homing voltage merely to clear a warning.
7. Open the Homing & safe recovery lab from the slide-out coach. Compare digital, current-stall,
   velocity-stall, and combined evidence, including stale and invalid samples.
8. Return to the builder, keep generated mock IO and verification enabled, and open Review.
9. Classify each artifact as generated starter, generated plumbing, or verification. Inspect the
   destination module and any replacement diff.
10. Save the canonical descriptor. If the team wants to continue, generate and compile it, then use
    Controller Bindings and simulation as later evidence gates.

### Evidence ladder

| Evidence | What it supports | What it does not support |
| --- | --- | --- |
| Canonical form validates | IDs, types, references, bounds, and required declarations are coherent | Hardware is wired or safe |
| Teaching lab behaves as predicted | Student can reason about a simplified failure model | Production controller or physical mechanism parity |
| Generated contract tests pass | Generated mock/runtime honors tested declarations | Vendor configuration, wiring, polarity, clearance, or load |
| Project compiles and simulator runs | Consumer integration and simulated flow execute | Competition-field reliability |
| Supervised hardware procedure passes | The tested robot/setup produced the recorded evidence | Every future state is safe |

Do not award progress for merely clicking through fields. Ask the student to predict, point to the
named evidence, and explain the remaining boundary at every stage.

## A 30-minute project-backed controller mission

Run this after the mechanism mission so the target action comes from the student's canonical
subsystem descriptor instead of a fictional exercise.

### Learning outcomes

Students should be able to:

- distinguish a logical control name from desktop, FTC, and FRC raw indexes;
- choose an event policy that matches the intended behavior;
- find a generated subsystem action and fill its typed argument;
- narrate controller input -> generated binding -> typed action -> Redux -> controller -> cached IO;
- identify the saved `.arescontrols` file and generated-project evidence; and
- explain why validation, generation, simulation, and physical operation are different claims.

### Activity

1. Open **Help & Learn -> Driver & operator -> Control the mechanism you created**.
2. Ask for a prediction: should the mechanism move once on press, remain commanded while held, or
   track an analog value? What should happen on release?
3. Open Controller Bindings and locate the generated `subsystem.<id>.set.<field>` action.
4. Select a standard control and read its target-platform mapping aloud. A desktop-learned mapping
   is not evidence for FTC or FRC.
5. Create the binding, enter the typed target, and inspect debounce, hold, maximum-active, and
   release behavior. Apply the draft only after another student reviews it.
6. Read the structural runtime trace from input through cached IO. Ask which parts have actually
   executed; the answer is none at this point.
7. Save the canonical scheme and generate the project. Preserve any validation or build output.
8. If continuing, run the appropriate simulator and observe the action/state/telemetry path. Record
   physical-controller verification as a separate future procedure.

### Evidence ladder

| Evidence | Supported claim | Unsupported claim |
| --- | --- | --- |
| Action appears in the typed catalog | The selected project declares that capability | A runtime implementation was exercised |
| Platform mapping exists | The logical control has a declared FTC/FRC index | This particular controller exposes that index correctly |
| Binding validates and saves | The canonical scheme is internally coherent | A button press reaches the robot |
| Generation and compile pass | Deterministic bindings integrate with the consumer project | Simulated or physical mechanism behavior is correct |
| Simulator trace passes | The simulated runtime followed the tested path | Wiring, polarity, load, or field safety is correct |

## A 40-minute reversible tuning mission

Use **Guided tuning experiment** after a control-response lab. Do not begin with live SysId or a
connected mechanism.

1. Start from one paired-run finding. Ask the student to restate the evidence and uncertainty before
   naming a possible cause.
2. Require a question, falsifiable prediction, one intended metric, held constants, a success
   threshold, a stop condition, and a next test before the candidate run.
3. In Tuning, identify the canonical Source value, parameter owner, stable key, type, unit, bounds,
   and apply policy.
4. Compare feedback terms with feedforward terms: kS counters static friction, kV scales desired
   velocity, kA scales desired acceleration, and kG counters gravity in an appropriate model. The
   exact units and plant assumptions remain project-specific.
5. Snapshot and stage exactly one bounded Proposed value. A teaching lab is model evidence, not robot
   evidence; an imported run or SysId artifact must retain its project path and hash when required.
6. Repeat the same routine in Local Sim, record a new candidate session, and compare the exact metric.
   Treat a missing signal or a below-threshold change as inconclusive.
7. Record Accept, Revise, Reject candidate, or Roll back, with the reason and next safe test. Export
   the mentor/student report before any canonical promotion discussion.
8. If the simulation evidence warrants promotion, use the separate structured diff and have a second
   student read the base hash, before/after value, unit, policy, and rollback path. Confirmation
   changes one canonical profile atomically and creates history; it does not send NT4.

Never award this mission for “finding gains that look good.” Require a typed hypothesis, one-change
experiment, provenance, policy explanation, reversible review, and an honest validation boundary.

## A 45-minute coordinated-mechanism mission

Use **Coordinate several mechanisms safely** only after students have saved two generated
subsystems. This activity teaches supervisory coordination; it does not replace each subsystem's
controller, homing, limits, or IO safety.

1. Ask students to sketch one safe complete posture and one dangerous partial posture before they
   open Superstructure Studio.
2. Add the same typed target set to startup, useful, and fault-neutral postures. Ask why omitted
   targets could retain an earlier command.
3. Add one project-catalog action transition. Review priority, fresh guards, debounce, timeout, and
   fallback as separate decisions.
4. Add a cached-port health fallback or interlock and have students name the unit and the exact
   unhealthy evidence that should make motion fail closed.
5. Run **Trace & fault lab**, first with healthy values and then with stale or invalid evidence.
   Require students to quote state, candidate transition, sequence, port age, and rejection/fault
   text.
6. Open the hash-bound review, read the complete posture/transition policy aloud, and save only
   after a second student can explain the fallback.
7. End by planning compilation, project simulation, and a later supervised physical-clearance
   procedure as three different gates.

The Studio trace executes production state-machine semantics against editor-owned cached values.
It is not multi-body physics and cannot validate loads, interference, wiring, polarity, current
thresholds, or vendor firmware.

## A 45-minute canonical autonomous mission

Use **Build your first bounded routine** after the autonomous-planning teaching lab.

1. Have students predict starting footprint, target pose, meters/radians convention, ideal duration,
   exclusive resources, timeout, and failure behavior.
2. Load the selected project's typed action/condition catalog. Handwritten action keys are not an
   acceptable shortcut.
3. Author one short Drive step and one named marker whose subsystem safety behavior the student can
   explain.
4. Resolve every ERROR and discuss each retained warning. Validation proves document consistency,
   not successful motion.
5. Play and scrub the kinematic preview. Compare geometry, heading, footprint, duration, and limits
   with the prediction.
6. Configure the chooser entry and alliance/default behavior deliberately, then save the canonical
   routine/catalog revision.
7. Generate the project and distinguish that result from simulator execution or deployment.
8. Ask students to design a later physics-simulation fault scenario and a supervised field test,
   including stop conditions and preserved evidence.

Do not award completion merely because the preview animates. Require the evidence-boundary
explanation: validation, kinematic preview, compilation, physics simulation, replay, and physical
field testing answer different questions.

## A 35-minute run-evidence mission

Use **Bring in and identify one run** followed by **Explain one run with bounded evidence**. A robot
is not required when the workspace already contains an imported or simulated run.

1. Before opening a report, have the student predict team, season, robot, capture source, time range,
   signal, unit, and expected direction.
2. Inspect Log Imports. Treat quarantine reports as preserved diagnostic evidence; do not erase or
   retry blindly.
3. Select a run in Guided Run Review and verify that it belongs to the current workspace.
4. Read source kind, filename/report, decoder, checksum, record counts, historical freshness status,
   and confidence explanation that are actually present.
5. Have one student read observations/metrics while another lists possible causes. Do not allow a
   cause to be restated as a measurement.
6. Use a baseline only if the report found a compatible team/season/robot session. Absence of a
   baseline is a valid result, not permission to compare unrelated runs.
7. Read missing signals and limitations, choose a next test that discriminates among causes, and
   export the Markdown evidence report.
8. Require one bounded claim, one alternative explanation, and one fact the report cannot prove.

Historical telemetry can support reproducible analysis. It cannot prove the robot is currently
configured the same way, connected, fresh, disabled, or physically safe.

## A 40-minute compare-two-runs mission

Use the two offline Academy practice runs before asking students to compare competition data.

1. Ask students to name one observation, one possible explanation, and one controlled next test.
2. Select both runs in Guided Run Review and read the exact team, season, and robot identity aloud.
3. Align by recording start, then autonomous start, then the shared match event. Ask why aligned
   time changes while every original replay timestamp remains fixed.
4. Inspect loop time, battery, current, pose, driver input, and mechanism evidence only where the
   same unit and source semantics are available. Read missing-data messages rather than treating
   gaps as zero.
5. Open one finding at its exact replay timestamp and verify the source topics in the replay.
6. Label the claim as observation, correlation, hypothesis, or verified cause. A log comparison
   normally supports the first three, not the last one.
7. Export the mentor/student report and have another student find the session IDs, timestamp,
   topics, sample/window limitation, and proposed next check without help.

Assessment should reward evidence boundaries, not the number of detected differences. The report
is local, contains recorded identifiers and topic names, and should be reviewed before external
sharing. Synthetic and simulator runs do not prove physical-robot behavior.

## A 50-minute generated-runtime graduation

Use **Graduate a GUI robot into a verified runtime** after students have completed one subsystem and
controller binding in the same project.

1. Have students draw five boxes: canonical documents, USER-OWNED source, GENERATED STARTER source,
   GENERATED—DO NOT EDIT plumbing, and disposable build products. Require regeneration behavior for
   each box.
2. In Robot Studio, read workspace, league, status, storage, and runtime consumer for every selected
   authoring stage. Optional stages may remain Optional; blocked or code-required selected behavior
   must be resolved or explicitly removed from the claimed no-code scope.
3. Run **Verify & build** and read the exact result. This is consumer compilation/tests/package
   evidence and performs no deployment.
4. In Developer Reference, trace one generated dependency to its owning repository, canonical unit,
   invariant, and focused test.
5. Trace a generated control path from mapped input to typed task/action, pure reducer, immutable
   state, controller, cached IO, platform/simulator adapter, and telemetry.
6. Start the project simulator only after the verified build. Exercise one intended path and one
   declared fault where the simulator supports it; record unsupported physics explicitly.
7. Stop cleanly, import/select the run, and export a Guided Run Review report.
8. End with a claim table for descriptor validation, generated tests, consumer build, state-machine
   preview, physics simulation, replay analysis, and physical testing.

Do not replace the final explanation with “the build was green.” Students must be able to state
where generated code stops, where user-owned/platform code begins, and which physical claims remain
unverified.

### Recommended complete practice robot

Use one drivetrain plus a homed position mechanism, velocity flywheel, hysteretic intake, and
positional servo. Require students to reuse catalog capabilities across a direct TeleOp binding, a
bounded autonomous routine, and a guarded superstructure transition; add one cross-mechanism
interlock and exercise invalid/stale feedback plus failed-write neutral recovery in simulation.
Finish by reading the Dashboard **Subsystem health** card. This gives every student the same
end-to-end vocabulary without pretending the simulator verified wiring, loads, clearances, or
physical gains.

Before a powered bring-up, open Hardware Setup and review its exact names/addresses and derived
commissioning checklist. The displayed pulse is an **unarmed proposal**, not a desktop control.
Physical identity/direction tests remain mentor-authorized, restrained, hold-to-run, and inside the
league controller's normal enable/stop boundary.

## A 30-minute input-to-telemetry lab

### Learning outcomes

Students should be able to:

- narrate input -> typed action -> pure reducer -> immutable state -> controller -> IO -> telemetry;
- show that a retained Redux snapshot does not change when the next state is created;
- distinguish an actuator command from a cached sensor measurement;
- identify a telemetry topic, value, unit, validity, and freshness; and
- explain why stale motor feedback produces a neutral request in the teaching model.

### Activity

1. Open **Help & Learn -> Driver & operator -> Lab: trace input, state, IO, and telemetry**.
2. Run the motor path and have one student read each stage aloud. Compare the retained and next sequence numbers.
3. Invert the motor, then adjust deadband. Predict the requested duty-cycle sign and magnitude before running again.
4. Make the cached encoder sample stale. Ask which layer holds intent and which layer refuses non-neutral output.
5. Switch to the positional servo. Predict how the `-1..1` axis becomes a normalized `0..1` position.
6. Simulate a failed mock write and identify the resulting fault-latch text.
7. Switch to the distance sensor. Confirm that its adapter refreshes cached state and telemetry without an actuator write.

### Misconceptions to challenge

- "The gamepad writes directly to the motor."
- "A reducer changes the old state object."
- "A sensor-only subsystem needs an output command."
- "A topic value is meaningful without its unit, timestamp, validity, and producer."

This is a simplified trace, not the production Redux store, controller, IO adapter, or NT4 publisher. It cannot prove generated code, simulator parity, or hardware behavior. Use the live source, generated verification, and supervised physical procedures for those claims.

## A 30-minute autonomous planning lab

### Learning outcomes

Students should be able to:

- explain why the full robot footprint, not only its center point, must fit at a starting pose;
- calculate an ideal distance-over-speed time and explain why a timeout still needs margin;
- identify an exclusive-resource conflict between parallel branches;
- distinguish a false condition from a missing condition source; and
- choose stop-and-report or continue-on-failure only after deciding whether an action is required.

### Activity

1. Open **Help & Learn -> Autonomous developer -> Lab: validate an autonomous plan**.
2. Read the safe example aloud, including meters, meters per second, seconds, condition, and failure policy.
3. Move the robot center near a field edge until the footprint no longer fits. Ask why the center coordinate alone was misleading.
4. Restore the pose, then shorten the timeout below the ideal drive estimate plus the displayed margin.
5. Make both parallel branches claim the drivebase. Have the student propose a resource-safe ordering or ownership change.
6. Remove the named action or its condition source and compare those two failures with a condition that is present but false.
7. Select continue-on-failure for a required action, then explicitly mark it optional and discuss what downstream behavior remains safe.
8. Reset the example, open the real routine builder, and repeat the same questions during its structured preview. Do not generate or run merely because the teaching card passes.

### Misconceptions to challenge

- "If the robot center is on the field, the robot is in bounds."
- "Distance divided by speed is a guaranteed completion time."
- "Parallel steps can both control the drivetrain if they are short."
- "A missing condition is the same as a condition that evaluated false."
- "Continue-on-failure is harmless because the next step will fix it."

The lab is a pure teaching model. It does not read the selected project, save a routine, detect real obstacles, generate code, run simulation, or approve a physical autonomous path. The canonical routine builder, generated verification, simulator evidence, field measurement, and supervised physical validation remain separate gates.

## Physical robot gate

Move from simulator/replay to **Live Robot** only when all applicable items are true:

- [ ] A designated adult/lead student owns enable/disable and emergency response.
- [ ] The correct workspace, league, robot, and live host were read aloud.
- [ ] The robot is on blocks or inside the approved test area for the planned motion.
- [ ] People, tools, cables, and game pieces are outside the mechanism/drivetrain envelope.
- [ ] Battery, radio/network, and driver-station state meet team standards.
- [ ] The student knows which Analytics actions are observational and which publish commands.
- [ ] Autonomous selection, remote drive, driver-station controls, and tuning pushes follow the team's documented safety checklist.
- [ ] The team has a stop plan independent of the Analytics toolbar.

The Analytics **Stop** button ends Analytics-managed desktop build/simulator processes. It is not a robot emergency stop.

## Recovery script for students

Teach this short response before introducing failure:

1. **Hands off controls.** Release gamepad/keyboard inputs.
2. **Name the mode.** Live robot, Local Sim, or Replay.
3. **Make safe.** Use the proper driver-station/robot disable process for hardware; use Analytics **Stop** for its simulator.
4. **Preserve evidence.** Keep terminal text and log files; do not delete quarantine.
5. **Change nothing else.** Call the mentor and report the workspace, target, last action, and symptom.

## Assessment prompts

Use concrete prompts instead of “Do you understand?”

- “Point to the evidence that this is simulator data.”
- “If Wi-Fi disappears, which parts still work?”
- “Why doesn't a green NT4 indicator mean the robot is safe to approach?”
- “Where will a completed local log go after import?”
- “What is the difference between the `live-telemetry` session and an imported run?”
- “Which axis and sign convention does heading use?”
- “What should you preserve before retrying a quarantined log?”

## Layering advanced detail

Once students can complete the task without prompts, add one layer at a time:

- **Protocol layer:** topic names, types, NT4, leading-slash normalization.
- **Geometry layer:** field axes, radians, CCW-positive heading, field-to-canvas transform.
- **State layer:** Redux action → reducer → immutable state → IO output.
- **Estimation layer:** odometry versus EKF versus simulator ground truth.
- **Persistence layer:** stable-file detection, fingerprints, DuckDB sessions, replay baselines.
- **Operations layer:** ADB versus SSH/SCP, ports `5810` and `5002`, quarantine, cloud sync.

Have students cite the [Telemetry contract](../TELEMETRY_CONTRACT.md) or [Glossary](../learn/GLOSSARY.md) rather than memorizing unexplained acronyms.

The [Robot Academy guide](../learn/ROBOT_ACADEMY.md) describes all paths, checkpoint meanings,
lab boundaries, progress migration, and recovery behavior.

## Lesson preparation checklist

- [ ] Launch Analytics and the selected simulator once before class so dependencies are cached.
- [ ] Verify the workspace points to the intended robot project.
- [ ] Verify **Local Sim** reaches NT4 port `5810` and produces at least one obvious changing value.
- [ ] Prepare one known-good completed log and one safe example of an import failure if teaching quarantine.
- [ ] Keep cloud sign-in out of the critical path; local lessons must work offline.
- [ ] Decide which students may operate and which controls are out of scope.
- [ ] Leave time for every student to perform the stop/recovery script.
