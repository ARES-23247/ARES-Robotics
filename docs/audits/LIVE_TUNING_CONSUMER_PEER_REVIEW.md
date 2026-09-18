# Live tuning consumer: local peer review

Date: 2026-09-18. Reviewed submission `5be808128afc43999bf20e65c1d93a42845bc486`, based on
the previous local checkpoint `dc7bf80c138c385fec596997c5f4038db2bb4b1c`.
Review branch: `codex/reviewed-live-tuning`; worktree `.codex-validation/reviewed-live-tuning`.
This report's containing commit identifies the corrected source. No push, GitHub publication, new task,
or subagent was used; other worktrees and processes were preserved.

## Review findings

The submission adds a useful generated-consumer tuning test and changes no production code.
Strengthening its transport boundary exposed a **high-impact pre-existing runtime defect**:
`TuningManager` initialized `Requested` and `RequestNonce` with robot telemetry writes. The custom
NT4 server treats such writes as an ownership claim and correctly rejects client publishers for
those topics. Real Studio requests therefore produced no acknowledgement or tuning update.
The submitted test bypassed this boundary by writing directly on the server.

The fix removes robot writes to the two client-owned request topics. Missing topics remain idle;
a nonce without a value is rejected. Metadata, Current, Canonical and acknowledgement topics stay
robot-owned. No NT4 ownership protection or tuning authorization was weakened. The server ownership
rule predates this submission (commit `020302df9d`), as does the manager initialization
(`417d95a1a0`); this is a discovered defect, not a regression introduced by the other agent's test.

| Finding | Correction |
| --- | --- |
| Robot initialization made tuning request topics unwritable by clients. | Stop initializing client proposal topics. Core regressions cover idle startup, nonce without value, actual NT4 publication before/after metadata refresh, and rejection of forged Current/acknowledgement writes. The generated consumer reproduces failure against published 19.1.3 and validates the corrected candidate. |
| Direct `NT4Server.publishTopic` calls for tuning requests bypassed Studio request construction and the receiving wire parser. | A small test fixture captures actual `Nt4OutboundPublisher` registration and binary request bytes using the real `TuningTransport` paths and canonical declaration. Those bytes survive project export/reopen as test resources and enter the actual consumer server's `onMessage` parser. No duplicate wire encoder or permissive apply context. |
| Neutral calibration output was asserted while the joystick was already idle. | First prove that a held full joystick command produces motion, then keep it held through arming, apply, replay and invalid-value checks. Assert the fresh STOP session owns neutral outputs despite that competing command. |
| The first strengthened fixture jumped 600 ms before proving motion, without observing a fresh IMU sample. It reached the real unarmed acknowledgement but failed the competing-motion assertion. | Use continuous 20 ms frames, observed asynchronous IMU samples and fresh STOP lease heartbeats between 500 ms tuning polls. Keep lease and sensor freshness distinct from the tuning poll interval. This is a fixture correction, not another production change. |
| Replay was tested after disarming; policy rejection could complicate diagnosis. | Keep the session armed with an advancing lease during replay. Verify the exact packed acknowledgement remains unchanged, nonce is unchanged, and current/runtime gain stays 3.2. |
| STOP followed an already-neutral calibration hold, and late-request checks asserted only motor power. | Return through the supported calibration-disable path, prove the tuned controller's nonzero response after the rejected requests, and STOP during that output. Deliver an observable late request and poll the retained closed manager as well as the stopped lifecycle; check gain, acknowledgement, processed nonce, output and overlay remain unchanged. |
| One profile was compared with `readText`, while the report claimed all canonical files were byte-identical. Experimental overlay ownership was not checked. | Compare exact bytes for every canonical `.ares` file, excluding `.ares/local`. After close drains the writer, decode the real runtime overlay and assert LOCAL_EXPERIMENTAL authority, canonical base UID and accepted gain; reject later changes. |
| Failure messages embedded every nested XML file, including potentially large passing-test logs, twice in outer reports. | Retain complete nested XML once per operation and point failures to it, alongside the already bounded build output. Retain the captured request frames too. No runtime performance claim. |
| Submitted ledger claims and report overstated the tested boundary; the ledger's self-review scope still named the preceding batch. | Preserve submitted records/report as history, add a correction notice, and bind only this reviewed delta to its actual evidence. |

The original unarmed-rejection, APPLIED, INVALID_VALUE, independent heading oracle, and real
generated runtime checks are preserved and strengthened. Saved canonical gain round trips still
run independently at 2.1 and 1.4. Only the request-topic initialization changes in production;
the generator and schema are unchanged. The shared existing
heading/feedback fixtures remain intact. Library identity advances to the unused 19.1.4 line;
validation uses a unique local prerelease, not an overwritten stable artifact.

The initial version-only bump exposed a local integration mismatch in Studio's bundled-starter
test: the old archives still declared 19.1.3. This was introduced during validation of this fix,
not by the submitted test. Corrected by generating newly versioned archives and updating their
hashes and release pins. Entry-by-entry comparison proves all five archives changed **only**
`release/ares-versions.properties`, replacing the library pin; all other extracted files are byte
identical. The old published bytes remain in Git history/GitHub. Local next-release identities are
Studio **7.0.64**, FTC/FRC starters **19.1.5**, XRP **3.0.64**, Lightbot **3.0.66**, BioBuzz **1.1.6**.
These are reserved local identities; no app release or installer was published.

## Contract and evidence boundaries

`isLiveTuningEnabled` and `enableCalibrationMode()` are supported local OpMode opt-ins, not
network authorization. A new token, advancing lease and STOP command establish the actual robot
context. Typed tuning checks that context; the generated consumer callback commits to Redux.
The acknowledgements bind a result to a nonce, not to an embedded value; Current and Redux gain
assertions independently verify the value.

Requests are: unarmed 3.5/nonce 1 at 1,020 ms; accepted 3.2/nonce 2 at 1,520 ms;
conflicting replay 4.0/nonce 2 at 2,020 ms; invalid
-5.0/nonce 3; late 5.0/nonce 5. Rejection/replay leave the last confirmed gain effective.
The independent P-only oracle is 3.2 * (0 - -0.10) = **+0.32 rad/s**. Tests establish Ki/Kd zero,
fresh observed IMU input, measured Redux heading, zero translation, and all four wheel signs below
saturation before STOP. No fixed wheel-power magnitude is claimed.

The writer/consumer connection is a **staged byte replay across test JVMs**. The socket interface
is a test proxy, while Studio encoding, NT4 parsing, generated robot, manager, policy and controller
are real. SysId token/lease fields use the existing in-process topic seam. Existing Studio request
and wire tests cover UI request ownership and actual loopback transport separately; this is not a
single live GUI-to-robot network session. Direct polling of the closed manager tests its close
fence; it does not simulate delivery over a physically closed network connection.

## Validation

Windows, JDK 17 and discovered Android SDK. The initial baseline used Studio 7.0.63 and FTC starter
19.1.4; final checks use the local identities above. The first run of the
new fixture failed compilation because it referenced an internal library method; the fixture now
uses public NT4 accessors. The next run against published ARESLib **19.1.3** compiled and reproduced
the runtime defect: a correctly encoded unarmed request never reached the manager, leaving its
acknowledgement empty. That failure and the actual Studio frame bytes are retained under
`build/live-tuning-review/fail-before-published-19.1.3/`; it is not an expected passing run.

The library fix is validated with one isolated candidate throughout the consumer matrix. Exact
identity, commands and results are recorded below. No stable artifacts or archives were rebuilt
under existing versions. For the focused Studio/generated-consumer check, with installed
JAVA_HOME/ANDROID_HOME and the same candidate properties:

```text
gradlew.bat :app:test --tests *TuningLiveRequestAuditTest --tests *Nt4TuningRequestWireAuditTest --tests *ConsumerRoundtripSupportTest :app:consumerRoundtripTest --tests *GenericStarterConsumerRoundtripIntegrationTest -ParesVersion=<candidate> -ParesRepository=<absolute-local-repository> --no-parallel --console=plain
```

Evidence lives under this worktree's `build/live-tuning-review/`, app `build/test-results/`, and
`build/consumer-roundtrip-evidence/generic/operation-{3,4}/`. Per-operation evidence includes
Studio-produced request frames, nested JUnit XML, and `TUNING_ACK` / `TUNING_OUTPUT` observations.
Operations 1/2 are generation; 3/4 are the two saved-gain consumer builds. Source-policy and scoped
ledger checks are recorded with the test results below. The existing app CI scope already runs
these unit and consumer checks; no CI redesign or remote job was performed.

Local fix commit: `dbc27bdbb86f16bba62609cef15f45530223bc88`; bundled dependency alignment: `79562548a`.
Library source tree: `feedf38a5d4a009a5505e7b89f15525fec6b54ea`.
Candidate: **`19.1.4-rc.live-tuning.feedf38a5d4a.1`**.
Repository on this machine:
`file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-live-tuning/ARESLib-Kotlin/build/release-repository`.
The candidate directory must be retained while this unpublished fix is used. A normal published
19.1.4 dependency lookup will fail until a separate authorized release publishes that version.
Use the two explicit candidate properties for local builds; do not fall back to the defective 19.1.3.

`candidate-identity.json` in the evidence directory binds 82 candidate JAR/POM/module files by
SHA-256. The full library `test apiCheck publishReleaseValidation --no-parallel` completed
successfully: **3,092 tests, zero failures/errors/skips**, with normal Gradle cache reuse for
unchanged tasks. Publication was only to the isolated local repository.

The final consumer script and logs are retained in `build/live-tuning-review/`. All consumer commands
use the same candidate and absolute repository, `--no-parallel --console=plain`:

| Product | Tasks | Result |
| --- | --- | --- |
| Studio unit/service suites | `:shared:test :gateway:test :app:test` | Passed: 37 shared, 18 gateway, 2,682 app tests; six app checks explicitly skipped |
| Studio generated consumers | `:app:consumerRoundtripTest`; corrected generic case rerun with `--tests '*GenericStarterConsumerRoundtripIntegrationTest'` | Passed across retained runs: generic and BioBuzz round trips at both saved gains, plus cancellation and write-failure recovery (four outer tests, zero skips) |
| FTC starter and FTC season (each) | `generateAresProject :TeamCode:verifyAresProject :TeamCode:testDebugUnitTest :simulator:test :TeamCode:assembleDebug` | Passed: 17 starter and 190 season tests, zero failures/errors/skips; both debug APKs built locally |
| FRC starter and FRC season (each) | `test` | Passed: 206 starter and 306 season tests, zero failures/errors/skips |
| Source policy | `scripts/verify-monorepo-policy.ps1` | Passed; versions, exact source tree, bundled hashes, links, guidance and size ratchet |

Each generic saved-gain build ran five injected simulator cases, including the live transaction;
each BioBuzz build ran four. Both generic runs observed `omega=0.32000000000000006` after accepted
gain 3.2, then passed active-output STOP, overlay drain and late-request fences. The generic rerun
log is `generic-candidate-final.log`; the passing BioBuzz/recovery outer XML is preserved under
`full-studio-before-fixture-correction/` (that same earlier batch also records the subsequently
corrected generic fixture failure). Current nested reports retain each operation separately.

Six app skips are explicit opt-ins: three separate template-build workflows, native file-chooser
UI, the dedicated performance-report check, and physical NT4 validation. They are not counted as
passes. The selected `consumerRoundtripTest` cases are non-skipping real wrapper builds; no native
UI, performance benchmark, or physical validation is inferred from their success.

`final-results.json` records the parsed JUnit totals. The audit ledger binds only this reviewed
delta and preserves prior records/claims; twenty current scoped fingerprints, including its
semantic self-fingerprint, are checked. Successful suite execution does not renew whole-file
review claims for unchanged source. No known high-impact defect remains in the selected live
transaction/close scope after these corrections.

The intermediate Studio run reported 2,682 tests, one failed and six skipped; the failure was the
archive dependency mismatch above. It is preserved under `starter-pin-failure/` and is superseded
by the corrected results in the table. The first candidate generic-consumer run then reached the
expected unarmed acknowledgement but failed the fixture's competing-motion assertion, corrected
as described above; `fixture-timing-failure/` preserves that evidence. Unchanged passing consumer
cases are retained, and only the affected generic case is rerun after this fixture correction.
`starter-archive-comparison.json` records exact payload comparison and
new hashes. No compatibility check was relaxed to accept mismatched dependencies.

Changed-path classification selects the library candidate and all consumers for the shared runtime
and release-pin change. Existing Studio scope includes the strengthened consumer regression;
workflow changes only align versions, archive URLs and hashes. No CI redesign or GitHub job was performed.

## Remaining limits and next action

No physical controller, native Studio window, radio/network timing, or robot-loop performance
measurement was available or claimed. Test outcomes establish only the listed desktop/simulator
boundaries. No broad file-audit completion is implied. Publication and native packaging remain a
separate protected release operation; this checkpoint is local.

Fresh-session behavior does not establish lease-expiry ordering in the generated consumer. The
[next-agent prompt](NEXT_TUNING_LEASE_RECOVERY_PROMPT.md) limits further work to expiry, rejection
and explicit rearming, reusing existing tests before adding integration coverage. Other policies,
mechanisms and speculative improvements remain deferred. Do not begin that work automatically.
