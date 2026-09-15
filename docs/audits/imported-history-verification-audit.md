# Imported history verification

Pass 185, 2026-09-11. Both history-verification scripts and their new regression
tests were reviewed. The previous pass made concrete progress through a validated
costmap fix; this pass covers previously pending repository tooling.

## Confirmed defect and fix

Both scripts verified that recorded import/source objects existed, that source
commits were ancestors of HEAD, and that each recorded imported subtree matched
its source tree. They did not require the import commits themselves to be
ancestors of HEAD. A replacement history could therefore retain all source
histories while abandoning the recorded import lineage and still pass.

A disposable Git graph reproduced this false pass in PowerShell 7, Windows
PowerShell and Git Bash. Both scripts now explicitly reject an import commit
outside HEAD's ancestry. Existing source ancestry and tree equality checks remain
necessary: import ancestry alone does not prove either property. The six recorded
commit identities are unchanged and agree between shell implementations.

## Validation

Seven new unittest cases exercise matching shell records and six real Git graph
scenarios: healthy history, missing import, missing source, unreachable source,
unreachable import, and an existing but incorrect imported subtree. All six graph
scenarios ran under all three installed interpreters, including paths with spaces
and invocation from outside the repository. Fixtures use only temporary local
objects and disable inherited Git configuration and repository environment.

The initial fixture needed a Windows-specific correction: binary stdin prevents
newline conversion from appending carriage returns to `git mktree` filenames.
After that correction, the baseline had exactly three failures, one per shell,
for the unreachable-import case. The final full root tooling suite passed all
67 tests with no skips. Both updated entry points also verified all six imports
in the real monorepo. Logs are preserved in
`ARESLib-Kotlin/build/audit-pass185-verified-evidence/`.

This tooling-only change does not alter robot/library code or invalidate the
previous candidate's runtime test evidence. No new library candidate or robot
build was needed. No remote operations or release actions were performed.
The scripts prove the recorded historical relationships, not current product
correctness, absence of later changes, or completeness of the repository audit.
