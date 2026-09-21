# Next bounded checkpoint: one real Studio tuning journey

The [feedback peer review](STUDIO_ROBOT_FEEDBACK_PEER_REVIEW.md) exercises generated robot
replies and Studio observers through staged replay. The next useful evidence is one actual
operator journey, using the existing Lightbot tuning OpMode and an owned desktop simulator.

```text
Validate one real Studio/Lightbot live-tuning journey without hardware. Keep this a bounded
operator-readiness checkpoint, not another general audit or another staged test harness.

Read AGENTS.md, docs/audits/STUDIO_ROBOT_FEEDBACK_PEER_REVIEW.md, the relevant repository skills,
ARES-FTC guidance, and especially compose-desktop-tester. Start from the latest local reviewed
checkpoint containing that report; record its exact SHA. Use one isolated codex/ branch/worktree,
one agent, disposable project copies and a separate desktop home. Preserve other work/processes.
Do not merge, push, publish, deploy to hardware, or create tasks/subagents.

First inspect the supported operator path. ARES-FTC has ARESTuningTeleOp ("ARES Live Tuning
TeleOp") and local calibration enable logic in AresRobot. The generic starter regression
arranges local opt-in through test APIs; it does not prove an operator can enable that mode
in an exported generic starter. Use the existing Lightbot implementation for this acceptance
check and record that distinction. Do not silently add a new starter feature to make it pass.

Use the existing UI/process automation and the actual rendered Studio window, real NT4
connection and simulated robot IO. Follow exact-window capture and graceful-shutdown rules.
Normal :app:run can kill other ARES instances: verify ownership, use -PskipKill and an isolated
-ParesIsolatedDesktopHome, and preserve isolated runtime snapshots and single-instance locks.
Never replace another task's simulator on its port or stop an unowned process.

Cover one connected journey:
1. Open the disposable Lightbot project, launch/connect the owned simulator, select the actual
   tuning OpMode and perform the visible INIT/START/local arm steps. Confirm UI state agrees
   with robot mode and feedback; publication alone must not appear as confirmed arm.
2. Stage one realistic, distinct heading-gain change using the public Tuning UI. Observe the
   pending request, matching robot acknowledgement, Current and experimental-applied status.
   Verify the canonical project remains unchanged and local overlay handling is correct.
3. Explicitly disarm in the UI. Confirm renewal ends and robot calibration disarms; distinguish
   transition-frame neutral from allowed later manual repositioning. Stop the OpMode while
   output is active if the existing simulator controls support a safe observable check.
4. Close the owned Studio/simulator gracefully and verify their PIDs and owned ports are gone.

Do not inject mode/arm flags or acknowledgements, invoke private controller state to bypass the
operator path, weaken leases, or substitute simulator truth for estimation. Reuse the existing
independent math and generated-consumer regressions instead of duplicating them. If a visible
interaction or missing supported control blocks the journey, capture the exact failure and fix
the smallest responsible product boundary when feasible. A live JVM or green service test
does not substitute for observing the required window interaction.

Read versions from the manifest. For unchanged library source
feedf38a5d4a009a5505e7b89f15525fec6b54ea, verify and reuse candidate
19.1.4-rc.live-tuning.feedf38a5d4a.1 and its retained 82-artifact hash manifest at
.codex-validation/reviewed-live-tuning/build/live-tuning-review/candidate-identity.json.
The machine-local repository is:
file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-live-tuning/ARESLib-Kotlin/build/release-repository
Use the same explicit candidate properties for Studio and robot. Never resolve unpublished
stable 19.1.4 remotely or fall back to 19.1.3. Missing artifacts require a fresh unique local
candidate. Library changes require an unused version/source identity and dependency-ordered
validation under ares-build-release; never publish changed bytes under an existing version.

For demonstrated defects, distinguish pre-existing behavior from regressions, add only meaningful
affected checks, and use the existing changed-part CI scopes. Serialize compilers. Avoid unrelated
UI redesign, speculative optimization or repeated full matrices for unchanged code.

Retain exact-window screenshots, connection/mode/request/result evidence, process identities,
cleanup observations, source/candidate identities and exact commands. Update only scoped ledger
records and preserve prior evidence. Do not claim hardware or controller timing results from
this desktop check. Stop after this one journey passes with no known high-impact defect, or
document a concrete tooling/environment blocker and the exact manual acceptance steps remaining.
Return local commits and a concise readiness report; do not automatically start another pass.
```
