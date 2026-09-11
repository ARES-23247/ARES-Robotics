# XRP JUnit reporting audit - pass 137

Reviewed the host tool's result recording and JUnit serialization scope, previously
left open in the file ledger. Robot runtime behavior and library sources did not change.

## Confirmed evidence defects

Expected failures and unexpected successes were absent from XML. In particular,
unittest could correctly fail a run for an unexpected success while its XML omitted
that failure. Repeated subtests with the same ID remained separate test-case entries
but collapsed in outcome dictionaries, making suite counters disagree with entries.
Subtest parameters containing dots also corrupted class-name splitting, and names
lost their parameter details. Failure text containing XML-forbidden characters could
produce a report that XML consumers could not parse.

## Correction

Serialize each outcome directly instead of indexing it by test ID. Unexpected
successes become failures; expected failures become skipped entries with diagnostic
detail. Counts come from those same serialized outcomes. Subtest identity uses the
parent test's class and retains parameter text in the case name. Repeated identities
retain separate entries; consumer-specific UI grouping is not controlled here.

XML-forbidden characters in names, messages and details are replaced by the Unicode
replacement character. Normal tabs, newlines, Unicode and XML escaping remain intact.
This is host report-generation work, with no robot-loop allocation impact.

JUnit counts describe emitted outcomes. A method with multiple failed subtests can
produce multiple entries, so these counts are not always unittest's method count.
Successful subtests remain represented by their successful parent method.

## Validation and remaining scope

Four tests run real inner unittest suites and parse their written reports. They
cover expected/unexpected outcomes, repeated subtest identities, invalid XML text,
and ordinary success/failure/error/skip. Before: two failures and one error. After:
all four pass. Full XRP verification: 119 tests, zero failures/errors/skips. Parsed
the real output and verified all counters against its entries. Policy, documentation
links and staged whitespace checks passed.

Logs, final XML and source hashes are retained under
`ARESLib-Kotlin/build/audit-pass137-verified-evidence/`. The host generator remains
partial for complete schema/feature validation, generated-test strength and remaining
CLI/process edge cases. No physical test, deployment, push, merge or release occurred.
