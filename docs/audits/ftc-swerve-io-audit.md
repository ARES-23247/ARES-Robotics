# FTC swerve module acquisition, units and lifecycle

Pass 48, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and changes

- Drive conversion assumed 2048 ticks/revolution and analog conversion assumed 3.3 V.
  The adapter now captures SDK motor resolution and analog range once, with explicit calibration
  overrides. A cached radians-per-tick coefficient removes repeated arithmetic and prevents an
  overflowing multiply-before-divide conversion from rejecting a representable velocity.
- Analog samples had no receipt-age check, so a blocked/dead worker could leave old feedback
  valid indefinitely. Freshness now uses acquisition start for both drive and analog reads;
  a slow returned sample cannot renew validity. Rewind and signed subtraction overflow reject.
  Fresh drive and analog observations are required for nonzero paired writes.
- Invalid power members were independently replaced by zero, and write exceptions were swallowed.
  Invalid coupled commands now neutralize both outputs. A failed write attempts every neutral
  output and propagates its original cause, retaining distinct cleanup failures without unsafe
  self-suppression. Invalid refreshed channels retain prior values with false validity flags and
  neutralize previously active output.
- Close only interrupted a thread: it left motor commands and cached validity alive and allowed
  subsequent commands. It now invalidates authority first, attempts both stops, interrupts/joins
  its worker and reports a join timeout. Repeated close retries failed neutral writes. Borrowed
  motors/encoder remain open. A late callback cannot restore closed state.
- Construction validates configuration and device aliasing, then attempts both neutral outputs
  before sampler startup. Invalid construction acquires no output ownership and does not change
  borrowed device state; the caller owns safe handling of a rejected constructor.
- Warning strings are constructed only after throttling. Normal update/write paths reuse
  primitive state, cache metadata and avoid redundant refresh-time neutral writes when outputs
  are already known neutral. Hardware watchdog requests are not globally cached away.

## SDK boundary

Read-only inspection of the cached FTC RobotCore 11.1.0 AAR confirmed `DcMotor.getMotorType`,
`MotorConfigurationType.getTicksPerRev`/setter and `AnalogInput.getMaxVoltage` signatures.
Compiled adapter bytecode uses matching descriptors; inherited DcMotorEx resolution and the
CachedDcMotorEx metadata forwarder were checked. The desktop mocks add only the metadata surface
needed here. An unspecified motor fixture rejects metadata access rather than guessing a
healthy encoder resolution. The minimal metadata holder starts unknown until configured.

The three-argument adapter constructor is retained; additional overloads expose resolution,
analog range and timeout. Existing fixtures without metadata explicitly supply their calibration.
The API manifests include these additions, SDK mock getters and generated cached/simulator-motor
forwarders. The simulator forwarder invokes the unknown-metadata default; explicit calibration
is required when constructing this adapter around that fixture. This is scoped binary inspection,
not complete FTC SDK emulation or a physical test.

## Evidence

All five initial old-adapter regressions failed in 9s: metadata conversion, coupled invalid
effort, propagated paired failure, close behavior and blocked-sampler freshness. Baseline evidence
is in `ARESLib-Kotlin/build/audit-pass48-before.log` and its copied XML.

The final focused gate passed 23 methods (22 new), API checks and Kover in 6s. Tests cover
configuration rejection before IO, constructor neutral ordering and failure aggregation,
once-per-update reads, cached metadata, explicit freshness, finite unit-conversion extremes,
preserved unavailable values, fatal reads, paired cleanup failures, repeated close and borrowed
device ownership. Latch-controlled tests cover blocked/late ADC reads, rewind/overflow, fatal
worker exit and a read that ignores interruption. The latter proves close reports a timeout
after neutral attempts; it then releases and joins the exact test worker before restoring state.

After 50,000 warmup ticks, two 10,000-tick robot-thread windows observed zero allocated bytes
while converting inputs and varying paired commands/stops. This excludes the sampling thread,
real SDK calls, failures/logging and physical loop timing. Focused/full coverage counts are
reported separately from exhaustive timing, concurrency or hardware proof.

## Scope and limits

The adapter and added tests are read in full, together with the existing one-method adapter
test and the new minimal motor metadata holder. Shared motor/sensor mock files and API manifests
receive scoped partial records; the generated metadata forwarding path was checked without
claiming a new full review of CachedHardware. Other swerve IO/configuration interfaces remain open.

SDK reads/writes can block. A failed stop is not a verified physical neutral output, and an
uninterruptible sampler can outlive the reported join timeout. Freshness is checked during
periodic refresh/commands, not by an independent watchdog. The owning controller must establish
explicit enable/arm, verified physical configuration, fault recovery and continued periodic
execution. Direction, gearing, mounting calibration and real motor response remain hardware work.

## Validation checkpoint

Source commits `cf8cc762` and `3707cf8d` bind candidate `17.0.3-rc.d09ca4313af7` to library tree
`d09ca4313af7d5236d0377d9a779fe8c2f06fef8`. The first full gate failed its simulator API check
in 32s because the SDK getter also generated a simulator-motor forwarder. That manifest was
updated after bytecode inspection. The partially published candidate `17.0.3-rc.1b82c0a92390`
was superseded, never reused for the revised source tree.

The final full library/API/Kover/local-publication gate passed in 12s. Its 1,335 methods have
zero failures/errors/skips; all library test tasks were up-to-date, reusing preceding executions
for unchanged test inputs. Focused adapter Kover measured 120/121 lines and 112/140 branches;
the full-suite snapshot measured 120/121 and 111/140. Neither asynchronous branch snapshot proves
exhaustive interleaving coverage.

FTC, FRC and their starters passed 109, 134, 14 and 34 methods, plus generated-project checks
and FTC debug assembly. Studio passed in 22s: ordinary suites reused 1,779 passing methods and
six existing opt-in skips; 56 dashboard methods and one performance baseline reran. Kover,
release alignment and source file-size gates passed against the same local candidate.

Logs and XML/hash manifests are under `ARESLib-Kotlin/build/audit-pass48-*`; the final snapshots
are in `audit-pass48-verified-evidence`, with the focused evidence preserved separately.
Repository policy verified source identity, unchanged archive hashes and links in 209 current
documents (38 historical records excluded). The inventory accounts for 2,540 tracked files:
280 reviewed, 74 partial and 2,186 pending, with no stale or orphaned records. The goal stays active.
No push, merge, remote release or physical device action has occurred.
