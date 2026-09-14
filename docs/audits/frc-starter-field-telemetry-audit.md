# FRC starter field application and telemetry lifecycle audit

Pass 263, 2026-09-14. This follows the [simulation mathematics pass](frc-starter-simulation-math-audit.md).
The scope is canonical field installation and acknowledgement, native telemetry entry ownership,
bridge teardown, and the native test harness. Changes remain local to the audit branch.

## Confirmed defects and fixes

The field revision gate committed an incoming revision before the application callback succeeded.
A callback exception escaped the polling loop; retrying the same document then skipped application
but published a success receipt. A failed high revision could also reject a corrected intermediate
revision. The new [field update helper](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/FrcStudioFieldUpdates.kt)
commits payload identity only after successful application. The robot composition callback validates
and installs simulator geometry before publishing the global canonical field. Ordinary decode or
application exceptions produce diagnostics without stopping lease-expiry processing; direct
interruption remains observable. Callbacks must preserve their previous state on failure.

Exact retries of the successfully installed payload obtain another receipt without decoding,
hashing, or resetting the simulator. New payloads are UTF-8 encoded once, and the SHA-256 digest uses
Java 17 hexadecimal encoding instead of 32 formatter invocations. Reusing a revision for different
bytes still fails closed. This work occurs on field updates, not every simulation frame.

Manual receipt escaping handled quotes and backslashes but left JSON control characters raw.
Gson now encodes the existing eight-field receipt contract, and its runtime dependency is explicit.
The hash continues to describe the exact received UTF-8 bytes.

Telemetry cleanup previously invoked `NetworkTableEntry.close()`, which intentionally does nothing.
The adapter also used entries cached by the shared instance, so it did not own independent native
publishers. This is an ARES API-usage defect: WPILib documents the compatibility entry's no-op
close and the actual close lifetime of generic entries. The exact cached 2026.2.1 source was inspected;
the live release documentation may describe a newer patch.
See [NetworkTableEntry](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/networktables/NetworkTableEntry.html)
and [GenericEntry](https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/networktables/GenericEntry.html).

[StarterFrcTelemetry](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/StarterFrcTelemetry.kt)
now caches adapter-owned generic entries by canonical topic. Closing releases those handles,
keeps the shared instance usable, and prevents later operations from recreating entries. Separate
adapters retain separate ownership even when publishing the same topic. Cleanup attempts every
resource, preserves the first failure, avoids repeated/self-suppression, and preserves direct
interruption. The bridge uses the same [cleanup helper](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/StarterResourceCleanup.kt);
after close it disconnects sampling without invoking fallback hardware.

The starter test task lacked GradleRIO's native test configuration. The first native test worker
terminated before producing assertion results. Adding `wpi.java.configureTestTasks(test)` supplies
the existing desktop native extraction and loader paths. Its behavior was checked against the
cached GradleRIO 2026.2.1 implementation; no custom task or skipped prerequisite was used.

## Validation

The authoritative baseline reran corrected fixtures against the original pass-262 source with
the repaired native harness: **9 tests, 8 failures, zero errors or skips**. Earlier runs are retained
as diagnostic evidence, not counted as regression proof. Two fixture assumptions were corrected:
a published NT topic can exist before receiving a value, and duplicate test publications must
explicitly enable duplicate delivery.

The final `verifyAresProject test` run passed **74 tests with zero failures, errors, or skips**.
All 59 prior invocations remain. Fifteen additional methods cover failed application/retry/revision
ordering, receipt control characters, field publication rollback, loader exceptions and interruption,
lease expiry despite field failure, post-close sampling, native typed round trips and array ownership,
canonical entry caching, independent publishers, and fault-tolerant cleanup.

Tests use isolated native NetworkTables instances and HAL simulation. They restore shared field,
Driver Station, and interruption state and close owned resources. They do not start an external
NetworkTables server or launch a full robot, Studio window, or physical hardware session. Telemetry
tests disable the process-owned DataLog startup; that startup branch was reviewed but not executed.
Adapter and field gate use remain confined to their owning update/teardown thread.

The ARESLib source tree remains `f9e7569ea873a08df0477fad1008b6f9ef4575e3`.
All 410 files of local candidate `17.0.44-rc.f9e7569ea873` were hash-checked unchanged.
No shared library change required another consumer matrix. Shared guidance and documentation links
were verified for this checkpoint. Detailed XML, corrected baseline, logs, hashes, and inventory
are retained locally under `ARESLib-Kotlin/build/audit-pass263-verified-evidence/`.

## Remaining coverage

The drive bridge's complete lease/mode/time semantics, robot runtime fault and teardown ownership,
and the rest of the build/deployment contract remain partial. This pass does not close those files
merely because the product suite passed. Review should next test expiry when no poll occurs between
commands, receiver-time arithmetic, and runtime cleanup failures.

Studio validation is still blocked by release-version alignment. Automatic approval review previously
rejected the bundled archive/reference migration as outside the audit's no-release authorization;
that approval remains pending and the old proposal must be refreshed for later source changes.
No archive, release reference, remote branch, or protected check was changed or bypassed here.
