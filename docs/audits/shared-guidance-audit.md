# Shared guidance audit â€” pass 113

## Scope

Reviewed 15 complete guidance documents: root `AGENTS.md` and `GEMINI.md`, the Antigravity
adapter, the agent setup README, all four repository skill entry points, and their seven Markdown
references. Reviewed the guidance verifier and all six existing disposable-repository tests.
The larger workspace guide was reviewed only for its desktop launch/cleanup section and remains
partial. Skill helper scripts and platform startup implementations remain separately scoped.

These are instruction/consistency reviews, not claims that every runtime invariant described in
the guidance has been implemented or tested. Normative safety requirements remain requirements.
Documentation receives link, source-reference and consistency validation; the Python verifier
receives its actual automated regression suite. No dummy runtime tests were added for prose.

## Corrections

The desktop tester skill and recovery guide described `killExisting` as scoped orphan cleanup.
The task in `ARES-Analytics/build.gradle.kts` checks only the application main-class string
reported by JPS. It does not distinguish PID ownership, checkout, isolated home or healthy windows.
Blanket cleanup or requiring all `MainKt` processes to disappear could therefore affect another task.

The two guides and matching workspace-guide section now require checking all matching process
owners before the broad task is used. They document `-PskipKill` and a dedicated isolated desktop
home for a separate test instance, and require verifying the owned PID's exit instead of absence
of every ARES JVM. Title-based helpers have no PID filter, so multi-instance testing must use the
owned instance's dedicated test-control port or same-process capture. The existing `-Close`
test-control option supports instance-specific cleanup.

The workflow also selected sibling dependencies only after a default compile. Dependency mode
must now be chosen first and passed consistently to compile and run, preventing validation against
different dependency sources and avoidable repeated compilation.

Only guidance changes. The underlying broad `killExisting` implementation and title-based helper
APIs are unchanged and are not certified as ownership-aware. No application was launched, no
process was killed, and no GUI/rendering outcome is claimed in this pass.

## Source checks

- Checked the root routing, canonical skills, adapter imports, relative paths, size limit, ignore
  exceptions and CI invocation against `verify_agent_guidance.py`, its tests, `.gitignore`, and
  `.github/workflows/monorepo-ci.yml`.
- Checked published coordinates, sibling/candidate properties and validation task names against
  current Gradle declarations and the successful frozen candidate matrix from pass 109.
- Checked desktop compile/run hooks, `skipKill`, isolated-home configuration, coroutine dependency
  pair, runtime snapshot/finalizer, and opt-in capture/test-control names against current sources.
- Checked subsystem schema/ownership/tuning-reference locations; no schema version was copied into
  guidance and no generated source was edited.

Official documentation checked on 2026-09-11 confirms the setup README's tool-discovery claims:
Codex discovers repository instructions with a default combined 32 KiB budget and repository
skills under `.agents/skills`. [Codex instructions](https://learn.chatgpt.com/docs/agent-configuration/agents-md),
[Codex skills](https://learn.chatgpt.com/docs/build-skills).

Gemini documents relative imports, memory reload/show, and the `.agents/skills` workspace alias.
[Gemini context](https://geminicli.com/docs/cli/gemini-md/),
[Gemini skills](https://geminicli.com/docs/cli/skills/).

Antigravity documents `.agents/rules`, Always On activation, relative file references and
`.agents/skills`, with older singular paths retained for compatibility.
[Antigravity rules](https://antigravity.google/docs/rules-workflows),
[Antigravity skills](https://antigravity.google/docs/skills).

These web checks verify the documented conventions, not the installed configuration or feature
availability of every contributor's editor. Permissions remain application-owned.

## Final validation

The guidance verifier, all six existing guidance regression tests, and monorepo policy passed. Current Markdown link validation passed. Fifteen complete guidance documents and two Python files are accounted for; the large workspace guide remains partial beyond the reviewed desktop section.

Logs and verified hashes are recorded in `ARESLib-Kotlin/build/audit-pass113-verified-evidence/summary.json`. Library identity and all runtime source are unchanged; no robot/desktop suite was rerun for these documentation-only edits.
