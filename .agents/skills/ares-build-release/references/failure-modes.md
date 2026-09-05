# Build and dependency failure modes

- **Artifact not found**: confirm whether the requested version is published to Maven Central or the monorepo Maven branch. Use sibling substitution or the isolated release repository for unpublished work.
- **Wrong local artifact**: avoid implicit `mavenLocal`; inspect coordinates and Gradle properties.
- **Relative `aresRepository` failure**: pass an absolute file URI/path because repository resolution may occur from a subproject directory.
- **Generated source missing**: ensure compile depends only on generated-plumbing preparation; creating editable starters must remain an explicit confirmed action.
- **Stale Gradle outputs**: inspect active commands and wait for the owner of the affected module outputs. Rerun the affected tasks only after that build finishes; follow the desktop recovery guide for missing-class failures. Never stop another agent's build or delete its outputs underneath it.
- **Concurrent Kotlin/Gradle corruption**: serialize builds that write the same module outputs or local publication repository.
- **Maven Central delay**: a staged deployment is not public until reviewed and published; allow propagation time after publication.
