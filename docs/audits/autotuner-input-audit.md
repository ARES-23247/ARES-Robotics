# AutoTuner input and proposal audit - pass 18

This pass reviews shared sample preparation, data-quality arithmetic, the two SysId import
paths and AutoTuner's declaration mapping/revalidation boundary. Step-response model math,
simulation fidelity and proposal transport remain open and keep AutoTuner partially reviewed.

## Findings and changes

- Quality checks accepted negative/out-of-domain timestamps that the fitter discarded.
  A shared prepared snapshot now owns finite, chronologically ordered rows and records
  invalid numeric/time counts. Any invalid timestamp blocks recommendation quality.
  Quality checks and fitting consume the same rows; chronological input skips sorting.
  This removes repeated filtering/sorting from a single AutoTuner analysis.
- The median of an even number of periods selected the upper observation. It now averages
  the two middle periods. Primitive period storage and one extrema/timing pass replace
  boxed interval lists, distinct timestamp sets and repeated column scans. Duplicate times
  remain a blocker. Overflowing voltage/velocity spans are rejected with finite diagnostic
  values. The ten-percent non-finite threshold message no longer truncates to nine percent.
- AutoTuner maintained a second import parser that invented CSV timestamps, ignored explicit
  seconds and replaced missing acceleration with zero. Both callers now use the reviewed
  parser in the service package. The former AutoTuner parser's timestampMs, accel and
  speed aliases are preserved, along with explicit-zero acceleration and named time units. Failed/unsupported
  file analysis clears the previously published recommendation before returning or throwing.
- The measured feedback model maps voltage to velocity. Its gains were incorrectly assigned
  to drivetrain path position controllers, whose error is position and output is velocity.
  LINEAR and ANGULAR proposals now contain their three feedforward coefficients only;
  warnings explain why velocity feedback gains cannot populate those controller declarations.
  The flywheel voltage/radians-per-second contract retains its six matching declarations.
- ELEVATOR and ARM need gravity-aware models and dedicated declarations. CUSTOM has no
  declaration binding. These previously inherited unrelated drivetrain or flywheel keys.
  Their diagnostic analyses remain visible, but proposals are empty and rejected with an
  explanation. This does not add a gravity model or claim those mechanisms are tuned.
- Approval checked supplied envelope limits while forwarding an independently mutable value
  map. It now rechecks canonical mechanism limits and requires the exact declaration values
  derived from the supplied coefficients, finite bounded confidence/R-squared and a usable
  step model. This boundary still submits to the review inbox; it does not write robot values.
- Duplicate direction warnings were removed. Golden and Monte Carlo fixtures now close
  their owned clients/databases; temporary golden imports are removed in finally blocks.

The drivetrain unit mismatch was traced through `MecanumTrajectoryFollower` and
`HolonomicDriveController`: position-error PID output is a commanded chassis velocity.
Flywheel units were checked against `FlywheelSysIdAdapter` and FRC's controller conversion
from radians/second coefficients to rotations/second vendor coefficients. These call-path
checks are not full-file or hardware validation of those robot components.

## Validation

Eleven of twelve initial regressions failed against the old implementation; baseline XML
is `ARESLib-Kotlin/build/audit-pass18-baseline.xml`. The focused integration suite then
passed **66 methods**, including seventeen new audit methods and all 21 prior parser
regressions. A coverage review added direct non-finite velocity/acceleration and maximum
timestamp rejection, plus reverse-only flywheel rejection, bringing this pass to **19 new
methods**. The additional process-observer regression brings the pass total to **20 new
methods**.

The older golden test now expects three LINEAR feedforward keys instead of six mixed-unit
keys; its exact known-plant gain/R-squared checks remain. All 36 Monte Carlo cases still
execute with unchanged recovery/stability thresholds. The eighteen unsupported cases must
reject proposals; the original 28/36 acceptance fraction applies to the eighteen supported
cases. No test now treats a generic gravity-free model as a valid arm/elevator proposal.

The first complete gate passed with 1,349 tests and six skips. The expanded gate then
exposed an existing Windows timeout-test assumption: it read a PID file that PowerShell
might not write before the 1.5-second deadline. Failure XML is
`ARESLib-Kotlin/build/audit-pass18-process-failure.xml`. The bounded-process adapter now
allows observation of the owned OS process immediately after launch; deadline and
cancellation tests assert termination using that handle. The deadline was not increased.
An additional regression checks cleanup if the observer throws. This is a scoped test
reliability/ownership correction, not a complete process-tree or output-capture audit.


The final complete gate passed with **1,352 tests passing and six opt-in skips** (app:
1,317 passing; shared: 17; gateway: 18), plus **56 dashboard smoke tests** and **one
performance-baseline test**. App tests executed; shared/gateway reused unchanged results.
The configured coverage gate, production Kotlin size ratchet, release alignment, monorepo
policy, shared guidance and links in 179 current documents passed.

| Source | Executed lines | Executed branches |
| --- | ---: | ---: |
| PreparedSysIdData | 21/21 (100%) | 28/28 (100%) |
| AutoTuningSafetyPolicy | 104/105 (99.0%) | 113/140 (80.7%) |
| Shared SysIdLogParser | 97/97 (100%) | 159/210 (75.7%) |
| AutoTunerService | 220/242 (90.9%) | 126/194 (64.9%) |
| BoundedProcess | 29/29 (100%) | 12/20 (60%) |

Execution coverage does not close the remaining model/proposal/process review scopes.
Final validation used the unchanged local library candidate `17.0.3-rc.b81c0156add9`:

```powershell
.\gradlew.bat :shared:test :gateway:test :app:test :app:koverXmlReport :app:koverVerify verifyReleaseVersionAlignment verifyProductionKotlinFileSizes --no-parallel '-ParesVersion=17.0.3-rc.b81c0156add9' '-ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/ARESLib-Kotlin/build/release-repository' --console=plain
```

Run from `ARES-Analytics`. Final logs are
`ARESLib-Kotlin/build/audit-pass18-studio-verified.log`, `audit-pass18-process-focused.log`
and `audit-pass18-policy.log`. Kover XML is `ARES-Analytics/app/build/reports/kover/report.xml`.
The failed expanded gate is retained as `audit-pass18-studio-final.log`; it is not cited as
a passing result. Library identity and starter hashes remain unchanged, so robot suites
were not rerun for these Studio changes.

## Remaining work

AutoTuner's step identification still needs its own analytic audit: plateau/chronology
assumptions, finite model arithmetic, time-constant/dead-time estimation and the quadratic
settling search. The inbox uses SharedFlow with no replay and still needs delivery/lifecycle
review; an emitted proposal does not prove board receipt. Simulation model fidelity,
import/simulation publication provenance and stronger live-run identity remain open.

The current dataset model uses integer milliseconds and uniform-sampling assumptions must
be established by callers. No physical loop time, jitter, stop acknowledgement, robot
configuration application or visible Studio window was measured. The repository-wide
coverage goal remains active; all changes remain local.
