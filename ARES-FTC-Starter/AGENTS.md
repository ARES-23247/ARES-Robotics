# ARES FTC Starter agent guide

Read the monorepo root `AGENTS.md` first; it owns cross-product release, safety, telemetry, and
validation contracts.

This directory is the generic, simulation-first FTC starter consumed by ARES Robotics Studio. It is not
a competition-season repository and must never absorb team-specific hardware constants, autonomous
routines, field assets, mechanism code, credentials, or calibration values.

- Canonical `.ares` documents are the robot source of truth.
- Generated mechanical Kotlin belongs under `TeamCode/build/generated/ares`.
- Files marked `GENERATED STARTER` may be customized only through an explicit reviewed diff.
- Keep Redux state immutable, hardware reads cached once per loop, outputs fail-safe, and robot/mock
  behavior aligned.
- Default builds resolve immutable ARES artifacts. For unreleased ARESLib validation, use an explicit
  prerelease `-ParesVersion` and absolute `-ParesRepository=file:///.../build/release-repository`.
- A simulator pass is not physical validation. Do not weaken template provenance or Hardware Review
  deployment gates.
