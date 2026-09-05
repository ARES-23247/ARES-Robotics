# Shared agent guidance

Open the monorepo root in your coding tool. No personal skill installation, copy script,
symlink, or machine-specific path is required for this repository's guidance.

| File | Responsibility |
|---|---|
| [AGENTS.md](../../AGENTS.md) | Shared entry point, routing, and essential engineering rules |
| [WORKSPACE_GUIDE.md](WORKSPACE_GUIDE.md) | Detailed product, safety, telemetry, desktop, and release contracts |
| [GEMINI.md](../../GEMINI.md) | Gemini import of the shared entry point |
| [Antigravity rule](../../.agents/rules/ares-workspace.md) | Always On rule referencing the shared entry point |
| [.agents/skills](../../.agents/skills/) | Canonical skills and their references/scripts for all tools |
| [ARESLib guide](../../ARESLib-Kotlin/GEMINI.md) | Library-specific contributor rules, read by every tool |
| [FTC starter guide](../../ARES-FTC-Starter/AGENTS.md), [FRC starter guide](../../ARES-FRC-Starter/AGENTS.md) | Additional starter boundaries, read by every tool |

Codex reads `AGENTS.md` and discovers repository skills in `.agents/skills`. Keep the root
guide below the repository's 8 KiB limit so nested instructions also have room within the
default 32 KiB instruction budget. Details belong in the linked engineering guide and skill
references. See [Codex instructions](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
and [skills](https://learn.chatgpt.com/docs/build-skills).

Gemini CLI reads `GEMINI.md`, whose `@./AGENTS.md` import loads the same entry point. It also
discovers `.agents/skills`. After changing instructions in an existing session, use
`/memory reload` and inspect `/memory show`; reload skills or start a new session after skill
changes. See [Gemini context](https://geminicli.com/docs/cli/gemini-md/) and
[skills](https://geminicli.com/docs/cli/skills/).

Antigravity discovers workspace rules in `.agents/rules` and skills in `.agents/skills`.
The shared rule is configured Always On and references `AGENTS.md` relative to the rule file.
Check that rule's activation in the editor after opening the workspace. Older releases may
use `.agent` paths; update the tool instead of maintaining another instruction copy.
See [Antigravity rules](https://antigravity.google/docs/rules-workflows) and
[skills](https://antigravity.google/docs/skills).

All three tools must follow the shared guide's links to applicable product instructions,
even when those files use another tool's conventional filename. Existing personal ARES skills
can be stale duplicates: prefer the repository files. Do not copy personal preferences,
credentials, account configuration, or session history into shared guidance. Equal access to
instructions does not change a tool's permissions, installed capabilities, or workspace trust;
those remain under each contributor's application settings.

## Maintenance

Edit the shared rule once in `AGENTS.md`, the engineering guide, or its owning skill/reference.
Keep the two tool adapters small and limited to routing. Use source paths instead of copying
volatile schema numbers, file lengths, or service counts. Check the actual build files before
changing dependency or test-framework guidance. Preserve safety and release constraints when
condensing prose. Updates within library or exported starter trees still follow their source
identity and archive policies.

Stage new guidance files, then run these checks from the monorepo root:

```text
python scripts/verify_agent_guidance.py
python -m unittest discover -s scripts/tests -p test_agent_guidance.py
```

On Windows, `./scripts/verify-monorepo-policy.ps1` also checks all current Markdown links and
the broader source/release policy. CI runs both the guidance check and its regression tests.
The check requires shared instructions and skill assets to be tracked and not ignored, validates
the two adapters and root-guide size, and rejects missing local Markdown targets. Git ignore
exceptions explicitly retain shared guidance; local caches and overrides remain excluded.
