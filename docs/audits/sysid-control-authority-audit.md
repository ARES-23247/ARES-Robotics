# SysId control authority audit

Pass 249 reviews the active Studio SysId arm/acknowledgement boundary, following
[profile persistence](driver-profile-persistence-audit.md). Robot-side FTC calibration
authorization was traced read-only; this pass does not change ARESLib or claim a hardware test.

Studio source tree: `adfa01092971e8e0d1416ef35e63fcdccee1ead3`.
Unchanged ARESLib source tree: `526a048d8dfb1092e89be95c8e906e9fbc516027`.
Validation candidate: `17.0.42-rc.526a048d8dfb`.

## Reproduced findings

Thirteen isolated regression cases failed against the original implementation:

- Nonbinary, NaN and text-backed numeric values could enable calibration mode.
- Unsolicited armed telemetry could set the local armed state without an owned lease.
- Replay could advertise live capabilities; disconnect and target changes retained old mode
  observations, and queued old-target frames could restore them.
- Command authorization trusted mutable arm flags without checking an active local lease,
  calibration mode or replay state.
- Disarm waited for a suspending STOP write before revoking local state. An arm request that
  resumed after connection loss could still publish the token/lease and restart renewal.

A subsequent held-transport regression also reproduced a late START completion restoring
`isRoutineRunning` after operator STOP. The other 25 cases in that intermediate run passed.

The robot independently requires a started tuning OpMode, STOP, a fresh token and lease, and
applies its own lease timeout and hardware gates. These desktop defects demonstrate incorrect
local authorization and command publication; they do not establish that a physical robot moved.

## Corrected boundary

The view model rejects control observations unless connected, outside replay, and still the
newest notified frame in TelemetryStore. Disconnect, replay transitions and target/socket
generation changes invalidate mode, capabilities, leases and old collected analysis. It checks
identity again when processing intents, so queued collector work cannot authorize a newer target.
Only an exact numeric 1 with no text payload enables a boolean control observation.

Socket generation is separate from clock-ready tuning identity. Initial clock synchronization
must not discard valid capability observations received on the same socket. A reconnection must
invalidate them even if a Boolean connection flow conflates a brief disconnect. Existing
clock-ready identity still guards the active lease and actual publication readiness.

Only four SysId control topics enter acknowledgement processing; unrelated telemetry returns
immediately. Identity objects are created only when the identity changes, avoiding repeated
allocations in the steady-state observer. Existing generator tests now mock their unrelated
database/network dependencies rather than constructing DuckDB solely to record fake commands.

The signal generator owns the arm attempt and renewable lease. It rechecks the same attempt and
connection after each suspending handshake write; late completion cannot restore a revoked
attempt. Commands require the current local lease, valid mode and acknowledgement for FTC.
FRC retains its robot-side Test/health authorization and does not acquire an FTC network lease.
Disarm revokes local authority before network writes. Publication/renewal exceptions clear the
lease and surface an error. An acknowledgement arriving during the final publish is reconciled
after successful lease establishment; a retained pre-arm frame cannot acknowledge a new attempt.
Routine/calibration completions carry a motion generation and connection/target identity, so
old success or failure cannot overwrite a stop or newer request. Operator stop invalidates old
collected analysis, uses one STOP/revocation sequence, and reports rejected publication while
keeping local authorization revoked. Feedback-triggered STOP failure does not kill the observer.

The legacy boolean Armed topic does not echo a token. It establishes a robot observation during
the local lease, not cryptographic or token-specific acknowledgement. UI wording now reflects
that limitation. Server-side token/lease validation remains authoritative and unchanged.

## Validation

Focused validation passed 66 tests: 31 new acknowledgement/lease/race regressions,
30 existing live-collection cases, three generator cases and two capability cases. The original
13 failing methods and additional late-START failure all pass in focused and full validation.
Positive cases retain normal FTC arming/renewal and FRC robot-side authorization. Controlled
tests establish one operator STOP publication and zero identity reads for unrelated telemetry.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,437 | 6 |

Full Studio validation has 2,486 passing results, zero failures/errors and six unchanged opt-in
skips. Focused results are not counted twice. All 410 unchanged library candidate file hashes
were rechecked. Monorepo policy passed, including 411 current-document links and 38 historical
records. The evidence-local initial heap setting remains 32 MiB with one worker; maximum heaps,
assertions and shared build settings are unchanged.

## Coverage and remaining scope

The ledger accounts for 3,081 tracked files: 1,409 reviewed, 195 partially reviewed and
1,477 pending, with zero stale or orphaned records. There are 1,672 files still requiring full
review completion. The existing three-case generator test file is now fully reviewed; the new
regression file and report are also included. The four edited runtime files retain explicit
partial scope rather than claiming their unrelated lifecycle and proposal paths are complete.

The full monorepo goal remains active. SysId simulation preview ownership, dormant historical
session/file intents, remaining calibration/proposal behavior and broader lifecycle/thread
ownership require independent review. No rendered-window, hardware/HIL, robot-loop latency,
physical timing improvement, library/version/archive change, remote CI, push, merge, release
or deployment is claimed. Local baseline and validation evidence is under
`ARESLib-Kotlin/build/audit-pass249-verified-evidence/`.
