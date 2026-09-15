# Joystick conditioning and gamepad snapshot audit - pass 40

This pass reviews complete `InputMath.kt`, `FtcGamepadAdapter.kt`, `ControllerState.kt`,
the existing `InputMathTest.kt`, and four added test files. It traces scalar callers in
FTC season driving, FRC teleop and Studio desktop driving. Those callers remain outside
this pass's full-file review; the related AresGamepad DSL also remains a separate scope.

## Findings and fixes

- Scalar deadband and curve helpers accepted nonfinite/out-of-domain observations and
  configuration. They could return NaN, infinity, reversed or excessive commands. Both now
  neutralize invalid scalar axes outside finite [-1, 1]. Deadbands must be finite in
  [0, 1), and curve exponents positive and finite. Positive fractional exponents remain
  supported; they amplify small travel and were already accepted by existing callers.
- Deadband's arbitrary `1e-6` denominator guard suppressed full travel for otherwise valid
  deadbands close to one. Explicit domain validation replaces it, preserving full travel
  even for the representable value immediately below one.
- Zero exponents made arbitrarily small travel just above the radial deadband jump to
  full magnitude. They now neutralize. The exact deadband edge already returned zero in
  the old implementation because its sign was zero; the regression includes both that
  control case and the next representable value above it.
- Radial conditioning accepted finite coordinates outside the declared stick domain and
  could turn corrupted observations into saturated output. A bad coordinate now neutralizes
  that entire stick. Valid square-stick corners still desaturate radially, preserving
  direction to floating-point precision. Subnormal valid vectors remain supported.
- The Pair-returning convenience method allocated its result and boxed components, despite
  its zero-allocation wording. The existing API remains available with accurate documentation.
  An additive `processJoystickVectorInto` API writes into a caller-owned reusable array.
  A private inline result-delivery helper shares the law without allocating callbacks or
  intermediate arrays. Only indices 0/1 are written; undersized arrays fail before writes.
  Exponents one and two use direct identity/multiplication instead of general `pow`.
- FTC polling created two intermediate vector results, and invalid trigger observations
  passed through unchanged. One reusable two-entry buffer now serves both sticks; left
  components are captured before processing the right stick. Invalid triggers neutralize
  individually. Each SDK field is read once, Y is inverted once, all buttons retain their
  mapping, and the returned ControllerState remains a newly allocated immutable snapshot.

ControllerState's default values, field ownership and copy/value semantics were inspected.
Reusing a mutable published snapshot would violate Redux ownership, so snapshot allocation
is retained deliberately. The adapter is a single-owner polling utility. It does not make
separate SDK field reads atomic against concurrent SDK updates, establish connection/age
evidence or replace enable and control-lease checks.

## Validation

The initial eight baseline methods reproduced six failures in 9s: four core methods and
both adapter methods. The exact deadband-edge control and valid fractional-curve method
passed. XML and log: `ARESLib-Kotlin/build/audit-pass40-before-{core,ftc-hardware}.xml` and
`ARESLib-Kotlin/build/audit-pass40-before.log`.

A probe against the unchanged previous candidate `17.0.3-rc.f252cc83428a` confirmed that
zero exponent at `nextUp(0.05)` returned `(1.0, 0.0)`. Initial probe setup selected a sources
JAR instead of the runtime stdlib, causing a JShell compiler exception and a missing-Pair
reflection failure. The corrected explicit runtime classpath passed the assertion.
Evidence: `ARESLib-Kotlin/build/audit-pass40-zero-exponent-probe-fixed.log`; earlier failed
probe logs are retained. These were probe setup failures, not failing production builds.

The first focused run passed in 9s. After reducing adapter scratch storage to one buffer,
the final focused run passed all 25 methods in 5s. There are 17 added methods: ten core
boundary/oracle methods, one core allocation method, five adapter contract methods and one
adapter allocation method. Existing three-method InputMath tests and five-method zero-GC
regressions also passed. Logs: `ARESLib-Kotlin/build/audit-pass40-focused.log` and
`ARESLib-Kotlin/build/audit-pass40-focused-final.log`.

The independent radial oracle checks 5,000 deterministic input/configuration combinations
against magnitude, direction and orientation invariants; the scalar sweep checks 12,012
points for oddness, boundedness and monotonicity. Dedicated cases cover invalid domains,
subnormal vectors, square corners, fractional powers, buffer bounds, trigger isolation,
all button/axis mapping and retained-snapshot independence.

Both allocation tests executed without skips. Scalar/buffer processing allocated zero
bytes in two consecutive warmed-up 10,000-update windows. Adapter polling allocated
800,000 bytes per 10,000 polls, exactly matching a volatile-escaped standalone snapshot
baseline in two consecutive windows. Thus no additional intermediate allocation was
measured on this JVM; the test does not assume a portable fixed object layout or prove
zero allocation on every runtime. Physical loop duration and jitter remain unmeasured.

Source commit `490c3e97` binds library tree `0d599b7bafbba3ab6c8fd1a887c4790f42352075`
to local candidate `17.0.3-rc.0d599b7bafbb`. The planned branch final version remains 17.0.3,
already bumped from local main's 17.0.2. The core API snapshot adds only the new buffer
method and its default-argument bridge; prior signatures are unchanged.

The full library test/API/coverage/local-publication gate passed in 1m 33s:
1,157 tests passed, including 794 core and 120 FTC hardware tests, with no failures or
skips. Unchanged project-schema/telemetry-schema suites were up-to-date; changed and
dependent suites executed. InputMath covered 22/23 executable lines and 54/54 branches;
FtcGamepadAdapter covered 37/37 lines and 8/8 branches. This is execution coverage of the
current source, not proof of every floating-point combination or SDK scheduling behavior.
Evidence: `ARESLib-Kotlin/build/audit-pass40-library.log` and the explicit test manifest,
XML and Kover snapshots in `ARESLib-Kotlin/build/audit-pass40-verified-evidence`.

FTC (109 tests), FRC (134), FTC starter (14) and FRC starter (34) passed against that exact
candidate. Generated-project verification ran, and both FTC debug APKs built. Only invoked
debug/simulator test variants are counted; historical release-variant XML is excluded.
Logs: `ARESLib-Kotlin/build/audit-pass40-{ftc,frc,ftc-starter,frc-starter}.log`.

The full Studio gate passed in 3m 34s: 1,777 ordinary methods passed, six existing opt-in
methods were skipped, and all 56 dashboard smoke methods plus the performance-baseline
method passed. Shared, gateway and app suites executed. Coverage verification, release
alignment and production Kotlin file-size checks passed. The dashboard fixture persisted
and restored all 12,000 frames; replay load was 19.6749 ms, scrub p95 21.7824 ms and rapid
seek burst 5.0242 ms. Those are desktop fixture measurements, not isolated joystick or
physical robot timing. Evidence: `ARESLib-Kotlin/build/audit-pass40-studio.log` and the
explicit Studio XML/metric snapshots in the verified-evidence directory.

Repository policy passed, including exact source identity, unchanged starter archive
hashes, shared guidance and links in 201 current documents
(`ARESLib-Kotlin/build/audit-pass40-policy.log`). Inventory: 2,491 tracked files, 202 fully
reviewed, 63 partially reviewed and 2,226 pending, with no stale or orphaned records.
Full suite execution and file-level review are recorded independently.

## Remaining work

Calibrated interpolation and remaining kinematics/estimation are next. The AresGamepad DSL,
controller connection ownership, age/lease integration and other adapter implementations
still require their own full reviews. Existing Studio policy/lifecycle/retention scopes,
intermittent validation concerns, opt-in tests and physical hardware validation remain open.
No push, merge, remote publication or device operation occurred. The full audit goal stays active.
