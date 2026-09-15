# Scalar kinematics and gravity feedforward audit

Pass 67, 2026-09-10. Source `ee982527`, local candidate `17.0.3-rc.b6b352cba4d4`,
bound to `release/ares-source-tree.txt`. This pass reviews the full KinematicsMath and
GravityFeedforward files and the new internal product-roundoff helper.

## Numerical defects and fixes

The speed solver evaluated the initial velocity squared and twice acceleration times distance
before taking a square root. Squaring a finite speed of 1e200 produced infinity; squaring
1e-200 produced zero. Doubling a large acceleration could overflow even when its product with
a small distance was finite. Near a braking stop, rounded products could cancel to zero while
the exact radicand remained positive. NaN inputs also propagated into the result.

The solver now validates inputs and returns the absolute initial speed directly when
acceleration or distance is zero. Ordinary accelerating profile points retain a short direct
calculation when intermediate values are normal and finite. Other cases scale by powers of
two before forming the quadratic terms, retain low product and sum residuals, take the square
root, and restore the exponent. This preserves representable results despite overflowing or
underflowing intermediate squares. Nonpositive radicands, invalid inputs and unrepresentable
final speeds return zero. The API returns a nonnegative speed magnitude; this equation alone
does not determine direction. The public method name/signature is preserved.

SplineMotionProfiler uses this helper in its forward and backward acceleration-limit sweeps.
Recovering the finite speed prevents an intermediate infinity from bypassing that edge's
acceleration constraint when min-combined with a larger speed limit. The profile construction
call sites were inspected and their existing tests included; no whole-file profiler credit
is inferred from this review.

Adaptive elevator feedforward previously multiplied payload factor by inventory count first.
That intermediate could overflow before multiplication by a tiny base gain, despite a finite
final answer. Cancellation in one plus a negative payload factor could also erase a residual.
The calculator now retains multiplication/sum residuals for fractional factors and scales large
factors and base gains separately. Empty or negative inventory counts preserve the base gain,
as before. Signed coefficients and factors remain supported. Non-finite inputs and final
outputs outside the finite Double range return zero; callers still own actuator bounds,
configuration validity and enable/freshness checks.

Arm feedforward could return NaN when subtracting two finite, opposite, extreme angles.
It retains the direct cosine path for finite differences and otherwise evaluates the cosine
difference identity from the individual finite angles. Clamping the reconstructed cosine to
[-1,1] prevents roundoff from exceeding the finite gain. The offset convention remains radians
from horizontal, with offset subtracted from the measured angle.

The shared internal helper uses Dekker splitting on safely scaled operands, without arrays,
arbitrary-precision runtime values or boxed pairs. Its preconditions are documented; it is not
a general fused-multiply-add replacement. FTC projects declare minSdk 24, whereas Android's
[Math.fma API](https://developer.android.com/reference/java/lang/Math#fma(double,double,double))
requires API 33. Production code therefore avoids that newer call. Arbitrary precision is used
only in host-side test oracles.

## Evidence

All eight initial regression methods failed against the old source. They cover extreme
zero-acceleration/zero-distance speed, overflowing and underflowing intermediate products,
near-stop cancellation, invalid/unrepresentable results, random exponent cases, payload
scaling/cancellation, gravity output overflow, and overflowing arm-angle subtraction.
Baseline XML/logs remain in `ARESLib-Kotlin/build/audit-pass67-before-evidence` and
`audit-pass67-before.log`.

The final focused gate passes 47 tests without failures, errors or skips, including 14 new
methods. BigDecimal operates on exact Double values for 1,500 randomized speed radicands
(seed 6701), 1,500 payload/gain cases (6702), and 5,000 product residuals (6703). Speed roots
use 80-digit precision; numerical comparisons bound both relative error and ulps. Boundary
tests cover subnormal and maximal values, signs, zero motion, every non-finite input position,
negative inventory and ordinary formulas.

The extreme arm-angle reference was independently computed with decimal Machin-pi reduction
and a cosine series at 450 and 550 digits; both agreed through 100 printed decimal places.
The retained script is `ARESLib-Kotlin/build/audit-pass67-angle-oracle.py`. This oracle does
not reproduce the production cosine-difference identity. Its rounded expected value is
embedded in the test, so test execution needs no extra Python package or network service.

Focused Kover: GravityFeedforward covers 24/24 executable lines, 42/42 branches and 5/5 methods;
KinematicsMath 21/21 lines, 34/34 branches and 1/1 method; DoubleProducts 7/7 lines and 1/1
method (no branches). All three production files and both new test files were read in full.

Five warmed batches of 10,000 mixed normal/extreme iterations measured [344,0,0,0,0] bytes.
Each iteration independently publishes each of its three calculator results to a volatile
sink, with varying inputs. The escaped-array control measured 48,000 bytes. The gate requires
a batch within 256 bytes and aggregate overhead within 4,096 bytes; it detects sustained
allocation rather than proving every invocation allocates zero. Unsupported JVM counters
skip explicitly. No numerical speedup or hardware-loop latency is claimed. Focused XML/Kover
are retained in `audit-pass67-focused-evidence`.

The full library gate passed 1,647 tests without failures, errors or skips, API checks, core
Kover and isolated candidate publication. It reported 1h05m17s elapsed build time; the cause
was not established, and this is not robot-loop timing evidence. The full allocation samples
were [288,0,0,0,0] bytes with a 48,000-byte calibration. Source policy passed, including source
identity, archives, agent guidance and links in 228 current documents (38 historical exclusions).
Candidate consumers passed in dependency order: FTC 109 tests, FRC 134, FTC starter 14 and
FRC starter 34, generated-project verification and both FTC application assemblies. Studio
passed in 3m39s with shared/gateway/app tests rerun: 1,779 passes and six opt-in skips. Dashboard
smoke 56 and performance one reran and passed, along with coverage, release alignment and
production-file-size checks. Final XML, core Kover, logs, angle-oracle script/output and
SHA-256 manifests are retained in `ARESLib-Kotlin/build/audit-pass67-verified-evidence`.
Six opt-in skips remain limitations.

## Limits

These are constant-acceleration and simple gravity models, not complete mechanism dynamics.
Returning zero for an invalid calculation does not itself neutralize an actuator or flag an
estimator fault. Callers retain responsibility for safety state and validating measurements.
Extreme inputs test numerical contracts and do not imply physically meaningful robot motion.
No physical hardware, live Studio window, hosted CI or loop jitter was measured. Gain-container
files and OdometryMath were inspected for scope selection but receive no coverage credit here.
The full-monorepo audit goal remains active.
