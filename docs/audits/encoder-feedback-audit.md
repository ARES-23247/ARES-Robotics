# Encoder feedback audit — pass 82

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

Full module and candidate validation, source identity and coverage reconciliation are pending.
