# Policy source inventory audit

Pass 190, 2026-09-11. Reviewed the monorepo policy checker's source selection,
retired-type and namespace checks, and source-size boundary. The preceding pass
completed the Git and wrapper configuration review.

## Corrections

The checker walked the entire workspace twice before filtering paths, plus a
separate FRC source walk. Ignored local validation exports could be interpreted
as current product source and fail the check. Conversely, a tracked build file
with the Windows hidden attribute escaped the recursive filesystem scan.

One checked Git inventory now supplies the build, production and FRC source
lists. It includes tracked files even when ignored, and untracked files that are
not ignored. Explicit build-output exclusions remain. Existing worktree bytes
are checked; unstaged deletions are skipped. NUL-delimited paths, an explicit
character-array split and forced file lookup support the tested Unicode, space
and hidden-file cases on PowerShell 7 and Windows PowerShell 5.1. Git enumeration
failure stops validation instead of producing an empty successful scan.

## Validation

The eight-case baseline reproduced four failures: ignored validation exports
and hidden tracked build files each failed in both PowerShell editions. All eight
passed after the inventory change. Four additional cases cover Unicode paths,
root-level production sources, explicit output exclusions and a corrupt Git
index. The full tooling suite passed 100 tests with no failures or skips,
including all twelve policy cases under both editions.

Fixtures execute the real policy script in temporary Git repositories with
synthetic manifests, archive bytes, workflows and source. Guidance and Markdown
checker entrypoints are stubs in these fixtures; their independent suites and
the real repository check provide integration evidence. Initial fixture runs
exposed an inherited PowerShell module-path problem; the fixture now clears that
variable case-insensitively so each edition uses its own modules.

The real repository policy check passed in 7.85 seconds on this populated
checkout, including 350 current Markdown documents and 38 historical exclusions.
This is a single observed elapsed time, not a controlled before/after speedup
ratio. The structural improvement removes repeated recursive workspace scans.
Logs and replayable test evidence are under
`ARESLib-Kotlin/build/audit-pass190-verified-evidence/`.

## Remaining work

The policy checker remains partially reviewed. Its line-count expression uses
`Measure-Object -Line`, which omits empty lines. The current 1,000-nonempty-line
boundary is tested; physical-line enforcement and decomposition remain open.
Direct inspection found three files above 1,000 physical lines:

| File | Physical lines |
| --- | ---: |
| `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/viewmodel/SubsystemGeneratorViewModel.kt` | 1008 |
| `ARESLib-Kotlin/codegen/src/main/kotlin/com/areslib/codegen/AresKotlinProjectGenerator.kt` | 1031 |
| `ARESLib-Kotlin/core/src/main/kotlin/com/areslib/networktables/NT4Server.kt` | 1057 |

Those sources need appropriate decomposition and validation before switching
the gate to physical lines; deleting whitespace to satisfy the number would not
address the ownership concern. Their executable behavior was not reviewed here.

Workflow and source regexes are not YAML/Kotlin/Gradle parsers. Comment/string
false positives, equivalent syntax bypasses, absolute-path exclusion boundaries,
symlink behavior and the remaining release/guidance checks require further review.
Untracked ignored files are intentionally outside this source inventory. No
runtime source, candidate version or bundled archive changed in this pass.
