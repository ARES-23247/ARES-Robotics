# FRC startup ownership audit

Pass 105 reviews the real and simulated season hardware factory and its ownership handoff.

## Finding and change

The factory allocated the PDH, ten mechanism motors, drivetrain and cameras before returning a
`FrcSeasonHardware` value. A later constructor failure escaped without closing earlier completed
allocations. The composition root had not received the value and could not recover those local
references. Similarly, a failed dashboard-input constructor could leave an already-created simulator
unowned. This finding comes from source-level allocation/escape analysis; no native failure was injected
into the original factory, and no failure-before host count is claimed.

Both factory branches now use a startup ownership scope. Each completed resource is registered
immediately. If construction throws, owned resources receive close calls in reverse acquisition order;
cleanup errors are suppressed onto the original construction failure without skipping other owners.
Successful construction clears temporary ownership and returns the existing hardware aggregate.

Successfully created mechanism adapters take ownership of their native motors, and the swerve
adapter takes ownership of its drivetrain. The temporary scope replaces the raw owners only after
adapter construction returns, avoiding duplicate closes. Both cameras remain independently tracked
until commit so rollback attempts each camera close even if another close fails. The composite holds
no additional native resource. Simulation and dashboard input are tracked separately.

## Contracts and cost

The six mechanism adapter close methods delegate to the all-motor `closeTalons` loop. The swerve
adapter closes its drivetrain. These ownership edges were inspected for the handoff; their complete
runtime implementations were not re-audited. The caller's CAN bus is borrowed and not claimed by the
factory scope. Existing optional-PDH handling and hardware configuration/enable policies are unchanged.

Identity-based tracking distinguishes equal but separate owners and avoids duplicate registration.
The small list and ownership-transfer arrays exist during startup only; nothing is added to robot
periodic evaluation. No startup-time or allocation-rate performance claim is made.

## Validation and remaining scope

Six tests cover later construction failure, reverse close order, successful transfer to a caller,
adapter replacement without duplicate child close, an adapter constructor that throws before transfer,
cleanup exceptions with original-error preservation, identity semantics and repeated rollback.
Tests inject resources into the same ownership scope used by both factory branches.

The factory remains partial for native constructor failure injection and native integration. Resources
allocated inside a constructor that never returns still require that constructor's own cleanup;
this scope cannot recover inaccessible partial objects. Failures after the completed aggregate reaches
`ARESRobot.robotInit()` but before all owners are registered also remain a separate open handoff scope.
No physical CAN device construction, calibration, native leak measurement or HIL result is claimed.

This FRC season-only change reuses unchanged library candidate `17.0.3-rc.de2cb9c407a0` and is
validated with full FRC tests, generated-project checks and monorepo policy.

## Final evidence

Full FRC validation passed 171 tests, including all six methods in `FrcStartupResourcesTest`, with zero failures, errors or skips. Generated-project and namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Copied FRC JUnit XML and successful logs, with verified SHA-256 hashes, are recorded in `ARESLib-Kotlin/build/audit-pass105-verified-evidence/summary.json`. No native failure-before execution is claimed; the original missing ownership boundary was identified by source analysis.
