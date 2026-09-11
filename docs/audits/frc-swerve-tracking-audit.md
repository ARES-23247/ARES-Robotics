# FRC swerve tracking audit â€” pass 111

## Reproduced findings

The tracker reused a mutable `Vector2` for every queued force. Dyn4j 4.2.2's
`Force(Vector2)` retains that reference. Updating another body rewrote the first body's
pending 50 N command to 100 N; queuing two commands for one body produced 200 N instead
of the expected 150 N. A 20-command burst reproduced the same ownership error.

Nonfinite drive commands/feedback and overflowing effort calculations were also accepted.
The tracker now validates heading, commanded and measured velocities, and calculated effort
before queuing anything or waking the body. Existing pending effort is not rewritten.

The former `applyForce(Vector2)` and `applyTorque(double)` calls constructed wrappers on
every tick. The corrected allocation-boundary test measured 4,000,000 bytes inside tracker
updates over 100,000 warmed ticks before the fix, versus zero bytes after the fix.

Nine methods were tested against the original implementation: five failed (three force
ownership scenarios, invalid input, and update allocation). Initial and expanded XML,
including the final corrected measurement baseline, are retained separately under
`ARESLib-Kotlin/build/audit-pass111-before-evidence/`. The first allocation assertion covered
both tracking and engine accumulation; it was refined to measure tracking separately, then
rerun against the original source before restoring and validating the fix.

## Ownership and efficiency changes

Every pending force/torque object now owns its values. Its Dyn4j completion callback returns
it to a cache only after accumulation. A cache holds at most eight consumed objects of each
kind, has no body references, and holds no pending objects. A normal update/step loop reuses
one force and one torque. Bursts can allocate additional pending snapshots without rewriting
earlier commands; excess consumed snapshots are discarded instead of growing retained memory.

Manual force/torque queue clearing can discard pending objects normally. They are not marked
available early and cannot leak stale effort into another body. Later ticks can allocate a
replacement and resume reuse. No reflection or per-tick collection construction is added.
Zero-mass/inertia channels skip borrowing and retain Dyn4j's no-effort behavior.

Robot-relative rotation computes sine and cosine once each. Body linear velocity is read
once, and the redundant explicit wake-up is removed because Dyn4j's effort overloads already
wake movable bodies. Field-relative commands still bypass rotation, angular signs remain CCW,
and force/torque remain proportional to world-frame velocity error.

## Validation and limits

Tests cover multiple bodies, multiple pending commands, bursts larger than the cache,
consumption/reuse, manual queue clearing, field/robot frames, angular effort, gain rejection,
invalid command/feedback/overflow, zero mass, allocation, and actual world integration with
no command replay. The test accumulator invokes Dyn4j's real protected accumulation method;
the integration test uses a real world step.

The tracker and Dyn4j are single-threaded. The cache relies on the standard Dyn4j accumulation
callback contract, which was read in the resolved dependency's local source archive. Custom
Body subclasses that duplicate submitted objects or call completion callbacks outside that
contract are not supported. This is a chassis tracking approximation, not a calibrated tire,
traction or individual-module model.

Dyn4j itself still allocates queue iterators during accumulation; JIT optimization can vary
that total. The allocation assertion covers the tracker update boundary and does not certify
the entire engine or physical robot loop. No physical timing or usable simulator-window
result is claimed. Only FRC source/tests change; local library candidate
`17.0.3-rc.100852e472fb` remains unchanged.

## Final evidence

Full FRC validation passed 203 tests, including nine new tracking methods, with zero failures, errors or skips. The five required core zero-GC methods also passed. Generated-project/namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Before: Swerve update: 4000000 bytes; update plus Dyn4j accumulation: 10400896 bytes / 100000 ticks.

After: Swerve update: 0 bytes; update plus Dyn4j accumulation: 5107976 bytes / 100000 ticks.

Copied XML, successful logs, source hashes and verified SHA-256 evidence are recorded in `ARESLib-Kotlin/build/audit-pass111-verified-evidence/summary.json`. The corrected original-source baseline has nine tests and five failures.
