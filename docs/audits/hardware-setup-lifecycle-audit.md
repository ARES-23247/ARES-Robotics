# Hardware Setup operation and checklist lifecycle

Pass 195, 2026-09-12. This pass closes the asynchronous lifecycle gaps identified
in pass 194. Changes belong to Studio; the existing isolated ARES library
candidate is unchanged.

## Confirmed fixes

An older cancelled inspection could report cancellation as an inspection failure
and erase a newer inventory. Refreshing during a save also left `saving` true,
and the old save could restore its captured state after a newer operation.
Refresh and both save methods now share one operation runner. An identity guard
allows only the current operation to publish results, failures and completion.
The replacement identity is installed before cancelling the old job. The new job
is installed before it starts, including for immediately executing scopes.

Cancellation propagates as cancellation instead of a service error. Completion
clears busy flags even when an already-cancelled owner prevents the body from
starting. A non-cancellable file operation that fails after owner cancellation
cannot publish its late error. Blocking file IO still runs on the IO dispatcher;
cancelling its coroutine does not undo a write or guarantee that synchronous
filesystem work stops immediately.

Review and physical-evidence requests capture the submitted form values. Their
success and failure paths update the latest state, preserving names, checkboxes
and evidence text edited while a save runs. Atomic StateFlow updates prevent
independent field edits and operation results from replacing each other's state.
Save eligibility is checked when starting the operation, so repeated clicks while
busy cannot launch additional writes.

Checklists are tied to the inspected inventory. A changed or invalid inventory,
or an inspection failure, clears all review and physical checkboxes and the
physical evidence summary. Person names remain. An unchanged valid inventory
retains its draft. Losing a current review or passing simulation clears the
physical checklist and summary while retaining the configuration-review draft.
Checks entered while prerequisites are unavailable are cleared when readiness
returns; preserving a draft requires readiness before and after the refresh.
This prevents unchecked assumptions from silently becoming eligible again after
an inspection failure or readiness change.

## Evidence

Eight initial lifecycle tests ran against the previous implementation; seven
failed. The unchanged-valid-inventory case already passed. The final suite also
checks loss of review/simulation readiness, repeated/ineligible save clicks and
cancellation during both kinds of save. A further baseline test reproduced checks
entered while prerequisites were missing becoming approvals when readiness
returned; its final matrix covers inventory, review and simulation prerequisites.
The save-result test covers both save
methods and both success/failure outcomes. Assertions verify every cleared
checkbox, not just the conjunction used by eligibility. Controlled barriers put
real IO-dispatcher calls in the required order; each test owns and joins its
coroutines and releases its barriers during cleanup.

All 33 final focused tests passed: 12 lifecycle cases, 18 hardware service cases
and three formatting cases. The full Studio app suite passed with 1,873 tests:
1,867 successful executions, zero failures/errors and six opt-in/environment
skips (three generated-project integrations, native file chooser, performance
baseline and physical dashboard target). The full run also includes removal of
redundant nullable checks reported by the compiler in the focused run. Repository
policy passed, including links in 356 current documents and 38 excluded historical
records. Evidence is under
`ARESLib-Kotlin/build/audit-pass195-verified-evidence/`.

## Scope and limitations

The complete view-model/state source and new lifecycle test file were reviewed.
The workspace construction and screen form callbacks were inspected only at their
view-model boundary; this is not a complete review of those larger UI files.

The view model remains partial for the separate persistence contract: neither
save request currently includes the inventory hash shown to the person completing
the checks. The service re-inspects before writing, so a descriptor edit between
viewing and saving can bind old human assertions to newer hardware. A follow-up
persistence pass must bind the submitted approval to its inventory and validate
source consistency and evidence ownership. The lifecycle fix does not resolve
that gap or serialize/roll back old filesystem writes. Service append ordering,
concurrent writes and the other pass-194 service limitations remain open.

No hardware, robot runtime, release or visible Studio window was exercised. Test
human-evidence values are synthetic and do not establish physical validation.
