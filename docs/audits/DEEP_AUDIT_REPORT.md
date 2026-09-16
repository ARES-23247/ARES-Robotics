# Deep audit submission: integration review

The campaign based on protected main `9487e288b0029833dac0a75dc63d95d8247811cb` submitted
changes through `f73d4ceed` on `antigravity/deep-audit`. Its original objective is retained in
[DEEP_AUDIT_GOAL.md](DEEP_AUDIT_GOAL.md). The broad campaign is stopped; this release review
covers the submitted changes and their affected behavior.

## Coverage claim requires evidence

The submitted report claimed that 2,966 files had complete review and validation. That claim is
not independently established by the available records. The campaign changed 269 ledger entries
to `reviewed`, with 268 marked `passed`; some evidence entries name only a production source file
or the goal document. Refreshing a content hash and passing a suite do not establish full review
of every file or validation of each claim. The final report itself was added after the cited
completion commit. Its category/product totals and malformed text are not retained as verified facts.
The cited `Nt4PerformanceTest.kt` does not exist in the submitted tree. A Gradle invocation with
several test filters can pass because other filters match; the XML results must identify the tests
that actually ran. The release review uses the existing disposal, loopback, and dashboard checks.

The submitted scopes, fingerprints, evidence references, and original statuses remain in
[file-reviews.json](file-reviews.json), with those changed submissions pending verification and
original statuses retained in `submission` metadata. Bounded release reviews can record partial
review and their actual validation separately; they do not complete the original all-file claim.
Unchanged historical records are preserved,
not newly certified. Pruning entries for deleted files is retained. The
[checkpoint](checkpoints/deep-audit-checkpoint.json) preserves the submitted completion claims
separately from the reviewed disposition. Run `python scripts/audit_inventory.py` for current
accounting; neither its complete count nor a hash match validates the quality of a review claim.

## Review of the runtime changes

- **AutoImportService:** the proposed eviction was ineffective. Observations use
  `local:<normalized absolute path>`, while the submitted cleanup removed plain absolute paths.
  A regression using the real CSV import and database path fails on the submitted implementation:
  a recreated source with identical metadata inherits the deleted file's stability observation.
  The corrected cleanup removes the actual key for the source and its Driver Station companion.
  This is an incomplete fix for pre-existing retention, not evidence that all source-retention
  paths are now bounded. External deletion, quarantine, and remote-source retention were not fixed
  by the submitted patch and are not claimed as resolved.
- **Nt4ClientService:** retain volatile visibility for the diagnostic divergence-log timestamp.
  This is limited defensive hardening; volatile does not make the read/check/write rate limiter
  atomic, and the submission did not provide a reproducer for an operational threading failure.
- **Nt4ConnectionLifecycle:** retain explicit close before replacing an inactive HTTP client.
  This makes cleanup intent explicit. The submission does not demonstrate an ordinary reconnect
  engine leak or measure retained threads, so that stronger defect claim is not accepted.

No robot library source, coordinate transform, output lease, or enable policy changes in this
release. The affected Studio checks and observed release evidence belong in the
[release review](RELEASE_7_0_61_REVIEW.md).

## Deferred observations

The submitted `Pose2d.distanceTo` allocation concern and proposed matrix mutators remain
unmeasured suggestions. Source-level object construction alone does not establish allocations
in a warmed JVM, and a new mutable API requires caller/ownership evidence. No library API or
version change is justified by those suggestions in this release.

Physical robot hardware remains unavailable. This review does not certify target loop timing,
physical safety, Limelight calibration, or all-file audit completion. Further audit work requires
a separately selected scope; publishing this bounded Studio release does not restart it.
