# FRC hardware refresh allocation audit - pass 120

## Scope

Reviewed reset sampling and all status-signal refresh groups in the six Talon mechanism
adapters: climber, cowl, feeder, floor, flywheel and intake. Traced each group's cached
validity calculation and configuration-reset latch. Reviewed the reset helper's loop and
exception propagation. These are partial hardware reviews: output writes, homing, tuning,
close and physical-device behavior are not closed by this pass.

## Finding and change

Every adapter constructed a new motor array for reset checks each loop. Twelve status-signal
groups also constructed vararg arrays, which Phoenix wrapped in lists before refreshing.
The installed Phoenix 26.1.1 source supplies an equivalent `refreshAll(List<BaseStatusSignal>)`
overload. Each adapter now retains its motor array and immutable signal lists at construction,
then calls the list overload. All group membership and ordering remain unchanged.

The reset sampler accepts the retained array and inlines the device read. It still samples
every device after finding a reset; short-circuiting would leave later reset indicators unread.
Exceptions propagate to the existing caller fault handling. The helper's generic sampler
allows deterministic testing without fabricating a physical Talon device or bypassing its
final reset API.

Independent refresh groups remain independent. For example, position validity still depends
on the position refresh and homing state, not on the current refresh result. Velocity, current
and temperature groups on the flywheel retain their separate behavior. Reset latching,
homing invalidation, numeric validity tests, signal rates and actuator requests are unchanged.

## Evidence

Before/after `javap` inspection verified the allocation instructions in each compiled `refresh`:

| Adapter | Before argument-array allocations | After | Retained signal groups |
|---|---:|---:|---:|
| Climber | 3 | 0 | 2 |
| Cowl | 3 | 0 | 2 |
| Feeder | 2 | 0 | 1 |
| Floor | 3 | 0 | 2 |
| Flywheel | 4 | 0 | 3 |
| Intake | 3 | 0 | 2 |

All twelve compiled refresh calls select the list overload. Source assertions compare exact
old/new signal group order and motor-array membership. Thus the six adapters remove eighteen
argument-array constructions per complete refresh round, plus the twelve vararg-to-list
wrappers in Phoenix's entry point. This is bytecode/source evidence, not a measured hardware
loop-time speedup; JIT escape analysis may affect runtime allocation.

Three new tests cover every reset-bit combination for one, two and four devices, repeated
consumption, empty groups, read exceptions and allocation measurement. A retained four-device
group sampled 100,000 times after warmup allocated zero measured bytes in the isolated test.
The fake devices verify sampling semantics; they do not emulate CAN communication.

Full FRC validation passed 274 tests with zero failures, errors or skips. Five core allocation
regression methods passed. Generated-project/namespace verification, monorepo policy and current
documentation links passed. Gradle reused valid unchanged outputs. XML, logs, source hashes and
before/after bytecode evidence are retained in `ARESLib-Kotlin/build/audit-pass120-verified-evidence/`.

## Limits and follow-up

Phoenix's shared `waitForAllImpl` still creates its own JNI argument array and performs vendor
work. Its reset API also refreshes a vendor signal. This change does not establish zero allocation
for hardware refresh or certify sensor freshness, CAN behavior, motor safety, or a 20 ms deadline.
No HIL execution occurred. Remaining output, homing/configuration and close paths require separate
review and appropriate device-boundary tests; the ledger retains partial status for these files.

ARESLib is unchanged at candidate `17.0.3-rc.100852e472fb`, tree
`100852e472fbeeba64fdf799665f51b4687f7f1b`. No push, merge or release occurred.
