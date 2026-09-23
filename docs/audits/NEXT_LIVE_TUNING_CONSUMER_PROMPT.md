# Next bounded checkpoint: live tuning reaches the generated FTC consumer

The [saved-configuration peer review](GENERATED_SAFETY_BEHAVIOR_PEER_REVIEW.md) covers canonical
startup gains and feedback gates. Its tests do not establish the live tuning transaction from
Studio through the robot's authorization, acknowledgement, and consumer callback.

```text
Verify one complete live heading-tuning transaction through the actual generated FTC consumer.
Keep this bounded; do not start another repository-wide audit.

Read AGENTS.md, the relevant repository skills/product guidance, and
docs/audits/GENERATED_SAFETY_BEHAVIOR_PEER_REVIEW.md. Inspect branch, dirty state, and processes.
Create an isolated codex/ feature branch/worktree from the latest local reviewed checkpoint
containing that report, recording its exact commit. Do not start from an older GitHub release
and discard the unpublished peer-review corrections. Preserve all other work and processes.
Use one agent. Keep everything local: no merges, pushes, releases, deployment, or new tasks.

First inventory the existing TuningLiveRequestAuditTest, Nt4TuningRequestWireAuditTest,
TuningManagerTest, TypedTuningRuntimeTest, MecanumTuningTransactionAuditTest, and generated
consumer round-trip fixtures. Reuse their valid evidence and mechanisms; identify the specific
integration boundary that remains unproved before adding tests. Do not duplicate these suites.

Use the generic FTC starter's actual .ares declarations and generated runtime with simulated IO.
Exercise Studio's real request construction, the production transport/request parser and
TuningManager, the robot's actual arm/lease context, its typed consumer, and acknowledgement.
Use existing in-process transport/test seams where available; avoid port/sleep based flakiness.
Do not grant authorization by constructing a permissive TuningApplyContext, inject tuning
directly into Redux, bypass the consumer callback, or use simulator truth as estimator state.

Cover these cases for one supported LIVE_SAFE heading parameter:
1. An unarmed request is rejected, without changing the runtime gain or persisted canonical
   profile. Check the acknowledgement, not just a UI success message.
2. An authorized request using the actual supported arm/nonce/lease flow applies, acknowledges
   the same request/value, and reaches the controller. For the same realistic measured heading
   error, demonstrate the expected output below saturation with an independent P-only oracle.
   Keep output inhibited while the actual tuning protocol requires it, and prove behavior only
   after the normal authorized transition back to control. Never weaken safety to obtain motion.
3. A replay/stale or mismatched request, and one invalid value, are rejected according to the
   documented protocol. The last confirmed value remains effective; canonical .ares files
   remain byte-identical. If the protocol intentionally handles replay idempotently, test and
   document that contract instead of inventing a rejection rule.
4. Stop/close leaves outputs neutral and cannot accept further actuation through a late request.

Trace and document actual apply policy, active canonical profile, live overlay ownership,
request identity, acknowledgement, freshness, and cleanup. Do not conflate an arm state, a
disabled-equivalent neutral hold, a control lease, and sensor freshness. Keep the saved-profile
round trips from the previous checkpoint intact. Broader policies, arbitrary callback failures,
and other mechanisms are deferred unless needed to fix a demonstrated defect in this path.

Fix demonstrated high-impact defects at the smallest responsible boundary. Distinguish
pre-existing defects, newly introduced regressions, and missing test evidence. Record a failing
regression before the fix where practical. Defer cosmetic changes and unmeasured optimizations.

Use release/ares-versions.properties and the published library when its source is unchanged.
Any ARESLib change requires a new unused version, exact source-tree identity, isolated candidate
repository, and dependency-ordered validation per ares-build-release. Never reuse a published
version for different bytes. Keep new regressions in the existing changed-part CI scopes and
serialize builds writing the same outputs. Do not redesign CI or start a release.

Run affected existing tests and the generated-consumer integration check once the change is
stable. Retain nested JUnit reports and request/acknowledgement evidence per operation, before
temporary projects are deleted. Report actual dependency versions, commands, failures, skips,
and expected/observed results. These are desktop tests, not hardware or native UI validation.

Update docs/audits/file-reviews.json for only the reviewed delta, preserve prior evidence, and
refresh accurate content identities. Write a concise report with remaining limits. Stop when
these four selected cases pass and no known high-impact defect remains in that path, or record
a concrete external blocker. Return local commits and the report; do not automatically expand.
```
