# Studio field validation work audit - pass 154

Read all of `FieldEditorInteraction`: measurement units, prefab declarations, validation
issues, league bounds and obstacle bounding boxes. Focused this pass on repeated geometry
work and preservation of existing validation outcomes.

Overlap checks previously recomputed a right-hand obstacle's bounds for every preceding
obstacle. Polygon bounds scanned vertices four times for each such comparison. Rectangle
bounds repeated sine/cosine calls within each computation. A counted-access regression
measured 9,960 vertex reads for 40 identical three-vertex polygons before the correction.

Validation now caches each obstacle's bounds once per call and reuses them for rectangle
containment and pair checks. Polygon extrema use one vertex traversal; rectangles reuse
sine and cosine. The regression requires at most 800 reads for that 40-polygon fixture
while retaining all 780 pair warnings. Pair comparisons are still quadratic; no wall-clock
speedup or exact shape-intersection performance claim is made.

A second test checks all leagues: invalid radius is an error, rotated rectangle extents
and outside game pieces are warnings, the inclusive 0.25 m AprilTag perimeter margin is
preserved, and duplicate numeric tag IDs remain an error. Bounds overlap is an advisory
axis-aligned approximation, not an exact collision test for arbitrary shapes.

The production file remains partial for invalid field dimensions/nonfinite geometry,
polygon topology, measurement-input domains, prefab provenance and complete caller/UI
validation flow. A passing bounds regression does not certify simulator acceptance or
physical collision behavior. The unmodified prefab lists still allocate their combined
league list on lookup; that separate lifecycle/ownership tradeoff was not changed here.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass154-verified-evidence/`.
Candidate remains `17.0.3-rc.100852e472fb`. All changes are local; no rendered UI, external
service, live simulator or physical robot operation was performed.

Validation: full app suite reports 1,785 tests: 1,779 passed and six opt-in skips,
with no failures or errors. The counted-access bound passes after failing at 9,960
reads before the change. Unchanged shared/gateway suites were not rerun. Policy,
documentation links and staged whitespace checks pass.
