# FTC frame timing and sensor shutdown

Pass 73, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and changes

- Desktop pacing subtracted unchecked elapsed time from 20 ms. A replay clock rewind
  could request a 100-second sleep; signed overflow could request much longer. Pacing now
  sleeps only for a valid elapsed interval below 20 ms. Android and externally paced
  callbacks retain their existing owners, and interruption remains visible to the caller.
- FTC control updates accepted negative or overflowed elapsed intervals. These now enter
  the existing fatal-error/neutralization path before hardware reads and subsystem logic.
  Timestamp zero is tracked as a valid first sample, so a following 25 ms interval remains
  25 ms. First and repeated-millisecond frames retain the nominal 20 ms fallback.
- Closing a robot did not prevent subsequent updates, sensor reads or pose resets, and
  repeated closes repeated teardown. Closure is now terminal and idempotent, including
  when neutralization fails; best-effort cleanup still attempts the remaining resources.
- Sensor shutdown accessed lazy properties, potentially calibrating unused hardware or
  starting an IMU worker during teardown. It now snapshots only initialized resources,
  prevents later initialization, closes all captured resources and reports cleanup errors.
  A shared cold-path lock coordinates initialization with shutdown; initialized getters
  retain the lazy fast path. Allocated teardown snapshots are outside control loops.

## Evidence

The initial 11-method fixture reproduced nine failures against unchanged production code.
Copied baseline XML and log are `ARESLib-Kotlin/build/audit-pass73-before.*`.
The same-throwable cleanup suspicion did not reproduce: Kotlin's existing suppression
handling preserves the original exception. That code was not changed.

Focused validation passed all 16 new methods and two existing loop-timing methods, plus
public API checks. Cases include fault latching, zero/repeated timestamps, arithmetic
overflow, post-close calls, failed neutralization, cached missing devices, concurrent lazy
initialization/shutdown and actual termination of an owned mock-IMU polling worker.
Pacing tests distinguish bounded completion from a multi-second park; they are not a
20 ms scheduling-jitter benchmark.

Source `4ed12b1f` pins library tree `8125d8df69fff72108da5efd9a765b879be472d2` and
local candidate `17.0.3-rc.8125d8df69ff`. Full library tests/API/FTC coverage/local
publication passed in 38 seconds: 1,723 methods, no failures/errors/skips. Serial
consumer validation passed FTC 111, FRC 134, FTC starter 14 and FRC starter 34 methods,
plus generated-project checks and FTC assembly. Studio validation passed with 1,790
passing methods and six existing opt-in skips, 56 dashboard methods, one performance
baseline, coverage/version/file-size gates. Its 20-second invocation reused unchanged
test inputs through Gradle up-to-date checks; this is not a fresh execution of every test.
Unchanged-input reuse in the library and other consumers is likewise recorded in logs.

FTC Kover reports base robot 207/251 lines and 80/124 branches, initializer 40/41 lines
and 25/32 branches, lifecycle controller 15/19 lines and 12/14 branches, and loop profiler
20/24 lines and both branches. These counters do not establish full lifecycle or device
coverage. Copied XML, hash manifests, coverage and final summaries are under
`ARESLib-Kotlin/build/audit-pass73-verified-evidence`. Monorepo policy passed with 234
current Markdown documents, 38 historical exclusions, source identity and archive checks.

## Scope limits and follow-up

The lifecycle controller and regression fixture were read in full. The loop profiler was
also reviewed: its milliseconds use bounded nanosecond differences, and the strict 25 ms
overrun threshold counts core work including cached sensor acquisition exactly once.
Its total excludes caller work between acquisition/update, diagnostic publication and
pacing. It is not an end-to-end physical deadline measurement.

Base-robot and initializer declarations were read, but their broader ownership review
remains partial. A later call is inhibited after close; this does not cancel an already
executing control callback. The lifecycle owner must stop that callback before teardown.
Global motor-reader registration, old/new robot replacement and shared status/telemetry
ownership need a dedicated follow-up. The existing global unregister operation remains.
These are headless JVM/mock-hardware checks, not physical FTC timing or hardware validation.
No push, merge, remote publication or device action is part of this batch.
