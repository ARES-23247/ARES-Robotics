# Studio field migration and geometry audit - pass 147

## Legacy catalog collision

Reviewed the editor/canonical mapping in `FieldDocumentMapper`. Legacy name-only game
pieces generate a catalog ID by lowercasing their name and replacing non-ASCII/non-word
characters with separators. Different names such as `Custom A` and `Custom-A`, or two
different non-ASCII names, can therefore generate the same ID. Inserting the second
type previously replaced the first map entry, changing what earlier pieces referenced.
A generated ID could also replace an explicitly authored catalog type and its physics.

Two regression tests failed before the correction. Generated types now select an unused
ID with a deterministic numeric suffix when needed. Existing IDs and entries remain
untouched. Tests cover colliding names, non-ASCII names, authored catalog identity/mass,
and stable piece/type identities through the next editor save.

The mapper remains partially reviewed: duplicate names in authored catalogs, remaining
legacy type/shape migration, revision boundaries and full persistence interoperability
still need validation. The shared geometry DTO review also remains partial. No upstream
season dimensions or live provider defaults were independently verified in this pass.

## Reflection mathematics

Read all of `FieldEditorTransforms` and its tests. FTC reflects about the center-origin
axes; FRC/XRP reflect using field dimensions. Negating rectangle orientation gives the
correct reflected occupied rectangle for either axis (rectangle geometry is invariant
under a half-turn). Polygon vertices are reflected individually; metadata is retained.

Added a corner-based geometric check for both reflections in all three leagues, using
an asymmetric rotated rectangle. Added explicit XRP circle and triangle cases. Existing
tests cover FTC rectangle values, FRC circles and polygon metadata. No transform-code
defect was found. These are mathematical checks; rendered editor interaction and physics
polygon acceptance are separate consumer behaviors, not certified here.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass147-verified-evidence/`.
Library candidate remains `17.0.3-rc.100852e472fb`. Changes stay local; no UI launch,
external publication, simulator operation or physical hardware validation occurred.

Validation: full app suite reports 1,766 tests: 1,760 passed, six opt-in skips, no
failures or errors. Unchanged shared/gateway suites were not rerun. Monorepo policy,
documentation links and staged whitespace checks pass. An intermediate test assertion
was corrected to compare stable catalog identity rather than nullable diameter defaults;
its failure evidence is retained separately from the original two collision failures.
