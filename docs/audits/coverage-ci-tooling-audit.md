# Coverage accounting and CI scope audit

Pass 57, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and corrections

Coverage completion used truthiness checks, accepting whitespace-only scope, blank evidence
entries, or incorrectly typed evidence. The ledger loader also ignored schema version and silently
accepted duplicate JSON keys. The inventory now validates record types, statuses, digest syntax,
repository-relative paths and JSON structure. Blank scope/evidence cannot earn completion credit;
malformed structure rejects the ledger. A generator of tracked paths was consumed once for
inventory and again for orphan detection, incorrectly reporting every record orphaned. Paths are
now materialized once and reused.

The ledger lacked a workable self-fingerprint convention. Its own record now uses an explicit
`ledger-json-v1` representation: canonical JSON of all semantic values except that record's hash
field, with a domain prefix. Other hashes, metadata and review claims remain bound. Formatting is
ignored for this one representation. Ordinary source and binary fingerprinting retain their former
rules. Symlinks bind link text rather than reading targets. The optional refresh command updates
only an existing self-record's hash and cannot invent a review or promote a partial record.
Alternate tracked ledger paths and normalized relative CLI paths are covered.

Inventory output could overwrite its own input when both CLI paths matched. That collision is
rejected before writing. JSON writes now use an owned temporary file and atomic replacement;
injected replacement failure preserves the prior report and removes the temporary file.
This does not establish crash durability or a concurrent-editor transaction guarantee.

The CI classifier converted backslashes to directory separators. Git paths already use forward
slashes; a literal POSIX root filename such as `docs\\build-input.kt` must not become a policy-only
documentation path. The classifier preserves literal names. SHA syntax now accepts exactly 40
or 64 hexadecimal characters rather than every intermediate length.

Root `.gitattributes` was treated as policy-only even though attributes can affect checked-out bytes
and archive contents. It now selects the full matrix. The current repository has no root attributes
file; the regression covers introduction of one. The underlying effects are documented in the
[Git attributes reference](https://git-scm.com/docs/gitattributes). Existing product dependencies,
intentional policy-only paths, rename handling and manual/full-run behavior are preserved.

The audit README's historical status list stopped at pass 45 and still called subsequently reviewed
areas pending. It now directs readers to current per-file hashes and linked reports, while retaining
unresolved intermittent-test and hardware concerns. The ledger schema, self-hash and refresh
procedure are documented instead of maintaining a second status list.

## Verification

Seven regression methods produced 16 failed assertions/subtests against copied, immutable pre-pass
scripts. Those baseline copies and logs are retained in `ARESLib-Kotlin/build/audit-pass57-baseline`
and `audit-pass57-baseline-authorized.log`. Earlier temporary-file test attempts hit local filesystem
permission errors; they are retained separately and are not counted as reproduced code defects.
The authorized rerun established the actual baseline and final results.

The full root script suite passes 60 methods with no failures/errors/skips, including 16 added
methods. Tests cover blank/type-invalid evidence, generator inputs, canonical self-hash sensitivity,
formatting independence, malformed schemas, path validation, target-free symlink hashing through
an instrumented filesystem boundary, atomic failure cleanup, alternate-ledger real-Git CLI behavior,
refresh without review escalation, overwrite rejection, exact object-ID lengths, literal backslashes,
attributes selection and result-gate exit codes. Existing tests retain real cross-product Git renames,
both review events, 4,000-plus changed files, dependency unions and failed/cancelled dependencies.

Actionlint 1.7.12 passed all workflows, with its optional shellcheck/pyflakes integrations disabled
as in the repository's documented command. A local parsed-YAML check confirmed all 15 reusable
workflow outputs forward their matching classifier values. The five review workflows' result jobs
use always(), depend on every other job, and invoke the result checker. The candidate job checks
full-or-library scope, so shared-input full runs still build the library candidate when `lib` is false.
Structure evidence is retained in `audit-pass57-workflow-structure.json`. This checks configuration
contracts; it does not execute GitHub's scheduler or prove every step of those five workflows.

Full-file review covers the three Python tools, their three existing test files, three new test
files, the reusable ci-scopes workflow, CI-scope documentation, the audit README and the review
ledger's configuration/accounting structure. The five calling workflows were inspected for these
integration boundaries only and retain their previous ledger status. Historical evidence claims
were not all re-executed; validating a record's schema does not authenticate its test history.

## Limits and checkpoint

Inventory reports accounting, not executable line coverage or proof that evidence is sufficient.
It must run against a stable checkout, and source hashes do not establish unchanged dependencies.
No tracked symlinks/submodules exist in this workspace; no native cross-platform symlink checkout
test is claimed. Hosted rulesets, scheduler behavior, CodeQL services, signing, deployment and
physical hardware were not exercised or changed. GitHub's distinction between skipped workflow
runs and skipped jobs remains documented in its
[required-check guidance](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks).

This pass changes only root tooling, tests and documentation. ARESLib and consumer source trees
remain unchanged from pass 56; its candidate `17.0.3-rc.0303879d9315` and recorded platform results
remain the last runtime validation. No new library candidate or platform-suite rerun is claimed.
Source commit `3dc358e6` passed source-policy verification, including source-tree identity,
archive hashes, shared guidance and links in 218 current documents (38 historical exclusions).
Actual committed-diff previews from `d5a961c9` select the full matrix in both pull-request and
merge-group modes: `lib=false` identifies unchanged library source, while all other scopes are
true and the full-or-library candidate condition still selects candidate validation.

The ledger now includes its own reviewed accounting structure with a stable canonical self-hash.
It accounts for 2,581 tracked files: 349 fully reviewed, 73 partial and 2,159 pending, with no stale
fingerprints or orphaned records at this checkpoint. The tracked ledger is not ignored or counted
without evidence. Logs and hashes are retained in `ARESLib-Kotlin/build/audit-pass57-verified-evidence`.
The full audit goal remains active; all changes are local and all owned commands are terminal.
