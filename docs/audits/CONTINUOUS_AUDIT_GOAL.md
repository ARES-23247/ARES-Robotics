# Continuous Evidence-Backed Audit Goal

## 1. Objective
Establish an ongoing, systematic audit campaign to review, validate, and burn down the remaining pending and stale inventory across the ARES monorepo. Every authored file in the target inventory must receive substantive, documented inspection, test-backed verification, and a cryptographically hashed record in `docs/audits/file-reviews.json`, strictly adhering to the bounded audit framework established in PR #104 and `.agents/skills/ares-workspace/references/audit-workflow.md`.

All work is kept strictly local on an isolated feature branch. Changes are never pushed, merged, published, or released without explicit user authorization.

---

## 2. Baseline & Inventory Status
- **Branch:** `codex/bounded-evidence-audit`
- **Initial Baseline Commit:** `d51c14399`
- **Inventory Metrics (at Campaign Start):**
  - Total Tracked Files: 2,970
  - Reviewed Files: 2,690 (90.6%)
  - Pending Files: 236
  - Stale Files: 44
  - Total Remaining Workload: 280 files

---

## 3. Mandatory Cycle Protocol (Batch Invariants)
Every audit cycle operates over a manageable batch of **15–25 coherent authored files** adhering to the following protocol:

1. **Coordinator-Worker Separation:**
   - Single coordinator writer owns all edits, test executions, ledger updates, and commits.
   - Up to 3 parallel read-only research subagents inspect code structure, concurrency, and lifecycle boundaries.
2. **Behavioral Defect Prioritization & Test-First Reproducers:**
   - Priority given to realistic runtime defects: lost commands, race conditions, unhandled exceptions, resource leaks, coordinate/unit errors, and deadlock scenarios.
   - For every confirmed defect, write an automated failing reproducer unit test *before* or *alongside* the fix. Verify the reproducer fails on the unpatched code and passes on the patched code.
3. **Maintainability & Size Ceiling:**
   - All production Kotlin files must strictly respect `ARES-Analytics/config/maintainability/large-production-kotlin-baseline.txt` (<= 750 lines for all non-grandfathered files).
   - Zero opportunistic refactor sprawl; edits remain minimal, clean, and targeted.
4. **Verification & Real Evidence:**
   - Run applicable Gradle test suites via local wrappers (`./gradlew.bat`).
   - Extract and verify exact XML test reports (`app/build/test-results/test/TEST-*.xml`) for test counts, passing status, and execution times.
   - Explicitly document that physical FTC Control Hub, roboRIO, and XRP robot hardware remain offline, noting that all communication is software/loopback verified.
5. **Ledger Accounting & Monorepo Policy Verification:**
   - Record exact SHA-256 content hashes, review scopes, and XML evidence paths in `docs/audits/file-reviews.json`.
   - Regenerate maintainability ledger via `python scripts/generate_codebase_ledger.py`.
   - Run and pass `pwsh -File scripts/verify-monorepo-policy.ps1` (0 policy errors, 0 broken links, 0 line ratchet violations).
6. **Milestone Commit:**
   - Create an atomic git milestone commit on `codex/bounded-evidence-audit` summarizing the batch fixes, test metrics, and inventory progress.

---

## 4. Multi-Cycle Batch Roadmap & Status

| Cycle | Target Area | File Count | Target Scope | Status | Commit / Notes |
|---|---|---|---|---|---|
| **Batch 1** | Log Ingestion & NT4 Client Services | 18 | `AutoImportService`, `Nt4ClientService`, run comparison, observation pruning, atomic CAS rate-limiting | **Complete** | `3b8d51dc8` (54 tests passed) |
| **Batch 2** | Cloud Integration, OAuth & Platform Services | 22 | `GoogleDriveService` query escaping (\'), `OAuthLoopbackServer` port collision resilience, `SyncEngine` atomicity | **Complete** | `d51c14399` (72 tests passed) |
| **Batch 3** | Analytics ViewModels & State Flows | ~22 | `OnboardingViewModel`, `ProfileViewModel`, `RunComparisonViewModel`, coroutine scopes, state emission safety | *In Progress* | Next cycle |
| **Batch 4** | Analytics Desktop UI, Panels & Dialogs | ~24 | Compose desktop lifecycles, memory retention, window state boundaries, layout dialogs | *Planned* | |
| **Batch 5** | Analytics Ingestion & DuckDB Pipelines | ~25 | Parquet framing, DuckDB thread serialization, batch telemetry database writes | *Planned* | |
| **Batch 6** | Analytics Preferences, Theming & Configuration | ~24 | Desktop settings persistence, atomic file serialization, theme state management | *Planned* | |
| **Batch 7** | ARESLib Core Control, Kinematics & Estimators | ~34 | Zero-allocation hot loops, radian/CCW coordinate standards, matrix operations | *Planned* | |
| **Batch 8** | ARESLib Hardware Drivers & FTC Mocks | ~30 | Mock driver fidelity, disconnect recovery, timeout bounds, threading safety | *Planned* | |
| **Batch 9** | FTC Starter SDK Controller Activities (Part 1) | ~34 | Upstream SDK boundary compliance, OpMode lifecycles, activity lifecycle transitions | *Planned* | |
| **Batch 10** | FTC Starter SDK Controller Activities (Part 2) | ~34 | Hardware event loop safety, telemetry rate limiting, network communication | *Planned* | |
| **Batch 11** | FTC Biobuzz Autonomous & Starter Templates | ~25 | Biobuzz autonomous action sequences, starter project templates, descriptor generation | *Planned* | |
| **Batch 12** | Tooling, Stale Scripts & Final Convergence | ~18 | Monorepo maintenance scripts, stale review records reconciliation, final verification | *Planned* | |

---

## 5. Campaign Exit Criteria
The continuous audit campaign is complete when:
1. Every file in `.codex-validation/audit-inventory.json` has `reviewStatus == "reviewed"` with 0 pending and 0 stale entries.
2. `docs/audits/file-reviews.json` is fully reconciled with matching SHA-256 hashes for all tracked files.
3. All unit and integration test suites pass with 0 failures across all products.
4. `pwsh -File scripts/verify-monorepo-policy.ps1` passes with 0 violations.
5. All completed batches are cleanly committed on `codex/bounded-evidence-audit`.
