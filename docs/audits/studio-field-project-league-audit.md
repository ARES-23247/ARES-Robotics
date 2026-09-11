# Studio field project-league audit - pass 155

Read `FieldDocumentStore`, `InitialFieldPresetInstaller` and `FieldImageLoader` in full.
Focused validation on project-league boundaries after finding that the document store
decoded fields without checking their field type against the requested project league.
Preset installation checked the preset against the starter, but both could agree on the
wrong league for the target project path.

Added guards to store load/save and initial preset installation. Saving validates both
the requested document and any existing field before creating history checkpoints or
replacing the canonical file. This prevents silently loading or overwriting a field using
a different league's coordinate convention. Existing mismatched files are rejected, not
automatically converted or repaired.

Three tests cover all six cross-league combinations, unchanged canonical/history bytes
after rejected saves, rejection of an already mismatched field, correct absent-document
defaults without disk writes, and a wrong-league starter/preset pair. The cross-league
and preset tests both failed before the guards were added. Existing successful template
installation and field persistence tests remain in the full app validation.

Both changed production files remain partial for full checkpoint failure/concurrency
semantics, revision boundaries, resource failures and the remaining mapper contracts.
The image loader was read for context but is not newly certified: decoded image size,
native resource ownership and malformed-image handling need dedicated validation. No
rendered image, UI interaction or live simulator application is claimed.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass155-verified-evidence/`.
Candidate remains `17.0.3-rc.100852e472fb`. All changes are local; no external service,
live simulator or physical robot operation was performed.

Validation: full app suite reports 1,788 tests: 1,782 passed and six opt-in skips,
with no failures or errors. Unchanged shared/gateway suites were not rerun. Policy,
documentation links and staged whitespace checks pass.
