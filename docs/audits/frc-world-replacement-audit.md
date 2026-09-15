# FRC world replacement audit - pass 133

Continued the simulator world-lifecycle review and inspected the remaining legacy
viewer preset. Both production files retain partial ledger status for their open scopes.

## Confirmed world replacement defect

`Dyn4jPhysicsWorld.buildWorld` removed all non-robot bodies and cleared grounded and
flying pieces before constructing replacement walls, obstacles and game elements.
If replacement construction threw, the caller retained an empty or partially rebuilt
world. `Dyn4jSimulation` catches field-update exceptions, so the failure could leave
the simulator running with missing collision geometry rather than its previous field.

Added regressions for a failure during wall creation (infinite width) and after
walls plus an obstacle were created (a later obstacle has invalid friction). Both
failed against the original code because the original world was already removed.
A successful repeated-replacement case passed before and after the fix.

Replacement geometry now builds in a temporary Dyn4j world first. After all builders
return successfully, bodies are detached from that world, the old non-robot contents
are removed, and the replacement is installed. The robot body, pose and velocity
remain intact. Failure during construction leaves active body identities/order,
grounded pieces and flying projectiles untouched. A successful replacement clears
old projectiles and updates the grounded-piece index without accumulating walls.

The tests cover normal builder exceptions, not arbitrary out-of-memory failure or
exceptions from custom listeners during the final commit. This class currently
does not install such custom listeners. The public raw configuration API is not
claimed to reject every invalid field; complete validation policy is a separate scope.
Temporary-world allocations occur on field replacement, not each unchanged frame.

## Viewer preset status

Read all of `marvin19_layout.json`. Several diagnostic fields and `Robot/Pose3d`
have no matching current publisher; the swerve and simulated component/game-piece
topics still exist. The file has no current in-repository consumer. Its intended
external viewer schema/import behavior remains unverified. Left it unchanged and
partial rather than replacing topics with incompatible value types or inventing an
import format. It is not counted as a working current dashboard.

## Validation

Before: three new test invocations, two failures. After: all three pass, along with
generated-project verification and all 302 FRC tests (zero failures/errors/skips).
Policy, documentation links and staged whitespace checks passed. Source hashes,
before/after XML and logs are retained under
`ARESLib-Kotlin/build/audit-pass133-verified-evidence/`.

No library source or dependency changed. No additional whole-loop allocation claim,
native GUI import, physical hardware, push, merge or release was performed.
