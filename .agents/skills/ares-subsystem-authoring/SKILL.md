---
name: ares-subsystem-authoring
description: Author or review ARES subsystem descriptors, generated source, hand-authored registration, and the Studio subsystem builder.
---

# ARES Subsystem Authoring

Use the live descriptor and implementation to establish ownership. `GENERATED_STARTER` creates
editable starter source for new subsystems; `HAND_AUTHORED` declares existing Kotlin explicitly.
Never infer ownership from arbitrary Kotlin or overwrite USER-OWNED source.

## Ownership and generation

- USER-OWNED files are never replacement-token eligible. Replacing a GENERATED STARTER requires
  a structured diff preview and the exact current confirmation token.
- Mechanical plumbing belongs in Gradle generated-source directories; editable starters stay in
  normal source directories. Emit deterministic output with ownership headers.
- Hand-authored descriptors generate no starter/mock/test source.
- Preserve domain, control, IO, hardware, simulation, lifecycle/registration, and verification boundaries.

## Select the contract

- For descriptors, generation, or registration, use [descriptor-contract.md](references/descriptor-contract.md).
- For actuator behavior, builder UI, generator review, or migration verification, use the applicable
  sections of [review-checklist.md](references/review-checklist.md).
- Before broad migration, compare a representative subsystem's customization points, safety
  coverage, regeneration behavior, build integration, and cost. File count alone does not justify
  rewriting a working subsystem; retain safe hand-authored examples.

Controller-binding actions must exist in the project catalog and route through NamedCommands/tasks/Redux.
Label capabilities with explicit text and descriptions; color alone is insufficient. Hardware-gate
optional capabilities such as Prism lighting.

Complete the requested authoring flow through its affected generation, registration, and behavior
checks. Product preview/token confirmation applies at starter replacement; it does not prevent
preparing the descriptor, diff, or validation evidence.

## Using `ares-project-mcp` for AI-guided authoring

When editing an external FTC or FRC team repository from an AI assistant connected to `ares-project-mcp`:
- Start with `ares_project_inspect` / `ares_project_validate` (or `ares://project/summary` & `ares://schemas/{documentKind}`).
- Author descriptors through `ares_subsystem_mutate` / `ares_descriptor_mutate` rather than hand-editing `.ares/` JSON directly so compiler IR validation and JGit `refs/ares-mcp/snapshots/*` rollback protection apply automatically.
- Reconcile plumbing with `ares_codegen_reconcile` (`PREVIEW` -> `APPLY` with `confirmationToken` if starters change), scaffold `USER-OWNED` adapters via `ares_kotlin_scaffold`, and verify `0` errors in `ares_code_audit`.
