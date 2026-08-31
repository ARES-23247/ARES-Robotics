# ARES maintainability contracts

This document records the active, executable maintainability policy for the ARES Robotics
monorepo. Dated audit reports are evidence snapshots, not a backlog. The source, Gradle tasks, and
protected workflows named here are authoritative.

## Release verification

`ARES-Analytics/gradlew studioReleaseVerification` is the complete deterministic Studio release
contract. It runs all `app`, `shared`, and `gateway` tests, verifies each module's Kover line-coverage
floor, enforces the production-file size ratchet, checks release-version alignment, and runs the
dashboard performance baseline. The protected distribution workflow must complete this task against
the exact isolated ARES release-candidate repository before native packaging can start.

The initial measured line-coverage baselines are:

| Module | Measured baseline | Enforced floor |
|---|---:|---:|
| `app` | 38.61% | 38% |
| `shared` | 54.56% | 52% |
| `gateway` | 52.55% | 52% |

Floors are ratchets, not targets. New work should raise them when durable coverage improves. A
release must never lower a floor merely to make a failing build green.

## Source-size ratchets

Studio production Kotlin files are limited to 500 lines. Existing larger files from the 3.0.0
baseline are enumerated in
`ARES-Analytics/config/maintainability/large-production-kotlin-baseline.txt`. An existing entry may
shrink, but may not grow; a new production file may not exceed the limit. The
`verifyProductionKotlinFileSizes` task enforces this without requiring a risky mass rewrite.

ARESLib applies the same policy through `verifyAresLibSourceFileSizes` and
`ARESLib-Kotlin/config/maintainability/large-production-kotlin-baseline.txt`. The aggregate
`apiCheck` gate depends on that ratchet, so a published API cannot grow a grandfathered monolith or
introduce a new production file above 500 lines without first extracting a cohesive component.

When changing a grandfathered file, extract one cohesive responsibility when practical. Prefer
domain services, policies, codecs, and presentation components with direct tests over filename-only
splits or forwarding wrappers.

## Public API and compatibility policy

ARES has not shipped to student teams yet, so the source tree intentionally carries no compatibility
layer for abandoned prototypes or superseded project schemas. Remove an obsolete implementation,
alias, overload, topic, or schema branch outright and update every product, starter, generated
artifact, test, and document in the same change. Do not add deprecated delegating wrappers merely to
preserve an unreleased signature.

Published modules use Kotlin explicit-API mode where their surface is deliberately small:
`telemetry-schema`, `simulation-foundation`, `project-model`, `project-compiler`, and `frc-runtime`.
Serialized descriptor modules remain protected by binary API validation rather than hundreds of
mechanical visibility modifiers. `apiDump` is a reviewed source change; `apiCheck` is the release
gate. The monorepo policy verifier rejects known retired production types and the former FRC product
namespace.

The FRC product owns `org.aresfirst.marvin`; reusable FRC library code remains under
`com.areslib.frc`. Product and library packages must not be split across that boundary.

## Published wire contracts

Hardware topology is an ARES producer/consumer wire contract. Its canonical DTO, topic name, schema
version, and JSON codec live in the published ARESLib `telemetry-schema` module. Robot publishers and
Studio consumers import that module; they do not maintain structurally similar copies. Golden
producer/consumer serialization tests protect the NT4 representation.

Other cross-process contracts should follow the same rule: one SDK-independent published schema,
explicit versioning, deterministic codecs, and compatibility tests at both ends.

## Gradle ownership

Repository settings may read user or CI configuration, but must never create or modify files under
the user's Gradle home. A missing or unsupported JDK fails with an actionable message. The protected
distribution workflow runs representative configuration commands with an isolated Gradle user home
and fails if a build writes `gradle.properties` there.

Unpublished ARES changes are validated through `publishReleaseValidation` and one explicit candidate
version/repository pair. Ambient `mavenLocal()` contents and sibling source substitution are not
release evidence.

## Pathing ownership

`MecanumTrajectoryFollower` is the single FTC mecanum trajectory follower. The abandoned duplicate
`FtcMecanumPathingController` was removed at the next major ARES boundary rather than retained behind
a compatibility wrapper. Behavioral tests exercise the canonical follower.

## Evidence boundaries

Compilation, unit tests, generated-project verification, simulator motion, and native packaging are
separate evidence levels. None proves physical wiring, motor direction, radio behavior, real sensor
quality, or safe operation on hardware. Physical commissioning evidence remains an explicit later
step and is never inferred from a protected software release.
