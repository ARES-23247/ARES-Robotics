# Calibration lease recovery: independent local peer review

Date: 2026-09-21. Submitted commit: `f69ddec09ecedc57d934784eb9be93ce61f2f216`,
based on the previously reviewed checkpoint `1a3926e0ff42f869b7ec2c48dc75876c04b15105`.
Review worktree: `.codex-validation/reviewed-tuning-lease`, branch
`codex/reviewed-tuning-lease`. The starting checkout was clean. Other worktrees and running
processes were preserved. This checkpoint is local; no push or publication is authorized.

## Findings and changes

The submission adds useful generated-consumer lease coverage. No production source changed,
and this review identified no new runtime defect within the selected scenario. Corrections are
to regression coverage and evidence:

1. **Expiry output and boundary coverage (test gap introduced by this submission).**
   The new test skipped from 1100 to 1620 ms and asserted disarming, but never asserted motor
   neutrality on that frame. It therefore could not detect an expired-frame output regression
   or establish the exact timeout boundary. The revised test runs continuous 20 ms frames,
   awaits actual asynchronous IMU samples, checks Redux sensor validity, holds through exactly
   500 ms, then checks all four motor powers on the first expired frame. It also proves manual
   repositioning resumes on the following frame; unarmed calibration does not permanently own
   manual-drive output.
2. **Publisher lifetime coverage (test weakening introduced by this submission).**
   Starting a publisher for every individual request made each request re-register its topics.
   That lost the prior test's persistent-registration behavior. Capture now uses one production
   publisher per connected robot-test sequence, registering only on its first request; recovery
   and late requests reuse the same publisher IDs. Capture asserts this structure. Each separate
   robot test still gets the announcements needed after NT4 shared-state reset.
3. **Evidence inaccuracies (introduced by this submission).**
   The submitted report described voltage assertions and `robot.stop()`, although the fixture
   checks normalized motor power and calls `lifecycle.stop()`. It also gave the wrong last
   heartbeat, an invented nanosecond timeout expression/constant, and 21 rather than 27 focused
   Studio tests. The corrected [submitted report](TUNING_LEASE_RECOVERY_REVIEW.md) distinguishes
   its original timeline from this revision. Original XML establishes 6 injected simulator cases
   plus 1 existing wiring case per generated project.

Retained-token, retained-lease and non-STOP rearm guards remain. The retained-lease check is
described precisely: a new session needs a valid sequence different from the last accepted
sequence; monotonic increase is enforced within an armed session. After stop, the revised test
also presents retained SysId session controls, a motion command, held manual input and a late
tuning request, and checks the closed manager and output fence.

## Selected scenario

These are mock robot-clock times, not desktop performance measurements.

| Time (ms) | Required behavior |
|---|---|
| 1020 | Held local gamepad input produces actual motor power before calibration opt-in. |
| 1040–1500 | Fresh STOP session and advancing lease own neutral output despite that input. |
| 1520–2000 | Robot frames and fresh feedback continue without lease writes; age 500 ms is valid. |
| 2020 | Age 520 ms expires; this same poll-eligible frame rejects nonce 2 with SESSION_NOT_ARMED, leaves the saved gain unchanged, and neutralizes all motors. |
| 2040 | Old token cannot rearm; valid manual repositioning resumes after the one-frame neutral. |
| 2060 / 2080 | New token with retained lease / new token and lease with non-STOP command cannot rearm. |
| 2100 / 2520 | Fresh STOP handshake rearms; new nonce 4 applies heading gain 3.2 under neutral hold. |
| 2540–2600 | Supported return to normal control yields +0.32 rad/s for a measured -0.10 rad heading and zero target; four motor signs agree with CCW rotation below saturation. |
| Stop / 3200 | Active output stops; retained control traffic and parsed nonce 5 cannot change output, accepted gain, acknowledgement, or persisted local overlay. |

The independent P-only reference is `3.2 * (0 - (-0.10)) = 0.32 rad/s`, with zero I/D
gains and fresh observed IMU input. The canonical saved gains are independently exercised at
2.1 and 1.4 through real save/export/reopen/generation. Canonical files remain byte-identical;
accepted tuning persists only to the local experimental overlay.

## Validation and identity

The focused Studio checks passed: **27 tests, 0 failures/errors/skips**. The outer generic
consumer roundtrip passed: **1 test, 0 failures/errors/skips**. Both saved configurations
(operations 3 and 4) passed their 6 injected simulator scenarios plus the existing simulator
wiring test, **14 simulator tests total**, plus **25 Android unit tests per configuration**,
with no failures/errors/skips. Per-operation results are retained. Both runs observed `omega=0.32000000000000006` and
the 500 ms held / 520 ms expired / next-frame manual-output assertions.

Final source-policy verification passed (including links, guidance and size ratchet). All seven
scoped ledger fingerprints match; every unrelated semantic record is preserved.
An intermediate policy check found the newly referenced peer-review report had not yet been
created; that documentation link was completed before final verification. No runtime test failed.

Library source is unchanged at `feedf38a5d4a009a5505e7b89f15525fec6b54ea`. All 82 retained
artifact SHA-256 values in the preceding candidate identity matched before reuse:
`19.1.4-rc.live-tuning.feedf38a5d4a.1`, repository
`file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-live-tuning/ARESLib-Kotlin/build/release-repository`.
No candidate was republished, archive regenerated or release version changed. The prior
[full library and consumer matrix](LIVE_TUNING_CONSUMER_PEER_REVIEW.md) remains prior evidence,
not a matrix rerun by this review.

From `ARES-Analytics`, with JDK 17 and the local Android SDK, commands use both candidate
properties above and `--no-parallel --console=plain`:

```text
gradlew.bat :app:test --tests '*TuningLiveRequestAuditTest' --tests '*Nt4TuningRequestWireAuditTest' --tests '*ConsumerRoundtripSupportTest' -ParesVersion=<candidate> -ParesRepository=<absolute-file-URI> --no-parallel --console=plain
gradlew.bat :app:consumerRoundtripTest --tests '*GenericStarterConsumerRoundtripIntegrationTest' -ParesVersion=<candidate> -ParesRepository=<absolute-file-URI> --no-parallel --console=plain
```

Exact commands, source/candidate identities and measured test counts are retained under the
review worktree's `build/tuning-lease-peer-review/`. Nested reports and captured requests are
retained under `ARES-Analytics/app/build/consumer-roundtrip-evidence/generic/operation-{3,4}/`.
These are local evidence, not portable CI artifacts. Existing `analytics_app` changed-part
routing runs both `:app:test` and `:app:consumerRoundtripTest`; no CI redesign is needed.

## Limits and next action

This exercises real generated code, FTC OpMode runtime and simulated IO. Tuning requests are
produced by the actual Studio publisher and replayed into the actual NT4 parser, with a proxy
socket. SysId token/lease/command still enter through the in-process topic seam. Local gamepad
input does not exercise the remote drive-frame lease. No live GUI, socket-network journey,
physical hardware, target-controller timing or allocation measurement is claimed.

The next bounded task is the [Studio calibration control-wire checkpoint](NEXT_CALIBRATION_WIRE_PROMPT.md).
It should close that specific remaining seam, reuse existing unit coverage, and stop. No broad
audit or release begins automatically.
