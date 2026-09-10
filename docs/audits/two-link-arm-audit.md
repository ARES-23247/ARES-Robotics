# Two-link arm mathematics and simulation audit - pass 42

This pass reviews complete TwoDofLinkageKinematics.kt (including its parameter/coordinate
models), TwoDofLinkagePlant.kt and new internal LinkageCoefficient.kt. It also reviews their
two existing test files, six new core test files, the compiled mock-failure test, generator
manifest test, and the new Studio preview state/test. Generator and Studio canvas changes
receive scoped review; their remaining authoring and lifecycle behavior stays open.

## Findings and changes

- Squared lengths/radii made tiny and huge arms report outside targets as reachable and
  return NaN inverse solutions. One normalized workspace contract now serves reachability
  and IK. It rejects nonfinite targets and allows eight normalized outer-radius ulps at
  boundaries. A factored cosine rule avoids squared terms; both elbow branches reuse the
  calculated sine/cosine. Links below normalized Double resolution have explicitly unresolved
  orientation and select a finite right-angle branch preserving the dominant link direction.
- Singularity detection multiplied the same scale into both sides, making overflow and
  underflow classify straight arms as healthy. Comparing abs(sin(elbow)) with the finite,
  nonnegative dimensionless threshold removes that scale. Unknown angles conservatively
  report near-singular; zero disables the strict band.
- Adding two finite angles could overflow before trigonometry. FK/Jacobian/gravity now use
  trigonometric addition identities on that exceptional path. Inline helpers share allocating
  and buffered implementations without constructing temporary periodic objects.
- Determinant and gravity products lost representable answers through intermediate overflow
  or underflow. Immutable coefficient significands/exponents are built once. Ordinary products
  retain a fast path; exceptional products and opposing gravity components remain scaled until
  final conversion. Largest components are combined first, with compensated ordinary sums,
  preserving small residuals after large opposing torques. Truly out-of-range components remain
  infinite, and unknown raw angles remain NaN: these math outputs require validation before IO.
- The plant's absolute 1e-12 determinant cutoff rejected a valid 1 mm, 1 g mechanism. The
  replacement eliminates joint 2 through its positive inertia pivot and forms the Schur
  complement from nonnegative physical terms. Immutable inertia/gravity terms are cached
  instead of rebuilt at every substep. Unrepresentable effective coefficients fail at
  construction; finite integration results are not a numerical-stability guarantee.
- Startup could violate limits excluding zero; reset retained velocity directed through a
  hard stop. Both now obey limits, remove outward velocity and preserve inward velocity.
  Nonfinite dynamics could commit NaN or silently clamp overflow to hard stops. Every substep
  checks before mutation, and a later failure restores the whole external step. Exactly zero
  Coriolis coefficients bypass velocity squares, avoiding zero-times-infinity when the actual
  term is zero. External steps use at most 50 equal substeps instead of subtracting a remainder.
- A failed generated mock refresh could retain valid feedback and active output values.
  It now invalidates feedback/current, latches an output fault and neutralizes before rethrowing;
  no failed snapshot receives a fresh timestamp. Studio's local lab stops, clears voltage
  controls, retains its committed pose and requires reset after numerical failure. Canvas link
  lengths are normalized before Float conversion, avoiding unusable coordinates for extreme
  physical scales. No physical robot or visible Studio session was exercised.

The standard mass/Coriolis/gravity model was checked against
[MIT's two-link manipulator derivation](https://underactuated.mit.edu/acrobot.html).
That source measures the shoulder from downward vertical and uses pivot inertias. ARES measures
from +X and adds COM offsets to centroidal rod inertias; converting those conventions preserves
its existing ordinary gravity signs. The new energy oracle independently builds the mass matrix
from COM Cartesian velocity Jacobians, differentiates mass and potential, then solves the full
2x2 matrix. Production uses cached physical terms and the Schur formulation.

Public Kotlin method/constructor signatures are unchanged. API checking revealed that Kotlin
had emitted the old private-companion MIN_INERTIA_DETERMINANT const as a public JVM field.
That unused field remains for binary compatibility; the solver does not consult it.

## Evidence and validation

The initial 11 boundary methods all failed in 57s against the old implementation. Evidence:
`ARESLib-Kotlin/build/audit-pass42-before.log` and `audit-pass42-before-evidence`. Five further
range methods all failed before their fixes in 5s (`audit-pass42-range-before.log/.xml`).
These failures include ordinary small-mechanism behavior as well as numerical extremes.

Focused tests include 5,000 seeded FK/IK configurations across binary length exponents -600
through 600 and both elbow branches; 500 numerical Jacobian checks; 500 potential-energy
checks; 500 independent dynamics checks; 2,000 exact-decimal product oracles across binary
exponents -1074 through 1023; and 1,000 generated signed three-term sum cases, checking finite results where the component
error bound is representable. Finite oracle tolerances
account for floating component error and do not prove every possible ill-conditioned result.
Additional fixtures cover subnormal combined terms, large cancellation residuals, zero/invalid
weights, policy boundaries, invalid time/reset atomicity, independent voltage neutralization,
later-substep rollback and explicit recovery. The original zero-Coriolis overflow fixture was
refined: nonzero coupling tests genuinely unrepresentable dynamics, while a separate straight-
arm fixture requires representable motion to succeed without forming zero-times-infinity.

The allocation method executed without skipping and measured zero scratch allocation in two
consecutive warmed 10,000-tick windows, including 20 ms plant integration, reset and buffered
FK/Jacobian/gravity/reachability. Allocating result overloads and physical device timings are
outside that claim. Final focused test/API/Kover log:
`ARESLib-Kotlin/build/audit-pass42-final-focus-api-fixed.log`.

The generated mock test compiles actual emitted Kotlin and executes both invalid-timestep and
numerical-overflow failures, verifying neutral outputs, invalid feedback/current, latched faults
and rejection of subsequent nonzero commands. The first fixture omitted required controller
loop declarations and failed document validation; completing the fixture made the compiled
behavior test pass (`audit-pass42-generated-failure.log` and `...-fixed.log`). The first combined
focus command put --tests after a Kover task and was rejected; correcting task-option placement
exposed the JVM-constant API issue described above. No failing assertion was removed to obtain
these passes.

Source commit 04386eee initially bound candidate 17.0.3-rc.69a962fd43e5. Its full run stopped in
54s on two intentionally sensitive generated-artifact manifests. Review confirmed the generator
source diff was solely the linkage failure guard; updated FTC/FRC manifests and compiled runtime
behavior passed together in 22s. Commit 2084906b binds the corrected source tree
`d10d866f79c622fcf1e16d83d7f745cc9ed87261` to candidate `17.0.3-rc.d10d866f79c6`.
The planned final branch versions remain ARES 17.0.3 and Studio 7.0.4. Candidate publication
and all commits remain local. No consumer validation is attributed to the superseded candidate.

The full library test/API/Kover/local-publication gate passed in 1m 35s: 1,208 tests,
no failures/errors/skips. Unchanged schema suites were up-to-date; changed/dependent suites
executed or reused valid Gradle cache outputs as reported in
`ARESLib-Kotlin/build/audit-pass42-library-final.log`. The verified XML manifest and coverage
snapshot are in `ARESLib-Kotlin/build/audit-pass42-verified-evidence`.

Kover reports 93/95 lines and 80/118 branches in TwoDofLinkageKinematics.kt (including its
parameter/coordinate models), 113/116 lines and 119/180 branches in TwoDofLinkagePlant.kt,
and 60/60 lines and 98/130 branches in LinkageCoefficient.kt. Core-unexecuted lines are the
reach-property getters and constructor rejection messages. Branch gaps include constructor
and exceptional numeric combinations; reviewed contracts and seeded numerical tests do not
establish exhaustive branch or floating-point input coverage.

FTC (109 tests), FRC (134), FTC starter (14) and FRC starter (34) passed against the exact
corrected candidate. Generated-project verification ran and both FTC debug APKs built.
Only invoked debug/simulator test variants are counted; stale release-variant XML is excluded.
Logs: `ARESLib-Kotlin/build/audit-pass42-{ftc,frc,ftc-starter,frc-starter}.log`.

Studio passed in 4m 1s: 1,779 ordinary tests passed, with six opt-in skips, plus 56 dashboard
checks and one performance baseline. Shared/gateway/app test tasks executed. API-dependent
compilation, Kover, version alignment and the production-file size ratchet passed. Both new
preview-state tests executed; LinkagePhysicsLabState.kt has 23/23 lines and 4/4 branches covered.
The dashboard persisted/restored all 12,000 frames with no drops; replay load was 21.7523 ms,
scrub p95 28.5836 ms and rapid-seek burst 4.3293 ms. Evidence:
`ARESLib-Kotlin/build/audit-pass42-studio.log` and the explicit verified XML/coverage/dashboard
snapshots. These host/headless results do not establish visual layout or physical timing.

Repository policy passed: shared guidance, local links in 203 current documents (38 historical
records excluded), and source/version/archive alignment. Log:
`ARESLib-Kotlin/build/audit-pass42-policy.log`. Final inventory: 2,506 tracked files,
223 reviewed, 65 partial and 2,218 pending, with no stale/orphaned records. Review and
validation remain separate dimensions; broad suite execution does not close pending reviews.

## Limits and remaining work

The plant assumes rigid links with rod centroidal inertia mL²/12, including when COM offsets
are customized. It does not model arbitrary inertia tensors, backlash, motor electrical/back-
EMF dynamics or nonviscous friction. Stiff or energetic systems can require a smaller timestep;
unsupported numerical ranges fail explicitly. Hardware characterization, loop jitter and
physical safety are unmeasured. Headless preview-state tests cannot establish a usable window.

Remaining kinematics/estimation and controller DSL files, generator/authoring boundaries,
connection/age ownership and the Studio policy/lifecycle/retention queue continue in subsequent
passes. Previous intermittent validation concerns, opt-in tests and every remaining inventory
file stay open. Passing broad suites does not award review credit to unreviewed files. The full
repository goal remains active; no push, merge, remote release or device operation is included.
