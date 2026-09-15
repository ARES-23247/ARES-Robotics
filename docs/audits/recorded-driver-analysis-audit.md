# Recorded driver analysis audit (pass 247)

## Scope and identity

This pass audits source selection, spectral timing and motion statistics in DriverAnalysisService,
its summary/history integration and the numeric labels in the driver-motion card. Profile
persistence and broader view-model/widget lifecycle remain separate partial scopes.

The local base is `89771784db0b09eb25373f24ec0ff92c7a88c8a1` on
`codex/robot-loop-math-audit`. Verified Studio source tree: `39b25d7f2a4ed322d098ff9bd6e088f4446c633d`.
The unchanged library tree remains `526a048d8dfb1092e89be95c8e906e9fbc516027`, with the existing
local candidate `17.0.42-rc.526a048d8dfb`. The previous Nyquist fix from pass 140 and validated
SysId FFT implementation are preserved.

## Confirmed regressions

All 14 original regression methods failed against unchanged production code.

| Boundary | Reproduced result | Correction |
| --- | --- | --- |
| Source clock | A 10 Hz component at 10,500 microseconds/sample was reported as 10.546875 Hz; a usable 500 microsecond capture was rejected | Preserve source microseconds and derive the uniform grid from elapsed source time |
| Same-time updates | Earlier invalid joystick values defeated valid replacements; chassis vx duplicates counted twice and invalid replacements revived old values | Use the complete latest-update analysis repository before interpreting values |
| Value validity | Text placeholders and nonnormalized joystick values produced oscillation/motion evidence | Text/nonfinite values remain barriers; joystick spectrum requires normalized [-1,1] samples |
| Query redundancy | Missing joystick inputs made four reads | One bounded selected-source statement per jitter or coaching call |
| Tuning inference | A component caused fixed exponent=1.6 and slew=2.5 prescriptions | Those numerical recommendations are unavailable; retain observed spectral evidence |
| Direction changes | Stops generated 584.6 changes/minute; one-second gaps generated 60 changes/minute with strong coverage | Compare adjacent valid moving vectors only within 200 ms; gaps/stops/invalid updates break comparisons |
| Numeric range | Finite vectors of magnitude 1e200 lost all reversals through overflowing dot-product intermediates | Compare normalized direction components |
| Coverage | 40 source timestamps were reported as 30 by a maximum-channel-count denominator | Use the timestamp union, including missing/invalid slots |

## Corrected mathematical contracts

The shared bounded analysis reader provides one selected-source statement snapshot with ordered,
latest source observations. It returns a complete group or explicit unavailability; no downsampled
prefix is analyzed. This removes leading-slash retries and separate channel snapshots. Raw profile
CRUD is not part of this read path.

Source ordering is reused rather than sorting each joystick axis again. Resampling uses a linear
cursor, spectral noise sorting uses a primitive array instead of boxed magnitudes, and motion
norms are calculated once per slot. These are source-level reductions in repeated work; only
query-count reductions are directly asserted, and no wall-clock speedup is claimed.

Each joystick axis needs at least 64 finite numeric normalized samples over at least one second.
The mean source interval establishes the spectral grid, not a median rounded millisecond interval.
The entire 8-12 Hz band must remain below the grid Nyquist frequency; each source interval must
also be below 1/24 second and within 50% of the grid period. Invalid samples are not deleted to
make a continuous signal. Captures outside these limits are unavailable.

Usable axes are linearly interpolated onto the elapsed-source-time grid with a monotonic cursor,
then passed to the existing Hann-window FFT. The reported spectrum describes that interpolated
signal. The bounds do not prove anti-alias filtering or remove interpolation/window bias.
The configured 0.02 normalized-amplitude and three-times-median-noise thresholds remain screening
heuristics. Detection is evaluated per axis before selecting a representative component, so a
stronger noisy axis cannot hide a weaker qualifying one. Missing axes are explicit; a quiet
available axis cannot establish a negative result for every requested control.

An oscillation does not identify a human cause, suitable response exponent or chassis slew rate.
Automatic gain fields are nullable/unavailable, old generated prescriptions are replaced, and
history no longer displays those recommendations. Numeric spectral results require source-axis
coverage metadata, preventing old unproven aggregates from reappearing as current findings.
The separate manually supplied tuning-proposal workflow, its review inbox and hardware leases
remain unchanged. No robot publisher or physical calibration was added.

Motion slots use the union of selected source timestamps. A complete slot needs numeric finite
vx, vy, omega and a representable velocity norm. Sample coverage is complete slots divided by
that union. Observed time is the sum of adjacent valid intervals no longer than 200 ms; temporal
coverage compares that time with the entire recorded span. A direction reversal requires both
adjacent vectors to be moving at least 0.2 m/s and their normalized dot product to be <= -0.5.
Its rate divides by observed minutes, excluding unobserved gaps. Stops, missing channels and
invalid updates break comparisons. These are sampled vector changes, not reconstructed human
intent or continuous physical trajectories.

Combined translation/rotation remains an explicitly labeled sample fraction (>=0.8 m/s and
>=1.5 rad/s), not elapsed-time occupancy. Rates need at least 30 complete samples and 0.5 seconds
of observed time; unavailable statistics display N/A. Strong data coverage additionally requires
200 samples, ten observed seconds and >=90% matched/temporal coverage. The card distinguishes
matched samples, observed time and recorded span. Layout, rendering and session-change behavior
were not validated by launching Studio in this pass.

## Validation

Focused validation passed 86 tests across seven suites, including 28 new driver-analysis
regressions, seven existing driver service tests, 25 prior bounded-input tests, five summary
integration tests, four guided-review tests and 17 existing SysId math tests. All 14 original
failing cases now pass. New evidence includes 500-microsecond and 10,500-microsecond source
cadences, bounded irregular resampling, invalid/text/latest barriers, missing-axis and per-axis
noise-floor handling, budget refusal, gap-excluded observed-time rates, normalized extreme
vectors, coverage-qualified confidence and generated/history provenance.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,383 | 6 |

Full Studio validation has 2,432 passing results, zero failures/errors and six unchanged opt-in
skips. App tests executed; unchanged shared/gateway tasks and dependencies include up-to-date
evidence. Focused tests are not counted twice. All 410 unchanged library candidate files were
rehashed; prior library and robot consumer validation remains applicable. Monorepo policy
passed, including 409 current-document links and 38 historical skips. No physical robot-loop
or rendered-window performance result is inferred from these checks.

## Coverage and remaining work

The ledger accounts for 3,076 tracked files: 1,402 reviewed, 196 partially reviewed and
1,478 pending, with zero stale or orphaned records. These are scoped file-review and appropriate
validation counts, not universal executable test coverage. There are 1,674 files still requiring
full review completion; new helper/test/report files are included in the total.

DriverAnalysisService remains partial for profile persistence failure/constructor concurrency.
SummaryEngineService remains partial for generated-tag ownership and multi-operation atomicity.
RunDataDictionary remains partial for core sentinels/current aliases and other rows. The
driver-motion card is partially reviewed for the changed nullable values, units and coverage
labels; its produceState lifecycle, cancellation, rendering and interactions need independent
verification. Whole-recording snapshots across separate analyses and immediate interruption of
JDBC/native FFT work are not established.

Next independent boundaries include the driver's profile storage failure behavior and the
SysId view model's session-load/cancellation state. Other run-history/current-row and remaining
monorepo scopes stay in the ledger. These changes make desktop interpretation more accurate;
they do not measure physical robot loop time or prove hardware behavior.

No library/version/archive change, rendered-window result, physical robot/HIL, remote CI, push,
merge, deployment or release is claimed. Machine-local baseline/focused/full XML, logs, source
identity and candidate hashes are under `ARESLib-Kotlin/build/audit-pass247-verified-evidence/`.
The full monorepo goal remains active.
