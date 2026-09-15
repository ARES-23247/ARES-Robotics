# Log server resources, requests and dashboard audit

Pass 230 reviewed the server's request, lifecycle and dashboard paths. `LogManagerServer.kt`
remains partial until filesystem name-alias behavior has separate lookup-consistency checks.
The new `LogRequestLimiter.kt` and `LogServerWorkers.kt`, four JVM test classes, and the new
browser regression script were fully reviewed.
The API catalog retains its reviewed status after checking its two new inherited-method
overrides. The candidate job in Monorepo CI, CloudViewModel's robot-log request mapping,
and RobotLogIngestionService's download adapter are partial reviews, not whole-file coverage.
This pass does not repeat the retention and event-cleaning audit. No WPILib code changed.

## Correctness and resource fixes

The dashboard interpolated filenames into HTML, inline JavaScript and CSS selectors. Ordinary
apostrophes or selector punctuation could break deletion; a filename containing markup created
an image element instead of displaying literal text. Rows now use DOM elements, text content
and closures capturing the intended basename and button. The browser tests exercise apostrophes,
brackets, angle brackets and executable-markup-shaped text without contacting a robot. The
[DOM textContent API](https://developer.mozilla.org/en-US/docs/Web/API/Node/textContent) supports
inserting those values as text without parsing them as HTML.

Only the latest refresh may update the listing. Failed HTTP responses no longer masquerade
as an empty directory. A rejected cached delete token is cleared so a subsequent attempt can
prompt for its replacement; button state is restored on failure or cancellation. Binary byte
formatting now has correct KiB through EiB units, including the previously missing terabyte
range. Browser checks verify every power-of-1024 boundary from one byte through one EiB.
The remote font dependency was removed so the dashboard's assets remain local.

The old client-limit map grew indefinitely. A synchronized, bounded table now tracks at most
256 clients, expiring clients idle for one minute rather than evicting active budgets. Ten initial
credits and one credit per 100 ms preserve the intended request rate. Integer nanosecond credits
avoid fractional accumulation error, handle ordinary signed nanoTime wrap, and preserve earned
credits during replay rewinds. Unknown clients are rejected while the tracking table is full.

Request-rate checks alone did not bound connection resources. The server now installs four
workers with sixteen queued connections, closes excess connections, and closes active/queued
sockets during shutdown. Core workers time out when idle. Lifecycle overrides serialize start
and stop, preserve idempotent starts, and close an earlier worker pool before replacing a failed
listener. Tests exercise saturation, subsequent requests, concurrent starts, normal restart and
restart after a controlled accept-listener failure. The implementation uses NanoHTTPD's
[pluggable AsyncRunner and lifecycle hooks](https://raw.githubusercontent.com/NanoHttpd/nanohttpd/nanohttpd-project-2.3.1/core/src/main/java/fi/iki/elonen/NanoHTTPD.java);
the upstream library was not edited.

Discovery now uses the same completed-file eligibility rules as requests. It rejects inaccessible
basename spellings, symbolic links, and canonical targets outside the permitted direct-child
roots or in the active/abandoned namespace. Downloads also open without following a final
symbolic link. Canonical-alias checks have deterministic fixtures; ordinary file reads, sockets
and shutdown were exercised on Windows. Concurrent replacement of directory ancestors and
physical Android/roboRIO filesystem behavior are not certified by these checks.

Requests address a basename. When both roots contain the exact same spelling, discovery reports the
unsynced copy that download actually returns, instead of advertising conflicting sizes/content.
Existing basename deletion semantics still remove both completed copies; the dashboard's
confirmation states this. Active reservations remain excluded. Multiple `file` parameters are
rejected before selection/deletion. Control characters are denied before filesystem resolution,
and binary log formats retain their bytes with a binary media type.

Authentication still precedes deletion-body parsing. Supported bodies have a 4096-byte ceiling;
invalid or chunked framing is rejected. A real HTTP form-body test preserves ordinary encoded
form compatibility. Deletion reports an error if a completed copy cannot be removed rather than
reporting complete success. Filesystem deletion across two roots is not transactional.

## Efficiency and compatibility

The dashboard HTML and UTF-8 bytes are cached, each response owns a fresh read stream,
date formatting is constructed once per listing, configured token bytes are encoded once per
configuration change, and redundant existence/path work was consolidated. Responses discourage
caching stale mutable listings. File downloads remain streamed. These are bounded-resource and
redundant-work improvements, not a measured robot loop-time or throughput claim.

`start(int, boolean)` and `stop()` now explicitly override inherited NanoHTTPD methods. The
API catalog records those declarations; existing public signatures and the log metadata schema
remain available. Consumer request mappings were source-traced before choosing basename
precedence and preserving deletion of both completed copies.

The nine browser tests are included in the existing library/full CI scope's candidate job, using
Node 24 and Playwright 1.62.1. The package version was verified against the public npm registry.
The workflow uses [setup-node's supported configuration](https://github.com/actions/setup-node)
and [Playwright request interception](https://playwright.dev/docs/api/class-page#page-route).
Workflow YAML parsed locally and the browser command passed locally in headless Edge with
intercepted API data. A rendered screenshot was inspected. GitHub Actions itself was not run
remotely, and the screenshot is not a native Studio-window or physical robot result.

## Evidence and validation

Before runtime fixes, fourteen JVM cases ran with ten failures; all nine initial browser cases
failed. These are failing scenarios, not nineteen distinct production bugs. The final focused
run passed 28 JVM checks and nine browser checks. This adds 24 JVM test methods and nine
browser cases; four existing endpoint methods remain, and two new lifecycle methods extend
that existing class. Silent early returns when test port 5002 cannot bind were replaced with
an explicit test failure. Later regressions also cover body framing, real form parsing, connection
saturation, exact refill boundaries, signed wrap and old-worker cleanup after listener failure.
There were no fixture compilation failures.

Source commit `93f1ed7b38ab75be98bb4cb4a1bf12a4deb38c84` binds library tree
`6c25c3bf76a088a7e85b402153462fe8de6ff047`. Candidate
`17.0.37-rc.6c25c3bf76a0` was validated locally. Versions are ARES/FTC/FRC starters
17.0.37, Studio 7.0.37 and XRP/Lightbot 3.0.36.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,806 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,043 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |
| Headless browser fixture checks | 9 | 0 |

The suites account for 5,647 passing results, zero failures/errors and six unchanged Studio
opt-in skips. These cover three starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. Gradle results may be executed,
up-to-date or restored from cache; focused results are not counted twice.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generated-project
checks passed. All 410 candidate file hashes were reverified after consumers finished.
Monorepo policy passed, including source/version/archive identity, shared guidance and links
in 392 current documents, with 38 explicitly historical records skipped. Four normalized
starter archive comparisons differ only in release version properties.

## Coverage and limitations

The ledger accounts for 3,016 tracked files: 1,314 fully reviewed, 179 partially reviewed
and 1,523 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable coverage.

No physical loop-time, actuator-response, hardware-in-loop or native Studio-window validation
was performed. The browser fixture and real HTTP/socket tests establish different parts of the
contract; they are not a live robot browser session. Bounded concurrency does not promise a
hard deadline for filesystem/network operations. Large listings still require scanning and sorting
the local directory. Broader desktop ingestion and cloud lifecycle behavior remains partial.
Cross-root names differing by case or filesystem normalization need further checks: directory
lookup may consider two spellings equivalent while the listing's string keys distinguish them.
All changes, candidates and evidence are local; nothing was pushed, merged or remotely released.

Evidence is in `ARESLib-Kotlin/build/audit-pass230-verified-evidence/`: baseline/focused JVM XML,
browser logs and screenshot, full validation logs, candidate hashes and normalized ZIP comparisons.
