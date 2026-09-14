# Bounded robot-readiness checkpoint

Status: **paused at the user's request**. Resume only on explicit instruction in this existing task.
See [the saved pause status](ROBOT_READINESS_STATUS.md) for completed evidence and remaining work.
This objective supersedes the open-ended
"audit every tracked file" plan. Preserve its useful fixes, reports, ledger, and local evidence;
unfinished file counts are historical supporting information, not a work queue or exit criterion.
No new task, sub-agent, automatic broad audit pass, push, merge, or external release is authorized.

## Saved objective

Close and review the combined current audit batch, preserve a reproducible local checkpoint, and
establish readiness for selected realistic robot scenarios. Fix known high-impact behavior and
measured performance problems in that scope. Exercise representative generated FTC/FRC mechanism
configurations through production runtime/controller code and simulated IO, retain existing XRP
starter host validation, and verify kinematics/estimation against independent realistic trajectories.
Identify platform loop budgets and measure available timing distributions, worst observed delays,
deadline misses, allocations, and sensor-to-output latency. Keep desktop/simulator evidence separate
from target-controller evidence. Preserve these regressions in the existing affected-product CI
scopes. Document unresolved hardware checks and deferred lower-priority findings, then stop.

The app currently retains the old paused goal. Its available goal API cannot edit an existing
objective; do not mark the unfinished old objective complete to replace it. This tracked document
is the replacement execution plan and saved objective for this task. Report the app limitation
plainly until the app's objective can be changed through a supported control.

## Scope and acceptance

Start from local checkpoint `779fb0a274b68fcac08132c6b69bc3375ce59c1e` on
`codex/robot-loop-math-audit`. Review the combined pass-277 changes against `614dc76c` before expanding
the scenario harness. Reuse valid unchanged evidence; rerun checks when changes invalidate it.

Use the existing representative mechanism templates and robot/simulator fixtures. Required scenario
coverage is startup, explicit enable/disable, simultaneous mechanism commands, autonomous/routine
cancellation, stale or missing feedback, and partial preparation/cleanup failures. Assert the actual
output behavior and command/state transitions, not just source strings or successful compilation.
Avoid expanding the checkpoint into every possible robot, feature, input extreme, or source file.

Math checks use independently derived wheel/body relationships and known straight/curved trajectories,
including realistic angle wrapping and delayed observations. A forward/inverse round trip alone is
insufficient. Performance reports must name the measured boundary, workload, sample count, warmup,
clock, platform, explicit budget, and limitations. Instrumentation must not become a new robot loop.

Classify findings before acting:

- **High impact:** realistic incorrect behavior, unsafe outputs, ignored freshness/enable conditions,
  lost commands, failed cancellation, or incomplete shutdown. Fix and validate within scope.
- **Performance:** demonstrated timing, allocation, or resource problems. Optimize measured bottlenecks
  and retain comparable before/after evidence.
- **Lower priority:** unrealistic extremes, speculative changes, and cosmetic cleanup. Record and
  defer. Keep already completed safe fixes; do not manufacture churn by reverting them.

Where evidence permits, label a finding pre-existing or audit-introduced. Never label an untested
hypothesis a confirmed defect. Preserve unrelated checkout changes, apps, build workers, branch
protections, safety gates, candidate immutability, and release authorization boundaries.

## Completion conditions

Stop this checkpoint when all of the following are satisfied:

1. The selected realistic scenarios pass using meaningful execution evidence.
2. No known high-impact defect remains within that selected scope.
3. Available performance evidence is assessed against explicit platform budgets.
4. Unverified hardware behavior, other validation limits, and deferred findings are documented.
5. Meaningful regressions are included in the appropriate existing CI scopes, including consumer
   integration after shared runtime, schema, or generator changes.

Finish with a concise readiness report covering verified behavior, important fixes, measured
performance, remaining risks, and any specific next action supported by evidence. File counts and
test counts support that report; neither defines readiness. Do not automatically start another pass.
