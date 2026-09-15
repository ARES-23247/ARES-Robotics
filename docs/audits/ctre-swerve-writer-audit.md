# CTRE swerve writer validation and failure handling

Pass 52, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and changes

The writer rejected nonfinite scale by throwing before attempting safety, leaving a previously
active request untouched. It did not reject nonfinite velocity components at all; zero scale
could still produce NaN from infinity times zero. Failed native motion writes also had no local
brake attempt. Explicit X-brake was incorrectly blocked by an unused invalid scale.

Normal motion now requires finite X/Y/omega and scale, then clamps scale to [0, 1]. Invalid input
attempts the existing X-brake primitive before throwing. A failed motion write also attempts
brake and rethrows the original cause. Distinct cleanup failures are suppressed; the same error
object cannot trigger self-suppression and mask the original failure. Explicit X-lock/X-brake
uses only its brake request, independent of unused motion/scale data. Already-failed brake calls
are not immediately repeated inside the same call.

Mutable field-centric, robot-centric and brake requests remain reused. The documented contract
requires a single loop and synchronous consumption/snapshotting; retaining a request reference
does not preserve old values. No periodic geometry objects or closures were added. Invalid and
exception paths may allocate. The existing allocation test now records unsupported instrumentation
as a JUnit skip instead of silently passing without measuring anything.

Pinned Phoenix 26.1.1 source inspection also exposed two implicit defaults: FieldCentric uses
OperatorPerspective, and ApplyRobotSpeeds uses OpenLoopVoltage. ARES FRC input code already
mirrors both translational axes for RED before dispatch, and autonomous coordinates use the fixed
field frame. The writer now explicitly selects BlueAlliance for field requests and Velocity/Position
for both motion frames. This prevents an externally configured operator perspective from applying
a second rotation and prevents a frame switch from silently changing drive control mode. Current
tracked callers do not set a nonzero vendor operator perspective; that rotation defect was latent.

## Evidence

All four baseline regression methods failed in 7s. Copied XML/log are preserved under
`ARESLib-Kotlin/build/audit-pass52-before*`. The initial focused gate passed 14 methods: 11 new
boundary/numerical methods, two existing safety methods and one existing allocation method.
API checks and Kover passed. Initial writer coverage was 36/36 lines, 24/24 branches and 6/6 methods.
The two added frame/control-mode regressions both failed before correction, then the expanded
16-method focused gate passed in 9s with no failures or skips. Final writer coverage is 39/39 lines,
24/24 branches and 6/6 methods. Frame baseline and final XML/Kover are preserved separately under
`ARESLib-Kotlin/build/audit-pass52-frame-*evidence`. These tests inspect the actual vendor request
configuration; they do not execute vendor JNI kinematics with a rotated operator perspective.

Tests cover each nonfinite velocity member in both frames at zero/partial/full scale, all
nonfinite scales, previous active commands, explicit brake precedence, ordinary/fatal write
errors, distinct/shared cleanup failures, one brake attempt, public-constructor forwarding,
frame selection and reuse of request/speed identities. A 2,000-case seeded oracle compares all
three scaled components to exact BigDecimal multiplication rounded to Double. Both frames use
every scale case, including negative/excessive scales, signed zero, subnormal and extreme values.
This tests the writer's request arithmetic, not the vendor's downstream control computation.

After 50,000 warmup writes, two 10,000-write windows observed zero allocated bytes while varying
frame, scale and brake commands. The existing five-window allocation check also passed on the
supported host. However, the frame-baseline run also measured 400 bytes in the strict
allocation test; the cause is unresolved. The later unchanged allocation check passed, so zero
allocation is an observation from those windows, not a guarantee across JVM execution conditions.
The failed XML is retained and the assertion was not relaxed. Mockito verifies the public constructor's call forwarding without opening devices.
Native execution, controller timing and physical response are not inferred from mock or allocation
results. Full source review includes the writer, both added test files and both existing test files.

## Contracts and remaining scope

X-brake requests zero drive velocity with steering position control; it is not PWM-off for every
actuator. A returned request is not proof of physical stopping, and a failed brake leaves physical
state unverified. The writer does not own enable/arm, fresh feedback, vehicle limits or fault
recovery. Those remain the responsibility of the surrounding hardware/controller lifecycle.

FRCSwerveHardwareIO remains a separate partial review. Its close/resource ownership, post-close
behavior, fresh-feedback output gating and numerical vision/estimator entrypoints still need
dedicated regression tests and fixes where confirmed. This pass does not close those obligations.

## Validation checkpoint

Initial source `633a56a1` and candidate `17.0.3-rc.42d9ce3aea40` passed the library
(1,383 methods), consumer and policy gates before the additional frame findings. Source `8bd6206f`
supersedes that candidate with `17.0.3-rc.a45e33938c0e`, bound to library tree
`a45e33938c0efbf87ca17dc41720beb457505c85`. The final library gate passed in 22s: 1,385 tests, no failures/errors/skips, API checks, Kover
and isolated publication. FTC/FRC/FTC starter/FRC starter gates passed with 109/134/14/34 tests,
including generated-project verification and FTC assembly. Studio passed in 18s: ordinary suites
were UP-TO-DATE (1,779 executed-result tests and six existing opt-in skips); dashboard smoke
(56 tests) and performance baseline (one test) reran. Studio coverage, version alignment and file
size checks passed. Policy passed for 213 current documents, with 38 historical exclusions,
source-tree identity and deterministic archive checks. Final XML/hash manifests are preserved in
`ARESLib-Kotlin/build/audit-pass52-frame-verified-evidence`. Build tasks used cache/up-to-date
results where applicable; these counts are not a claim that every test executed afresh.
No push, merge, remote publication or physical device action has occurred.
