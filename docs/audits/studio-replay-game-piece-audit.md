# Studio replay game-piece audit - pass 151

Read all of `ReplayFieldSnapshot` and its four existing tests, plus the typed game-piece
accumulator and its live-consumer integration test. Two new replay tests failed before
the correction: a typed frame produced no pieces, and legacy count did not remove stale
array entries. Missing legacy coordinates were also replaced with invented zeros.

Replay now recognizes the version-2 typed frame and delegates record construction to
`GamePieceFrameAccumulator`, sharing stable ID, type, orientation, dimensions, shape
and color mapping with live viewing. A snapshot entry point validates the version,
integral bounded count (at most 10,000), required element presence and finite values
before feeding the accumulator in index order. It uses the existing protocol constants.
An empty or incomplete typed frame remains authoritative over stale legacy entries.

Legacy replay retains support for recordings without a count topic, but respects an
available nonnegative integral count. Only complete finite x/y pairs are reconstructed;
negative or malformed array indices no longer create synthetic pieces at the origin.
Unused legacy visual attributes are not reinterpreted without a verified wire contract.

Tests cover typed identity/type/position/rotation/dimensions/shape/color, typed empty and
incomplete frames with stale legacy data, count reduction and zero, absent legacy count,
and missing coordinates/negative indices. Existing pose-source separation, no-target
vision and source-timestamp trace tests remain. The database test now awaits close before
removing its owned temporary directory.

Both production files remain partial. Replay pose finiteness, mixed scalar timestamps,
trace downsampling/join behavior and further vision cases remain open. The live accumulator's
stream staging/reconnect/completeness rules are not certified by the new complete-snapshot
entry point. Snapshot element presence alone does not prove a common producer timestamp.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass151-verified-evidence/`.
The candidate remains `17.0.3-rc.100852e472fb`. All changes are local; no live simulator,
rendered UI, external service or physical hardware operation occurred.

Validation: full app suite reports 1,777 tests: 1,771 passed and six opt-in skips,
with no failures or errors. Unchanged shared/gateway suites were not rerun. Policy,
documentation links and staged whitespace checks pass.
