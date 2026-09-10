# XRP desktop control, estimator provenance and field boundaries

Pass 46, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

Correction from pass 71: this pass inferred XRP's coordinate convention from a legacy
spawn and preset. Canonical project metadata and the Python collision implementation instead
require a center origin. The corner-origin wall change below was incorrect and has been
replaced together with the inconsistent spawn/preset and fmap conversion. See
[the XRP coordinate contract audit](xrp-coordinate-contract-audit.md). The historical tests
below proved consistency with those old fixtures, not agreement with the canonical frame.

## Findings and changes

- XRP interpreted the first values of a legacy three-value payload as velocity and could
  interpret v2 protocol metadata as motion. The engine now owns an independent canonical v2
  receiver with neutral handshake, sequence validation, explicit teleop and a 500 ms lease.
  Retained input cannot renew authority. Invalid input, time rewind and expiry neutralize.
- Shared receiver age subtraction could wrap at signed Long boundaries. Freshness checks now
  reject rewind/overflow. Acknowledgement generation also expires stale authority, preserving
  agreement between receiver status and neutral applied axes. A published wrong-type topic
  is distinguished from absent input and disarms instead of retaining active control.
- XRP published mutable raw optical fields as its estimator. Observations now pass through
  its Store EKF, and packed/legacy telemetry read the Store estimate and odometry separately
  from physical truth. Field-relative intent uses estimator heading. A separate Store producer
  cannot override a disabled/expired network lease at the output boundary.
- The launcher rejected its own mecanum option, retained input through shutdown and swallowed
  loop failures. It now parses that option before shared CLI handling, polls into reusable
  buffers, paces a nominal 50 Hz loop, neutralizes in finally and preserves borrowed servers.
  One active invocation owns the loop; interruption and failures reach cleanup.
- Center-origin walls put the corner-origin XRP default spawn against the upper wall. Walls
  now follow FTC center coordinates and XRP/FRC corner coordinates, rebuilding when origin
  changes even if dimensions do not. Positive-Y mecanum movement is verified with the actual
  canonical XRP field preset.
- Invalid step duration neutralizes before rejection; invalid pose reset preserves prior
  physical state. Speed/radius configuration rejects invalid values. Coupled invalid powers
  neutralize, finite powers clamp once, and normalized wheel commands share a direct divisor.
  Reused input/wheel/action/acknowledgement storage and removal of redundant pose-array cloning
  reduce repeated work. NT4 still owns a transport copy; new immutable Store/command snapshots
  and physics/telemetry work can allocate.

## Focused evidence

Initial old-code regressions failed: eight lease/time methods (`audit-pass46-before.log`),
four simulator boundary methods (`audit-pass46-boundary-before.log`) and the wrong-type
receiver case (`audit-pass46-type-before.log`). The initial end-to-end gate exposed blocked
mecanum movement; `audit-pass46-e2e-before.xml` preserves that failure. A wall-origin regression
failed in `audit-pass46-origin-before.log` and `audit-pass46-wall-before.log`. The disabled
Store-intent bypass failed in `audit-pass46-ownership-before.log` and its copied XML.

The final focused gate passed 34 methods in 13s (`ARESLib-Kotlin/build/audit-pass46-focused.log`). It covers canonical
protocol domains, independent receiver/payload ownership, lease expiry/recovery, bounded
acknowledgement writes, elapsed overflow, estimator-heading steering, subscriber failure
neutralization, wall replacement without body leaks, both launcher drive modes, retained-input
expiry and joined shutdown preserving a borrowed server. These are headless host tests using
the in-memory NT4 registry, not remote WebSocket, desktop UI or physical hardware evidence.

The retained receiver allocation test warms 50,000 iterations and measures two 10,000-iteration
windows and observed zero allocated bytes, covering duplicate input, absent input and acknowledgement writes. Fresh accepted
frames and exception paths are outside this allocation claim. API additions are the receiver
class plus XRP engine Store access and stop; existing SimInputBridge signatures remain intact.

Full-suite Kover reports receiver 149/152 lines and 90/94 branches, bridge 12/14 and 3/4,
engine 216/219 and 100/140, launcher 54/67 and 18/46, and physics world 95/103 and 28/32.
These include existing tests and are not exhaustive domain or lifecycle proof. Receiver/bridge
misses include default-argument bridges and condition alternatives; launcher cleanup branches
remain substantially uncovered. XML snapshots and invoked-file manifests are preserved locally
in `ARESLib-Kotlin/build/audit-pass46-{focused,verified}-evidence`.

## Scope limits

DriveFrameReceiver and SimInputBridge were read in full. Engine, launcher, NT4Server,
SimPhysicsWorld and API manifests receive scoped partial records. General NT4 bind readiness
is still unproven: the underlying server can retain an instance while startup/bind fails.
Global publisher lifecycle, field replacement transactionality, arbitrary finite extreme
physics parameters, publication sequence semantics and sub-millisecond estimator timing need
further review. The engine models ideal optical observations, not independent IMU fusion or
physical XRP characterization. Nominal pacing and allocation evidence do not measure robot
loop deadlines. Full swerve and the rest of the file inventory remain in the active goal.

## Validation checkpoint

Source commit `00091bdf` binds candidate `17.0.3-rc.993368b5727a` to library tree
`993368b5727a3b89625777d503ecf1a3f203ddf0`. Full library tests, API checks, Kover and isolated
publication passed in 1m 50s: 1,291 methods, zero failures/errors/skips, with explicit cache
and up-to-date reuse for unchanged inputs. FTC, FRC and their starters passed 109, 134, 14
and 34 methods respectively against that same candidate, plus generated-project checks and
FTC debug assembly. Stable planned versions remain ARES 17.0.3 and Studio 7.0.4.

Studio passed in 3m 26s: ordinary tests reran with 1,779 passed and six existing opt-in skips;
56 dashboard methods and one performance baseline also passed. Kover, release alignment and
production file-size checks passed. Policy verified source identity, unchanged archive hashes
and links in 207 current documents (38 historical records excluded). Logs are
`audit-pass46-library.log`, `audit-pass46-{ftc,frc,ftc-starter,frc-starter}.log`,
`audit-pass46-studio.log` and `audit-pass46-policy.log` under ARESLib-Kotlin/build.

The inventory accounts for 2,528 tracked files: 262 reviewed, 71 partial and 2,195 pending,
with no stale or orphaned records. Passing a suite does not close an unreviewed file. The
goal remains active. No push, merge, remote release or device action has occurred.
