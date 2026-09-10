# Shared swerve IO and module configuration

Pass 49, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and changes

The checked current/absolute-encoder helpers trusted a validity flag even when the cached getter
returned infinity, NaN or only part of the four-module snapshot. Reused caller storage could
therefore make an incomplete read appear healthy. The helpers now prefill four entries with NaN,
read once, require four finite values and invalidate the entire snapshot on failure. A throwing
getter clears the four entries before its original exception propagates. Short buffers reject
before mutation or invoking the cached getter. Unavailable snapshots skip the getter. Invalid
reads no longer clear unrelated trailing storage. Conforming raw getters preserve that storage.
One private inline helper shares the contract without per-call closures or temporary arrays.

`SwerveModuleConfig` converted malformed/overflowing CAN strings to zero, which is also a real
numeric device identity. CAN access now rejects malformed, overflowing or negative values;
explicit zero remains accepted. Constructor/copy validate nonblank identities and finite geometry
and calibration. The integer convenience constructor rejects negatives. FTC names remain verbatim,
and CAN parsing remains a setup operation. No tracked runtime constructor caller was found;
the public type still needs a correct contract for external consumers.

The module-input DTO retains its serialized fields and initially false validity flags. Its
documentation now distinguishes retained/default numeric values from valid feedback and describes
caller storage ownership. The default module power method remains explicitly sensor-only and
does not prove that a physical command or neutral output occurred.

## Traced boundaries

The CTRE bridge delegates cached measurements to `SwerveCtreDrivetrainReader`. The FRC calibration
sample cache additionally checks physical rotation plausibility and latency; those checks remain
necessary. Checked helper success alone does not prove vendor status freshness, physical
plausibility, enabled state or safe actuation. Raw current consumers remain separate paths.

`ITelemetry` already requires implementations to snapshot retained arrays before returning.
The interface's reusable scratch arrays conform to that contract; this pass does not add copies
to the publishing side. A two-instance telemetry test verifies owned values and truthful checked
validity. Key construction/backend publication are outside the checked-read allocation claim.
The covariance fallback forwards an accepted pose/timestamp once to the required simpler method;
it intentionally does not implement covariance-aware estimation itself.

## Evidence

All three baseline regression methods failed in 10s. The copied XML/log are preserved under
`ARESLib-Kotlin/build/audit-pass49-before*`. Final focused validation passed 20 methods: 14 new
contract/configuration methods, one existing swerve telemetry method and five existing zero-GC
regressions, with API checks and Kover. One intermediate fixture compile error used an untyped
heading; correcting it to Rotation2d resolved the error without changing production behavior.

Tests cover each invalid floating-point member, every incomplete module count, all short buffer
lengths, unavailable flags, exact finite values including signed zero/subnormals/extremes,
trailing storage, ordinary/fatal exceptions, getter counts, telemetry ownership, fallback routing,
DTO legacy/default JSON and explicit validity/timestamp roundtrip, malformed/negative/overflowing
identifiers, nonfinite geometry and calibration, blank names, constructor/copy and valid CAN zero.

After 50,000 warmup iterations, two 10,000-iteration windows observed zero allocated bytes for
paired checked reads (140,000 getter calls total). This is host allocation evidence using cached
fixtures, not a deadline guarantee, real SDK measurement or whole-telemetry allocation claim.
Focused Kover reports configuration 41/41 lines and 50/50 branches, checked helper 10/10 and 12/12,
interface default methods 17/17 lines and input DTO 7/7. Generated compatibility bridges and
unbounded input/concurrency domains are not established by these counters.

## Scope and remaining work

The shared SwerveHardwareIO, SwerveModuleIO/inputs and SwerveModuleConfig files, both new test
files and the existing one-method SwerveHardwareIOTest are reviewed in full. Abstract methods
specify contracts; concrete vendor IO implementations receive no full-file credit from this pass.
The coordinate-contract document receives scoped credit only.

The shared CAN boundary does not infer vendor address ranges or validate bus/device uniqueness.
Finite geometry is not proof of physical plausibility or calibration. Reflection/unsafe object
construction can bypass constructor validation and needs validation at its deserialization owner.
Reads/refresh share one owning loop; helpers do not make concurrent vendor updates atomic.
Vendor reader refresh exception behavior, signal freshness, allocation claims and redundant state
access are queued for a separate pass with vendor-specific evidence and tests.

## Validation checkpoint

Source `681db64c` binds candidate `17.0.3-rc.9fb62f05bd44` to library tree
`9fb62f05bd44b1fd39beac58eae3dc233829a1fe`. Full library tests, API checks, Kover and local
candidate publication passed in 1m 40s: 1,349 methods with zero failures/errors/skips. Unchanged
build inputs include explicit cache/up-to-date reuse; changed module tests executed.

FTC, FRC and their starters passed 109, 134, 14 and 34 methods against that same candidate,
plus generated-project verification and FTC debug assembly. Studio passed in 3m 24s: 1,779
ordinary methods passed with six existing opt-in skips, plus 56 dashboard methods and one
performance baseline. Kover, release alignment and production file-size gates passed.

Policy verified exact source identity, unchanged bundled archive hashes and links in 210 current
documents (38 historical records excluded). Logs are `audit-pass49-{library,ftc,frc,ftc-starter,
frc-starter,studio,policy}.log` under ARESLib-Kotlin/build; invoked XML/hash manifests and core
Kover are preserved in `audit-pass49-verified-evidence`, alongside separate baseline/focused evidence.

The inventory accounts for 2,543 tracked files: 287 reviewed, 74 partial and 2,182 pending, with
no stale or orphaned records. The goal remains active.
No push, merge, remote publication or physical device action has occurred.
