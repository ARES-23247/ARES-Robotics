# Next bounded checkpoint: Studio calibration control wire

The [lease peer review](TUNING_LEASE_RECOVERY_PEER_REVIEW.md) verifies generated-robot expiry,
rearming and heading output. Its tuning proposals use Studio-produced NT4 frames, but its
calibration token, lease and command still enter through an in-process topic seam. Close that
specific integration gap before adding more parameters or repeating the broader audit.

```text
Verify the real Studio calibration-control publication path through the generated generic FTC
consumer. Keep this a single bounded, local checkpoint. First inspect existing coverage; if the
connected scenario already has equivalent evidence, report it instead of duplicating tests.

Read AGENTS.md, the relevant repository skills, docs/audits/TUNING_LEASE_RECOVERY_PEER_REVIEW.md,
and docs/audits/LIVE_TUNING_CONSUMER_PEER_REVIEW.md. Start from the latest local reviewed commit
containing those reports and record its exact SHA. Use one isolated codex/ branch/worktree and
one agent. Preserve other work and running processes. Do not merge, push, release, deploy, create
other tasks/subagents, or resume the all-files audit.

Inspect SysIdSignalGenerator, its calibration transport, Nt4OutboundPublisher, the existing
SysIdAcknowledgementAuditTest/SysIdSignalGeneratorTest, FTC calibration-controller tests, and
the generated consumer fixtures. Reuse their unit coverage and the current generated-consumer
scenario. Avoid copying another large robot lifecycle fixture.

For one FTC heading parameter, carry Studio's actual STOP/token/lease publications through the
production NT4 publisher and robot parser, alongside the existing typed tuning requests. Keep
the robot's explicit local calibration opt-in. Do not fabricate an armed apply context, write
SysId controls with NT4Server.publishTopic, hard-code replacement wire messages, or bypass
nonce, feedback, mode, connection, or lease gates. Preserve publisher registration once per
connection and binary ordering within each connected sequence.

Cover one connected flow:
1. Studio explicitly arms; the generated robot acknowledges the fresh STOP session and owns
   neutral output despite a competing manual command.
2. A valid typed heading request is accepted only under the established authorization.
3. Studio explicitly disarms; its real STOP/token revocation reaches the robot. The transition
   neutralizes output, tuning is rejected afterward, and lease renewal has stopped.
4. Explicit fresh arming recovers and a new nonce succeeds. Stop with active output and verify
   cleanup of this test's session, jobs and robot. Reuse existing controller/canonical-byte
   assertions and unit-level delayed-completion coverage.

Prefer deterministic capture/replay with observable acknowledgements or an owned loopback
harness, whichever closes the gap with less scaffolding. Document the actual seam: staged
cross-JVM replay does not establish live socket, GUI, physical robot or network timing behavior.
Keep sensor feedback fresh and distinguish the one-frame fault neutral from allowed manual
repositioning after calibration disarms. Assert real motor output, processed nonce and Redux
gain, not just local Studio arm state. Use controlled scheduling for heartbeat jobs and bounded
observed waits for asynchronous sensors; no arbitrary sleeps or shortened safety leases.

Fix only demonstrated defects at the smallest responsible boundary and retain fail-before
evidence where practical. Identify pre-existing behavior versus a new regression. Defer
speculative optimization, new tuning policies, other platforms and cosmetic refactoring.

The unchanged library candidate is 19.1.4-rc.live-tuning.feedf38a5d4a.1, source tree
feedf38a5d4a009a5505e7b89f15525fec6b54ea. Verify the retained candidate-identity.json hashes in
.codex-validation/reviewed-live-tuning/build/live-tuning-review before reuse. Its repository is:
file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-live-tuning/ARESLib-Kotlin/build/release-repository
These are machine-local evidence paths, not tracked build defaults. Do not resolve unpublished
stable 19.1.4 from GitHub or fall back to 19.1.3. If artifacts are missing, create a fresh unique
local candidate from the recorded tree. Any library source change requires a new unused
version/source identity and dependency-ordered validation under ares-build-release.

Run the affected Studio unit/wire tests and :app:consumerRoundtripTest --tests
'*GenericStarterConsumerRoundtripIntegrationTest' with the same explicit candidate version
and absolute repository. Serialize compilers. Broaden validation only for changed contracts or
unresolved failures; reuse the recorded full matrix for unchanged library/consumers. Keep this
in the existing analytics_app CI scope; do not redesign CI.

Retain per-operation nested JUnit reports, captured control/request bytes, acknowledgement/output
evidence and exact commands. Update only actually reviewed ledger entries, preserving previous
records and correct fingerprints. Report skips and limitations plainly. Return local commits and
a concise report, and stop when this control-wire sequence passes with no known high-impact
defect in scope. Do not automatically start another audit pass.
```
