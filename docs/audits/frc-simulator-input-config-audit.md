# FRC simulator input configuration audit - pass 124

Reviewed the complete controller descriptor and four saved simulator files:
`frc-driver.arescontroller`, `simgui-ds.json`, `simgui.json`, `simgui-window.json`
and `networktables.json`. These are configuration checks, not a rendered GUI test.

## Confirmed issues and fixes

The controller descriptor mapped axes 0, 1, 4 and 5 but accepted devices with only
four axes. Its matcher now requires six axes. Its eight-button minimum remains
conservative relative to the four declared button mappings; IDs, mappings and
anchors otherwise remain unchanged.

Keyboard0, assigned to robot port 0, exposed only axes 0 through 2. Xbox rotation
reads axis 4, so the saved keyboard layout could translate but could not supply
that rotation input. Keyboard0 now exposes six axes, with Q/F decreasing/increasing
axis 4 and positive decay returning it to zero after release. Axes 3 and 5 are
unbound. Existing WASD translation, E/R throttle on axis 2, buttons and POV bindings
remain intact. Q/F do not overlap the other Keyboard0 bindings or simulator
enable/disable/emergency-stop shortcuts. This does not make Keyboard0 identify as
an Xbox device or satisfy the separate descriptor's name/button matcher.

Verified the configuration format against the pinned
[WPILib 2026.2.1 simulator implementation](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.1/simulation/halsim_gui/src/main/native/cpp/DriverStationGui.cpp):
it uses GLFW key codes, limits exposed axes to the configured array/count, and
defaults key/decay rates to 0.05 per GUI update. These rates are per frame, not
physical robot loop timing guarantees. A local source snapshot is retained with
the evidence. No upstream WPILib change was necessary.

The other keyboard arrays/counts are consistent. NetworkTables persistence is
empty; the saved NT display metadata contains no actuator settings. Window sizes,
scale and frame limit are positive. Saved window preferences alone cannot prove
visibility on another display. The legacy `marvin19_layout.json` viewer preset
has unresolved topic/schema questions and is not counted as completed by this pass.

## Validation

Three focused regression tests initially produced two failures, reproducing the
axis-count mismatches. After the fixes, generated-project verification and the
full FRC suite passed: 288 tests, zero failures/errors/skips. The tests verify
descriptor mapping bounds, usable independent rotation bindings with positive
decay, complete joystick arrays and saved configuration structure. They do not
simulate native key events or establish physical joystick behavior.

No robot loop implementation or library dependency changed, so no additional
allocation benchmark or consumer matrix was run. Monorepo policy and documentation
links passed. Before/after XML, build output, pinned source hash and reviewed-file
hashes are retained in `ARESLib-Kotlin/build/audit-pass124-verified-evidence/`.
Changes remain local; no GUI, hardware, push, merge or release was performed.
