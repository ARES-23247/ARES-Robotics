# Legacy migration inventory

Pass 186, 2026-09-11. Full review of `scripts/prepare-clean-monorepo-checkout.ps1`
and its new regression tests. The previous pass completed an import-history fix;
this pass covers a different previously pending script.

## Findings and corrections

- Detached checkouts returned no branch string, causing `.Trim()` on null.
- Unborn repositories printed the literal `HEAD` after a failed revision lookup.
- A corrupt Git index made `git status` fail, but the script still succeeded and
  overwrote an existing inventory with zero dirty entries.
- Untracked directories were collapsed into one entry, omitting individual files
  from the migration inventory.
- A trailing separator bypassed the same-source/destination rejection.

The script now uses one checked `git status --porcelain=v2 --branch
--untracked-files=all` snapshot per repository instead of three Git subprocesses.
Branch and commit headers explicitly represent detached and initial states; the
report labels its remaining entries as Git porcelain v2. Git errors or missing
metadata abort before the report is written. `--no-optional-locks` prevents
optional index-refresh writes during inventory. Destination equality ignores
trailing separators. The command still only inventories the supplied root and
its immediate child repositories, including linked worktrees; it does not clone,
move, delete, reset or recursively discover arbitrary nested repositories.

## Evidence

Six new cases ran under both PowerShell 7 and Windows PowerShell. Five cases
failed in each interpreter before the fix (ten failing subcases). All six now
pass, covering root/child repositories, every untracked file, a detached linked
worktree, an unborn branch, a failed Git status read preserving the old report,
equivalent destination paths, and a folder without Git. The repository fixtures
verify unchanged HEAD/index bytes and tracked file contents, and verify that no
destination directory is created.

The complete root tooling suite passed 73 tests with no skips or failures. A
read-only invocation on the real monorepo reported its actual branch, commit and
two then-current dirty entries, without creating the proposed checkout. Logs and
that local inventory are preserved under
`ARESLib-Kotlin/build/audit-pass186-verified-evidence/`.

This is a tooling-only change; the verified runtime candidate remains
`17.0.8-rc.c5f40142a878`. No new robot build, remote operation, migration or release
was performed. Report-path write permissions and discovery beyond immediate
children remain caller/environment concerns, not claims of this inventory.
