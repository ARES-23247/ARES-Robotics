# Localization diagnostic math and coaching audit

Pass 242 follows clean commit `d8c4902e6a291322064faf0591267da4607045b6` and reviews
the sampled localization/path diagnostics and their pit-coaching consumer. Studio tree
`f255827131e98221d96ce895a6ac6eaa9c9f56a0` uses unchanged ARESLib candidate `17.0.42-rc.526a048d8dfb` and library
tree `526a048d8dfb1092e89be95c8e906e9fbc516027`. This pass changes desktop analysis only.

## Confirmed findings

All 18 initial regression cases failed on the preceding implementation. The preserved XML
records assertion failures, with zero test errors or skips. Confirmed defects include:

- Valid zero NIS observations were discarded. Nonfinite and textual placeholders entered
  calculations; resulting invalid diagnostics could prevent downstream analysis persistence.
- A few recorded NIS values could label the filter optimal, underweighting vision or jittering.
  The code assumed a target mean near two and called values above nine three-sigma outliers.
- Mean residual magnitude was called systematic bias. Opposite 10 cm offsets must have zero
  mean vector while their mean magnitude remains 10 cm. Missing pose data fabricated zero bias.
- Pose components could be paired from different microseconds inside the same millisecond.
  Pose comparison unnecessarily required NIS and ignored inactive-camera placeholders.
- Path RMS missed canonical `Path/Error_CrossTrack`, combined aliases, included inactive/text
  observations, and overflowed for large finite errors. Squaring tiny values also lost precision.
- The coach ignored the `analysis_diagnostics` table populated by the summary engine. Legacy
  metrics used their oldest value and accepted nested suffix matches as aggregate evidence.
- Coaching inferred camera calibration faults and prescribed a 30–50% covariance adjustment
  without evidence supporting that cause or magnitude. A valid path peak alone was ignored,
  and missing peak values were presented as measured zero.

## Mathematical and source contracts

`SummaryLocalizationDiagnostics` computes descriptive statistics from the existing bounded
secondary-diagnostic snapshot. It groups by session and exact topic after removing leading
slashes. Selected topics are sorted once through the existing numeric-series helper and
cached. The latest ordered sample at each source microsecond supersedes previous values before
numeric validity is checked. Explicit alias priority prevents parallel publications or invalid
preferred streams from changing the statistic through opportunistic fallback.

NIS accepts finite nonnegative observations, including zero. The mean scales values by their
maximum before summing, so repeated maximum-finite observations remain finite. `NISSamples`
counts retained recorded samples; it does not count independent camera measurements.

The library publisher emits the last NIS without its measurement dimension or distinct-event
identity. Read-only tracing of `VisionMeasurementController`, `VisionMahalanobisFilter` and
`ARESNetworkStatePublisher` shows both two- and three-dimensional filter paths and repeated
published state. Under the appropriate Gaussian covariance assumptions, a chi-square
distribution depends on its degrees of freedom and has that dimension as its mean; there is
no universal target two or threshold nine. See the
[NIST chi-square reference](https://www.itl.nist.gov/div898/handbook/eda/section3/eda3666.htm).
These scalar samples alone cannot establish whiteness, optimality or the correct Q/R change.
New summaries therefore omit `NISOutlierRatio` and the unsupported EKF/calibration tags.

Published camera and estimator X/Y components must all share a source microsecond. Preferred
estimator families are packed simulator estimate indices 3/4, `ARES/EstimatedPose` indices 0/1,
then `Drive/Pose_X/Y`. Selection requires both topics of one family; samples never combine
families. Simulator truth indices 0/1 never substitute for the Redux estimate. A preferred
family with incomplete paired samples does not fall back to another estimator.

For camera minus estimator offsets `(dx,dy)`, `PoseDisagreementMeanM` is the mean of
`hypot(dx,dy)`, while `PoseDisagreementBiasM` is `hypot(mean(dx),mean(dy))`. The latter describes
the retained mean offset; it is not proof of systematic physical bias. Finite representable
differences and norms are required. Missing comparisons omit metrics rather than generating
zero. Both mean and norm arithmetic avoid overflowing squared or summed intermediate values.
`PoseDisagreementSamples` records the number of complete comparisons.

When present, `Vision/HasTarget` and `Path/Active` must be numeric one at the exact metric time.
Absent flags preserve legacy compatibility and provide no validity/activity guarantee.
Published pose disagreement is not the filter's capture-time innovation; timestamps alone do
not correct latency or establish atomic hardware capture.

Path priority is `Path/Error_CrossTrack`, `Path/CrossTrackError`, `Drive/CrossTrackError`, then
`Drive/Cross_Track`. RMS is `scale * sqrt(mean((error/scale)^2))`, with an explicit measured-zero
case. Peak is the maximum absolute retained error. Existing `Diagnostics/Auto` metric keys
remain compatible, with an added sample count. New screening tags say `PathDeviation` because
the samples alone do not establish autonomous mode.

## Coaching integration and efficiency

The coach loads raw EKF/path aggregate metrics in one query and generated analysis in one read.
`DiagnosticCoachLocalization` uses exact metric names, session isolation and latest
`(timestampUs,sampleOrder)` selection before finite/nonnegative/text checks. A generated
diagnostic family supersedes its raw legacy family, including invalid generated data; old raw
peaks or residuals cannot silently fill holes in a current generated result.

Pose screening describes disagreement and suggests timestamp/frame/measurement checks without
claiming an extrinsic fault. Legacy `ResidualBiasM` remains readable as an unspecified recorded
offset statistic. NIS coaching is informational and asks for dimension and independent-observation
context. Path RMS and peaks are independently usable; missing quantities say unavailable.
Existing generic screening severity thresholds and the path finding identifier are retained.
Extreme display-unit conversion remains finite. Summary times are explicitly described as
display anchors, using zero when generated records have no event timestamp.

Repeated full-frame filtering and temporary intersections in this boundary became one topic
index with cached per-topic numeric preparation and exact-time hash lookups. This removes
redundant scans; selected-topic sorting remains O(n log n), with O(n) storage and pairing.
Two raw coaching queries became one, plus the required generated-store read. These are code
structure observations, not a measured wall-clock speedup or robot-loop benchmark.

## Validation

Focused validation passed 45 tests: 30 new audit methods, five summary integration tests and
ten coaching tests. The first fix run passed all new methods and identified an existing tag
expectation that needed to include the now-recognized canonical path deviation. The corrected
focused run and the full suite both pass.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,226 | 6 |

Full Studio validation has 2,275 passing results, zero failures/errors and six unchanged
opt-in skips. App tests executed; unchanged build dependencies include up-to-date/cache
evidence. Focused tests are not counted twice. All 410 unchanged library candidate files
were rehashed; prior library and robot consumer validation is retained. Monorepo policy
passed, including 404 current-document link checks and 38 historical skips.

The new audit includes independent 3–4–5 geometry, symmetric offsets, analytic RMS, zero/tiny/
maximum finite values, duplicate ordering, invalid preferred aliases, session isolation,
microsecond mismatch, canonical estimator/path sources, validity flags, generated-versus-legacy
precedence and evidence-limited coaching. Existing summary and coaching tests retain integration
coverage. Coaching fixtures now close their owned DuckDB before deleting their temporary directory.

## Coverage and remaining work

The ledger accounts for 3,055 tracked files: 1,380 reviewed, 194 partially reviewed and
1,481 pending, with zero stale or orphaned records. These are scoped file-review and
appropriate-validation counts, not universal executable test coverage.

The two new helpers and new test file are fully reviewed within their stated contracts.
`SummaryEngineService` and `DiagnosticCoachService` remain partially reviewed. Next independent
boundaries include sampled loop/CAN/brownout counters, SysId signal units and motor polarity,
traction/gravity interpretation, and provenance for generated versus user-owned tags. Existing
ambiguous tags are preserved; this pass does not delete potentially user-authored labels.
Existing summaries need regeneration to obtain corrected diagnostics.

Per-topic sampling (2,048 target samples and a 100,000-frame global cap) can miss peaks, drop
later duplicate updates and lose exact cross-topic alignment. These statistics describe only
the retained diagnostic snapshot; they are not all-sample population statistics or independent
camera-event estimates. Core summary aggregates from pass 241 remain unchanged. The loader's
sampling, provenance and truncation contracts need a separate pass before stronger claims.

Analysis and raw reads are separate operations; use completed recordings. Generated records have
no persisted calculation/source version. An entirely absent generated family cannot distinguish
not-yet-calculated from calculated-with-no-evidence, so legacy raw summaries remain readable in
that case. Storage generation markers, tag ownership and transactional replacement remain open.

No physical robot, HIL, rendered Studio window, remote CI, deployment, push, merge or release was
performed. Robot/library bytes, versions and archives are unchanged. Read-only publisher/storage
inspection establishes this boundary without claiming full new coverage of those files.
Machine-local logs, XML, candidate hashes, policy and source identity are under
`ARESLib-Kotlin/build/audit-pass242-verified-evidence/`. The monorepo audit remains active.
