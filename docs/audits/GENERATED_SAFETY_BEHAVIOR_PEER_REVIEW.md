# Generated safety behavior: local peer review

Date: 2026-09-18. Submission: `f3b44d887842914066f47a9a10b779fa5d74aa52`,
based on release `v7.0.63` / `25608fd42ed3dc8bf5f7506beb54d38983208b97`.
Review branch: `codex/reviewed-generated-safety`, isolated under
`.codex-validation/reviewed-generated-safety`. This report's containing commit identifies the
corrected source checkpoint. No GitHub writes, release, new task, or subagent was used.

## Findings and corrections

The submission changed tests and a report, not production robot code. Its independent P-only
heading examples and timeout boundary checks were useful, but did not prove all claimed paths.
These are evidence defects in the submission, not demonstrated runtime regressions.

| Finding | Correction |
| --- | --- |
| Only the first heading gain was saved. The second directly dispatched `UpdateTuningState`, bypassing persistence, profile selection, and LIVE_SAFE authorization/acknowledgement. | Both gains now use typed Studio promotion, export, extraction, a fresh ProjectSession, actual regeneration, compilation and a new OpMode. Assert the saved profile is the selected canonical profile. Claim canonical startup behavior only; defer live transactions explicitly. |
| “Disable” meant a zero command or zero power-budget scale. BioBuzz released its command before STOP; generic had no mechanism OpMode shutdown test. | Keep power-budget inhibition accurately named. In separate real INIT/START/STOP scenarios, command a mechanism and drive simultaneously, stop while both remain commanded, check neutral IO and DISABLED, and check a later tick cannot reactivate output. Generic uses the generated subsystem setter after START; BioBuzz uses its existing A control binding. |
| Healthy configuration and feedback fields were manually fabricated. | Generated IO refresh and subsystem `readSensors` now populate Redux. Check missing initial feedback, healthy active output, timeout minus one, exact inclusive boundary, stale boundary, and recovery of the held command. Also give the controller a previously valid real snapshot to check its own age gate independently. |
| Fixed 35 ms sleeps guessed when the asynchronous REV IMU had sampled. | Wait with a bounded wall-clock deadline for the real IMU adapter to expose the requested heading and RobotClock timestamp. Tick the real runtime to ingest and then consume the observation. Assert measured estimator heading, fresh IMU, zero translation, omega and all four wheel signs. No estimator state injection. |
| Component IO ownership was not closed before later lifecycle use. | Each component fixture owns a separate hardware map and closes its generated subsystem in `finally`; verify close neutralizes and a later write is ignored. |
| The report overstated LIVE_SAFE, explicit enable, physical output, rearm and file-review evidence. No audit-ledger delta was included. | Preserve the original as historical submission with a correction notice. Record scoped evidence here and in the audit ledger. Freshness recovery does not claim output-fault latch rearming. File sizes and green suites do not establish file review. |

The two consumers share readable fixture source for these checks instead of duplicating escaped
test bodies. Existing geometry, autonomous metadata, USER-OWNED extension, generation determinism,
generic deadband and recovery checks remain. BioBuzz joystick behavior is exercised alongside its
active mechanism in the real lifecycle scenario. Nested reports are retained per build operation,
so the second saved gain does not overwrite the first gain's evidence.

## Independent expectations

All headings are CCW-positive radians. With target zero, measured heading -0.10, and Ki/Kd zero,
the independent proportional expectation is omega = Kp * 0.10. The fixture asserts literal outputs:

| Consumer | Saved Kp values | Expected omega (rad/s) |
| --- | --- | --- |
| BioBuzz | 2.4 and 1.8 | 0.24 and 0.18 |
| Generic FTC | 2.1 and 1.4 | 0.21 and 0.14 |

Each is a separate fresh generated consumer. All four wheel efforts must have the expected CCW
sign/symmetry and remain below saturation. No arbitrary drivetrain saturation constant is assumed.

For feedback sampled at 2000 ms, BioBuzz's saved 120 ms timeout permits output at 2119/2120 and
neutralizes at 2121. The generic gripper's saved 180 ms timeout permits at 2179/2180 and neutralizes
at 2181. Refresh at 2250 restores the held command in these non-latched freshness cases. Safe IO
means motor power 0 or servo position 0.5 for these specific generated configurations.

## Reproduction and evidence

Host: Windows, JDK 17, discovered Android SDK. Use published ARESLib **19.1.3** and the configured
immutable Maven repository; no local RC override or sibling substitution. ARESLib source remains
`4161ce50ae762d9ba02cd65663c6e9a40da6afbf`. Studio is 7.0.63, FTC starter 19.1.4, BioBuzz 1.1.5.
No library candidate or version bump is necessary for these test-only corrections.

From `ARES-Analytics` with JAVA_HOME and ANDROID_HOME set to installed toolchains:

```text
gradlew.bat :app:test --tests "*ConsumerRoundtripSupportTest" :app:consumerRoundtripTest --no-parallel --console=plain
```

From the monorepo root:

```text
python -m unittest discover -s scripts/tests -p test_*ci_path*.py
powershell.exe -ExecutionPolicy Bypass -File scripts/verify-monorepo-policy.ps1
python scripts/audit_inventory.py --refresh-ledger-fingerprint
git diff --check
```

Retained local evidence lives in this worktree's `build/generated-safety-review/`,
`ARES-Analytics/app/build/test-results/`, and `ARES-Analytics/app/build/consumer-roundtrip-evidence/`.
The last directory contains per-operation logs and nested JUnit XML captured before temporary
consumer projects are removed. Operations 3 and 4 identify the first and second saved gains.
The existing Studio CI scope runs `:app:test` and `:app:consumerRoundtripTest` and retains this
evidence. Shared/runtime/generator changes already propagate to the relevant consumers; no CI
redesign or remote job was needed.

The complete Gradle command passed in **8m 47s**. No test failures, errors or skips:

| Check | Observed result |
| --- | --- |
| BioBuzz outer round trip | Passed (131.113 s), including both separate saved/exported gains. Each nested build passed 49 robot/simulator tests, including the four consumer fixture methods. |
| Generic FTC outer round trip | Passed (116.620 s), including both separate saved/exported gains. Each nested build passed 30 robot/simulator tests, including the four consumer fixture methods. |
| Generation recovery | Both scenarios passed (173.904 s combined): owned-process cancellation/retry and partial-write rejection/recovery, including check-only rejection before repair. Each successful nested build passed 45 robot/simulator tests. Expected deliberate failed builds remain in the logs. |
| Driver completion regression | Passed: repeated identical immediate preflight failures do not hang the completion observer. |
| CI classifier and boundary tests | All 25 passed. The initial sandbox attempt could not create its temporary Git repository; the permitted local rerun passed without code changes. The reviewed delta selects Studio app checks. |
| Source policy | Passed: shared guidance, links in 197 current documents, maintainability ratchet (zero violations), release identities and bundled artifacts. |
| Audit accounting | Updated only this peer-review delta; seven unrelated stale identities and five missing historical paths remain outside scope. |

`build/generated-safety-review/results.json` summarizes the actual JUnit XML. Repeated nested
suite counts represent executions at different configurations, not distinct files reviewed or
unique tests. `verification-1.log`, `ci-scopes.log`, `source-policy.log` and
`selected-scopes.json` retain the corresponding command and scope evidence. No production
runtime fix was necessary for these selected scenarios.

## Limits and next action

This is a headless Windows integration checkpoint, not native Studio UI, Linux/macOS execution,
physical REV controller validation, or a loop-performance benchmark. Test build elapsed time is
not robot loop latency. No new performance result or hardware safety guarantee is claimed.

The feedback tests deliberately isolate cached sensor freshness from control leases and loop
stalls; their controller/subsystem segment does not itself emulate an enabled robot. The separate
OpMode scenario supplies real lifecycle evidence. Generic mechanism actuation uses its supported
generated API, not a newly invented control binding. Arbitrary hardware failures, latching fault
rearm, all disabled-init user-extension behaviors, and all tuning policies remain outside scope.

The specific remaining gap is live tuning authorization and acknowledgement through the actual
generated consumer. The [next-agent prompt](NEXT_LIVE_TUNING_CONSUMER_PROMPT.md) bounds that work
and requires reuse of the existing unit/transport evidence before adding integration tests.
Do not automatically begin that checkpoint or another broad audit pass.
