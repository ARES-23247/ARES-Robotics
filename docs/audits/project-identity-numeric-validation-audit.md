# Project identity numeric validation audit — pass 203

Scope: the complete ProjectIdentityValidation helper: numeric input, metadata-to-
draft round trips, stable-key/name rules, platform defaults/runtime options and
review-diff completeness. This is distinct from the inspection/repair and
concurrency scopes of passes 201–202. Changes remain local.

## Confirmed defects and changes

Editable geometry and XRP brownout thresholds were formatted to six decimal
places when loaded. A measurement such as `0.45123456789` became `0.451235` before
the user changed anything. Positive dimensions below the formatter's resolution
could become zero and fail validation. Unchanged precise metadata could produce
and save a different identity, and a display-name-only edit could also propose
four unintended geometry changes.

The helper now uses the Double representation that round-trips to the same
numeric value, removing only a terminal `.0` for ordinary integral inputs.
Geometry and safety thresholds retain their exact values. Very large or small
editable values may use scientific notation. This is preservation of accepted
metadata values, not a claim that such extremes describe physically usable robots.

The form also trimmed valid stored display names and XRP SSIDs. The canonical
metadata contract permits nonblank strings with surrounding spaces. XRP generation
copies the SSID into `wifi_ssid`, and the starter passes that string directly to
the Wi-Fi interface. Trimming it could therefore change the requested network.
Display names and SSIDs are now preserved literally, with blank and full-string
length checks. Stable identity keys retain their existing whitespace normalization
and ASCII/length rules; whitespace is not a valid part of those keys.

Three immutable regular expressions now serve repeated stable-key checks and ID
sanitization. Validation no longer compiles four Studio regexes on every edit.
The unreachable fallback in suggestedProjectId was removed: its fixed `team`
prefix survives filtering and truncation, so the result already starts with an
ASCII letter. Formatter machinery is no longer needed to populate numeric inputs.
These are structural reductions in work; no allocation or latency benchmark was
run, and the library validator's own implementation did not change.

## Validation evidence

Seven baseline cases ran against unchanged production source, and all seven failed:
geometry round trips, adjacent/fractional brownout values, literal SSIDs, literal
display names, workspace measurement defaults, unchanged-editor persistence and
name-only review diffs. Matrix tests stop at the first failure in each baseline
case; the report does not count every row as independently reproduced before repair.

The final 14-case test file covers:

- Exact metadata round trips for FTC, FRC and XRP across subnormal/minimum-normal
  values, ordinary adjacent/fractional values, large exponents and maximum finite
  values; hashes agree after the round trip.
- Brownout endpoints and adjacent values; nonfinite, nonpositive, underflowed and
  overflowing input; a footprint exceeding the field by one Double step.
- Exact stable-key/name length boundaries, key normalization, blank/non-ASCII or
  malformed keys, and deterministic bounded ID suggestions for unusual labels.
- XRP port/deadman/brownout limits, reserved NT4 port rejection, literal SSID rules,
  Wi-Fi-mode validation and ignoring hidden XRP inputs for FTC metadata.
- Platform coordinate/default-field/runtime selection and authoring-model retention.
- One distinct review entry for each editable identity/geometry/FTC runtime field,
  all XRP runtime options, new-document entries, no-op diffs and platform changes.
- Actual editor/repository flows proving unchanged precise metadata produces no
  write/history, while a name-only save preserves exact geometry/runtime settings
  and the prior canonical history document.

The boundary/default tests establish the existing repository metadata contract.
They are not a new competition sizing certification, Wi-Fi interoperability test,
or exhaustive test of every finite Double. Temporary project files and coroutine
test scopes are owned by the tests; no real robot or network interface is opened.

All 77 focused tests passed with zero skips, failures or errors, including the
14 new cases and existing identity/concurrency/repair/session regressions.
The full Studio app suite passed: 1,990 tests total, 1,984 successful executions,
zero failures/errors and six opt-in/environment skips (three generated-project
integrations, native file chooser, performance baseline and physical dashboard).
Shared agent guidance and monorepo policy checks passed. Local links were checked
in 364 current documents; 38 explicitly historical records were skipped.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass203-verified-evidence/`,
including baseline and copied final result XML. Gradle used the unchanged isolated
`17.0.10-rc.271e2518a412` candidate. No library source, release pins, starter archives
or runtime source changed. No visible-window, physical hardware, release,
deployment or remote publication was exercised.

## File accounting and remaining boundaries

ProjectIdentityValidation.kt and the new test file were read completely and are
accounted for under the validated-current-metadata contract. The library metadata
validator and XRP SSID generation/use were inspected to establish that contract;
their wider file coverage is not inferred from this pass.

The larger identity screen remains partial for unit/sizing presentation and actual
rendered interaction. Session fixture waits/ownership, raw metadata text ambiguity,
external writers and transaction durability remain in the existing queue. Broader
runtime geometry consumers and physical measurements retain their own validation
requirements. The repository-wide audit goal remains incomplete.

The ledger accounts for 2,899 tracked files: 1,077 reviewed, 157 partial and 1,665
pending, with no stale fingerprints or orphaned records. These are file-review
counts, not line or branch coverage.
