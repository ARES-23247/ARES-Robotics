# Swerve inverse kinematics and angle precision

Pass 47, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and changes

- Caller-owned module lists could change geometry or shrink after module-count capture.
  The solver now snapshots geometry into an immutable list and validates finite coordinates.
  Finite, nonnegative rate limits are checked at construction; zero limits remain usable.
- Short output buffers partially changed output and steering history before throwing.
  Size, null entries and duplicate state identities are checked before mutation. Trailing
  capacity remains untouched, and returned states cannot mutate internal history.
  Desaturation likewise rejects aliases instead of scaling a shared wheel object twice.
- A fixed 0.0001 m/s threshold held old angles for small nonzero lateral commands, changing
  their direction. Every nonzero representable module vector now resolves its own direction;
  exactly zero vectors retain valid previous angles.
- Reset left old held angles behind. It now clears angles, speeds and steering velocities.
  Initial ideal-target seeding is preserved, including Studio's stateless vector preview.
  An initial zero command explicitly seeds rest before a rate-limited start.
- Overflowing rotational products could lose representable cancellation with translation;
  overflowing module vectors could partially emit nonfinite motion. Product overflow uses
  a scaled sum, and any remaining unrepresentable module vector neutralizes all modules.
  Targets and steering velocities are staged before a coupled history/output commit.
- Rate limiting used a subtract/clamp/add expression that could overflow when the target
  and prior signed speeds were opposite extremes. A bounded move-toward operation handles
  an overflowing allowed change by reaching the finite target directly.
- The standalone optimizer admitted invalid speed/angle values. It now validates raw angle
  values before the legacy wrapper can replace nonfinite inputs with zero, then neutralizes
  invalid output while retaining a valid current heading.
- Shared angle wrapping added pi before taking the remainder, erasing tiny normalized
  angles and introducing a pi offset when that addition rounded away for large inputs.
  Normalized values now return unchanged; other finite values reduce before shifting.
  The documented nonfinite-to-zero fallback remains, and raw validity checks remain required.

Rate-change bounds are computed once per update, and optimization avoids repeated angle
reconstruction/wrapping. Existing inline angle fields were already unboxed: the previous
module-state allocation warning was unsupported and is corrected. Buffered output validation
uses N(N-1)/2 identity comparisons, six for the usual four modules. This favors simple,
allocation-free validation for robot-sized arrays; it is not a general large-N optimization.
Two existing math tests only exercised test-local interpolation/clamping lambdas. Their bodies
now check production angle-wrap periodicity and idempotence, preserving the other test cases.

## Evidence

All nine initial swerve regression methods failed against the previous code in 11s.
`ARESLib-Kotlin/build/audit-pass47-before.log` and its copied XML preserve the evidence.
Both initial angle precision methods failed in `audit-pass47-angle-before.log` and copied XML.
The initial correction exposed two strict-angle assertions affected by the shared wrapper;
the wrapper precision fix resolved them without loosening assertions.

An additional desaturation-alias regression failed in `audit-pass47-alias-before.log` and its
copied XML. The final focused gate passed 48 methods, API checks and Kover in 10s, including 22 new
methods. A seeded test checks 5,000 variable-duration updates against independent drive and
steering derivative bounds. Another checks 2,000 four-module vectors against independent
BigDecimal products/sums and reconstructs X/Y velocities from the signed output speed/angle.
Wrap/optimization tests cover right-angle ties, branch cuts, aliasing, small commands, output
ownership, invalid inputs, zero limits and finite extreme reversal. A 4,000-case exponent
sample compares angle wrapping to exact BigDecimal remainder using the represented Double
period; it does not claim arbitrary-precision mathematical pi reduction.

The four-module allocation test warms 50,000 ticks and observes zero allocated bytes in two
10,000-tick windows while changing translation/rotation, stopping, optimizing in place and
desaturating. This is instrumented host allocation evidence, not a physical loop-time deadline
or a claim about owning overloads, exception paths or whole-JVM memory behavior.

Focused Kover reports 112/114 lines and 128/136 branches for SwerveKinematics, 3/3 lines for
SwerveModuleState, and 8/8 lines plus 12/12 branches for MathUtils. Unexecuted alternatives,
floating-point domain exhaustiveness and hardware behavior remain distinct from full source
review. The four existing SwerveKinematicsTest methods were read in full and executed. A tracked
usage search found the Studio vector lab, tests and documentation; robot hardware uses other
swerve implementations, which receive no review credit from this pass.

## Contracts and limits

The reviewed solver implements inverse kinematics and discrete target rate limiting. It is
single-thread-owned, does not solve forward kinematics, and does not implement a time-optimal
steering trajectory. It may overshoot while decelerating. Initial/reset seeding and emergency
neutralization intentionally bypass acceleration constraints. Output validity does not prove
physical configuration, feedback freshness, enable or steering tracking. Finite products keep
ordinary IEEE rounding; scaled overflow recovery is not a fully compensated dot product.

This pass fully reviews SwerveKinematics, SwerveModuleState, MathUtils, MathUtilsTest,
SwerveKinematicsTest and the added tests.
The coordinate-contract document and core API manifest receive only scoped validation credit.
Earlier simulator/network/retention findings and the remaining monorepo inventory stay open.

## Validation checkpoint

Source commit `50e46196` binds candidate `17.0.3-rc.0bce3f5a3522` to library tree
`0bce3f5a352271d33afd62524346e33456a9b6c8`. Full library tests, API checks, Kover and local
candidate publication passed in 1m 39s: 1,313 methods, zero failures/errors/skips. The gate
includes explicit cache/up-to-date reuse for unchanged inputs. Full-suite SwerveKinematics
branch coverage is 129/136; line counts remain unchanged from the focused run.

FTC, FRC and their starters passed 109, 134, 14 and 34 methods against that same candidate,
plus generated-project checks and FTC debug assembly. Studio passed in 3m 27s: ordinary tests
reran with 1,779 passed and six existing opt-in skips, followed by 56 dashboard methods and one
performance baseline. Kover, release alignment and source file-size gates passed. Stable planned
versions remain ARES 17.0.3 and Studio 7.0.4.

Policy verified source identity, unchanged archive hashes and links in 208 current documents
(38 historical records excluded). Logs are `audit-pass47-library.log`,
`audit-pass47-{ftc,frc,ftc-starter,frc-starter}.log`, `audit-pass47-studio.log` and
`audit-pass47-policy.log` under ARESLib-Kotlin/build. Invoked XML manifests/snapshots and the core
Kover report are saved in `audit-pass47-verified-evidence`; the final focused XML is saved in
`audit-pass47-focused-evidence` beside it.

The inventory accounts for 2,534 tracked files: 272 reviewed, 70 partial and 2,192 pending,
with no stale or orphaned records. The goal remains active. No push, merge, remote publication
or physical device action has occurred.
