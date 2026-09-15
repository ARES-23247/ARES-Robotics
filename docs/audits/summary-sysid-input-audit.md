# Summary SysId input, units and interpretation audit

Pass 245 follows clean commit `609cda552f2db4f58a2a4d14ba4638242a889295`.
Studio tree `3bdff5a4bb4b02a2baced2baf3cef00d943312d4` uses unchanged ARESLib candidate
`17.0.42-rc.526a048d8dfb`, source tree `526a048d8dfb1092e89be95c8e906e9fbc516027`.
This pass reviews automatic summary SysId preparation, shared explicit-channel alignment,
fit provenance and the history rows consuming those fits. The earlier SVD/FFT solver audit
is retained, with its tests rerun after integration.

## Confirmed defects and source contracts

All 17 original regression cases failed against the preceding code:

- An initial nonzero velocity was counted as a reversal and nearby samples were discarded.
  Direction-change screening also scanned the entire reversal list for every velocity sample.
- Millisecond voltage maps collapsed distinct source microseconds. Exact-millisecond joins
  rejected independently sampled channels, while acceleration matching had no separation bound.
- Explicit zero acceleration was replaced by a derivative. The first derivative could be invented
  as zero, and subsequent derivatives could span missing feedback or use rounded source times.
- Text placeholders and superseded invalid updates could enter numerical fits. The standalone
  explicit-channel API also removed invalid samples before choosing nearest observations.
- Motor duty-cycle power was multiplied by an invented 12 V supply. Broad substring matching
  admitted voltage limits and nested configuration paths; mixed velocity sources overwrote one
  another without preserving their units.
- Left/right voltage arithmetic inferred motor polarity and topology from names, averaged absent
  wheels as zero contribution, and asserted a signed angular effort without its required contract.
- Near-zero native encoder velocity was treated as proof of a gravity gain. Native wheel speed
  was compared directly to chassis speed and presented as a traction-loss percentage.
- History asserted metre-based units for channels whose catalog does not declare metres. Its
  SysId name canonicalization also merged distinct device identities and exposed old unsupported
  gravity, traction and automatic ADRC interpretations.

The read-only producer trace identifies `MotorIO.velocity` as ticks per second and its published
Power as scaled duty cycle. Other motor interfaces publish VelocityRps or VelocityRpm. The
shared state publisher explicitly publishes chassis angular velocity in radians per second;
wheel names alone do not establish an angular applied-voltage channel. The Studio catalog's
generic Drive/Velocity and Drive/Acceleration entries do not specify a physical length basis.

The [WPILib feedforward contract](https://docs.wpilib.org/en/latest/docs/software/advanced-controls/controllers/feedforward.html)
requires consistent units. The [mechanism identification models](https://frcdocs.wpi.edu/en/latest/docs/software/advanced-controls/system-identification/introduction.html)
distinguish a constant elevator gravity term from an arm term involving angle. A stationary
voltage mean without mechanism, pose, load and friction information cannot identify either
gravity model. These are ARES input/interpretation defects; no WPILib defect is established.

## Shared recorded-channel preparation

RecordedSysIdInputs is used by automatic summaries and SysIdService.analyzeMotorData. Each
channel selects the highest-order update at each source microsecond before value validation.
Sources remain distinct and belong to one session. Invalid/textual latest updates remain
barriers; nearest matching does not fall back through them to an older finite reading.

Channels are sorted once. Matching uses forward-only nearest-neighbor cursors with an inclusive
50,000-microsecond bound and the existing later-sample tie rule. Reversal screening uses one
ordered reversal list and a monotonic cursor, not an all-reversals search per row. Initial
motion, invalid feedback and separated segments do not invent a known reversal timestamp.
Samples within 50,000 microseconds of an observed sign reversal are excluded.

Supplied acceleration, including zero, is retained. Only an absent optional acceleration source
permits a backward difference. It requires an immediately preceding valid velocity observation
with a positive gap no greater than 50,000 microseconds. It uses source microseconds even when
the legacy AlignedDataRow display anchor is the same integer millisecond. Invalid, initial,
excessively separated and unrepresentable derivatives are omitted. A voltage-alignment failure
does not change which velocity observation precedes the next derivative.

Caller arrays remain unchanged. After ordering, adjacency, matching and reversal scans are
linear in input length; maps and output arrays still allocate on the desktop. A 20,000-sample,
19,999-reversal case verifies the expected exclusion result. This is stress/structural evidence,
not a measured speedup or robot-loop benchmark.

## Automatic fits and provenance

SummarySysIdDiagnostics groups sources once. Exact motor paths contain one nonempty device
segment and a recognized metric, preserving case and device identity. AppliedVoltage takes
priority over Voltage by source presence; an invalid preferred stream cannot borrow a legacy
alias. Duty cycle, bus voltage, voltage limits and nested configuration are not applied voltage.
Velocity takes priority over VelocityRps and VelocityRpm. Acceleration must use the corresponding
suffix/basis; values are not silently converted or combined across bases.

The recorded model remains `V = kS*sign(v) + kV*v + kA*a`. A fit needs at least ten aligned rows,
finite coefficients, finite R-squared in its accepted range and the existing quality thresholds.
Its source units and polarity remain native, and the three-term fit does not include gravity.
The output records VoltageSource, VelocitySource, AccelerationSource, Model and FitSamples.
The finite mathematical reciprocal is now InverseKA, not an automatically approved ADRC b0.

Angular fitting requires an explicit signed Drive/AngularVoltage channel paired with
Drive/Velocity_Omega and optional Drive/AngularAcceleration. The voltage must represent the
chosen angular-effort convention; the caller owns compatible polarity/units. This optional
recorded-input contract is tested; this pass adds no robot publisher. Ordinary wheel names and
voltages no longer manufacture that quantity.

Automatic kG and TractionLoss outputs are removed because their required mechanism/geometry
and unit contracts are absent. They are unavailable, not measured zero. A future physical
estimator needs explicit mechanism type, coordinate/angle, wheel conversion, topology, voltage
polarity, synchronized observations and model validation before those names can be restored.
The existing fit is a recorded-data regression, not a substitute for that validation or an
instruction to apply gains to hardware.

RunSysIdRows displays finite results in native speed/acceleration units, with R-squared and
actual fit counts. Old results lacking valid FitSamples show N/A; regenerate summaries to
populate the corrected evidence. Retired gravity/traction/ADRC rows are not displayed. Exact
SysId motor discovery rejects nested paths and preserves names such as bl/rl and FL/fl instead
of canonicalizing them into the same device. Unrelated current-row alias handling remains open.

## Validation

Focused validation passed 125 tests: 40 new SysId input/display audit methods, 17 previous
SysId math audit methods, five SysId service tests, five summary integration tests, 28 health
audit tests and 30 localization tests. The original 17 regression cases now pass. The real
summary path recovers known kS=0.4, kV=1.6 and kA=0.32 from synthetic regression columns
with independently timed channels. This is numerical evidence, not a physical plant test.
The 20,000-sample reversal case also passes in focused and full runs.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,330 | 6 |

Full Studio validation has 2,379 passing results, zero failures/errors and six unchanged
opt-in skips. App tests executed; unchanged shared/gateway tasks and dependencies include
up-to-date evidence. Focused tests are not counted twice. All 410 unchanged library candidate
files were rehashed; prior library and robot consumer validation remains applicable.
Monorepo policy passed, including 407 current-document links and 38 historical skips.

## Coverage and remaining work

The ledger accounts for 3,068 tracked files: 1,394 reviewed, 195 partially reviewed and
1,479 pending, with zero stale or orphaned records. These are scoped file-review and
appropriate-validation counts, not universal executable test coverage.

The new alignment, automatic-fit and history-row helpers and their regression file are reviewed
within their stated contracts. SummaryEngineService remains partial for its per-topic sampled
loader, driver recommendations, tag ownership and persistence transactionality. RunDataDictionary
and RunHistoryScreen remain partial outside the changed SysId rows and discovery integration.

In particular, automatic summary input still comes from the existing bounded per-topic sample.
Sampling can remove invalid updates, perturb alignment, omit transients or truncate later topics;
the helper can validate only the observations it receives. Removing that lossy SysId loading is
the next independent boundary and is required before claiming complete source-observation
coverage. The explicit-channel API also retains its existing full-channel loading behavior.
No current live/recording-wide snapshot, capture timestamp/epoch or calibration provenance is
invented. A good R-squared can still describe an inappropriate or unexcited physical experiment.

No rendered Studio window, physical robot/HIL, hardware tuning, robot-loop timing or remote CI
result is claimed. No library/version/archive change, push, merge, deployment or release occurred.
Machine-local baseline/focused/full XML, logs, source identity and candidate hashes are under
`ARESLib-Kotlin/build/audit-pass245-verified-evidence/`. The full monorepo goal remains active.
