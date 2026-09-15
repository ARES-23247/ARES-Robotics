# XRP JVM lifecycle and device doubles - pass 45

This pass fully reviews XrpBaseRobot (including its mode enum), XrpMotorIO and XrpSensorIO
(including all their in-memory doubles), three new test files, and the complete XRP API dump.
Tracked-source searches found no production caller of this JVM base robot in the monorepo;
the existing XRP hardware test is its direct consumer. The exported MicroPython runtime and
the full XRP physics simulator are separate implementations and retain their own audit scopes.

## Findings and fixes

- A sensor refresh exception bypassed the final stop and left the lifecycle active. A failed
  tick now marks DISABLED, attempts drivetrain neutral and propagates the original failure;
  distinct cleanup failures are suppressed, and a reused exception cannot self-suppress.
  Later successful ticks remain disabled until an explicit mode transition.
- INIT did not neutralize periodically, and DISABLED neutralized after feedback refresh. A
  motor double could therefore integrate one extra active step while inactive. Both inactive
  modes now stop before any refresh. Successful active ticks refresh each device once, without
  reading sensor getters or issuing redundant stops.
- Starting AUTO/TELEOP retained preceding output. Every transition into INIT/AUTO/TELEOP now
  requires a successful neutral boundary; stop failures leave DISABLED. onStop disables before
  attempting neutral. These lifecycle methods do not replace a concrete controller's leased
  output gate or feedback-validity checks.
- Periodic accepted invalid dt without effect, and resetPose replaced its snapshot with invalid
  coordinates. Invalid duration now rejects before refresh and disables/neutralizes through the
  failure path. Pose reset validates all components before mutation. Battery voltage no longer
  fabricates an unmeasured 6 V reading: its initial value is NaN until an integration supplies one.
- The motor double accepted nonfinite or unbounded commands, allowing invalid integration.
  Finite effort clamps to [-1, 1]; nonfinite effort becomes neutral. Its ideal fixed 20 ms step
  and 30 rad/s per unit effort remain explicit fixture semantics. Mutable feedback still permits
  fault injection; an unknown position remains unknown through update or stop.
- Reflectance greater than the valid range, including positive infinity, asserted a line.
  The existing strict >0.5 threshold now requires a value no greater than 1. Each classification
  reads its cached component once. Raw invalid readings remain observable; false does not prove
  known off-line feedback. The normalized white/black convention is confirmed by the primary
  [XRPLib API reference](https://open-stem.github.io/XRP_MicroPython/api.html): 0 is white, 1 black.
- Servo fixture commands could leave the documented normalized interval or become nonfinite.
  Finite commands clamp to [0, 1]; nonfinite commands throw before replacing the prior command.
  No universal neutral servo angle or physical PWM-off behavior is invented.

## Evidence

All 11 initial regression methods failed against the previous code in 6s. They cover lifecycle
failure/neutral ordering, mode transitions, dt, pose, battery, motor effort, reflectance and servo
commands. Baseline logs/XML are preserved at `ARESLib-Kotlin/build/audit-pass45-before.log` and
`audit-pass45-before-evidence`. The initial fixes passed the XRP suite and API checks in 5s.

The final focused gate passed 48 XRP methods (20 new) and five existing core zero-GC methods,
with no failures/errors/skips, plus XRP API checks and Kover XML, in 8s. Additional cases cover
successful once-per-device refresh without hidden getters, failure abort ordering, shared
exception identity, init/stop failure state, explicit recovery, fixed-step displacement and
stop preservation, injected unknown feedback, strict reflectance boundary and cached reads,
and ordinary servo commands. The API dump remains unchanged and was reviewed in full against
the now-reviewed five XRP production source files; generated bridges are compatibility entries,
not extra independent physical behaviors.

The allocation test executed after 50,000 warmup ticks and observed zero bytes in two consecutive
10,000-tick windows. Each tick varies commands and cached reflectance, runs active/inactive
lifecycle refresh, uses the servo fixture, and observes motor displacement and classification.
This supports the tested single-owner host paths, not whole-JVM or physical robot loop timing.
Exception paths and manually replaced pose snapshots are outside the allocation claim.

Kover reports 29/32 lines and 26/26 branches for XrpBaseRobot, 10/10 and 4/4 for XrpMotorIO,
and 14/16 and 12/12 for XrpSensorIO. Unexecuted source lines map to protected property-setter
bridges and interface getter bridges. Complete review and branch counts are not exhaustive
input, subclass or hardware proof. Focused XML is in `audit-pass45-focused-evidence`; the full
gate and exact invoked test-file manifest are in `audit-pass45-verified-evidence`.

Source commit 122eb8b2 binds candidate `17.0.3-rc.5b0923c9b37a` to library tree
`5b0923c9b37a8e4dc4b28236f959feabaa84bdd2`. Stable planned branch versions remain ARES 17.0.3
and Studio 7.0.4. Full library/API/local-publication gates passed in 12s: 1,268 methods with no
failures/errors/skips, including up-to-date/cache reuse for unchanged task inputs. FTC, FRC and
the two starters passed 109, 134, 14 and 34 methods respectively with the same candidate,
plus generated-project checks and FTC debug assembly. Studio's gate passed in 17s: unchanged
ordinary tests remained up-to-date (1,779 passed, six existing opt-in skips); 56 dashboard tests
and one performance baseline reran and passed. Kover, version alignment and file-size gates
also passed. Dashboard smoke retained/restored all 12,000 frames without drops; replay load
was 19.8854 ms, scrub p95 26.8266 ms and rapid-seek burst 5.1275 ms. This is headless host evidence.

Policy passed, including source identity, archive integrity and 206 current documentation link
checks (38 historical records excluded). Logs are `audit-pass45-library.log`,
`audit-pass45-{ftc,frc,ftc-starter,frc-starter}.log`, `audit-pass45-studio.log` and
`audit-pass45-policy.log` under ARESLib-Kotlin/build. Final inventory: 2,521 tracked files,
252 reviewed, 66 partial and 2,203 pending, with no stale or orphaned records. The goal remains active.

## Limits and next scopes

This JVM foundation does not implement EKF/odometry, measure battery voltage, or enforce a
leased output controller around arbitrary direct access to its public drivetrain. Pose is
manually supplied. An integration must own configuration/channel identity, freshness, physical
output gating and overridden lifecycle methods. Its periodic dt is validated but not forwarded
to IO: the motor fixture's update has no time argument and is not a variable-step simulator.
Sensor doubles preserve injected values with no-op refresh. Servo rejection retains a prior
command and cannot prove a physical stop. Failed neutral attempts are explicitly unproven.

The full XRP simulation engine/launcher, swerve solver, remaining estimator/control paths,
Studio lifecycle/retention and tooling/configuration/resource inventory remain in the goal.
Existing intermittent failures, opt-in tests and physical validation limits remain open.
No source search or passing suite closes an unrelated file. All changes/publication are local;
no push, merge, remote release or physical device action occurred.
