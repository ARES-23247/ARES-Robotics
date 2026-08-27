# ARES FRC Starter agent guide

Read the workspace `AGENTS.md` first. This repository is a generic product template, not Team 23247's
season robot. Preserve these invariants:

- `.ares` documents are canonical and generated output is deterministic.
- Physical outputs remain blocked until a reviewed hardware adapter exists.
- Simulation evidence is never described as physical validation.
- Do not add `mavenLocal()` or automatic sibling substitution.
- Use `RobotClock`, immutable Redux state, cached hardware inputs, neutral-on-disable behavior, and
  zero-allocation production hot paths.
- Keep the starter free of season-specific mechanisms and vendor-generated drivetrain source.
