# Next bounded checkpoint: tuning lease expiry and explicit rearming

Follow the [live tuning peer review](LIVE_TUNING_CONSUMER_PEER_REVIEW.md). The preceding checks
exercise fresh authorized sessions, replay, invalid values, and close. They do not establish the
generated consumer's behavior when its calibration lease expires in the same frame as a request.

```text
Verify calibration-lease expiry and recovery for one live heading parameter in the actual
generated generic FTC consumer. Keep this a bounded local checkpoint, not a broad audit.

Read AGENTS.md, relevant repository skills/product guidance, and
docs/audits/LIVE_TUNING_CONSUMER_PEER_REVIEW.md. Inspect branch, dirty state and processes.
Create one isolated codex/ branch/worktree from the latest local reviewed checkpoint containing
that report; record the exact commit. Preserve unpublished corrections, other work and running
processes. Use one agent. Do not merge, push, release, deploy, or start other tasks/subagents.

First inspect existing calibration/lease tests, TuningPollingAuditTest, TuningManagerTest,
MecanumTuningTransactionAuditTest and the generated consumer fixtures. Reuse valid coverage;
add only the missing generated-consumer integration. Keep the existing saved-gain, live tuning,
shutdown and canonical-preservation regressions intact.

Use the real generic starter, canonical declarations, generated code, FTC OpMode runtime,
RobotClock and simulated IO. Reuse the Studio-produced wire fixture and production NT4 parser.
Do not inject permissive apply context or tuning state, replace estimator state with simulator
truth, bypass enable/nonce gates, or claim a full GUI/network journey from staged wire replay.

Cover only this connected sequence:
1. Establish a fresh calibration STOP session using the actual local mode opt-in plus new token
   and advancing lease. With a competing drive command, demonstrate neutral output ownership.
2. Withhold lease updates across the documented expiry boundary. Present a new valid heading
   request on a tick when the tuning poll is eligible. Prove the same frame processes lease
   expiry before tuning authorization, rejects the request with the correct acknowledgement,
   and leaves the last confirmed gain and canonical files unchanged.
3. Show retained/replayed token/lease data cannot accidentally rearm. Then perform the actual
   documented fresh rearm procedure, apply one valid request with a new nonce, and demonstrate
   its intended controller effect after the supported transition back to normal control.
4. Stop while output is active, then prove stale session traffic cannot reactivate output or
   change the closed tuning manager. Reuse existing close checks where they already cover this.

Read and document the precise lease boundary, poll timing and manual-control behavior after
disarming. Do not assume calibration mode permanently inhibits manual repositioning if the
documented contract allows it. Separate sensor freshness, calibration lease, control-input lease,
and tuning poll interval so a different safety gate cannot explain a passing assertion. Use
observed asynchronous sensor samples and bounded waits, not guessed sleeps. Calculate any
heading-output expectation independently in a realistic range below saturation.

Fix demonstrated high-impact defects at the smallest responsible boundary, with fail-before
evidence where practical. Identify pre-existing defects versus regressions. Defer cosmetic work,
unmeasured performance changes, other tuning policies and additional mechanisms.

Read dependency versions and the local candidate identity from the peer review. This checkpoint
contains an unpublished library fix, so reuse its exact candidate/repository for unchanged
library source; do not resolve the unpublished 19.1.4 stable pin from GitHub or fall back to 19.1.3.
If the retained candidate is unavailable, build a fresh unique local candidate from the recorded
library tree before testing consumers. Any new library change requires an unused version/source-tree identity,
an isolated candidate and dependency-ordered validation per ares-build-release; never change
bytes under a published version. Keep tests in existing changed-part CI scopes and serialize
builds writing the same outputs. Do not redesign CI or begin a release.

Run affected checks once stable. Retain per-operation request bytes, acknowledgement/output
evidence and nested JUnit reports before temporary consumers are deleted. Record actual commands,
dependency identity, failures/skips, expected/observed behavior and desktop/hardware limitations.
If library source is unchanged, reuse the prior full-matrix evidence and run focused affected
unit tests plus :app:consumerRoundtripTest --tests '*GenericStarterConsumerRoundtripIntegrationTest'
with the recorded candidate properties. Broaden only for changed contracts or unresolved failures.
Update docs/audits/file-reviews.json only for reviewed changes, preserving earlier evidence and
accurate fingerprints. Return local commits and a concise report. Stop when this selected expiry
and recovery sequence passes with no known high-impact defect, or document a concrete external
blocker. Do not automatically expand into another audit pass.
```
