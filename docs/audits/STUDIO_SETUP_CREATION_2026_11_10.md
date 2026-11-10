# Studio setup and project creation checkpoint

Date: 2026-11-10. Baseline: `59a5e410`, branch `codex/ftc-sdk12-20261109`.
This is the requested setup/project-creation follow-up to the completed
[OTOS checkpoint](FTC_SDK12_OTOS_RELIABILITY_2026_11_10.md). The original dirty checkout
and its processes remain outside this isolated worktree's validation scope.

## Findings and changes

The baseline 1424 × 861 client capture showed no required location fields before scrolling.
Introductory teaching, example cards, and ownership explanations preceded those fields.
The first step now uses four compact project choices and places the location fields immediately
after them. Detailed ownership choices and beginner explanations remain available on demand.
Project choices expose selected radio-button semantics. Validation requests the affected location
block into view; it no longer jumps to the bottom past the fields. Errors and save progress sit
outside the scroll pane, with fallback status text while saving.

Controlled creation tests exposed a functional failure: the friendly name is labeled optional,
but an empty name was passed into canonical project identity, which rejects blank display names.
The creation request now defaults that name to the trimmed robot ID. Existing workspace config
semantics remain unchanged.

Repeated submit intents could start another creation while the first was suspended. A submission
mutex, saving-state intent guard, and completed-state guard now admit one operation, preserve its
inputs, and allow retry after failure. A failed staging hook still publishes no destination.

Switching from either example back to Create formerly retained the example's displayed template
identity even though the request selected a generic starter. The label now resolves the actual
league's generic starter again. A regression test reproduced this mismatch before correction.

## Evidence and limits

**41 focused tests passed without failures, errors, or skips**: 17 onboarding model, 3 presentation, 3 submission/identity, and 18 template-service cases. Submission tests use actual bundled XRP archive extraction and a controlled suspending history hook. Blank-name failure and stale template display were observed before correction. The initial timeout was diagnosed as the blank-name product failure; the final test asserts the published metadata. The unrelated full Studio suite and unchanged library/consumer suites were not repeated for this UI-only delta.

One isolated 1440 × 900 window (1424 × 861 client) completed the native journey: first-view location fields, empty Continue validation, native parent-folder chooser, field recovery, robot details with a blank optional name, offline review, visible progress/disabled navigation, and creation/opening of a generic FTC project in Robot Studio. Eight exact client captures were inspected. Metadata, GUI-owned authoring, clean initial local Git history, archive provenance, and HARDWARE_REVIEW_REQUIRED were verified. Native Git required per-command core.longpaths=true for this deliberately deep test folder; no global setting changed. The owned PID exited, its loopback port closed, and its runtime snapshot was removed, with no crash, watchdog, or database-close error observed. Other window sizes and the other native creation modes were not exercised in this UI pass; software tests cover both example mode switches, all template-service cases, and XRP creation/retry.

The same validated local dependency candidate, `20.0.2-rc.sdk12.otos.20261110.1`, is used for
tests and launch. ARESLib source tree remains `dc60c8818d75ff351d909535befa9bf8c4096ca9`;
no runtime library, starter archive, public release, deployment, or protected-main change is made.

The canonical desktop tester skill now covers first-view discoverability, validation recovery,
visible progress/error state, mode/template identity, disposable creation artifacts, and controlled
duplicate-submit/retry tests. The [evidence summary](STUDIO_SETUP_CREATION_2026_11_10_EVIDENCE.json)
records commands, source fingerprints, results, and captures; raw local evidence is in
`build/studio-setup-20261110/`. File-review status remains separate from passing validation in
the [review ledger](file-reviews.json). No repository-wide or physical-hardware claim is made.

Canonical guidance/link and archive/source policy checks, six guidance regression tests, the desktop skill validator, and the maintainability ratchet passed (1,116 production files, zero violations).
