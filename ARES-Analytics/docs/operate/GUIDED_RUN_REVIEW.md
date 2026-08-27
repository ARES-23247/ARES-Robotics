# Guided run review

Robot Academy uses this exact screen for **Bring in and identify one run** and **Explain one run
with bounded evidence**. Observable checkpoints come from the workspace run list, the selected
report's source/metrics/comparison/limitations, and successful Markdown export. Conclusions remain
student reflections; ARES never converts a generated finding into a proven cause.

Use **Analysis → Guided Run Review** when you have a completed simulator, practice, or match run and want to understand it without starting from a table or SQL query. The review is read-only: it does not change the run, publish tuning, edit robot source, or command hardware.

## Follow the evidence path

1. Confirm the selected workspace names the expected team, season, and robot.
2. Choose a run. ARES lists only sessions with that exact identity.
3. Read **Data source**, **Freshness**, and **Interpretation confidence** before interpreting a graph.
4. Inspect timestamps, units, and persisted alerts.
5. Choose one or more compatible comparison runs from the same team, season, and robot.
6. Align by run start, autonomous start, a shared match event, or a shared timeline annotation. An option appears only when every selected run contains it.
7. Keep **Observed difference** and **Correlation — cause not proven** separate from possible causes.
8. Use **Open replay at evidence** to load the named session at the original persisted timestamp and verify the listed topics.
9. Export the mentor/student Markdown report alongside the original logs.

Trajectory and telemetry overlays share an X-axis only after their run timestamps are shifted by the selected anchor. Each graph retains one physical unit. Derived signals join exact source timestamps; a future value is never borrowed to fill a gap. See [Run comparison and guided diagnosis](../RUN_COMPARISON_AND_GUIDED_DIAGNOSIS.md) for the complete contract.

## Understand the confidence language

- **Moderate evidence** means the source and timeline support threshold screening and same-robot comparison. It does not prove causation or physical safety.
- **Limited evidence** means the run is usable, but source identity, summary coverage, or a screening service is incomplete.
- **Insufficient evidence** means the timeline or telemetry topic set is missing. Missing data is not a normal measurement.

Every review is historical. Its freshness line names the persisted timestamp range and explicitly says it is not a live reading. A green status or a quiet alert list never proves that a robot is safe to approach.

## Preserve source identity

Import through **Data → Log Imports** whenever possible. ARES then keeps the source filename, decoder, accepted and rejected record counts, warnings, and SHA-256 digest. A workspace Drive object can also provide a stable object identity and digest. If neither record exists, Guided Run Review labels provenance incomplete rather than guessing.

## Use the next action safely

Suggested actions only open an existing ARES tool:

- **Dashboard replay** shows the selected session timeline.
- **Tuning** lets you review current, requested, and canonical values; opening it does not apply a proposal.
- **Robot Academy** explains evidence and missing-data limits.
- **Log Imports** preserves a better source record for the next capture.
- **Advanced Run History** retains tables and developer analysis through progressive disclosure.

For a physical test, stop at the recommendation and use the team's supervised procedure. Simulator or recorded evidence does not establish mechanism clearance, wiring correctness, actuator direction, current limits, or emergency-stop readiness.
