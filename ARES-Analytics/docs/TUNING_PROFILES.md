# Robot-owned tuning profiles

ARES tuning is built around component declarations and robot-owned profiles—not an unstructured list of NT4 topics.

## Files

- `.ares/tuning-components/*.arestuningcomponent` declares each project-wide value's component, description, type, unit, range, and apply policy. Drivebase and subsystem documents may also own declarations.
- `.ares/tuning/<stable-profile-id>.arestuning` stores canonical values and provenance.
- `.ares/history/tuning/<stable-profile-uid>/<content-hash>.arestuning` stores the prior canonical content before promotion, alongside reviewer metadata.

Profiles can inherit another profile. The UI shows whether a value comes directly from the selected profile or from its parent, plus its evidence/provenance.

## Learn this workflow in Robot Academy

Open **Help & Learn → Control Theory & Guided Tuning → Run One Safe, Evidence-Guided Tuning Experiment**. The lesson uses the
current project's real declarations and `.arestuning` profile, starts from a paired-run finding, and asks for one feedforward-aware
prediction, one typed proposal, provenance, an apply-policy explanation, and a structured review.
Its observed checkpoints prove only what the editor can see: loaded declarations, a valid proposal,
recorded provenance, and review readiness. Interpretation, simulator evidence, acknowledged live
testing, and physical validation remain separate.

For the complete evidence → snapshot → Local Sim → paired comparison → decision workflow, see
[Guided tuning experiments](GUIDED_TUNING_EXPERIMENTS.md).

## Three columns

- **Source** is the resolved canonical robot profile.
- **Live** is an observation received from the robot. Connecting never changes Source.
- **Proposed** is an unsaved experiment. Editing, AutoTuner, calibration analysis, or explicitly copying a Live value can create a proposal, but cannot promote it.

## Apply policies

- `LIVE_SAFE`: may be explicitly live-tested after validation; this still does not change Source. The UI waits for the robot to acknowledge the exact request nonce and reports the robot's result—it never labels a successful network write as a successful test.
- `DISABLED_ONLY`: can change only through a runtime protocol that proves the robot is disabled.
- `RESTART_REQUIRED`: takes effect after restarting the runtime.
- `REBUILD_REQUIRED`: requires regenerated/rebuilt robot configuration.
- `CALIBRATION_ONLY`: may be changed only by its declared calibration workflow.
- `READ_ONLY_VENDOR`: cannot be edited or pushed; re-import the vendor source.

The UI labels these policies in text and disables unavailable actions. It never communicates policy or direction by color alone.

The Proposed editor follows each declaration's type: decimal input for `DOUBLE`, whole-number input for `INT`, a switch for `BOOLEAN`, text input for `TEXT`, and a constrained choice menu for `ENUM`. ARES does not coerce one type into another.
Invalid text immediately clears any older valid proposal for that field and is marked beside the editor. A visibly invalid value can therefore never promote or live-test a stale hidden proposal.

## Promotion

Promotion requires:

1. Declared keys only.
2. Correct value type, finite numeric values where applicable, and declared ranges.
3. A compatible apply policy and owner.
4. Provenance explaining where every value came from.
5. A content-hash-bound structured diff.
6. Explicit confirmation.

ARES first writes an immutable `LOCAL_EXPERIMENTAL` proposal snapshot under `.ares/history/tuning/<profile>/proposals/`. The canonical profile's promotion metadata records that snapshot UID/hash, reviewer, summary, and any supplied evidence paths/hashes. It then backs up the prior content and atomically replaces exactly one canonical `.arestuning` profile. Calibration and promoted live experiments additionally require a project-local evidence file whose SHA-256 still matches. Promotion does not publish NT4 and never edits Kotlin, Java, or vendor source.

## AutoTuner

AutoTuner sends measured recommendations to the proposal inbox. It uses the same validation, diff, discard, and explicit promotion path as manual edits. Rejected, out-of-range, undeclared, vendor-owned, or policy-incompatible values cannot be promoted or live-tested. AI-assisted tuning is intentionally deferred until it can use this same typed, evidence-bound review contract.
