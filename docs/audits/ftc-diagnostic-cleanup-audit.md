# FTC drivetrain diagnostic cleanup audit - pass 173

Reviewed the complete diagnostic and its generated motor mapping test. The old helper
combined lookup and configuration inside runCatching, returning null on either failure.
That discarded a discovered motor if its initial neutral write or later configuration
failed, so the outer finally block could not retry neutral on that device. Two regression
tests reproduced the missing cleanup attempts before the fix.

Lookup now stores every discovered motor in the cleanup array before configuration.
Configuration success is tracked separately; every motor must configure successfully
before the active loop is allowed. Driver Station text distinguishes missing devices
from configuration failures. Cleanup attempts all discovered motors even if an earlier
cleanup write throws. Active-loop write failures still propagate after final cleanup.
This proves attempted writes against mocks, not successful physical neutralization.

The diagnostic also skipped its first running-status telemetry at clock zero. An executed
regression reproduced that omission. Explicit timestamp initialization now permits the
first update and recovery from rewind/elapsed overflow while retaining the 100-ms rate
limit. The mocked test exercises first publication at zero; rewind/overflow branches
were inspected statically in this file, not separately fault-injected here.

Six focused tests cover generated names/directions/control labels, failed initial neutral,
later configuration failure plus a cleanup error, missing motor, active write failure,
one held button commanding only its motor, final zero commands and initial telemetry.
Definitions and power arrays allocate once at entry; the motor loop uses indexed arrays,
cached SDK button fields and no sensor reads. It sleeps 20 ms after work, so 50 Hz is an
upper bound rather than a measured or exact loop frequency. Telemetry strings are built
only at the low-rate boundary. Multiple simultaneously held buttons can command multiple
motors; this remains the documented per-button diagnostic behavior.

No robot hardware was constructed or operated in these tests. SDK lifecycle timing,
physical direction, actual motor-stop response and transport-fault recovery remain
external validation. Library source and candidate `17.0.3-rc.100852e472fb` are unchanged.
Evidence: `ARESLib-Kotlin/build/audit-pass173-verified-evidence/`. No release or push occurred.

Validation: six focused tests and all 135 TeamCode plus six simulator tests pass without
skips. Debug APK assembly, monorepo policy, documentation links and staged whitespace
checks pass. Unchanged library and other product suites were not rerun.
