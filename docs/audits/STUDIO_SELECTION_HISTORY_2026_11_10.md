# Setup selection and project-history follow-up

Date: 2026-11-10. Baseline: `18d5c1cbf`, branch `codex/ftc-sdk12-20261109`.
This continues the [setup/creation checkpoint](STUDIO_SETUP_CREATION_2026_11_10.md) in the
same isolated worktree. It reviews desktop orchestration and two UI areas; the original dirty
checkout remains excluded. It does not reopen the historical all-files audit.

## Confirmed findings and changes

Five controlled regression tests failed against the baseline: a delayed project reader overwrote
a newer creation-mode selection; rapid path edits caused redundant inspections; a history load
selected during a busy operation retained the previous project's result; repeated save intents
admitted multiple writes before dispatch; and a newer project selection could inherit an old
inspection failure instead of loading. The baseline failures are preserved with their XML.

Setup detection now waits for 200 ms of settled input, performs disk inspection away from the
UI dispatcher, and fences publication by selection generation, path, mode, step, and save state.
Changing selection cancels obsolete work. The late-completion test joins a deliberately delayed
reader before its final assertions. Continue shows a checking state while inspection is pending.
Unreadable or unsupported canonical metadata stays on the project step and cannot be bypassed
by Continue. Recognized legacy FTC/FRC/XRP layouts retain their manual-identity path.

Creation now validates the schema's ID/season/name constraints on the robot-details step before
extracting an archive. Accepted surrounding identity whitespace is trimmed consistently in the
creation request. Optional blank names retain the previous checkpoint's robot-ID fallback.
Build-tool results carry their checked league, so Java readiness cannot appear as Python readiness;
a generation guard rejects superseded checks and failure/cancellation clears their busy state.

Project History now reserves an action before dispatch. Admitted writes finish against their
original project. A new selection hides old plans and notices and loads the latest queued project
afterwards. Cancellation releases busy state. Local load no longer discovers GitHub destinations;
failed explicitly requested discovery retains the valid local history plan and reports the
optional online failure. Confirmation tokens, review requirements, repository permissions, and
physical deployment gates are preserved.

The history screen keeps status and operation feedback outside its scroll pane, exposes a scrollbar,
distinguishes uninitialized/first-version/saved/changed states, and brings recent versions higher
in a clean project's first view. Author and commit-message fields appear when relevant; drafts
reset when changing projects. Setup keeps field-specific creation errors visible.
Native XRP creation also exposed Kotlin-only ownership labels in Robot Studio and Gradle-only
folder guidance in Open Existing. Both now use language/platform-neutral repository wording.

## Validation and limits

**104 focused tests passed with zero failures, errors, or skips**. These cover onboarding model/presentation/detection/submission, actual bundled XRP extraction, project history admission/selection/cancellation/offline discovery, actual local Git history/archive/recovery services, and Robot Studio presentation. Tests use mocked GitHub/transport boundaries and do not authenticate or upload to a live account. The five baseline failures are now passing regressions. The final run followed the observed platform-neutral copy corrections.

One intermediate 97-test run had a timeout before the controlled project reader started.
The diagnostic-only rerun passed. The fixture had allowed constructor-triggered verification to
overlap re-stubbing of the same Mockito mock; it now drains that initialization first and joins
the delayed inspection before asserting its effects. The failed run and diagnostic are retained;
the timeout is not represented as an additional confirmed production defect.
An offline-discovery test initially used a Mockito checked-exception stub that was rejected before
the scenario ran; the stub was corrected to throw from its answer, and that observation is retained.

The three-directory edit fixture performed **two inspections before and one after** the fix.
The budget is one inspection for the final settled selection, with an intentional 200 ms debounce.
This is request-count evidence, not a disk-latency, frame-time, or controller-performance benchmark.

Three isolated native launch/capture/graceful-close cycles passed, with interactions primarily at 1280 × 800 (1264 × 761 client). Setup checks covered first-view location fields, native parent selection, expanded ownership choices, code-first XRP creation, invalid season/ID/name feedback and recovery, optional Python verification, and visible creation progress. Canonical identity, CODE_FIRST authoring, clean initial Git history and HARDWARE_REVIEW_REQUIRED were verified. Project History showed clean/recent versions, an unsaved documentation file, a missing-author error pinned while scrolled, and a successful reviewed local save using a fictional audit identity. Its second commit and clean final status were checked. Relaunch verified the corrected Robot Studio label, rejection of the parent folder, actual canonical XRP recognition/auto-fill, cancellation without project writes, and the final repository-root guidance. Exact owned client captures were inspected. All owned PIDs exited, loopback ports closed, snapshots were removed, and no crash/watchdog/database-close error was observed. No physical deployment, robot actuation, or live GitHub backup was attempted.

The first post-exit TCP-table query reported the final test port still listening after its owned
PID had exited. A direct follow-up found no listener, and the settled summary confirmed all three
ports closed. The initial query is retained alongside the final cleanup evidence.

Dependencies remain the validated `20.0.2-rc.sdk12.otos.20261110.1` local candidate and library
tree `dc60c8818d75ff351d909535befa9bf8c4096ca9`. No ARESLib source, starter archive, release pin,
or public artifact is changed; their existing SDK 12/OTOS evidence remains in its prior checkpoint.
Unrelated full Studio suites, physical hardware, and live GitHub backup are outside this pass.

The canonical desktop testing skill now covers delayed selections, settled fixture initialization,
joining completion, project switches during saves, and offline history. The
[evidence summary](STUDIO_SELECTION_HISTORY_2026_11_10_EVIDENCE.json) records identities, commands,
tests, captures, and limits. Raw local evidence is in `build/studio-followup-20261110/`;
the [file ledger](file-reviews.json) distinguishes scoped review from validation and preserves
earlier canonical fields/history and unrelated records.

Canonical guidance/link/archive/source policy, six guidance regression tests, the desktop skill validator, and the maintainability ratchet passed: 1,117 production files, zero violations.
