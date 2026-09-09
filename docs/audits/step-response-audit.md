# Step-response and PI math audit - pass 19

This pass extracts and reviews the numerical step-response estimator and gain calculation.
AutoTuner's remaining proposal delivery, legacy state and provenance contracts are still
partially reviewed. Robot control code and library source identity are unchanged.

## Findings and changes

- The former estimator treated the first five-percent response crossing as dead time.
  A first-order plant has already spent approximately 0.0513 time constants rising at
  that point. It then subtracted that value from the 63.2-percent crossing, biasing both
  estimates. Linear interpolation at the 10/90-percent crossings now gives
  `tau = (t90 - t10) / ln(9)` and `delay = max(0, t10 + tau * ln(0.9))`.
- A selected step previously used the entire remaining log as its response, including
  subsequent voltage changes. Identification now examines constant-input plateaus and
  selects the largest usable response. An incomplete larger final step cannot hide an
  earlier measured one. Ramps, insufficient samples, unordered/duplicate/invalid times
  and non-finite fields do not identify a model.
- The steady response uses a finite normalized tail mean and a five-percent tail-range
  criterion. A changing initial velocity cannot masquerade as a steady baseline. For
  coast-to-step experiments, AutoTuner supplies DC gain `1/kV` only from its independently
  identifiable feedforward fit with R-squared at least 0.70; response timing then uses
  the measured initial velocity. Without that independent gain, moving-baseline data
  remains unidentifiable. No simulator truth is supplied to this path.
- Baseline/tail means and residuals now use normalized quantities to avoid overflow.
  Model fit is R-squared about the observed response mean, replacing the old residual
  ratio about the final target. An incomplete or non-finite model, negative plant gain,
  or model fit below 0.5 cannot recommend positive feedback gains. Negative voltage and
  velocity with a positive plant gain remain supported.
- Settling previously rescanned every remaining suffix. One pass now records the last
  sample outside the two-percent band, and settling starts at the next observed sample.
  Plateau traversal is also linear; examined plateaus are not rescanned as new tails.
  Non-random-access input lists are copied once before indexed scans, avoiding quadratic
  linked-list traversal. Immutable unknown-model and zero-gain results are shared.
- The old gain calculation combined a PI proportional rule with PID integral/derivative
  terms. The first-order model now uses one consistent SIMC PI rule: with the existing
  conservative response choice `lambda = max(0.65*tau, 3*delay)`, proportional gain is
  `tau / (K*(lambda+delay))`, integral time is `min(tau, 4*(lambda+delay))`, and derivative
  gain is zero. Parallel integral gain is proportional gain divided by integral time.
  The existing finite gain caps remain; canonical proposal envelopes still apply.
  This follows [Skogestad's published first-order SIMC PI rules](https://skoge.folk.ntnu.no/publications/2003/tuningPID/smooth_tunings/).

The new numerical helper separates model calculations from publication and IO. The
existing Golden/Monte Carlo assertion messages now include model diagnostics on failure;
their acceptance, recovery and stability thresholds were not relaxed in this pass.

## Validation

Eleven of the twelve initial analytic/access-count regressions failed against the old
algorithm after a behavior-preserving extraction. Baseline XML is
`ARESLib-Kotlin/build/audit-pass19-baseline.xml`. The late-excursion case performed
**146,591,284 sample reads for 20,001 rows**. The new algorithm passes a bound of **30
reads per row** for that same input. This measures algorithmic work, not hardware loop
latency or a wall-clock speedup.

The first integrated run exposed the flywheel golden fixture's coasting initial state.
The independently identified DC gain path fixes that case, verified with a separate
analytic moving-state response. The focused suite then passed 43 methods, including
both golden fixtures and all existing Monte Carlo thresholds. Five further sign,
chronology/input, numeric-boundary and linked-list methods bring this pass to **18 new
methods**.

The final complete Studio gate passed in 3m 23s with **1,370 tests passing and six opt-in
skips** (app: 1,335 passing; shared: 17; gateway: 18), plus **56 dashboard smoke tests**
and **one performance-baseline test**. App tests executed; shared/gateway reused unchanged
results. The configured coverage gate, production Kotlin size ratchet and release alignment
passed. Monorepo policy, shared guidance and links in 180 current documents also passed
(`ARESLib-Kotlin/build/audit-pass19-policy.log`). Kover reports **137/137 executable lines and 128/156 branch outcomes** for
StepResponseAnalysis, and **156/177 lines and 103/156 branches** for AutoTunerService.
Uncovered branches remain recorded; line execution is not complete behavioral coverage.

An earlier complete run failed the existing ProjectBuildServiceTest replacement test
while waiting five seconds for the first parent PID, before replacement began. Its cause
is unproven. No matching probe JVM remained when checked after the failed run. PID timeout
assertions now include process/generation state and captured output; the deadline and
lifecycle assertions are unchanged. All 19 lifecycle tests and the then-current 17 math
tests passed in a targeted rerun, followed by the final complete gate above. This adds
diagnostics, not a proven process-lifecycle fix; the affected test file remains partially
reviewed.

Validation used the unchanged local library candidate `17.0.3-rc.b81c0156add9`:

```powershell
.\gradlew.bat :shared:test :gateway:test :app:test :app:koverXmlReport :app:koverVerify verifyReleaseVersionAlignment verifyProductionKotlinFileSizes --no-parallel '-ParesVersion=17.0.3-rc.b81c0156add9' '-ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/ARESLib-Kotlin/build/release-repository' --console=plain
```

Run from `ARES-Analytics`. Final evidence is in
`ARESLib-Kotlin/build/audit-pass19-studio-final.log`; focused results are in
`audit-pass19-focused-final.log` and `audit-pass19-process-recheck.log`. The failed complete
run is retained as `audit-pass19-studio.log` and its lifecycle failure XML as
`audit-pass19-process-failure.xml`. Kover XML is
`ARES-Analytics/app/build/reports/kover/report.xml`. Library source identity and robot
consumers are unchanged, so their suites were not rerun for these Studio-only changes.

## Remaining work

This is an approximate first-order model. Four-to-eight baseline samples, ten tail
samples, a ten-percent baseline-motion threshold, five-percent voltage/tail tolerances
and the 0.5 model-fit threshold are explicit heuristics. They do not establish physical
settling, identify arbitrary higher-order/oscillatory plants, or measure transport delay.
Coasting with latency yields an effective model delay; it is not a hardware timing claim.
Crossings interpolate observed timestamps; the separate quality policy enforces telemetry
gap limits. Physical tuning must still use the existing review and validation workflow.

Proposal delivery, legacy apply/rollback state, simulation fidelity and imported/simulated
result provenance remain open. Feedforward-only proposal eligibility also needs review
where a usable feedback model is unavailable. The bounded-process adapter and physical
robot timing retain their previously recorded open scopes. The repository goal remains
active, and changes remain local.
