# Mathematical calculations audit

This audit covers mathematical correctness and avoidable computational work in ARES-owned
estimation, drive kinematics, geometry, feedback control, trajectory profiling/sampling, calibration,
and XRP runtime calculations. It preserves the unrelated XRP board-profile and release edits that
were already in the working tree.

## Confirmed defects and corrections

| Area | Original behavior | Correction and regression evidence |
| --- | --- | --- |
| Continuous PID | Differentiated raw headings across ±π, producing a nearly full-turn derivative spike. | Wrap the measurement difference as well as the position error; test both crossing directions. Removed unused previous-error state. |
| Input/linkage edge cases | Centered joystick with zero deadband divided by zero; non-finite IK targets and gravity offsets produced NaN results. | Neutral zero-length joystick input, reject invalid IK targets, and return zero feedforward for an invalid angle offset. |
| Scalar Kalman filter | An absolute denominator cutoff disabled corrections at small, valid covariance scales. Subtracting `1-K` could also lose posterior variance. | Scale-independent positive-denominator check and equivalent `R*K` posterior variance; test successive updates at ordinary and tiny variances. Negative noise is rejected. |
| Matrix inversion | Absolute determinant cutoff rejected small well-conditioned matrices; large cofactors overflowed. | Normalize the matrix before inversion and apply a relative singularity threshold. Test `A*A^-1` across scales from `1e-120` to `1e120`. |
| EKF vision validity | Infinite coordinates could enter the state when statistical gating was disabled. | Finite pose checks and unconditional innovation-validity checks; invalid observations leave pose/covariance untouched. |
| EKF delayed vision | Rewinding before an accepted camera correction replayed only odometry, erasing that correction. | Reject capture times older than the last accepted camera observation; regression retains the existing pose and covariance. |
| EKF process noise | Pure odometry rotation could receive stationary heading noise when no gyro rate was supplied. | Include the larger measured odometry/gyro yaw rate in heading uncertainty growth; check turn-in-place and replay equivalence. |
| Wheel normalization | Some object overloads and swerve normalization reversed commands for negative limits; non-finite commands survived other overloads. Tiny swerve commands could bypass a smaller speed limit. | Shared scalar normalization policy across differential, mecanum, and swerve. Invalid coupled vectors become neutral; valid vectors retain their ratios. |
| Swerve stop state | A stopped steering angle retained its previous angular-velocity history. | Clear that velocity when holding the angle stationary, so restart limiting begins from the actual held state. |
| FTC/XRP wheel odometry | Midpoint rotation treated encoder arc length as chord length, overestimating curved displacement. | Apply the SE(2) arc-to-chord factor. A unit-length quarter-circle now ends at `(2/π, 2/π)`, rather than `(√0.5, √0.5)`. |
| XRP drive saturation | Independently clipping each wheel changed the requested translation/rotation ratio; NaN could become full power through Python min/max. | Normalize the entire wheel vector before output; invalid commands neutralize all motors. Differential and mecanum ratio tests cover saturation. |
| Spline initial tangent | Used the waypoint chord instead of the natural spline's initial derivative. | Evaluate the actual Bézier derivative at the start; test a non-collinear three-point spline. |
| Spatial profile sweeps | Negative acceleration at a corner contaminated the next acceleration bound, creating unreachable zero-speed plateaus. | Keep directional sweep acceleration bounds nonnegative; the timed provider checks the resulting derivatives. |
| Timed trajectory | Angular speed limits stretched individual segment times without consistently reducing linear velocity. Sample vector acceleration/jerk could exceed configured bounds. | Uniform time scaling keeps linear/angular velocities consistent with timestamps and bounds reported sample derivatives. Tests integrate straight-path velocity and check corner acceleration/jerk. |
| Vision calibration | Arithmetic mean of ±π headings interpreted a stationary camera as extremely noisy. | Circular mean plus wrapped residuals; test observations on both sides of π. |
| Geometry/encoder calibration | Recommendations assumed a 0.45 m wheelbase and 2,000 ticks/m; insufficient rotation fabricated a default result. | Carry actual configuration with the sample, reject missing/changing configuration or insufficient excitation, and test non-default configurations. |

## Computational changes

- Path distance lookup uses binary search instead of scanning from the beginning on every sample.
  Both API variants are tested on a 32,768-point path with fewer than 40 point reads per lookup,
  including late-path queries. Boundary, duplicate-distance, and wrapped-angle behavior is covered.
- Vision NIS uses a triangular solve against the Cholesky factor. Rejected observations avoid
  constructing the inverse. Accepted observations reuse that factor for the gain.
- Vision correction no longer constructs a temporary `Pose2d`; yaw and standard-deviation scaling
  are reused. Incidence calculation uses a normalized dot product rather than an angle conversion.
- History cloning delegates to the existing copying operation, which copies only active ring slots.
  Odometry reuses its already-computed translational speed.
- Wheel normalization shares one internal policy, avoiding divergent copies of the edge-case math.
- XRP angle wrapping uses bounded modulo arithmetic; invalid angles raise an error instead of
  hanging. Mecanum updates reuse the encoder buffer, avoid per-cycle list/zip construction, and
  reuse sine/cosine values. Python floating-point operations still allocate; this is not a claim of
  a zero-allocation MicroPython runtime.
- Studio calibration avoids temporary mapped lists for its means, and its regression solver no
  longer depends on an unused NT4 service.

## Reviewed boundaries

Review included the shared SE(2) propagation/Jacobians, Cholesky/gain/Joseph update, replay/history,
2D/3D geometry, differential/mecanum/swerve transforms, linkage FK/IK/Jacobian/gravity/dynamics,
PID/ADRC/feedforward/profile/filter implementations, spline and trajectory math, coordinate/alliance
transforms, FTC fallback and drive feedforward, FRC's delegation to the CTRE estimator, simulation
world integration boundaries, Studio SysId/FFT/calibration, and MicroPython drive integration.
Existing regression suites provide additional coverage; source inspection is not a formal proof
of every numerical expression in the monorepo.

The linear and seven-element spin calibration sample formats are documented in
[the telemetry contract](../ARES-Analytics/docs/TELEMETRY_CONTRACT.md). Estimator and trajectory
semantics are documented in [the math contracts](../ARESLib-Kotlin/docs/math-and-coordinate-contracts.md).

## Fidelity and behavior limits

- No physical robot or hardware-in-the-loop validation was performed. Encoder scale, sensor noise,
  friction, wheel slip, and real motor behavior still require physical calibration.
- The trajectory provider checks discrete sample acceleration and finite-difference jerk. The
  spatial seed uses line segments and heuristic ramps, not the previously claimed seven-phase
  continuous-time S-curve/Bézier algorithm. These checks do not prove continuous jerk at geometry
  corners or enforce a complete force model. Uniform time scaling can reduce nonzero entry/exit
  speeds; the provider reports this explicitly for trajectory handover review.
- Out-of-order camera corrections are rejected rather than replayed. Supporting them without
  losing information requires retaining camera updates alongside odometry history.
- Matrix inversion retains a relative numerical-singularity guard and the existing zero-matrix
  fallback. Scale-invariance tests do not imply reliable inversion of arbitrarily ill-conditioned
  matrices.

## Validation

Validated on 2026-09-06 with isolated candidate `17.0.0-rc.math-audit.20260906.2`.
The library's `test apiCheck publishReleaseValidation --no-parallel` completed successfully before
the consumer runs. Every JVM consumer used that same candidate and the absolute local repository
`file:///C:/Users/david/dev/robotics/ARES-Robotics/ARESLib-Kotlin/build/release-repository`.
No remote release was published.

| Product | Verification | Passed | Skipped |
| --- | --- | ---: | ---: |
| ARESLib-Kotlin | `test`, `apiCheck`, isolated candidate publication | 1,043 | 0 |
| ARES-FTC | `:TeamCode:testDebugUnitTest :simulator:test` | 109 | 0 |
| ARES-FRC | `test` | 134 | 0 |
| ARES-FTC-Starter | `:TeamCode:testDebugUnitTest :simulator:test` | 14 | 0 |
| ARES-FRC-Starter | `test` | 34 | 0 |
| ARES-Analytics | `:shared:test :gateway:test :app:test` | 1,230 | 5 |
| ARES MicroPython runtime | `python -m unittest discover -s ARESLib-Kotlin/ares-micro/tests` from the monorepo | 35 | 0 |
| ARES-XRP-Starter | `python -m unittest discover -s tests` from the starter | 30 | 0 |
| **Total** | **No test failures or errors** | **2,629** | **5** |

The five conditional Studio tests were the generic starter, official template, and representative
zero-code starter integration tests, the dashboard performance baseline, and physical hardware
dashboard validation. They were not executed by this ordinary test run.

The added regressions exercise heading discontinuities, small/large covariance scales, invalid
observations and commands, delayed camera ordering, curved odometry, wheel saturation, path lookup
complexity, spline tangents, trajectory consistency, and calibration with non-default configuration.
The direct-vision allocation regression recorded 816 bytes over 1,000 corrections, within the
existing 4,096-byte allowance; this is not a measured percentage speedup or a zero-allocation claim
for the full Redux pipeline. In-place matrix operations recorded zero allocated bytes over 10,000
operations.

The corrected runtime is bundled as `ARES-XRP-Starter-3.0.1.zip`; its SHA-256 matches the release
manifest and workflow, and its kinematics/drivetrain sources match the audited runtime after line
ending normalization. Studio release preflight and `git diff --check` passed. The existing pending
ARES 17.0.0 release identity was retained, and its library source-tree identity was refreshed.
