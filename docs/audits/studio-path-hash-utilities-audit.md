# Studio project path and hash utilities audit - pass 164

Read ProjectLayout, Sha256 and the existing hash test in full. Project source detection
accepted any .kt/.java/.py file regardless of league, allowing a misplaced Python helper
to satisfy FTC/FRC source presence and a Kotlin file to satisfy XRP presence. Detection
now requires Python for XRP and Java/Kotlin for FTC/FRC. This remains a lightweight source
presence check; it does not parse source or certify a buildable robot repository.

New project tests cover those mismatches, valid Kotlin/Python source, FTC TeamCode Java
source, blank/missing folders, Android/module FTC asset roots, FRC/XRP roots, canonical
field paths, normalized relative image paths and rejection of absolute/escaping image
paths. ProjectLayout remains partial: recursive source scans, symlink cycles, repeated
XRP scans and concurrent filesystem changes require further review. Canonical path checks
are not claimed to eliminate filesystem time-of-check/time-of-use changes.

Sha256 previously formatted each digest byte separately and converted requested prefix
bytes through a list and copied byte array. Hex conversion now writes nibbles directly
into one character array; prefix conversion limits the encoded digest length directly.
Output stays lowercase, UTF-8 text behavior is unchanged, CRLF normalization remains an
explicit separate method, positive prefix counts above 32 retain full-digest truncation,
and nonpositive counts are still rejected. Each call owns its MessageDigest; file streams
close through use and composite/streaming callers retain their existing API semantics.

Expanded hash tests include standard empty/abc digests, a UTF-8 vector, 65,553 binary bytes
crossing the 64-KiB read boundary, file/byte/composite agreement, digest reset after finish,
1/12/31/32/33/Int.MAX_VALUE prefix lengths, negative/zero rejection, raw CRLF distinction
and preservation of lone CR. UTF-8 and binary golden values were independently calculated
with Python hashlib. Test fixtures now delete files promptly and verify handles are closed.
No hash-output incompatibility was found; the encoding change removes redundant work,
without a measured throughput claim. No before-fix test execution is claimed in this pass.
The first new project fixture used an unavailable JUnit 5 annotation and failed test
compilation; it was corrected to the app's JUnit 4 temporary-folder rule before execution.

The hash utility and both test files are fully reviewed within those contracts. Provider
internals, hardware storage failures and native filesystem race behavior are not certified.
Evidence is retained under `ARESLib-Kotlin/build/audit-pass164-verified-evidence/`.
Candidate remains `17.0.3-rc.100852e472fb`; no external service, simulator or physical robot
operation occurred.

Validation: all eight focused tests pass. The full app suite reports 1,821 tests: 1,815 passed and 6 opt-in skips, with no failures or errors. Unchanged shared/gateway suites were not rerun. Monorepo policy, documentation links and staged whitespace checks pass.
