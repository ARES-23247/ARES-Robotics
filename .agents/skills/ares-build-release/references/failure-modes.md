# Build and dependency failure modes

- **Artifact not found**: confirm whether the requested version is published to Maven Central or the monorepo Maven branch. Use sibling substitution or the isolated release repository for unpublished work.
- **Wrong local artifact**: avoid implicit `mavenLocal`; inspect coordinates and Gradle properties.
- **Relative `aresRepository` failure**: pass an absolute file URI/path because repository resolution may occur from a subproject directory.
- **Generated source missing**: ensure compile depends only on generated-plumbing preparation; creating editable starters must remain an explicit confirmed action.
- **Stale Gradle outputs**: inspect active commands and wait for the owner of the affected module outputs. Rerun the affected tasks only after that build finishes; follow the desktop recovery guide for missing-class failures. Never stop another agent's build or delete its outputs underneath it.
- **Concurrent Kotlin/Gradle corruption**: serialize builds that write the same module outputs or local publication repository.
- **Partial Kotlin upgrade**: keep the JVM/Android/serialization/Compose compiler plugins,
  forced standard-library modules, and embedded generated-code test compiler in one cohort across
  the six isolated products. Run `python scripts/verify_kotlin_toolchain.py`; updating only the
  dependency-bot plugin declaration can leave an older runtime or test compiler in place. Rebuild
  the library candidate before consumers, and review API-check differences rather than rewriting
  committed API baselines to make the check pass. For compiler-generated API additions, compare
  old/new bytecode and exercise a precompiled client before accepting an explicit baseline change.
  `gradlew --version` reports Gradle's embedded Kotlin, separately from the project's compiler;
  use the resolved project plugins/compiler artifacts when verifying the cohort.
- **Maven Central delay**: a staged deployment is not public until reviewed and published; allow propagation time after publication.
- **Repository TLS failure**: preserve the failing URL, JDK vendor/version, and certificate-chain error.
  Reproduce with that JDK's truststore; OS/browser trust and warm Gradle caches do not establish
  Java trust. Compare a maintained JDK truststore before changing repository URLs. Never disable
  certificate/hostname verification or import an unverified server certificate as a trusted root.

## FTC SDK compatibility

When reviewing an FTC SDK upgrade or claiming support for the latest official release, compare
FIRST's current release notes and artifacts with both the season project's and starter's pins.
Validate affected adapters against the real SDK classes as well as desktop mocks. Check binary
member shape, including Java fields versus Kotlin-generated getters; a mock-only compile can hide
linkage failures on the controller. Review changed vision semantics such as detection subtypes,
units, and whether a target can provide a stationary field reference. Record source/API probes,
Android builds, simulator checks, and physical-controller checks as separate evidence.

## Test failures and performance evidence

- Real Compose/AWT lifecycle tests on Linux require a display, including tests without a visible
  application frame. Use the CI virtual display (`xvfb-run -a`) instead of skipping these tests.
  Retain XML/HTML for every test task and nested consumer logs on failure; Gradle's short exception
  format and a `reports/tests/test`-only artifact can hide custom-task failures.
- A successful CodeQL analysis/upload job does not establish that its separate PR scanning check
  passed. Inspect annotations and affected source semantics. Keep sample fixes consistent across
  season/starter copies, and create new archive versions when exported bytes change.
- Real socket/coalescing tests must observe actual receipt and sampler progress before asserting
  expiry. Advancing a fake receipt clock while older wire/UI work is queued can make that work
  appear newly received after the jump. Keep deterministic clock tests separate from the real-wire
  test, and preserve its existing freshness threshold and timeout budget.
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
