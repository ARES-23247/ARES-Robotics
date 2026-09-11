# FRC native extraction audit - pass 130

Continued the partial build-script review at native extraction, test library-path
configuration and packaged JAR contents. The build script remains partial for
deployment, remote offset fetching, cross-platform packaging and generator caching.

## Confirmed defect and fix

`extractTestNatives` used a Copy task. Copy updates inputs but does not remove old
outputs, so a dependency change can leave obsolete DLLs available to tests. Added
an audit-owned sentinel DLL to the old output directory and explicitly reran the
task: it remained. This reproduces stale-output retention without modifying any
real dependency or loading the sentinel.

The task now uses Sync and owns `build/jni/test-release`. The test JVM's native
library path and environment point to the same dedicated directory. Isolating the
directory prevents synchronization from removing GradleRIO-owned outputs in its
existing native directories.

Placed an equivalent sentinel in the new directory before the task ran. Sync
removed it. All 47 real extracted native files have exactly the same names and
SHA-256 hashes as before. Removed only the audit-owned old sentinel afterward;
existing GradleRIO files remain untouched. No binary version or dependency changed.

## Packaged runtime inspection

Inspected the current 18,132,908-byte JAR: 9,858 entries, 42,535,442 uncompressed
bytes, and the expected `org.aresfirst.marvin.Main` manifest entry. Its desktop
GLFW/LWJGL natives total 1,065,472 uncompressed bytes. Repeated entries are license,
notice, index, native-hash and module-descriptor metadata; there are no duplicate
application class entries. Exact names/counts and the inspected JAR hash are saved.

Desktop native contents may be unnecessary on the roboRIO, but removing them
requires validating both deployed and desktop packaging contracts. No such claim
or removal is made here. Duplicate license/notice entries also need a preservation
strategy before changing packaging; blindly excluding metadata would lose notices.

## Validation

Generated-project verification and all 299 FRC tests passed with the dedicated
native directory; this run executed the tests. Policy, documentation links and
staged whitespace checks passed. Before/after stale-file results, all native file
hashes, JAR inspection, test XML and logs are retained under
`ARESLib-Kotlin/build/audit-pass130-verified-evidence/`.

This is a test-build correctness fix. It does not claim robot loop speedup or
measured deployment size reduction. No new library candidate, native binary,
physical hardware run, rendered GUI, deployment, push, merge or release occurred.
