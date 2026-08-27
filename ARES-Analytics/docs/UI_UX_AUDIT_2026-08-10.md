# UI/UX and Safety Audit — 2026-08-10

This audit covers every user-facing area in the desktop application. It prioritizes student safety, truthful feedback, recoverable editing, and novice-friendly workflows ahead of visual polish.

## Release blockers addressed in this wave

- Local keyboard driving is disarmed by default, scoped to the Dashboard, and requires a held deadman control. Focus, target, connection, and page changes clear input state.
- Cloud-only deletion no longer deletes the local DuckDB session when the remote request fails.
- The Pit Self-Test no longer fabricates healthy hardware. It is now an honest, read-only telemetry readiness view and never claims that motors were exercised.
- Match Strategy is no longer presented as real analysis. The sample-data implementation is isolated as a developer preview.
- The replay toolbar no longer relies on the missing `DashboardScreenKt$WhenMappings` helper that caused the reported Windows crash.
- Custom SQL results are bounded and communicate truncation instead of materializing an entire season database in memory.
- Destructive robot, local, and cloud deletion flows require confirmation and identify the affected storage location.

## Navigation and workspace shell

### Implemented

- TeleOp Controls is a first-class Robot page instead of a hidden Dashboard drawer.
- The oversized controller drawer was removed.
- Controls and subsystem workspaces use task-oriented layouts and preserve more room for their primary editor.

### Next

- Make the global header collapse progressively at 1024, 1280, and 1440 dp rather than clipping controls.
- Persist target IP only after validation and an explicit Apply action; do not reconnect for every typed character.
- Never switch from a manually selected robot to Local Sim automatically.
- Confirm workspace deletion and protect dirty forms when navigating or switching workspaces.
- Replace color-only connection dots with labeled status and accessible descriptions.

## TeleOp Controls

### Implemented

- The primary flow is now: choose Driver/Operator, select a visible control, then add an action.
- Standard gamepad mappings are supplied for Desktop, FTC, and FRC. Raw hardware slots are hidden under an advanced Hardware setup section.
- Built-in profiles ship with verified defaults; project-owned profiles are never silently remapped.
- Draft edits cannot be stranded or silently replaced by changing controller/binding context.
- Empty chord editing remains in chord mode instead of becoming impossible to rebuild.

### Next

- Add explicit physical-device assignment for Driver and Operator.
- Add simple timing presets: On press, While held, On release, and Repeat. Keep debounce, cooldown, and maximum duration under Advanced.
- Add scheme create, duplicate, rename, and delete operations.
- Add a final generated-code summary showing the target file, TeleOp base class, and how handwritten overrides compose with generated bindings.
- Add responsive controller/list/inspector modes for narrow windows.

## Subsystem Builder

### Implemented

- Replaced the fixed three-column layout with a guided document/architecture workspace and Configure/Generated Kotlin tabs.
- Added a four-stage progress indicator for hardware, state, behavior, and generation.
- Reload confirms before discarding dirty work.
- Persisted document IDs are stable instead of acting like unsafe rename fields.
- Control-rule creation explains and enforces its actuator/numeric-target prerequisites.

### Next

- Add templates for common mechanisms, duplicate/rename/delete, and a guided “Continue to TeleOp Controls” action.
- Move project I/O and code preview generation off the Compose event thread.
- Add responsive single-pane navigation for smaller laptops.

## Dashboard and autonomous selection

### Implemented

- Removed the generated enum-mapping crash dependency from replay controls.
- Local control publication has one owner and an explicit deadman.

### Next

- Make autonomous selection a visible state machine: Disconnected, Requested, Robot acknowledged, and Locked.
- Preserve dismissed critical alerts and do not re-add resolved alerts.
- Prevent stale asynchronous layout/profile loads from replacing the current selection.
- Render loading, error, retry, and unsupported-widget placeholders instead of blank space.
- Store responsive layouts per breakpoint so editing on a narrow window cannot overwrite the desktop layout.

## Routine Studio

### Next

- Give every step a stable ID and use one recursive editor for nested routines at every depth.
- Add an explicit dirty flag and confirm New, Open, navigation, and project switching.
- Make routine/catalog saving transactional.
- Never silently choose a default match autonomous.
- Provide a non-persisted preview-start pose for reusable routines and fail previews atomically when any leg is invalid.
- Replace the fixed-width editor with Field, Timeline, and Inspector modes that adapt to the window.

## Field Studio

### Next

- Flush or confirm pending autosave before project/league changes.
- Show load/save errors with Retry and Choose Folder actions.
- Disable image import until a valid project exists.
- Transcode imported JPEG files instead of writing JPEG bytes under a PNG name.
- Add cursor-centered zoom, pan, and fit-to-field.
- Replace the fixed inspector and toolbar with dockable/wrapping controls.

## Tuning and SysId

### Implemented

- Live observation no longer silently writes robot values into project constants.
- Clearing/changing projects clears the previous constants path and state.
- The richer push/pull/backup tuning panel is no longer shadowed by a duplicate local panel.
- Reverse SysId velocity retains its sign and the graph shows a zero reference.
- Failed start/stop publication no longer leaves a false running state.
- Temporary NT4 publication is labeled as temporary rather than “Apply to Robot Code.”

### Next

- Require connection, disabled robot state, and explicit confirmation before mechanism motion.
- Add ViewModel tests for failed publish, project changes, signed samples, and backup/restore.
- Split the page into Live Values, SysId, Odometry, and Vision workflows.

## Data, Cloud, and Run Review

### Implemented

- Cloud and local deletion semantics are separate.
- Destructive actions confirm storage location and count.
- Custom SQL results are bounded and report truncation.
- Robot imports use the active workspace robot identity.

### Next

- Serialize Cloud operations so refresh, sync, upload, and delete cannot race.
- Make Upload versus Move from robot storage explicit.
- Add a real manual file picker/drop target to Import Center.
- Paginate Run History, default to newest first, and virtualize the comparison table.
- Add workspace/robot/date/mode filters and clear error/retry states.

## Learning, documentation, and administration

### Next

- Replace the Academy sandbox and completion buttons with versioned lessons, persistent progress, and executable exercises.
- Generate KDoc search from the current ARESLib source/Dokka output and compile-test every example.
- Rename Admin to Shared Robot Roster, enforce authorization, avoid writes on page load, and confirm deletion.
- Split Profile into Workspace, Account, Integrations, Accessibility, and Advanced pages with dirty-change protection.

## Verification baseline

- Compile and test the app from a clean checkout.
- Inspect the packaged JAR/MSI for Dashboard/replay class completeness.
- Add screenshot and interaction tests at 1024×768, 1440×900, and ultrawide sizes.
- Exercise disconnected, no-project, corrupt-document, failed-network, and slow-database states.
- Verify keyboard-only navigation, visible focus, non-color status indicators, and touch target sizes.
