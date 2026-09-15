# Git and wrapper configuration audit

Pass 189, 2026-09-11. Reviewed fifteen previously pending declarative files:
the root ignore file; Analytics ignore and wrapper properties; FRC Starter,
FTC Starter and FTC ignore, attributes and wrapper properties; ARESLib ignore
and wrapper properties; and the simulation launcher daemon ignore file.

## Evidence

Read every selected file and checked its rules against product ownership and
the engineering guide. Ran 78 checks against the current checkout: 63 Git
ignore decisions, ten Git attribute decisions and five wrapper property checks.
All passed. The replayable check script and detailed results are preserved in
`ARESLib-Kotlin/build/audit-pass189-verified-evidence/`.

Git checks use `--no-index` to evaluate rules even for tracked paths. Cases
include local caches, Python bytecode, XRP secrets, canonical shared guidance,
daemon dependencies and certificates, Android outputs and bundled AARs,
wrapper JARs, Studio template ZIPs, source packages named `build`, robot logs,
local editor history and starter editor tasks. Attribute checks confirm LF
wrapper scripts, CRLF batch scripts, FRC Kotlin/Gradle/JSON and the explicitly
named FTC Starter generated Kotlin file.

All five wrapper property files select the same HTTPS Gradle 8.14.5 binary
distribution specified in the workspace guide. Keys are unique, cache bases
and paired storage paths agree, explicit timeouts are positive, explicit URL
validation is enabled, and companion wrapper JARs exist. FRC Starter uses
`permwrapper/dists`; the other selected products use `wrapper/dists`.

## Observations and limits

No confirmed correctness defect required a configuration edit. Some rules
overlap: ARESLib's nested build/bin rules repeat the broader directory rules,
and product editor/OS exclusions overlap the root. These small declarative
redundancies have no demonstrated material cost and can help standalone roots.
They were retained rather than changing library or template release bytes for
cosmetic deduplication.

Starter `.vscode` directory exceptions allow other files besides `tasks.json`.
ARESLib's CSV exclusion applies broadly, while Analytics explicitly preserves
template ZIPs and source directories named `build`. These are recorded policy
boundaries, not evidence that untracked source or credentials were found.
Ignore rules do not prevent force-adding files or remove already tracked files.

The selected wrapper properties do not pin a distribution SHA-256. This pass
does not establish downloaded distribution or wrapper JAR authenticity, perform
a fresh network download, or review wrapper executable code. Analytics omits
explicit timeout and URL-validation keys; no claim about its wrapper binary's
defaults is made. Companion binaries retain their independent ledger status.
Representative path checks do not exhaust all possible path names or global
Git configurations. No runtime source, release identity or archive bytes changed;
the earlier runtime matrix was not rerun for this declarative review.
