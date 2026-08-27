# Deterministic replay and dashboard evidence

ARES replay is a read-only view of one persisted run. It cannot publish robot controls, alter the
live NT4 topic store, or silently fill missing measurements with current live values.

## The replay clock

The timeline uses recorded timestamps for the playhead and a monotonic clock only to advance
playback. Wall-clock time, network arrival time, and the current date do not change replay order.
At equal timestamps, DuckDB `sample_order` is the stable tie-breaker.

Every dashboard update is one immutable `ReplayFrame` containing all latched numeric and string
values at the newest recorded sample at or before the playhead. Seeking first restores the latest
value for every topic before the active query window, then applies in-window samples in order. A
sparse topic therefore stays visible until another recorded value replaces it.

Robot actions, alerts, and annotations are timeline evidence. They do not extend the telemetry
time range and do not synthesize pose, odometry, sensor, or actuator values.

## Reading the Dashboard

The source pill says **Replay** and names the recorded robot when identity metadata is available.
The timeline reports elapsed and total recorded time to the millisecond.

- **Exact sample** means the playhead is on a recorded telemetry instant.
- **Held N ms** means the dashboard is displaying the last recorded values before the playhead.
- A cyan circle is an action, a gold diamond is a note, and a red flag is an alert.
- **No ... topics in this recording** means missing evidence. It does not mean a healthy zero.

Controls are Play/Pause, Stop, Step Back, Step Forward, Loop, speed from 0.25x through 8x, and the
timeline slider. When the timeline has keyboard focus: Space toggles playback, Left/Right steps,
and Home/End seeks to the bounds.

The field keeps simulator truth, EKF estimate, raw odometry, and vision as separate layers. It
never substitutes truth for a missing estimator or odometry sample. Angles remain CCW-positive
radians internally and are converted only for display.

## Failure and recovery

| Status | Meaning | Recovery |
| --- | --- | --- |
| Loading recording | DuckDB bounds, identity, markers, and the first replay window are loading | Wait; do not repeatedly select the same row |
| No telemetry samples | The session exists but has no replayable telemetry | Inspect the import report and source log; actions alone are not telemetry |
| Replay unavailable | A database/query failure prevented a trustworthy snapshot | Preserve the source, reopen the run, and check Operations logs |
| Seeking | A bounded replay window is being replaced | The newest seek wins; wait for the exact time to appear |
| Missing widget topics | That producer did not record the data family | Select another widget or fix logging for the next run; do not infer zero |

Stopping returns to the first recorded sample without changing the selected source. Leaving the
historical run returns other screens to live data; revisiting the same run preserves the replay
position for that app session. Import and replay work offline.

## Verification boundary

Automated tests prove stable duplicate-timestamp ordering, atomic snapshots, numeric/string
latching, baseline restoration, seek cancellation, source isolation, field-source separation,
empty and zero-duration behavior, and smoke/soak performance budgets. Desktop interaction tests
verify the actual Compose controls and visible source labels.

This is software evidence. It does not claim that a sensor was calibrated, a mechanism was safe,
or a physical robot matched simulation unless the run contains separately reviewed hardware
evidence.
