# In-app documentation improvement goal

Created: 2026-08-17 · Owner: software subteam · Review: append completed workstreams to [CYCLE_LOG.md](CYCLE_LOG.md) as a numbered cycle.

**Goal statement:** every place a team member can be confused in ARES Robotics Studio has an accurate, discoverable, in-app explanation one action away — and every explanation states what it does *not* prove.

This goal follows the workspace truthfulness rules (AGENTS.md §9): no completeness claims, no promotional language, explicit fidelity boundaries in teaching content.

## Verified baseline (2026-08-17)

- Academy: 32 lessons, 9 interactive labs, 6 role paths (`ui/help/LearningCatalog.kt`).
- Developer Reference: 15 source-backed entries (`ui/help/DeveloperReferenceCatalog.kt`).
- Contextual Help mapping covers 14 of 22 navigation targets.
- Onboarding shows a "How this system works" orientation card on the PROJECT step (`ui/screens/onboarding/WelcomeStep.kt`).
- Written docs layer: `docs/INDEX.md` task table, `docs/learn/GLOSSARY.md` (first-year definitions with mentor notes), mentor guides.
- Completed this cycle: `why-documents` and `why-immutability` lessons; first-mission path retitled "New team member · First mission"; Developer Reference 8 → 15 entries; ACADEMY contextual mapping; onboarding orientation card; game-piece dialog terminology.

## Workstreams

### G1 · Surface the glossary inside the app — priority 1 — **delivered 2026-08-17**

**Problem.** `docs/learn/GLOSSARY.md` exists, and "glossary" is a Ctrl+K/Academy search keyword, but the word resolves to no glossary content inside the app. A team member mid-lesson who meets "CCW-positive" or "neutral output" must leave the app to look it up.

**Change.** Port the glossary to `ui/help/GlossaryCatalog.kt` (term, short definition, mentor note, cross-links to lesson ids and Developer Reference ids). Surface it as an Academy section and in Ctrl+K results. Keep `docs/learn/GLOSSARY.md` authoritative or add a sync check — do not maintain two divergent copies silently.

**Acceptance.**
- Searching a glossary term in Academy and Ctrl+K returns the term.
- Every term cross-links to at least one lesson or Developer Reference entry (test-enforced).
- If both the markdown and catalog exist, a unit test compares term sets and fails on drift.

**Delivered.** `ui/help/GlossaryCatalog.kt` ports all 33 terms verbatim; the Academy has a Glossary pane (entry button in the catalog header, cross-links open the owning lesson in-place or the Developer Reference); Ctrl+K shows glossary results that deep-link into the pane. `GlossaryCatalogTest` enforces link resolution, coverage, search, and term-set parity with `docs/learn/GLOSSARY.md`. Two terms (ADB, Gateway) are deliberately unlinked in `unlinkableTerms` until their owning lessons exist — Gateway awaits the G2 Cloud Sync lesson.

### G2 · Contextual help for the six unmapped screens — priority 1 — **delivered 2026-08-17**

**Problem.** `contextualLessonIds` covers 14 targets. FIELD_EDITOR, CLOUD, MATCH_STRATEGY, GUIDED_RUN_ANALYSIS, DATABASE_VIEWER, and HARDWARE_SETUP have no `lessonFor()` result, so their Help affordance (or lack of one) teaches nothing. PROFILE and ADMIN legitimately have none.

**Change.** Write doc-accurate lessons for field documents (Field Editor), the offline-first pull model (Cloud Sync), strategy previews, guided review (or map to `compare-run-evidence` if that is accurate), the local DuckDB store, and hardware setup; then extend the map.

**Acceptance.**
- `LearningCatalog.lessonFor(target)` is non-null for every `NavigationTarget` except PROFILE and ADMIN (extend `LearningCatalogTest.contextual help opens the lesson for the active workflow`).
- Each new lesson keeps the house shape: outcome, before-you-start boundaries, steps, success criteria, safety note, checkpoints.

**Delivered.** Five new lessons: `understand-offline-sync` (Cloud), `edit-field-documents` (Field Editor), `read-driver-coaching` (Strategy Preview), `query-stored-telemetry` (Database), and `review-hardware-addresses` (Hardware Setup); GUIDED_RUN_ANALYSIS maps to the existing `compare-run-evidence`. A new test (`every workflow screen except profile and admin has contextual help`) enforces the blanket invariant. Lessons were placed into the driver-operator, robot-builder, data-analyst, and mentor paths after their path heads, preserving all tested recommendation orderings. The Gateway glossary term now links to `understand-offline-sync` and left the allowlist (ADB remains).

### G3 · Widen the Academy search corpus — priority 2 — **delivered 2026-08-17**

**Problem.** `LearningCatalog.search` matches only title, outcome, track, level, and keywords. Lesson *steps* are invisible: searching "hysteresis" or "deadband" finds nothing even though `map-one-control` teaches them in its steps.

**Change.** Include `steps` and `beforeYouStart` text in the match corpus. Guard the existing behavioral tests — notably `search("sysid", LearningLevel.STARTER)` must stay empty, so audit STARTER lesson steps for that token before/after.

**Acceptance.** Existing help-suite tests stay green; a step-only vocabulary term returns the lesson that teaches it.

**Delivered.** `LearningCatalog.search` now matches lesson `steps` and `beforeYouStart` text in addition to title/outcome/track/level/keywords. The "sysid" STARTER emptiness test stayed green (the only lessons mentioning SysId are BUILDER level). Searching a step-only term such as "hysteresis" now returns `map-one-control`.

### G4 · Error-to-lesson deep links — priority 2

**Problem.** When validation rejects a routine, subsystem, or binding, the message says what is wrong but not where to learn the concept behind it. The blog-post feedback loop ("mistake found → fix it here, with a concrete error") has no in-app learning equivalent.

**Change.** Add a "Learn why" affordance next to validation ERROR items, opening the owning lesson (`first-routine`, `safe-subsystem`, `map-one-control`) at a matching checkpoint. Start with PathPlanner only; replicate after review.

**Acceptance.** PathPlanner validation errors link to a lesson that names the violated concept; link targets are test-enumerated, not free-text.

### G5 · Post-onboarding nudge and authoring empty states — priority 3

**Problem.** After setup completes, nothing suggests the first-mission path. Authoring screens with no project loaded show empty states that do not explain what the screen is for.

**Change.** One post-onboarding suggestion of the first-mission path (dismissible, remembered); one-sentence purpose text in each authoring screen's empty state.

**Acceptance.** A fresh workspace reaches the first mission within two actions of finishing onboarding; empty states state purpose without claiming capability the screen lacks.

### G6 · Terminology and two-layer sync — priority 3

**Problem.** User-visible "student" strings were replaced with "team member" in the app, but older written evidence (for example CYCLE_LOG cycle 1 naming the "New student" path) still uses the old term, and the app/help layer and `docs/` layer can drift apart silently.

**Change.** Sweep user-facing written docs for the terminology change where they describe *current* behavior (leave dated cycle-history entries as history). Add a lightweight convention note in this file's review step: when a lesson or dev-ref entry changes, check the matching `docs/` page the same PR.

**Acceptance.** No current-behavior doc names the retired term; each G1–G5 change that has a docs counterpart updates it in the same change.

## Non-goals

- Replacing the written docs layer or generating full API documentation (the Developer Reference stays a small, source-backed map).
- Video or screenshot production.
- Any help text that implies certification or proves physical safety.

## Verification

- `.\gradlew.bat :app:test --tests "com.ares.analytics.ui.help.*"` after every workstream.
- Help-text review against AGENTS.md §9: claims cite screens/files, state limits, and never promise hardware safety.
