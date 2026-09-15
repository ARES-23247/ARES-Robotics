# FRC starter capability construction and stop audit

Pass 269, 2026-09-14, continuing the [marker and selection audit](frc-starter-marker-selection-audit.md).
This pass covers the generated capability boundary, field transforms, partial task construction,
and stop sequencing in [StarterFrcAutonomousRuntime.kt](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/StarterFrcAutonomousRuntime.kt).
The containing file remains partially reviewed for the ownership and match-lifecycle gaps below.

## Drive and coordinate fixes

Generated drive input previously bounded each translation axis independently, allowing a diagonal
command to exceed the configured maximum translation speed by a factor of sqrt(2). It now bounds
the vector magnitude while preserving direction. A nonfinite axis rejects the entire frame and
clears previous drive intent; inactive or unpermitted input also commands neutral. Autonomous drive
construction now checks the same drive permit before constructing tasks.

The capability retains one JoystickDriveIntent and refreshes its timestamp for each synchronous
dispatch, removing that action allocation from the input callback. The reducer still produces
immutable state: tests retain an earlier state and verify subsequent commands do not change it.
Generated action bindings are initialized lazily once instead of being reconstructed for every
action key. Bytecode confirms the input callback contains no object-construction instruction;
this is not an allocation or deadline measurement of the entire robot loop.

Pose mirroring previously used fixed field dimensions and mirrored symmetry. It now uses the
selected FRC field's resolved dimensions and explicit alliance symmetry. Raw pose values and field
dimensions are validated before Rotation2d normalization or dimension defaults can conceal invalid
values. Zero dimensions retain the documented league defaults. Configuring autonomous captures one
immutable field configuration, so later global field changes cannot give start poses and drive
targets different transforms within the same construction context.

The existing opposite-alliance test now supplies its historical field dimensions explicitly.
New tests cover a 20 by 10 meter fixture, both symmetry types, inverse transforms, opt-out, default
dimensions, invalid raw axes/headings/fields, and replacement versus retained field configuration.
Canonical field drive commands remain canonical on either alliance: the Studio bridge already maps
that contract through the controller profile, so adding another alliance flip was not justified.

## Construction and cancellation fixes

If a later generated action was missing, an earlier action's timeout or callback metadata could
remain registered. An invalid marker progress could similarly abandon its already-created child.
The builder now tracks the tasks it acquires, rejects repeated identities and already-started
actions, and releases acquired owners if construction fails. Marker ownership prevents double
release of its private child. Rejection does not call hardware end hooks on never-started tasks.
Cleanup attempts all acquired owners, preserves the factory exception, and suppresses distinct
cleanup failures without adding the same exception twice.

[StarterResourceCleanup.kt](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/StarterResourceCleanup.kt)
now includes a helper that clears one task's timeout and callback registries even when its custom
release hook throws before delegating. Marker teardown uses that helper for private children.
Tests cover throwing release hooks, both untriggered and active marker metadata cleanup, terminal
status, exactly-once child release, and absence of hardware end calls for metadata-only cleanup.
A successful constructed deadline group also verifies marker/during start, delayed arrival start,
and cancellation of the unfinished companion when the drive settles.

Stop previously left nonzero Redux drive intent when no active drive task supplied a neutral action.
If generated cancellation threw, it also skipped terminal bookkeeping and remaining neutralization.
Stop now clears local execution state first, then attempts generated cancellation, neutral drive
dispatch, hardware neutralization, and status publication. It retains the first exception, later
distinct failures, and direct interruption. Successful autonomous completion uses the same stop
path. Tests verify neutral Redux state and terminal bookkeeping after cancellation failure, and
that a later telemetry error cannot replace the original cancellation error.

## Evidence and open boundaries

The first baseline had **10 tests and 10 failures**, with only the action-factory test seam added.
A separate stop baseline had **3 tests and 3 failures**, with only the cancellation seam added.
Final `verifyAresProject test` passed **203 tests**, zero failures, errors, or skips, preserving all
181 prior test cases. The new fixture has 22 test methods. Local evidence, copied XML, candidate
hash verification, bytecode, and coverage inventory are in
`ARESLib-Kotlin/build/audit-pass269-verified-evidence/`. The shared library source tree and all 410
candidate repository files are unchanged. Guidance and current-document link checks pass.

Construction cleanup here covers tasks tracked by this builder and private children owned by
markers. An arbitrary built-in task group returned by a custom action factory can contain children
that the starter cannot inspect through the current public API. A failed construction before the
outer RoutineCompiler acquires the returned tree can therefore leave nested metadata unowned.
That shared-library ownership boundary remains open; this pass does not claim recursive cleanup
for arbitrary action-factory trees or validation of already-claimed but still-pending descendants.

Review of shared RoutineTaskOwnership found that generated routine suspension already walks running
leaf tasks. Missing pause overrides on a plain group alone therefore do not prove generated routine
preemption is broken. Actual generated preemption and throwing pause/resume callbacks still need
tests. Match timing, preflight/runtime exception handling, and preferred-engine policy also remain
open. These host tests establish no physical stop, competition loop timing, GUI, or HIL result.
Existing Studio alignment and archive/reference migration gates remain unresolved and unchanged.
