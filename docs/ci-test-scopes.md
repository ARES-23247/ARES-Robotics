# CI test scopes

Pull requests and merge-queue commits select affected products using
[`scripts/classify_ci_paths.py`](../scripts/classify_ci_paths.py). All five review workflows call
[`ci-scopes.yml`](../.github/workflows/ci-scopes.yml), so their decisions use the same dependency
rules. Each run writes the selected scopes to the GitHub Actions job summary.

## Selection rules

| Changed area | Ordinary product tests | Additional validation |
| --- | --- | --- |
| ARESLib-Kotlin, including its build inputs | Library tests/API checks, then every consumer against one isolated candidate | All integration, CodeQL, and package checks |
| FTC season robot | FTC Android tests, simulator tests, APK build | Autonomous contracts and packages, because Lightbot is an exported example |
| FRC season robot | FRC tests | Autonomous contracts; no desktop package build |
| FTC or FRC starter | That starter plus Studio app tests | Template/package and autonomous integration checks |
| XRP starter | Standalone XRP verification, MicroPython runtime tests, Studio app tests | Template/package and autonomous integration checks |
| Studio `app/` | `:app:test` | Dashboard, autonomous authoring, and complete package verification |
| Studio `gateway/` | `:gateway:test` | JVM CodeQL; no dashboard, autonomous, or package job |
| Studio `shared/` or root build configuration | `:shared:test :app:test :gateway:test` | Dashboard, autonomous, and package verification |
| Root `docs/`, root Markdown guidance, `.agents/`, Studio `docs/` | Source/release policy checks | No product tests |
| `release/`, `scripts/`, `.github/workflows/`, shared build inputs, unknown root files | Full matrix | All integration and package checks |

Multiple changes select the union of their scopes. Starter changes also test their Studio consumer:
Studio imports, generates, and embeds those projects. Documentation under source/resource directories
is treated as a product input. Product documentation outside the explicit documentation allowlist
is also treated conservatively, since exported archives and library source identities can include it.

These are product/module scopes, not individual test-method selection. A selected product keeps its
complete ordinary suite. The autonomous job remains an integration contract across library, Studio,
FTC, and FRC. Java/Kotlin CodeQL retains its complete cross-product build when any JVM scope is
affected; Python CodeQL runs for XRP or full-matrix changes. This avoids changing the security
analysis's source coverage and alert identity on each pull request.

## Review trees and required checks

The resolver compares the event's base SHA with the checked-out review SHA. For pull requests this
is the synthetic merge tree, and for merge groups it is the queue tree against `merge_group.base_sha`.
Checkout fetches full history. The local Git diff has no API changed-file count limit; NUL-delimited
paths preserve unusual filenames, and disabling rename detection includes both the old and new path.
Missing/invalid SHAs or a failed diff fail classification rather than reporting no changes.

Workflows always start. Individual jobs skip when unaffected; there are no workflow-level path
filters. GitHub documents why skipped workflow runs can leave required checks pending, while skipped
jobs report a completed status in its [required-check guidance](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks).

Each review workflow has an `always()` result job. It accepts successful jobs and intentional skips,
but fails on classification failure, cancellation, or any failed dependency. For repository rulesets,
use these stable result checks alongside the existing required security/policy checks:

- `Monorepo CI result`
- `Analytics validation result`
- `Autonomous validation result`
- `Desktop package validation result`
- `CodeQL validation result`

The source change adds these check jobs; it does not edit hosted branch-protection/ruleset settings.
Existing product job names are retained. Required checks still run on merge-queue commits and do not
rerun on the resulting `main` push.

## Full validation and release artifacts

Manually dispatching a review workflow selects all of that workflow's scopes. Existing scheduled
dashboard soak and CodeQL runs also remain full within their respective workflows. To run the entire
product test matrix regardless of changes, dispatch **Monorepo CI**. To build a promotable artifact
for any exact tree, dispatch **Build Desktop Packages** on that tree.

When packages are affected, the existing complete release-verification, generated-project acceptance,
native MSI/DMG build, archive hashes, and attestation sequence all run. None of those checks is
replaced with a partial release test suite. A skipped package workflow produces no candidate, and a
candidate from an earlier tree cannot be promoted for a newer tree. Promotion still requires the
complete Git tree to equal `main` and verifies provenance and every declared file hash.

## Maintaining and testing the rules

Add new product boundaries and dependencies to the shared classifier and its tests. Unknown root
inputs deliberately select everything until their ownership is classified. CI scope/workflow changes
themselves select the full matrix, so the first pull request introducing these rules gets full
validation.

Run the local routing, failure-propagation, and release-manifest tests with:

```powershell
python -m unittest discover -s scripts/tests -p 'test_*.py'
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 -shellcheck= -pyflakes=
```

The regression suite includes both review events, cross-product Git renames, mixed changes, shared
module dependencies, unusual filenames, more than 4,000 changed paths, and failed/cancelled jobs.

## Previewing a branch's scopes

After fetching the current base branch, you can inspect the scope selection before opening a PR:

```powershell
git fetch origin
$scopeBase = git rev-parse origin/main
$scopeHead = git rev-parse HEAD
$scopeOutput = Join-Path ([IO.Path]::GetTempPath()) ("ares-ci-scopes-" + [guid]::NewGuid() + ".txt")
python scripts/classify_ci_paths.py --event-name pull_request --base-sha $scopeBase --head-sha $scopeHead --github-output $scopeOutput
Get-Content -LiteralPath $scopeOutput
```

This compares committed branch content with the fetched base. Uncommitted edits are not included;
GitHub additionally tests the synthetic merge tree described above. For a PR that changes only this
document, every product scope should be `false`. Source/release policy and the five final-status
checks should pass, while product tests, dashboard validation, CodeQL analysis, and package jobs
report explicit skips. A full validation can still be requested with a manual workflow run.
