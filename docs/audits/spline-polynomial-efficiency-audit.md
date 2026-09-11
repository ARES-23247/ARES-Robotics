# Spline polynomial correctness and efficiency

Pass 95 reviews the complete BÃ©zier evaluator, natural-cubic control solver, and their
existing test classes. SplineMotionProfiler's callers and complete source were traced;
this pass changes its heading-loop work and clarifies its centripetal-limit documentation.
Its earlier rotation/velocity regressions are reused, not claimed as new coverage.

## Confirmed heading error

Collapsed endpoint handles make the first derivative zero at exactly t=0 or t=1.
The old heading evaluator returned atan2(0,0), so a northbound parsed path with omitted
handles could receive a default robot heading of zero throughout the path. Two new
regression methods failed before the fix: direct collapsed handles and parsed path heading.

The evaluator now uses the first nonzero control-polygon direction at either endpoint.
This is the one-sided limiting tangent: at the start, a coincident first handle leaves
the second handle's direction as the leading derivative term; if both handles coincide,
the end anchor supplies that direction. The end uses the corresponding reversed ordering
with the forward travel direction. Asymmetric tests distinguish this from simply choosing
the endpoint chord. Interior stationary points and fully constant curves retain the
existing zero-vector atan2 convention; no unique tangent is invented there.

## Independent math checks and reduced work

The existing position/derivative and natural-cubic formulas passed baseline checks.
New tests compare BÃ©zier derivatives with central differences on 40 deterministic random
curves, verify endpoint derivatives and curve reversal, and compare natural controls for
2â€“7 anchors with an independent dense solve of piecewise polynomial coefficients. That
oracle solves interpolation, C1/C2 continuity and natural endpoint conditions rather than
repeating the production first-derivative solver. Short and coincident inputs are covered.

- Heading evaluation now inlines scalar derivative work without constructing a temporary
  Translation2d. JVM bytecode inspection confirms a primitive double return with no new
  object, boxing call or Function2 invocation in that method. Rotation2d is a value class;
  generic callers can still box it. Position/derivative APIs still allocate their returned vectors.
- Natural-cubic controls factor the shared tridiagonal matrix once for X and Y. For three
  or more anchors, ten primitive working arrays become three; boxed coordinate lists and
  per-anchor temporary derivative vectors are removed. The two-anchor case stays linear.
- Hermite heading interpolation computes its constant wrapped angle once. Its unconstrained
  velocity sweep no longer constructs a sample-sized list of zeros.
- The profiler documentation now distinguishes its fixed 2 m/sÂ² centripetal ceiling from
  the caller's longitudinal acceleration limit. Runtime limits remain unchanged.

These are source/bytecode observations and numerical tests, not physical loop-time or JIT
allocation measurements. Path construction still allocates its result and uses sampled
geometry. Uniform segment parameters define the natural-cubic continuity contract.

## Remaining profiler scope

SplineMotionProfiler remains partial in the ledger. Its sampling-budget policy, direct
ParsedPathData/Hermite input boundary and approximation behavior need dedicated review.
The public PathPlannerParser wrapper already validates finite bounded points, headings and
positive limits; this pass does not duplicate or redesign that policy. Neither checking
the helpers nor passing all pathing tests proves every profiler input is validated.

## Final validation

The final source passed all 169 pathing tests and API checks before freezing. Six new methods include two preserved failure-before regressions. Public signatures are unchanged. JVM heading bytecode has a primitive return and no new object, boxing call or Function2 invocation.

Source `f1e92ddda8e3def7267c5e983b8e75abe52f1a04`; library tree `6a51bbf4cada81927b045df7b82f4a2ebdbd0a64`.
Local candidate `17.0.3-rc.6a51bbf4cada`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 2127 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs; counts do not imply every test was freshly executed. Conditional Studio skips remain recorded in XML.

Copied XML, hashes, logs, heading bytecode and candidate BOM identity are under `ARESLib-Kotlin/build/audit-pass95-verified-evidence/summary.json`; focused XML is under `ARESLib-Kotlin/build/audit-pass95-focused-evidence/`. No physical timing, measured JIT allocation rate or usable Studio-window result is claimed.
