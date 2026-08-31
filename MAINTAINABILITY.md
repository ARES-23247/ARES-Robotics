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

## Source-size ratchet

Studio production Kotlin files are limited to 500 lines. Existing larger files from the 3.0.0
baseline are enumerated in
`ARES-Analytics/config/maintainability/large-production-kotlin-baseline.txt`. An existing entry may
shrink, but may not grow; a new production file may not exceed the limit. The
`verifyProductionKotlinFileSizes` task enforces this without requiring a risky mass rewrite.

When changing a grandfathered file, extract one cohesive responsibility when practical. Prefer
domain services, policies, codecs, and presentation components with direct tests over filename-only
splits or forwarding wrappers.

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
