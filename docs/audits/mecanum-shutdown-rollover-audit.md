# Mecanum shutdown and encoder rollover audit — pass 83

This pass continues the lifecycle and counter boundaries identified in pass 82. It traces direct
drive-facade close, registry close, subsequent output/recovery calls, neutral-write failure and
signed 32-bit encoder rollover. Changes and validation remain local and use SDK doubles.

## Findings and changes

Closing `MecanumHardwareIO` delegated to a cluster that closed only its cached IO wrappers. It
neither attempted physical neutral nor prevented subsequent raw/feedforward commands or recovery
from energizing the drive. Registry close also reached that path without independently calling safe.

The cluster now marks shutdown terminal before attempting neutral on all four motors. It clears
cached requests and scales, closes cached IO even if a neutral write fails, and reports unsuccessful
neutral through the direct close call. Later output/recovery cannot reopen the cluster. A failed
close can be retried to neutralize a previously unavailable motor; a confirmed close is idempotent.
Registry close retains its existing best-effort exception policy, so it attempts other resources
even when one rejects shutdown. No software change guarantees a physical stop when hardware rejects
the neutral command; the regression verifies attempts, other-motor stopping and retry behavior.

Encoder finite differences previously widened signed raw counters before subtraction. Crossing the
32-bit boundary therefore produced a false displacement near 2^32 ticks and a huge false velocity.
The wrapper now subtracts in the counter domain and accumulates the signed modular delta into an
unwrapped position. The independent derivative baseline still survives duplicate timestamps.
Forward, reverse and same-timestamp rollover are covered by explicit numeric expectations.

## Counter/reset contract and limits

Unwrapping assumes fewer than 2^31 ticks between successful reads; position alone cannot determine
the number of complete revolutions of the counter across an arbitrarily long outage. Double-valued
tick accumulation represents consecutive integers exactly only within its ordinary 2^53 range.
The existing read-only `resetEncoder()` no-op is preserved and now described directly. Its test
verifies that reference/derivative continuity remain unchanged without another device read.
An external SDK counter reset is not distinguishable from motion using this observation alone;
reconstruct the wrapper/drive lifecycle when establishing a new hardware counter reference.

The motor cluster still retains partial review for construction/configuration and independently
mutated power-scale boundaries. The feedforward configuration/history follow-ups from pass 82 and
FTC flywheel cached-read/diagnostic follow-ups from pass 81 remain pending. No hardware loop deadline,
physical actuator stop guarantee or complete monorepo review is claimed by these tests.

## Validation

Eight regression methods failed before the corresponding fixes: five shutdown methods and three
rollover methods. Preserved logs/XML are under `ARESLib-Kotlin/build/audit-pass83-*-before.*`.
Initial focused tests and API verification passed; full module and frozen-candidate validation,
source identity and ledger reconciliation are pending.
