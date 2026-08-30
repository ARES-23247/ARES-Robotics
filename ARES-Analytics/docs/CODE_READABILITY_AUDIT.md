# Code Readability and KDoc Audit

Date: 2026-08-10

## Scope

All 228 first-party Kotlin/Java files under the following source sets were reviewed:

| Source set | Files |
|---|---:|
| `app/src/main` | 181 |
| `app/src/test` | 33 |
| `shared/src/main` | 7 |
| `shared/src/test` | 2 |
| `gateway/src/main` | 3 |
| `gateway/src/test` | 2 |

Generated build output, Gradle caches, packaged resources, reports, and external dependencies were excluded. The count includes `ProjectLayout.kt`, which this audit added.

Each scoped file was included in a declaration/line/KDoc inventory and checked for placeholder documentation, malformed encoding, direct nested `if` statements, empty catches, non-null assertions, TODO/FIXME markers, and whitespace errors. High-risk services, shared wire/storage models, gateway routes, path editing, field transforms, and the largest control-flow hotspots were also read in full.

## Documentation policy

KDoc now records contracts that are not obvious from a symbol name: units, coordinate frames, serialization compatibility, ownership/lifecycle, concurrency, persistence, authentication, and fallback behavior. Self-explanatory private helpers, Compose layout code, and behavior-named tests intentionally do not receive restated-name comments.

The audit removed 459 generated KDoc blocks that contained only a generic physical-units sentence. Those comments were inaccurate for most declarations and hid rather than explained their contracts. Targeted KDocs replaced them on shared models, major view models, project-layout resolution, gateway routes, and other non-obvious APIs.

## Readability changes

- Centralized FTC/FRC asset and PathPlanner directory resolution in `ProjectLayout`; field editing, field viewing, path serialization, and export now use one documented policy.
- Replaced all direct `if { if { ... } }` matches with guards, combined conditions, `when`, or focused helpers where the result is clearer.
- Extracted bounded field-trace append/replace operations and named their meter/sample thresholds.
- Removed forced-null sentinel reader loops in process handling and made EOF explicit.
- Replaced avoidable non-null assertions in NT4 batching and controller-image caching.
- Clarified the shared telemetry, session, field geometry, PathPlanner, topology, unit, workspace-secret, and cloud-forensics contracts.
- Removed trailing whitespace, excessive blank lines, and indentation damage left by prior generated documentation.

## Intentionally retained structures

- Double-checked locking in `Nt4ClientService` remains nested because it protects lazy client replacement across threads.
- Rendering branches remain where they directly mirror mutually exclusive visual states; flattening them would obscure the drawing order.
- Exception isolation remains around corrupt log records, best-effort cleanup, and optional native/tool integration. Suppressed failures should state that policy at the catch site.

## Follow-up concerns found during review

- `ParquetLogDecoder` remains an intentionally unsupported adapter. It now fails explicitly instead of returning a misleading successful empty import.
- Several legacy UI sites use non-null assertions after local null checks. They are generally safe today but should continue migrating to local smart-cast values as those components are touched.
- Large classes such as `FieldCanvas` and `CloudViewModel` remain candidates for cohesive feature
  extraction. The former `MatchLogRepository` concern was subsequently split into session,
  telemetry, action, run-evidence, and bounded-query repositories over one transaction coordinator.

## Verification

- Placeholder KDoc patterns: zero in first-party source.
- Direct nested-`if` pattern: zero in first-party production source.
- Encoding-artifact scan: clean.
- `git diff --check`: required before handoff.
- Full Gradle suites: required in dependency order after all six monorepo products finish this shared-source pass.
