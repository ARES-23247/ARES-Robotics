# Next bounded checkpoint: saved tuning and feedback safety

Use this prompt after Studio 7.0.63 is published. It follows the
[consumer peer review](PROJECT_ROUNDTRIP_CONSUMER_PEER_REVIEW.md), whose headless tests prove
configuration preservation, generated geometry/control behavior, and recovery. They do not yet
establish the behavioral effect of the edited heading gain and feedback timeout.

```text
Verify that saved tuning and feedback-safety settings actually govern generated FTC robot output.
Keep this a bounded consumer-behavior checkpoint, not another repository-wide audit.

First verify that GitHub release v7.0.63 exists. Create an isolated feature branch/worktree from
that exact release tag, record its commit, and preserve other people's changes and processes.
Read AGENTS.md, the relevant repository skills/product guidance, and
docs/audits/PROJECT_ROUNDTRIP_CONSUMER_PEER_REVIEW.md. Use one agent.

Use the existing BioBuzz and generic FTC consumer round-trip fixtures and actual Studio
ProjectSession/persistence APIs, export/reopen flow, project wrapper, generated runtime, and
simulated IO. Extend the existing consumerRoundtripTest scope instead of duplicating an entire
test harness. Use the Gradle-selected dependency version/repository and discovered Android SDK;
do not introduce absolute machine paths, installed-app cache mutations, or silent opt-in skips.

Close these two specific evidence gaps:
1. Save two distinct heading gains and demonstrate their intended effect on controller output
   for the same realistic heading error, zero translation, and fresh feedback. Account for
   active tuning-profile selection and apply policy. Choose a case below saturation so clamping
   cannot hide a disconnected setting. Calculate the expected sign and magnitude independently
   from the documented controller contract; do not reuse the implementation as the oracle.
2. Save a meaningful mechanism feedback timeout. Establish nonzero output with valid config,
   explicit enable, and fresh feedback; advance an injected RobotClock across the timeout while
   withholding feedback and prove the affected output neutralizes. Cover missing initial
   feedback, enable/disable, and recovery according to the documented rearm policy. Distinguish
   sensor-feedback freshness from control-input leases and loop stalls; keep unrelated conditions
   valid so each assertion identifies the gate it tests. Exercise threshold boundaries according
   to the actual contract rather than assuming inclusive/exclusive behavior.

Trace the generated setting from its .ares document through runtime configuration to the
controller and IO. If a setting intentionally does not apply to a selected mechanism/profile,
document why and select a supported representative mechanism; do not invent an unsupported
contract. If the UI/schema promises behavior the runtime ignores, treat it as a demonstrated
defect and fix the smallest responsible boundary. Preserve USER-OWNED extensions and all enable,
freshness, neutralization, Redux, units, and clock invariants. Do not use simulator truth as state.

Capture fail-before/pass-after evidence for actual defects where practical. Distinguish
pre-existing defects from regressions, and defer cosmetic or speculative work. Reuse the passing
round-trip/cancellation checks; run affected regressions once the fixes stabilize. No timing
optimization without measured evidence. These are desktop/simulator tests, not hardware proof.

Read dependency identities from release/ares-versions.properties. Use the published release for
unchanged library code. Any ARESLib change requires a new unused version/source-tree identity,
a unique isolated candidate, and dependency-ordered validation per the release skill. Never
republish different bytes under an existing version. Keep useful checks in the existing CI scopes.

Update docs/audits/file-reviews.json only for the actual reviewed delta, preserving prior claims
and evidence with accurate content fingerprints. Write a concise report with exact source and
dependency identities, commands, expected/observed outputs, defects and origins, and limitations.
Stop when these two behavioral contracts pass or a concrete external blocker remains. Return
local commits and that report. Do not merge, push, publish, deploy, start other tasks/subagents,
or automatically begin another audit pass.
```
