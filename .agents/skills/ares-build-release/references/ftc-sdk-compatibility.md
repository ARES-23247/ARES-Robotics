# FTC SDK changes

For an FTC SDK update, compare the official release and source contracts with both FTC products,
the published hardware module, mocks, controller source/manifest, wrappers, and bundled templates.
Preserve ARES build customizations when importing official controller sources. Matching a version
string alone does not establish API or behavior compatibility.

Check Kotlin's D8/R8 compatibility independently of the official SDK's AGP pin. A successful APK
task with Kotlin-metadata rewrite errors is incomplete evidence. Use the documented R8 override
when required by the [Android compatibility table](https://developer.android.com/build/kotlin-support),
then confirm the loaded compiler and rebuild without those errors. Do not suppress the diagnostics.

`ftc-hardware` production compilation uses real SDK AAR classes. Its test suite includes
`FtcSdkBinaryCompatibilityTest`, which checks production bytecode member references against desktop
mocks. Keep SDK fields as fields and preserve JVM descriptors, static members, and interface/class
shape. Do not restore mock-only production compilation to bypass errors. Run module and simulator
regressions after mock changes, then validate Android consumers against the new library candidate.

Review SDK mutations that invalidate a decorator's command cache. Servo direction and range changes
alter the logical-to-controller mapping; invalidate before delegating, including partial failures.
Exercise actual SDK implementations as well as mock call counters. Measure steady-state allocations
and accepted/suppressed writes separately from configuration; desktop measurements are not bus timing.

Sensor reset/close must invalidate cached motion and timestamps and fence late reads from an earlier
lifecycle. Keep SDK I/O outside the short cache lock so close can finish while a device call is blocked.
Preserve owned legacy action snapshots when removing intermediate actions from input-container refresh.
Output-parameter SDK APIs can still allocate internally: inspect actual driver bytecode and measure
wrapper allocations separately from SDK/transport allocations before claiming zero-GC behavior.

For vision changes, check singleton/cluster subtypes, null poses, distance and angle units,
capture-time conversion, frame identity, camera mounting, coordinate bases, and stale/disconnect
clearing. Exercise the actual Redux consumer as well as adapter output. Absolute localization
must use an explicit tag-pose convention: maintained FTC layouts use optical tag rotations,
while WPILib/PhotonVision use outward/left/up tag axes. Include a maintained field's actual
rotations in the regression fixtures; preserve its stored data and convert at the boundary.
`RobotFieldAprilTag.roll/pitch/yaw` are stored in degrees, while camera-mount `Rotation3d` angles
and internal headings use radians. Preserve those distinct input units when authoring fixtures.
Native moving clusters
belong to targeting state; they must not seed or update absolute localization, including mixed
frames. VisionPortal localization requires explicit stationary IDs and measured mounting. See
[the runtime migration guide](../../../../ARESLib-Kotlin/docs/ftc-sdk12.md) for the current contract.

Follow the library version/source-tree and candidate order in the main build skill. Generate new
template versions for changed sources; never replace bytes under an existing archive/version.
Test fresh exported FTC projects (generation, unit tests, simulation, Android assembly) and Studio's
archive consumption. Keep Android builds, desktop simulation, and physical controller/camera
results distinct. Hardware absence limits the final claim; do not weaken enable or deployment gates.

Source-copy acceptance fixtures must include the standalone export's shared version policy,
dependency manifest, and canonical FTC runtime. Assert mechanical output under `build/generated/ares`
after normal project generation/build. `generateSubsystemStarters` previews an explicit editable-source
operation; its successful plan does not establish that editable files were written. Immutable unique
candidates do not need a forced dependency refresh for every generation/build phase.
For representative-project failures, set `ARES_REPRESENTATIVE_OUTPUT_DIR` to a new directory to
retain the generated sources, child build log, and full JUnit reports; inspect the actual child
failure before changing assertions. Default runs still clean their temporary workspace.

The normal Studio `:app:test` excludes the real Android consumer round-trip classes; run
`:app:consumerRoundtripTest` for export/reopen/edit/build, generated tuning/IO, and interrupted
generation recovery. A full unit-suite pass does not establish that boundary.
