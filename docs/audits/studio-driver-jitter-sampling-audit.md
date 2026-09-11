# Studio driver-jitter sampling audit - pass 140

Moved from XRP into previously unreviewed Studio numerical analysis. Read the driver
analysis service and its tests, plus the telemetry metric catalog and its alias tests.
The service retains partial status for remaining persistence and coaching edge cases.

## Confirmed sampling defect

Jitter analysis searches 8–12 Hz, but accepted regularly sampled captures whose
Nyquist frequency did not cover that whole band. A 20 Hz capture could therefore
produce the message that inputs were smooth and stable, despite being unable to
establish absence of jitter throughout the configured band.

After validating sample intervals, the service now requires sample rate strictly
greater than twice the upper band limit (24 Hz). An unusable axis returns no spectrum
before allocating FFT input or performing the transform. If neither axis is usable,
the result reports insufficient telemetry; a sufficiently sampled other axis can
still provide evidence. Existing cadence-gap checks remain in place.

The regression inserts 128 real database samples at 20 Hz and requires insufficient
data rather than a smooth-input conclusion. It then adds a 100 Hz second axis with
a 10 Hz component and verifies detection. The new regression failed before the fix.
Existing clean-signal and stronger-low-frequency-plus-jitter tests remain intact.

## Additional review

Several driver-analysis fixtures retained open DuckDB connections. Added teardown
tracking to close them after each test, including assertion failure, and retry deletion
of their owned temporary files after closing. The new sampling fixture also closes
its database before removing its owned temporary directory. Corrected a test comment
that equated simultaneous translation/rotation with wheel scrub without wheel evidence.

Reviewed all seven metric definitions, units, rate metadata, alias normalization and
lookup construction. Aliases retain provenance rather than becoming one database
storage key. The existing tests distinguish drive voltage from battery voltage and
check slash normalization, aliases and unknown keys. No catalog change was needed.

Remaining service scopes include persistence failure consistency and concurrency,
coaching timestamp/duplicate/extreme-number behavior, and broader spectral confidence
under imperfect sampling. This Nyquist gate does not establish hardware anti-alias
filtering or prove that a human driver's motion is safe or efficient. No UI launch
or physical robot validation is claimed.

## Validation

The new regression failed before the fix; all seven focused driver-analysis tests
passed afterward. Final Studio validation completed with 1,791 passing tests and
six skips (1,762 app entries including six skips, 17 shared and 18 gateway tests).
The app suite executed; unchanged shared/gateway results were up-to-date. Skips cover
three opt-in template/integration cases, native file chooser, performance baseline
and physical dashboard telemetry. Policy, documentation links and staged whitespace
checks passed. No skipped capability is claimed as validated here.

Before-failure XML and final evidence are retained under
`ARESLib-Kotlin/build/audit-pass140-verified-evidence/`. The unchanged library candidate
is `17.0.3-rc.100852e472fb`; no library source, deployment, push, merge or release changed.
