# Guided tuning experiments

ARES Robotics Studio turns a paired-run observation into a controlled, reversible learning cycle. The workflow is intentionally narrower than an automatic tuner: the student chooses one declared parameter, predicts one metric, tests in Local Sim, and records what the evidence supports.

## The student workflow

1. Open **Analysis → Guided Run Review** and compare two compatible runs from the active team, season, and robot workspace.
2. Inspect a finding's exact replay timestamp, source topics, and uncertainty statement.
3. Choose **Create one-change experiment**.
4. Write the question, a falsifiable prediction, the conditions you will hold constant, a numeric success threshold, and the Local Sim safety boundary. Request mentor review when appropriate; this is a learning prompt, not a cloud permission or invented approval role.
5. Select one typed numeric parameter and one outcome metric. ARES proposes one conservative change inside the parameter's declared bounds.
6. Select **Snapshot configuration & stage one proposal**. ARES records the resolved profile values, profile content hash, and hashes of the canonical `.ares` robot documents.
7. Launch **Local Sim**. A `LIVE_SAFE` candidate can be sent only while the loopback simulator is the selected, connected target. ARES waits for the runtime request nonce and explicit `APPLIED` acknowledgement.
8. Open the simulator Dashboard, start the same TeleOp or autonomous routine, select **Record run**, repeat the baseline maneuver, then select **Stop & save**. Guided tuning intentionally accepts only a new run tagged by Studio as Local Sim evidence.
9. Compare the baseline and candidate automatically, preserving source timestamps and units. ARES classifies the selected metric as **Improved**, **Regressed**, or **Inconclusive** against the threshold declared before the run.
10. Record **Accept**, **Revise**, **Reject candidate**, or **Roll back**, plus the reason and next safe test. Accept is available only when finite recorded evidence meets the intended threshold. Reject removes the staged candidate while retaining the evidence; rollback is also available before comparison.
11. Export the engineering report for a mentor/student review.

An accepted experiment is evidence, not a silent configuration write. Canonical `.arestuning` promotion still uses the existing structured diff, reviewer, provenance, history snapshot, and atomic replacement workflow.

## Ownership and storage

Guided experiment records are local, non-canonical working evidence:

```text
.ares/local/tuning/experiments/<experiment-id>.arestuningexperiment.json
```

Each record includes the workspace identity, baseline finding, question, hypothesis, held constants, success threshold, safety notes, mentor-review request, immutable configuration digest, exactly one proposed value, metric intent, candidate run, evaluation, limitations, decision, and next test. `.ares/local` and `.ares/history` are excluded from the configuration digest so saving an experiment cannot recursively change its own snapshot.

Canonical configuration remains in `.ares/tuning`, drivetrain, subsystem, superstructure, controls, routine, field, and project documents. Generated source is not an authoring surface and is not edited by this workflow.

## Safety and evidence boundaries

- The guided apply action never targets a live robot. It requires Local Sim selection, loopback NT4, an online simulator, a declared `LIVE_SAFE` policy, and an explicit runtime acknowledgement.
- Restart-, rebuild-, disabled-, or calibration-only values are never presented as hot-injectable. Follow their declared application workflow.
- Rolling back stops the managed simulator before removing the proposal so a restarted process resolves the unchanged canonical profile.
- A one-factor experiment reduces confounding but does not prove causation.
- Thresholds are declared before the candidate run. A change in the intended direction that misses the threshold remains inconclusive rather than being presented as success.
- Simulator evidence does not certify physical hardware safety or competition performance.
- Candidate runs must belong to the active workspace, differ from the baseline, be recorded after the experiment snapshot, and carry Studio's explicit `simulation` evidence tag. Imported and live-robot runs are not silently substituted.
- Studio permits only one active telemetry recording and finalizes it on graceful application shutdown so half-finished sessions are not presented as completed evidence.
- Experiment files and exported reports remain local until a user explicitly backs up or shares them. Reports may contain team/robot IDs, topic names, timestamps, and student-authored notes.

## Mentor review questions

- Did the student state what changed and keep every other intended condition constant?
- Does the selected metric measure the predicted outcome in compatible units?
- Can the student open the exact replay evidence rather than relying on a summary sentence?
- Did the result improve the intended metric, and were there regressions elsewhere?
- What physical effects are missing from the simulator?
- Should the team accept, revise, or roll back—and what evidence is still required before physical testing?

The exported Markdown report is designed to capture those answers for a student notebook or mentor review.
