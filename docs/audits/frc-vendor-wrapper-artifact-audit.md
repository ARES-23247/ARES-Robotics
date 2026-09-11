# FRC vendor and wrapper artifact audit - pass 132

Accounted for eight remaining FRC vendor/policy/build artifacts: both wrapper
launchers, wrapper JAR, Phoenix descriptor, LICENSE/NOTICE and their packaged copies.
All remain unchanged.

## Wrapper

Read both launcher scripts, including Java discovery, quoting, environment options,
OS handling and exit propagation. Generated fresh wrapper artifacts in an isolated
fixture using the installed pinned Gradle 8.14.5 distribution. Both launcher scripts
and the 43,764-byte wrapper JAR are byte-for-byte identical to the fresh output.
This is comparison with the installed distribution, not independent authentication
of the distribution's supply chain or an audit of every upstream wrapper class.

The Windows launcher has been used for the recorded validation runs. The POSIX
launcher passed `bash -n` and executed `--version` under Git Bash on Windows,
reporting Gradle 8.14.5. Native Linux/macOS/AIX/nonstandard-shell execution was not
tested. Existing line-ending policy remains appropriate; no custom wrapper edits
or speculative replacements were made.

## Phoenix descriptor

Read every descriptor field. Its name, file name, UUID, FRC year 2026 and version
26.1.1 agree. All one Java, fourteen JNI and fourteen C++ dependency entries pin
26.1.1; coordinate tuples and each platform list are unique within their sections.
Hardware-mode platforms include linuxathena; software-simulation platforms exclude
it and include desktop targets. JNI and C++ entries intentionally describe separate
consumers and are not redundant duplicate dependencies to remove.

The declared Java and applicable native coordinates are present in the local
dependency cache. Previous FRC tests exercised the Windows simulation selection.
This is descriptor/configuration validation, not a fresh resolution of every C++
or foreign-platform artifact or verification of vendor binary internals. The
descriptor's latest-update URL does not override the pinned dependency versions
without replacing the descriptor. No update was requested or performed.

## Policy artifacts

Read the license/notice text and compared the full root files with their
`src/main/resources/META-INF` counterparts: both pairs are byte-identical. The
notice identifies the FRC season application and ARES-authored portions. Retained
these as policy/attribution artifacts, not executable files requiring robot tests.
No legal sufficiency, contributor rights or third-party redistribution compliance
certification is implied by this consistency check.

## Evidence

Reference generation, POSIX syntax/version invocation, descriptor checks and exact
copy comparisons passed. Policy, documentation links and staged whitespace checks
passed. Hashes, fixture reference artifacts, cache-coordinate inventory and logs
are retained in `ARESLib-Kotlin/build/audit-pass132-verified-evidence/`.

No runtime code, build configuration or vendor artifact changed. The existing
299-test FRC result was not rerun for artifact accounting. No physical robot,
rendered GUI, dependency upgrade, push, merge or release was performed.
