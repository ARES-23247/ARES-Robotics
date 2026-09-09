# SysId analysis audit - pass 17

This pass reviews feedforward regression, database channel alignment, spectral analysis,
and completed motor-run analysis/publication in Studio. AutoTuner's step-response model,
quality policy, import parser and proposal workflow remain separate audit work; reading
those call paths here does not close their correctness or test coverage.

## Findings and changes

- A pseudoinverse returned plausible coefficients for rank-deficient data. Regression now
  normalizes the three design columns and voltage, uses one compact SVD, and requires all
  three singular values to exceed a relative 1e-10 threshold. Unidentifiable data returns
  the existing zero/default fit instead of fabricated gains. Transient classification is
  independent of whether feedforward gains can be identified.
- Large column-unit differences spoiled conditioning, and squared raw voltages overflowed
  goodness-of-fit arithmetic. Normalized residuals keep R-squared finite. Binary rescaling
  preserves finite physical gains even when an intermediate units ratio would overflow.
- Database channel alignment rounded source timestamps to milliseconds. Matching and the
  inclusive 50 ms direction-change/alignment bounds now use preserved microseconds, with
  non-finite measurements filtered before matching. The public aligned-row model still
  stores integer milliseconds; this pass does not invent higher-resolution elapsed times.
- Transient classification depended on list order, missed negative voltage steps, and
  interpreted a large polarity reversal as starting from rest. It now orders samples and
  detects an absolute-voltage step from rest in either direction. Scalar normalized passes
  replace temporary velocity lists and avoid overflowing the tail average. This remains
  a 30-sample peak/tail heuristic, not a proof of plant poles or physical damping ratio.
- FFT omitted Nyquist, could overflow its mean/transform and frequency arithmetic, and
  used incorrect endpoint scaling. The one-sided spectrum now includes DC and Nyquist once,
  doubles only interior amplitudes, and normalizes input before windowing. Peak selection
  uses the unscaled windowed transform so endpoint scaling cannot promote Hann leakage
  beside Nyquist. Invalid or unrepresentable output yields an empty spectrum.
- A completed motor run fit its data twice while holding the collector lock. A pure
  AutoTuner computation now returns both summary and recommendation from one fit. Motor
  analysis runs on an owned background dispatcher; publication checks run generation and
  mechanism. New runs cancel pending analysis, and cancellation also prevents publication
  after a non-cooperative computation finishes. Clearing/failing a run or analyzing too
  few samples clears a prior recommendation. Existing public synchronous analysis remains
  available to callers that own their own scheduling.

## Validation

Ten of the initial twelve regression methods failed against the old implementation;
baseline XML is `ARESLib-Kotlin/build/audit-pass17-baseline.xml`. Two additional numeric
boundary failures are preserved in `audit-pass17-extra-boundaries.xml`. Test development
also exposed a suspend-cleanup compile error and incompatible coroutine schedulers; these
were corrected before final validation. Three older AutoTuner tests used a rank-deficient
single-exponential fixture. Their replacement drives a known motor plant at multiple
voltage levels, making all three feedforward coefficients identifiable. The fixture now
closes its owned database/client resources.

The final focused run passed **58 methods**, including **21 new audit methods**: seventeen
math/alignment/publication methods and four collector scheduling/cancellation scenarios.
Queued work, completed-but-unpublished work, new-run cancellation and mechanism changes
are exercised through a controlled dispatcher queue without wall-clock sleeps. Tests
also cover large constant signals with both power-of-two and padded FFT lengths.

The complete Studio gate passed against unchanged local candidate
`17.0.3-rc.b81c0156add9`: **1,332 passing tests and six opt-in skips** (app: 1,297 passing;
shared: 17; gateway: 18), plus **56 dashboard smoke tests** and **one performance-baseline
test**. App tests executed; shared/gateway reused up-to-date results. Kover records:

| Source | Executed lines | Executed branches |
| --- | ---: | ---: |
| SysIdService | 179/183 (97.8%) | 148/202 (73.3%) |
| SysIdDataCollector | 118/121 (97.5%) | 111/132 (84.1%) |
| AutoTunerService | 255/277 (92.1%) | 152/250 (60.8%) |

These metrics describe executed code, not full physical-model or proposal validation.
The configured coverage gate, production Kotlin size ratchet and release alignment passed.
The command was:

```powershell
.\gradlew.bat :shared:test :gateway:test :app:test :app:koverXmlReport :app:koverVerify verifyReleaseVersionAlignment verifyProductionKotlinFileSizes --no-parallel '-ParesVersion=17.0.3-rc.b81c0156add9' '-ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/ARESLib-Kotlin/build/release-repository' --console=plain
```

Run from `ARES-Analytics`. Final logs are `ARESLib-Kotlin/build/audit-pass17-studio.log`
and `audit-pass17-focused-final.log`; Kover XML is
`ARES-Analytics/app/build/reports/kover/report.xml`. Library identity and starter hashes
are unchanged; robot suites were not rerun for these Studio-only changes. Monorepo policy,
shared agent guidance and links in 178 current documents passed; the policy log is
`ARESLib-Kotlin/build/audit-pass17-policy.log`. The staged inventory accounts for 2,388
files: 62 reviewed with scoped validation, 37 partial and 2,289 pending, with no stale
fingerprints or orphaned entries.

## Remaining work

Geometric calibration still uses its existing synchronous solver. AutoTuner's separate
structured-log parser, repeated sample preparation, step-response identification (including
its quadratic settling search), quality arithmetic and mechanism-to-topic mappings remain
open. Simulation/import callers also need their own provenance/publication review.

The collector still consumes a bounded, lossy telemetry fan-out and lacks a request nonce
or physical neutral-output acknowledgement. FFT assumes uniformly sampled input; callers
must establish that assumption. No robot loop-time, jitter, electrical, visible-window or
physical tuning validation is claimed. The repository-wide audit goal remains active.
