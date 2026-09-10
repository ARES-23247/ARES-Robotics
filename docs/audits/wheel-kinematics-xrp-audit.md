# Wheel kinematics and paired XRP outputs - pass 43

This pass reviews complete differential/mecanum kinematics and wheel-value files, shared
normalization and mean/ratio helpers, SwerveModuleState, XRP differential IO (including its
standard implementation), the existing differential/XRP hardware tests and five new test files.
SwerveKinematics receives scoped desaturation review. Its steering dynamics, configuration
ownership and remaining allocation behavior are explicitly deferred, not credited as complete.

## Findings and fixes

- Differential forward means overflowed for equal finite maximum wheel speeds. Opposing finite
  wheel speeds could overflow their separation before dividing by a large track width. Cached
  reciprocal width produced zero-times-infinity for a stationary subnormal-width mechanism.
  Means/separations now choose operation order that retains representable results. Inverse
  rotation avoids halving an odd subnormal width before multiplication rounds it incorrectly.
- Mecanum's quarter-sum and reciprocal four-times-moment-arm arithmetic lost finite results
  through overflow, cancellation or underflow. The effective moment arm uses a stable mean;
  compensated sums retain small cancellation residuals, and angular conversion selects division
  order before a tiny average disappears. Inverse conversion computes repeated translation and
  rotation terms once. These accuracy paths add arithmetic; no whole-loop speedup is claimed.
- Coupled wheel normalization computed limit/max first, which could underflow or retain too few
  bits even when the bounded result was representable. Tiny factors now divide the wheel by the
  maximum before multiplying by the limit. Ordinary factors retain a direct path; final values
  clamp to the finite bound for rounding. Differential, mecanum and swerve callers share the
  contract. Invalid vectors/NaN or nonpositive limits neutralize participating speeds; positive
  infinity remains an unlimited normalization bound. Unscaled wheel values retain identity.
- Undersized differential/mecanum buffers silently retained old contents. Buffered operations
  now reject before mutation and preserve trailing elements. Mecanum immutable normalization
  used a four-argument vararg maximum, creating unnecessary temporary-array work; nested primitive
  maxima replace it. Mutable swerve containers alone do not prove allocation-free steering:
  their documentation now distinguishes container reuse from replacement angle allocation.
- Standard XRP differential drive independently clipped motor powers, turning a requested 1:2
  wheel ratio into approximately 1:1 under ordinary saturation. It now scales the wheels together
  before dividing by maximum linear speed. A reusable standard-instance buffer replaces explicit
  per-call wheel-value construction, and each chassis field is captured once. The interface
  default still allocates scratch for custom implementations; this difference is documented.
- setPowers(NaN, 0.5) left one motor active and stored NaN in the other. Invalid paired power or
  invalid chassis/maximum-speed input now neutralizes both. A failed second write previously
  left the first active; incomplete writes/refreshes now attempt both stops before propagating
  the original failure. stop attempts both motors, preserving secondary failures as suppressed
  exceptions. Failed neutral attempts cannot establish physical neutral. The standard drive
  also rejects invalid wheel radius at construction.

Coordinates remain +X forward, +Y left and CCW-positive. Differential inverse conversion projects
onto forward/angular motion; mecanum ordering remains front-left/front-right/back-left/back-right.
Raw conversions follow IEEE arithmetic for invalid inputs or genuinely unrepresentable final
components. They do not turn unknown measurements into apparently valid stationary feedback.
Controller enable, feedback freshness and fault-latch ownership remain outside this raw IO layer.

## Evidence

All 13 initial regression methods failed in 12s against the previous implementation: eight wheel
math methods and five XRP boundary methods. Both modules ran with --continue to preserve all
failures. Logs/XML: `ARESLib-Kotlin/build/audit-pass43-before.log` and
`audit-pass43-before-evidence`. Two additional hand-picked subnormal cases exposed premature
rounding after the first fixes; both failed before operation-order refinement. Evidence:
`audit-pass43-subnormal-before.log/.xml`. No assertion was relaxed to obtain a pass.

The two seeded numerical oracles generate 3,000 signed wheel vectors/limits and 3,000 forward
kinematics configurations across binary exponents -1074 through 1023. Expected ratios, means
and angular differences use exact binary-input BigDecimal values with 80-digit division and
six-ulp output tolerance. Normalization compares differential object/primitive, mecanum object/
primitive and swerve results, including finite bound checks. Hand-picked cases complement the
sampling at rare subnormal/cancellation boundaries; these tests do not prove every input.

The focused gate passed 50 core methods and 12 XRP methods, with no failures/errors/skips,
in 7s. It includes existing kinematics, mathematical-audit and zero-GC regressions, new paired
write/refresh/stop and invalid-input cases, buffer ownership and unscaled identity checks.
Core and XRP API checks passed unchanged. Evidence:
`ARESLib-Kotlin/build/audit-pass43-final-focus.log` and `audit-pass43-focused-evidence`.

Both allocation methods executed. Each measured zero allocation in two consecutive warmed
10,000-tick windows after 50,000 warmup ticks: buffered wheel conversion/normalization and
unscaled mecanum value reuse; and the standard XRP differential drive with alternating inputs.
Swerve steering generation, returned chassis objects, scaled immutable wheel values, the custom
interface-default drive and Pair-returning wheel-distance API are excluded from that claim.
No hardware loop timing, physical motor response or JVM-wide zero-GC claim is made.

Source commit 4f661773 binds candidate `17.0.3-rc.0b8b3dd94d1c` to library tree
`0b8b3dd94d1cd16401327349589b52fa67f30505`. The already-planned stable ARES 17.0.3 /
Studio 7.0.4 branch versions remain unchanged; candidate identity is unique. All publication
and commits are local. The full library gate passed 1,232 methods (859 core, 12 XRP), with
no failures/errors/skips, plus API checks and isolated candidate publication in 1m 35s.
FTC, FRC, FTC starter and FRC starter passed 109, 134, 14 and 34 methods respectively,
with no failures/errors/skips, generated-project verification and FTC debug assembly.
Evidence is preserved under `ARESLib-Kotlin/build/audit-pass43-verified-evidence/summary.json`.
Studio passed 1,779 ordinary methods (six existing opt-in skips), 56 dashboard methods and
one performance-baseline method in 3m 33s, along with Kover, version alignment and production
file-size gates. The dashboard smoke check persisted/restored all 12,000 frames with no drops;
replay load was 19.606 ms, replay-scrub p95 27.2683 ms and rapid-seek burst 4.0451 ms.
These are host test measurements, not robot loop or visible-window evidence. Policy passed,
including 204 current documentation link checks (38 historical records excluded), source-tree
identity, versions and archive integrity. Logs: `audit-pass43-studio.log` and `audit-pass43-policy.log`.

The staged inventory contains 2,513 tracked files: 239 reviewed, 67 partial and 2,207 pending,
with no stale or orphaned records. The ledger records full review separately from test coverage;
the monorepo goal remains active.

Kover reports 33/33 lines and 18/22 branches for differential kinematics, 40/41 and 15/22
for mecanum kinematics, 19/19 and 24/26 for wheel math helpers, and 8/8 and 16/16 for
normalization helpers. Differential/mecanum wheel-value files and SwerveModuleState have
full observed line coverage. XRP differential IO reports 37/46 lines and 39/44 branches;
its default-interface/getter and exceptional cleanup paths are not all exercised. Complete
source review is distinguished from exhaustive line/branch coverage. These gaps remain
visible in the saved core/XRP Kover XML and do not establish physical behavior.

## Remaining work

The full swerve solver still needs configuration, aliasing, timing, acceleration/steering and
allocation review. XRP mecanum IO has analogous saturation/failure paths needing its own full
review; XrpMotorDouble's deterministic simulation contract and the XRP simulation engine also
remain pending. This pass does not claim whole-file review from a search or suite execution.
Remaining estimation, controller/age ownership, Studio lifecycle/retention, tooling and all
other pending inventory files stay in the goal. Hardware and previously recorded intermittent/
opt-in validation limits remain open. No push, merge, remote release or device action occurred.
