# FRC documentation consistency audit - pass 131

Reviewed all ten remaining pending Markdown files under ARES-FRC: README,
CONTRIBUTING, architecture, autonomous/simulation, operations, drivebase configuration,
subsystem authoring, trademarks, and both copies of third-party notices.

## Corrected instructions

- Replaced obsolete team-source paths and the claim that generated Kotlin is checked
  in. Current team sources live under `org/aresfirst/marvin`; build plumbing is disposable.
- Replaced the obsolete zero-controller-scheme description with the installed driver
  scheme, axes/deadband/scaling, generated runtime, hand-controller assist gate and
  mode cancellation. Retained the distinction between keyboard codes and HID indexes.
- Documented the HAL GUI opt-in and the saved WASD/Q/F keyboard controls.
- Corrected Kotlin 1.9.23 to the build's 2.4.10, optional explicit sibling substitution,
  the missing-sibling error and the dedicated synchronized JNI test directory.
- Replaced obsolete 8.0.0 candidate examples with the canonical release property's
  base version and an explicitly replaceable unique-source placeholder. No candidate
  publication was performed. Offline generation requires locally available dependencies.
- Moved relative-mechanism homing after deployment/process startup, because restarting
  invalidates the old zero. Offset-promotion instructions now include matching the
  canonical profile and descriptor evidence/hash before verification and rebuilding.
- Corrected flywheel brownout behavior: target RPM stays fixed while allowed effort
  scales. Clarified intake degree-valued Redux fields and the cowl readiness requirement
  for automatic shot feeding, separate from manual feed/intake paths.
- Corrected the root-directory `verify-autos.ps1` invocation and clarified the legacy
  starter output directory versus the generated team-owned package.

## Source checks and limits

Compared these descriptions with build/settings/version policy, the controller scheme
and profile, `ARESRobot` composition/periodic/tuning paths, hand-controller and shooter
readiness code, `MarvinState`, generated-command scaling, and the reviewed IO/safety
tests. README source paths exist. Third-party notice files are byte-identical.
The drivebase configuration guide's unarmed typed-tuning description agrees with
the explicit `sessionArmed=false` context in the composition root.

The contribution, trademark and notice files were reviewed as repository policy
artifacts and retained unchanged. This does not independently certify publisher
license terms, rights to every dependency, trademark compliance, or legal sufficiency.
No license or attribution notice was removed or rewritten.

Hardware steps and new-subsystem safety requirements are instructions and acceptance
criteria, not assertions that this audit physically tested them or that every current
adapter already satisfies them. Remaining production/hardware gaps stay in the ledger.

Documentation links, repository policy, agent-guidance checks and staged whitespace
checks passed. No executable configuration, runtime source or test changed; the
299-test FRC result from pass 130 was not rerun. No simulator window, robot action,
deployment, candidate publication, push, merge or release was performed. Source hashes
and validation logs are retained in `ARESLib-Kotlin/build/audit-pass131-verified-evidence/`.
