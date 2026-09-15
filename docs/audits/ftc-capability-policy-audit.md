# FTC capability and runtime policy audit - pass 169

Reviewed AutoCapabilities.kt, AresRuntimePolicy.kt and the complete
FtcAutoCapabilitiesTest.kt. Traced registration in AresRobot, the neutral recovery
callback, NamedCommands factories and Task lifecycle defaults. No production defect
was confirmed in these two small adapters; production source remains unchanged.

The capability advertises DRIVE ownership and produces a fresh task per creation.
Registration and factory construction do not invoke hardware recovery. Initialization
calls the callback once; repeated completion/execute checks do not retry it. A false
result marks failure, an exception propagates to the owning executor, and runtime
cleanup clears completion state while retaining terminal status for diagnostics.
The actual callback checks neutral Redux intent and calibration exclusion before
delegating to the IO neutral-recovery operation. These tests certify adapter behavior,
not motor writes or executor exception handling.

Added tests for lazy invocation, separate task instances, descriptor/resource agreement,
one-shot callback execution, cleanup, exception identity and generated runtime policy
agreement. The prior success/failure test left initialized task metadata behind; it now
resets both tasks in finally blocks, as do the new lifecycle tests.

Runtime policy constructs one immutable options object from generated constants. Enum
conversion occurs once, and an invalid transport would fail construction instead of
silently selecting a different hardware path. Current generated values agree with the
checked-in project: ARES_PHOTON transport and enabled Limelight proxy. Physical transport
activation and proxy behavior are outside this adapter test.

Also read the complete season autonomous and TeleOp DSL adapters. These remain partial:
static inspection confirms reusable input frames/adapters, a single shared nanosecond
timestamp, opt-in generated drive, and stop-time reference cleanup. Hardware construction
failure, repeated SDK lifecycle calls, generated-runtime initialization failures and
season adapter integration need dedicated fault-injection evidence before closure.
The shared base maintains persistent GamepadState objects, matching the cached adapter
references. No lifecycle guarantee is inferred merely from compiling the wrappers.

Validation: eight focused tests and all 120 TeamCode plus six simulator tests pass,
with no skips. No APK rebuild was needed for test-only changes. The unchanged library
candidate is 17.0.3-rc.100852e472fb. No hardware, release, push or other-product test run
occurred. Local XML and logs are retained in
`ARESLib-Kotlin/build/audit-pass169-verified-evidence/`.
