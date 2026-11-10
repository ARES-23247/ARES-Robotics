# Database initialization cleanup and official FTC SDK compatibility

Date: 2026-11-09. Branch: `codex/framework-lifecycle-audit-20261109`.
Base: `a9526e5b9` (the preceding shutdown/import/template correction).

This bounded continuation fixes a database constructor resource leak and answers the question
about the latest official FIRST code. It does not certify the whole framework or the original
checkout's pending changes. Those changes and other tasks' processes were preserved; edits and
runtime validation use the isolated audit worktree.

| Finding | Result |
|---|---|
| Failed database initialization left native connections alive, preventing recovery | Fixed; failure-first regressions and native recovery checks pass |
| Latest official FTC SDK support | Incomplete: both FTC consumers pin 11.1.0; official release is 12.0 |
| VisionPortal real-SDK compatibility | Open: legacy detection ID access breaks with 12.0; mock-built binary also has getter/field mismatches |

## Database correction

`DatabaseService` opens a persistent connection and duplicates readers, configures resources and
extensions, opens live connections, then initializes repositories and schema. An exception during
any of these steps previously escaped without closing acquired connections. The registry lazy
value remains uninitialized after a throwing constructor, so normal registry shutdown cannot
dispose that partial object.

The constructor now records each acquired connection, cancels its checkpoint scope on failure,
and closes the connections in reverse acquisition order. Closing one connection unsuccessfully
does not skip the others: cleanup failures are attached to the original error as suppressed
exceptions. Successful startup and normal disposal retain their existing ordering and behavior.

Five new regressions cover reader duplication, extension preparation, late schema preparation,
multiple cleanup errors, and a real DuckDB recovery attempt. All five failed against unchanged
production code. In the real case, opening with a `1KB` memory limit failed as expected, but a
second constructor at the same path with `256MB` also failed because the abandoned native
instance retained the tiny limit. With cleanup applied, the second constructor succeeds in the
same JVM, writes/reads a session, closes, and permits deletion of the database file.

The focused database/import/bootstrap/desktop-lifecycle run passed **69 tests in 14 suites**, with
zero failures, errors, or skips. The bulk-import responsiveness regressions remain green; this
patch does not introduce a new performance measurement or replace the prior workload evidence.

**Final full Studio app suite: 2,789 tests, zero failures/errors, 6 opt-in skips (2,783 passed).**

Dependency mode throughout compile, test, and native run:

```text
aresVersion=19.1.5-rc.acceptance.20260925.1
aresRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/ARESLib-Kotlin/build/release-repository
canonical ARES pin=19.1.5; Studio=7.0.65
library source tree=534a2778c52356641621ceaea71532a63219b8e2
Gradle: --offline --no-parallel
```

The initial reproducer used `:app:test --tests
com.ares.analytics.service.DatabaseInitializationCleanupTest`; the final full check used
`:app:test`. Retained local logs: `build-initialization-baseline.log`,
`build-initialization-fixed.log`, and `build-initialization-full.log`.
JUnit snapshots are under `build/initialization-cleanup/{baseline,fixed,full}/`.

## Rendered recovery and shutdown

Two runs used `-PskipKill` and the same isolated
`-ParesIsolatedDesktopHome=build/initialization-native-home`:

1. With `ARES_DUCKDB_MEMORY_LIMIT=1KB`, the exact window rendered the database startup error and
   its Try again control. It reached the settled presentation diagnostic and answered the owned
   loopback control port. Graceful close exited PID 21724.
2. With that override cleared, the same data path opened normally and rendered the Studio setup
   screen. It also reached settled presentation, answered the control port, and closed gracefully,
   exiting PID 52236.

Both runs finished with `BUILD SUCCESSFUL`, disposed the window, removed their runtime snapshots,
and left their loopback ports rejecting connections. There were no crash logs, database-closed
errors, or watchdog halts. The PNGs were inspected. Native captures and process evidence remain
under `build/initialization-cleanup/`; launch logs are `build-initialization-native-{error,ready}.log`.
The native runs demonstrate recovery across processes. The real DuckDB regression separately
demonstrates retry within one process; the native Try again button was not used to claim that.

## Official FTC SDK comparison: support remains incomplete

On the audit date, FIRST's [latest official release is v12.0](https://github.com/FIRST-Tech-Challenge/FtcRobotController/releases/tag/v12.0),
for the 2026/2027 season. Its [dependency file](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v12.0/build.dependencies.gradle)
pins the SDK artifacts at `12.0.0`. Both `ARES-FTC/build.dependencies.gradle` and
`ARES-FTC-Starter/build.dependencies.gradle` still pin all eight FTC artifacts at `11.1.0`.
The original dirty checkout has those same pins and legacy ID access, but its other pending
changes were not built or certified here.

FIRST's [version 12 release notes](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v12.0/README.md#version-120-20260907-090034)
require distinguishing `AprilTagSingleDetection` from `AprilTagClusterDetection`. Inspection of
the published `Vision:12.0.0` AAR confirms that `id` belongs to the single-detection subtype,
whereas `ftcPose` remains on the common base. Our `FtcVisionPortalIO` iterates the base detections
and reads `detection.id` directly without that type distinction.

A minimal Java probe using the same ID access compiled against the real `Vision:11.1.0` classes
and failed against `Vision:12.0.0` with `cannot find symbol: variable id`. This is a source/API
probe, not a full SDK 12 Android build. The exact diagnostic source was:

```java
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
final class AprilTagLegacyProbe {
    static int tagId(AprilTagDetection detection) { return detection.id; }
}
```

There is also a pre-existing binary compatibility problem hidden by desktop mocks. `javap -c`
on the tested `ftc-hardware:19.1.5-rc.acceptance.20260925.1` binary shows calls to
`AprilTagDetection.getFtcPose()` and `getId()`. Our Kotlin mocks supply these getters; the actual
11.1.0 SDK exposes public Java fields and no such methods. The actual 12.0.0 base has a public
pose field and no ID/getter. These missing method references would cause linkage errors if the
affected binary path executes with the real SDK. That conclusion follows from class/bytecode
inspection; no physical controller execution was performed.

FIRST additionally states that BIOBUZZ tags move and cannot provide absolute field localization.
Our current adapter computes absolute poses from configured tag coordinates. Migration must
preserve stationary-reference validation and distinguish target-relative cluster aiming from
absolute localization. The official build stack also uses Gradle 9.1 and AGP 8.13.2; our audited
FTC projects use Gradle 8.14.5 and AGP 8.7.0. That tooling difference is an upgrade task, not by
itself proof that an Android build fails.

The open migration work is to align FTC mocks and adapters with real SDK member shapes and
detection subtypes, review moving-target behavior, update both FTC projects and generated starter
resources, then validate a new ARESLib version/candidate and Android consumers in dependency order.
Controller/Driver Station and camera checks remain a separate hardware checkpoint. SDK pins,
library versions, and starter archives were not changed in this continuation.

Local probe evidence is under `build/ftc-sdk12-probe/`: the official AAR, extracted class files,
`apriltag-api.txt`, `sdk11-apriltag-api.txt`, `ares-bytecode.txt`, the source probe, and both compiler
logs. The evidence JSON records artifact hashes; no downloaded SDK artifact is committed.

## Guidance, policy, and limits

The canonical desktop skill now covers cleanup when constructors fail before registration and
same-path recovery. The build skill now requires real-SDK/API and binary-shape evidence when
checking FTC compatibility, separating it from simulator and controller evidence.
Both skill format checks and the shared guidance verifier passed; all six guidance regressions
passed. The regenerated maintainability inventory has 1,114 production files and zero size
violations. Monorepo source policy passed without changing limits.

The [evidence summary](INITIALIZATION_CLEANUP_FTC_COMPATIBILITY_2026_11_09_EVIDENCE.json) and
[file review ledger](file-reviews.json) bind this bounded delta. No full-file or repository-wide
completion claim is implied by a passing suite. No SDK 12 Android build, physical hardware
validation, publication, protected release, or integration into the original dirty checkout was
performed. The preceding [shutdown/import checkpoint](DATABASE_LIFECYCLE_IMPORT_2026_11_09.md)
retains the responsiveness workload results and prior known limits.
