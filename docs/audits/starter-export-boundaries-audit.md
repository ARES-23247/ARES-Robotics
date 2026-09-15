# Starter export source and verification boundaries

Pass 187, 2026-09-11. The previous pass completed migration-inventory validation
and its local commit. This pass reviews starter export and archive enumeration.

## Confirmed fixes

The XRP runtime overlay recursively copied filesystem contents instead of using
Git's tracked-file list. Synthetic untracked and ignored files were included in
both the mirror and its integrity manifest. The overlay now uses the same tracked
source hashing as the other templates, retaining the Python-cache exclusions.

The mirror `-Check` operation compared dictionary-enumerator objects instead of
their entries. It accepted an exported mirror after a tracked file changed.
Explicit keyed comparisons now reject changed, missing and unexpected files and
identify their paths. This also avoids a general object-comparison operation for
data already indexed by relative path.

Git's default quoted filename output broke tracked Unicode paths. NUL-delimited
enumeration now preserves the tested Unicode and space-containing paths. The
output-root boundary now distinguishes a sibling whose name starts with the
workspace name from an actual path inside the workspace. Mirror checking and ZIP
enumeration explicitly include hidden files.

## Evidence and scope

Four of six original export tests failed before the fix: local XRP file leakage,
changed-file acceptance, Unicode filenames and the sibling-prefix rejection.
Seven final cases pass, covering those behaviors plus existing-output protection,
missing/extra files, hidden-file checking, standalone dependency manifests and
two independently generated sets of four archives. ZIP assertions check identical
bytes, LF text normalization, preserved binary bytes, fixed timestamps, executable
wrapper permissions and exclusion of synthetic local runtime data.

The archive fixture initially wrote doubled carriage returns through Windows text
translation. Its writer was corrected to preserve literal line endings; that was
a fixture issue, not an additional archive-normalization defect.

All 80 root tooling tests passed with no skips. All four real source mirrors were
exported and verified in a uniquely owned temporary directory, then removed.
All four newly generated real archives match both the existing SHA-256 pins and
Studio's bundled archive bytes exactly. Consequently the existing versions and
runtime candidate `17.0.8-rc.c5f40142a878` remain valid; no runtime source changed,
and no new consumer build, push or release was performed. Logs and hashes are in
`ARESLib-Kotlin/build/audit-pass187-verified-evidence/`.

The exporter and archive builder remain partially reviewed. Reparse-point path
containment, failure recovery during export/archive creation and cross-locale
archive ordering still need separate evidence. The repeated-archive test proves
reproducibility on the tested runtime, not every operating system or locale.
