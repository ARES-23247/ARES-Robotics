# XRP subsystem control and sampling audit

Pass 9 audits the basic controller math, stop/recovery state, typed targets, loop-output
validation, and repeated measurement reads in `ares_micro/subsystem.py`. Source commit:
`99811ddd`. The subsystem remains **partially reviewed** because advanced descriptor
behavior is still incomplete. High execution coverage does not establish feature parity.

## Findings and fixes

- Stop and neutral recovery previously retained integral and previous-error state. The
  next enabled cycle could therefore inherit integral output or a derivative kick. Stop
  now resets integral, derivative, previous-error, and bang-bang state, including when
  an output failure triggers cleanup.
- The integral accumulated through saturation and even when `kI` was zero. A candidate
  integral now commits only while unsaturated or when its output contribution moves
  back toward the permitted interval. The test uses the direction of `kI * error`, so
  negative integral gains are handled correctly. Zero integral gain skips accumulation.
- Continuous position error wrapped, but its derivative did not. The derivative now
  uses the shortest wrapped error delta. The declared derivative filter is implemented
  with `alpha = dt / (tau + dt)`; its canonical default is 0.02 seconds. Stop removes
  derivative history, and zero derivative gain skips derivative computation.
- Bang-bang was positive-only and ignored hysteresis. It now supports both signs, a
  separate restart threshold, and one neutral tick before reversal. A one-sided output
  interval cannot command the opposite direction. Neutral remains zero even when an
  active-output interval excludes it.
- Python `min`/`max` could conceal invalid controller numbers as saturated commands.
  Timing, gains, limits, intermediate control values, and target types now validate before
  output. Canonical omitted output limits are -12..12, and omitted continuous-input
  bounds are -pi..pi. Unknown strategies fail rather than falling through to PID.
- A later invalid loop could be discovered after an earlier actuator had already received
  nonzero output. Controller results are now prepared in a reusable output list before
  any loop output is written. A physical write failure still attempts all neutral outputs;
  separate hardware writes are not claimed to be atomic.
- Multiple mappings of one raw source previously performed multiple hardware reads.
  Device/source groups and field lookup are compiled once. Each raw source is read once
  per cycle, then each mapping applies its own scale, offset, and validity bounds.
- A factory returning `None` could appear healthy. It is now treated as a failed adapter.
  Missing actuator or feedback dependencies make the subsystem unconfigured, including
  feedback from an optional device referenced by a loop. Recovery cannot replace absent
  hardware with a default measurement value.

The existing descriptor and Kotlin controller renderer were used to establish defaults,
angular wrapping, derivative filtering, and bang-bang semantics. This is a source
comparison, not evidence that the Kotlin renderer itself is fully reviewed or correct.

## Validation

The shared Python suite passes **101 tests**, including **16 new regression methods**.
Running the final regression suite against the pre-fix `4026ab29` subsystem produced
**41 failing assertions and no errors**; the same final suite passes with these fixes.
Checks include one-sided bang-bang limits, missing optional feedback, and omitted
canonical defaults. Existing stop, transport, robot, autonomous, and hardware boundary
tests remain green.

The sampling regression runs two cycles with two differently transformed mappings of
one raw source. It observes exactly **two reads rather than four**, with both mapped
values refreshed correctly. The output-order regression confirms that an invalid second
controller produces no nonzero write to the earlier actuator. No timing speedup or
physical IO latency claim is made; numeric validation also adds CPU work.

Source/generated XRP verification and the independently extracted standalone starter
each pass **107 tests**. The import-inclusive Python trace reports **94.9% executable-line
coverage** for the current subsystem file and **99.5%** for the new regression file.
These are not branch coverage or coverage of features absent from the implementation.
Annotated sources are under `ARESLib-Kotlin/ares-micro/build/audit-pass9-trace/`.

The library test/API/local-publication gate passed for candidate
`17.0.3-rc.733b68c0939b`, source tree `733b68c0939b1b194338cf0e70a0a164bd9138ee`.
The unpublished XRP 3.0.3 archive SHA-256 is
`57a72bffe1c8565210e3e74a73f0c677fe0ec3ba769a815f2eb7262935d2a4df`.
The other three deterministic archives reproduced unchanged. FTC, FRC, both robot
starters, and Studio passed their candidate gates in dependency order. Studio's app suite
executed **1,209 passing tests with six opt-in skips**; shared/gateway gates reused their
35 passing results. Unchanged JVM library/simulator/FRC tests reused up-to-date results;
FTC TeamCode tests executed. The green gates do not imply that every JVM test executed
again. Release alignment, source/archive policy, guidance, and links in 170 current
documents passed.

Evidence logs are `ARESLib-Kotlin/build/audit-pass9-*.log`,
`ARES-XRP-Starter/build/audit-pass9-verify.log`, and
`.codex-validation/audit-pass9-standalone/audit-pass9-standalone.log`.
All changes remain local; no release or device deployment occurred.

## Remaining work

Profiled position currently still executes ordinary PID; profile constraints are not yet
implemented. Feedforward declarations and other advanced descriptor safety behavior also
remain open. The next pass must implement and test those contracts, then cover feedback
freshness/typing, disabled sampling, followers and descriptor validation. It must not
silently replace promised profile/feedforward behavior with a smaller feature set.

The Kotlin renderer needs corresponding edge-case tests for negative integral gains and
one-sided bang-bang intervals; only the relevant emitted-math sections were inspected here.
Startup duplicate identities, malformed structural declarations, and some cleanup-error
branches also need further coverage. Descriptor topology is fixed at subsystem creation;
the cached grouping is not a live structural-reconfiguration mechanism.

These checks run on CPython with fake adapters. Pico arithmetic, real actuator response,
sensor latency, heap pressure, and loop jitter remain unmeasured. The whole-repository
goal stays active, and the subsystem source does not receive full-review completion credit.
