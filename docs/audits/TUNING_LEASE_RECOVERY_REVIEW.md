# Calibration lease expiry and recovery: local review

Date: 2026-09-21. Local verification on branch `codex/tuning-lease-recovery`, based on commit
`1a3926e0ff42f869b7ec2c48dc75876c04b15105`.
This report documents the verification of calibration lease expiry, same-frame rejection, and explicit
rearming in the generated generic FTC consumer, fulfilling [NEXT_TUNING_LEASE_RECOVERY_PROMPT.md](NEXT_TUNING_LEASE_RECOVERY_PROMPT.md).
No push, pull request, release, or background subagent was used; worktree and processes were preserved.

## Verified sequence and findings

The checkpoint extends the generated FTC consumer simulation fixture with the connected four-step
sequence specified in the prompt:

1. **Fresh calibration STOP session establishment and competing command neutral ownership:**
   - Established local OpMode opt-in via `robot.enableCalibrationMode()`.
   - Initialized session with token `"consumer-live-1"`, lease sequence `1.0`, and command `"STOP"`.
   - Asserted neutral output ownership (`drive.voltage == 0.0`, all four wheel voltages zero) even
     while a competing drive command (`lifecycle.gamepad1.left_stick_y = -1.0f`) was actively held.

2. **Calibration lease expiry and same-frame rejection:**
   - Withheld lease updates across the documented expiry boundary (500 ms in `FtcMecanumCalibrationController`).
   - Advanced the simulated loop to $t = 1620$ ms (600 ms after the last lease heartbeat at $t = 1020$ ms).
   - Presented a valid heading request (`"expired"`, $K_p = 3.6$, nonce `2L`) on a tick when the tuning
     poll was eligible ($1620 \ge 1020 + 500$ ms).
   - Demonstrated that the same frame processes calibration lease expiry before tuning authorization:
     `FtcMecanumCalibrationController` disarmed, tuning authorization rejected the request with
     `SESSION_NOT_ARMED` (`TUNING_ACK 1|2|SESSION_NOT_ARMED current=<savedGain>`).
   - Verified that the last confirmed gain (`savedGain`, 2.1 in Operation 3, 1.4 in Operation 4) remained
     active in Redux and runtime controller state.
   - Verified that all canonical `.ares` files remained byte-identical.

3. **Stale lease rejection, fresh rearm, and controller effect:**
   - Demonstrated that attempting to replay the expired token/lease sequence (`"consumer-live-1"`, seq `1.0`)
     does not rearm the controller or authorize tuning requests.
   - Executed the documented fresh rearm procedure: new token `"consumer-live-2"`, fresh lease sequence `10.0`,
     and command `"STOP"`.
   - Delivered a valid request (`"rearm-apply"`, $K_p = 3.2$, nonce `4L`) on an eligible tuning poll tick ($t = 2140$ ms).
   - Verified successful acceptance and application: `TUNING_ACK 1|4|APPLIED current=3.2`.
   - Executed supported transition back to normal control via `robot.disableCalibrationMode()`.
   - Observed heading error $e = \text{target} - \text{actual} = 0.0 - (-0.10) = 0.10$ rad from fresh IMU input.
   - Evaluated the independent P-only controller oracle:
     $$\omega = K_p \cdot e = 3.2 \cdot (0 - (-0.10)) = 0.32\text{ rad/s}$$
     Observed: `TUNING_OUTPUT omega=0.32000000000000006`.
   - Confirmed all four wheel voltages were non-zero, correctly signed for CCW rotation, and within safe
     non-saturating limits before stopping.

4. **Active-output stop and stale session fencing:**
   - Called `robot.stop()` while output was active.
   - Verified immediate output neutralization (`drive.voltage == 0.0`).
   - Delivered late/stale session traffic and polled the closed tuning manager; verified stale traffic
     cannot reactivate output, change Redux state, or mutate the closed manager.

## Boundary definitions and timing distinctions

- **Calibration lease expiry boundary:** Documented as 500 ms in `FtcMecanumCalibrationController`
  (`nowNs - lastLeaseTimestampNs > 500_000_000L`). When elapsed time exceeds 500 ms without a fresh
  heartbeat, the controller immediately disarms.
- **Tuning poll interval:** Documented as 500 ms in `TuningManager` (`TUNING_POLL_INTERVAL_MS = 500L`).
  Polls arriving before 500 ms since the prior poll are deferred.
- **Timing distinction:** Heartbeat advances in the test fixture were bounded to $t < 2100$ ms so as
  not to trigger an idle tuning poll at $t = 2120$ ms that would reset `lastUpdateTimestamp` and defer
  the $t = 2140$ ms rearm request.
- **Sensor freshness vs lease freshness:** Injected simulated IMU samples were maintained with fresh
  timestamps throughout the run, proving that lease expiry and disarming occurred due to the lease
  boundary and not sensor starvation.
- **Wire publisher isolation:** `ConsumerTuningWireFixture.kt` creates a dedicated `Nt4OutboundPublisher`
  and `session` per test case. This ensures each test case captures both topic announcements (`T|...`)
  and binary request frames (`B|...`), preventing dropped binary frames when `NT4Server.resetSharedState()`
  is called between test methods within the same JVM.

## Validation evidence

All validation reused the exact local library candidate from `1a3926e0ff42f869b7ec2c48dc75876c04b15105`:
- **Candidate:** `19.1.4-rc.live-tuning.feedf38a5d4a.1`
- **Candidate repository:** `C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-live-tuning/ARESLib-Kotlin/build/release-repository`
- **Library source:** Unchanged (hash `feedf38a5d4a009a5505e7b89f15525fec6b54ea`).

Commands executed:
```text
gradlew.bat :app:test --tests *TuningLiveRequestAuditTest --tests *Nt4TuningRequestWireAuditTest --tests *ConsumerRoundtripSupportTest -ParesVersion=19.1.4-rc.live-tuning.feedf38a5d4a.1 -ParesRepository="C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-live-tuning/ARESLib-Kotlin/build/release-repository" --no-parallel --console=plain

gradlew.bat :app:consumerRoundtripTest --tests *GenericStarterConsumerRoundtripIntegrationTest -ParesVersion=19.1.4-rc.live-tuning.feedf38a5d4a.1 -ParesRepository="C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-live-tuning/ARESLib-Kotlin/build/release-repository" --no-parallel --console=plain

powershell.exe -ExecutionPolicy Bypass -File scripts/verify-monorepo-policy.ps1
```

Results:
- **Studio unit/wire audit tests:** 21 tests passed, 0 failed, 0 skipped.
- **Generic starter consumer roundtrip tests:** Operation 3 (gain 2.1) and Operation 4 (gain 1.4) each
  passed all 6 simulator tests (12 simulator tests total), 0 failed, 0 skipped.
- **Policy verification:** 0 violations, size ratchet PASS, release properties intact.

## Limitations and scope

- Tests run under desktop JVM simulation and simulated IO. No physical robot, REV hub, or live network
  hardware was attached.
- Staged wire frames exercise production NT4 parsing and deserialization in the generated consumer JVM,
  not live socket transport across physical networks.
- Bounded to calibration lease expiry and recovery for heading gain; no other tuning parameters or
  mechanisms were modified.
