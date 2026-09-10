# Monorepo audit coverage

The active audit goal is to account for every tracked file, review its owned behavior, and
complete appropriate validation. The inventory started at 2,349 tracked files on
`e6640f5a`; additions are included after staging. No repository-wide coverage claim is made.

Run `python scripts/audit_inventory.py` from the monorepo. It regenerates the complete
file-level inventory at `.codex-validation/audit-inventory.json`, including product,
provisional file category, content fingerprint, review status, and validation evidence.
The tracked [review ledger](file-reviews.json) stores evidence; files absent from it
are pending, not implicitly covered by an old suite run. Deleted entries are reported as
orphaned. A changed or missing file loses completion credit automatically. Fingerprints
normalize UTF-8 text line endings, but preserve binary bytes.

Review and validation are independent. `partial` means only the recorded sections were
audited. `reviewed` means the entire file was inspected. `passed` means the recorded
checks passed for their stated scope, not that every branch executed. Completion requires
an unchanged file, full review, explicit scope, and passing applicable validation (or a
justified `not-applicable` record for non-executable content). A matching fingerprint does
not establish that dependencies or consumers are unchanged: rerun their affected checks
after integration changes. Keep the command, candidate/source identity, failures, skips,
and limitations in the linked report. The inventory is accounting, not a substitute for
Kover/line coverage or measured hardware tests.

Use unit, property, integration, and generated-project tests for executable behavior;
schema/semantic checks for configuration; link and content checks for documentation;
integrity and consumption checks for binary resources. Identify generated and upstream
files explicitly during review; do not manufacture unit tests for every file extension.
Hardware, platform-specific UI, and deployment evidence must be recorded separately.
Opt-in skips remain open until run or documented as requiring an unavailable environment.

Prior bounded findings and test counts are in the [robot audit report](../robot-loop-audit.md)
and [math audit report](../mathematics-audit.md). Historical edited runtime/test files are
seeded as partial review only; historical green suites do not close their remaining review.

## Ledger schema and its own fingerprint

Ledger schema version 1 requires a `files` object keyed by repository-relative paths. Every record
has a lowercase 64-character `sha256`, `review` (`pending`, `partial`, or `reviewed`), `validation`
(`pending`, `passed`, `failed`, or `not-applicable`), a string `scope`, and a list of string `evidence`
entries. Blank scope or evidence prevents completion credit. Wrong types, invalid statuses/hashes,
duplicate JSON keys and non-JSON constants reject the ledger rather than emitting success.
Evidence remains a reviewer claim: schema validation does not prove a referenced test ran or that
its scope was sufficient. Category labels are provisional routing hints, not review dispositions.

Ordinary file fingerprints bind content bytes, with UTF-8 text CRLF normalized to LF. Symbolic
links bind their link text, not target contents; a broken target does not erase the tracked link.
The inventory reports `fingerprintKind` to identify each representation. This workspace currently
contains no tracked symlinks or submodules; no cross-platform symlink checkout validation is claimed.

The ledger itself uses `ledger-json-v1`: SHA-256 over the UTF-8 prefix
`ARES audit ledger JSON v1\n` (with an actual newline), followed by sorted-key, compact ASCII JSON
of the complete parsed document, excluding only its own record's `sha256` field. Every other review,
validation, scope, evidence, file digest and metadata value remains bound. JSON formatting and
object-key ordering do not change this semantic fingerprint; array order and string content do.
This resolves the self-reference without ignoring the ledger or automatically granting it credit.

After reviewing ledger changes and creating or updating its explicit self-review record, refresh
only that record's hash:

```powershell
python scripts/audit_inventory.py --refresh-ledger-fingerprint
```

A placeholder self-hash must still be 64 lowercase hexadecimal characters. Refresh requires an
existing record for a tracked ledger and preserves every review/validation/evidence field. A partial
review stays partial. Later edits to any bound value make the self-record stale again. Alternate
`--records` paths receive the same treatment when tracked. Inventory output cannot overwrite its
ledger input; JSON writes use atomic replacement so an interrupted write does not expose partial JSON.
This is not a crash-durability or concurrent-editor transaction guarantee. Run against a stable checkout.

## Execution method for the active goal

The full-coverage objective remains unchanged. Prioritize live robot control, estimation,
hardware safety, loop timing, and confirmed cross-product contract disagreements, then finish
the remaining tooling, application, configuration, documentation and resource review.

- Review coherent batches of related files and resolve their connected findings together.
  Use the inventory to avoid repeating completed reviews unless changed code, a dependency
  change or contradictory evidence justifies reopening them.
- Reproduce confirmed defects with focused regression tests and independent reference cases.
  During implementation, run the affected tests and nearby contract tests. Do not expand a
  pass merely to increase a coverage percentage or add tests that repeat the implementation.
- Once a batch is stable, run its required affected-module checks and, for library changes,
  publish one uniquely identified local candidate and validate consumers in dependency order.
  Repeat broad validation only when a code change invalidates that evidence, a gate fails, or
  an unresolved integration concern requires it. Preserve required release and safety gates.
- Reuse valid evidence for unchanged inputs. Documentation-only and ledger-only edits need
  their own appropriate checks, not another complete robot/Studio build matrix.
- Keep accounting proportional: one concise report per coherent batch, with findings, actual
  checks, source identity and remaining limitations. Refresh the ledger at verified checkpoints;
  retain detailed logs locally without repeatedly reproducing them in reports or conversation.
- Revisit earlier conclusions when independent evidence contradicts them. A passing round trip
  does not establish agreement with a physical coordinate frame or another product's contract.

## Selecting files within a batch

Use the current inventory's `pending`, `partial`, and `stale` entries as the work queue. Follow each
record's linked reports for findings, source/candidate identity, validation commands and remaining
limits. Historical reports describe their recorded checkpoints, not necessarily today's remaining
work. The ledger and fresh content hashes replace a second, quickly outdated pass-by-pass status list.

Continue through all library modules, robot/starter products, Studio modules, generators,
build/release/CI tooling, configuration, documentation, and resources. Map Kover reports to source
files and identify uncovered behavior. A passing suite never closes an unreviewed file, and full
source review never substitutes for feasible execution or hardware evidence.

Open validation concerns include the timing-sensitive `TelemetryUpdateE2ETest` failure recorded
in pass 3, the first-parent PID readiness timeout in pass 19 (`ProjectBuildServiceTest`, cause
unproven after passing reruns), the pass 20 replay-scrub performance-baseline failure (105.8996 ms
against 100 ms; cause unproven), Studio opt-in tests, and physical loop/jitter/electrical validation.
Consult the associated reports before treating a later pass as a resolution of those concerns.
The goal remains active until every file has a defensible disposition and all feasible checks have
completed. Changes remain local; no push, merge, or release is part of this goal.
