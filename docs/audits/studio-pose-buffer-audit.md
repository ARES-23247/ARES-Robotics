# Studio pose buffer audit - pass 152

Reviewed all of `FieldPoseBufferManager`. Sampling previously substituted default truth
coordinates when estimator fields were absent, without checking connection state. This
could create an invented origin or mix partial estimator data with defaults. Sampling
now requires a connected state and a complete finite pose from one source: explicit
simulator truth when available, otherwise all three estimator components.

The background sampler and public clear operation previously mutated the same deque
without synchronization. A clear between reading the last point and replacing it could
empty the deque before `removeLast`; clearing could also race with snapshot publication.
Both operations now synchronize on the same manager, covering deque changes and trace
publication together. Private mutation helpers are called only within the sampler lock.

Movement filtering now uses Euclidean displacement. The old per-axis test suppressed a
0.009 m displacement on each axis even though total movement exceeds 0.01 m. Heading-only
updates continue to replace the latest point without growing history. Capacity remains
150 samples, and unchanged poses do not copy/publish a new list.

Added an injectable sampling dispatcher with the existing Default dispatcher as its
production default. Three controlled-clock tests cover absent/disconnected/incomplete/
nonfinite states, valid estimator coordinates, diagonal movement, heading replacement,
duplicate suppression, oldest-sample eviction and clearing/reset while retaining other
viewer state. Test scopes automatically cancel the background sampler.

No before-fix test execution is claimed; the old admission logic, per-axis predicate and
unsynchronized interleaving establish the defects by source inspection. The tests validate
the corrected behavior. They do not measure wall-clock scheduler latency or exercise every
possible concurrent thread schedule. Pose freshness beyond the connection/source flags
depends on upstream telemetry because this state model carries no sample timestamp.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass152-verified-evidence/`.
The candidate remains `17.0.3-rc.100852e472fb`. All changes stay local; no rendered UI,
live simulator or physical robot operation was performed for this audit.

Validation: full app suite reports 1,780 tests: 1,774 passed and six opt-in skips,
with no failures or errors. Unchanged shared/gateway suites were not rerun. Policy,
documentation links and staged whitespace checks pass.
