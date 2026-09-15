# XRP deployment and recovery audit (pass 6)

This pass inspects the complete deployment tool and boot selector, their existing tests,
and the in-memory device model. It adds 18 tests in `test_deployment_audit.py`; changes
remain local and all device/network interactions in this validation are mocked.

## Findings and corrections

| Area | Correction |
| --- | --- |
| Missing preflight evidence | Check an explicit required API set. An absent API, `"false"` string, or numeric truthy value no longer counts as successful Boolean evidence. Validate receipt/object shape before consuming API/capability data. |
| Native API probing | Check callability, not only attribute presence. Inspect each available motor's effort/position/speed methods and each servo's angle method. Probe the complete declared IMU interface and user button, and require generic I/O for ordinary analog inputs. |
| Payload contamination | Exclude Python bytecode and `__pycache__` directories from runtime and extension staging. Local CPython caches cannot change deployed content or consume controller storage. Preserve source and extension resources. |
| Payload resealing | Exclude the root `ares-files.json` from its own hash manifest. Repeated sealing is stable. Reuse the streaming SHA helper instead of reading each complete payload file into memory. |
| Concurrent downloads | Each attempt owns a unique temporary file. Cleanup no longer overwrites/deletes another attempt's conventional `.partial` file. Replace the destination only after matching size and digest. |
| Oversized responses | Stop reading after the expected byte length plus one, and reject immediately. A mismatched response cannot grow the partial file without bound. Hash accepted chunks while streaming instead of rereading the downloaded file. |
| Boot fallback | Invalid text decoding is handled alongside missing files and invalid syntax, before executing any program. Activation uses the same fallback rule to preserve a usable previous slot. |
| Duplicate boot work | Read and compile the selected entry point once, then execute that code object. Put the selected slot first on the import path even if it was already present later. Do not fall back after the selected program starts and raises. |
| Plan identity | Include the selected board identity in read-only staging. Successful preflight requires that same board, so plan and actual staging now produce the same payload digest for unchanged inputs. |

The first regression run reproduced ten failing assertions/subcases and one decoding
error. Additional tests reproduced missing sensor-capability checks, activation recovery,
import precedence, and plan/deployment digest disagreement. All final regressions pass.
One interruption fixture initially assumed LF bytes; its assertion now accepts the
equivalent platform text newline. Production payload hashing still covers exact bytes.

## Verification

- XRP source plus generated safety suite: **107 passed** (89 existing plus 18 added).
- Extracted standalone XRP archive with its bundled runtime: **107 passed**.
- Interruption is injected after every modeled deployment filesystem mutation, covering
  slot reservation/upload, validation, launcher installation, and marker replacement.
  Each outcome executes the launcher actually left on the modeled device and verifies
  a bootable selected slot plus preservation of the original slot's program.
- Existing activation interruption, duplicate-content deployment, partial-copy, stale
  runtime, board mismatch, checksum, and payload-tampering tests continue to pass.
- Additional checks reject extra files and hash-matching but syntactically invalid
  Python; preserve the prior destination on failed/oversized downloads; and verify
  independent temporary-file ownership, idempotent seals, and plan/deploy identity.
- File-based trace reports **77.6%** executable-line coverage for `xrp_device.py` and
  **92.3%** for `ares_boot.py`. These are not branch coverage. Generated device scripts
  execute in the fake device model but are not falsely mapped onto host generator lines.
- Studio app/archive-consumer suite: **1,209 passed, six opt-in tests skipped**.
  Release preflight passed. Repository policy, source/archive integrity, guidance, and
  links in 167 current documents passed.

The final unpublished XRP 3.0.3 archive SHA-256 is
`ee746bed6041acbb77ceebda15716f9bc608160b98e5da960d0e2dac1c21a2aa`.
The other three deterministic archives reproduced unchanged. ARESLib source is unchanged;
Studio validation uses existing isolated candidate `17.0.3-rc.343862ce3c59`.

Logs are `ARES-XRP-Starter/build/audit-pass6-*.log` and
`ARESLib-Kotlin/build/audit-pass6-*.log`. Annotated source is under
`ARES-XRP-Starter/build/audit-pass6-trace/`.

## Limits and continuing work

The fake filesystem models completed operations and atomic rename. It does not prove
LittleFS/flash power-loss durability, USB behavior, firmware installation, or real
MicroPython compilation. Physical interruption/rollback and controller preflight still
require the existing [physical-readiness checks](../../ARES-XRP-Starter/docs/PHYSICAL_READINESS.md).
No connected device or real download endpoint was used for these tests.

Deployment validates all staged files before activation. Boot selection validates the
entry point; it is not a new promise to hash every dependency on every boot. The
standalone slot's directory priority assumes a fresh controller interpreter after reset;
it does not evict arbitrary modules already loaded by an unrelated script.

Remaining executable-line gaps include command-line dispatch, native `mpremote` failure
handling, image preparation orchestration, and some invalid metadata branches. The next
pass should cover the shared MicroPython transport, leases, shutdown failure aggregation,
and loop allocations, then continue through the monorepo inventory. Full coverage remains
unproven and the original goal stays active.

## Library coverage inventory for subsequent passes

All library `koverXmlReport` tasks completed against the existing candidate with test
tasks up to date. Core's module-local report contains 12,082 covered and 2,991 missed
lines across 180 source files with line counters; 32 have no recorded local execution.
Large missed-line areas include NT4 transport, subsystem/superstructure construction,
trajectory planning, routine compilation, and localization calibration. Zero-execution
entries include `CompositeVisionIO`, `SwerveOffsetManager`, and `FieldWaypointLoader`.

These are module-local reports, not a union of downstream consumer execution. In
particular, the mocks module's zero local coverage does not mean simulator tests never
use it. Use these reports to choose review/test work, not to close file-review entries.
The machine-readable snapshot at `ARESLib-Kotlin/build/audit-library-coverage.json`
maps source counters to tracked file fingerprints and records unresolved mappings.
Coverage generation evidence is `ARESLib-Kotlin/build/audit-coverage-library.log`.
