# Studio project traversal and linked image paths audit - pass 165

Closed the traversal scope left open in pass 164. The source probe used recursive File
walking without directory identity tracking, and XRP's generated/extension trees were
then scanned again when probing the project root. It now uses an explicit pending stack
and a real-path set shared across candidate roots. Linked source directories remain
usable, ancestor cycles terminate, and aliases/overlapping roots are scanned once per
call. Missing/unreadable directories are skipped when metadata resolution fails or
directory listing returns null. No latency benchmark or permission-ACL experiment is claimed.

Added real filesystem tests using temporary Windows junctions on this host (symbolic
links on non-Windows hosts): linked FRC source, an ancestor cycle with no source, source
discovery through that link, overlapping XRP roots and ordinary root Python discovery.
Tests remove each created link before their temporary directory cleanup. Native link
creation and bounded traversal actually ran; no skipped-link-test result is claimed.

A separate image-containment regression found that Windows File.canonicalFile accepted
an asset child junction leading outside the asset root. This test failed before the image
fix. Image resolution now normalizes the requested path, resolves existing ancestors
with toRealPath, then appends not-yet-created components before checking path containment.
Thus both existing and future image paths under an escaping junction are rejected.
The missing-prefix/parent-segment case is normalized before resolution so it cannot hide
an existing escaping link. A linked asset root intentionally establishes its real directory
as the asset boundary and still supports new images inside that directory.

ProjectLayout and its complete test file are now reviewed for a stable local filesystem:
league source languages/roots, directory traversal, asset layout and real-path image
containment. This remains a source-presence probe rather than build validation. The code
resolves paths before later image opening; it does not provide atomic protection against
an adversary changing filesystem links between those operations. Filesystem race and
ACL fault injection were not performed, and POSIX link behavior was not executed on this
Windows host. These limitations do not undo the observed junction fix.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass165-verified-evidence/`,
including the before-image-fix failure. Candidate remains `17.0.3-rc.100852e472fb`.
No external service, simulator, rendered UI or physical robot operation occurred.

Validation: all ten focused project/image tests pass. The full app suite reports 1,825 tests: 1,819 passed and 6 opt-in skips, with no failures or errors. The new link tests ran without skips. Unchanged shared/gateway suites were not rerun. Monorepo policy, documentation links and staged whitespace checks pass.
