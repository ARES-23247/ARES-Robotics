# Drive facade and hold-control audit

Pass 221 covers the shared drive facades, their command limits and hold lifecycle, measured
velocity accessors, controller target deduplication and the subsystem/power interfaces. It
also traces the affected action, replay, reducer and estimator-preparation boundaries. It
does not repeat the preceding hardware topology passes.

## Findings and changes

- **Position hold canceled its own target.** Its nonzero correction was classified as manual
  input by the reducer. A separate `fromPositionHold` flag preserves the target. Translation
  and rotation now release their corresponding holds independently; disabling holds restores
  the appropriate drive mode. Sub-deadzone stick noise no longer captures and immediately
  clears a target. Partial or nonfinite position targets are cleared without dereferencing
  a missing coordinate.
- **Command limits depended on direction and coordinate frame.** Per-axis clipping after
  rotation distorted diagonal commands. Normalized translation is now projected onto the
  unit disk before rotation and scaling. Physical commands use a circular linear limit and
  a separate angular limit. The shared primitive helper preserves the direction of finite
  extreme inputs without overflowing the norm. Invalid inputs or nonpositive/nonfinite
  speed configuration neutralize the whole command.
- **Position correction mixed motor effort with chassis velocity.** Adding static-friction
  feedforward turned a requested 0.1 m/s correction into 0.275 m/s; invalid timing could
  leave 0.175 m/s after the PID had neutralized. Chassis correction now stays in m/s, with
  a circular deadzone and cap. The downstream motor controller retains its feedforward.
  Platform coefficients may represent voltage or normalized voltage/duty effort; this fix
  removes effort from the velocity calculation and does not rescale those adapters.
- **Brake and hold transitions retained inconsistent state.** X-brake now atomically clears
  command velocities and targets. Enabled holds cannot reacquire while braking; deliberate
  movement exits under the existing reducer threshold. PID history resets on target
  changes, release and brake. Invalid field-drive pose or timing releases holds and
  neutralizes the command.
- **Facade configuration and measurements were misleading.** Constructor heading gains were
  immediately overwritten by default Redux tuning. They now apply until a new drive-tuning
  object arrives; subsequent Redux tuning is authoritative. Velocity getters now expose
  cached measured field velocities and angular velocity. Commanded values remain available
  under the full names in `state.drive`; measured getters do not establish freshness.
- **`followPath` only relocated the estimator.** It now submits a `FollowPathTask` to an
  explicitly configured follower and task owner, preserving the actual pose. Unconfigured
  and empty requests fail before submission. No extra loop is created. The existing owner
  must execute updates and dispatch cancellation actions on stop. The facade performs no
  additional alliance mirroring; callers supply the intended path.
- **Repeated commands performed avoidable allocation.** Physical drive commands reuse a
  synchronous action while refreshing its `RobotClock` timestamp. Unrelated actions bypass
  the estimator's otherwise unused preparation wrapper. Holonomic calculations use one
  state snapshot and primitive estimator fields, and tuning updates occur only when tuning
  changes. A primitive Double overload avoids boxing unchanged persistent controller
  targets; Marvin inherits it instead of maintaining a duplicate implementation.

The lifecycle interfaces remain hardware-free contracts. Their units and power-manager
filtering documentation now match implementations. Legacy gamepad shaping, turbo precedence
and BLUE-alliance inversion remain; season code must not apply that inversion twice.

## Focused evidence and compatibility

The new `DriveFacadeControlAuditTest` contains 17 methods; all 17 failed against the previous
implementation. These are related failing scenarios, not 17 independent defects. A further
nine methods in `DriveFacadeBoundaryAuditTest` exercise the repaired boundary behavior.
Together with the existing facade, field rotation, replay, reducer, store ownership/failure,
estimator timing and allocation suites, the final focused run passed **83 results** and the
API checks. The existing path test now observes task initialization, nonzero commands after
20 ms, unchanged estimator pose and neutral cancellation.

| Desktop allocation observation | Before | After |
| --- | ---: | ---: |
| 10,000 unchanged Double target requests | 480,000 bytes | 0 bytes |
| 10,000 physical drive commands, no-op reducer | 720,000 bytes | 0 bytes |
| 10,000 combined hold calculations, no-op reducer | Not measured | 0 bytes |

Action reuse initially left 240,000 bytes per 10,000 physical commands; removing the unused
estimator-preparation wrapper removed that remainder. These measurements isolate facade and
dispatch overhead on this desktop JVM. Immutable Redux reductions still allocate: the
existing store/EKF test reported 904,816 bytes for 1,000 reductions. They do not demonstrate
an allocation-free robot loop or reduced physical loop latency. Synchronous listeners that
retain reused actions must copy or serialize them during dispatch.

The public API changes are intentional: `JoystickDriveIntent` gains `fromPositionHold`, the
facade gains path-owner configuration and hold reset, and the controller base gains the
primitive overload. Old Java constructor overloads remain, but Kotlin data-class copy and
synthetic default constructor signatures changed: **recompile Kotlin consumers**. Schema-1
logs without the new flag default to false; explicit malformed values still fail decoding.
The stricter path-owner requirement and measured getter semantics are documented in
[Drive facades](../../ARESLib-Kotlin/docs/drive-facades.md).

## Candidate and validation

Source commit: `15d4997ca6af38cfff48e730f446bd449bab645e`.
Library tree: `73ec2a3ea406d8f632f6c989f12db4b7f2072bf0`.
Local candidate: `17.0.27-rc.73ec2a3ea406`.

Logs, original/final XML, allocation diagnostics, candidate hashes and archive comparisons
are kept under `ARESLib-Kotlin/build/audit-pass221-verified-evidence/`. Consumers resolve the
same candidate from the isolated local repository.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,595 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,043 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,427 passing results, zero failures/errors and six existing Studio
skips. These cover three opt-in starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. FTC/starter generated-project checks
and APK assembly passed; FRC/starter generated-project checks passed. All 410 candidate files
were hashed and reverified after consumer validation. Monorepo policy passed, including
source/version/archive identity, shared guidance and links in 383 current documents, with
38 explicitly historical records skipped. Four rebuilt archives differ only in version properties.

Validated results include Gradle up-to-date/cache outputs; focused results are not counted
twice in the matrix.

## Coverage and limitations

The ledger accounts for 2,971 tracked files: 1,219 fully reviewed, 169 partially reviewed
and 1,583 pending, with zero stale or orphaned records. Coverage describes file review and
appropriate validation, not universal executable coverage.

Eight previously pending production files and two existing test files receive complete
review records. The new limit helper, two regression files, drive-facade guide and this
report are accounted for. The README receives only a partial link/context review. The
action model, drive reducer and estimator runtime retain their existing partial status;
this pass reviews only the touched command/provenance/preparation boundaries there.

Physical enable, freshness, faults, output leases and stop/close safety remain downstream
responsibilities. No physical robot, native Studio window, remote CI or public release was
exercised. No WPILib defect was established. Changes and evidence remain local, and the
whole-monorepo audit remains active.
