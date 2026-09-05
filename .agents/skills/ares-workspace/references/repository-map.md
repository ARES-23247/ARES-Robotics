# Monorepo product map

| Product directory | Owns | Typical entry points |
|---|---|---|
| `ARESLib-Kotlin` | Shared math, Redux, hardware abstractions, sequencer, codegen, simulator, NT4 | `core/`, `ftc-hardware/`, `frc-hardware/`, `simulator/` |
| `ARES-FTC` | FTC season hardware, Redux composition, OpModes, generated project assets | `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/` |
| `ARES-FRC` | FRC season hardware, Marvin Redux/control, TimedRobot lifecycle, FRC simulation | `src/main/kotlin/org/aresfirst/marvin/` |
| `ARES-FTC-Starter` | Generic standalone FTC project exported by Studio | project root, `.ares/` |
| `ARES-FRC-Starter` | Generic standalone FRC project exported by Studio | project root, `.ares/` |
| `ARES-Analytics` | ARES Robotics Studio Compose UI, authoring tools, local log ingestion, DuckDB, desktop sync/gateway | `app/`, `shared/`, `gateway/` |
| `ARES-XRP-Starter` | MicroPython runtime integration, deterministic project generation, simulator, and deployment | `.ares/`, `tools/`, `deploy/`, `simulator/` |

## Cross-product boundaries

- ARESLib is the dependency root. Consumer fixes that expose a shared contract gap should update ARESLib rather than duplicate logic.
- Reusable `com.areslib.frc` classes belong to ARESLib; the FRC season product owns `org.aresfirst.marvin`. FTC follows the same ownership boundary even where SDK package names overlap.
- NT4 topic names and packed layouts are APIs. Update every producer, receiver, test, and contract document together.
- Canonical autonomous authoring uses `.ares` catalogs/routines and generated project source. Do not restore removed legacy auto formats.
- GUI-owned projects treat canonical `.ares` documents as source of truth and place generated runtime
  and verification code under Gradle generated directories. Code-first and hybrid projects keep
  handwritten Kotlin in explicit USER-OWNED extension packages and publish registration metadata;
  Studio does not reverse-engineer arbitrary Kotlin.
