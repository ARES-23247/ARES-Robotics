# Source decomposition and XRP scaffold audit

Pass 191, 2026-09-12. This pass follows the physical-line issue identified in
the policy source inventory review and inspects the extracted responsibilities.

## Corrections and decomposition

The source-size gate previously omitted empty lines through `Measure-Object -Line`.
It now counts physical lines with a streaming file reader. The new boundary test
fails on the old checker in both PowerShell editions, and the final thirteen-case
policy fixture suite passes in both. The full tooling suite passes 101 tests.

The three oversized sources were decomposed without deleting whitespace to meet
the threshold. NT4 connection-owned encoding storage moved to `NT4OwnedSendSlot`;
the transport-drain gate remains in the server. Code-generation request/result
models moved to `KotlinProjectCodegenModels`. The Studio view model delegates XRP
extension creation to `XrpExtensionScaffold`. The resulting original files have
987, 985 and 956 physical lines respectively. The library extraction preserves
public class identities, generated-source behavior and configured send-buffer
ownership; its focused networking/codegen run passed 106 tests.

Reviewing the XRP template exposed a separate executable defect. Kotlin raw-string
backslashes were emitted before Python quotation marks, producing a `SyntaxError`
in every newly created extension. The template now emits valid Python quoting.
One of four new scaffold tests failed before the correction when Python executed
the actual generated file. All four now pass, including both physical/simulated
factories, neutral recovery, rejection of unknown actions, preservation of an
existing USER-OWNED file, platform/kind exclusions and invalid source paths.
All 34 existing subsystem view-model tests also pass.

## Validation and provenance

The isolated library candidate is `17.0.10-rc.271e2518a412`, bound to source tree
`271e2518a412f19cac6ea84331cbc101e5efdd91`. Full library validation passed
2,234 tests with no failures or skips, API compatibility checks and local Maven
publication. The first command's mismatched candidate version was rejected before
publication; the corrected command derives the version from the manifest.

Studio is pinned to 7.0.10, FTC/FRC starters to 17.0.10, and XRP/Lightbot to 3.0.9.
Four deterministic starter archives were rebuilt locally, with bundled bytes and
workflow/manifest hashes updated together. Nothing was pushed or released.
Comparing each archive with its predecessor confirmed identical entry order and
metadata after normalizing the versioned root folder. The only changed entry is
the standalone library-version manifest: 137 FTC, 48 FRC, 46 XRP and 201 Lightbot
entries were checked. This accounts for archive bytes, not every embedded source's
review status or a fresh build of an exported project.
Detailed logs, XML and archive hashes are preserved under
`ARESLib-Kotlin/build/audit-pass191-verified-evidence/`.

All consumers used the same local candidate after library publication:

| Scope | Passed | Skipped | Additional validation |
| --- | ---: | ---: | --- |
| Library | 2234 | 0 | API checks, local publication |
| FTC robot and simulator | 156 | 0 | Debug APK assembly |
| FRC | 305 | 0 | Consumer suite |
| FTC starter | 14 | 0 | Debug APK assembly |
| FRC starter | 34 | 0 | Consumer suite |
| Studio shared, gateway and app | 1875 | 6 | Host Python scaffold execution included |

The total is 4,618 passed runtime tests and six skipped checks, plus 101 passed
tooling tests. Focused reruns are not added again. The six opt-in skips cover fresh
generic starter builds, official pinned archive integration, representative
zero-code starter integration, the native file chooser, dashboard performance
baseline and physical dashboard telemetry. Exact test identities are retained in
`studio-summary.json`. Initial scaffold test compilation was adjusted to use
Studio's existing JUnit 4 framework before obtaining the executable baseline.

## Remaining scope

The three original large files remain partially reviewed; moving one responsibility
does not account for their remaining behavior. NT4 storage was reviewed at the
server's configured 8 KiB initial/4 MiB maximum capacity with the existing drained
transport and allocation regressions. This is not a new general-purpose buffer API.

Scaffold path checks and preservation of an already-existing file are tested.
Concurrent external file creation during the atomic replacement window, filesystem
aliases, unusual document-ID characters and broader save/registration lifecycle
behavior remain open. Existing USER-OWNED extensions are preserved, including
previously generated files that may need an explicit user edit. The Python execution
test is skipped when no Python interpreter is installed; it ran and passed here.
Host Python execution does not prove MicroPython hardware behavior or hardware safety.

The policy's other regex, source-selection and release/guidance limitations from
the preceding review remain open. No physical robot or visible Studio UI was tested.
The audit continues in an isolated worktree after a concurrent branch switch in the
shared checkout; the other work's staged skill edits were preserved separately.
