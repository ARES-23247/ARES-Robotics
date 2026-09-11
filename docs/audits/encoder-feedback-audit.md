# Encoder feedback audit â€” pass 82

This pass traces FTC encoder observations through cached IO, software/hub velocity control and
empirical calibration. It continues the input-validity gap recorded in pass 81. All work is local;
the tests use SDK doubles and do not establish physical hardware deadlines or calibration accuracy.

## Confirmed defects and changes

`EstimateMotorIO` previously retained position and velocity after a read exception, exposed an
unobserved cache as zero, and had no position age limit. It could resume polling after close.
The cache now returns NaN for missing, failed, more-than-100-ms-old, future-dated or closed
observations. Getters use cached values and the robot clock without additional device reads.
Close is terminal for polling and invalidates all observations.

Velocity now requires two position observations at distinct timestamps. A read failure, long
gap or rewind starts a new derivative baseline instead of reporting average motion across an
unobserved interval or manufacturing zero speed. Duplicate timestamps preserve the previous
derivative baseline. Position can be valid while the second velocity observation is still pending.

`MecanumDriveFeedforward` previously substituted zero for nonfinite software feedback and could
continue feedforward when the encoder conversion was invalid. Software PID now requires finite
wheel observations and finite positive conversion; hub velocity mode requires available wheel
observations. Invalid required feedback neutralizes all four calculated outputs and resets PID,
slew and acceleration history. Feedforward-only mode retains its lack of encoder dependency.
The formula documentation now matches the implemented kV/kA/kS sum and single voltage compensation.

Encoder-dependent linear/track-width calibration stops on invalid wheel position. Publication also
rejects nonfinite encoder data before the next output update, using the sample validation added
in pass 81. Pinpoint/vision routines retain their own observation dependencies.

## Evidence and remaining scope

Thirteen distinct regression methods failed before their corresponding fixes, including the real
four-wheel facade, calibration stop, cache validity/lifecycle, controller validity and first-sample
velocity. Preserved XML/log evidence is under `ARESLib-Kotlin/build/audit-pass82-*-before.*`.
Additional cases verify positive feedback unit conversion, feedforward-only behavior, publication
before the next output update and existing duplicate-time/replay behavior.

The cached wrapper does not itself command physical motors. The motor cluster's close/neutral
ownership and encoder counter rollover/reset behavior remain explicit follow-up scopes. The
feedforward class also retains pending review of other configuration/invalid-command/reset paths;
this pass does not claim that feedback validation completes its whole-file audit. FTC flywheel
cached reads and diagnostic clearing from pass 81 remain pending.

## Validation

Before freezing, all 239 FTC hardware tests and the API check passed. The selected four affected
classes account for 64 passing methods: 40 calibration, 14 encoder feedback, two encoder timing
and eight drive-facade tests. This selected evidence comes from the full module run after the
final test edits, and is copied under `ARESLib-Kotlin/build/audit-pass82-focused-evidence/`.

Source `7ad591fa3f291140c8aef93b0045c66f164da116`; library tree `6d4b98d3da30ff859c53eda736dd3b0faecab47a`.
Candidate `17.0.3-rc.6d4b98d3da30` was published only to the local validation repository.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 1908 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project
verification, FTC assembly, Studio Kover/version/file-size checks and monorepo policy passed.
Gradle reused unchanged valid outputs. The six Studio skips are the three conditional fresh-template
checks, native file chooser, physical dashboard and optional performance baseline. No additional
standalone dashboard benchmark or usable-window check is claimed.

Copied XML, manifests, log hashes and candidate BOM identity are under
`ARESLib-Kotlin/build/audit-pass82-verified-evidence/summary.json`. The whole-monorepo goal remains
active; passing a suite does not account for unread source files.
