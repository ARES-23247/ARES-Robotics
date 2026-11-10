# Dependency branch consolidation

Date: 2026-11-10. Branch: `codex/dependency-pr-consolidation-20261110`.
This follows [SDK/Studio integration and cleanup](GITHUB_CONSOLIDATION_2026_11_10.md).
The source baseline is `4cb98f914b6ceaa99bc867a2aca4cd3459f279d7` from
[PR #109](https://github.com/ARES-23247/ARES-Robotics/pull/109).

## Integrated dependency changes

Three existing PR heads were merged into an isolated integration branch, preserving their
original commits. Compared with the SDK/Studio baseline, the functional delta is exactly three
dependency declarations in two Gradle files:

| Original PR | Dependency | Before | After |
| --- | --- | --- | --- |
| [#86](https://github.com/ARES-23247/ARES-Robotics/pull/86) | Google API client | 2.9.0 | 2.9.1 |
| [#87](https://github.com/ARES-23247/ARES-Robotics/pull/87) | Google GenAI | 1.68.0 | 1.71.0 |
| [#97](https://github.com/ARES-23247/ARES-Robotics/pull/97) | JGit | 7.7.1.202607240634-r | 7.8.0.202609011348-r |

Original heads are `4659a0c5cbb8b166a92c4cdc252fc963e497f85d`,
`6ac628a0438b7a37efcf3915fa8dc7c4bf34fb89`, and
`bb570426c7364164f1df695e86ac33d09153366c`, respectively. The combined dependency tree was
tested at `b2005f0823b89e57a6c6e67dde1fa0c84453865a` before the version/documentation checkpoint.
No conflict resolution changed application source. Studio reserves version `7.0.70` and its
workflow asset URLs advance together. Library `20.0.2`, its bound source tree, all starter/example
versions, and every bundled archive hash remain unchanged.

## Validation and boundaries

**61 focused tests passed with zero failures, errors, or skips:** 43 actual local Git-history,
archive, and review-token tests, plus all 18 gateway tests. The Gradle run resolved the new
dependencies, compiled the affected production/test sources, and exercised real JGit repository
operations. It used candidate `20.0.2-rc.sdk12.otos.20261110.1` from the same isolated file
repository as SDK validation. After reserving Studio 7.0.70, release-version alignment, dependency
provenance, canonical guidance/archive/source policy, and the 1,117-file zero-violation ratchet
also passed. Command:

```text
gradlew :gateway:test :app:test --tests com.ares.analytics.service.versioncontrol.* --tests com.ares.analytics.viewmodel.ProjectVersionControlViewModel* --tests com.ares.analytics.gateway.* -ParesVersion=20.0.2-rc.sdk12.otos.20261110.1 -ParesRepository=<absolute isolated file URI> --no-parallel --console=plain
```

The matching app tests are the 43 service tests; the additional viewmodel filter matched no
class and contributes no coverage claim. Raw logs and XML are retained in the ignored
`build/github-consolidation-20261110/dependency-test-xml/` directory and adjacent log/count files.
Gateway tests do not call live Google OAuth/GenAI services. Local software results do not establish
live-cloud behavior or physical robot operation. The final integration must use the normal
protected-main PR checks; no dependency PR is bulk-merged around failed checks.

## Additional cleanup and preserved work

After the earlier 23 ancestry-merged local branches and six remote branches were removed,
`git cherry origin/main <branch>` proved five further unused local branches contained only
patches already replayed on main `8294bd6de9663e4f9cecc86dbd6a64acc3ae36ee`:
`codex/deduplicate-post-merge-ci`, `codex/release-candidate-promotion`,
`codex/release-studio-5.0.5`, `codex/remove-rebuild-release-fallback`, and
`codex/visible-e2e-followup-fixes`. Their local refs and the two corresponding release-studio and
visible-follow-up remote refs were removed after fresh worktree/head checks. The remote deletes
used exact-SHA leases. Commit IDs and patch-equivalence proof are retained in
`build/github-consolidation-20261110/replayed-cleanup.json`. Thus this checkpoint has removed
28 local and eight remote branches explicitly; the remote heads of the three closed duplicate
dependency PRs are also absent. No worktree or unique unmerged patch was removed.

The remaining Kotlin/Compose/serialization, API-validator, and simulator logging PRs affect
coordinated compiler/library identity work. The separate BioBuzz turret and other unique/checked-out
branches remain preserved for scoped integration. Main/Maven refs, tags, dirty worktrees, and
the original dirty checkout are untouched by branch deletion. No public release is promoted here.
