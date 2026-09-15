# Hardware evidence inventory and source binding

Pass 196, 2026-09-12. This pass follows the persistence gap left open in pass 195.
All production changes belong to Studio. The isolated ARES library candidate,
release identity and on-disk evidence document formats remain unchanged.

## Confirmed fixes

Configuration review and physical-validation requests now require the inventory
hash that was displayed when the checks were completed. The view model captures
that identity together with the submitted form values. The service compares it
with its inspected inventory and rejects mismatches before appending evidence.
The error directs the person to refresh and repeat the checks. Existing inventory,
review, simulation and checklist requirements still apply; supplying an identity
does not bypass them.

Previously, changing a descriptor after a form loaded could cause configuration
assertions about the old hardware to approve the new hardware. Physical assertions
could also attach to the new hardware if another configuration review for it had
already been recorded. Real view-model/service/filesystem tests reproduced both
flows. The fixed paths leave the evidence directory unchanged on rejection.

A configuration save also re-read and re-hashed each descriptor after inspection.
An edit during the end of inspection could therefore produce a record containing
the earlier inventory hash and later source hashes. The private inspection result
now carries its source fingerprints with its public snapshot; the review stores
those exact fingerprints. This removes a duplicate read/decode/hash pass and the
mixed-record defect. Source existence and project containment checks remain, with
the canonical project root resolved once per save.

A file can still change after it was inspected. A record for the already-reviewed
inventory is historical evidence for that inventory; it does not authorize later
hardware. The final inspection reports the newer mapping as stale and the view
model clears the obsolete checklist. This pass does not claim a filesystem-wide
transaction or rollback of a write after coroutine cancellation.

## Evidence and rejected candidate

The initial five-test baseline had four failures. Three were confirmed product
failures: stale configuration assertions, stale physical assertions and mixed
source hashes. The fourth was an invalid test fixture: the proposed hardware-free
subsystem failed schema validation before inventory inspection. Source inspection
confirmed that hardware and state fields are required, so that omission candidate
was rejected and the invalid fixture test removed. No existing test was removed.
The unchanged-inventory end-to-end success case passed before and after the fix.

Final coverage includes direct service rejection of blank/unrelated identities,
unchanged-inventory saves of both evidence types, stale forms across a descriptor
edit, and a deterministic edit injected at the simulation-verification boundary.
Those tests use real canonical descriptors, isolated temporary projects, owned
coroutine scopes and synthetic human-evidence text. Existing inventory, lifecycle
and template deployment-policy tests retain their assertions and now supply the
inventory identity their fixture reviewed.

Both focused runs passed all 53 tests: five persistence cases, 18 existing hardware
service cases, 12 lifecycle cases and 18 template-service cases. The final run
strengthened the direct-call rejection fixture: distinct reviewer content and an
assertion on the inventory-change error prevent a duplicate-file rejection from
masking a missing identity check. Full Studio validation of the final production
code passed with 1,878 tests: 1,872 successful executions, zero failures/errors and
six opt-in/environment skips (three generated-project integrations, native file
chooser, performance baseline and physical dashboard target). The final test-only
strengthening was then verified by the complete focused run. Repository policy
passed, including links in 357 current documents and 38 excluded historical records.
Evidence is under
`ARESLib-Kotlin/build/audit-pass196-verified-evidence/`.

## Scope and limitations

The hardware service was previously read in full; this pass rechecked inspection,
request construction, review serialization and the matching readback boundary.
The complete view model and both directly affected test classes were reviewed;
the template-service test file was also read completely and its full class was
included in focused validation. This does not grant whole-file coverage to the
template production service, schema implementation or document-store dependency.

The service remains partial for evidence-directory ownership, append-only write
races and ordering, clock/record edge cases, XRP address namespaces and CAN/bus
identity. Those are separate from the submitted-inventory and mixed-hash fixes.
Physical-save source ownership and cross-file inspection consistency also remain
part of that persistence follow-up. No release, visible Studio window, robot
runtime or physical hardware was exercised. Synthetic physical-validation records
in these tests establish no real-world hardware result.
