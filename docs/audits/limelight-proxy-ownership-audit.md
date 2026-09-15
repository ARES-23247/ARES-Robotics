# Limelight proxy startup and connection ownership audit

Pass 66, 2026-09-10. Sources `b3478ec3` and `66d6432e`, local candidate `17.0.3-rc.4323b89fc645`,
bound to the exact library tree in `release/ares-source-tree.txt`. This pass addresses proxy
configuration ownership, listener startup, bounded worker queues and connection cleanup.

## Reproduced defects and fixes

The proxy retained the caller's camera list after validating it. Clearing that list before
startup caused an invalid zero-thread pool; adding cameras could bypass the constructor's
camera limit. It now checks the size before making a private membership snapshot. Each local
and remote eight-port interval must fit within 1..65535, local intervals must not overlap,
and camera targets must not be blank. Offset validation precedes addition and subtraction,
so extreme Int values cannot wrap into apparently valid ports.

Listeners previously bound inside asynchronous workers, swallowing bind failures. Startup
could return success with some tunnels missing, and the automatic startup facade could then
report the proxy active. All listeners now bind synchronously before any acceptor starts.
A collision closes already acquired listeners and propagates the error; the same proxy can
start successfully after the conflict is removed. The public constructor and lifecycle
signatures remain unchanged.

Each connection previously released its permits only after both copy workers finished.
Queued workers returned by shutdownNow never executed their finally blocks. The same leak
occurred when the second submission was rejected while the first remained queued. Connection
counts and per-camera/global permits could therefore remain occupied after shutdown.

Each direction now owns an atomic pending/running/complete state. Aborting a connection
releases pending directions immediately; running directions keep their ownership until their
finally blocks exit. The second completed direction releases the connection's permits exactly
once. A canceled direction later dequeued cannot run or release again. This also handles EOF
in one direction while its peer is still queued. The additional release flag and separate
pre-start abort wrapper became redundant and were removed.

Copy directions are submitted directly with execute, avoiding unused FutureTask wrappers.
The pool now has an explicit queue capacity of twice the global connection limit. This bounds
canceled tasks waiting in the queue as well as active work. Socket registration and listener
shutdown share a short lock; connection setup stays outside that lock so stop can close a
connecting socket. After stopping all listeners, stop closes registered connections before
waiting for the pools. Network copying still allocates its per-worker 8KiB buffer and preserves
the existing bidirectional close-on-EOF behavior.

## Evidence

The baseline eight-test suite had six failures: caller mutation, hidden bind failure, invalid
port intervals, discarded queued workers, partial submission rejection, and pending peer
cleanup after EOF. The two existing loopback cap/restart tests passed. Baseline XML/logs are
retained in `ARESLib-Kotlin/build/audit-pass66-before-evidence` and `audit-pass66-before.log`.

The final focused suite passes 11 tests without failures, errors or skips: nine new methods
plus the two original integration tests. Additional cases retain the permit of a deliberately
blocked running worker, verify the actual worker queue capacity and idempotent start, and send
a deterministic 100,003-byte payload through both directions of a real loopback proxy (seed
6601). Exact byte comparisons span multiple copy buffers. Queue/discard cases use test-only
reflection and an executor that holds tasks; no runtime injection hooks were introduced.

Making bind failures visible also exposed fixture port overlap: selecting a free proxy range
before opening the upstream listener allowed the latter to claim a port in that range. The
fixture now reserves the upstream listener first, then selects the proxy range. Its upstream
base is chosen to keep the entire configured eight-port interval valid. Retry deadlines now
compare bounded nanosecond differences rather than raw signed timestamps.

Focused Kover covers the main proxy class at 75/108 lines, 52/86 branches and 9/12 methods;
ProxiedConnection at 27/27 lines, 2/2 branches and 5/5 methods; CopyDirection at 15/15 lines,
7/8 branches and 3/3 methods; TCPForwarder at 23/26 lines, 7/10 branches and 4/4 methods.
The focused XML and FTC coverage report are retained in `audit-pass66-focused-evidence`.
The test file was reviewed in full. Proxy source remains partially credited because discovery
and clock/lifecycle edge cases below need further work; this pass does not credit AutoStart.

The initial full gate rejected two accidentally public constant fields generated from the
private companion. Explicitly private constants restore the unchanged API; the focused API
check passes. The initial `17.0.3-rc.37eb279d0c1b` candidate was not consumed. Its failure log
is retained as `audit-pass66-library-initial-api.log`; the final source uses a new candidate.
The final full library gate passed with 1,633 test results without failures, errors or skips,
API checks, core/FTC Kover reports and isolated candidate publication in 33s. FTC hardware and
simulator tests reran; unaffected test tasks, including core, reused up-to-date results. Source policy passed,
including exact source identity, archives, agent guidance and links in 227 current documents
(38 historical exclusions). Candidate consumers passed in dependency order: FTC 109 tests,
FRC 134, FTC starter 14 and FRC starter 34, generated-project verification and both FTC
application assemblies. FTC, FRC and FTC starter tests reran; the FRC starter test task was
up-to-date. Studio passed in 17s; shared/gateway/app test tasks were up-to-date,
retaining 1,779 passes and six opt-in skips. Dashboard smoke 56 and performance one reran and
passed, along with coverage, release alignment and production-file-size checks. Final XML,
core/FTC Kover, logs and SHA-256 manifests are retained in
`ARESLib-Kotlin/build/audit-pass66-verified-evidence`. Six opt-in skips remain limitations.

## Remaining scope and limits

The existing USB-subnet discovery behavior still scans candidate addresses and substitutes the
discovered address for configured 172.29.0.* targets. That behavior, interruption, discovery
socket lifetime and multi-camera address selection need a separate pass. The auto-start facade
was inspected as a caller, but its lifecycle/status implementation was not directly tested.

Shutdown uses a shared RobotClock deadline. A frozen or rewound replay clock does not impose
the same wall-time budget across both executor waits; the KDoc now states its live-clock
requirement. Discovery has the same clock-domain concern. These paths were not changed to
bypass RobotClock. Read timeouts do not impose a write timeout, and this pass makes no hard
real-time promise about socket close, thread scheduling or operating-system calls.

No physical Limelight camera, Control Hub, live Studio window, USB-subnet discovery, hosted CI
or robot-loop latency was tested. The full-monorepo audit goal remains active.
