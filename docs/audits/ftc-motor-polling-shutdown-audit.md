# FTC motor polling, neutral output and resource ownership

Pass 74, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Confirmed findings and changes

- REV motor and CR-servo write caching suppressed zero when the previous output was less
  than 0.001 away. Zero transitions now bypass that tolerance; ordinary repeated/nearby
  commands retain caching. Signed floating-point zero is treated as electrically neutral.
- Motor close only unregistered polling. It now attempts zero output, inhibits later
  writes, invalidates current feedback and unregisters its own reader entry even when the
  zero write fails. CR-servo adapters now implement AutoCloseable with the same output
  inhibition. Their optional external feedback sensor retains its independent owner.
- Polling continued after the last motor closed, and duplicate registrations survived one
  removal. Registration is now unique, closed motors reject registration, and removing the
  last motor stops its worker with a bounded join.
- A shared running flag let a retired worker adopt a replacement registry after an SDK read
  swallowed its interruption. Worker identity now owns each polling generation. Selection
  uses one coherent locked list operation, IO runs outside that lock, the round-robin index
  stays bounded, and errors cannot skip pacing. Interrupted worker handles are cleared so a
  later registration can restart polling.
- A sensor initializer globally unregistered motors it did not own. That operation was
  removed. Base robot shutdown now closes its HardwareRegistry, which owns the registered
  drivetrain/actuator and telemetry resources, using the registry's existing best-effort
  policy. Sensor shutdown remains separate and does not initialize unused devices.
- Stall timing used zero as an uninitialized timestamp. A replay beginning at zero now
  preserves its first interval and trips strictly after 500 ms. Rewind/overflow during a
  pending stall produces neutral output until healthy input permits the existing recovery.
- Existing hardware fault tests leaked motors, an IMU worker and mock-clock state. Fixtures
  now close owned resources and restore the clock. The IMU-disconnect test waits for an
  actual asynchronous failed read before asserting cached feedback.

The output/close lock is separate from the current-sample and reader-registry locks. No
new collections are allocated by periodic motor writes or reader selection. Mock-device
classification is cached once; the preexisting synchronous mock current read and 2 ms
simulated latency remain. This is not a target-device performance measurement.

## Evidence

All nine initial ownership/neutralization regression cases failed before production edits.
The later zero-timestamp stall fixture also failed before the stall change. Baseline logs
and copied XML are in `ARESLib-Kotlin/build/audit-pass74-before-evidence` and
`audit-pass74-stall-before.*` in the library build folder.

Focused validation passed 35 methods: 13 motor lifecycle, 17 robot/sensor lifecycle and
five existing hardware fault tests, plus API checks. Independent fixtures cover a stalled
SDK read, reader replacement, unrelated motor ownership, exact zero, retained write caching,
stall boundaries and clock discontinuity. A signed-zero assertion was corrected to use
numerical zero equality; it was not a remaining hardware-output error.
The public API change only adds AutoCloseable/close to the two CR-servo adapter classes.

Source `0e101c66` pins library tree `f433673160a3bf61ec21057d90b5a5de41b5c268` and
local candidate `17.0.3-rc.f433673160a3`. Full library validation passed in 38 seconds:
1,737 methods, no failures/errors/skips, API checks, FTC Kover and isolated publication.
Serial consumers passed FTC 111, FRC 134, FTC starter 14 and FRC starter 34 methods,
generated-project checks and FTC assembly. Studio passed with 1,790 passing methods and
six existing opt-in skips, 56 dashboard methods, one performance baseline, coverage,
version alignment and file-size checks. Its 15-second invocation reused unchanged test
inputs; other valid Gradle cache/up-to-date reuse is also explicit in the recorded logs.

FTC Kover covers motor controller 87/92 lines and 47/58 branches, CR-servo controller
34/36 lines and 9/18 branches, reader 43/44 lines and 23/34 branches, and sensor initializer
38/39 lines and 25/32 branches. These are host coverage counters, not proof of all device
failure modes. Copied XML, hash manifests, coverage and verified summary are under
`ARESLib-Kotlin/build/audit-pass74-verified-evidence`. Monorepo policy passed with 235
current Markdown documents, 38 historical exclusions, source identity and archive checks.

## Remaining scope

The reader, facade, controller declarations and changed fixtures were read in full. Motor
control review remains partial for feedback freshness, failed nonzero writes, mock polling
policy, positional-servo behavior and borrowed feedback lifecycles. Base-robot review also
retains open inherited-subsystem and shared status/telemetry ownership concerns.

A worker join waits at most one second. An unresponsive SDK read may outlive that deadline;
it cannot adopt a new registry and exits when the read returns. No thread is forcibly killed.
Successful mock zero writes do not prove a disconnected physical actuator stopped. These
tests do not establish Control Hub loop jitter or physical hardware safety performance.
No push, merge, remote publication or device action is included.
