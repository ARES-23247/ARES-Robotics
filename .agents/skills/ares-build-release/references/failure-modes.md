# Build and dependency failure modes

- **Artifact not found**: confirm whether the requested version is published to Maven Central or the monorepo Maven branch. Use sibling substitution or the isolated release repository for unpublished work.
- **Wrong local artifact**: avoid implicit `mavenLocal`; inspect coordinates and Gradle properties.
- **Relative `aresRepository` failure**: pass an absolute file URI/path because repository resolution may occur from a subproject directory.
- **Generated source missing**: ensure compile depends only on generated-plumbing preparation; creating editable starters must remain an explicit confirmed action.
- **Stale Gradle outputs**: inspect active commands and wait for the owner of the affected module outputs. Rerun the affected tasks only after that build finishes; follow the desktop recovery guide for missing-class failures. Never stop another agent's build or delete its outputs underneath it.
- **Concurrent Kotlin/Gradle corruption**: serialize builds that write the same module outputs or local publication repository.
- **Maven Central delay**: a staged deployment is not public until reviewed and published; allow propagation time after publication.

## Test failures and performance evidence

- Preserve the failing command, source/candidate identity, environment, and assertion before
  retrying. Distinguish application failures from infrastructure failures that prevented execution.
  A passing rerun alone does not resolve a race or prove a test is flaky. Compare the affected path
  with the baseline and use a targeted reproducer when possible; record uncertainty when unresolved.
- Retry a diagnosed transient infrastructure failure at the same source identity, limiting reruns
  to affected jobs where supported. Do not weaken assertions or increase budgets to obtain green CI.
  Keep failed observations alongside subsequent results.
- Record the workload, platform, JVM/runtime options, warmup, sample size, measurement method, and
  intended budget before making performance claims. For loop checks, distinguish configured pacing
  and elapsed loop period from computation time; report distributions, worst observed delay, and
  missed deadlines. Measure allocations and sensor-to-output latency where instrumentation permits,
  and explicitly identify missing measurements.
- A warmed JVM can hide boxing through escape analysis. When an allocation regression differs
  between local and CI runs, inspect bytecode or profiler evidence and use fresh JVMs; disabling
  escape analysis can help diagnose an implicated path. Such diagnostic runs supplement the
  representative benchmark; they are not a new universal release gate.
- Measure before and after under comparable conditions. Scope zero-allocation claims to the tested
  path and runtime. Desktop/simulator timing does not establish target-controller performance;
  preserve the hardware checkpoint when hardware is unavailable.
