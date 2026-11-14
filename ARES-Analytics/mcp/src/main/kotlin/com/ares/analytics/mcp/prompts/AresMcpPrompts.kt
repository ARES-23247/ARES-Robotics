package com.ares.analytics.mcp.prompts

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/** Registers repeatable guided MCP workflows for subsystem authoring, auto routines, hand-authored migrations, and audits. */
internal object AresMcpPrompts {
    fun register(server: Server) {
        server.addPrompt(
            name = "ares-new-subsystem",
            description = "End-to-end guided workflow to author, validate, generate starters/plumbing, and statically audit a new ARES subsystem.",
            arguments = listOf(
                PromptArgument(name = "subsystemId", description = "Lowercase documentId (e.g. 'intake')", required = true),
                PromptArgument(name = "projectPath", description = "Optional path to robot repo root", required = false),
            ),
        ) { request ->
            val subsystemId = request.arguments?.get("subsystemId").orEmpty().ifBlank { "new-subsystem" }
            val projectPath = request.arguments?.get("projectPath").orEmpty().ifBlank { "<active-project>" }
            singleUserPrompt(
                description = "Author and verify subsystem '$subsystemId'",
                text = """
                    |Follow the ARES subsystem workflow for subsystemId='$subsystemId' in '$projectPath':
                    |1. Read resource `ares://conventions/coordinates-and-safety` and `ares://schemas/subsystem`.
                    |2. Call `ares_project_inspect` to check existing hardware, action keys, and league.
                    |3. Author `.ares/subsystems/$subsystemId.aressubsystem` via `ares_subsystem_mutate` (or `ares_descriptor_mutate` in compact mode).
                    |4. Call `ares_project_validate` to verify `RobotProjectAssembler` and compiler IR lowering.
                    |5. Run `ares_codegen_reconcile` (`mode='PREVIEW'` first, then `'APPLY'` with confirmationToken if replacing starters).
                    |6. If adding `USER-OWNED` extensions or tests, preview via `ares_kotlin_scaffold` then run `ares_code_audit`.
                """.trimMargin(),
            )
        }

        server.addPrompt(
            name = "ares-build-autonomous",
            description = "Author and validate a match autonomous routine registered in autonomous-catalog.json.",
            arguments = listOf(
                PromptArgument(name = "routineId", description = "Lowercase routine documentId", required = true),
                PromptArgument(name = "projectPath", description = "Optional path to robot repo root", required = false),
            ),
        ) { request ->
            val routineId = request.arguments?.get("routineId").orEmpty().ifBlank { "match-auto" }
            val projectPath = request.arguments?.get("projectPath").orEmpty().ifBlank { "<active-project>" }
            singleUserPrompt(
                description = "Author autonomous routine '$routineId'",
                text = """
                    |Build autonomous routine '$routineId' in '$projectPath':
                    |1. Call `ares_project_inspect` and read `ares://project/catalog` to verify valid action keys and coordinate convention (`CCW+ radians`).
                    |2. Call `ares_routine_mutate` with `registerAutonomousDisplayName` so it appears in the INIT selector.
                    |3. Run `ares_project_validate` and `ares_codegen_reconcile`.
                """.trimMargin(),
            )
        }

        server.addPrompt(
            name = "ares-migrate-hand-authored",
            description = "Safely scaffold USER-OWNED Redux slices, hardware IO adapters, and mock-IO tests without clobbering user code.",
            arguments = listOf(
                PromptArgument(name = "className", description = "Target PascalCase Kotlin class root", required = true),
                PromptArgument(name = "league", description = "FTC or FRC", required = true),
            ),
        ) { request ->
            val className = request.arguments?.get("className").orEmpty().ifBlank { "CustomSubsystem" }
            val league = request.arguments?.get("league").orEmpty().ifBlank { "FTC" }
            singleUserPrompt(
                description = "Migrate '$className' ($league) to USER-OWNED Redux + Mock IO",
                text = """
                    |Scaffold clean zero-GC USER-OWNED Kotlin components for '$className' ($league):
                    |1. Preview `SEASON_REDUX_SLICE`, `HARDWARE_IO`, and `UNIT_TEST` with `ares_kotlin_scaffold` (`mode='PREVIEW'`).
                    |2. Review generated code and apply with `mode='APPLY'` (pass `confirmationToken` when updating an existing file).
                    |3. Run `ares_code_audit` to verify all 8 static rules (`ARES-GC-001`..`ARES-SIM-008`) pass with 0 errors.
                """.trimMargin(),
            )
        }

        server.addPrompt(
            name = "ares-audit-readiness",
            description = "Run full descriptor validation plus static Kotlin invariant audit and report concrete blockers.",
            arguments = listOf(
                PromptArgument(name = "projectPath", description = "Optional path to robot repo root", required = false),
            ),
        ) { request ->
            val projectPath = request.arguments?.get("projectPath").orEmpty().ifBlank { "<active-project>" }
            singleUserPrompt(
                description = "Readiness audit for '$projectPath'",
                text = """
                    |Audit project readiness for '$projectPath':
                    |1. Run `ares_project_validate` to check canonical `.ares/*` schemas and compiler IR lowering.
                    |2. Run `ares_code_audit` to enforce all 8 static rules (`ARES-GC-001`..`ARES-SIM-008`).
                    |3. Summarize blockers cleanly and explicitly note that desktop/static validation does not replace physical Pit Self-Test commissioning.
                """.trimMargin(),
            )
        }
    }

    private fun singleUserPrompt(description: String, text: String): GetPromptResult =
        GetPromptResult(
            description = description,
            messages = listOf(PromptMessage(role = Role.User, content = TextContent(text = text))),
        )
}
