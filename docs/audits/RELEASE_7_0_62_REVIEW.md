# Studio 7.0.62 and ARESLib 19.1.2 release review

## Scope and source

Review the submitted changes through `c1d07aeba` against protected main
`8463a65375ce54bbe56987340f71931c6570fab1`. Integrate on
`codex/reviewed-runtime-audit-release` in an isolated worktree; preserve the submitting
agent's branch, checkout, and processes. This is a release diff and integration review,
not a renewed all-files audit. The user's request authorizes protected merge and publication.

## Corrections and accepted behavior

- Reproduced three audit regressions with tests that failed on the submission: an unlimited
  wheel-speed limit zeroed valid commands; voltage control applied adapter power scaling twice;
  translation division silently replaced valid small-scalar results with zero. Restore the
  existing contracts. Tests exercise public wheel normalization and the real REV motor adapter
  with simulated hardware, including duty saturation, negative voltage, and invalid supply.
- Retain the direct pose-coordinate calculations, with an independent known 90-degree field
  transform and the existing allocation regression checks. No target-controller speedup is claimed.
- Preserve rejected-vision target unavailability and finite-position filtering; test that rejected
  batches clear current availability while preserving owned valid history and its timestamp.
- Keep NT4 silence recovery and verify timeout, reconnection, and explicit shutdown against a real
  local WebSocket server. Keep bounded import observations and concurrent diagnostic rate limiting.
- Fix replay gamepad source exclusivity introduced by the audit. Fix the pre-existing console
  snapshot cache problem, which the submitted size-only cache key did not solve. Composition tests
  exercise subscription ownership and replacement of same-sized console/replay buffers.
- Keep cloud query escaping consistent with the [Google Drive query syntax](https://developers.google.com/workspace/drive/api/guides/search-files).
  Remove the redundant bind-and-close port probe: the installed Ktor CIO engine's `start` awaits
  socket startup. Own the engine's failure handler so a recoverable bind failure cannot escape to
  the process crash handler. The collision test exercises startup, cleanup, and retry on the same port.
- Preserve callback startup error recovery and prevent an old picker startup failure from replacing
  a newer authentication generation's state. Instance-lock I/O errors remain visible; only an actual
  competing lock reports another running instance. Lock tests use isolated temporary directories.
- Keep field-editor cancellation propagation. Defer its proposed CAS rewrite because it performs
  undo-history writes inside a retryable update lambda. Keep the established single-owner edit model.
  Make playback's update lambda free of the completion side effect and honor a concurrent pause.
- Preserve missing-battery telemetry display, modal-overlay drive disarming, chooser entry resilience,
  cloud deletion details, angular-velocity unit inference, and bounded UI fallback changes.
- Restore unsupported changes to match fallback order, externally updated lesson reflection state,
  silent workspace-error swallowing, save-through-symlink behavior, and Git path normalization.
  Literal backslashes in POSIX Git filenames must not be reinterpreted as separators.

## Evidence and release identity

Evidence is retained under `build/release-review/` in the isolated review worktree. All local
consumers use candidate `19.1.2-rc.review778ca715b.1` from that worktree's absolute
`ARESLib-Kotlin/build/release-repository` URI. Final source identity is recorded in
`release/ares-source-tree.txt`; published library and archive versions are not reused.

- Full ARESLib `test apiCheck publishReleaseValidation`: 3,085 tests, no failures or skips.
  The pre-fix failures and XML results are saved under `library-regressions-before.log` and `before/`.
- Tooling `python -m unittest discover -s scripts/tests`: 107 tests passed.
- Studio, robot consumers, rendered desktop, policy, and package validation: pending completion.

Studio advances to 7.0.62; ARESLib to 19.1.2. Rebuilt archives are FTC/FRC starters 19.1.3,
XRP starter 3.0.62, Lightbot 3.0.64, and BioBuzz 1.1.4. Canonical manifests, embedded archives,
workflow URLs, and hashes are aligned. Required CI must pass before protected merge; promotion
must verify attestation and exact complete main-tree equality before publishing candidate bytes.

## Audit record disposition and remaining limits

The submission changed 291 ledger records, 287 beyond their hashes. Generic claims and unrelated
test reports do not substantiate full-file coverage. For example, a desktop presentation test
does not verify the release manifest or engineering instructions, and the existing
`state/reducer/VisionReducerTest` contains placeholder assertions. The new vision regression
uses real reducer inputs and outputs instead.

Preserve every changed submitted record under `runtimeAuditSubmission` while retaining the prior
authoritative status and identity. The scope reviewed here is recorded separately as partial
diff/integration review. No claim of 100% file review or physical hardware coverage is made.
The submitted campaign documents are historical proposals/evidence, not active instructions.

Hardware remains unavailable. FTC Control Hub, roboRIO, XRP, physical Limelight calibration,
and field latency require a later hardware checkpoint. Desktop and simulator evidence is not
controller timing. Wider field-editor concurrency, speculative edge-input cleanup, and unsupported
full-file ledger closures are deferred. Do not automatically begin another broad audit pass.
