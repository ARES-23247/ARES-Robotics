# Studio calibration control wire: independent peer review

Date: 2026-09-21. Submitted commit: `08fb408ae7a0a928c8b0479b101e24522ec64ef9`.
Prior reviewed checkpoint: `3962fe8ef12dec3faa859d8a909156470d72c6e0`.
Review branch/worktree: `codex/reviewed-calibration-wire`,
`.codex-validation/reviewed-calibration-wire`. The primary checkout was clean on
`codex/studio-calibration-wire`. Other worktrees and running JVMs were preserved.
This is local review and integration, with no push or publication.

## Findings and fixes

The submission adds useful outbound control coverage without changing production source.
No new production defect was identified in the selected paths. Its tests and report needed
the following corrections:

1. **Timing-dependent loopback assertions.** The submitted loopback test ran the 200 ms
   heartbeat on wall time but required exact initial sequences 1 and 2. A scheduling delay
   allowing a heartbeat before observation would invalidate those expectations. It also
   claimed periodic renewal without waiting for any renewal. The test now drives a separate,
   owned coroutine scheduler while retaining the real public NT4 client, default calibration
   transport and live loopback socket. It observes leases 1, 2, 3, disarms, advances another
   1000 virtual ms, verifies renewal is inactive and the lease unchanged, then explicitly
   rearms with a new token and sequence 4. Network waits remain bounded by observed values.
2. **Capture cancellation and cleanup gap.** The submitted capture disarmed and immediately
   rearmed without proving cancellation. Its manually constructed TestScope had no failure-path
   teardown. The capture now checks no frames and no active renewal after 1000 virtual ms
   while disarmed. An owned SupervisorJob/controlled dispatcher is cancelled and checked in
   finally, including assertion failures; publisher/channel cleanup still runs if that check
   fails. The loopback test similarly retires its renewal scope before closing the client.
3. **Recovery could be a no-op.** Rearming reapplied the existing gain 3.2. The revised request
   applies a distinct 2.6 at nonce 5 and verifies Current, Redux, persisted local overlay and
   controller response. For target 0 and measured heading -0.10 rad, the independent P-only
   expectation is +0.26 rad/s, with zero I/D and correct CCW motor signs below saturation.
   The separate lease-expiry scenario still checks gain 3.2 and +0.32 rad/s.
4. **Missing observation checks and inaccurate claims.** Capture now requires the exact
   registration-frame count on initial requests and zero re-registration later. Generated
   consumer assertions check actual SysId/Armed feedback and distinct emitted tokens, keep
   feedback fresh at the disarmed request, and check the lease stays unchanged while disarmed.
   The [submitted report](STUDIO_CALIBRATION_WIRE_REVIEW.md) is corrected: UUID tokens are
   generated, not the stated literal examples; the live-tuning scenario moved to wire replay
   but the separate lease-expiry fixture did not. A real socket is exercised by the standalone
   loopback test, not by the combined generated-robot replay scenario.

Items 1–4 are test/evidence defects or gaps introduced in the submission, not observed unsafe
robot behavior. No unmeasured optimization, library version bump or unrelated refactor was added.

## Verified boundaries

The generated live-tuning scenario still covers unarmed rejection, a fresh STOP session with
competing manual input, valid apply, duplicate nonce and invalid value rejection, wire-driven
operator disarm with one neutral output frame, allowed later manual repositioning, rejection
while disarmed, fresh rearm, a different accepted gain, active-output lifecycle stop, and the
closed-manager/overlay fence. Canonical files remain byte-identical. Robot inputs are actual
simulated IO observations; no estimator truth or permissive apply context is substituted.

Publisher registration is once per connection. Calibration frames come from the production
SysIdSignalGenerator through a capture transport forwarding to Nt4OutboundPublisher. Captured
frames are replayed through the real robot NT4 parser. The standalone loopback test additionally
exercises Nt4ClientService and its default Nt4CalibrationCommandTransport over a real socket.

## Validation and reproducibility

Final focused checks passed: **62 Studio tests**, **1 outer generic consumer roundtrip**,
and both saved configurations (operations 3 and 4), each with **25 Android unit tests and 7
simulator tests**, all with **zero failures/errors/skips**. The simulator total includes 6
injected scenarios and 1 existing wiring case per configuration. Each live-tuning scenario
observed nonce 5 APPLIED at 2.6 and angular velocity 0.26 rad/s; each lease-expiry scenario
retained its independent 3.2 / 0.32 rad/s result. Final source-policy and scoped ledger checks
are recorded with the local checkpoint.

Library source remains `feedf38a5d4a009a5505e7b89f15525fec6b54ea`. All 82 retained artifact
SHA-256 values matched before reuse of `19.1.4-rc.live-tuning.feedf38a5d4a.1` at:
`file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-live-tuning/ARESLib-Kotlin/build/release-repository`.
Nothing was republished or repackaged. The earlier full matrix remains prior evidence for
unchanged library/consumer sources; it was not repeated here.

From `ARES-Analytics`, using JDK 17, the local Android SDK, the candidate above and its absolute
file repository, validation uses:

```text
gradlew.bat :app:test --tests '*TuningLiveRequestAuditTest' --tests '*Nt4TuningRequestWireAuditTest' --tests '*ConsumerRoundtripSupportTest' --tests '*SysIdSignalGeneratorTest' --tests '*SysIdAcknowledgementAuditTest' -ParesVersion=<candidate> -ParesRepository=<absolute-file-URI> --no-parallel --console=plain
gradlew.bat :app:consumerRoundtripTest --tests '*GenericStarterConsumerRoundtripIntegrationTest' -ParesVersion=<candidate> -ParesRepository=<absolute-file-URI> --no-parallel --console=plain
```

An initial review run failed only in the newly added cleanup join: manually cancelling a
TestScope used outside runTest did not complete its special lifecycle. This was a review-harness
error, not a product failure or a passing result to discard. It was corrected to an explicitly
owned ordinary CoroutineScope/SupervisorJob with the same controlled scheduler. The initial XML
and log are retained under `build/calibration-wire-peer-review/initial-scheduler-cleanup-failure/`.

Final logs, commands, counts, candidate identity and checkpoint records are retained in that
worktree's `build/calibration-wire-peer-review/`. Per-operation generated-consumer XML and
wire fixtures remain under
`ARES-Analytics/app/build/consumer-roundtrip-evidence/generic/operation-{3,4}/`.
Existing `analytics_app` changed-part CI already selects both test tasks. No CI changes are needed.
Only scoped ledger records are updated; previous evidence and unrelated entries are preserved.

## Limits and next action

Mock robot time and virtual heartbeat time are correctness tools, not performance measurements.
No physical hardware, native Studio UI, target-controller loop budget, or allocation result is
claimed. Local gamepad input does not exercise the remote drive-frame lease.

The staged capture arranges Studio's connection/capability prerequisites and forwards its
outbound calls through a test transport. Robot acknowledgement state is checked at the consumer,
but is not fed back into that capturing Studio instance. Thus these checks do not establish a
combined bidirectional Studio/generated-robot network session. The next bounded task is the
[robot feedback return path](NEXT_STUDIO_ACKNOWLEDGEMENT_PROMPT.md), with existing stale-epoch
unit coverage reused instead of another broad pass. This review stops after local integration.
