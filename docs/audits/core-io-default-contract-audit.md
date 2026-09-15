# Core IO defaults, intake safety and IMU telemetry audit

Pass 216 reviews core IO contracts and executable defaults, with targeted FTC IMU
and FRC intake integration. It fixes four confirmed ARES issues: incomplete intake
neutralization after a pivot failure, repeated numeric current reads, per-frame IMU
telemetry storage allocation, and nonfinite lighting ratios selecting arbitrary output.

## Findings and changes

- **Intake neutralization:** a throwing pivot stop previously skipped the roller stop.
  Both zero-voltage writes are now attempted. The first failure is rethrown, with each
  distinct secondary failure retained once, including serious errors and shared identities.
  This guarantees attempted shutdown; software cannot guarantee a failed physical write.
- **Sampled current:** an aggregate read followed by validation previously read the two
  constituent values six times total. The aggregate now reads each once and encodes an
  invalid constituent as unavailable. Validation uses that sampled number plus the cached
  freshness flag. Negative, nonfinite and overflowing current cannot become valid power
  feedback. The FRC intake override delegates to the same contract and retains its cached
  status flag.
- **IMU telemetry:** the FTC controller retains an input buffer and six topic keys.
  Its facade delegates logging to the controller, avoiding duplicate publisher storage.
  Each call copies cached inputs once; it does not read the physical SDK on the caller.
  Six values and keys are captured before sink callbacks so nested publishing cannot mix
  frames. Partial/failed copies cannot leak prior values. A reusable public publisher is
  available to other serialized owners; the convenience interface default still allocates.
  Overlapping calls or recursion from an input provider are outside the publisher contract.
- **Prism input math:** five ratio helpers share a finite/clamping conversion. NaN and
  either infinity select the existing explicit solid-off preset (1055 microseconds).
  Finite behavior and preset constants remain unchanged.

No defect was found in the reviewed color/multizone copy defaults: they take one source
snapshot, respect destination capacity, preserve caller ownership and leave excess tail
elements unchanged. Season feedback defaults remain unavailable until an implementation
supplies valid feedback; single-actuator safety defaults request zero voltage.

## Review scope and evidence

Eleven previously pending core files received complete declaration/default-contract review:
LoggableDevice, SimMechanismOutputProvider, SubsystemIO, SyncPolledDevice, SeasonInterfaces,
ServoIO, OdometryIO, ColorSensorIO, DistanceSensorIO, ImuIO and MultizoneDistanceSensorIO.
Abstract interfaces and serialized data declarations are accounted for by contract review,
compiler/API validation and applicable consumer tests, not invented physical coverage.
The new publisher, its tests and the two regression test files were also fully reviewed.
CurrentSourceIO retains its complete review; the core API diff adds only the publisher's
constructor and publish method. The FTC API snapshot has no semantic change.

IndicatorLightIO and PrismDriverIO remain partial because device calibration and complete
preset-table verification are outstanding. The official
[Prism user guide](https://www.gobilda.com/content/user_manuals/3118-2855-0001_user-guide.pdf)
documents the PWM interface; ambiguities in extracted table text were not treated as proof
of a vendor defect or grounds to rewrite constants. The
[indicator product page](https://www.gobilda.com/rgb-indicator-light-pwm-controlled/)
refers to a hardware gradient chart. Indicator positions and its rainbow sentinel still
need an adapter/calibration pass.

The larger FTC facade and REV sensor manager files remain partially reviewed: this pass
covers their IMU telemetry path, not every analog/digital adapter or background lifecycle.
The FRC intake remains partial beyond sampled-current validation. Registry failure
isolation and polling ownership are separate remaining scopes.

The unchanged-production baseline ran 12 tests: seven failures reproduced the findings
and five controls passed. The final focused set has 19 passing test methods, including
5,125 exactly represented ratio cases checked against an independent integer oracle.
Additional cases cover invalid currents, overflow, stop exceptions/errors, buffer and key
identity, prefix changes, failed input/sink retries and reentrant telemetry callbacks.

Desktop JVM allocation measurements after warmup, for 10,000 calls:

| Path | Baseline bytes | Fixed bytes |
| --- | ---: | ---: |
| Direct FTC IMU controller | 5,040,280 | 280 |
| FTC IMU facade | 5,040,000 | 0 |
| Reusable core IMU publisher | New API | 0 |
| Intake shutdown plus current validation | Not measured | 0 |

These are host allocation measurements with observable work and an inert telemetry sink,
not robot loop latency, Android allocation or real device timing evidence. Real sinks and
prefix changes can allocate. Existing FTC background sensor polling is unchanged.

## Candidate validation

Source commit: `7eb380d57ab4116502d19e319f09b5b356d04504`.
Library tree: `0d76019da0309fb34d6eafc024912b34689fd2d0`.
Candidate: `17.0.23-rc.0d76019da030`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,529 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,020 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,338 passing results, zero failures/errors and six existing Studio
skips. These cover three opt-in starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. FTC/starter generated-project checks
and APK assembly passed; FRC/starter generated-project checks passed. All 410 candidate files
were hashed and reverified after consumer validation. Monorepo policy passed, including
source/version/archive identity, shared guidance and links in 377 current documents, with
38 explicitly historical records skipped. Four rebuilt archives differ only in version properties.

Evidence directory: `ARESLib-Kotlin/build/audit-pass216-verified-evidence/`, including
baseline/focused/full XML, logs, candidate hashes, archive comparisons and final summary.
The first full-validation invocation was rejected by Gradle because PowerShell split an
unquoted property argument; the corrected invocation used the same frozen source.
Cached/up-to-date suite results are included; focused tests are not counted twice.

The ledger accounts for 2,950 tracked files: 1,180 fully reviewed, 169 partially reviewed
and 1,601 pending, with zero stale or orphaned records. This pass adds the publisher,
three test files and this report, completes eleven previously pending core contracts and
records partial lighting/REV sensor coverage. Wider adapter and hardware limits remain explicit.

No physical robot, target MicroPython runtime, rendered Studio window, remote workflow,
deployment or public release was exercised. This pass identifies ARES implementation
issues; it does not establish a WPILib or device-vendor defect. The audit goal remains open.
