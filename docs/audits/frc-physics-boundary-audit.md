# FRC physics boundary audit â€” pass 110

## Findings and fixes

Four new regressions failed against the original physics-world and field-builder code:

- Pose reset retained queued force/torque. A 1000 N queued command moved the chassis from
  x=6 to x=6.007852564102564 during the next 20 ms step after reset. Reset now clears both
  current and accumulated effort, as well as velocity, and wakes the robot body.
- Nonfinite reset coordinates were accepted. All three pose components are now validated
  before changing the transform or clearing effort.
- Invalid time was delegated to Dyn4j without a finite/nonnegative boundary. The wrapper
  now rejects invalid time before integration. Zero time returns without consuming effort.
- Invalid wall dimensions could fail after two walls had already been installed. Width
  and height are now validated together before any bodies are added.

A fifth regression at the top-level simulation entry point also failed: invalid durations
were not consistently rejected there (negative durations returned normally). The entry now
checks finite 0..50 second time before changing scratch actions, reading live configuration,
queuing tracking effort or advancing any model. The upper bound matches the mechanism
integration budget introduced in pass 109. Zero remains a no-advance update.

Initial four-failure XML and the later seven-method entry-boundary run with one failure are
preserved separately under `ARESLib-Kotlin/build/audit-pass110-before-evidence/`.
Compilation failures are not part of the evidence.

## Scope, efficiency and limits

Seven new methods exercise reset effort, atomic invalid pose, invalid/zero time, partial wall
construction, preservation of queued effort on zero time, static wall sizes/ownership, and
simulation entry rejection followed by a valid update. The two existing
`FrcFieldGeometryContractTest` methods cover bumper geometry and canonical wall placement;
both were read and included in full FRC validation. Tests use real Dyn4j bodies/worlds;
the top-level simulation fixtures close their native/NT resources.

Reviewed the complete small wall builder: centers, rectangle dimensions, static mass,
existing-body preservation and invalid-input behavior. Physics-world setup/rebuild/reset
code was read, but the world remains partial: transactional rebuild when a loader fails,
field-document fallback, extreme geometry and full world lifetime still need review.
The top-level simulation remains partial beyond its timestep entry boundary.

Zero-time integration now avoids unnecessary physics work and preserves queued effort.
No broad throughput improvement or physical loop deadline is claimed. Separately inspected
swerve tracking and Dyn4j's force overloads: the vector/double overloads construct wrapper
objects, and force vectors are retained by reference. This needs ownership-aware review;
no unsafe wrapper reuse or whole-file completion claim was made in this pass.

Only FRC source/tests change. Library tree `100852e472fbeeba64fdf799665f51b4687f7f1b` and
isolated candidate `17.0.3-rc.100852e472fb` remain unchanged. No physical HIL or simulator-window
validation is claimed.

## Final evidence

Full FRC validation passed 194 tests, including seven new boundary methods and two existing geometry-contract methods, with zero failures, errors or skips. Generated-project/namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Copied JUnit XML, successful logs and verified SHA-256 hashes are recorded in `ARESLib-Kotlin/build/audit-pass110-verified-evidence/summary.json`. The first before-run has four failures; the later entry-boundary run has one additional failure.
