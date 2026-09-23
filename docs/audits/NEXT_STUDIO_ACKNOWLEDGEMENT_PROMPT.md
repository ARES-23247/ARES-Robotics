# Next bounded checkpoint: robot feedback reaches Studio

The [calibration wire peer review](STUDIO_CALIBRATION_WIRE_PEER_REVIEW.md) covers outbound
commands reaching the generated FTC robot. Its staged capture does not feed that robot's
acknowledgements back into Studio. The separate live loopback test has transport but no robot
policy. Verify that return path before expanding to more tuning parameters or mechanisms.

```text
Verify one generated FTC calibration/tuning session's return path into Studio: robot-confirmed
arm/disarm state and the result of one heading-gain transaction. Keep this bounded and local.

Read AGENTS.md, relevant repository skills, and docs/audits/STUDIO_CALIBRATION_WIRE_PEER_REVIEW.md.
Inspect the branch, dirty state and processes. Create one isolated codex/ branch/worktree from
the latest local reviewed checkpoint containing that report, and record the exact SHA. Preserve
unrelated work and running processes. Use one agent. Do not merge, push, release, deploy, create
other tasks/subagents, or resume the all-files audit.

First inspect SysIdViewModel/SysIdSignalGenerator, TuningViewModel, Nt4ClientService's inbound
path, TelemetryStore, SysIdAcknowledgementAuditTest, TuningLiveRequestAuditTest and the current
consumer fixtures. Map existing coverage before editing. Reuse unit coverage for stale epochs,
late completion and cancellation; do not recreate that matrix. If equivalent connected evidence
already exists, report it instead of adding redundant tests.

Close only the missing robot-to-Studio integration:
1. Withhold a robot arm acknowledgement after Studio's explicit arm request. Confirm Studio
   does not present confirmed ARMED merely because publication succeeded.
2. Process that request in the actual generated FTC consumer with explicit local mode opt-in
   and simulated IO. Carry its actual emitted acknowledgement through Studio's production
   inbound decoding/telemetry path and existing observer; confirm the expected arm state.
3. Issue one valid heading-gain change through the public Studio tuning action. Show the request
   remains pending until the matching robot-produced result arrives, then agrees with robot
   Redux state, Current and the independent controller-output expectation.
4. Disarm through Studio, carry the generated robot's feedback back, and verify local
   authorization is revoked and renewal stops. Include one stale/previous-connection feedback
   case at this integration boundary only if existing coverage does not establish it. Stop the
   owned robot/client and prove test jobs/resources close.

Prefer one owned loopback harness if existing infrastructure makes it practical. Otherwise,
capture actual robot-produced bytes and replay them through the real Studio inbound path;
describe the seam honestly. Do not manufacture success acknowledgements, call store/viewmodel
setters to force ARMED or APPLIED, substitute permissive apply context, directly mutate tuning
state, or weaken freshness, enable, nonce, connection or lease checks. Configuration and local
operator prerequisites may be arranged explicitly and must be identified as test setup.

Reuse the existing generated starter scenario, canonical-preservation checks and observed IMU
helper. Use a distinct accepted gain so a no-op cannot pass. Distinguish sensor freshness,
calibration lease, tuning polling, and one-frame disarm neutral from allowed later manual
repositioning. Use controlled coroutine scheduling and bounded observed network/sensor waits.
Avoid duplicating another large lifecycle fixture; do not add production hooks solely for tests.

Fix only demonstrated defects at the smallest responsible boundary, with fail-before evidence
where practical. Classify pre-existing defects versus regressions. Defer speculative changes,
cosmetics, performance claims, additional products and broad test expansion.

Unchanged library source is feedf38a5d4a009a5505e7b89f15525fec6b54ea. Reuse candidate
19.1.4-rc.live-tuning.feedf38a5d4a.1 only after checking the retained SHA-256 identity at
.codex-validation/reviewed-live-tuning/build/live-tuning-review/candidate-identity.json.
Its machine-local repository is:
file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-live-tuning/ARESLib-Kotlin/build/release-repository
Do not resolve unpublished stable 19.1.4 remotely or fall back to 19.1.3. Missing artifacts
require a fresh unique local candidate from the recorded source. New library source requires
an unused version/source identity and dependency-ordered validation under ares-build-release.

Run affected Studio feedback/tuning/wire tests and the existing generic consumer roundtrip
with the same explicit -ParesVersion and absolute -ParesRepository. Serialize compilers.
Reuse unchanged full-matrix evidence and existing analytics_app CI routing; broaden only for
changed contracts or unresolved failures. Do not redesign CI.

Retain request and robot-reply bytes, relevant Studio states, robot outputs, nested JUnit
reports and exact commands. Update only scoped ledger entries, preserving prior records and
accurate hashes. Report failures, skips, seams and unavailable hardware plainly. Return local
commits and a concise report. Stop once the selected return path passes with no known
high-impact defect in scope, or identify a concrete blocker. Do not begin another audit pass.
```
