# Indicator task timing and sequence duration audit

Pass 64, 2026-09-10. Source `7728c07e`, local candidate `17.0.3-rc.94c8d572d10f`,
bound to the exact library tree in `release/ares-source-tree.txt`. This pass reviews both
indicator tasks and the complete RobotSequence builder, concentrating on lifecycle, timing,
duration quantization and unnecessary work.

## Confirmed defects and changes

Both indicator initializers skipped `Task.initialize`, leaving status and watchdog origins
uninitialized. Blink also skipped default execution and end, bypassing elapsed timeout checks,
terminal status and completion callbacks. These overrides now honor the default lifecycle.
Blink refuses phase output after failure, cancellation or completion and fails on negative
elapsed time even without an optional configured timeout. End preserves the authored behavior
of restoring color A on normal or interrupted cleanup; these tasks return Redux actions and
do not directly write device outputs.

Blink divided its period by two and alternated on that truncated half-period. A five-millisecond
period therefore became four milliseconds; the largest odd Long period also switched color at
the wrong boundary. Phase now uses elapsed modulo the full period, with the first color occupying
`period / 2 + period % 2` milliseconds. This preserves the exact full period and avoids overflow
from adding one to Long.MAX_VALUE. The extra tick in an odd period belongs to color A. Periods
below two milliseconds are rejected because both colors need a representable clock tick.
Direct constructors also reject negative blink duration and blank names.

Identical hardware color positions no longer generate alternating redundant commands, including
the PURPLE/VIOLET aliases. Initialization, changed-color and terminal actions still allocate
immutable values with the current RobotClock timestamp. Caching those actions would make event
timestamps stale, so this pass does not trade timestamp correctness for a zero-allocation claim.
The old blink documentation incorrectly equated a 500ms full period with 1Hz; it now states 2Hz
and distinguishes unchanged-phase behavior from phase-change allocation. The instant-color
example uses the current sequence DSL and its duplicate obsolete class comment was removed.

The builder truncated fractional wait, distance-fallback and blink durations, allowing them to
finish before the requested duration. Those inclusive completion boundaries now round up to
whole milliseconds. Full blink periods also round up, then must meet the two-millisecond minimum.
The strict `waitUntil(timeout)` threshold deliberately continues rounding down: for example,
elapsed=2 is the first whole-millisecond tick strictly greater than 1.5ms, so a stored strict
threshold of 1ms preserves that behavior. Non-finite and negative durations remain invalid.
Ceiling arithmetic uses checked addition if a fractional remainder exists.

Two intermediate `toList` copies were removed from the builder because all group constructors
now own immutable membership snapshots. A captured builder mutated after construction still
cannot alter the built routine. Indicator name validation now lives in the task constructors,
covering direct and builder use without checking the same name twice.

## Evidence

The initial 15 tests ran against pass 63 source: 12 failed. Fresh action timestamps, strict
fractional-timeout semantics and invalid-duration controls passed. Baseline log/XML are retained
in `ARESLib-Kotlin/build/audit-pass64-before.log` and `audit-pass64-before-evidence`.

The corrected focused suite passed 150 tests without failures/errors/skips, including 23 new
methods: 13 indicator tests, six duration tests and four builder integration tests. Cases cover
watchdog origins, elapsed timeout and callback delivery, terminal status, exact odd/maximal periods,
negative elapsed, aliases, fresh independent actions, reinitialization, fractional/zero/large
durations, nested condition/wait/deadline execution, path-task construction, invalid builders and
snapshot ownership. An independent BigInteger oracle checks 200 deterministic random periods
at five boundary/extreme elapsed values each (seed 6401). Added integration tests were not all
individually run against the old source.

Focused Kover: BlinkIndicatorTask covers 32/32 executable lines, 32/32 branches and 6/6 methods;
SetIndicatorColorTask covers 10/10 lines, 2/2 branches and 3/3 methods. RobotSequence covers 41/41
lines, 19/20 branches and 24/24 methods, plus its top-level builder's one line and method. All
three source files and three new test files were read in full. This file credit does not extend
to the implementation of their delegated path planner, named-command registry or hardware IO.

The warmed allocation probe measured 80 bytes over 10,000 unchanged-phase updates, within its
256-byte regression budget. It skips explicitly when the JVM allocation counter is unavailable.
This is not a strict zero-byte result, and transition actions remain allocating. Focused XML/Kover
are retained in `ARESLib-Kotlin/build/audit-pass64-focused-evidence`. One expanded build reported
1h32m wall time for a successful gate; its cause was not established. The final expanded gate
passed in 8s. Build wall time is not evidence of robot-loop latency.

The same unchanged-phase probe measured zero bytes in the full run. The full library gate
passed 1,610 tests without failures/errors/skips, API compatibility, core
Kover and isolated candidate publication in 1m31s. Source policy passed, including source identity,
archive integrity, agent guidance and links in 225 current documents (38 historical exclusions).
Candidate consumers passed in dependency order: FTC109, FRC134, FTCstarter14 and FRCstarter34
tests, generated-project verification and both FTC application assemblies. Studio passed in
3m25s with shared/gateway/app tests rerun: 1,779 passes and six opt-in skips. Dashboard smoke56
and performance1 reran and passed, along with coverage, release alignment and production-file-size
checks. Final XML, core Kover, logs and SHA-256 manifests are retained in
`ARESLib-Kotlin/build/audit-pass64-verified-evidence`. Six opt-in skips remain limitations.

## Limits

Milliseconds remain the task time resolution. The period and inclusive waits can be rounded up
by less than one millisecond, and odd periods have unequal half-cycle lengths by one tick. A
blocked or slow caller can miss phase changes; the task emits the phase corresponding to the
supplied elapsed time, not every intermediate color. No scheduler thread or busy wait was added.
Lifecycle calls remain synchronous and owned by the control loop. This pass does not introduce
support for concurrent arbitrary lifecycle calls or bypass broader executor/group limitations.

No physical indicator, live Studio window, hosted CI, electrical behavior or hardware-loop jitter
was tested. The full-monorepo audit goal remains active.
