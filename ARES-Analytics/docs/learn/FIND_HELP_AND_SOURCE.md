# Find help and current source

Use this guide when you know what you want to accomplish but do not know which ARES screen or code file owns it.

## Find an app workflow

1. Press **Ctrl+K** anywhere in the main app.
2. Type a task or problem, not necessarily a screen name. Examples:
   - `start here`
   - `disconnected`
   - `bring in a run`
   - `gamepad`
   - `quarantine`
3. Choose the matching screen.
4. On supported screens, select **Help** near the execution toolbar to open the lesson for that exact workflow.

The sidebar's **Help & Learn** button opens the entire lesson catalog. A lesson's **Mark as practiced** state is a private reminder, not a grade or safety certification.

For readability, open **Settings → Profile → Accessibility & Usability Options**. **Larger Interface Text** increases the app font scale while respecting the computer's existing text scale. High contrast, colorblind-friendly colors, and larger touch targets remain independent choices.

## Read connection status

The sidebar keeps connection state visible without requiring a tooltip:

- **NT4 on**: Analytics currently has a live NetworkTables connection to the selected target.
- **NT4 off**: no current live telemetry connection was established.
- **ADB on**: for FTC, the laptop can currently reach an Android device through Android Debug Bridge.
- **ADB off**: FTC log pulling/deployment over ADB is unavailable.

These labels do not indicate Driver Station enable state. A disconnected robot can still be enabled by its Driver Station.

## Find owning code

1. In **Profile**, enable **Developer Mode**.
2. Press **Ctrl+K** and open **Developer Reference**.
3. Search for a concept such as `pose`, `hardware reads`, `clock`, `Redux`, or `path`.
4. Read its responsibility, physical units, and invariants.
5. Open the listed source file in the workspace.
6. Read the current declaration and KDoc, then run the listed or nearest focused test.

Developer Reference is intentionally a small curated map. It does not claim that every API is indexed, and it does not generate answers. Live source and tests remain authoritative.

## Success check

You have succeeded when you can state:

- which app screen owns the task;
- whether its data is live, simulated, or replayed;
- which module and source file own the underlying concept;
- the units and invariant that must not change; and
- which test gives the closest verification evidence.

## Safety and recovery

- Opening help or source reference never enables a robot.
- Do not infer physical safety from a green connection or passing unit test.
- If a curated entry disagrees with source, trust source and tests, then report the stale entry.
- Ask a mentor before using live tuning, remote drive, autonomous start, or deployment controls.
