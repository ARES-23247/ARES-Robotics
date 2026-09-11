# XRP autonomous input audit - pass 136

Continued the previously open host-generator validation scope. No shared library
source or robot-loop implementation changed.

## Confirmed defects

The host validator converted WAIT durations with `float()` and only checked for
negative values. It accepted NaN, positive infinity, booleans and numeric strings;
null and overflowing integers produced incidental exceptions. Starting and DRIVE_TO
target poses had no finite-number validation. This allowed invalid authored data
past host validation, leaving failures to generated imports or runtime use. The
shared MicroPython waypoint/runtime validation remains a separate safety boundary.

Python's default JSON decoder also accepted nonstandard NaN/Infinity tokens and
overflowed JSON exponent values such as `1e999`. Finally, duplicate enabled catalog
entry IDs silently replaced earlier routines in the generated dictionary.

## Fixes

- Reject nonstandard and overflowed floating-point JSON numbers while loading any
  canonical document, with a path-specific validation error.
- Require actual finite numbers for wait durations and all three starting/target
  pose components. Booleans and numeric strings are rejected. Waits must be
  nonnegative; omitted starting poses still default to the origin.
- Reject duplicate enabled autonomous entry IDs before replacing a selected routine.

These checks run on the host before generation. They do not add periodic work or
weaken device-side validation. Routine feature preservation, complete schema/type
validation, generated safety-test strength and reporting/CLI edge cases remain open;
the generator retains partial ledger status.

## Evidence

Seven new tests cover invalid wait values, finite pose components, missing heading,
default starting pose, duplicate selection, valid zero/fractional waits, supported
drive/action payload preservation and malformed JSON numbers. Before the fix the
seven methods reported 40 assertion failures and two errors across their cases;
these are not 42 separate test methods. All seven now pass.

Full XRP host verification: 115 tests, zero failures/errors/skips. Generated-source
freshness, repository policy, documentation links and staged whitespace checks pass.
Logs, final JUnit XML and source hashes are retained under
`ARESLib-Kotlin/build/audit-pass136-verified-evidence/`.

No deployment, physical robot validation, push, merge or release was performed.
