# XRP lifecycle, timing, and hardware adapters (pass 5)

This pass reviews `ARES-XRP-Starter/main.py`, `hardware.py`, and
`simulator/xrp_simulator.py`, following the field-geometry pass. It adds 24 tests in
`test_lifecycle_audit.py` and `test_output_adapter_audit.py`. Shared MicroPython facade,
telemetry, and subsystem code was traced to establish call boundaries, but is not
counted as fully reviewed by this pass.

## Confirmed findings and changes

| Area | Finding and correction |
| --- | --- |
| Physical loop scheduling | Embedded MicroPython `time.time()` has whole-second precision. The loop now uses `ticks_us` and `ticks_diff`, sleeps only for the remaining 20 ms budget, and passes the actual start-to-start period into `robot.step`. The first cycle uses the nominal period because there is no prior sample. |
| Overrun reporting | The facade publishes the supplied period. Passing a constant 0.02 hid overruns and distorted time-dependent behavior. A deterministic clock test verifies 4 ms work followed by 16 ms sleep, then a 35 ms overrun with no added sleep and a measured 0.035-second next period. The same scenario passes across tick wrap. |
| Startup | A failed physical server bind entered the loop anyway; it now raises and runs shutdown. AP startup could poll forever; it now permits at most 150 sleeps/polls, matching the existing station retry budget. This is a bounded polling count, not a hard real-time network deadline. |
| Simulator termination | Normal signal exit stopped only the drivetrain, and exceptions bypassed even that. A `finally` calls the facade's shutdown for normal termination, bind failure, exceptions, and keyboard interruption. Factory setup failures also shut down the already-created facade. |
| Invalid output | Python `min`/`max` can turn NaN into full output. Motor, directional-drive, PWM, digital, and light adapters now neutralize non-finite numeric commands; the simulator motor follows the same rule. Finite motor commands are bounded. Invalid servo positions raise because a universal servo neutral position does not exist; the generated subsystem remains responsible for its declared safe output. |
| Buzzer cache | A failed write could cache a note or neutral command that never reached hardware. Invalidate before an attempted write and commit the cache only after success. An uncertain note write also invalidates a previously cached neutral so recovery must actually write neutral again. Successful identical commands remain suppressed. |
| Optional devices | Importing motor 4 and optional servos in one statement hid motor 4 on a board with only two servos. Resolve each optional device independently. Factory tests cover both board capability combinations and missing-device rejection. |
| RGB state | A class-wide RGB buffer leaked color between board objects. Component adapters now share state only for the same board identity. Failed writes roll back the attempted component in that buffer so a later component command does not replay the rejected value. |

Initial lifecycle/output regressions reproduced 14 failing subcases. A later factory
test reproduced the Beta motor-4 error, and two RGB tests reproduced independent-board
leakage and failed-write contamination. All final tests pass.

## Contract references and validation scope

The timing implementation follows [MicroPython 1.28 timing documentation](https://docs.micropython.org/en/v1.28.0/library/time.html):
use tick differences rather than raw subtraction across rollover. As with MicroPython's
API, intervals must remain shorter than half the tick period.

The [XRPLib API reference](https://open-stem.github.io/XRP_MicroPython/api.html) confirms
encoder revolutions, RPM, IMU angles in degrees, gyro rates in millidegrees/second, and
acceleration in milli-g. Tests verify the conversions to revolutions/second, radians,
radians/second, and meters/second squared. The
[published defaults source](https://open-stem.github.io/XRP_MicroPython/defaults.html)
initializes motor 4 and servo ports independently, consistent with the correction.
The repository's pinned board manifest declares the Beta's four motors and two servos.

The motor adapter's existing nominal 12 V command scale is unchanged. It is a command
normalization convention, not measured battery-voltage compensation. RGB board state is
retained for the lifetime of the controller process; normal physical operation uses the
XRPLib singleton board. The simulator's existing 100 ms maximum integration step remains;
its time semantics and model fidelity must not be confused with physical loop timing.

Local verification:

- Source and generated XRP safety suite: **89 passed** (65 existing plus 24 added).
- Extracted standalone XRP archive: **89 passed**, using its bundled MicroPython runtime.
- Path-based line trace: hardware **99.6%**, simulator **97.7%**, physical entry point
  **75.8%**. These are executable-line figures, not branch coverage. The ordinary Python
  tracer's basename cache conflated `unittest/main.py` with project `main.py`; a filter
  keyed by full filename was used for the entry-point measurement. Its first uncached
  diagnostic run was stopped for excessive instrumentation overhead, then the cached
  filter completed successfully.
- Studio app/archive-consumer suite: **1,209 passed, six opt-in tests skipped**.
  Release preflight passed against the existing isolated candidate. Repository policy,
  guidance integrity, archive integrity, and links in 166 current documents passed.

Logs are under `ARES-XRP-Starter/build/audit-pass5-*.log` and
`ARESLib-Kotlin/build/audit-pass5-*.log`; annotated source is under
`ARES-XRP-Starter/build/audit-pass5-path-trace/`.

The unpublished XRP 3.0.3 archive SHA-256 is
`848f6c05810513da7f3973a357752d439bad5c99570f5546299b226ecb7c8f78`.
Other deterministic starter/example archive hashes reproduced unchanged. ARESLib source
did not change; Studio validation uses `17.0.3-rc.343862ce3c59` from the existing isolated
repository. No artifacts were published and no devices were deployed.

## Remaining evidence

Physical GPIO, XRPLib/firmware behavior, Wi-Fi latency, motor polarity, brownout, bus
timing, and measured loop jitter remain untested on hardware. Fake clocks and adapters
prove the host-side contracts only. Unexecuted entry-point branches include successful
network setup, station-mode failure/recovery paths, and physical OTOS/mecanum creation.
The line trace does not establish coverage for native libraries or generated bytecode.
Further facade/telemetry auditing must inspect socket lifecycle, lease enforcement,
shutdown failure aggregation, and per-loop allocation. The full monorepo goal remains active.
