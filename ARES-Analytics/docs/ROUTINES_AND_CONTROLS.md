# Student guide: routines and controller bindings

ARES Robotics Studio can author robot behavior entirely offline. Select a local FTC or FRC repository as
the workspace project; the robot does not need to be powered on, joined to Wi-Fi, or connected over
NT4. The app reads and writes ordinary project files under `.ares/`.

## The one concept to learn: a routine

A routine is an ordered or grouped set of robot steps. It can contain field drive goals, mechanism
actions, waits, conditions, parallel work, branches, repeats, and calls to other routines. The same
routine can be:

- listed as an autonomous choice;
- assigned to a controller button, trigger, chord, or analog zone;
- called by another routine as a macro;
- executed by a test or simulator.

There is no separate path document to keep synchronized with an autonomous routine. A drive goal is
just one routine step. Autonomous-only information, especially the starting pose and selectable
display name, belongs to the autonomous entry toggle in the routine editor.

For a guided project-backed workflow, use **Help & Learn → Autonomous developer → Build your first
bounded routine**. The mission observes typed catalog loading, routine validation, kinematic
preview, chooser configuration, canonical save, and project generation. It deliberately does not
call any of those results physics simulation, deployment, or physical field validation.

## Your first simulator routine

When the visible routine has no steps, choose **Start guided first routine**. The four-step guide:

1. names one intended move;
2. records a starting pose and one drive goal in meters and counter-clockwise degrees;
3. fixes the move to the conservative **Safe** motion preset and records alliance mirroring; and
4. shows exactly what will become an unsaved draft.

The guide rejects non-numeric coordinates, robot footprints outside the selected field, moves under
0.10 m, and first moves over 2.00 m. It requires a student to acknowledge that the field preview
still needs inspection. Applying the guide changes only the in-memory editor. It does not save,
generate, deploy, start a simulator, or command hardware.

After applying it, inspect the canvas and the **Needs attention** card. **Save & Generate** is the
separate action that writes the canonical `.aresroutine` and autonomous catalog. A passing preview
still does not prove obstacle clearance, traction, actuator direction, or physical safety.

Opening another routine, choosing **New**, or changing the project folder while the visible draft
has unsaved changes requires an explicit discard confirmation. While a project is being loaded, the editor shows a
blocking loading explanation; a draft created during that load is preserved rather than silently
replaced by the late disk result.

## Offline workflow

1. In the workspace/project selector, point Analytics at the robot repository root. You can repoint
   it later; creating a new workspace is not required.
2. Open **Autonomous Builder**. Use **Start guided first routine** for a first simulator move, or
   create/open a routine in the full editor.
3. Add steps from the inspector. Field goals are clamped using the selected field and robot
   dimensions so the robot footprint stays inside the field.
4. Choose actions and conditions from the project catalog. Parameter fields are generated from
   their declared types. A drive-only project does not need mechanism actions: the guided tour
   skips that lesson and the drive editor keeps the optional mechanism-action section collapsed.
   Add and generate a subsystem in Robot Studio when you are ready to unlock named actions.
5. Enable **Autonomous entry** only if this routine should appear in the match selector, then set its
   starting pose, alliance/mirroring policy, order, and enabled state.
6. Choose **Save & Generate**. Analytics atomically saves every changed scheme/profile, creates
   content-hashed revisions under `.ares/history/`, and runs the repository's fixed
   `generateAresProject` Gradle task. This is local-only; the robot can remain powered off.
7. Review and commit both the `.ares` changes and the generated Kotlin file. Robot builds still run
   `verifyAresProject`, so stale generated code fails closed.

The project layout is:

```text
.ares/
  project.json
  action-catalog.json
  autonomous-catalog.json
  routines/<routine-id>.aresroutine
  controllers/<profile-id>.arescontroller
  controls/<scheme-id>.arescontrols
  history/...
```

`project.json` is the canonical, Git-tracked source for league, coordinate convention, robot
footprint, and field dimensions. This prevents two student laptops from validating the same field
goal with different machine-local settings.

Routine documents use the single canonical `.aresroutine` format.

### Actions during a drive

Expand **Mechanism actions** on a drive step only when the robot should operate a mechanism while it
moves. A progress action runs once at the chosen percentage of that drive. A during-motion action
is active for the drive, and an arrival action runs after the drive reaches its goal. All three use
the typed project action catalog; students never type an action ID.

If an action, condition, or called routine is later renamed or removed, the old reference remains
visible as **Missing: ...** so it can be replaced or deleted. ARES blocks saving and generation
rather than silently dropping the behavior. A routine containing action or condition references is
also blocked when the project action catalog is missing or unreadable.

## Action discovery

Analytics automatically loads `.ares/action-catalog.json` from the selected project. This is why a
new or correctly repointed project immediately shows its actions without a running robot. The
catalog is a typed interface, not a Kotlin-text heuristic: its keys must match the FTC or FRC
runtime capability implementation. If the editor reports **No project actions declared**, verify:

1. the selected directory is the repository root, not `TeamCode`, `src`, or a parent workspace;
2. `.ares/action-catalog.json` exists and is valid JSON;
3. the catalog action is allowed in the current context;
4. after changing the catalog, generated Kotlin has been refreshed.

Generated subsystem descriptors add their typed target actions to this catalog automatically. A
subsystem that requires explicit neutral recovery or calibration also contributes clearly named
**Recover ... with neutral** and **Confirm ... calibration** actions. They appear in the same action
browser as hand-authored robot actions and can be assigned to ordinary buttons without writing
Kotlin. These are one-shot requests, not safety bypasses: the generated controller still requires
fresh, valid, configuration-healthy feedback; calibration performs a successful neutral write;
failed neutral recovery remains latched; and successful recovery/calibration holds neutral until a
later target command. Use deliberate operator controls and document the team's supervised physical
procedure before enabling these actions on hardware.

## Visual controller editor

The controller editor has two related documents:

- a **controller profile** names and draws the physical controls and records raw mappings;
- a **control scheme** assigns those named controls to actions or routines.

Use front/rear view for controllers with back paddles. Live input highlights the detected control,
and learn mode records its raw button or axis index. The Flydigi Vader 5 Pro template includes its
extra face and rear controls, but detection still depends on what the operating system or Driver
Station exposes.

Mappings must be verified separately for `DESKTOP_GLFW`, `FTC`, and `FRC`; their raw indexes are not
interchangeable. The editor warns when the target robot platform lacks a learned mapping. For the
Vader 5 Pro:

- FRC reads all raw buttons exposed through WPILib `GenericHID`, including extras that the Driver
  Station reports.
- FTC always supports the standard SDK gamepad controls. Vendor-only buttons require the FTC app or
  Android event path to expose them; a desktop-learned extra button does not prove the Control Hub
  can see it.

Bindings can use press, release, held, delayed hold, or repeat; debounce, cooldown, maximum-active
limits; analog values, thresholds, or zones with hysteresis; and button chords. A chord can suppress
the single-button bindings it contains. A macro is simply a reusable routine assigned to a binding,
so there is no second macro file format to learn.

### Learn the flow in Robot Academy

After creating a generated subsystem, open **Help & Learn -> Driver & operator -> Control the
mechanism you created**. The lesson uses the current project's action catalog and control scheme.
For the selected draft or binding, the editor shows a structural trace:

```text
logical control + platform mapping -> event policy -> typed target -> generated binding runtime
-> Redux -> subsystem controller -> cached IO
```

This trace is explanatory metadata. It does not poll a controller, dispatch an action, run a
simulator, or operate hardware. Saving proves that a canonical `.arescontrols` revision was written;
generation proves deterministic source was emitted; compilation, simulator behavior, and physical
controller/hardware checks are later and separate evidence gates.

## Generated code and robot selection

The GUI never edits season Kotlin by string replacement. The shared generator turns the validated
documents into deterministic, typed `GeneratedAresProject.kt`, which is compiled into the APK or
RoboRIO program. Robot builds run `verifyAresProject` and fail when that checked-in output is stale.

FTC presents enabled autonomous entries during OpMode INIT: D-pad left/right changes the choice and
X toggles alliance unless the OpMode locks either setting. For FRC simulation, choose a compiled
routine in Studio's always-visible simulator strip and select **Run auto**. Studio publishes the
exact requested ID on `ARES/Auto/Requested`; the robot reports the routine it actually locked before
motion starts. The SmartDashboard `SelectedAuto` key remains a compatibility path for Driver Station
and mentor tools. If the exact requested routine is unavailable, the generated runtime fails closed
to its do-nothing entry instead of running a different autonomous routine.

Studio distinguishes **Autonomous running**, **Autonomous complete**, and **Autonomous blocked**.
Complete means the generated routine ended and outputs were returned to neutral; choose **Stop** to
leave Autonomous mode. These simulator results are useful evidence, but they are not physical-field
or physical-hardware validation.

## Versioning and collaboration

Use Git as the authoritative team history. Commit canonical `.ares` files and generated Kotlin in
the same change; review JSON and Kotlin diffs together. The app's `.ares/history` revisions are fast
local recovery checkpoints, not a replacement for branches, pull requests, or backups.

Google Drive is appropriate for repository snapshots and off-machine backup. Current telemetry and
session Drive synchronization does not replace Git-aware merging of `.ares` project files. Avoid
having two students edit the same routine or control scheme in separate Drive copies and then
overwriting one copy. Prefer one Git branch per task and merge normally.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Project opens but has no actions | Repoint to the repository root and inspect `.ares/action-catalog.json`. |
| A button highlights on desktop but robot mapping is missing | Learn/verify the FTC or FRC mapping; do not reuse the GLFW index. |
| Vader extra button works on FRC but not FTC | Confirm the FTC SDK/app receives that vendor input; standard controls remain available. |
| Build says generated project is stale | Use **Save & Generate** (or run `generateAresProject`), review the diff, and build again. |
| Routine cannot be saved | Resolve typed-argument, missing-reference, recursion, resource, or field-bound diagnostics. |
| Autonomous does not appear | Enable its autonomous entry and ensure it is present in `.ares/autonomous-catalog.json`. |
