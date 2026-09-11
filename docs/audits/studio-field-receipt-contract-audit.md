# Studio field receipt contract audit - pass 150

Reviewed all declarations and functions in `SimulatorFieldApplyReceipt.kt`: topic
constants, expected identity, failure identity, receipt fields, event ID composition,
content matching, nullable parser and raw payload hash delegation. No production defect
was established in this helper file.

Added three contract tests. Parsing rejects absent/blank/invalid/incomplete JSON and
preserves a valid receipt, including compatibility with unknown fields. Content matching
requires exact field ID and revision plus a case-insensitive hexadecimal hash comparison.
Changing the event sequence or simulator session changes event identity without changing
content matching. The editor owns freshness checks separately; `matches` is not intended
to prove freshness by itself.

The payload hash is over exact UTF-8 bytes, not newline-normalized text. A pinned digest
for a Unicode payload was independently calculated with Python hashlib. Tests distinguish
LF from CRLF and reject equality after appending whitespace. No hashing code changed.

Traced editor confirmation handling: incoming receipt events must differ from the prior
event and match expected content; a matching retained receipt has a separate 'already
has exact field revision' status. Existing tests exercise mismatched revisions, fresh
rejection reporting, retained exact receipts and application counts using injected
callbacks. These were rerun with the new helper tests.

The parser validates serialization structure, not every numeric/count/hash-format domain.
A receipt is a simulator assertion, not cryptographic authentication or independent proof
of physical installation. Live NT4 delivery, reconnect timing and the interval between
publish and receipt subscription were not validated. The larger editor remains partial.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass150-verified-evidence/`.
Candidate remains `17.0.3-rc.100852e472fb`. Only tests/audit records changed, so validation
is focused on receipt and editor contracts; the previously passing full app suite was
not repeated. No external, UI, simulator or physical hardware operation occurred.

Validation: 25 focused tests passed with no failures, errors or skips.
Monorepo policy, documentation links and staged whitespace checks pass.
