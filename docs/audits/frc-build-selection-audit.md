# FRC build selection audit - pass 129

Reviewed the complete settings script, Gradle properties and wrapper properties.
Read the complete build script, but keep its review partial for deployment, native
packaging/extraction, remote offset acquisition and platform-specific launch paths.

## Confirmed defect

With `aresUseSiblingLib=true`, settings previously included the library only if its
directory existed. If missing, configuration succeeded and dependency resolution
could silently use versioned binaries despite the explicit source request.

The explicit source mode now requires a sibling directory with a Gradle settings
file and otherwise throws a descriptive error. Default/false mode still uses
versioned binaries. This is a build-selection contract check, not authentication
of arbitrary contents of a sibling repository.

An isolated settings-only fixture with no sibling succeeded before the fix and
failed with the intended diagnostic afterward. Additional isolated Gradle runs
verified default mode, explicit false, a valid included build and a directory
without a settings file. The real monorepo sibling also configured successfully
and appeared in `projects` as an included build. Both Groovy and Kotlin settings
filenames are accepted; the fixture exercised Groovy and the actual library Kotlin.

## Other build observations

The settings plugin repositories and 2026 WPILib home selection are coherent with
the pinned GradleRIO version. Windows behavior was exercised; macOS/Linux home
paths were inspected only. The unresolved-native-header system property is retained
as existing plugin compatibility configuration, not proof of resolved native inputs.

The daemon has a 4 GiB heap cap, 1 GiB metaspace cap and G1GC; these are build-process
settings, not robot loop settings. Kotlin incremental compilation is disabled.
That sacrifices potential compile reuse, but this pass did not establish that
enabling it is safe across generation and native builds. No speculative change.

Wrapper metadata consistently selects Gradle 8.14.5, HTTPS distribution, URL
validation, 10-second network timeout and matching persistent distribution/cache
paths. The installed wrapper ran successfully; a fresh network download, wrapper
JAR provenance and launcher-script review are not established by that result.

The build script uses version policy/BOM and explicit candidate-repository override,
keeps generated code under build outputs and verifies documents before compilation.
Code-generation preparation and verification are separate startup/build work.
Task output caching needs a fuller input/ownership analysis before reusing generated
outputs. Remote offset fetch uses a temporary file, validates four bounded finite
rotations and requests atomic replacement; no SCP operation or failure injection
was performed, so that path remains open. Native extraction and fat-JAR dependency
contents also remain open rather than inferred safe from passing unit tests.

## Validation

Normal candidate-mode `verifyAresProject test` passed after the settings change;
unchanged test outputs were up-to-date (299 successful tests in the retained XML).
This is task reuse, not a fresh execution of every test. Policy, documentation
links and staged whitespace checks passed. Isolated fixture inputs, logs, hashes
and exit outcomes are retained under
`ARESLib-Kotlin/build/audit-pass129-verified-evidence/`.

No robot runtime code, dependency version or library source changed. No hardware,
GUI, deployment, remote offset fetch, push, merge or release was performed.
