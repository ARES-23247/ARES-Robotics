# Local Studio and template alignment follow-up

**Complete locally, 2026-09-15.** Normal Studio tests, the official FTC/FRC/XRP project journeys and
local release-alignment gates pass. Hardware and protected release validation remain separate.

This is the authorized follow-up to the completed [robot-readiness checkpoint](ROBOT_READINESS_REPORT.md),
on `codex/robot-loop-math-audit`, starting at `c88e00583960c1429cdc286e0badfde63626bb59`.
The open-ended audit remains superseded. No external release, push, merge or deployment is included.

## Changes and origin

- The existing distribution pins and bundled archives were behind the canonical release manifest.
  Installed the explicitly approved FTC/FRC **19.0.0** and XRP/Lightbot **3.0.54** archives, all with
  the standalone ARES dependency pin **19.0.1**, and aligned the Studio **7.0.55** workflow URLs/hashes.
  Superseded ZIPs remain in Git history and local evidence backups. No existing version was overwritten.
- Each archive was built independently twice with the existing deterministic exporter. Both builds
  match the installed SHA-256 pins. Entry paths, unique names, standalone manifests and excluded local
  state were checked. The content comparison records the intervening source fixes and tests; these
  archives differ in more than version metadata.
- The release integration job now prepares hash-checked copies of the pinned installer archives.
  Previously it recreated ZIPs with `Compress-Archive`, while the template service preferred bundled
  resources and left those separately recreated bytes unused. The existing deterministic archive
  job still checks source reproducibility. This changes preparation, not the release gates.
- The first full Studio run exposed a wall-clock timeout in `UiTelemetryFanoutTest`. Its unchanged
  two-test class passed in isolation. Both coalescing/reset tests now use the existing coroutine test
  scheduler and an automatically cancelled background scope, retaining the timeout and value assertions.
  This is test reliability work; no production telemetry or robot behavior changed. The exact cause
  of the original scheduling delay was not established.

The ARESLib tree is unchanged: `6d49e143e3a919569c5b3d933b8a8200adeb9a36`. Validation reuses
`19.0.1-rc.6d49e143e3a9` from the isolated local repository; all **410** candidate artifact hashes
still match the completed readiness evidence. No candidate was rebuilt or published externally.

## Observed validation

- Monorepo policy, release preflight and installed archive hashes pass without bypasses.
- Exporter/policy tests: **20 pass**. Changed-part routing/result tests: **25 pass**. The updated CI
  preparation block was executed locally and produced the exact approved FTC/FRC/XRP hashes.
- The initial full Studio run passed shared **31** and gateway **18** tests. App reported **2,611**
  results, with the single telemetry timeout above and **5** explicit opt-in skips. Original XML/logs
  and the successful unchanged telemetry retry are retained.
- The official archive integration actually ran. Production project creation verified bundled hashes,
  personalized FTC/FRC/XRP projects, initialized clean local history, then generated and built each
  project against the candidate. FTC passed **17** tests, generated verification, simulated drivetrain
  acceptance and debug APK assembly. FRC passed **206** tests, verification and packaging. XRP's native
  Python wrapper passed **121** tests. All three completed history checkpoints and portable exports
  excluding Git/build internals. Fresh project roots and logs remain in the local evidence directory.
- Final normal Studio validation passes: shared **31**, gateway **18**, and app **2,605 passing / 6
  skipped**, with no failures or errors. The official-template fixture is one of those final skips
  because its successful earlier execution is retained; the other five opt-ins are described below.
  Across the two runs, **2,655 distinct Studio checks** pass. The changed telemetry class also passes
  its focused check. The final normal invocation completed in **5 m 34 s**, reusing unchanged shared,
  gateway and compilation results.

These are desktop, simulated IO and packaging results. Build-cache reuse is present in the fresh FTC
project and is retained in its log. The initial Studio invocation crossed an overnight interval; its
reported nine-hour elapsed duration is not a throughput or robot-performance measurement.

## Reproduction and retained evidence

Run from the isolated worktree. The existing candidate repository is
`ARESLib-Kotlin/build/release-repository`. Its identity and prerequisite library/consumer validation
are preserved in [the readiness evidence](checkpoints/robot-readiness.json).

```powershell
./scripts/verify-monorepo-policy.ps1
python -m unittest scripts.tests.test_starter_export scripts.tests.test_monorepo_policy
python -m unittest scripts.tests.test_classify_ci_paths scripts.tests.test_check_ci_results
```

From `ARES-Analytics`, run `:shared:test :gateway:test :app:test` with
`-ParesVersion=19.0.1-rc.6d49e143e3a9` and `-ParesRepository=file:///<absolute-candidate-repository>`.
Local runs used `--no-parallel --max-workers=1` and a retained init script setting test startup heap
to 32 MB and one test fork. The normal release gate remains enabled.

To reproduce the official integration, prepare `ftc.zip`, `frc.zip` and `xrp.zip` as exact copies of
the pinned bundled files. Set `ARES_OFFICIAL_TEMPLATE_ARCHIVE_DIR`, a **fresh**
`ARES_OFFICIAL_TEMPLATE_OUTPUT_DIR`, `ARES_OFFICIAL_TEMPLATE_VALIDATION_REPOSITORY` and
`ARES_OFFICIAL_TEMPLATE_VALIDATION_VERSION`, then run the existing
`com.ares.analytics.service.project.OfficialProjectTemplateIntegrationTest` through `:app:test`.
The output must be a disposable owned directory because the fixture replaces its generated projects.

Full logs, original/final XML, archive comparisons, old ZIP backups and fresh projects are under
`ARES-Analytics/build/release-alignment/`. The [durable checkpoint](checkpoints/release-alignment.json)
binds their hashes and the reviewed source files. The historical robot-readiness JSON remains unchanged.
An initial local CI-step extraction/module-name error is retained alongside its corrected passing
invocation; it did not indicate a product or workflow failure.

## Remaining limits

The opt-in generic-candidate and representative-project checks were not selected in this run;
the official pinned-template journey above was selected instead. Native file-dialog interaction,
the separate dashboard performance-baseline task and physical dashboard telemetry were not run.
Offscreen rendering tests do not establish a usable live Studio window.

Lightbot archive integrity, deterministic source export and the prior FTC source-consumer validation
are retained; this follow-up's fresh-project journey covers the three generic starters, not a new
Lightbot journey. Signed desktop installers, protected remote CI/promotion and public artifact
availability have not been validated here. Local pin alignment is not release approval.

The next robot-readiness action remains the documented physical-controller checkpoint: verify applied
neutral outputs, cancellation/freshness behavior, calibration and sustained target loop/heap budgets
on the actual FTC, FRC and XRP controllers. No automatic broad audit follows this work.
