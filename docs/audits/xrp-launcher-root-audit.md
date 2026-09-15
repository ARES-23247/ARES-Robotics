# XRP launcher and verification-root audit - pass 138

Reviewed both launcher scripts, ignore rules, secrets example and starter README.
The README remains partial: its broader Studio, firmware and deployment instructions
need evidence beyond this launcher review.

## Confirmed defects

The POSIX launcher was tracked with mode `100644`, despite the documented `./ares`
invocation. Changed its Git executable bit to `100755`; script content is unchanged.
This fixes checkout metadata, not a native Linux/macOS execution claim.

The generated check for unsupported superstructure documents searched `Path.cwd()`.
Calling the project tool from another directory could therefore miss files in the
actual project. It now uses the tool's canonical `ROOT`. A regression creates an
unsupported document in an isolated project and supplies a different caller directory:
the old check missed it, while the corrected check reports failure. The outer
regression treats that intentional inner failure as its expected result.

## Launcher and file-policy evidence

Copied the unchanged launchers into a local fixture whose path contains spaces.
A harmless Python stub receives `alpha beta` as one argument and `gamma` as another,
then exits with code 19. Both the Windows launcher and Git Bash shell invocation
preserved the arguments and exit code. Windows also passed with `py` removed from
the fixture's PATH, exercising its `python` fallback. No device command was invoked.

Git ignore checks cover generated build output, Python caches and local Wi-Fi
secrets; the public placeholder secrets example remains visible to Git. The example
contains no credential and is included in the host Python compilation sweep.

## Validation

Before: the new regression failed in the 120-test XRP suite. After: all 120 tests
pass with zero failures/errors/skips when verification is invoked from the monorepo
root. Policy, generated-source freshness, documentation links and staged whitespace
checks pass. Logs, final XML, source fingerprints and launcher observations are
retained under `ARESLib-Kotlin/build/audit-pass138-verified-evidence/`.

The host generator retains its open schema, routine-feature, generated-test and
remaining CLI scopes. No physical robot, native POSIX machine, push, merge or release
was involved.
