# Generated robot feedback: independent local peer review

Date: 2026-09-21. Submission: `41c82fef4`; prior reviewed checkpoint: `34369f071`.
Review branch/worktree: `codex/reviewed-robot-feedback`,
`.codex-validation/reviewed-robot-feedback`. Primary branch at review start:
`codex/studio-robot-feedback`, clean. Other worktrees and processes were preserved.
This review authorizes local integration only, with no push or publication.

## Findings and corrections

The submission provides a useful production inbound-parser/observer test, but its integration
and completion claims exceeded the evidence. No new robot-runtime defect was identified;
the following are submission-introduced test/evidence defects:

1. **Tests changed tracked source.** The consumer roundtrip copied generated replies into
   `src/test/resources`, overwriting three committed dumps with run-dependent IDs/timestamps.
   This made tests mutate their own input and could leave stale cached data when capture failed.
   The automatic copy, duplicate cached-replay unit wrapper and three cached dumps are removed.
   Their history remains in `41c82fef4`; the same return-path check now consumes fresh replies
   from both generated configurations. Build artifacts retain request/reply bytes per operation.
2. **Artificial nonce and profile agreement.** The Studio verifier injected ProcessedNonce 4
   directly and loaded a synthetic declaration/profile whose key differed from the real robot.
   It now obtains nonce 4 and Current 3.2 from an actual robot readiness snapshot, loads the
   exported canonical project, resolves the actual key by parameter UID, and checks that the
   public action proposes the same UID/value/nonce as the frame consumed by the generated robot.
   A replayed older real acknowledgement must leave nonce 5 pending; only its matching reply
   may report experimental application.
3. **Arithmetic was labelled controller evidence.** Computing `2.6 * 0.10` in Studio only checked
   constants. The consumer now records its observed Redux gain, estimated heading and angular
   output after existing independent assertions. The return-path check compares these observations
   to 2.6, -0.10 rad and +0.26 rad/s, as well as incoming Current and canonical-byte preservation.
4. **Chronology, capture and evidence retention.** The old verifier replayed a disarm from before
   the acknowledged apply and the driver omitted reply artifacts from retained operation evidence.
   The consumer now captures a final disarm after the accepted transaction. A real subscription
   supplies complete snapshots of SysId and the selected heading parameter, preserving emitted
   announcement/value ordering. Concurrent capture uses snapshot-safe storage instead of iterating
   synchronized lists without their lock. Replies and controller evidence are retained beside
   each operation's nested reports.
5. **Unnecessary production access and leaked workers.** The new test exposed SysIdViewModel's
   private signal generator solely to inspect it, while its real NT4 client was never disposed.
   The helper is private again. Tests use public state and observed lease publications, retire
   their owned coroutine scope, and invoke `disposeAndJoin()` on the owned client in finally.
6. **Overstated report.** The [submitted report](STUDIO_ROBOT_FEEDBACK_REVIEW.md) remains historical
   with a correction notice. Its missing-announcement failure was a capture-fixture defect, not
   a demonstrated production inbound-router defect. Staged inbound replay is not a connected
   bidirectional operator session.

No safety leases, production authorization, estimator behavior or library source were changed.
The final production visibility matches the preceding reviewed checkpoint.

## Selected behavior and seams

In each freshly generated configuration, Studio stays ARMING until the captured arm reply
is decoded by its real inbound router, stored in TelemetryStore and observed by SysIdViewModel.
The actual canonical heading declaration feeds TuningViewModel. Its public request remains
pending without a matching result, ignores a prior result, and confirms experimental application
only after the generated robot's nonce-5 APPLIED reply. Incoming Current agrees with actual
robot gain/output evidence. Disarm revokes local authorization, stops renewal, and a later replay
of old armed feedback cannot rearm it. Canonical files remain byte-identical.

The source robot uses real generated code, FTC OpMode runtime, observed simulated IO, actual
NT4 reply encoding and existing independent controller assertions. The inbound receiver is a
real Nt4ClientService with spied connection identity; its calibration transport records outbound
commands and its tuning publication is an acknowledged test seam checked against the consumed
request. Local connection/mode/capability prerequisites are arranged explicitly. Replies come
from a different staged producer session, not the receiving Studio actor's live socket.
No claim is made that a boolean arm reply identifies a token or proves end-to-end session binding.

## Validation and identity

- Focused Studio checks: **62 tests, 0 failures/errors/skips** across tuning requests, NT4 wire
  encoding, consumer support and SysId arm/lease acknowledgement behavior.
- Fresh generic consumer roundtrip: **1 integration test passed**, with **25 TeamCode + 7 simulator
  tests per configuration** (64 nested test executions across operations 3 and 4), no failures,
  errors or skips. Both fresh reply sets passed the Studio return-path verification.
- Both generated configurations recorded gain **2.6**, estimated heading **-0.10 rad** and actual
  controller angular output **+0.26 rad/s**. Requests, replies and observations remain in build
  evidence; test execution did not write tracked source resources.
- Repository source policy passed: shared guidance, Markdown links, maintainability ratchet,
  release manifests, bundled archive identities and existing source constraints.
- CI path classification selects `analytics_app` and its consumer roundtrip task, with unchanged
  library source; scoped ledger hashes match and unrelated semantic records are preserved.

The first peer-review integration run failed because the newly real canonical project uses
`drive.headingKp` as the public action key, while the first revision still passed the parameter
UID. This was a review-fixture mismatch, not a production regression. The corrected check resolves
the canonical key by UID. The failed run and its nested evidence are retained under
`build/robot-feedback-peer-review/initial-canonical-key-failure/`.

Unchanged library tree: `feedf38a5d4a009a5505e7b89f15525fec6b54ea`.
Candidate: `19.1.4-rc.live-tuning.feedf38a5d4a.1`.
All 82 retained artifact hashes matched before reuse at:
`file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-live-tuning/ARESLib-Kotlin/build/release-repository`.
No artifact was republished, archive rebuilt, or version changed. The earlier full library and
consumer matrix remains prior evidence; only affected checks are run here.

From `ARES-Analytics`, with JDK 17, local Android SDK and the explicit candidate/file repository:

```text
gradlew.bat :app:test --tests '*TuningLiveRequestAuditTest' --tests '*Nt4TuningRequestWireAuditTest' --tests '*ConsumerRoundtripSupportTest' --tests '*SysIdSignalGeneratorTest' --tests '*SysIdAcknowledgementAuditTest' -ParesVersion=<candidate> -ParesRepository=<absolute-file-URI> --no-parallel --console=plain
gradlew.bat :app:consumerRoundtripTest --tests '*GenericStarterConsumerRoundtripIntegrationTest' -ParesVersion=<candidate> -ParesRepository=<absolute-file-URI> --no-parallel --console=plain
```

Logs, exact commands/counts, candidate identity and local checkpoint records are in the review
worktree's `build/robot-feedback-peer-review/`. Fresh nested JUnit, requests, replies and observed
controller values are retained under
`ARES-Analytics/app/build/consumer-roundtrip-evidence/generic/operation-{3,4}/`.
Existing `analytics_app` CI selects both test tasks; no CI redesign is needed. The ledger records
only scoped review and retains the removed cached fixtures' historical records.

## Remaining practical check

No native Studio window, physical robot, target-controller timing, allocation measurement or
real combined network session was observed here. The generic consumer test locally enables
calibration through APIs; it does not prove an exported starter has an operator-facing tuning
OpMode. Lightbot has the existing `ARES Live Tuning TeleOp` path.

The [next prompt](NEXT_STUDIO_OPERATOR_ACCEPTANCE_PROMPT.md) therefore asks for one real
Studio/Lightbot simulator operator journey, including visible acknowledgement and graceful
shutdown, instead of another nested replay fixture. This checkpoint ends after local integration.
