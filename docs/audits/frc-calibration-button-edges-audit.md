# FRC calibration button edges

Pass 179, 2026-09-11. Local audit against candidate `17.0.3-rc.100852e472fb`.

The calibration wrapper masked Back/Start before tracking their edges. When the
homing combination ended with either button still held, the mask changed from false
to true and manufactured a fresh calibration command. This could zero surveyed truth
or queue a pose seed without another press. Track the physical edge first, then
suppress the action during homing. A held button must now be released and pressed again.
The change retains the existing BooleanArray and adds no loop allocations.

Three new tests use WPILib HAL controller simulation and the real calibration session
with a temporary recorder directory. Two failed before the fix: Back changed truth X
from 2 to 0, and Start unexpectedly made an action pending. All three passed after the
fix. They also exercise one action per held button/D-pad press and cancellation of a
queued checkpoint by a truth edit. Full-suite results are recorded in the local
`ARESLib-Kotlin/build/audit-pass179-verified-evidence/summary.json`.

The production Test-mode caller and session request/dwell/cancellation paths were
traced. The wrapper remains partially covered: these tests do not exhaust every
button mapping, vision-fusion switching, or Test-mode transitions. The pose seed is
checked as a queued request; no physical robot, camera, or actual motor output was
validated. ARESLib and dependency identity are unchanged. Nothing was pushed or released.
