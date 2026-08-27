# Run Comparison and Guided Diagnosis

ARES Robotics Studio can compare two to six persisted runs from the active team, season, and robot workspace. The workflow is read-only: it does not edit telemetry, tuning profiles, generated robot source, or hardware output.

## Student workflow

1. Import at least two runs from the same robot workspace.
2. Open **Guided Run Review**.
3. Choose the primary run, then select one or more comparison runs.
4. Align the runs by recording start, autonomous start, a shared match event, or a shared timeline annotation.
5. Inspect the shared trajectory and telemetry axes.
6. Open a finding at its exact replay timestamp and verify the listed source topics.
7. Export the mentor/student Markdown report.

The primary run is a reference, not an assumed “good” run. A baseline should be a run whose context the team understands.

## Deterministic alignment

Alignment changes only the viewing coordinate:

```text
aligned time = original persisted timestamp - selected run anchor
```

The original timestamp remains attached to every sample and evidence link. ARES never rewrites session data. Recording start is always available. Other alignment choices appear only when every selected run contains the same marker:

- **Autonomous start:** first persisted autonomous-running state.
- **Match event:** a shared autonomous/TeleOp transition, named event topic, or match action.
- **Annotation:** the same annotation text with a timestamp inside each run's telemetry range.

Notes entered after a run use wall-clock time and are intentionally excluded as timeline anchors unless their timestamp is inside the recorded range.

## Timestamp and unit integrity

- Each graph has one declared physical unit. Volts, amperes, milliseconds, meters, radians, and normalized inputs are never plotted on the same Y-axis.
- Trajectory X/Y values remain meters in one shared field coordinate system.
- Composite current, driver magnitude, mechanism error, and truth-versus-estimate error use only values with the exact same source timestamp.
- ARES does not use a future value to fill a missing sample. Missing or differently sampled data remains absent and is listed as a limitation.
- Each topic contributes at most 1,500 uniformly spaced persisted samples, including both endpoints. ARES creates no interpolated or held samples; chart lines connect adjacent loaded points only as a visual guide, and metrics describe that bounded sample set.
- Mechanism target/measurement series are compared only when at least two runs use the same source-topic pair, preventing unrelated mechanisms or units from being mixed.

## Evidence and claims

Every guided finding is one of:

- **Observed difference:** a configured material difference measured in the selected runs.
- **Correlation — cause not proven:** two measured changes occurred in the same run or review window, but the recording cannot prove that one caused the other.
- **Evidence limitation:** required topics, timestamps, or compatible units were absent.

For example, lower voltage and slower loop timing in one run justify checking power-system evidence. They do not prove that voltage caused the slowdown. A mentor/student review should name the next controlled test before claiming a cause.

Each finding carries the session ID, original replay timestamp, aligned time, evidence window, and source topics. **Open replay at evidence** loads that immutable session and seeks to the exact timestamp.

## Signals

ARES compares signals only when at least two runs contain compatible evidence:

- battery voltage;
- control-loop time;
- exact-instant summed motor current;
- simulator truth-to-estimator localization error;
- driver input magnitude;
- one compatible mechanism target/measurement error pair;
- persisted faults and alerts;
- source-consistent estimated trajectory.

Missing signals are not converted to zero and are not interpreted as healthy.

## Workspace isolation and privacy

Before reading telemetry, the service verifies every selected session against the active `teamId`, `seasonId`, and `robotId`. A run from another workspace fails closed. Comparison and export operate on the local DuckDB data already available to ARES. Export writes a new Markdown file selected by the user; it does not upload or alter the original sessions.

## Simulator acknowledgement noise

The desktop drive publisher continues sending safe neutral frames while a launched simulator waits for a TeleOp. Receiver acknowledgement status `WAITING_FOR_FRAME` is an intentional idle state, so it no longer causes repeated neutral-session restart messages. Once the receiver starts polling, the existing neutral handshake, acknowledgement timeout, 500 ms receiver lease, loopback restriction, and explicit arm behavior remain unchanged.

## Validation

Automated coverage includes paired golden fixtures, workspace isolation, common marker discovery, exact-timestamp joins, unit declarations, evidence deep-link data, report export, and inactive-simulator acknowledgement behavior. Release acceptance also requires a visible Compose Desktop journey at the normal 1440×900 size and the 1100×700 minimum size.
