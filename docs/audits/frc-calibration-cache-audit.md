# FRC calibration cache audit

Pass 101 reviews the season robot's absolute-encoder calibration cache and its tests. Only the
calibration action listener and periodic recording call sites in `ARESRobot.kt` were traced; the
remaining robot lifecycle and loop-efficiency review is still open.

## Confirmed defects

The cache checked vendor latency and local residence time separately against a 100 ms limit.
A sample already 90.25 ms old was accepted after another 10 ms in the cache. The CTRE reader's
latency getter reports the age of the oldest cached absolute encoder, so these ages must combine.
The cache now retains acquisition latency and compares it with the remaining age allowance.
Fractional milliseconds are preserved; 90 ms plus 10 ms is accepted, but 90.25 ms plus 10 ms rejects.

A backwards clock transition could wrap signed subtraction into a small positive age. The cache
now explicitly checks timestamp ordering, as well as negative overflowed elapsed time, before
allowing a copy. These are host boundary cases, not an observed physical clock failure.

Both defects failed in the initial regression run (10 methods, two failures). The corrected focused
run passed 12 methods; one further acquisition/ownership method is included in the full FRC run.

## Efficiency and ownership

The second plausibility scan during every copy was redundant: only the cache owns its stored
array, and publication follows complete validation. Removing that scan avoids rechecking four
encoder values on each copy. The scratch initialization remains defensive for incomplete IO reads.
No periodic hardware refresh was added. The cache still uses two preallocated primitive arrays,
and the new state is one primitive latency value. Tests assert that repeated copies neither read
the IO adapter again nor call refresh. This establishes call behavior, not a measured timing gain.

Failed or invalid acquisitions revoke the previous sample. Successful copies preserve trailing
caller storage, rejected copies leave caller storage unchanged, and returned buffers do not alias
the retained snapshot. The cache assumes the existing single owning robot loop; it is not a
concurrent snapshot mechanism. Calibration uses the snapshot before persisting new offsets.

## Scope and validation

Five new methods cover combined age, fractional and exact boundaries, replacement latency, clock
rewind/overflow, ownership, invalid latency, failed/incomplete acquisition, short output buffers and
IO call counts. Existing calibration, mechanism-safety, PDH and topology assertions remain intact.
The full test file was read and executed; this does not certify every implementation it calls.

This change is confined to the FRC season product. Validation reuses the exact pass-100 library
candidate `17.0.3-rc.de2cb9c407a0`; no library source or release identity changed. Unaffected
FTC, starter and Studio suites do not need another run for this product-local change. There is no
claim of physical calibration, roboRIO timing, live simulator behavior or hardware-in-the-loop results.

## Final evidence

Full FRC validation passed 153 tests, including all 13 methods in `ARESRobotSafetyBoundaryTest`, with zero failures, errors or skips. Generated-project and namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Copied FRC JUnit XML and successful logs, with verified SHA-256 hashes, are recorded in `ARESLib-Kotlin/build/audit-pass101-verified-evidence/summary.json`. Initial failure XML is preserved in `ARESLib-Kotlin/build/audit-pass101-before-evidence/`.
