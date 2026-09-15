# FRC field loader boundary audit - pass 126

Reviewed both complete pending field tests (`FrcFieldContractLoaderTest` and
`MarvinConfigFieldGeometryTest`) and the field-contract loader section of
`ARESRobot.kt`. The broader robot lifecycle remains partially reviewed.

Added six parameterized malformed-document cases through the actual FRC loader:
broken JSON, negative field width, duplicate tag IDs, invalid tag ID, numeric
overflow and unsupported future schema. Each sequence first loads valid data,
checks rejection and a diagnostic for the invalid document, then checks successful
recovery and clearing of the diagnostic on a new valid load. All six passed.

Added a complete orientation conversion case with nonzero roll, pitch and yaw,
tag height and explicit field dimensions. It verifies degree-to-radian conversion
through the resulting WPILib pose. The tag is slightly outside the playable field
rectangle, which is legitimate for field-mounted tags; the loader preserves it.
The existing tests cover missing tags, wrong league and a basic yaw conversion.

The geometry tests consistently connect generated bumper dimensions to season
configuration and shot-origin offsets. Their inch-to-meter target arithmetic is
correct (651.25 inches = 16.54175 m; 218.42 inches = 5.547868 m). This pass checked
the assertions and their current consumers; it did not independently survey the
physical field or re-establish the tests' original drawing provenance.

The shared decoder validates the document and the public WPILib layout factory
validates again. This is startup work, outside periodic control. Both boundaries
are callable independently; bypassing validation to remove this small duplication
would require a different trusted-input API and offers no demonstrated loop-time
benefit. Left the validation intact. The season loader's shared last-error field
is suitable for its observed sequential startup use; concurrent callers were not
certified by these tests.

No new runtime defect was found. Focused tests passed (11 invocations); generated
project verification and the full FRC suite passed (296 tests, zero failures,
errors or skips). Policy, documentation links and staged whitespace checks passed.
Local logs, XML and file hashes are retained under
`ARESLib-Kotlin/build/audit-pass126-verified-evidence/`.

This test-only change required no new library candidate, consumer matrix or
allocation benchmark. Tests use real parsing/layout conversion but no camera,
native GUI, physical robot, push, merge or release.
