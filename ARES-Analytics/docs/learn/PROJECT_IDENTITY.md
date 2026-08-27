# Set up project identity

Project Identity creates or reviews `.ares/project.json`, the small Git-tracked document that tells every ARES builder which robot and field frame belong to the selected repository.

Open **Robot Studio**, then choose **Set up project identity** when the first stage needs action. You can also open **Robot > Project Identity** directly.

## What each value means

- **Stable project ID** connects drivebase, subsystem, controls, autonomous, and tuning documents. It is not a display name. Choose it once; after creation the editor locks it to prevent accidental broken references.
- **Team ID, season ID, and robot ID** are stable machine identifiers used by generated code,
  evidence, imports, and optional backups. They are stored here—not in a second root identity file.
- **Friendly name** is the student-facing label and can change without changing those stable IDs.
- **League** comes from the selected workspace. FTC and FRC projects use different generated adapters, so Project Identity will not silently change the platform of an existing project.
- **Authoring model** declares whether `.ares` documents, team Kotlin, or an explicit hybrid boundary owns robot behavior. The field is required; Studio never guesses ownership from source files.
- **Coordinate convention** is derived from the league. FTC uses a center origin with counter-clockwise-positive heading. FRC uses the blue-corner origin with counter-clockwise-positive heading.
- **Robot length and width** are measured bumper-to-bumper dimensions in meters. Do not enter wheelbase or track width here.
- **Field length and width** define autonomous bounds in meters. ARES pre-fills its current league preset, but a student or mentor must verify it for the selected season.
- **FTC Control Hub runtime** selects the robot's command path before an OpMode starts. **Standard FTC SDK** is the recommended default. **ARES Photon** is an experimental direct REV Hub write path with per-command SDK fallback and requires restrained physical-hardware validation before competition use.
- **Limelight camera proxy** is an optional Control Hub network bridge for reaching Limelight web/video ports from the laptop. Leave it off unless the team actually uses that route.

Project Identity does not store CAN IDs, motor names, tuning gains, credentials, or evidence that hardware was physically tested. Those responsibilities stay in their dedicated documents and workflows.

## Safe creation and editing

1. Confirm the selected project path shown at the top of the screen.
2. Measure the robot footprint; do not guess values to make validation pass.
3. Verify the field preset against the current game manual or team field model.
4. For FTC, keep **Standard FTC SDK** unless a mentor deliberately enables and validates experimental Photon. Enable the Limelight proxy only when the network layout needs it.
5. Select **Review structured diff**. No file is written yet.
6. Read every before/after value and the destination.
7. Select **Create reviewed identity** or **Save reviewed changes**.

When updating a valid file, ARES preserves the previous canonical content under `.ares/history/project/<content-hash>.json` before replacing it atomically. If the file changes after preview, the save is rejected and you must reload and review again.

An unreadable existing `.ares/project.json` is protected. ARES will not overwrite it. Preserve the file, repair it or restore a known-good revision, then reload. A workspace/canonical league mismatch is also protected rather than rewritten. Older schemas are unsupported; create or export a current project so ownership is explicit instead of carrying a compatibility layer.

## Success check

Return to Robot Studio. **Project & robot identity** should report **Ready** only when the canonical file exists, validates, and agrees with the selected workspace. After generating and building FTC code, the dashboard Control Hub Health card reports **FTC SDK SELECTED**, **PHOTON ACTIVE**, or **PHOTON SELECTED · INACTIVE** from robot telemetry. The latter distinction prevents a saved choice from being mistaken for working hardware acceleration. Simulation cannot prove Photon hardware activity.

This is document evidence, not a build, deployment, or physical-robot safety result.

Next: [Build one robot with Robot Studio](ROBOT_STUDIO.md).
